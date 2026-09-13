# ApexHub Android SDK v1.0.1

Patch release. **Bug fix — no API changes, fully backward compatible.**

## Fixed

- **OTA updates now actually install.** Previously, when an update was
  detected and the user tapped **Update Now**, nothing appeared to happen and
  the "update available" prompt returned on every launch. The download step
  threw a plain `IOException` (network timeout, TLS, DNS, dropped connection),
  but `downloadAndInstall()` only caught `ApexHubException`, so the failure
  silently killed the coroutine — no installer launched, no error shown, and
  the app was never updated, so it kept re-prompting.
- The updater now catches **all** failures (while still respecting coroutine
  cancellation) and always surfaces an "Update failed" dialog with a clear
  reason instead of failing silently.
- `ApkDownloader` converts network and file-I/O errors into descriptive
  messages and guards against empty (0-byte) downloads.
- `ApkInstaller` guards a missing/empty APK and wraps the FileProvider /
  installer launch (`IllegalArgumentException`, `ActivityNotFoundException`)
  with a user-visible message.
- Added `Log` diagnostics (tag `ApexHub`) across the check → download →
  install flow so update problems are visible in logcat.

## Detection behavior (unchanged, confirmed correct)

The SDK reads the installed `versionCode` from `PackageManager` and sends it
to the backend as `?installed=`. The server returns `updateAvailable: true`
only when the latest published release's `version_code` is **greater** than
the installed one; equal or lower means "up to date."

## Compatibility — what apps on v1.0.0 should do

- **This is a drop-in patch.** No public API, method signatures, or
  integration steps changed. Bump the dependency and rebuild:

  ```kotlin
  dependencies {
      implementation("io.github.mr-perfect-252:sdk:1.0.1")
  }
  ```

- **Apps already shipped with v1.0.0 are not fixed automatically** — the buggy
  code is compiled into those app binaries. To deliver the fix, developers
  must rebuild their app against SDK `1.0.1` and publish a new app version
  (via ApexHub OTA, Play Store, or sideload).

- **The OTA path still works for delivering that new version.** The v1.0.0 bug
  is only in the download/install step, not in *detection* — a v1.0.0 app can
  still detect and start downloading a newer release. If that one download
  succeeds, the update installs and the app moves onto the fixed 1.0.1 code
  from then on. Where the old download step fails on a given device, users can
  update once via the store/sideload to get onto 1.0.1, after which OTA is
  reliable.

- No server-side or backend changes are required.

## Installation

```kotlin
dependencies {
    implementation("io.github.mr-perfect-252:sdk:1.0.1")
}
```

## Getting Started

See the README for detailed documentation and the integration guide.

---

# ApexHub Android SDK v1.0.0

Initial release of the ApexHub Android SDK with full support for:

- **OTA Updates**: Seamless over-the-air update management for Android applications
- **Analytics**: Comprehensive event tracking and user analytics
- **Crash Reporting**: Automatic crash detection and detailed crash reports

## Features

- Easy integration with minimal setup
- Background update checks
- Granular analytics events
- Real-time crash monitoring and diagnostics
- Thread-safe operations
- Support for Android 8.0+
