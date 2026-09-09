package com.apexhub.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  ApexHubUpdater — Main entry point for the ApexHub Android SDK  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Typical usage in your MainActivity:
 *
 * ```kotlin
 * val updater = ApexHubUpdater(
 *     context = this,
 *     config = ApexHubConfig(
 *         publicKey = "pk_live_YOUR_KEY",   // from ApexHub Console
 *     )
 * )
 *
 * // One-liner: check → prompt → download → install
 * lifecycleScope.launch {
 *     updater.checkAndPrompt(activity = this@MainActivity)
 * }
 *
 * // Or hook into each stage manually:
 * lifecycleScope.launch {
 *     updater.checkAndUpdate(
 *         onUpdateFound = { info -> showMyCustomDialog(info) },
 *         onProgress    = { percent -> progressBar.progress = percent },
 *         onReadyToInstall = { activity -> ApkInstaller.install(activity, it) },
 *         onError       = { e -> Log.e("ApexHub", e.message) }
 *     )
 * }
 * ```
 */
class ApexHubUpdater(
    private val context: Context,
    private val config: ApexHubConfig,
) {
    private val api = ApexHubApi(config)
    private val downloader = ApkDownloader(context)

    /** Resolved package name — falls back to the host app's package name. */
    private val resolvedPackageName: String
        get() = config.packageName ?: context.packageName

    /** The currently installed versionCode from PackageManager. */
    private val installedVersionCode: Int
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(resolvedPackageName, 0)
                    .longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(resolvedPackageName, 0)
                    .versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) { 0 }

    // ──────────────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Checks the ApexHub backend for an update.
     * Returns a [UpdateCheckResult] — never throws.
     */
    suspend fun checkForUpdate(): UpdateCheckResult = try {
        val info = api.checkForUpdate(resolvedPackageName, installedVersionCode)
        if (info.updateAvailable) UpdateCheckResult.UpdateAvailable(info)
        else UpdateCheckResult.UpToDate
    } catch (e: Exception) {
        UpdateCheckResult.Error(e.message ?: "Unknown error", e)
    }

    /**
     * High-level one-liner: check → show system AlertDialog → download → install.
     * Safe to call on every app launch from a coroutine.
     *
     * @param activity The foreground Activity used to show dialogs and the installer.
     */
    suspend fun checkAndPrompt(activity: Activity) {
        when (val result = checkForUpdate()) {
            is UpdateCheckResult.UpdateAvailable -> {
                val info = result.info
                val proceed = withContext(Dispatchers.Main) {
                    showUpdateDialog(activity, info)
                }
                if (proceed) {
                    downloadAndInstall(activity, info, onProgress = null)
                }
            }
            is UpdateCheckResult.UpToDate -> { /* nothing to do */ }
            is UpdateCheckResult.Error -> { /* silent — don't interrupt users for network blips */ }
        }
    }

    /**
     * Fine-grained update lifecycle with custom callbacks.
     *
     * @param onUpdateFound      Called when an update exists. Return true to proceed, false to skip.
     * @param onProgress         Called with download progress 0–100.
     * @param onReadyToInstall   Called when the APK is downloaded & verified. Launch the installer here.
     * @param onError            Called on any failure.
     */
    suspend fun checkAndUpdate(
        onUpdateFound: (suspend (UpdateInfo) -> Boolean)? = null,
        onProgress: ((Int) -> Unit)? = null,
        onReadyToInstall: ((Activity) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        activity: Activity? = null,
    ) {
        when (val result = checkForUpdate()) {
            is UpdateCheckResult.UpdateAvailable -> {
                val info = result.info
                val proceed = onUpdateFound?.invoke(info) ?: true
                if (proceed && activity != null) {
                    downloadAndInstall(activity, info, onProgress)
                }
            }
            is UpdateCheckResult.UpToDate -> {}
            is UpdateCheckResult.Error -> onError?.invoke(result.message)
        }
    }

    /**
     * Schedules a periodic background update check using WorkManager.
     * Safe to call multiple times — WorkManager deduplicates by [UpdateCheckWorker.WORK_NAME].
     *
     * Call this once after SDK init, e.g. in your Application.onCreate().
     *
     * @param appDisplayName Display name shown in the update notification.
     */
    fun schedulePeriodicCheck(appDisplayName: String = resolvedPackageName) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.allowMeteredNetwork) NetworkType.CONNECTED
                else NetworkType.UNMETERED
            )
            .build()

        val inputData = workDataOf(
            UpdateCheckWorker.KEY_PACKAGE_NAME to resolvedPackageName,
            UpdateCheckWorker.KEY_PUBLIC_KEY to config.publicKey,
            UpdateCheckWorker.KEY_BASE_URL to config.baseUrl,
            UpdateCheckWorker.KEY_CHANNEL to config.channel,
            UpdateCheckWorker.KEY_INSTALLED_VERSION_CODE to installedVersionCode,
            UpdateCheckWorker.KEY_APP_DISPLAY_NAME to appDisplayName,
        )

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            config.checkIntervalHours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // Update interval if config changed
            request
        )
    }

    /** Cancels any scheduled background update checks. */
    fun cancelPeriodicCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Analytics (fire-and-forget)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends a custom analytics event to your app's ApexHub analytics dashboard.
     * Failures are silently swallowed — never interrupts user flows.
     *
     * @param appId      Your app's ID from the ApexHub Console.
     * @param eventType  Broad category: "install", "update", "crash", "custom", etc.
     * @param eventName  Specific event name: "button_click", "purchase_complete", etc.
     * @param metadata   Any extra key-value data to attach.
     */
    suspend fun trackEvent(
        appId: String,
        eventType: String,
        eventName: String,
        metadata: Map<String, Any> = emptyMap(),
    ) {
        val deviceInfo = getDeviceInfo()
        api.sendAnalyticsEvent(
            appId = appId,
            eventType = eventType,
            eventName = eventName,
            deviceId = deviceInfo.deviceId,
            osVersion = deviceInfo.osVersion,
            deviceModel = deviceInfo.model,
            sessionSec = 0,
            metadata = metadata,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(
        activity: Activity,
        info: UpdateInfo,
        onProgress: ((Int) -> Unit)?,
    ) {
        try {
            val apkFile = downloader.download(
                url = info.downloadUrl ?: throw ApexHubException("No download URL in update response"),
                versionName = info.latestVersion ?: "update",
                expectedSha256 = info.sha256,
                onProgress = onProgress,
            )
            withContext(Dispatchers.Main) {
                ApkInstaller.install(activity, apkFile, resolvedPackageName)
            }
        } catch (e: ApexHubException) {
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(activity)
                    .setTitle("Update failed")
                    .setMessage(e.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** Shows a standard update prompt dialog. Returns true if user taps "Update Now". */
    private suspend fun showUpdateDialog(activity: Activity, info: UpdateInfo): Boolean =
        withContext(Dispatchers.Main) {
            var result = false
            val latch = java.util.concurrent.CountDownLatch(1)

            val message = buildString {
                append("Version ${info.latestVersion} is available.")
                if (!info.releaseNotes.isNullOrBlank()) {
                    append("\n\nWhat's new:\n${info.releaseNotes}")
                }
                if (info.mandatory) {
                    append("\n\n⚠️ This is a required update.")
                }
            }

            AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(message)
                .setPositiveButton("Update Now") { _, _ ->
                    result = true
                    latch.countDown()
                }
                .apply {
                    if (!info.mandatory) {
                        setNegativeButton("Later") { _, _ -> latch.countDown() }
                        setCancelable(true)
                    } else {
                        setCancelable(false)
                    }
                }
                .setOnDismissListener { latch.countDown() }
                .show()

            withContext(Dispatchers.IO) { latch.await() }
            result
        }

    private fun getDeviceInfo(): DeviceInfo {
        val prefs = context.getSharedPreferences("apexhub_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
        return DeviceInfo(
            deviceId = deviceId,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private data class DeviceInfo(val deviceId: String, val osVersion: String, val model: String)
}
