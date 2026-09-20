package org.afet.mesh.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import org.afet.mesh.service.MeshForegroundService

@AndroidEntryPoint
class SosDashboardActivity : ComponentActivity() {

    private val viewModel: SosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekran her zaman açık kalsın — kazazede elinden bırakmış olabilir
        // (DutyCycleManager zaten pilin kritik olduğunda bunu devre dışı bırakır)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Mesh servisini başlat
        Intent(this, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START
            startForegroundService(this)
        }

        setContent {
            val isSosActive by viewModel.isSosActive.collectAsState()
            val batteryPct by viewModel.batteryPercent.collectAsState()
            val connectedPeers by viewModel.connectedPeers.collectAsState()
            val pendingQueue by viewModel.pendingQueueSize.collectAsState()
            val profile by viewModel.currentProfile.collectAsState()
            val location by viewModel.lastKnownLocation.collectAsState()
            val delivered by viewModel.deliveredCount.collectAsState()

            SosDashboard(
                isSosActive = isSosActive,
                batteryPct = batteryPct,
                connectedPeers = connectedPeers,
                pendingQueueSize = pendingQueue,
                currentProfile = profile,
                lastKnownLocation = location,
                deliveredCount = delivered,
                onSosToggle = { viewModel.toggleSos() },
                onAddMessage = { viewModel.addCustomMessage(it) }
            )
        }
    }
}
