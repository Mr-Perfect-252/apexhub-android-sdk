package com.apexhub.sdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw ApexHubException("APK download failed: HTTP ${response.code}")
        }

        val body = response.body
            ?: throw ApexHubException("APK download returned empty body")

        val contentLength = body.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L

        body.byteStream().use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
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

        onProgress?.invoke(100)

        // SHA-256 verification
        if (!expectedSha256.isNullOrBlank()) {
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
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

        destFile
    }

    /** Cleans up previously downloaded APK files. */
    fun clearCache() {
        downloadDir.listFiles()?.forEach { it.delete() }
    }
}
