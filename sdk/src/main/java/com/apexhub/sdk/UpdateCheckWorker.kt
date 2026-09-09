package com.apexhub.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that runs a background update check.
 * When a new version is found it posts a system notification so the user
 * can tap to open the app and install.
 *
 * Scheduled by [ApexHubUpdater.schedulePeriodicCheck].
 */
internal class UpdateCheckWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "apexhub_update_check"
        const val CHANNEL_ID = "apexhub_updates"
        const val NOTIF_ID = 0x4150_4558 // "APEX" in hex

        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_PUBLIC_KEY = "public_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_CHANNEL = "channel"
        const val KEY_INSTALLED_VERSION_CODE = "installed_version_code"
        const val KEY_APP_DISPLAY_NAME = "app_display_name"
    }

    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE_NAME) ?: return Result.failure()
        val publicKey = inputData.getString(KEY_PUBLIC_KEY) ?: return Result.failure()
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: BuildConfig.DEFAULT_BASE_URL
        val channel = inputData.getString(KEY_CHANNEL) ?: "stable"
        val installed = inputData.getInt(KEY_INSTALLED_VERSION_CODE, 0)
        val appName = inputData.getString(KEY_APP_DISPLAY_NAME) ?: "App"

        return try {
            val config = ApexHubConfig(
                publicKey = publicKey,
                packageName = packageName,
                channel = channel,
                baseUrl = baseUrl,
            )
            val api = ApexHubApi(config)
            val info = api.checkForUpdate(packageName, installed)

            if (info.updateAvailable) {
                postUpdateNotification(appName, info)
            }
            Result.success()
        } catch (e: Exception) {
            // Retry once on transient network failures
            if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
    }

    private fun postUpdateNotification(appName: String, info: UpdateInfo) {
        ensureNotificationChannel()

        // Deep-link into the host app's launcher
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }

        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$appName update available")
            .setContentText(
                "Version ${info.latestVersion} is ready to install." +
                if (!info.releaseNotes.isNullOrBlank()) " — ${info.releaseNotes}" else ""
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for available app updates from ApexHub"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
