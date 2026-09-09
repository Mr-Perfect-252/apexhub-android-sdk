package com.apexhub.sdk

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP wrapper around the ApexHub REST API.
 * All network calls are dispatched on [Dispatchers.IO].
 */
internal class ApexHubApi(private val config: ApexHubConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Calls GET /api/update/{packageName}?channel=stable&installed={versionCode}
     * Returns parsed [UpdateInfo] or throws on network / API error.
     */
    suspend fun checkForUpdate(packageName: String, installedVersionCode: Int): UpdateInfo =
        withContext(Dispatchers.IO) {
            val baseUrl = config.baseUrl.trimEnd('/')
            val url = "$baseUrl/api/update/${packageName}" +
                    "?channel=${config.channel}" +
                    "&installed=$installedVersionCode"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Api-Key", config.publicKey)
                .addHeader("X-Sdk-Version", BuildConfig.SDK_VERSION)
                .addHeader("X-Platform", "android")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: throw ApexHubException("Empty response from server (HTTP ${response.code})")

            if (!response.isSuccessful) {
                val errorMsg = try {
                    gson.fromJson(body, JsonObject::class.java)
                        ?.get("error")?.asString ?: "HTTP ${response.code}"
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                throw ApexHubException("Update check failed: $errorMsg")
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val available = json.get("updateAvailable")?.asBoolean ?: false

            UpdateInfo(
                updateAvailable = available,
                latestVersion = json.get("latestVersion")?.asString,
                versionCode = json.get("versionCode")?.asInt,
                downloadUrl = json.get("downloadUrl")?.asString,
                sha256 = json.get("sha256")?.asString,
                mandatory = json.get("mandatory")?.asBoolean ?: false,
                releaseNotes = json.get("releaseNotes")?.asString,
                rolloutPercent = json.get("rolloutPercent")?.asInt,
                channel = json.get("channel")?.asString,
                certificateFingerprint = json.get("certificateFingerprint")?.asString,
            )
        }

    /**
     * Posts an analytics event to POST /api/analytics/event.
     * Fire-and-forget — callers should not await the result critically.
     */
    suspend fun sendAnalyticsEvent(
        appId: String,
        eventType: String,
        eventName: String,
        deviceId: String,
        osVersion: String,
        deviceModel: String,
        sessionSec: Int,
        metadata: Map<String, Any> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        try {
            val baseUrl = config.baseUrl.trimEnd('/')
            val payload = gson.toJson(
                mapOf(
                    "app_id" to appId,
                    "event_type" to eventType,
                    "event_name" to eventName,
                    "device_id" to deviceId,
                    "os_version" to osVersion,
                    "device_model" to deviceModel,
                    "session_sec" to sessionSec,
                    "metadata" to metadata,
                )
            )
            val body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"), payload
            )
            val request = Request.Builder()
                .url("$baseUrl/api/analytics/event")
                .post(body)
                .addHeader("X-Api-Key", config.publicKey)
                .addHeader("X-Sdk-Version", BuildConfig.SDK_VERSION)
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Analytics failures are always silent
        }
    }
}

class ApexHubException(message: String, cause: Throwable? = null) : Exception(message, cause)
