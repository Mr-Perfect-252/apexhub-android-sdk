# ApexHub Android SDK — Documentation

Full documentation for the official ApexHub Android SDK (`io.github.mr-perfect-252:sdk`, package
`com.apexhub.sdk`). Start with the [README](../README.md) for the 5-minute quickstart, or dive in here.

---

## Guides

| Doc | What it covers |
|---|---|
| [getting-started.md](getting-started.md) | Requirements, dependency, key, initialization, first update, verification |
| [configuration.md](configuration.md) | Every `ApexHubConfig` field: meaning, defaults, validation |
| [ota-updates.md](ota-updates.md) | The update lifecycle: check, prompt, download, verify, install, permission flow |
| [background-checks.md](background-checks.md) | WorkManager scheduling, constraints, notifications, testing |
| [analytics.md](analytics.md) | `trackEvent`, payload, device metadata, attribution |
| [api-reference.md](api-reference.md) | Public classes and methods; visibility; threading |
| [backend-api.md](backend-api.md) | The REST endpoints the SDK calls, with examples |
| [troubleshooting.md](troubleshooting.md) | Symptom → cause → fix |
| [known-issues.md](known-issues.md) | Known limitations, workarounds, suggested fixes |

Also: [RELEASE_NOTES.md](../RELEASE_NOTES.md) · [LICENSE](../LICENSE.md)

---

## The 30-second version

```kotlin
// 1. Application.onCreate()
val updater = ApexHubUpdater(
    context = this,
    config = ApexHubConfig(publicKey = "pk_live_YOUR_KEY", channel = "stable"),
)
updater.schedulePeriodicCheck(appDisplayName = "My App")

// 2. MainActivity.onCreate()
lifecycleScope.launch { updater.checkAndPrompt(activity = this@MainActivity) }
```

```kotlin
// app/build.gradle.kts
implementation("io.github.mr-perfect-252:sdk:1.0.1")
```

That's it — the SDK handles the dialog, the download (SHA-256 verified), and the Android system
installer.

---

## How the pieces fit

```
        Your app                          ApexHub backend                 Storage
 ┌────────────────────┐   GET /api/update   ┌──────────────────┐
 │  ApexHubUpdater    │ ──────────────────► │  returns UpdateInfo│
 │   • checkForUpdate │                     │  + downloadUrl     │
 │   • checkAndPrompt │   GET downloadUrl   └────────┬──────────┘
 │   • trackEvent     │ ──────────────────►          │ proxy
 │                    │ ◄───── APK bytes ────────────┘ (private)
 │  ApkDownloader     │
 │  ApkInstaller ─────┼──► Android system installer (user confirms)
 └────────────────────┘
        │ WorkManager (UpdateCheckWorker)
        └──► notification: "update available"
```

- **`ApexHubUpdater`** — the public entry point.
- **`ApexHubApi`** (internal) — talks to the two REST endpoints.
- **`ApkDownloader`** (internal) — streams + verifies the APK.
- **`ApkInstaller`** (internal) — hands the APK to the system installer.
- **`UpdateCheckWorker`** (internal) — periodic background checks.

---

## Conventions in these docs

- **`public`** = callable from your app. **internal** = SDK-internal only (documented for
  completeness; not callable by you).
- Code samples are Kotlin and target `AppCompatActivity` + `lifecycleScope` unless stated otherwise.
- "the backend" means the ApexHub API at `ApexHubConfig.baseUrl`.
