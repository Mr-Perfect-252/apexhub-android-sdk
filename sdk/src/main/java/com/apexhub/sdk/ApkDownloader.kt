package com.apexhub.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads an APK from a given URL into the app's cache directory
 * and optionally verifies its SHA-256 digest.
 */
internal class ApkDownloader(context: Context) {

    private val downloadDir = File(context.cacheDir, "apexhub/downloads").also { it.mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // APKs can be large
        .followRedirects(true)
        .build()

    /**
     * Downloads the APK at [url] and returns the local [File].
     * Reports progress (0–100) via [onProgress].
     * Verifies SHA-256 if [expectedSha256] is not null/blank.
     *
     * @throws ApexHubException on network errors or hash mismatches.
     */
    suspend fun download(
        url: String,
        versionName: String,
        expectedSha256: String?,
        onProgress: ((Int) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val destFile = File(downloadDir, "apexhub-update-$versionName.apk")

        Log.d(TAG, "Starting APK download: url=$url -> ${destFile.absolutePath}")

        val response = try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute()
        } catch (e: IllegalArgumentException) {
            // Malformed / empty download URL
            throw ApexHubException("Invalid download URL: $url", e)
        } catch (e: IOException) {
            // Network failure: timeout, DNS, TLS, dropped connection, etc.
            throw ApexHubException("Network error while downloading update: ${e.message}", e)
        }

        response.use {
            if (!it.isSuccessful) {
                throw ApexHubException("APK download failed: HTTP ${it.code}")
            }

            val body = it.body
                ?: throw ApexHubException("APK download returned empty body")

            val contentLength = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L

            try {
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { r -> read = r } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                val progress = ((bytesRead * 100) / contentLength).toInt()
                                onProgress?.invoke(progress.coerceIn(0, 99))
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                destFile.delete()
                throw ApexHubException("Failed while saving downloaded update: ${e.message}", e)
            }

            onProgress?.invoke(100)

            if (bytesRead == 0L) {
                destFile.delete()
                throw ApexHubException("Downloaded update was empty (0 bytes)")
            }

            // SHA-256 verification
            if (!expectedSha256.isNullOrBlank()) {
                val actualHash = digest.digest().joinToString("") { b -> "%02x".format(b) }
                if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                    destFile.delete()
                    throw ApexHubException(
                        "SHA-256 integrity check failed!\n" +
                        "Expected: $expectedSha256\n" +
                        "Got:      $actualHash\n" +
                        "The downloaded APK has been deleted for your safety."
                    )
                }
            }

            Log.d(TAG, "APK download complete: ${destFile.length()} bytes")
            destFile
        }
    }

    /** Cleans up previously downloaded APK files. */
    fun clearCache() {
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "ApexHub"
    }
}
