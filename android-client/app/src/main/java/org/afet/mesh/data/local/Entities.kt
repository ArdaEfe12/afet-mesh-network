package org.afet.mesh.data.local

import androidx.room.*

// ═══════════════════════════════════════════════════════════════════════
// ROOM DATABASE — Gecikmeye Dayanıklı Depolama (Delay-Tolerant Storage)
//
// Tablo 1: pending_messages  — Ağa yayılmayı bekleyen SOS paketleri
// Tablo 2: seen_packet_ids   — Deduplication: daha önce görülen paket ID'leri
// Tablo 3: peers             — Keşfedilen komşu cihazlar
// ═══════════════════════════════════════════════════════════════════════

// ─── pending_messages ────────────────────────────────────────────────
/**
 * Henüz bir kurtarma merkezine ulaştırılamamış, ağda dolaşmayı bekleyen
 * MeshPacket'lerin yerel depolama kaydı.
 *
 * Öncelik Sıralaması (Triage):
 *  1 = CRITICAL_SOS (Enkaz altı — asla silinmez, TTL sona erse bile tutulur)
 *  2 = MEDICAL_AID
 *  3 = FAMILY_SAFE
 *  4 = RELAY_HEARTBEAT
 */
@Entity(tableName = "pending_messages")
data class MessageEntity(
    /** 12-byte ikili mesaj kimliği — hex string olarak saklanır (24 karakter). */
    @PrimaryKey
    val messageId: String,

    /** Ham Protobuf MeshPacket ikili verisi — BLE/Wi-Fi üzerinden doğrudan aktarılabilir. */
    @ColumnInfo(name = "raw_proto_bytes")
    val rawProtoBytes: ByteArray,

    /** Triage öncelik skoru (küçük = daha acil). */
    @ColumnInfo(name = "priority")
    val priority: Int,

    /** Paketin anlık TTL değeri (aktarım sonrası azaltılır). */
    @ColumnInfo(name = "ttl")
    val ttl: Int,

    /** Paketin kaç kez aktarıldığı (hop sayısı). */
    @ColumnInfo(name = "hop_count")
    val hopCount: Int,

    /** Kayan noktalı enlem (görüntüleme ve harita için). */
    @ColumnInfo(name = "latitude")
    val latitude: Double,

    /** Kayan noktalı boylam. */
    @ColumnInfo(name = "longitude")
    val longitude: Double,

    /** Gönderici cihaz kimliği (kısa node ID). */
    @ColumnInfo(name = "sender_id")
    val senderId: String,

    /** Paketin oluşturulduğu Unix epoch (saniye). */
    @ColumnInfo(name = "timestamp_epoch")
    val timestampEpoch: Long,

    /** Bu düğümün paketi aldığı yerel zaman (milisaniye). */
    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long = System.currentTimeMillis(),

    /** Paket ACK paketi ile doğrulanmış mı? Evet ise silinmeye hazır. */
    @ColumnInfo(name = "is_acked")
    val isAcked: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other is MessageEntity && messageId == other.messageId

    override fun hashCode(): Int = messageId.hashCode()
}

// ─── seen_packet_ids ─────────────────────────────────────────────────
/**
 * Cihazın daha önce gördüğü veya işlediği mesaj kimliklerinin özet listesi.
 * Bu tablo Salgın Yönlendirme'de (Epidemic Routing) çoklama (deduplication) için kullanılır.
 *
 * Tasarım: Sadece message_id saklanır, raw paket baytları tutulmaz → hafıza tasarrufu.
 * Kapasite: Maksimum 10.000 kayıt. Dolduğunda en eski kayıtlar silinir (LRU).
 */
@Entity(tableName = "seen_packet_ids")
data class SeenPacketEntity(
    @PrimaryKey
    val messageId: String,

    /** Bu paketin ilk görüldüğü yerel zaman (LRU temizleme için). */
    @ColumnInfo(name = "seen_at_ms")
    val seenAtMs: Long = System.currentTimeMillis()
)

// ─── peers ───────────────────────────────────────────────────────────
/**
 * Keşfedilen komşu cihazların (Nearby Connections endpoint'lerinin) kayıtları.
 * Kurye düğümleri bu tablodan hareketle karşılaşma geçmişini analiz edebilir.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    /** Nearby Connections endpointId (Google'ın verdiği kısa string). */
    @PrimaryKey
    val endpointId: String,

    /** Cihazın mesh ağındaki kendi oluşturduğu node kimliği (6-byte hex). */
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    /** Düğüm rolü: "VICTIM", "COURIER", "GATEWAY" */
    @ColumnInfo(name = "role")
    val role: String,

    /** Son karşılaşma zamanı (milisaniye). */
    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long = System.currentTimeMillis(),

    /** Son ölçülen RSSI (sinyal gücü, dBm). */
    @ColumnInfo(name = "last_rssi")
    val lastRssi: Int = -70,

    /** Bu cihaz ile toplam kaç mesaj takası yapıldı? */
    @ColumnInfo(name = "exchange_count")
    val exchangeCount: Int = 0,

    /** Bu cihaz ile en son senkronize edilen mesaj kimliği. */
    @ColumnInfo(name = "last_synced_message_id")
    val lastSyncedMessageId: String? = null
)
