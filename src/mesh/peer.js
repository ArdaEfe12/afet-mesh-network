/**
 * Peer Yönetimi ve Pil Tüketim Optimizasyonu (Duty Cycle Engine)
 */

export const NodeRole = {
  VICTIM: 'VICTIM',     // Enkaz altı / Sabit SOS feneri
  COURIER: 'COURIER',   // Yürüyen / Hareket eden veri kuryesi
  GATEWAY: 'GATEWAY'    // Kurtarma ekibi / Uydu bağlantılı merkez
};

export class PeerManager {
  constructor(nodeId, role = NodeRole.COURIER) {
    this.nodeId = nodeId;
    this.role = role;
    this.peers = new Map(); // peerId -> { id, role, rssi, lastSeen, distanceEst, battery }
    this.batteryLevel = 100; // 0 - 100
    this.isScanning = false;
    this.isAdvertising = false;
    this.dutyCycleState = 'IDLE'; // 'SLEEP' | 'ADVERTISE' | 'SCAN'
  }

  /**
   * Bir komşu cihaz keşfedildiğinde veya sinyal alındığında çağrılır
   */
  updatePeer(peerData) {
    const { id, role, rssi, battery, coordinates } = peerData;
    const distanceEst = this.calculateEstimatedDistance(rssi);

    const peer = {
      id,
      role: role || NodeRole.COURIER,
      rssi: rssi || -70,
      battery: battery !== undefined ? battery : 100,
      coordinates: coordinates || null,
      distanceEst: distanceEst,
      lastSeen: Date.now()
    };

    this.peers.set(id, peer);
    return peer;
  }

  /**
   * Log-distance path loss modeli ile RSSI'dan yaklaşık mesafe (metre) hesabı
   */
  calculateEstimatedDistance(rssi, txPower = -59, n = 2.5) {
    if (!rssi) return 10;
    // ratio = (txPower - rssi) / (10 * n)
    const ratio = (txPower - rssi) / (10 * n);
    const distance = Math.pow(10, ratio);
    return Math.max(0.5, Number(distance.toFixed(1)));
  }

  /**
   * Belirli bir süre sinyal alınamayan pasif cihazları temizler
   */
  pruneStalePeers(timeoutMs = 60000) {
    const now = Date.now();
    for (const [id, peer] of this.peers.entries()) {
      if (now - peer.lastSeen > timeoutMs) {
        this.peers.delete(id);
      }
    }
  }

  /**
   * Pil tasarrufu için adaptif Duty Cycle hesaplayıcı
   * Batarya düştükçe tarama aralıkları açılır, fener yayın süresi optimize edilir
   */
  getDutyCycleProfile() {
    if (this.role === NodeRole.VICTIM) {
      if (this.batteryLevel < 15) {
        // Kritik Pil Modu: Enkaz altında günlerce hayatta kalabilmek için
        return {
          mode: 'CRITICAL_SURVIVAL',
          advIntervalMs: 15000,   // 15 saniyede bir 100ms yayın
          advDurationMs: 100,
          scanIntervalMs: 0,      // Tarama tamamen kapalı (aşırı pil tasarrufu)
          scanDurationMs: 0,
          wifiDirectAllowed: false
        };
      } else if (this.batteryLevel < 40) {
        // Eko Mod
        return {
          mode: 'BATTERY_SAVER',
          advIntervalMs: 5000,
          advDurationMs: 150,
          scanIntervalMs: 60000,  // Dakikada bir 500ms kısa dinleme
          scanDurationMs: 500,
          wifiDirectAllowed: false
        };
      } else {
        // Normal Enkaz SOS Modu
        return {
          mode: 'NORMAL_BEACON',
          advIntervalMs: 2000,
          advDurationMs: 200,
          scanIntervalMs: 30000,
          scanDurationMs: 1000,
          wifiDirectAllowed: false
        };
      }
    }

    if (this.role === NodeRole.COURIER) {
      // Kurye (Taşıyıcı) Modu: Hareket halindeyken çevredeki enkazları toplar
      return {
        mode: 'ACTIVE_COURIER',
        advIntervalMs: 1500,
        advDurationMs: 200,
        scanIntervalMs: 4000,
        scanDurationMs: 1500,
        wifiDirectAllowed: true
      };
    }

    // GATEWAY (Kurtarma Ekibi / Uydu)
    return {
      mode: 'CONTINUOUS_LISTENER',
      advIntervalMs: 1000,
      advDurationMs: 300,
      scanIntervalMs: 1000,
      scanDurationMs: 900,
      wifiDirectAllowed: true
    };
  }

  getActivePeers() {
    return Array.from(this.peers.values());
  }
}
