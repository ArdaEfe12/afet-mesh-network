/**
 * Store-and-Forward / DTN (Delay-Tolerant Networking) Kuyruk Yöneticisi
 * İnternet olmayan ortamda paketleri depolar, önceliklendirir ve kurye mantığıyla iletir.
 */

import { PacketType, UrgencyLevel } from '../protocol/packet.js';

export class StoreAndForwardQueue {
  constructor(options = {}) {
    this.maxQueueSize = options.maxQueueSize || 200; // Cihaz hafızasını koruma limiti
    this.seenPacketIds = new Set();
    this.deliveredPacketIds = new Set();
    this.queue = []; // MeshPacket listesi
    this.onPacketReceived = options.onPacketReceived || null;
    this.onPacketRelayed = options.onPacketRelayed || null;
  }

  /**
   * Yeni bir paket eklendiğinde deduplication ve öncelik sırasına göre kuyruğa alır
   * @param {MeshPacket} packet
   * @returns {boolean} Paketin ilk defa alınıp alınmadığı
   */
  enqueue(packet) {
    if (!packet || !packet.id) return false;

    // 1. Çoklama Kontrolü (Deduplication)
    if (this.seenPacketIds.has(packet.id)) {
      return false; // Zaten bu paketi gördük veya taşıyoruz
    }

    // 2. TTL Kontrolü
    if (packet.ttl <= 0) {
      return false; // Paketin ömrü dolmuş
    }

    this.seenPacketIds.add(packet.id);
    this.queue.push(packet);

    // 3. Öncelik Sıralaması (Triage Priority Queue)
    this.sortQueueByPriority();

    // 4. Kapasite taşarsa en düşük öncelikli olanı düşür
    if (this.queue.length > this.maxQueueSize) {
      this.evictLowestPriority();
    }

    if (this.onPacketReceived) {
      this.onPacketReceived(packet);
    }

    return true;
  }

  /**
   * Öncelik Sıralaması:
   * 1. Acil Kurtarma (CRITICAL_SOS)
   * 2. Tıbbi Aciliyet (UrgencyLevel: 4 > 3 > 2 > 1)
   * 3. Enkaz altında olanlar (isTrapped === true)
   * 4. Zaman damgası (daha yeni mesajlar)
   */
  sortQueueByPriority() {
    this.queue.sort((a, b) => {
      // Tip önceliği
      const typeScore = (p) => (p.type === PacketType.CRITICAL_SOS ? 100 : p.type === PacketType.MEDICAL_AID ? 50 : 10);
      const scoreDiff = typeScore(b) - typeScore(a);
      if (scoreDiff !== 0) return scoreDiff;

      // Aciliyet seviyesi
      const urgencyDiff = (b.status?.urgency || 1) - (a.status?.urgency || 1);
      if (urgencyDiff !== 0) return urgencyDiff;

      // Enkaz durumu
      if (b.status?.isTrapped && !a.status?.isTrapped) return 1;
      if (!b.status?.isTrapped && a.status?.isTrapped) return -1;

      // Zamana göre (en yeni en önde)
      return b.timestamp - a.timestamp;
    });
  }

  /**
   * Karşı cihaza aktarılacak paketlerin listesini döner
   * Karşı cihazın bilmediği (haveList içinde olmayan) paketleri filtreler
   */
  getPacketsToSync(peerKnownIds = [], limit = 10) {
    const knownSet = new Set(peerKnownIds);
    return this.queue
      .filter(p => !knownSet.has(p.id) && p.ttl > 0)
      .slice(0, limit);
  }

  /**
   * Düşük öncelikli paketi kuyruktan çıkar
   */
  evictLowestPriority() {
    // Kuyruk ters sıralı olduğundan son eleman en düşük önceliklidir
    const evicted = this.queue.pop();
    return evicted;
  }

  /**
   * Sahip olunan paketlerin ID listesi (Anti-Entropy / Bloom Filter benzeri hızlı el sıkışma için)
   */
  getInventoryIds() {
    return Array.from(this.seenPacketIds);
  }

  /**
   * İnternete veya Kurtarma Merkezine ulaşıldığında paketleri teslim edildi olarak işaretler
   */
  markDelivered(packetId) {
    this.deliveredPacketIds.add(packetId);
  }

  isDelivered(packetId) {
    return this.deliveredPacketIds.has(packetId);
  }

  getQueue() {
    return [...this.queue];
  }

  size() {
    return this.queue.length;
  }
}
