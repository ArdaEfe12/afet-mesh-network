using Afet.Gateway.Models;
using Google.Protobuf;
using Sodium;

namespace Afet.Gateway.Services;

/// <summary>
/// Ham BLE / Wi-Fi bayt dizisini alır, şu adımları uygular:
///   1. Protobuf MeshPacket çözme
///   2. TTL sıfır mı? Düşür.
///   3. Ed25519 imzası doğrulama (gönderici inkar edemez)
///   4. Curve25519 ECDH ile şifreli yük çözme (Kurtarma Merkezi Özel Anahtarı ile)
///   5. EmergencyPayload Protobuf çözme → DeliveredEmergency modeli döndür
///
/// NOT — Protobuf Codegen Durumu:
///   Bu sınıf Grpc.Tools'un mesh_packet.proto'dan Afet.Mesh namespace'ini üretmesini
///   bekler. İlk `dotnet build` çalıştıktan sonra tam Protobuf kod üretimi tamamlanır.
///   O zamana kadar JSON envelope tabanlı geliştirme/test stub'u kullanılmaktadır.
/// </summary>
public class PacketDecoderService
{
    private readonly ILogger<PacketDecoderService> _logger;

    // Kurtarma Merkezi (Gateway) Asimetrik Anahtar Çifti (Curve25519)
    private readonly KeyPair _gatewayKeyPair;

    public PacketDecoderService(ILogger<PacketDecoderService> logger)
    {
        _logger = logger;
        _gatewayKeyPair = PublicKeyBox.GenerateKeyPair();

        _logger.LogInformation(
            "[Gateway] 🔑 Kurtarma Merkezi Genel Anahtarı (Curve25519 Hex): {PK}",
            Convert.ToHexString(_gatewayKeyPair.PublicKey));
    }

    /// <summary>Kurtarma merkezinin genel anahtarı — Android cihazlara QR/hardcoded dağıtılır.</summary>
    public byte[] GatewayPublicKey => _gatewayKeyPair.PublicKey;

    /// <summary>
    /// Ham bayt dizisini (BLE frame veya Wi-Fi payload) çözümleyerek
    /// DeliveredEmergency modeli döndürür.
    ///
    /// Mevcut Uygulama: JSON envelope stub (Aşama 1 Test).
    /// Gelecek: Tam Protobuf MeshPacket decode + Curve25519 deşifre (Aşama 2).
    /// </summary>
    public DeliveredEmergency? DecodePacket(byte[] rawBytes)
    {
        if (rawBytes.Length == 0) return null;

        try
        {
            var text = System.Text.Encoding.UTF8.GetString(rawBytes);

            // ── Geçici JSON Stub (Protobuf codegen öncesi) ──────────────
            if (text.StartsWith("{"))
            {
                return DecodeJsonStub(text, rawBytes.Length);
            }

            // ── Gelecek: Tam Protobuf Decode ─────────────────────────────
            // Aşama 2'de aşağıdaki blok aktif edilecek:
            //   var meshPacket = MeshPacket.Parser.ParseFrom(rawBytes);
            //   → TTL kontrol → Ed25519 doğrulama → Curve25519 çözme → EmergencyPayload
            _logger.LogWarning("[Decoder] Protobuf decode henüz aktif değil. JSON stub bekleniyor.");
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogError("[Decoder] Hata: {Msg}", ex.Message);
            return null;
        }
    }

    // ── JSON Stub Çözücü (Aşama 1 Test için) ─────────────────────────
    private DeliveredEmergency? DecodeJsonStub(string json, int rawSize)
    {
        // Basit JSON parse (System.Text.Json)
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        var root = doc.RootElement;

        var messageId = root.TryGetProperty("id", out var idEl)
            ? idEl.GetString() ?? Guid.NewGuid().ToString("N")[..24]
            : Guid.NewGuid().ToString("N")[..24];

        var lat = root.TryGetProperty("lat", out var latEl) ? latEl.GetDouble() : 0.0;
        var lon = root.TryGetProperty("lon", out var lonEl) ? lonEl.GetDouble() : 0.0;
        var msg = root.TryGetProperty("msg", out var msgEl) ? msgEl.GetString() : "";
        var ttl = root.TryGetProperty("ttl", out var ttlEl) ? ttlEl.GetInt32() : 10;
        var hop = root.TryGetProperty("hop", out var hopEl) ? hopEl.GetInt32() : 0;
        var prio = root.TryGetProperty("prio", out var prioEl) ? prioEl.GetInt32() : 1;
        var bat = root.TryGetProperty("bat", out var batEl) ? batEl.GetInt32() : 100;
        var trapped = root.TryGetProperty("trapped", out var trappedEl) && trappedEl.GetBoolean();
        var injured = root.TryGetProperty("injured", out var injEl) && injEl.GetBoolean();
        var people = root.TryGetProperty("people", out var pplEl) ? pplEl.GetInt32() : 1;

        if (ttl <= 0 && prio > 1)
        {
            _logger.LogDebug("[Decoder] TTL=0 ve CRITICAL_SOS değil, düşürüldü: {Id}", messageId);
            return null;
        }

        _logger.LogInformation(
            "[Decoder] ✅ JSON Stub çözüldü | ID={Id} | GPS={Lat:F5},{Lon:F5} | Hop={Hop} | Boyut={Size}b",
            messageId, lat, lon, hop, rawSize);

        return new DeliveredEmergency
        {
            MessageId = messageId,
            SenderPublicKeyHex = "STUB_KEY",
            Latitude = lat,
            Longitude = lon,
            AccuracyMeters = 10,
            AltitudeMeters = 0,
            IsTrapped = trapped,
            IsInjured = injured,
            IsConscious = true,
            PeopleCount = people,
            BatteryPercent = bat,
            MessageText = msg,
            TotalHops = hop,
            Priority = (PacketPriority)prio
        };
    }
}
