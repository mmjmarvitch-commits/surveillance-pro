package com.surveillancepro.android.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiClient(private val storage: DeviceStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun registerDevice(
        deviceId: String,
        deviceName: String,
        userName: String?,
        acceptanceVersion: String,
        acceptanceDate: String
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        val body = mutableMapOf<String, Any>(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "acceptanceVersion" to acceptanceVersion,
            "acceptanceDate" to acceptanceDate,
        )
        userName?.let { body["userName"] = it }

        val request = Request.Builder()
            .url("${storage.serverURL}/api/devices/register")
            .post(gson.toJson(body).toRequestBody(json))
            .build()

        try {
            val response = client.newCall(request).execute()
            Log.d("ApiClient", "Register: ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                Log.w("ApiClient", "Register failed: ${response.code}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(responseBody, Map::class.java) as? Map<String, Any>
        } catch (e: Exception) {
            Log.e("ApiClient", "Register error: ${e.message}")
            null
        }
    }

    suspend fun sendConsent(consentVersion: String, userName: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val token = storage.deviceToken ?: return@withContext false
            val body = mutableMapOf<String, Any>("consentVersion" to consentVersion)
            userName?.let { body["userName"] = it }

            val request = Request.Builder()
                .url("${storage.serverURL}/api/consent")
                .addHeader("x-device-token", token)
                .post(gson.toJson(body).toRequestBody(json))
                .build()

            try {
                client.newCall(request).execute().isSuccessful
            } catch (e: Exception) {
                Log.e("ApiClient", "Consent error: ${e.message}")
                false
            }
        }

    suspend fun sendEvent(type: String, payload: Map<String, Any> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            val token = storage.deviceToken ?: return@withContext false
            val body = mapOf("type" to type, "payload" to payload)

            val request = Request.Builder()
                .url("${storage.serverURL}/api/events")
                .addHeader("x-device-token", token)
                .post(gson.toJson(body).toRequestBody(json))
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    storage.eventCount++
                    true
                } else false
            } catch (e: Exception) {
                Log.w("ApiClient", "Event error: ${e.message}")
                false
            }
        }

    fun pingSync(batteryLevel: Int, batteryState: String): Boolean {
        val token = storage.deviceToken ?: return false
        val body = mapOf("batteryLevel" to batteryLevel, "batteryState" to batteryState)

        val request = Request.Builder()
            .url("${storage.serverURL}/api/devices/ping")
            .addHeader("x-device-token", token)
            .post(gson.toJson(body).toRequestBody(json))
            .build()

        return try {
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    data class SyncResponse(val success: Boolean, val commands: List<Map<String, Any>> = emptyList())

    suspend fun syncBatch(events: List<Map<String, Any>>): SyncResponse =
        withContext(Dispatchers.IO) {
            val token = storage.deviceToken ?: return@withContext SyncResponse(false)
            val body = mapOf("events" to events)

            val request = Request.Builder()
                .url("${storage.serverURL}/api/sync")
                .addHeader("x-device-token", token)
                .post(gson.toJson(body).toRequestBody(json))
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext SyncResponse(false)
                val responseBody = response.body?.string() ?: return@withContext SyncResponse(true)
                @Suppress("UNCHECKED_CAST")
                val jsonResp = gson.fromJson(responseBody, Map::class.java) as? Map<String, Any>
                val commands = (jsonResp?.get("commands") as? List<Map<String, Any>>) ?: emptyList()
                SyncResponse(true, commands)
            } catch (e: Exception) {
                Log.w("ApiClient", "Sync error: ${e.message}")
                SyncResponse(false)
            }
        }

    companion object {
        @Volatile private var instance: ApiClient? = null
        fun getInstance(storage: DeviceStorage): ApiClient =
            instance ?: synchronized(this) {
                instance ?: ApiClient(storage).also { instance = it }
            }
    }
}
