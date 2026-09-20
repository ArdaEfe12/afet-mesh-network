package org.afet.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.afet.mesh.mesh.dutycycle.DutyCycleManager
import org.afet.mesh.mesh.routing.EpidemicRouter
import org.afet.mesh.ui.SosDashboardActivity
import javax.inject.Inject

/**
 * ═══════════════════════════════════════════════════════════════════════
 * AFET MESH ARKA PLAN SERVİSİ (Foreground Service)
 *
 * - Ekran kapandığında bile çalışmaya devam eder (PARTIAL_WAKE_LOCK)
 * - Android Doze Mode'u aşmak için Foreground Service tipi "connectedDevice"
 * - Cihaz yeniden başladığında BootReceiver aracılığıyla otomatik başlar
 * - DutyCycleManager ve EpidemicRouter'ı yönetir
 * ═══════════════════════════════════════════════════════════════════════
 */
@AndroidEntryPoint
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundSvc"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "afet_mesh_channel"
        private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L // 5 dakika

        const val ACTION_START = "org.afet.mesh.ACTION_START_MESH"
        const val ACTION_STOP  = "org.afet.mesh.ACTION_STOP_MESH"
    }

    @Inject lateinit var dutyCycleManager: DutyCycleManager
    @Inject lateinit var epidemicRouter: EpidemicRouter

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "MeshForegroundService oluşturuldu")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMeshNetwork()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("🛡️ Afet Mesh Ağı Aktif"))
                startMeshNetwork()
            }
        }
        return START_STICKY // Servis öldürülse bile sistem tarafından yeniden başlatılır
    }

    private fun startMeshNetwork() {
        Log.i(TAG, "Mesh ağı başlatılıyor...")
        dutyCycleManager.start()

        // Periyodik temizleme (teslim edilmiş / süresi dolmuş paketler)
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                epidemicRouter.schedulePeriodicalCleanup()
            }
        }
    }

    private fun stopMeshNetwork() {
        Log.i(TAG, "Mesh ağı durduruluyor...")
        dutyCycleManager.stop()
        cleanupJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMeshNetwork()
    }

    // ── Bildirim ──────────────────────────────────────────────────────
    private fun buildNotification(text: String): Notification {
        val dashboardIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SosDashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MeshForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Afet Mesh Ağı")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setContentIntent(dashboardIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "Durdur", stopIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Afet Mesh Ağı",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "İnternetsiz acil durum mesh ağı arka plan servisi"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

// ─── Boot Receiver — Cihaz Açıldığında Servisi Başlat ─────────────────
@AndroidEntryPoint
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Cihaz yeniden başlatıldı — Mesh servisi başlatılıyor")
            Intent(context, MeshForegroundService::class.java).apply {
                action = MeshForegroundService.ACTION_START
                context.startForegroundService(this)
            }
        }
    }
}
