package com.apexhub.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/**
 * Triggers the Android system package installer for a downloaded APK.
 * Handles the REQUEST_INSTALL_PACKAGES permission flow on Android 8+ (API 26+).
 */
internal object ApkInstaller {

    /**
     * Prompts the user to install the [apkFile].
     * On Android 8+, if the app doesn't have the "Install unknown apps" permission,
     * shows an educational dialog first before redirecting to settings.
     *
     * @param activity   The foreground Activity to attach dialogs to.
     * @param apkFile    The verified local APK file to install.
     * @param appName    The app name shown in the educational dialog.
     */
    fun install(activity: Activity, apkFile: File, appName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                showInstallPermissionDialog(activity, apkFile, appName)
                return
            }
        }
        triggerInstall(activity, apkFile)
    }

    private fun triggerInstall(context: Context, apkFile: File) {
        val authority = "${context.packageName}.apexhub.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * Shows an educational dialog explaining WHY the user needs to grant
     * "Install unknown apps" permission — required by Play Store policy Section 4.8
     * even for non-Play apps.
     */
    private fun showInstallPermissionDialog(activity: Activity, apkFile: File, appName: String) {
        AlertDialog.Builder(activity)
            .setTitle("Allow $appName to install updates")
            .setMessage(
                "To receive verified updates directly from the developer, " +
                "you need to allow $appName to install apps.\n\n" +
                "On the next screen, enable \"Allow from this source\". " +
                "Your download is verified with SHA-256 and scanned for malware before reaching your device."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                } else {
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                }
                activity.startActivity(settingsIntent)
                // Store the pending APK path so we can resume after the user grants permission
                activity.getSharedPreferences("apexhub_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pending_install_path", apkFile.absolutePath)
                    .apply()
            }
            .setNegativeButton("Not Now", null)
            .setCancelable(true)
            .show()
    }

    /**
     * Call this from your Activity's onResume() to resume a pending install
     * after the user returned from the settings screen.
     */
    fun resumePendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) return

        val prefs = activity.getSharedPreferences("apexhub_prefs", Context.MODE_PRIVATE)
        val pendingPath = prefs.getString("pending_install_path", null) ?: return

        prefs.edit().remove("pending_install_path").apply()

        val file = File(pendingPath)
        if (file.exists()) {
            triggerInstall(activity, file)
        }
    }
}
