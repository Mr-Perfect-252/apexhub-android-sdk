package com.apexhub.sdk

/**
 * Configuration for the ApexHub SDK.
 *
 * @param publicKey     Your app's public key from the ApexHub Console (starts with pk_live_).
 * @param packageName   Your app's Android package name (e.g. com.example.myapp).
 *                      Defaults to the host app's package name if not supplied.
 * @param channel       Release channel to subscribe to. Options: "stable", "beta", "nightly".
 *                      Default: "stable".
 * @param baseUrl       ApexHub API base URL. Override only for self-hosted deployments.
 * @param checkInterval How often WorkManager checks for updates in the background, in hours.
 *                      Default: 6 hours. Minimum: 1 hour.
 * @param updateStrategy Whether updates are optional (FLEXIBLE) or blocking (IMMEDIATE).
 * @param allowMeteredNetwork  Allow background update downloads on cellular data. Default: false.
 */
data class ApexHubConfig(
    val publicKey: String,
    val packageName: String? = null,
    val channel: String = "stable",
    val baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    val checkIntervalHours: Long = 6L,
    val updateStrategy: UpdateStrategy = UpdateStrategy.FLEXIBLE,
    val allowMeteredNetwork: Boolean = false,
) {
    init {
        require(publicKey.startsWith("pk_live_") || publicKey.startsWith("pk_test_")) {
            "[ApexHub] publicKey must start with 'pk_live_' or 'pk_test_'. Got: $publicKey"
        }
        require(checkIntervalHours >= 1) {
            "[ApexHub] checkIntervalHours must be >= 1. Got: $checkIntervalHours"
        }
    }
}

enum class UpdateStrategy {
    /** User can dismiss the update prompt and continue using the app. */
    FLEXIBLE,
    /** App is blocked until the user installs the update. Use only for critical security patches. */
    IMMEDIATE
}
