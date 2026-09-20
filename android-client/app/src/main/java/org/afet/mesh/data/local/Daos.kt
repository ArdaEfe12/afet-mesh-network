package org.afet.mesh.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════════════
// ROOM DAO ARAYÜZLERI
// ═══════════════════════════════════════════════════════════════════════

// ─── MessageDao ──────────────────────────────────────────────────────
@Dao
interface MessageDao {

    /**
     * Yeni bir SOS/Mesh paketini kuyruğa ekle.
     * Aynı ID zaten varsa IGNORE (çoklama güvenlik katmanı).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * Öncelik sırasına göre (priority ASC, timestamp ASC) sıralanmış
     * tüm bekleyen paketleri döndür.
     * Triage: En acil paket listenin başındadır.
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE is_acked = 0
        ORDER BY priority ASC, timestamp_epoch ASC
    """)
    fun observePendingMessages(): Flow<List<MessageEntity>>

    /**
     * Çevredeki diğer cihazlardan (mesh üzerinden) alınan acil durum mesajlarını gözlemle.
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE is_self = 0
        ORDER BY received_at_ms DESC
    """)
    fun observeNearbyEmergencies(): Flow<List<MessageEntity>>

    /**
     * Karşı cihaza aktarılacak paketleri belirle.
     * Karşı cihazın bilmediği (listede olmayan ID'ler) ve TTL > 0 olanlar.
     * Limit: Tek bir karşılaşmada en fazla 5 paket aktar (BLE bant genişliği koruması).
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE is_acked = 0 AND ttl > 0
        AND messageId NOT IN (:knownIds)
        ORDER BY priority ASC
        LIMIT 5
    """)
    suspend fun getPacketsToSync(knownIds: List<String>): List<MessageEntity>

    /**
     * Tüm bekleyen mesaj ID'lerini döndür (Anti-Entropy Digest için).
     */
    @Query("SELECT messageId FROM pending_messages WHERE is_acked = 0")
    suspend fun getAllPendingIds(): List<String>

    /**
     * Paketin hop sayısını ve TTL değerini aktarım sonrası güncelle.
     */
    @Query("""
        UPDATE pending_messages
        SET ttl = :newTtl, hop_count = :newHopCount
        WHERE messageId = :messageId
    """)
    suspend fun updateHopAndTtl(messageId: String, newTtl: Int, newHopCount: Int)

    /**
     * ACK alındı — paketi teslim edildi olarak işaretle.
     * Periyodik temizleme job'u bu kayıtları diskten siler.
     */
    @Query("UPDATE pending_messages SET is_acked = 1 WHERE messageId = :messageId")
    suspend fun markAsAcked(messageId: String)

    /**
     * Teslim edilmiş (acked) paketleri diskten temizle.
     */
    @Query("DELETE FROM pending_messages WHERE is_acked = 1")
    suspend fun deleteAckedMessages(): Int

    /**
     * TTL sıfıra düşmüş paketleri temizle (CRITICAL_SOS hariç — priority=1).
     */
    @Query("DELETE FROM pending_messages WHERE ttl = 0 AND priority > 1")
    suspend fun deleteExpiredNonCritical(): Int
}

// ─── SeenPacketDao ───────────────────────────────────────────────────
@Dao
interface SeenPacketDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markAsSeen(entity: SeenPacketEntity)

    @Query("SELECT COUNT(*) FROM seen_packet_ids WHERE messageId = :messageId")
    suspend fun hasSeen(messageId: String): Int

    /** LRU temizleme: En eski 500 kaydı sil (10.000 kapasiteyi aşmamak için). */
    @Query("""
        DELETE FROM seen_packet_ids
        WHERE messageId IN (
            SELECT messageId FROM seen_packet_ids
            ORDER BY seen_at_ms ASC
            LIMIT 500
        )
    """)
    suspend fun evictOldest(): Int

    @Query("SELECT COUNT(*) FROM seen_packet_ids")
    suspend fun count(): Int
}

// ─── PeerDao ─────────────────────────────────────────────────────────
@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY last_seen_ms DESC")
    fun observePeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE endpointId = :endpointId")
    suspend fun getPeer(endpointId: String): PeerEntity?

    /** Belirli bir süredir görülmemiş düğümleri temizle (varsayılan 30 dakika). */
    @Query("DELETE FROM peers WHERE last_seen_ms < :cutoffMs")
    suspend fun deleteStalePeers(cutoffMs: Long): Int

    @Query("UPDATE peers SET exchange_count = exchange_count + 1, last_seen_ms = :now WHERE endpointId = :endpointId")
    suspend fun incrementExchangeCount(endpointId: String, now: Long = System.currentTimeMillis())
}
