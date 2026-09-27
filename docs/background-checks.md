# Background Update Checks

The SDK can check for updates while your app is closed, using **WorkManager**, and post a
notification when one is found. Schedule it once and forget it.

---

## Scheduling

```kotlin
// Application.onCreate() — safe to call on every launch
updater.schedulePeriodicCheck(appDisplayName = "My App")
```

```kotlin
suspend fun schedulePeriodicCheck(appDisplayName: String = resolvedPackageName)
```

`appDisplayName` is only used in the notification text ("**My App** update available"). It defaults
to the resolved package name, which is rarely what you want to show a user — pass your app's display
name.

### Cancelling

```kotlin
updater.cancelPeriodicCheck()
```

---

## What gets scheduled

| Property | Value |
|---|---|
| Work name | `apexhub_update_check` (constant: `UpdateCheckWorker.WORK_NAME`) |
| Type | Unique **periodic** work |
| Interval | `ApexHubConfig.checkIntervalHours` (default 6 h, minimum 1 h) |
| Existing-work policy | **`UPDATE`** — re-scheduling updates the interval instead of stacking jobs |
| Constraints | `NetworkType.UNMETERED`, or `NetworkType.CONNECTED` when `allowMeteredNetwork = true` |
| Backoff | Exponential, 15-minute initial |
| Worker | `UpdateCheckWorker` (`CoroutineWorker`) |

Because the work is *unique*, calling `schedulePeriodicCheck()` on every app start is the intended,
safe pattern: it updates the existing job rather than creating duplicates.

---

## What the worker does

1. Reads its inputs (package name, public key, base URL, channel, installed `versionCode`, display
   name) from the work's input data.
2. Calls `GET /api/update/{packageName}?channel=…&installed=…` with the `X-Api-Key` header.
3. If `updateAvailable`, posts a notification on the **`apexhub_updates`** channel
   (`"App Updates"`, `IMPORTANCE_DEFAULT`): *"Version **x.y.z** is ready to install."* plus release
   notes, with a tap target that deep-links back into the app.
4. On transient failure it returns `Result.retry()` **once** (`runAttemptCount < 1`), then
   `Result.failure()`.

The worker does **not** download the APK — it only notifies. The download/install happens in the
foreground flow once the user opens the app (or when they tap the notification).

---

## Gotchas

### Notifications need runtime permission on Android 13+

The worker posts a notification via `NotificationManagerCompat`. On **Android 13 (API 33)+** posting
requires the `POST_NOTIFICATIONS` runtime permission, which the **SDK does not declare or request**.
Without it, the notification is silently dropped — the background check still runs, you just won't
see anything.

**Fix (in your host app):**

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

```kotlin
// Request it at an appropriate moment (API 33+)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
}
```

The `apexhub_updates` notification channel is created lazily by the worker on Android 8+.

### Intervals are a minimum, not a schedule

WorkManager batches and defers jobs to protect battery. A 6-hour interval means "no more often than
every 6 hours", and Doze/low-battery can delay a run further. Never rely on the background check for
time-critical delivery — do a foreground check on launch for that. (`IMMEDIATE` updates still need
the user to open the app.)

### It survives reboots — but only if you scheduled it

`RECEIVE_BOOT_COMPLETED` is merged into your manifest by the SDK, and WorkManager persists its
scheduled work across reboots. The job is re-scheduled on each app launch anyway (because
`schedulePeriodicCheck` is idempotent), so there's nothing else to do.

### Unmetered by default

The default `allowMeteredNetwork = false` means the check won't run on cellular. This is a *check*,
not a download, so the data cost is tiny — but if you want it to run on any connection, set the flag:

```kotlin
ApexHubConfig(publicKey = key, allowMeteredNetwork = true)
```

---

## Testing background checks

WorkManager doesn't run on demand, so to verify the worker quickly:

1. Lower the interval for testing: `checkIntervalHours = 1L` (the enforced minimum).
2. Force a run from the shell (WorkManager's test hook, debug builds):

```bash
# Find the job id, then force it
adb shell dumpsys jobscheduler | grep apexhub
adb shell cmd jobscheduler run -f <your.package.name> <jobId>
```

3. Watch the logs:

```bash
adb logcat -s ApexHub
```

4. Check the notification and `Console → Analytics` for the resulting traffic.

---

## Related

- [configuration.md](configuration.md#checkintervalhours--long-default-6l)
- [ota-updates.md](ota-updates.md)
