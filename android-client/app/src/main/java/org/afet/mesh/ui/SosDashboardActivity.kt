package org.afet.mesh.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.afet.mesh.service.MeshForegroundService

@AndroidEntryPoint
class SosDashboardActivity : ComponentActivity() {

    private val viewModel: SosViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.i("SosDashboardActivity", "İzinler sonuçlandı: $permissions")
        viewModel.startMesh()
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
            val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
            val isLocationEnabled by viewModel.isLocationEnabled.collectAsState()

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
                isBluetoothEnabled = isBluetoothEnabled,
                isLocationEnabled = isLocationEnabled,
                onSosToggle = { viewModel.toggleSos() },
                onAddMessage = { viewModel.addCustomMessage(it) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkHardwareStatus()
        if (hasMeshPermissions()) {
            viewModel.startMesh()
            startMeshService()
        }
    }

    private fun hasMeshPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocation) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!scan || !adv || !conn) return false
        }
        return true
    }

    private fun startMeshService() {
        try {
            Intent(this, MeshForegroundService::class.java).apply {
                action = MeshForegroundService.ACTION_START
                startForegroundService(this)
            }
        } catch (e: Exception) {
            Log.e("SosDashboardActivity", "Servis başlatılamadı: ${e.message}")
        }
    }

    private fun requestMeshPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
