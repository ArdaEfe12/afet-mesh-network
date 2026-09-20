package org.afet.mesh.mesh.routing

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.afet.mesh.data.local.MessageDao
import org.afet.mesh.data.local.MessageEntity
import org.afet.mesh.data.local.SeenPacketDao
import org.afet.mesh.data.local.SeenPacketEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SALGÜN YÖNLENDİRME MOTORU (Epidemic Router with Anti-Entropy Sync)
 *
 * Çalışma Akışı:
 *
 * [İki Cihaz Karşılaşır]
 *      ↓
 * 1. Anti-Entropy El Sıkışması:
 *    A → B : "Bende şu message_id'ler var: [X, Y, Z]" (AntiEntropyDigest)
 *    B → A : "Bende şu message_id'ler var: [Y, W]"
 *      ↓
 * 2. Fark Hesaplama:
 *    A'dan B'ye: [X, Z] (B bunları bilmiyor)
 *    B'den A'ya: [W]    (A bunu bilmiyor)
 *      ↓
 * 3. Paket Aktarımı:
 *    Sadece eksik paketler gönderilir → Pil ve bant genişliği koruması
 *      ↓
 * 4. Alınan Paketi İşle:
 *    - Daha önce görüldü mü? IGNORE
 *    - TTL sıfır mı? DROP (CRITICAL_SOS hariç)
 *    - Geçerliyse: Room DB'ye ekle, hop sayısını artır
 * ═══════════════════════════════════════════════════════════════════════
 */
@Singleton
class EpidemicRouter @Inject constructor(
    private val messageDao: MessageDao,
    private val seenPacketDao: SeenPacketDao
) {
    companion object {
        private const val TAG = "EpidemicRouter"
        private const val MAX_SEEN_PACKET_CACHE = 10_000
        private const val CRITICAL_SOS_PRIORITY = 1
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Bu cihazın sahip olduğu tüm bekleyen paket ID'lerini döndürür.
     * Karşı cihaza gönderilecek Anti-Entropy Digest budur.
     */
    suspend fun getLocalInventory(): List<String> =
        messageDao.getAllPendingIds()

    /**
     * Karşı cihazın envanter listesiyle karşılaştırıp
     * karşı cihazın bilmediği paketleri döndürür (aktarılacaklar).
     */
    suspend fun getPacketsToSendToPeer(peerKnownIds: List<String>): List<MessageEntity> =
        messageDao.getPacketsToSync(peerKnownIds)

    /**
     * Karşı cihazdan alınan ham ikili paketi işler:
     *  1. Daha önce görüldü mü kontrol (deduplication)
     *  2. TTL azaltma ve hop sayısı artırma
     *  3. Room DB'ye kayıt
     *  4. Seen-set güncelleme
     *
     * @return true → Paket ilk defa alındı ve işlendi
     *         false → Daha önce görüldü veya TTL sıfır (reddedildi)
     */
    suspend fun receivePacket(
        messageId: String,
        rawProtoBytes: ByteArray,
        priority: Int,
        ttl: Int,
        hopCount: Int,
        latitude: Double,
        longitude: Double,
        senderId: String,
        timestampEpoch: Long
    ): Boolean {
        // 1. Deduplication — Bu paketi daha önce gördük mü?
        if (seenPacketDao.hasSeen(messageId) > 0) {
            Log.d(TAG, "Paket zaten görüldü, reddedildi: $messageId")
            return false
        }

        // 2. TTL Kontrolü
        val newTtl = ttl - 1
        if (newTtl < 0 && priority != CRITICAL_SOS_PRIORITY) {
            Log.d(TAG, "TTL sıfır ve CRITICAL_SOS değil, düşürüldü: $messageId")
            // Seen-set'e ekle ki tekrar bu paketi işlemeyelim
            markSeen(messageId)
            return false
        }

        val finalTtl = maxOf(0, newTtl)

        // 3. Room DB'ye kayıt
        val entity = MessageEntity(
            messageId = messageId,
            rawProtoBytes = rawProtoBytes,
            priority = priority,
            ttl = finalTtl,
            hopCount = hopCount + 1,
            latitude = latitude,
            longitude = longitude,
            senderId = senderId,
            timestampEpoch = timestampEpoch
        )
        messageDao.insertMessage(entity)

        // 4. Seen-set güncelle
        markSeen(messageId)

        Log.i(TAG, "✅ Paket alındı ve kuyruğa eklendi: $messageId (TTL=$finalTtl, Hop=${hopCount + 1})")
        return true
    }

    /**
     * ACK paketi alındığında ilgili mesajı teslim edilmiş olarak işaretle.
     */
    suspend fun handleAck(ackedMessageId: String) {
        messageDao.markAsAcked(ackedMessageId)
        Log.i(TAG, "✅ ACK alındı, paket teslim edildi işaretlendi: $ackedMessageId")
    }

    /**
     * Kendi ürettiğimiz SOS paketini kuyruğa ekle (kazazede modunda).
     */
    suspend fun enqueueOwnSos(
        messageId: String,
        rawProtoBytes: ByteArray,
        latitude: Double,
        longitude: Double,
        senderId: String,
        ttl: Int = 12
    ) {
        val entity = MessageEntity(
            messageId = messageId,
            rawProtoBytes = rawProtoBytes,
            priority = CRITICAL_SOS_PRIORITY,
            ttl = ttl,
            hopCount = 0,
            latitude = latitude,
            longitude = longitude,
            senderId = senderId,
            timestampEpoch = System.currentTimeMillis() / 1000
        )
        messageDao.insertMessage(entity)
        markSeen(messageId) // Kendi paketimizi gördüğümüzü kaydet
        Log.i(TAG, "🚨 Kendi SOS paketimiz kuyruğa eklendi: $messageId")
    }

    /**
     * Periyodik temizleme — Teslim edilmiş ve süresi dolmuş paketleri sil.
     */
    fun schedulePeriodicalCleanup() {
        scope.launch {
            val acked = messageDao.deleteAckedMessages()
            val expired = messageDao.deleteExpiredNonCritical()
            Log.d(TAG, "Temizlendi: $acked teslim, $expired süresi dolmuş paket silindi.")

            // Seen-set LRU kapasitesi aşıldı mı?
            if (seenPacketDao.count() > MAX_SEEN_PACKET_CACHE) {
                seenPacketDao.evictOldest()
            }
        }
    }

    private suspend fun markSeen(messageId: String) {
        seenPacketDao.markAsSeen(SeenPacketEntity(messageId = messageId))
    }
}
