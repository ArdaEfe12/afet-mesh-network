# Afet ve Acil Durum "İnternetsiz Ağ" (Mesh Network Kuryesi)

## 📌 Problem ve Çözüm
- **Sorun:** Deprem veya büyük afetlerde baz istasyonları ve karasal internet çöker. Enkaz altındakiler veya mahsur kalanlar koordinatlarını ve acil durumlarını iletemez.
- **Çözüm:** İnternet veya hücresel veri olmadan çalışan, **cihazdan cihaza (P2P BLE / Wi-Fi Direct / Wi-Fi Aware)** sıçrayarak mikro mesaj ileten gecikmeye dayanıklı ağ (**Delay-Tolerant Networking - DTN / Data Mule**).
- **İşleyiş:** Bir vatandaşın cebindeki telefon veya bir arama kurtarma dronu, enkazdaki kazazedenin yaydığı SOS sinyalini arka planda depolar ve yürürken karşılaştığı diğer cihazlara aktararak mesajı uydu/internet bağlantısı olan ilk kurtarma merkezine (AFAD/AKUT uydu aracına) taşır.

---

## 🧱 Kurulan Temel Mimari (Foundation)

1. **`docs/PROTOCOL_SPEC.md`**:
   - Protokol Sihirli Numarası (`0xD54D`), versiyon, paket tipi ve durum bit alanları (Bitflags: enkaz, yaralı, bilinç, kişi sayısı).
   - TTL (Hop Count) ve LRU önbellek tabanlı deduplication (çoklama engelleme).
   - Pil tasarrufu için adaptif Duty Cycle (Kritik bataryada dakikalarca derin uyku + mikro-yayın).

2. **`src/protocol/packet.js`**:
   - `MeshPacket` sınıfı: Kompakt serileştirme/deserileştirme, GPS koordinatı sıkıştırma, bayt boyutu tahmini.

3. **`src/mesh/store_forward.js`**:
   - Gecikmeye dayanıklı depola-ve-ilet (Store-and-Forward) kuyruğu.
   - Öncelik Sıralaması (Triage Priority): Enkaz & Ağır Yaralı > Tıbbi Yardım > Güvendeyim > Genel Telemetri.

4. **`src/mesh/peer.js`**:
   - Komşu düğüm yönetimi, RSSI sinyal gücünden log-distance mesafe tahmini, pil durumuna göre adaptif BLE yayınlama/tarama döngüleri.

5. **`src/simulation/network_simulator.js`**:
   - Enkaz altındaki kazazedeleri, hareket eden veri kuryelerini (vatandaşlar/dronlar) ve kurtarma uydusunu simüle eden uzamsal 2D motor.
   - Sinyal kapsama alanları, gerçek zamanlı paket aktarımları (hop animasyonları) ve teslimat teyidi.

6. **`index.html` & `src/styles.css` & `src/app.js`**:
   - Canlı görselleştirici, etkileşimli SOS gönderme paneli, AFAD kurtarma triage ekranı ve nano-frame bayt inceleyici.

---

## 🚀 Başlatma ve Test
Projeyi herhangi bir web tarayıcısında açabilirsiniz (örneğin doğrudan `index.html` dosyasına çift tıklayarak veya yerel bir sunucu ile):

```bash
# Tarayıcıda açmak için
start index.html
```
*(Detaylı gereksinimler ve özel spesifikasyonlar geldiğinde doğrudan bu temelin üzerine inşa edilecektir.)*
