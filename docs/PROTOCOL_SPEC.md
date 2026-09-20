# Afet ve Acil Durum İnternetsiz Ağ Protokolü (Disaster Mesh Protocol - DMP)

## 1. Genel Bakış ve Amaç
Deprem, tsunami veya diğer büyük ölçekli afetlerde hücresel baz istasyonları ve karasal internet altyapısı dakikalar içinde çökebilmektedir. Enkaz altında kalan kazazedeler veya mahsur kalan bireyler acil durum sinyallerini, konumlarını ve sağlık durumlarını geleneksel yöntemlerle ulaştıramazlar.

**DMP (Disaster Mesh Protocol)**; internet, hücresel veri veya merkezi bir sunucuya ihtiyaç duymadan, cihazlar arası (P2P: Bluetooth Low Energy ve Wi-Fi Direct / Wi-Fi Aware) doğrudan iletişim kullanarak çalışan, **Gecikmeye Dayanıklı Ağ (Delay-Tolerant Networking - DTN)** ve **Veri Kuryesi (Data Mule / Courier)** mimarisine dayanan hafif, şifreli bir mikro mesajlaşma protokolüdür.

---

## 2. Ağ Rolleri (Node Typology)

1. **Beacon / Kazazede Düğümü (Victim Node):**
   - Enkaz altında veya erişilemeyen bölgede bulunan cihaz.
   - Ultra düşük güç tüketim modunda çalışır (Deep sleep + periodik BLE Advertising).
   - Acil durum sinyali (SOS), son bilinen GPS koordinatları, enkaz durumu, pil seviyesi ve varsa tıbbi ihtiyaçları içeren sıkıştırılmış paket yayını yapar.

2. **Kurye / Aktarıcı Düğüm (Relay / Courier Node):**
   - Bölgede hareket eden vatandaşlar, gönüllüler, arama kurtarma personeli veya devriye gezen İHA'lar/dronlar.
   - Kazazedelerin yayınladığı paketleri depolar (Store-and-Forward).
   - Yürürken/hareket ederken karşılaştığı diğer cihazlara bu paketleri aktarır (Epidemik yayılım).

3. **Ağ Geçidi / Komuta Merkezi (Gateway / Sink Node):**
   - Uydu bağlantısı (Starlink, Iridium vb.), telsiz veri rölesi veya çalışan bir hücresel/internet bağlantısına sahip kurtarma aracı ya da AFAD/AKUT koordinasyon merkezi.
   - Ağdan toplanan paketleri alır, deşifre eder ve merkezi kurtarma haritasına işler.

---

## 3. Kompakt İkili Paket Formatı (Binary Packet Frame - Max 64-128 Byte)

BLE reklam paketleri (Advertising Data) ve Wi-Fi Aware nano-paketleri için bayt tasarrufu kritik öneme sahiptir. Standart bir DMP Acil Durum Paketi şu şekilde yapılandırılır:

| Alan (Field) | Boyut | Açıklama |
|---|---|---|
| `Protocol Magic` | 2 Byte | `0xD5 0x4D` ("DM" - Disaster Mesh) |
| `Packet Version` | 1 Byte | Protokol sürümü (örn. `0x01`) |
| `Packet Type` | 1 Byte | `0x01` SOS/Acil, `0x02` Tıbbi, `0x03` Durum Bildirimi, `0x04` ACK/Teyit |
| `Packet UUID / Hash` | 8 Byte | Paketin tekil tanımlayıcısı (Deduplication için) |
| `Timestamp (Epoch)` | 4 Byte | Unix epoch (saniye cinsinden) |
| `Latitude` | 4 Byte | IEEE 754 float veya ölçeklendirilmiş int32 (1e-7 hassasiyet) |
| `Longitude` | 4 Byte | IEEE 754 float veya ölçeklendirilmiş int32 |
| `Accuracy & Battery` | 1 Byte | İlk 4 bit GPS hassasiyet sınıfı, son 4 bit Pil yüzdesi (%10 dilimler) |
| `Status Flags` | 2 Byte | Enkaz altında (1 bit), Yaralı (1 bit), Bilinç açık (1 bit), Kişi sayısı (4 bit), vs. |
| `Hop Count / TTL` | 1 Byte | Başlangıçta 7-15; her sıçramada 1 azalır (TTL=0 ise aktarılmaz) |
| `Payload Length` | 1 Byte | Şifreli/Sıkıştırılmış mesaj uzunluğu (0-64 byte) |
| `Encrypted Payload` | 0-64 Byte | Kısa metin (örn. "3 kişi 2. kat mutfaktayız"), AES-128/GCM veya Açık Acil Çağrı |
| `CRC16 / Checksum` | 2 Byte | Paket bütünlüğü kontrolü (Hata tespiti) |

---

## 4. Gecikmeye Dayanıklı Depola-ve-İlet (Store-and-Forward) Mimarisi

- **Epidemik Yönlendirme (Epidemic Routing with Anti-Entropy):** İki düğüm birbirinin kapsama alanına girdiğinde, sahip oldukları paketlerin özetlerini (Bloom Filter veya Vector Hash) takas eder. Karşı tarafta olmayan paketler aktarılır.
- **Deduplication (Çoklama Önleme):** Her düğüm son $N$ paketin UUID'lerini bir LRU önbelleğinde saklar. Daha önce görülmüş paketler tekrar işlenmez ve gereksiz aktarılmaz.
- **Öncelik Kuyruğu (Priority Queue):**
  1. *Acil SOS / Enkaz / Ağır Yaralı* (En yüksek öncelik, asla düşürülmez)
  2. *Hafif Yaralı / Tıbbi İhtiyaç*
  3. *Aile İçi Güvendeyim Mesajları*
  4. *Genel Durum / Telemetri*

---

## 5. Düşük Güç ve Pil Tüketimi Optimizasyonu (Duty Cycling)

- **Akıllı Tarama / Yayınlama Döngüsü (Adaptive Duty Cycle):**
  - Kazazede modu (Batarya <%20): 10 saniyede bir 100ms BLE yayını, tarama kapalı.
  - Kazazede modu (Batarya >%20): 3 saniyede bir 150ms BLE yayını, 30 saniyede bir 500ms kısa tarama.
  - Kurye modu (Hareketli): İvmeölçer hareket algıladığında tarama sıklığı artırılır; durağan halde tarama azaltılır.
  - Wi-Fi Direct / Wi-Fi Aware geçişi: Sadece yüksek boyutlu paket veya yoğun veri takası gerektiğinde el sıkışma sonrası kısa süreliğine açılır, ardından hemen kapatılır.
