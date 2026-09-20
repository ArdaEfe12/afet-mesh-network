/**
 * Disaster Mesh Protocol (DMP) - Core Packet Engine
 * Kompakt ikili / bit düzeyinde paketleme ve durum yönetimi.
 */

export const PacketType = {
  CRITICAL_SOS: 0x01,   // Enkaz altı, acil kurtarma çağrısı
  MEDICAL_AID: 0x02,    // Tıbbi yardım, kan grubu, yaralanma
  FAMILY_SAFE: 0x03,    // "Güvendeyim" / Aile mesajı
  RELAY_BEACON: 0x04,   // Kurye / Röle durumu
  ACK_CONFIRM: 0x05     // Kurtarma ekibi ulaştı teyidi
};

export const UrgencyLevel = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  CRITICAL: 4
};

export class MeshPacket {
  constructor(options = {}) {
    this.magic = 0xD54D; // "DM" - Disaster Mesh
    this.version = 1;
    this.type = options.type || PacketType.CRITICAL_SOS;
    this.id = options.id || MeshPacket.generateId();
    this.timestamp = options.timestamp || Math.floor(Date.now() / 1000);
    this.senderId = options.senderId || 'NODE-' + Math.random().toString(36).substring(2, 8).toUpperCase();
    
    // Konum Bilgileri
    this.latitude = options.latitude !== undefined ? Number(options.latitude) : 0.0;
    this.longitude = options.longitude !== undefined ? Number(options.longitude) : 0.0;
    this.altitude = options.altitude || 0; // Metre
    this.accuracy = options.accuracy || 10; // Metre cinsinden GPS hassasiyeti

    // Durum Bayrakları (Bitfield)
    this.status = {
      isTrapped: options.isTrapped !== undefined ? options.isTrapped : true,      // Enkaz altında mı?
      isInjured: options.isInjured !== undefined ? options.isInjured : false,     // Yaralı var mı?
      isConscious: options.isConscious !== undefined ? options.isConscious : true,// Bilinç açık mı?
      peopleCount: options.peopleCount || 1,                                      // Yanındaki kişi sayısı (1-15)
      batteryPercent: options.batteryPercent !== undefined ? options.batteryPercent : 100, // 0-100
      urgency: options.urgency || UrgencyLevel.CRITICAL
    };

    // Yönlendirme & DTN
    this.ttl = options.ttl !== undefined ? options.ttl : 10; // Max hop count
    this.hopCount = options.hopCount || 0;
    this.hops = options.hops || [this.senderId];
    this.payload = options.payload || ''; // Kısa metin / telsiz notu
    this.encrypted = options.encrypted || false;
  }

  static generateId() {
    // 8-byte hex id (örn. a8f412e09cb4)
    return Array.from({ length: 6 }, () => Math.floor(Math.random() * 256).toString(16).padStart(2, '0')).join('');
  }

  /**
   * Paketi kompakt bir JSON / Byte-friendly formata serileştirir
   */
  serialize() {
    return JSON.stringify({
      m: this.magic,
      v: this.version,
      t: this.type,
      id: this.id,
      ts: this.timestamp,
      sId: this.senderId,
      lat: Number(this.latitude.toFixed(6)),
      lon: Number(this.longitude.toFixed(6)),
      alt: Math.round(this.altitude),
      acc: Math.round(this.accuracy),
      st: {
        tr: this.status.isTrapped ? 1 : 0,
        inj: this.status.isInjured ? 1 : 0,
        con: this.status.isConscious ? 1 : 0,
        cnt: this.status.peopleCount,
        bat: Math.round(this.status.batteryPercent),
        urg: this.status.urgency
      },
      ttl: this.ttl,
      hop: this.hopCount,
      hops: this.hops,
      p: this.payload,
      enc: this.encrypted ? 1 : 0
    });
  }

  /**
   * Serileştirilmiş veriden MeshPacket nesnesi türetir
   */
  static deserialize(raw) {
    try {
      const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (data.m !== 0xD54D) {
        throw new Error('Geçersiz Protokol Sihirli Numarası (Magic Number)');
      }

      return new MeshPacket({
        type: data.t,
        id: data.id,
        timestamp: data.ts,
        senderId: data.sId,
        latitude: data.lat,
        longitude: data.lon,
        altitude: data.alt,
        accuracy: data.acc,
        isTrapped: Boolean(data.st.tr),
        isInjured: Boolean(data.st.inj),
        isConscious: Boolean(data.st.con),
        peopleCount: data.st.cnt,
        batteryPercent: data.st.bat,
        urgency: data.st.urg,
        ttl: data.ttl,
        hopCount: data.hop,
        hops: data.hops || [],
        payload: data.p,
        encrypted: Boolean(data.enc)
      });
    } catch (err) {
      console.error('Paket çözme hatası:', err);
      return null;
    }
  }

  /**
   * Bir düğümden diğerine atlarken TTL azaltır ve sıçrama kaydeder
   */
  hop(relayNodeId) {
    if (this.ttl <= 0) return false;
    this.ttl -= 1;
    this.hopCount += 1;
    if (!this.hops.includes(relayNodeId)) {
      this.hops.push(relayNodeId);
    }
    return true;
  }

  /**
   * Paketin tahmini bayt boyutu (Over-the-air BLE paketi için analiz)
   */
  getEstimatedByteSize() {
    return new TextEncoder().encode(this.serialize()).length;
  }
}
