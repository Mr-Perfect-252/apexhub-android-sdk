# Known Issues & Limitations

Documented gaps in the current SDK (v1.0.1), each with a workaround and a suggested fix. None of these
are blockers for a typical integration, but two are worth acting on.

| # | Issue | Severity |
|---|---|---|
| 1 | [`resumePendingInstall` is unreachable from host apps](#1-resumependinginstall-is-not-reachable-from-host-apps) | **Medium** — install doesn't auto-resume after the user grants the permission |
| 2 | [`POST_NOTIFICATIONS` is not declared](#2-post_notifications-is-not-declared) | **Medium** — background notifications silently dropped on Android 13+ |
| 3 | [`onError` only covers check failures](#3-onerror-only-covers-check-failures) | Low |
| 4 | [`onReadyToInstall` passes an `Activity`](#4-onreadytoinstall-passes-an-activity-not-the-file) | Low (surprising signature) |
| 5 | [Downloads are not resumable](#5-downloads-are-not-resumable) | Low |
| 6 | [`session_sec` is always 0](#6-session_sec-is-always-0) | Low |
| 7 | [`trackEvent(appId=…)` does not attribute the event](#7-trackeventappid-does-not-attribute-the-event) | Low (documentation trap) |
| 8 | [minSdk vs. release-notes mismatch](#8-minsdk-vs-release-notes-mismatch) | Low (docs) |
| 9 | [Stale installation instructions](#9-stale-installation-instructions-in-the-old-readme) | Fixed in this docs set |

---

## 1. `resumePendingInstall` is not reachable from host apps

**What:** `ApkInstaller` is declared `internal`, so `ApkInstaller.resumePendingInstall(activity)` — from
the SDK's own docs and the website's setup guide — **does not compile** in a consumer app.

**Why it matters:** the intended UX is: user taps *Update Now* → is sent to *Install unknown apps* →
grants it → returns to the app → the pending APK installs immediately. Because the host app can't call
the resume method, **the install does not happen on return**; the user has to trigger the update again.

**Workaround:** re-run the check when the app comes back to the foreground, e.g.:

```kotlin
override fun onResume() {
    super.onResume()
    // Installs the update once the permission is granted and the APK is on disk.
    lifecycleScope.launch { updater.checkAndPrompt(this@MainActivity) }
}
```

The APK is still cached in `cacheDir/apexhub/downloads`, and once `canRequestPackageInstalls()`
returns true the flow downloads (cache hit / fast re-download) and launches the installer.

**Suggested fix (one line, in the SDK):** expose a public passthrough on the updater:

```kotlin
// ApexHubUpdater.kt
fun resumePendingInstall(activity: Activity) = ApkInstaller.resumePendingInstall(activity)
```

…or change `internal object ApkInstaller` → `object ApkInstaller`. The latter is a larger API-surface
change; the passthrough is preferable.

---

## 2. `POST_NOTIFICATIONS` is not declared

**What:** the SDK manifest declares `INTERNET`, `REQUEST_INSTALL_PACKAGES`, and
`RECEIVE_BOOT_COMPLETED` — but **not** `POST_NOTIFICATIONS`. `UpdateCheckWorker` posts via
`NotificationManagerCompat.notify`.

**Why it matters:** on **Android 13 (API 33)+** posting a notification requires the runtime
`POST_NOTIFICATIONS` permission. Without it the background check still runs, but the "update
available" notification is **silently dropped** — so the feature looks broken.

**Workaround (host app):**

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

```kotlin
if (Build.VERSION.SDK_INT >= 33 &&
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9012)
}
```

**Suggested fix:** declare `POST_NOTIFICATIONS` in the SDK manifest (the host app still requests it at
runtime, but declaring it makes the requirement discoverable and mergeable).

---

## 3. `onError` only covers check failures

**What:** in `checkAndUpdate(...)`, `onError` fires only when `checkForUpdate()` returns
`UpdateCheckResult.Error`. A failure **after** that (download, verify, install) is surfaced by the
SDK's own "Update failed" dialog, not by `onError`.

**Why it matters:** if you build a custom UI and expect `onError` to fire for a failed download, it
won't — you'll get the SDK's dialog instead.

**Workaround:** to fully own error UX, call `checkForUpdate()` yourself, then trigger the install path
and rely on logcat (`ApexHub`) for the reason. Otherwise, accept the SDK's dialog.

**Suggested fix:** invoke `onError` for download/install failures too (in addition to, or instead of,
the built-in dialog) when a callback is supplied.

---

## 4. `onReadyToInstall` passes an `Activity`, not the file

**What:** the callback signature is `((Activity) -> Unit)?`.

**Why it matters:** the name suggests the downloaded APK; consumers may expect the `File`/path. The
SDK has already launched (or is about to launch) the installer by the time you get it — it's a
"we're installing now" hook, not a "here's the artifact" hook.

**Workaround:** treat it as a notification only. If you need the file path, it's deterministically
`cacheDir/apexhub/downloads/apexhub-update-{versionName}.apk`.

**Suggested fix:** rename to `onInstalling` or pass `UpdateInfo`/`File` for clarity.

---

## 5. Downloads are not resumable

**What:** `ApkDownloader` streams to a file with no HTTP `Range` support and no partial-file resume.

**Why it matters:** a dropped connection mid-download restarts from 0 — painful on large APKs and slow
networks (read timeout is 5 minutes).

**Workaround:** none client-side; retry. Prefer smaller APKs / Wi-Fi where possible.

**Suggested fix:** support `Range` + `If-Range` resume, or use a download manager.

---

## 6. `session_sec` is always 0

**What:** `ApexHubUpdater.trackEvent` sends `session_sec = 0`; the SDK does not track session length.

**Why it matters:** the backend's `avg_session_sec` aggregate will read 0 for events from this SDK.

**Workaround:** if you need session duration, send it yourself in `metadata`.

**Suggested fix:** implement session tracking, or drop the field from the SDK payload.

---

## 7. `trackEvent(appId=…)` does not attribute the event

**What:** the backend attributes an event to an app via the `X-Api-Key` header (falling back to a
`package_name` body field). The `app_id` field in the payload is stored but doesn't select the app.

**Why it matters:** passing the "wrong" `appId` changes nothing — the key decides. This is
counter-intuitive given the parameter name.

**Workaround:** always pass the app's real `appId` for tidiness, but rely on the correct public key
being configured. See [analytics.md](analytics.md#attribution).

**Suggested fix:** accept and use `app_id` when the caller is authorised for it, or rename the
parameter to reflect that it's metadata.

---

## 8. minSdk vs. release-notes mismatch

**What:** `sdk/build.gradle.kts` sets `minSdk = 21` (Android 5.0), but `RELEASE_NOTES.md` (v1.0.0)
states "Support for Android 8.0+".

**Why it matters:** integrators may set a higher `minSdk` than necessary, or be surprised that the
install-permission flow only kicks in on API 26+ (below 26 the permission concept doesn't exist).

**Reality:** the SDK supports API 21+. The interactive "Install unknown apps" flow applies on API 26+.

**Suggested fix:** correct the release notes.

---

## 9. Stale installation instructions in the old README

**What:** the previous README told you to add a **GitHub Packages** repository with credentials and
use `implementation("com.apexhub:sdk:1.0.0")`.

**Why it matters:** the SDK is actually published to **Maven Central** as
`io.github.mr-perfect-252:sdk:1.0.1`. The documented coordinates and repository would fail to resolve.

**Status:** fixed in the current README and [getting-started.md](getting-started.md).

---

## Reporting a new issue

Include the SDK version (`X-Sdk-Version` header / `BuildConfig.SDK_VERSION`), the `logcat -s ApexHub`
output, and the backend's response to the update-check and download URLs.
