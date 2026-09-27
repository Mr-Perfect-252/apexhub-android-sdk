# OTA Updates

The complete update lifecycle: **check → prompt → download → verify → install → user confirms**.

- [Entry points](#entry-points)
- [Step 1 — Check for an update](#step-1--check-for-an-update)
- [Step 2 — Default UI: `checkAndPrompt`](#step-2--default-ui-checkandprompt)
- [Step 3 — Custom UI: `checkAndUpdate`](#step-3--custom-ui-checkandupdate)
- [Update strategies](#update-strategies)
- [Download & integrity verification](#download--integrity-verification)
- [The install-permission flow](#the-install-permission-flow)
- [How the actual install happens](#how-the-actual-install-happens)
- [Rollout, mandatory, and downgrade semantics](#rollout-mandatory-and-downgrade-semantics)

---

## Entry points

| Method | Use when |
|---|---|
| `checkForUpdate()` | You only want to know whether an update exists (never throws) |
| `checkAndPrompt(activity)` | You want the SDK's built-in dialog + download + install |
| `checkAndUpdate(...)` | You want your own UI but the SDK's download/install |

All three are `suspend` functions — call them from a coroutine scope
(`lifecycleScope`, `viewModelScope`, …).

---

## Step 1 — Check for an update

```kotlin
suspend fun checkForUpdate(): UpdateCheckResult
```

It asks the backend `GET /api/update/{packageName}?channel=…&installed={versionCode}` and maps the
answer to a sealed result. **It never throws** (except coroutine cancellation, which is correctly
rethrown).

```kotlin
when (val result = updater.checkForUpdate()) {
    is UpdateCheckResult.UpdateAvailable -> showUpdateBanner(result.info)
    UpdateCheckResult.UpToDate            -> { /* already current */ }
    is UpdateCheckResult.Error            -> Log.w("App", "Check failed: ${result.message}")
}
```

| Result | Meaning |
|---|---|
| `UpdateCheckResult.UpdateAvailable(info)` | A newer `versionCode` exists; `info` has everything you need |
| `UpdateCheckResult.UpToDate` | No newer version on the subscribed channel |
| `UpdateCheckResult.Error(message, cause)` | Network/API failure — inspect `message`, optional `cause` |

The installed `versionCode` is read from `PackageManager` (`longVersionCode` on API 28+, `versionCode`
below) and sent as `?installed=`. If the package isn't found it sends `0`.

### `UpdateInfo`

Returned inside `UpdateAvailable`:

| Field | Type | Notes |
|---|---|---|
| `updateAvailable` | `Boolean` | `true` for an available update |
| `latestVersion` | `String?` | e.g. `"2.1.0"` |
| `versionCode` | `Int?` | server-side version code |
| `downloadUrl` | `String?` | where the SDK downloads the APK from (in production an **HTTPS** ApexHub URL) |
| `sha256` | `String?` | expected APK digest; verified after download |
| `mandatory` | `Boolean` | developer marked this release mandatory |
| `releaseNotes` | `String?` | developer-authored text, shown in the dialog |
| `rolloutPercent` | `Int?` | staged-rollout bucket (informational) |
| `channel` | `String?` | the channel that answered |
| `certificateFingerprint` | `String?` | signing cert fingerprint the APK was uploaded with |

---

## Step 2 — Default UI: `checkAndPrompt`

```kotlin
suspend fun checkAndPrompt(activity: Activity)
```

The one-liner. It:

1. Calls `checkForUpdate()`.
2. If an update exists, shows an `AlertDialog` on the main thread —
   *"Update available"*, the version, `releaseNotes`, and a ⚠️ line when `mandatory` is set.
3. If the user taps **Update Now**, downloads the APK and launches the system installer.
4. On `UpToDate` or `Error`, does **nothing** — network blips never interrupt the user.

```kotlin
lifecycleScope.launch {
    updater.checkAndPrompt(activity = this@MainActivity)
}
```

---

## Step 3 — Custom UI: `checkAndUpdate`

```kotlin
suspend fun checkAndUpdate(
    onUpdateFound: (suspend (UpdateInfo) -> Boolean)? = null,   // return false to skip
    onProgress: ((Int) -> Unit)? = null,                        // 0..100
    onReadyToInstall: ((Activity) -> Unit)? = null,             // APK downloaded & verified
    onError: ((String) -> Unit)? = null,
    activity: Activity? = null,                                 // required to actually download
)
```

```kotlin
lifecycleScope.launch {
    updater.checkAndUpdate(
        activity = this@MainActivity,
        onUpdateFound = { info ->
            // Show your own bottom sheet / dialog. Return true to proceed.
            showMyUpdateSheet(info.latestVersion, info.releaseNotes)
        },
        onProgress = { percent ->
            progressBar.progress = percent
            progressText.text = "Downloading… $percent%"
        },
        onReadyToInstall = { act ->
            // The APK is on disk and SHA-256 verified at this point.
            toast("Installing update…")
        },
        onError = { message ->
            Log.e("ApexHub", message)
        },
    )
}
```

Notes:

- **`activity` is required for the download to happen.** With `activity == null` the flow stops after
  `onUpdateFound` — that's intentional, so you can't kick off an install with nothing to attach dialogs
  and the installer to.
- If `onUpdateFound` is omitted, the download proceeds automatically.
- `onError` is only invoked for a failed **check**. A failure during **download/install** is surfaced
  by the SDK's own "Update failed" dialog (see [error handling](#error-handling)).

---

## Update strategies

`UpdateStrategy` sets the *prompt* behaviour; the release's `mandatory` flag also feeds in.

| Config | Release `mandatory` | Dialog |
|---|---|---|
| `FLEXIBLE` (default) | `false` | Dismissible, **Later** + **Update Now** |
| `FLEXIBLE` | `true` | Shows "⚠️ This is a required update." but still has a **Later** button |
| `IMMEDIATE` | either | Non-dismissible (`setCancelable(false)`), **Update Now** only |

> `IMMEDIATE` blocks the caller's coroutine until the user acts, so use it sparingly — critical
> security patches only.

---

## Download & integrity verification

`ApkDownloader` (internal) handles the transfer:

| Property | Value |
|---|---|
| Destination | `cacheDir/apexhub/downloads/apexhub-update-{versionName}.apk` |
| HTTP client | OkHttp, `followRedirects = true` |
| Timeouts | 30 s connect · 5 min read (APKs are large) |
| Progress | `onProgress(0..99)` while streaming, then `onProgress(100)` |
| Integrity | streaming **SHA-256**; compared case-insensitively against `UpdateInfo.sha256` |

If the hash does not match, the file is **deleted** and an `ApexHubException` is thrown with the
expected and actual digests. Empty (0-byte) downloads are also rejected and deleted.

On failure the partial file is removed, so retries always start clean.

---

## The install-permission flow

Android 8.0+ (API 26+) requires the user to allow **"Install unknown apps"** for your app before it
may install an APK. The SDK walks them through it:

```
user taps "Update Now"
        │
        ▼
canRequestPackageInstalls()? ── yes ──► launch system installer
        │
        no
        ▼
educational AlertDialog
("Allow MyApp to install updates" + why)
        │
   "Open Settings"
        ▼
Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES (package-scoped)
        │
        ▼
pending APK path saved to SharedPreferences ("apexhub_prefs")
        │
   user returns to the app
        ▼
resume pending install → launch system installer
```

The dialog copy is deliberately educational — Google Play policy §4.8 expects you to explain *why*
before sending a user to a system permission screen, and this does.

> **Known limitation:** the resume step is `ApkInstaller.resumePendingInstall(activity)`, and
> `ApkInstaller` is declared `internal`, so **your app cannot call it**. The permission-granted case
> therefore resumes only if the flow is re-run. See
> [known-issues.md](known-issues.md#resumependinginstall-is-not-reachable-from-host-apps) for the
> one-line fix and current workaround.

---

## How the actual install happens

The SDK does **not** silently install anything. It hands the verified APK to the **Android system
package installer**:

```kotlin
val uri = FileProvider.getUriForFile(context, "${context.packageName}.apexhub.fileprovider", apkFile)
startActivity(Intent(ACTION_VIEW).apply {
    setDataAndType(uri, "application/vnd.android.package-archive")
    addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_GRANT_READ_URI_PERMISSION)
})
```

The user confirms in the system dialog, and Android performs a normal **in-place update** (data
preserved) when:

- the package name matches,
- the signing key matches, and
- the new `versionCode` is higher.

If the signing key differs, Android rejects the install — which is why the backend locks the
`certificateFingerprint` on your app's first upload. Silent/self-update is not possible for normal
apps on modern Android, and attempting it would violate Play policy.

---

## Rollout, mandatory, and downgrade semantics

These are **enforced by the backend**, not the SDK, but they shape what `checkForUpdate()` returns:

| Behaviour | Rule |
|---|---|
| Update offered | The live release's `version_code` **must be greater** than `installed` |
| Staged rollout | If `rollout_percent < 100`, the server hashes your request into a bucket; excluded devices get `updateAvailable: false` with `rolloutStatus: "excluded_from_staged_rollout"` |
| Mandatory | Surfaces as `mandatory: true` → sets the dialog's non-cancellable copy |
| Downgrade | A lower `version_code` is not offered unless the release explicitly allows downgrade |

### Error handling

`downloadAndInstall()` catches **everything** except coroutine cancellation and shows an
**"Update failed"** dialog with the reason. This is intentional — an unhandled failure used to kill
the coroutine silently, leaving the app re-prompting forever. If you want to handle failures yourself,
catch around `checkAndUpdate`/`download` and check `logcat` tag `ApexHub` for the underlying cause.

---

## Related

- [background-checks.md](background-checks.md) — do the check while the app is closed
- [api-reference.md](api-reference.md) — full signatures
- [troubleshooting.md](troubleshooting.md) — "nothing happens when I tap Update Now"
