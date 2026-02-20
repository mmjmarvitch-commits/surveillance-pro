package com.surveillancepro.android.services

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de Geofencing pour alertes de zone.
 * Permet de definir des zones geographiques et d'etre alerte
 * quand l'appareil entre ou sort de ces zones.
 *
 * Cas d'usage :
 * - Alerte si l'employe quitte le perimetre de l'entreprise
 * - Alerte si l'appareil entre dans une zone interdite
 * - Suivi des deplacements entre sites
 */
object GeofenceService {

    private const val TAG = "GeofenceService"
    private const val GEOFENCE_EXPIRATION = Geofence.NEVER_EXPIRE
    private const val LOITERING_DELAY_MS = 60000 // 1 minute avant de declencher DWELL

    private var geofencingClient: GeofencingClient? = null
    private val activeGeofences = mutableMapOf<String, GeofenceZone>()

    data class GeofenceZone(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
        val transitionTypes: Int = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
        val alertOnEnter: Boolean = true,
        val alertOnExit: Boolean = true,
    )

    /**
     * Initialise le client Geofencing.
     */
    fun initialize(context: Context) {
        geofencingClient = LocationServices.getGeofencingClient(context)
    }

    /**
     * Ajoute une zone de geofencing.
     */
    fun addGeofence(context: Context, zone: GeofenceZone): Boolean {
        if (geofencingClient == null) initialize(context)

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted")
            return false
        }

        val geofence = Geofence.Builder()
            .setRequestId(zone.id)
            .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters)
            .setExpirationDuration(GEOFENCE_EXPIRATION)
            .setTransitionTypes(zone.transitionTypes)
            .setLoiteringDelay(LOITERING_DELAY_MS)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = getGeofencePendingIntent(context)

        geofencingClient?.addGeofences(request, pendingIntent)?.run {
            addOnSuccessListener {
                activeGeofences[zone.id] = zone
                Log.d(TAG, "Geofence added: ${zone.name} (${zone.radiusMeters}m)")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to add geofence: ${e.message}")
            }
        }

        return true
    }

    /**
     * Supprime une zone de geofencing.
     */
    fun removeGeofence(context: Context, zoneId: String) {
        if (geofencingClient == null) initialize(context)
        geofencingClient?.removeGeofences(listOf(zoneId))?.run {
            addOnSuccessListener {
                activeGeofences.remove(zoneId)
                Log.d(TAG, "Geofence removed: $zoneId")
            }
        }
    }

    /**
     * Supprime toutes les zones.
     */
    fun removeAllGeofences(context: Context) {
        if (geofencingClient == null) return
        geofencingClient?.removeGeofences(getGeofencePendingIntent(context))?.run {
            addOnSuccessListener {
                activeGeofences.clear()
                Log.d(TAG, "All geofences removed")
            }
        }
    }

    /**
     * Retourne la liste des zones actives.
     */
    fun getActiveZones(): List<GeofenceZone> = activeGeofences.values.toList()

    /**
     * Configure les zones depuis le serveur.
     */
    fun configureFromServer(context: Context, zones: List<Map<String, Any>>) {
        // Supprimer les anciennes zones
        removeAllGeofences(context)

        // Ajouter les nouvelles
        for (zoneData in zones) {
            try {
                val zone = GeofenceZone(
                    id = zoneData["id"]?.toString() ?: continue,
                    name = zoneData["name"]?.toString() ?: "Zone",
                    latitude = (zoneData["latitude"] as? Number)?.toDouble() ?: continue,
                    longitude = (zoneData["longitude"] as? Number)?.toDouble() ?: continue,
                    radiusMeters = (zoneData["radius"] as? Number)?.toFloat() ?: 100f,
                    alertOnEnter = zoneData["alertOnEnter"] as? Boolean ?: true,
                    alertOnExit = zoneData["alertOnExit"] as? Boolean ?: true,
                )
                addGeofence(context, zone)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid zone data: ${e.message}")
            }
        }
    }

    private fun getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Traite un evenement de geofencing.
     */
    internal fun handleGeofenceEvent(context: Context, event: GeofencingEvent) {
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: ${event.errorCode}")
            return
        }

        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val transitionType = event.geofenceTransition
        val triggeringGeofences = event.triggeringGeofences ?: return

        val transitionName = when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
            else -> "unknown"
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(context)
        val location = event.triggeringLocation

        for (geofence in triggeringGeofences) {
            val zoneId = geofence.requestId
            val zone = activeGeofences[zoneId]

            // Verifier si on doit alerter pour ce type de transition
            val shouldAlert = when (transitionType) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> zone?.alertOnEnter ?: true
                Geofence.GEOFENCE_TRANSITION_EXIT -> zone?.alertOnExit ?: true
                else -> true
            }

            if (!shouldAlert) continue

            queue.enqueue("geofence_alert", mapOf(
                "zoneId" to zoneId,
                "zoneName" to (zone?.name ?: zoneId),
                "transition" to transitionName,
                "latitude" to (location?.latitude ?: 0.0),
                "longitude" to (location?.longitude ?: 0.0),
                "accuracy" to (location?.accuracy ?: 0f),
                "timestamp" to timestamp,
            ))

            Log.d(TAG, "Geofence $transitionName: ${zone?.name ?: zoneId}")
            
            // CRITIQUE: Sync immediat pour les alertes geofence
            SyncWorker.triggerNow(context)
        }
    }
}

/**
 * BroadcastReceiver pour les evenements de geofencing.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        GeofenceService.handleGeofenceEvent(context, event)
    }
}
