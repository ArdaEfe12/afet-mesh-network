package org.afet.mesh.mesh.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.afet.mesh.data.local.PeerDao
import org.afet.mesh.data.local.PeerEntity
import org.afet.mesh.mesh.routing.EpidemicRouter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════
 * NEARBY CONNECTIONS YÖNETİCİSİ (Google P2P_CLUSTER Mesh Katmanı)
 *
 * Bu sınıf:
 *  1. BLE Advertising (Yayın) ve BLE Discovery (Tarama) döngüsünü yönetir
 *  2. İki cihaz kapsama alanına girince otomatik bağlantı kurar
 *  3. Anti-Entropy Digest takası yapar
 *  4. Karşı cihazın bilmediği paketleri aktarır (Epidemic Sync)
 *  5. Bağlantı bitince kaynakları serbest bırakır (Pil tasarrufu)
 *
 * Strateji: P2P_CLUSTER — Her cihaz hem Advertiser hem Discoverer.
 * Gerçek dünya: BLE keşif ≤ 15m, Wi-Fi Direct aktarım ≤ 80m
 * ═══════════════════════════════════════════════════════════════════════
 */
@Singleton
class NearbyConnectionsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epidemicRouter: EpidemicRouter,
    private val peerDao: PeerDao
) {
    companion object {
        private const val TAG = "NearbyConnMgr"
        /** SERVICE_ID — Afet Mesh uygulamasına ait unique kimlik. */
        private const val SERVICE_ID = "org.afet.mesh.DISASTER_MESH_V1"
        /** Her bir karşılaşmada aktarılacak maksimum paket sayısı. */
        private const val MAX_PACKETS_PER_ENCOUNTER = 5
    }

    // Cihazın kendine ait kısa Node ID (ısı yönetimi ve deduplication için)
    var localNodeId: String = generateShortNodeId()
        private set

    // Aktif bağlantılar: endpointId → PeerEntity
    private val activeConnections = mutableMapOf<String, PeerEntity>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Bağlı endpoint sayısının reaktif akışı (UI için)
    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount

    // ── Bağlantı Yaşam Döngüsü Callback ──────────────────────────────
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "🔗 Bağlantı başlatıldı: $endpointId (${info.endpointName})")
            // Güven kararı vermeden hemen kabul et (Afet ortamı: açık ağ)
            // Güvenlik: Paket içeriği zaten asimetrik şifreli; aktarıcı okuyamaz
            Nearby.getConnectionsClient(context)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "✅ Bağlantı kuruldu: $endpointId")
                    val peer = PeerEntity(
                        endpointId = endpointId,
                        nodeId = extractNodeIdFromEndpointName(endpointId),
                        role = "COURIER" // Default; Anti-Entropy Digest ile güncellenir
                    )
                    activeConnections[endpointId] = peer
                    _connectedPeerCount.value = activeConnections.size

                    // Bağlantı kurulur kurulmaz Anti-Entropy Sync başlat
                    scope.launch { performAntiEntropySync(endpointId) }

                    scope.launch { peerDao.upsertPeer(peer) }
                }
                else -> {
                    Log.w(TAG, "Bağlantı başarısız: $endpointId — ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "🔌 Bağlantı kesildi: $endpointId")
            activeConnections.remove(endpointId)
            _connectedPeerCount.value = activeConnections.size
        }
    }

    // ── Payload (Veri) Callback ───────────────────────────────────────
    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return

            scope.launch {
                handleIncomingPayload(endpointId, bytes)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Transfer tamamlandı bilgisi (isteğe bağlı loglama)
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload aktarımı tamamlandı → $endpointId")
                scope.launch { peerDao.incrementExchangeCount(endpointId) }
            }
        }
    }

    // ── Reklam (Advertising) Başlat ──────────────────────────────────
    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        Nearby.getConnectionsClient(context).startAdvertising(
            localNodeId,       // Endpoint adı = kısa Node ID
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "📡 BLE Advertising başladı: $localNodeId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising başlatılamadı: ${e.message}")
        }
    }

    // ── Keşif (Discovery) Başlat ──────────────────────────────────────
    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        Nearby.getConnectionsClient(context).startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.i(TAG, "🔍 Yakın cihaz bulundu: $endpointId (${info.endpointName})")
                    // Bağlantı isteği gönder
                    Nearby.getConnectionsClient(context).requestConnection(
                        localNodeId,
                        endpointId,
                        connectionLifecycleCallback
                    )
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d(TAG, "Cihaz kapsama dışına çıktı: $endpointId")
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "🔍 BLE Discovery başladı")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery başlatılamadı: ${e.message}")
        }
    }

    // ── Anti-Entropy Senkronizasyonu ──────────────────────────────────
    /**
     * Bağlantı kurulur kurulmaz:
     * 1. Kendi envanter ID listesini karşıya gönder
     * 2. Karşı tarafın envanter listesini bekle (handleIncomingPayload içinde)
     * 3. Eksik paketleri aktar
     */
    private suspend fun performAntiEntropySync(endpointId: String) {
        val myInventory = epidemicRouter.getLocalInventory()

        // Envanter paketini JSON-compact olarak gönder
        // Üretim: Protobuf AntiEntropyDigest kullan
        val digestJson = buildString {
            append("{\"type\":\"DIGEST\",\"nodeId\":\"$localNodeId\",\"ids\":[")
            append(myInventory.joinToString(",") { "\"$it\"" })
            append("]}")
        }

        sendBytesToEndpoint(endpointId, digestJson.toByteArray())
        Log.d(TAG, "Anti-Entropy Digest gönderildi → $endpointId (${myInventory.size} ID)")
    }

    /**
     * Gelen ham veriyi işle:
     * - DIGEST mesajı ise → karşı cihazın bilmediği paketleri gönder
     * - PACKET mesajı ise → EpidemicRouter'a ilet
     * - ACK mesajı ise → teslimat kaydı güncelle
     */
    private suspend fun handleIncomingPayload(endpointId: String, bytes: ByteArray) {
        val text = bytes.decodeToString()

        when {
            text.startsWith("{\"type\":\"DIGEST\"") -> {
                // Anti-Entropy Digest alındı — karşı cihazın ID listesini parse et
                val peerIds = parseDigestIds(text)
                val packetsToSend = epidemicRouter.getPacketsToSendToPeer(peerIds)

                Log.d(TAG, "← Digest alındı: ${peerIds.size} ID | Göndereceğim: ${packetsToSend.size} paket")

                for (packet in packetsToSend.take(MAX_PACKETS_PER_ENCOUNTER)) {
                    val envelope = buildPacketEnvelope(packet.messageId, packet.rawProtoBytes)
                    sendBytesToEndpoint(endpointId, envelope)
                    delay(50) // BLE akışını boğmamak için küçük gecikme
                }
            }

            text.startsWith("{\"type\":\"PACKET\"") -> {
                // Gerçek SOS/Mesh paketi alındı
                // Üretim: Ham Protobuf MeshPacket baytı → PacketDecoder'a ver
                Log.d(TAG, "← Paket alındı: ${bytes.size} byte")
                // TODO: Aşama 3'te Protobuf Decoder burada çağrılacak
            }

            text.startsWith("{\"type\":\"ACK\"") -> {
                val ackedId = parseAckedId(text)
                if (ackedId != null) {
                    epidemicRouter.handleAck(ackedId)
                }
            }
        }
    }

    // ── Yardımcı Metotlar ─────────────────────────────────────────────

    private fun sendBytesToEndpoint(endpointId: String, bytes: ByteArray) {
        Nearby.getConnectionsClient(context)
            .sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun buildPacketEnvelope(messageId: String, rawProto: ByteArray): ByteArray {
        val prefix = "{\"type\":\"PACKET\",\"id\":\"$messageId\",\"len\":${rawProto.size}}\n".toByteArray()
        return prefix + rawProto
    }

    private fun parseDigestIds(json: String): List<String> {
        val idsStart = json.indexOf("\"ids\":[") + 7
        val idsEnd = json.lastIndexOf("]")
        if (idsStart < 7 || idsEnd < 0) return emptyList()
        val idsStr = json.substring(idsStart, idsEnd)
        return idsStr.split(",").map { it.trim('"', ' ') }.filter { it.isNotBlank() }
    }

    private fun parseAckedId(json: String): String? {
        val key = "\"ackedId\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val idStart = start + key.length
        val idEnd = json.indexOf('"', idStart)
        return if (idEnd > idStart) json.substring(idStart, idEnd) else null
    }

    private fun generateShortNodeId(): String =
        (1..6).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")

    private fun extractNodeIdFromEndpointName(endpointId: String): String =
        endpointId.take(6).uppercase()

    fun stopAll() {
        Nearby.getConnectionsClient(context).apply {
            stopAdvertising()
            stopDiscovery()
            stopAllEndpoints()
        }
        scope.cancel()
        Log.i(TAG, "Nearby Connections durduruldu.")
    }
}
