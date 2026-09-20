using Afet.Gateway.Hubs;
using Afet.Gateway.Services;
using Afet.Gateway.Models;

var builder = WebApplication.CreateBuilder(args);

// ── Servis Kayıtları ──
builder.Services.AddSingleton<PacketDecoderService>();
builder.Services.AddSingleton<AfadRelayService>();
builder.Services.AddSignalR();

var app = builder.Build();

// ── SignalR Hub Rotası (AFAD Harita Arayüzü WebSocket) ──
app.MapHub<RescueHub>("/hubs/rescue");

// ─────────────────────────────────────────────────────────────────────
// ENDPOINT 1: Sağlık Kontrolü
// GET /health
// ─────────────────────────────────────────────────────────────────────
app.MapGet("/health", (PacketDecoderService decoder) =>
{
    return Results.Ok(new
    {
        Status = "online",
        Role = "AFAD_GATEWAY",
        GatewayPublicKeyHex = Convert.ToHexString(decoder.GatewayPublicKey),
        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
    });
});

// ─────────────────────────────────────────────────────────────────────
// ENDPOINT 2: Paket Gelen Kutusu (BLE / Wi-Fi payload ingest)
// POST /ingest
// Body: Ham Protobuf MeshPacket bayt dizisi (application/octet-stream)
// ─────────────────────────────────────────────────────────────────────
app.MapPost("/ingest", async (
    HttpRequest request,
    PacketDecoderService decoder,
    AfadRelayService relay,
    ILogger<Program> logger,
    CancellationToken ct) =>
{
    // Ham ikili veriyi oku
    using var ms = new MemoryStream();
    await request.Body.CopyToAsync(ms, ct);
    var rawBytes = ms.ToArray();

    if (rawBytes.Length == 0)
        return Results.BadRequest(new { Error = "Boş paket" });

    logger.LogInformation("[Ingest] {Bytes} byte alındı", rawBytes.Length);

    // Çöz → Doğrula → Deşifre et
    var emergency = decoder.DecodePacket(rawBytes);
    if (emergency is null)
        return Results.UnprocessableEntity(new { Error = "Paket çözümlenemedi veya doğrulama başarısız" });

    // Triage kuyruğuna ekle ve AFAD arayüzüne push
    await relay.IngestAsync(emergency, ct);

    return Results.Created($"/emergencies/{emergency.MessageId}", new
    {
        emergency.MessageId,
        emergency.Latitude,
        emergency.Longitude,
        emergency.IsTrapped,
        emergency.PeopleCount,
        emergency.Priority
    });
});

// ─────────────────────────────────────────────────────────────────────
// ENDPOINT 3: Bekleyen Triage Listesi
// GET /emergencies
// ─────────────────────────────────────────────────────────────────────
app.MapGet("/emergencies", async (AfadRelayService relay) =>
{
    var pending = await relay.GetPendingAsync();
    return Results.Ok(pending);
});

// ─────────────────────────────────────────────────────────────────────
// ENDPOINT 4: ACK / Teslimat Onayı Üret (Ağa Yayılacak Bayt Dizisi)
// POST /ack/{messageId}
// Body: boş (mesaj ID URL'den alınır)
// Response: ham Protobuf AckPacket bayt dizisi (application/octet-stream)
// ─────────────────────────────────────────────────────────────────────
app.MapPost("/ack/{messageId}", async (
    string messageId,
    AfadRelayService relay,
    ILogger<Program> logger) =>
{
    var ackBytes = await relay.AcknowledgeAndGenerateAckAsync(messageId);
    if (ackBytes is null)
        return Results.NotFound(new { Error = $"{messageId} ID'li paket bulunamadı veya zaten ACK'landı" });

    logger.LogInformation("[ACK] Yayılmak üzere ACK paketi üretildi: {Id}", messageId);
    return Results.Bytes(ackBytes, "application/octet-stream");
});

// ─────────────────────────────────────────────────────────────────────
// ENDPOINT 5: Anti-Entropy Envanter Takası (Hangi mesajları biliyoruz?)
// GET /inventory
// Response: JSON — Kurtarma merkezinin sahip olduğu message_id listesi
// ─────────────────────────────────────────────────────────────────────
app.MapGet("/inventory", async (AfadRelayService relay) =>
{
    var pending = await relay.GetPendingAsync();
    var ids = pending.Select(e => e.MessageId).ToArray();
    return Results.Ok(new { KnownMessageIds = ids, Count = ids.Length });
});

app.Run();
