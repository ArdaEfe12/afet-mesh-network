package org.afet.mesh.mesh.nearby

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.afet.mesh.data.local.PeerDao
import org.afet.mesh.data.local.PeerEntity
import org.afet.mesh.mesh.routing.EpidemicRouter
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════
 * NEARBY CONNECTIONS YÖNETİCİSİ (Google P2P_CLUSTER Mesh Katmanı)
 *
 * Bu sınıf:
 *  1. BLE Advertising (Yayın) ve BLE Discovery (Tarama) döngüsünü yönetir
 *  2. İki cihaz kapsama alanına girince çakışmasız (tie-breaker) otomatik bağlantı kurar
 *  3. Yeni bağlanan cihaza bekleyen acil durum paketlerini derhal gönderir
 *  4. Anti-Entropy Digest takası ve periyodik senkronizasyon yapar
 *  5. Alınan paketleri Room DB'ye yazar ve yüksek öncelikli Acil Durum Bildirimi fırlatır
 *  6. Mesh Gossip: Paketi diğer komşu cihazlara ileterek ağ boyunca yayar
 *
 * Strateji: P2P_CLUSTER — Her cihaz hem Advertiser hem Discoverer.
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
        /** SERVICE_ID — Her iki cihazda da eşleşen standart servis kimliği. */
        private const val SERVICE_ID = "org.afet.mesh"
        /** Her bir karşılaşmada aktarılacak maksimum paket sayısı. */
        private const val MAX_PACKETS_PER_ENCOUNTER = 10
    }

    // Cihazın kendine ait kısa Node ID'si
    var localNodeId: String = generateShortNodeId()
        private set

    val deviceModelName: String =
        "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"

    // BLE reklam paketi boyutuna sığması için en fazla 32 karakter
    val endpointDisplayName: String = "$deviceModelName ($localNodeId)".take(32)

    // Aktif ve süreçteki bağlantıların güvenli takibi
    private val activeConnections = ConcurrentHashMap<String, PeerEntity>()
    private val pendingEndpointNames = ConcurrentHashMap<String, String>()
    private val connectingEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val discoveredEndpoints = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicSyncJob: Job? = null

    private var isAdvertising = false
    private var isDiscovering = false

    // Bağlı endpoint sayısının reaktif akışı (UI için)
    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount

    // ── Bağlantı Yaşam Döngüsü Callback ──────────────────────────────
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "🔗 Bağlantı başlatıldı: $endpointId (${info.endpointName})")
            pendingEndpointNames[endpointId] = info.endpointName

            // Afet anında güvenlik şifresi bekletmeden hemen kabul et
            Nearby.getConnectionsClient(context)
                .acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.i(TAG, "Bağlantı kabul edildi -> $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Bağlantı kabul başarısız ($endpointId): ${e.message}")
                    connectingEndpoints.remove(endpointId)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectingEndpoints.remove(endpointId)
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
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
                    Log.i(TAG, "✅ BAĞLANTI KURULDU: $endpointId ($epName) — Toplam aktif bağlı: ${activeConnections.size}")

                    // 1. Yeni bağlanan cihaza elimizdeki acil durum paketlerini anında doğrudan aktar
                    scope.launch {
                        sendAllPendingDirectly(endpointId)
                        performAntiEntropySync(endpointId)
                    }

                    scope.launch { peerDao.upsertPeer(peer) }
                }
                ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> {
                    Log.i(TAG, "Zaten bağlı: $endpointId")
                }
                else -> {
                    val statusStr = ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)
                    Log.w(TAG, "Bağlantı kurulamadı: $endpointId — $statusStr (${result.status.statusCode})")
                    pendingEndpointNames.remove(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "🔌 Bağlantı kesildi: $endpointId")
            activeConnections.remove(endpointId)
            connectingEndpoints.remove(endpointId)
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
                scope.launch { peerDao.incrementExchangeCount(endpointId) }
            }
        }
    }

    // ── Keşif Callback ────────────────────────────────────────────────
    private val discoveryCallback = object : EndpointDiscoveryCallback() {
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

            if (activeConnections.containsKey(endpointId) || connectingEndpoints.contains(endpointId)) {
                return
            }

            val myName = endpointDisplayName
            val otherName = info.endpointName
            discoveredEndpoints[endpointId] = System.currentTimeMillis()

            // ── Çakışma Önleyici Tie-Breaker ──
            // İki cihaz aynı anda keşfettiğinde ikisinin de aynı anda requestConnection()
            // çağırması Google Play Services'de çarpışmaya (STATUS_CONNECTION_REJECTED) yol açar.
            // Bu nedenle alfabetik olarak büyük olan taraf istek başlatır, küçük olan dinler.
            if (myName > otherName) {
                Log.i(TAG, "👑 Bağlantı inisiyatifi bizde ($myName > $otherName) → İstek gönderiliyor: $endpointId")
                connectingEndpoints.add(endpointId)
                Nearby.getConnectionsClient(context).requestConnection(
                    endpointDisplayName,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    Log.w(TAG, "requestConnection başarısız ($endpointId): ${e.message}")
                    connectingEndpoints.remove(endpointId)
                }
            } else {
                Log.i(TAG, "⏳ Karşı cihazın bağlanması bekleniyor ($myName <= $otherName) → $endpointId")
                // Fallback: 4 saniye içinde karşıdan bağlantı gelmezse biz deneriz
                scope.launch {
                    delay(4000)
                    if (!activeConnections.containsKey(endpointId) && connectingEndpoints.add(endpointId)) {
                        Log.i(TAG, "⏱️ Fallback süresi doldu, biz bağlanıyoruz: $endpointId")
                        Nearby.getConnectionsClient(context).requestConnection(
                            endpointDisplayName,
                            endpointId,
                            connectionLifecycleCallback
                        ).addOnFailureListener {
                            connectingEndpoints.remove(endpointId)
                        }
                    }
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Cihaz kapsama dışına çıktı: $endpointId")
            discoveredEndpoints.remove(endpointId)
            connectingEndpoints.remove(endpointId)
        }
    }

    // ── Mesh Başlatma ve Yönetimi ────────────────────────────────────
    fun startMesh() {
        Log.i(TAG, "▶ startMesh() çağrıldı: Radyolar ve periyodik senkronizasyon başlatılıyor...")
        startAdvertising()
        startDiscovery()
        startPeriodicSync()
    }

    fun startAdvertising() {
        if (isAdvertising) return
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        Nearby.getConnectionsClient(context).startAdvertising(
            endpointDisplayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            isAdvertising = true
            Log.i(TAG, "📡 BLE Advertising başladı: $endpointDisplayName")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising başlatılamadı: ${e.message}")
            if (e is com.google.android.gms.common.api.ApiException &&
                e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                isAdvertising = true
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        Nearby.getConnectionsClient(context).startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            isDiscovering = true
            Log.i(TAG, "🔍 BLE Discovery başladı")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery başlatılamadı: ${e.message}")
            if (e is com.google.android.gms.common.api.ApiException &&
                e.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                isDiscovering = true
            }
        }
    }

    // ── Anında Paket Yayınlama (Broadcast) ─────────────────────────────
    fun broadcastPacket(messageId: String, rawProtoBytes: ByteArray) {
        val envelope = buildPacketEnvelope(messageId, rawProtoBytes)
        val peers = activeConnections.keys.toList()
        Log.i(TAG, "📡 broadcastPacket: $messageId -> ${peers.size} bağlı komşuya anında fırlatılıyor")
        for (endpointId in peers) {
            sendBytesToEndpoint(endpointId, envelope)
        }
    }

    // ── Yeni Bağlantıya Bekleyen Tüm Paketleri Doğrudan Aktar ─────────
    private suspend fun sendAllPendingDirectly(endpointId: String) {
        try {
            val pending = epidemicRouter.getAllPendingPackets()
            if (pending.isNotEmpty()) {
                Log.i(TAG, "🚀 Yeni bağlı $endpointId cihazına bekleyen ${pending.size} paket aktarılıyor")
                for (packet in pending) {
                    val envelope = buildPacketEnvelope(packet.messageId, packet.rawProtoBytes)
                    sendBytesToEndpoint(endpointId, envelope)
                    delay(80)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAllPendingDirectly hatası: ${e.message}")
        }
    }

    // ── Periyodik Senkronizasyon Döngüsü ──────────────────────────────
    private fun startPeriodicSync() {
        if (periodicSyncJob?.isActive == true) return
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(3000)
                if (activeConnections.isNotEmpty()) {
                    for (endpointId in activeConnections.keys.toList()) {
                        performAntiEntropySync(endpointId)
                    }
                }
            }
        }
    }

    // ── Anti-Entropy Senkronizasyonu ──────────────────────────────────
    private suspend fun performAntiEntropySync(endpointId: String) {
        try {
            val myInventory = epidemicRouter.getLocalInventory()
            val digestJson = buildString {
                append("{\"type\":\"DIGEST\",\"nodeId\":\"$localNodeId\",\"ids\":[")
                append(myInventory.joinToString(",") { "\"$it\"" })
                append("]}")
            }
            sendBytesToEndpoint(endpointId, digestJson.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "performAntiEntropySync hatası ($endpointId): ${e.message}")
        }
    }

    // ── Gelen Veriyi Güvenle İşle ─────────────────────────────────────
    private suspend fun handleIncomingPayload(endpointId: String, bytes: ByteArray) {
        val text = bytes.decodeToString()

        when {
            text.startsWith("{\"type\":\"DIGEST\"") -> {
                val peerIds = parseDigestIds(text)
                val packetsToSend = epidemicRouter.getPacketsToSendToPeer(peerIds)

                Log.d(TAG, "← Digest alındı ($endpointId): ${peerIds.size} ID biliniyor | Gönderilecek: ${packetsToSend.size} paket")

                for (packet in packetsToSend.take(MAX_PACKETS_PER_ENCOUNTER)) {
                    val envelope = buildPacketEnvelope(packet.messageId, packet.rawProtoBytes)
                    sendBytesToEndpoint(endpointId, envelope)
                    delay(80)
                }
            }

            text.startsWith("{\"type\":\"PACKET\"") -> {
                Log.i(TAG, "← Paket alındı: ${bytes.size} byte ($endpointId)")
                val newlineIdx = bytes.indexOf('\n'.code.toByte())
                if (newlineIdx != -1) {
                    val headerJson = bytes.copyOfRange(0, newlineIdx).decodeToString()
                    val payloadBytes = bytes.copyOfRange(newlineIdx + 1, bytes.size)
                    val payloadStr = payloadBytes.decodeToString()

                    var msgId = parseHeaderId(headerJson)
                    var msg = "Acil Durum / SOS"
                    var model = activeConnections[endpointId]?.deviceModel ?: "Bilinmeyen Cihaz"
                    var senderId = extractNodeIdFromEndpointName(endpointId)
                    var lat = 0.0
                    var lon = 0.0
                    var time = System.currentTimeMillis()

                    try {
                        val json = JSONObject(payloadStr)
                        val jMsg = json.optString("msg", "")
                        if (jMsg.isNotBlank()) msg = jMsg
                        val jModel = json.optString("model", "")
                        if (jModel.isNotBlank()) model = jModel
                        val jSender = json.optString("senderId", "")
                        if (jSender.isNotBlank()) senderId = jSender
                        lat = json.optDouble("lat", 0.0)
                        lon = json.optDouble("lon", 0.0)
                        time = json.optLong("time", time)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON ayrıştırma uyarısı: ${e.message}")
                    }

                    if (msgId.isBlank()) {
                        msgId = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                    }

                    val isNew = epidemicRouter.receivePacket(
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

                    if (isNew) {
                        Log.i(TAG, "🚨 YENİ ACİL DURUM ALINDI! Model=$model, Mesaj=$msg, Konum=$lat,$lon")
                        showEmergencyNotification(model, msg, lat, lon)

                        // Karşıya derhal teslim edildi teyidi (ACK) gönder
                        val ackJson = "{\"type\":\"ACK\",\"ackedId\":\"$msgId\"}"
                        sendBytesToEndpoint(endpointId, ackJson.toByteArray())

                        // Epidemic Mesh Gossip: Bu paketi ağdaki diğer tüm komşulara ilet!
                        forwardToOtherPeers(endpointId, bytes)
                    }
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

    private fun forwardToOtherPeers(incomingEndpointId: String, packetBytes: ByteArray) {
        val otherPeers = activeConnections.keys.filter { it != incomingEndpointId }
        if (otherPeers.isNotEmpty()) {
            Log.i(TAG, "Mesh Gossip: Paket diğer ${otherPeers.size} bağlı cihaza aktarılıyor...")
            for (peerId in otherPeers) {
                sendBytesToEndpoint(peerId, packetBytes)
            }
        }
    }

    // ── Yardımcı İletişim Metotları ───────────────────────────────────

    private fun sendBytesToEndpoint(endpointId: String, bytes: ByteArray) {
        try {
            Nearby.getConnectionsClient(context)
                .sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener { e ->
                    Log.w(TAG, "Payload gönderme hatası ($endpointId): ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "sendBytesToEndpoint istisnası: ${e.message}")
        }
    }

    private fun buildPacketEnvelope(messageId: String, rawProto: ByteArray): ByteArray {
        val prefix = "{\"type\":\"PACKET\",\"id\":\"$messageId\",\"len\":${rawProto.size}}\n".toByteArray()
        return prefix + rawProto
    }

    private fun parseHeaderId(headerJson: String): String {
        return try {
            JSONObject(headerJson).optString("id", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseDigestIds(json: String): List<String> {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("ids") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAckedId(json: String): String? {
        return try {
            val obj = JSONObject(json)
            obj.optString("ackedId").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
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
        try {
            periodicSyncJob?.cancel()
            Nearby.getConnectionsClient(context).apply {
                stopAdvertising()
                stopDiscovery()
                stopAllEndpoints()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopAll hatası: ${e.message}")
        }
        activeConnections.clear()
        connectingEndpoints.clear()
        isAdvertising = false
        isDiscovering = false
        _connectedPeerCount.value = 0
        Log.i(TAG, "Nearby Connections durduruldu.")
    }

    private fun showEmergencyNotification(model: String, msg: String, lat: Double, lon: Double) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "afet_emergency_alerts_v3"

            val channel = android.app.NotificationChannel(
                channelId,
                "Çevredeki Acil Durumlar (SOS)",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Yakındaki cihazlardan gelen acil durum bildirimleri"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = android.graphics.Color.RED
            }
            notificationManager.createNotificationChannel(channel)

            val intent = Intent(context, org.afet.mesh.ui.SosDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val locStr = if (lat != 0.0 || lon != 0.0) " (📍 %.4f, %.4f)".format(lat, lon) else ""
            val notification = android.app.Notification.Builder(context, channelId)
                .setContentTitle("🚨 YAKINDA ACİL DURUM: $model")
                .setContentText("$msg$locStr")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(android.app.Notification.PRIORITY_MAX)
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .build()

            val notifId = (System.currentTimeMillis() % 100000).toInt()
            notificationManager.notify(notifId, notification)
            Log.i(TAG, "🔔 Acil durum bildirimi gösterildi: $model - $msg (ID: $notifId)")

            // Ses / Titreşim donanımını doğrudan uyar
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bildirim gösterilemedi: ${e.message}", e)
        }
    }
}
