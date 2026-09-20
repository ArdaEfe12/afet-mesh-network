namespace Afet.Gateway.Models;

/// <summary>
/// Kurtarma merkezine ulaşan ve deşifre edilmiş acil durum verisi.
/// Room veritabanı veya in-memory hub üzerinden AFAD arayüzüne aktarılır.
/// </summary>
public record DeliveredEmergency
{
    /// <summary>12-byte binary message_id — hex olarak temsil edilir.</summary>
    public required string MessageId { get; init; }

    /// <summary>Enkaz kazazedesinin cihaz açık anahtarı (Curve25519 hex).</summary>
    public required string SenderPublicKeyHex { get; init; }

    public DateTimeOffset ReceivedAt { get; init; } = DateTimeOffset.UtcNow;

    // ── Konum ──
    /// <summary>Enlem (1e-6 hassasiyet ile çözülmüş float).</summary>
    public double Latitude { get; init; }
    public double Longitude { get; init; }
    public int AccuracyMeters { get; init; }
    public int AltitudeMeters { get; init; }

    // ── Durum ──
    public bool IsTrapped { get; init; }
    public bool IsInjured { get; init; }
    public bool IsConscious { get; init; }
    public int PeopleCount { get; init; }
    public int BatteryPercent { get; init; }

    // ── Serbest Metin / Tıbbi ──
    public string? MessageText { get; init; }
    public string? BloodType { get; init; }
    public string? MedicalNotes { get; init; }

    // ── Yönlendirme Bilgisi ──
    public int TotalHops { get; init; }
    public PacketPriority Priority { get; init; }
}

/// <summary>Triage öncelik seviyesi — kurtarma haritasında renk kodlaması için kullanılır.</summary>
public enum PacketPriority
{
    Unknown = 0,
    CriticalSos = 1,
    MedicalAid = 2,
    FamilySafe = 3,
    RelayHeartbeat = 4,
    AckDelivered = 5
}
