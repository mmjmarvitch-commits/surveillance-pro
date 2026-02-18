package com.surveillancepro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de localisation A LA DEMANDE uniquement.
 * Ne tourne PAS en permanence. S'active quand l'admin envoie une commande "locate",
 * récupère une seule position, l'envoie au serveur, puis s'arrête.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Supervision Pro")
            .setContentText("Localisation en cours…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        fetchSingleLocation()
        return START_NOT_STICKY
    }

    private fun fetchSingleLocation() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .setDurationMillis(30000)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                fusedClient.removeLocationUpdates(this)
                sendLocationAndStop(location.latitude, location.longitude, location.accuracy.toDouble())
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun sendLocationAndStop(lat: Double, lng: Double, accuracy: Double) {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) { stopSelf(); return }

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                val api = ApiClient.getInstance(storage)
                api.sendEvent("location", mapOf(
                    "latitude" to lat,
                    "longitude" to lng,
                    "accuracy" to accuracy,
                    "batteryLevel" to getBatteryLevel(),
                    "source" to "on_demand",
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Localisation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Localisation ponctuelle de l'appareil professionnel"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "supervision_pro_location"
        const val NOTIFICATION_ID = 1001
    }
}
