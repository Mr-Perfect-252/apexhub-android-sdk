# Troubleshooting

Start here — always check `logcat` tag **`ApexHub`** first, everything is logged there:

```bash
adb logcat -s ApexHub
```

| Symptom | Jump to |
|---|---|
| Tapping "Update Now" does nothing | [No installer appears](#no-installer-appears-tapping-update-now-does-nothing) |
| Update is never detected | [Update not detected](#update-is-never-detected) |
| Download fails (404 / network) | [Download fails](#download-fails) |
| Background check never runs / no notification | [Background checks](#background-check-never-runs) |
| Analytics events missing | [Analytics](#analytics-events-dont-appear) |
| App crashes at startup | [Config validation crash](#app-crashes-at-startup-illegalargumentexception) |
| Build fails | [Build errors](#build-errors) |

---

## No installer appears (tapping "Update Now" does nothing)

Work through these in order — the log line tells you which one you're hitting.

### 1. You're on SDK 1.0.0 (the swallowed-error bug)

In 1.0.0, a download failure threw a plain `IOException` that `downloadAndInstall()` didn't catch — so
the coroutine died silently: no installer, no error, and the prompt came back on every launch. Look
for a stack trace logged by `ApexHub` with no subsequent "Launching system installer".

**Fix:** upgrade to `1.0.1`:

```kotlin
implementation("io.github.mr-perfect-252:sdk:1.0.1")
```

(Apps already shipped with 1.0.0 need a rebuild + re-publish to receive this — the buggy code is in
those binaries.)

### 2. FileProvider misconfiguration

```
E/ApexHub: FileProvider misconfiguration — cannot share APK for install
```

The installer needs the SDK's `FileProvider` at authority `"${applicationId}.apexhub.fileprovider"`.
This is contributed automatically by the SDK manifest — the usual cause is a **duplicate provider**
in your app manifest with the same authority, which loses at merge time.

**Fix:** remove your own provider entry (or rename its authority) and let the SDK's win.

### 3. "Install unknown apps" not granted

On Android 8.0+, before installing, the user must allow it. The SDK shows an educational dialog and
sends them to `Settings → Install unknown apps`. If the user backs out, nothing installs. Look for:

```
D/ApexHub: Install permission not granted; prompting user
```

> Note: after they grant it, the pending install is **not** auto-resumed in v1.0.1 because
> `ApkInstaller.resumePendingInstall` is `internal` — see
> [known-issues.md](known-issues.md#resumependinginstall-is-not-reachable-from-host-apps). Re-running
> the check (relaunching) completes the install.

### 4. Activity isn't AppCompat-themed

The SDK's dialogs use `androidx.appcompat.app.AlertDialog`. If your Activity uses a non-AppCompat
theme you'll get `IllegalStateException: You need to use a Theme.AppCompat theme`. Use one, e.g.:

```xml
<style name="Theme.MyApp" parent="Theme.AppCompat.DayNight.NoActionBar"> … </style>
```

### 5. The APK is missing or empty

```
E/ApexHub: Cannot install: APK file missing or empty at …
```

The downloaded file was deleted (OS cache eviction) or the download was truncated. Re-run the update.

---

## Update is never detected

The backend only returns `updateAvailable: true` when **the latest live release's `version_code` is
greater than the `installed` value the SDK sent**. Check the log line:

```
D/ApexHub: Checking for update: pkg=com.example.myapp installed=12 channel=stable
D/ApexHub: Update check result: available=false latest=1.2.0 code=12
```

| Cause | Check |
|---|---|
| `installed` ≥ latest `versionCode` | Publish a release with a **higher** `versionCode` |
| Wrong `channel` | Your release's channel must equal `ApexHubConfig.channel` |
| Wrong / missing public key | A `401` in the log means the key isn't `pk_…`; a `404` means it matches no app |
| Package name mismatch | `ApexHubConfig.packageName` (or the app's `applicationId`) must match the registered package |
| Staged rollout excluded | `rolloutPercent < 100` and this device landed outside the bucket → `rolloutStatus: "excluded_from_staged_rollout"` |
| Release not `live` | Draft/archived releases are not served |

Quick manual check:

```bash
curl "https://apex-hub-production.vercel.app/api/update/com.example.myapp?channel=stable&installed=0" \
  -H "X-Api-Key: pk_live_YOUR_KEY"
```

`installed=0` should always return an update if any live release exists.

---

## Download fails

### `HTTP 404` from the download

The `downloadUrl` points at the ApexHub proxy (`…/api/releases/{id}/download`). A 404 means the
**backend** couldn't serve the asset — usually one of:

- the APK isn't in storage (release row exists, binary missing), or
- server-side storage credentials are misconfigured (the backend proxies a private store).

This is a backend/deployment issue, not an SDK one. Verify directly:

```bash
curl -I "https://apex-hub-production.vercel.app/api/releases/<release-id>/download"
```

A healthy response is `200` with `Content-Type: application/vnd.android.package-archive`.

> A misconfigured backend returns a **5xx** (`503` for missing storage credentials, `502` for an auth
> failure) rather than a 404 — if you see a 404 on a release that definitely exists, the binary is
> genuinely absent.

### Cleartext / TLS errors

```
CLEARTEXT communication to … not permitted
```

`downloadUrl` must be **HTTPS**. Android blocks cleartext traffic for target SDK 28+. If you're
self-hosting and the backend returned an `http://` URL, fix the backend (set its public base URL to an
HTTPS host) rather than enabling cleartext in the app — enabling cleartext for the OTA download is a
security downgrade and Play discourages it.

### SHA-256 mismatch

```
ApexHubException: SHA-256 integrity check failed!
```

The downloaded bytes don't match the `sha256` the backend advertised. The file is deleted
automatically. Causes: a corrupted/truncated transfer (retry), or a proxy/CDN mutating the body. Retry
once; if it persists, verify the release's stored hash against the published APK.

### Timeouts

Connect timeout is 30 s, read timeout 5 min. Large APKs on slow links can hit the read timeout — the
download is not resumable in v1.0.1, so a retry starts over.

---

## Background check never runs

See [background-checks.md → Gotchas](background-checks.md#gotchas) in full. Summary:

- **Android 13+ notification permission** — the check runs but the notification is silently dropped
  unless your app declares/requests `POST_NOTIFICATIONS`.
- **Unmetered constraint** — with the default `allowMeteredNetwork = false`, the worker waits for
  Wi-Fi.
- **Interval is a minimum** — WorkManager/Doze can defer runs well past `checkIntervalHours`.
- **Work not enqueued** — you must actually call `schedulePeriodicCheck(...)` (typically in
  `Application.onCreate`).

Force-run it for testing:

```bash
adb shell dumpsys jobscheduler | grep apexhub
adb shell cmd jobscheduler run -f <package> <jobId>
```

---

## Analytics events don't appear

- **Wrong key.** The backend attributes events by `X-Api-Key`. If the key matches no app you get
  `404` (and the SDK swallows it). Use the app's own public key.
- **Wrong app.** The `appId` you pass to `trackEvent` does **not** select the app — the API key does.
- **Looking in the wrong place.** Events show in **Console → the app → Analytics**.
- **Recent events.** The summary endpoint aggregates on read; there may be a short delay.

---

## App crashes at startup (`IllegalArgumentException`)

The SDK validates its config in `init`:

```
[ApexHub] publicKey must start with 'pk_live_' or 'pk_test_'. Got: …
[ApexHub] checkIntervalHours must be >= 1. Got: 0
```

Fix the offending value. This is intentional fail-fast behaviour, so a typo surfaces immediately
instead of silently disabling updates.

---

## Build errors

| Error | Fix |
|---|---|
| `android.useAndroidX property is not enabled` | Add `android.useAndroidX=true` to `gradle.properties` |
| Manifest merger: provider authority conflict | Remove the duplicate `FileProvider` from your manifest (the SDK provides it) |
| `minSdk` too low | SDK requires `minSdk >= 21` |
| `AppCompat` theme exception at runtime | Use an AppCompat theme on the update Activity |

---

## Still stuck?

Collect this and you'll have everything needed to diagnose:

```bash
adb logcat -s ApexHub                  # the whole SDK flow
curl -i "<downloadUrl>"                # what the download endpoint returns
# and the backend's response to:
curl "https://apex-hub-production.vercel.app/api/update/<pkg>?channel=stable&installed=0" \
  -H "X-Api-Key: pk_live_…"
```
