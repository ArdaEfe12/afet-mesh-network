using Afet.Gateway.Hubs;
using Afet.Gateway.Models;
using Microsoft.AspNetCore.SignalR;

namespace Afet.Gateway.Services;

/// <summary>
/// Çözümlenmiş acil durum paketlerini:
///   1. In-memory Triage kuyruğuna ekler (sonraki uydu / internet penceresi için tampon)
///   2. Bağlı AFAD komuta arayüzlerine SignalR WebSocket ile anlık olarak basar
///   3. Kurtarma ekibinin tahmini varışına göre geriye ACK paketi üretir
/// </summary>
public class AfadRelayService
{
    private readonly ILogger<AfadRelayService> _logger;
    private readonly IHubContext<RescueHub> _hubContext;

    // In-memory triage kuyruğu — internet olmayan durumlarda tampon görevi görür
    // Uydu/internet penceresi açıldığında toplu gönderim yapılır
    private readonly List<DeliveredEmergency> _pendingDeliveries = [];
    private readonly SemaphoreSlim _lock = new(1, 1);

    // Daha önce işlenmiş mesaj ID'leri (duplicate gönderim engeli)
    private readonly HashSet<string> _processedIds = [];

    public AfadRelayService(ILogger<AfadRelayService> logger, IHubContext<RescueHub> hubContext)
    {
        _logger = logger;
        _hubContext = hubContext;
    }

    /// <summary>
    /// Yeni bir acil durum paketini triage kuyruğuna ekler ve
    /// bağlı AFAD arayüzlerine anlık bildirim gönderir.
    /// </summary>
    public async Task IngestAsync(DeliveredEmergency emergency, CancellationToken ct = default)
    {
        await _lock.WaitAsync(ct);
        try
        {
            // Duplicate kontrol
            if (_processedIds.Contains(emergency.MessageId))
            {
                _logger.LogDebug("[Relay] Zaten işlendi, atlandı: {Id}", emergency.MessageId);
                return;
            }

            _processedIds.Add(emergency.MessageId);
            _pendingDeliveries.Add(emergency);

            // Triage öncelik sıralaması
            _pendingDeliveries.Sort((a, b) => a.Priority.CompareTo(b.Priority));

            _logger.LogInformation(
                "[Relay] 🚨 Yeni acil paket kuyruğa alındı | ID={Id} | Enkaz={Trapped} | Kişi={Cnt} | Konum={Lat:F5},{Lon:F5}",
                emergency.MessageId,
                emergency.IsTrapped,
                emergency.PeopleCount,
                emergency.Latitude,
                emergency.Longitude);
        }
        finally
        {
            _lock.Release();
        }

        // SignalR ile AFAD komuta ekranına anlık gönderim
        await _hubContext.Clients.All.SendAsync(
            "NewEmergency",
            new
            {
                emergency.MessageId,
                emergency.Latitude,
                emergency.Longitude,
                emergency.AccuracyMeters,
                emergency.IsTrapped,
                emergency.IsInjured,
                emergency.IsConscious,
                emergency.PeopleCount,
                emergency.BatteryPercent,
                emergency.MessageText,
                emergency.BloodType,
                emergency.TotalHops,
                emergency.Priority,
                ReceivedAt = emergency.ReceivedAt.ToUnixTimeSeconds()
            },
            ct);
    }

    /// <summary>
    /// Triage kuyruğundaki acil durumları döndürür (AFAD REST arayüzü için).
    /// </summary>
    public async Task<IReadOnlyList<DeliveredEmergency>> GetPendingAsync()
    {
        await _lock.WaitAsync();
        try { return _pendingDeliveries.AsReadOnly(); }
        finally { _lock.Release(); }
    }

    /// <summary>
    /// Bir acil durumu "Kurtarma ekibi yola çıktı" olarak işaretler ve
    /// ACK paketini üretir (ağa yayılmak üzere JSON byte[] olarak döner).
    /// Not: İlk dotnet build'da Protobuf codegen çalışmadan önce
    /// JSON envelope kullanılır; build sonrası Protobuf AckPacket'e geçilir.
    /// </summary>
    public async Task<byte[]?> AcknowledgeAndGenerateAckAsync(string messageId)
    {
        await _lock.WaitAsync();
        DeliveredEmergency? target = null;
        try
        {
            target = _pendingDeliveries.FirstOrDefault(e => e.MessageId == messageId);
            if (target is not null)
            {
                _pendingDeliveries.Remove(target);
                _logger.LogInformation("[Relay] ✅ ACK üretildi: {Id}", messageId);
            }
        }
        finally
        {
            _lock.Release();
        }

        if (target is null) return null;

        // ACK paketi — şimdilik JSON envelope; Protobuf codegen sonrası
        // bu blok AckPacket.ToByteArray() ile değiştirilecek.
        var ackJson = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new
        {
            type = "ACK",
            ackedId = messageId,
            gatewayNodeId = "AFAD-UYDU-01",
            deliveryHopCount = target.TotalHops,
            estimatedRescueEpoch = 0
        });

        return ackJson;
    }
}
