# API Reference

Complete public surface of `com.apexhub.sdk` (v1.0.1). Types marked **internal** are implementation
details — they are visible inside the SDK module only and **cannot be referenced from your app**.

- [ApexHubUpdater](#apexhubupdater)
- [ApexHubConfig](#apexhubconfig)
- [UpdateInfo](#updateinfo)
- [UpdateCheckResult](#updatecheckresult)
- [UpdateStrategy](#updatestrategy)
- [ApexHubException](#apexhubexception)
- [Internal classes](#internal-classes)
- [Threading model](#threading-model)
- [Generated constants](#generated-constants)

---

## `ApexHubUpdater`

`public class ApexHubUpdater` — the single entry point. Create one per app; it is cheap and safe to
keep around.

```kotlin
class ApexHubUpdater(private val context: Context, private val config: ApexHubConfig)
```

### `checkForUpdate`

```kotlin
suspend fun checkForUpdate(): UpdateCheckResult
```

Checks the backend for a newer version. **Never throws** (except coroutine cancellation, which is
re-thrown). Returns `UpdateAvailable`, `UpToDate`, or `Error`.

### `checkAndPrompt`

```kotlin
suspend fun checkAndPrompt(activity: Activity)
```

Full built-in flow: check → system dialog → download → install. Silent on `UpToDate`/`Error`.

### `checkAndUpdate`

```kotlin
suspend fun checkAndUpdate(
    onUpdateFound: (suspend (UpdateInfo) -> Boolean)? = null,
    onProgress: ((Int) -> Unit)? = null,
    onReadyToInstall: ((Activity) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    activity: Activity? = null,
)
```

| Callback | When | Contract |
|---|---|---|
| `onUpdateFound` | Update exists | Return `true` to proceed, `false` to skip. Suspending. Omitted → proceeds. |
| `onProgress` | During download | `0..100` |
| `onReadyToInstall` | After download + SHA-256 verify | Receives the `Activity` |
| `onError` | **Check** failed | Receives a message string |
| `activity` | — | **Required for download+install.** `null` → stops after `onUpdateFound`. |

### `schedulePeriodicCheck`

```kotlin
fun schedulePeriodicCheck(appDisplayName: String = /* resolved package name */)
```

Enqueues unique periodic WorkManager work (name `apexhub_update_check`, policy `UPDATE`). Idempotent.
See [background-checks.md](background-checks.md).

### `cancelPeriodicCheck`

```kotlin
fun cancelPeriodicCheck()
```

Cancels the unique periodic work.

### `trackEvent`

```kotlin
suspend fun trackEvent(
    appId: String,
    eventType: String,
    eventName: String,
    metadata: Map<String, Any> = emptyMap(),
)
```

Sends one analytics event. Fire-and-forget; all failures are swallowed. See
[analytics.md](analytics.md).

### Internal state (not part of the API)

The updater resolves `packageName` (config value, else `context.packageName`) and reads the installed
`versionCode` from `PackageManager` (`longVersionCode` on API 28+, else `versionCode`; `0` if the
package is missing).

---

## `ApexHubConfig`

`public data class ApexHubConfig` — immutable configuration. See
[configuration.md](configuration.md) for full descriptions.

```kotlin
data class ApexHubConfig(
    val publicKey: String,
    val packageName: String? = null,
    val channel: String = "stable",
    val baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    val checkIntervalHours: Long = 6L,
    val updateStrategy: UpdateStrategy = UpdateStrategy.FLEXIBLE,
    val allowMeteredNetwork: Boolean = false,
)
```

**Throws `IllegalArgumentException`** from `init` if `publicKey` doesn't start with `pk_live_`/
`pk_test_`, or if `checkIntervalHours < 1`.

---

## `UpdateInfo`

`public data class UpdateInfo` — parsed backend response.

```kotlin
data class UpdateInfo(
    val updateAvailable: Boolean,
    val latestVersion: String?,
    val versionCode: Int?,
    val downloadUrl: String?,
    val sha256: String?,
    val mandatory: Boolean,
    val releaseNotes: String?,
    val rolloutPercent: Int?,
    val channel: String?,
    val certificateFingerprint: String?,
)
```

---

## `UpdateCheckResult`

`public sealed class UpdateCheckResult`

```kotlin
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckResult()
}
```

---

## `UpdateStrategy`

`public enum class UpdateStrategy { FLEXIBLE, IMMEDIATE }`

| Value | Dialog |
|---|---|
| `FLEXIBLE` | Dismissible ("Later" + "Update Now") |
| `IMMEDIATE` | Non-dismissible ("Update Now" only) |

---

## `ApexHubException`

`public class ApexHubException(message: String, cause: Throwable? = null) : Exception`

Thrown internally by the download/API layer for network errors, non-2xx responses, empty downloads,
and SHA-256 mismatches. It is mapped to UI by the SDK (an "Update failed" dialog) rather than
propagated to your callbacks — you'll see it in logcat under tag `ApexHub`.

---

## Internal classes

Present in the AAR but **`internal`** — not callable from your app:

| Type | Role |
|---|---|
| `ApexHubApi` | OkHttp + Gson wrapper for `GET /api/update/…` and `POST /api/analytics/event` |
| `ApkDownloader` | Streams the APK to cache, computes SHA-256, reports progress |
| `ApkInstaller` | FileProvider → system installer; educational permission dialog |
| `UpdateCheckWorker` | `CoroutineWorker` for background checks |

> This visibility is why the README's `ApkInstaller.resumePendingInstall(activity)` example does not
> compile in a consumer app. See
> [known-issues.md](known-issues.md#resumependinginstall-is-not-reachable-from-host-apps).

---

## Threading model

You never choose a dispatcher:

| Stage | Dispatcher |
|---|---|
| Network (`checkForUpdate`, `trackEvent`, download) | `Dispatchers.IO` |
| Dialog + installer launch | `Dispatchers.Main` |

All entry points are `suspend`, so calling them from `lifecycleScope`/`viewModelScope` is all you
need. Coroutine cancellation is honoured (it is re-thrown, never swallowed).

---

## Generated constants

`com.apexhub.sdk.BuildConfig` is generated by the SDK build:

| Constant | Value (v1.0.1) | Use |
|---|---|---|
| `DEFAULT_BASE_URL` | `https://apex-hub-production.vercel.app` | default `ApexHubConfig.baseUrl` |
| `SDK_VERSION` | `1.0.1` | sent as the `X-Sdk-Version` header |

---

## ProGuard / R8

The SDK ships consumer rules (`consumer-rules.pro`) that keep its public API and Gson models, and
`-dontwarn com.google.gson.**`. If you minify with R8, no extra rules are required — but do not strip
`com.apexhub.sdk.**`, or reflection-based Gson parsing of `UpdateInfo` will break.
