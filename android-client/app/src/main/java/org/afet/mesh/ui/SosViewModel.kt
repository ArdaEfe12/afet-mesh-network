package org.afet.mesh.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.afet.mesh.data.local.MessageDao
import org.afet.mesh.mesh.dutycycle.DutyCycleManager
import org.afet.mesh.mesh.dutycycle.DutyCycleProfile
import org.afet.mesh.mesh.nearby.NearbyConnectionsManager
import org.afet.mesh.mesh.routing.EpidemicRouter
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SosViewModel @Inject constructor(
    application: Application,
    private val epidemicRouter: EpidemicRouter,
    private val nearbyManager: NearbyConnectionsManager,
    private val dutyCycleManager: DutyCycleManager,
    private val messageDao: MessageDao
) : AndroidViewModel(application) {

    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive

    private val _batteryPercent = MutableStateFlow(100)
    val batteryPercent: StateFlow<Int> = _batteryPercent

    private val _lastKnownLocation = MutableStateFlow("")
    val lastKnownLocation: StateFlow<String> = _lastKnownLocation

    private val _deliveredCount = MutableStateFlow(0)
    val deliveredCount: StateFlow<Int> = _deliveredCount

    val connectedPeers: StateFlow<Int> = nearbyManager.connectedPeerCount

    val currentProfile: StateFlow<DutyCycleProfile> = flow {
        while (true) {
            emit(dutyCycleManager.getCurrentProfile())
            kotlinx.coroutines.delay(5_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DutyCycleProfile.NORMAL_BEACON)

    val pendingQueueSize: StateFlow<Int> = messageDao.observePendingMessages()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        startLocationUpdates()
        syncBattery()
    }

    fun toggleSos() {
        _isSosActive.value = !_isSosActive.value

        if (_isSosActive.value) {
            viewModelScope.launch { enqueueSosPacket() }
        } else {
            // SOS durduruldu — opsiyonel: "Güvendeyim" paketi gönder
        }
    }

    fun addCustomMessage(text: String) {
        viewModelScope.launch {
            enqueueSosPacket(customMessage = text)
        }
    }

    private suspend fun enqueueSosPacket(customMessage: String? = null) {
        val loc = lastKnownLocation.value
        val parts = loc.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.0
        val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0

        val messageId = UUID.randomUUID().toString().replace("-", "").take(24)
        val message = customMessage ?: "Enkaz altındayım, yardım gerekiyor!"

        // TODO: Aşama 2'de Protobuf serileştirme burada yapılacak
        // Şimdilik JSON placeholder baytı oluştur
        val rawProto = buildString {
            append("{\"type\":\"SOS\",\"msg\":\"$message\",\"lat\":$lat,\"lon\":$lon}")
        }.toByteArray()

        epidemicRouter.enqueueOwnSos(
            messageId = messageId,
            rawProtoBytes = rawProto,
            latitude = lat,
            longitude = lon,
            senderId = nearbyManager.localNodeId
        )

        Log.i("SosViewModel", "🚨 SOS kuyruğa eklendi: $messageId")
    }

    private fun startLocationUpdates() {
        val lm = getApplication<Application>()
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _lastKnownLocation.value =
                    "%.5f, %.5f".format(location.latitude, location.longitude)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                30_000L,   // 30 saniyede bir güncelle (pil tasarrufu)
                5f,         // 5 metre hareket eşiği
                listener
            )
        } catch (e: SecurityException) {
            Log.w("SosViewModel", "Konum izni yok: ${e.message}")
        }
    }

    private fun syncBattery() {
        viewModelScope.launch {
            while (true) {
                _batteryPercent.value = dutyCycleManager.getBatteryPercent()
                kotlinx.coroutines.delay(30_000) // 30 saniyede bir güncelle
            }
        }
    }
}
