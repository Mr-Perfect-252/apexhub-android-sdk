package com.apexhub.sdk

/**
 * Represents the result of an update check from the ApexHub backend.
 * Maps directly to the JSON response from GET /api/update/:packageName
 */
data class UpdateInfo(
    /** True if a newer version is available for this device. */
    val updateAvailable: Boolean,
    /** The latest published version name (e.g. "2.1.0"). Null if no update. */
    val latestVersion: String?,
    /** The latest version code (integer). Null if no update. */
    val versionCode: Int?,
    /** CDN URL to download the APK. Null if no update. */
    val downloadUrl: String?,
    /** SHA-256 hex digest of the APK for integrity verification. */
    val sha256: String?,
    /** If true, the update should be treated as mandatory. */
    val mandatory: Boolean,
    /** Developer-authored release notes. */
    val releaseNotes: String?,
    /** What percentage of devices are receiving this rollout. */
    val rolloutPercent: Int?,
    /** The release channel (stable, beta, nightly). */
    val channel: String?,
    /** The certificate fingerprint the APK was signed with. */
    val certificateFingerprint: String?,
)

/** Sealed result wrapper returned from [ApexHubUpdater.checkForUpdate]. */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckResult()
}
