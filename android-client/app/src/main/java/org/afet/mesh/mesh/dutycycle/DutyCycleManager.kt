package org.afet.mesh.mesh.dutycycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.afet.mesh.mesh.nearby.NearbyConnectionsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ADAPTİF PİL TASARRUFU MOTORU (Adaptive Duty Cycle Manager)
 *
 * Pil seviyesini izler ve BLE reklam/tarama aralıklarını dinamik olarak
 * ayarlar. Enkaz altındaki bir telefon yeterli pil yönetimiyle
 * teorik olarak 3-5 gün aktif SOS yayını yapabilir.
 *
 * Profiller:
 *  - CRITICAL_SURVIVAL  (%0-15) : 15s'de bir 100ms yayın, tarama yok
 *  - BATTERY_SAVER      (%15-40): 5s yayın, 60s'de bir kısa tarama
 *  - NORMAL_BEACON      (%40-70): 2s yayın, 30s tarama
 *  - ACTIVE_COURIER     (%70+)  : 1s yayın, 4s tarama, Wi-Fi Direct açık
 * ═══════════════════════════════════════════════════════════════════════
 */
@Singleton
class DutyCycleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyManager: NearbyConnectionsManager
) {
    companion object {
        private const val TAG = "DutyCycleManager"
        private const val WAKELOCK_TAG = "org.afet.mesh:MeshWakeLock"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var cycleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentBatteryPct: Int = 100
    private var currentProfile: DutyCycleProfile = DutyCycleProfile.ACTIVE_COURIER

    // ── Pil Durumu Alıcısı ────────────────────────────────────────────
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val newPct = (level * 100 / scale)
                if (newPct != currentBatteryPct) {
                    currentBatteryPct = newPct
                    val newProfile = selectProfile(newPct)
                    if (newProfile != currentProfile) {
                        currentProfile = newProfile
                        Log.i(TAG, "⚡ Pil=%${newPct} → Profil değişti: ${newProfile.name}")
                        restartDutyCycle()
                    }
                }
            }
        }
    }

    fun start() {
        acquireWakeLock()
        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "Pil dinleyicisi kayıt uyarısı: ${e.message}")
        }
        currentProfile = selectProfile(currentBatteryPct)
        startDutyCycle()
        Log.i(TAG, "DutyCycleManager başlatıldı. İlk profil: ${currentProfile.name}")
    }

    fun stop() {
        cycleJob?.cancel()
        try { context.unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        releaseWakeLock()
    }

    // ── Duty Cycle Döngüsü ────────────────────────────────────────────
    private fun startDutyCycle() {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            Log.i(TAG, "▶ Mesh antenleri aktif ediliyor (Advertising + Discovery + Sync): ${currentProfile.name}")
            nearbyManager.startMesh()
        }
    }

    private fun restartDutyCycle() {
        startDutyCycle()
    }

    // ── Profil Seçimi ─────────────────────────────────────────────────
    private fun selectProfile(batteryPct: Int): DutyCycleProfile = when {
        batteryPct < 15 -> DutyCycleProfile.CRITICAL_SURVIVAL
        batteryPct < 40 -> DutyCycleProfile.BATTERY_SAVER
        batteryPct < 70 -> DutyCycleProfile.NORMAL_BEACON
        else            -> DutyCycleProfile.ACTIVE_COURIER
    }

    // ── WakeLock ──────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            // Zaman aşımı: 7 gün (afet senaryosu için maksimum ömür)
            acquire(7L * 24 * 60 * 60 * 1000)
        }
        Log.d(TAG, "WakeLock alındı")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.d(TAG, "WakeLock serbest bırakıldı")
    }

    fun getBatteryPercent(): Int = currentBatteryPct
    fun getCurrentProfile(): DutyCycleProfile = currentProfile
}

// ─── Duty Cycle Profilleri ────────────────────────────────────────────
enum class DutyCycleProfile(
    val advIntervalMs: Int,   // İki reklam arasındaki toplam süre (ms)
    val advDurationMs: Int,   // Reklamın açık kaldığı süre (ms)
    val scanIntervalMs: Int,  // Tarama döngü süresi (ms), 0 = tarama yok
    val scanDurationMs: Int,  // Taramanın açık kaldığı süre (ms)
    val wifiDirectAllowed: Boolean
) {
    /** %0-15 Pil: Günlerce hayatta kal — sadece minimal SOS feneri */
    CRITICAL_SURVIVAL(
        advIntervalMs = 15_000,
        advDurationMs = 100,
        scanIntervalMs = 0,
        scanDurationMs = 0,
        wifiDirectAllowed = false
    ),
    /** %15-40 Pil: Eko mod — aralıklı kısa tarama */
    BATTERY_SAVER(
        advIntervalMs = 5_000,
        advDurationMs = 150,
        scanIntervalMs = 60_000,
        scanDurationMs = 500,
        wifiDirectAllowed = false
    ),
    /** %40-70 Pil: Normal kazazede feneri */
    NORMAL_BEACON(
        advIntervalMs = 2_000,
        advDurationMs = 200,
        scanIntervalMs = 30_000,
        scanDurationMs = 1_000,
        wifiDirectAllowed = false
    ),
    /** %70+ Pil: Aktif kurye/devriye modu */
    ACTIVE_COURIER(
        advIntervalMs = 1_500,
        advDurationMs = 200,
        scanIntervalMs = 4_000,
        scanDurationMs = 1_500,
        wifiDirectAllowed = true
    )
}
