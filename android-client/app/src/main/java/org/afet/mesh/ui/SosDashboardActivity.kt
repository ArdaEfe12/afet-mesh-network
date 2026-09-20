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

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        android.util.Log.i("SosDashboardActivity", "İzinler sonuçlandı: $permissions")
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestMeshPermissions()
        startMeshService()

        setContent {
            val isSosActive by viewModel.isSosActive.collectAsState()
            val batteryPct by viewModel.batteryPercent.collectAsState()
            val connectedPeers by viewModel.connectedPeers.collectAsState()
            val pendingQueue by viewModel.pendingQueueSize.collectAsState()
            val profile by viewModel.currentProfile.collectAsState()
            val location by viewModel.lastKnownLocation.collectAsState()
            val delivered by viewModel.deliveredCount.collectAsState()
            val nearbyEmergencies by viewModel.nearbyEmergencies.collectAsState()

            SosDashboard(
                isSosActive = isSosActive,
                batteryPct = batteryPct,
                connectedPeers = connectedPeers,
                pendingQueueSize = pendingQueue,
                currentProfile = profile,
                lastKnownLocation = location,
                deliveredCount = delivered,
                nearbyEmergencies = nearbyEmergencies,
                localDeviceModel = viewModel.deviceModel,
                onSosToggle = { viewModel.toggleSos() },
                onAddMessage = { viewModel.addCustomMessage(it) }
            )
        }
    }

    private fun startMeshService() {
        try {
            Intent(this, MeshForegroundService::class.java).apply {
                action = MeshForegroundService.ACTION_START
                startForegroundService(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("SosDashboardActivity", "Servis başlatılamadı: ${e.message}")
        }
    }

    private fun requestMeshPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

