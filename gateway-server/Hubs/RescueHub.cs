using Microsoft.AspNetCore.SignalR;

namespace Afet.Gateway.Hubs;

/// <summary>
/// AFAD Komuta Merkezi canlı yayın kanalı (SignalR WebSocket Hub).
/// Bağlı tüm AFAD arayüzlerine (tarayıcı, tablet) gerçek zamanlı bildirim gönderir.
/// </summary>
public class RescueHub : Hub
{
    public override async Task OnConnectedAsync()
    {
        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        await base.OnDisconnectedAsync(exception);
    }
}
