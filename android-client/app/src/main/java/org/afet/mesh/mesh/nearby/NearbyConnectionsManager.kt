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

    val deviceModelName: String =
        "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
    val endpointDisplayName: String = "$deviceModelName ($localNodeId)"

    // Aktif bağlantılar: endpointId → PeerEntity
    private val activeConnections = mutableMapOf<String, PeerEntity>()
    private val pendingEndpointNames = mutableMapOf<String, String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Bağlı endpoint sayısının reaktif akışı (UI için)
    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount

    // ── Bağlantı Yaşam Döngüsü Callback ──────────────────────────────
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "🔗 Bağlantı başlatıldı: $endpointId (${info.endpointName})")
            pendingEndpointNames[endpointId] = info.endpointName
            // Güven kararı vermeden hemen kabul et (Afet ortamı: açık ağ)
            Nearby.getConnectionsClient(context)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "✅ Bağlantı kuruldu: $endpointId")
                    val epName = pendingEndpointNames.remove(endpointId) ?: ""
                    val model = parseDeviceModel(epName)
                    val peer = PeerEntity(
                        endpointId = endpointId,
                        nodeId = extractNodeIdFromEndpointName(if (epName.isNotBlank()) epName else endpointId),
                        role = "COURIER",
                        deviceModel = model,
                        deviceName = epName
                    )
                    activeConnections[endpointId] = peer
                    _connectedPeerCount.value = activeConnections.size

                    // Bağlantı kurulur kurulmaz Anti-Entropy Sync başlat
                    scope.launch { performAntiEntropySync(endpointId) }

                    scope.launch { peerDao.upsertPeer(peer) }
                }
                else -> {
                    Log.w(TAG, "Bağlantı başarısız: $endpointId — ${result.status}")
                    pendingEndpointNames.remove(endpointId)
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
            endpointDisplayName, // Model ve kısa ID
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "📡 BLE Advertising başladı: $endpointDisplayName")
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
                    val parsedModel = parseDeviceModel(info.endpointName)
                    scope.launch {
                        peerDao.upsertPeer(
                            PeerEntity(
                                endpointId = endpointId,
                                nodeId = extractNodeIdFromEndpointName(info.endpointName),
                                role = "COURIER",
                                deviceModel = parsedModel,
                                deviceName = info.endpointName
                            )
                        )
                    }
                    // Bağlantı isteği gönder
                    Nearby.getConnectionsClient(context).requestConnection(
                        endpointDisplayName,
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
    private suspend fun performAntiEntropySync(endpointId: String) {
        val myInventory = epidemicRouter.getLocalInventory()

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
     * - PACKET mesajı ise → aç, modeli ve konumu oku, EpidemicRouter'a ve Room'a yaz
     * - ACK mesajı ise → teslimat kaydı güncelle
     */
    private suspend fun handleIncomingPayload(endpointId: String, bytes: ByteArray) {
        val text = bytes.decodeToString()

        when {
            text.startsWith("{\"type\":\"DIGEST\"") -> {
                val peerIds = parseDigestIds(text)
                val packetsToSend = epidemicRouter.getPacketsToSendToPeer(peerIds)

                Log.d(TAG, "← Digest alındı: ${peerIds.size} ID | Göndereceğim: ${packetsToSend.size} paket")

                for (packet in packetsToSend.take(MAX_PACKETS_PER_ENCOUNTER)) {
                    val envelope = buildPacketEnvelope(packet.messageId, packet.rawProtoBytes)
                    sendBytesToEndpoint(endpointId, envelope)
                    delay(50)
                }
            }

            text.startsWith("{\"type\":\"PACKET\"") -> {
                Log.i(TAG, "← Paket alındı: ${bytes.size} byte")
                val newlineIdx = bytes.indexOf('\n'.code.toByte())
                if (newlineIdx != -1) {
                    val headerJson = bytes.copyOfRange(0, newlineIdx).decodeToString()
                    val payloadBytes = bytes.copyOfRange(newlineIdx + 1, bytes.size)
                    val payloadStr = payloadBytes.decodeToString()

                    val msgId = parseJsonField(headerJson, "id") ?: java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                    val msg = parseJsonField(payloadStr, "msg") ?: "Acil Durum / SOS"
                    val model = parseJsonField(payloadStr, "model") ?: parseDeviceModel(activeConnections[endpointId]?.deviceName ?: "Bilinmeyen Cihaz")
                    val senderId = parseJsonField(payloadStr, "senderId") ?: extractNodeIdFromEndpointName(endpointId)
                    val lat = parseJsonDouble(payloadStr, "lat") ?: 0.0
                    val lon = parseJsonDouble(payloadStr, "lon") ?: 0.0
                    val time = parseJsonLong(payloadStr, "time") ?: System.currentTimeMillis()

                    epidemicRouter.receivePacket(
                        messageId = msgId,
                        rawProtoBytes = payloadBytes,
                        priority = 1,
                        ttl = 10,
                        hopCount = 0,
                        latitude = lat,
                        longitude = lon,
                        senderId = senderId,
                        timestampEpoch = time / 1000,
                        senderDeviceModel = model,
                        messageText = msg
                    )
                }
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

    private fun parseJsonField(json: String, key: String): String? {
        val searchKey = "\"$key\":\""
        val start = json.indexOf(searchKey)
        if (start < 0) return null
        val valStart = start + searchKey.length
        val valEnd = json.indexOf('"', valStart)
        return if (valEnd > valStart) json.substring(valStart, valEnd) else null
    }

    private fun parseJsonDouble(json: String, key: String): Double? {
        val searchKey = "\"$key\":"
        val start = json.indexOf(searchKey)
        if (start < 0) return null
        val valStart = start + searchKey.length
        val comma = json.indexOf(',', valStart)
        val brace = json.indexOf('}', valStart)
        val end = when {
            comma > 0 && brace > 0 -> minOf(comma, brace)
            comma > 0 -> comma
            brace > 0 -> brace
            else -> json.length
        }
        return json.substring(valStart, end).trim().toDoubleOrNull()
    }

    private fun parseJsonLong(json: String, key: String): Long? {
        val searchKey = "\"$key\":"
        val start = json.indexOf(searchKey)
        if (start < 0) return null
        val valStart = start + searchKey.length
        val comma = json.indexOf(',', valStart)
        val brace = json.indexOf('}', valStart)
        val end = when {
            comma > 0 && brace > 0 -> minOf(comma, brace)
            comma > 0 -> comma
            brace > 0 -> brace
            else -> json.length
        }
        return json.substring(valStart, end).trim().toLongOrNull()
    }

    private fun parseDeviceModel(endpointName: String): String {
        val parenIdx = endpointName.lastIndexOf('(')
        return if (parenIdx > 0) {
            endpointName.substring(0, parenIdx).trim()
        } else {
            endpointName.ifBlank { "Bilinmeyen Cihaz" }
        }
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
