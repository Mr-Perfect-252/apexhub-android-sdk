# ApexHub Android SDK

Official Android SDK for [ApexHub](https://apexhub.app) — independent Android app distribution with
in-app **OTA updates**, **background update checks**, and **analytics**.

| Feature | What it does |
|---|---|
| **OTA updates** | Checks for a new version, prompts the user, downloads the APK (SHA-256 verified) and hands it to the Android installer |
| **Background checks** | A WorkManager job wakes up every N hours even when the app is closed and posts a notification |
| **Analytics** | Sends custom events (with device metadata) to your ApexHub dashboard |

> **Want automatic sessions, screen views, and crash reporting too?** Add the companion Maven
> artifact `io.github.mr-perfect-252:apex-analytics` — a drop-in `implementation(...)` that talks to
> ApexHub only. See [docs/analytics.md](docs/analytics.md#scope-what-this-sdk-does-and-doesnt-cover).

- **Package:** `com.apexhub.sdk`
- **Latest version:** `1.0.1`
- **Min SDK:** 21 · **Compile/Target SDK:** 34
- **Maven:** `io.github.mr-perfect-252:sdk`
- **Release notes:** [RELEASE_NOTES.md](RELEASE_NOTES.md)
- **License:** MIT

---

## Installation

The SDK is published to **Maven Central** — no credentials, no extra repositories.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.mr-perfect-252:sdk:1.0.1")
}
```

> **Upgrading from 1.0.0?** `1.0.1` is a drop-in patch — no API changes. See
> [RELEASE_NOTES.md](RELEASE_NOTES.md). Apps already shipped with 1.0.0 must be rebuilt
> and re-published to receive the fix.

The SDK depends on `okhttp`, `gson`, `androidx.core`, `androidx.appcompat`, and `androidx.work`
(they are `implementation` dependencies and are resolved transitively).

**Requirements**

- `android.useAndroidX=true` in `gradle.properties` (AndroidX is required).
- The host Activity you pass to the update flow must use an **AppCompat theme** (the SDK's dialogs
  use `androidx.appcompat.app.AlertDialog`).

---

## Quickstart

### 1. Get your public key

ApexHub Console → your app → **Settings** → copy the `pk_live_…` key.

> ⚠️ Only the **public** key (`pk_live_` / `pk_test_`) goes in your app. Never ship a secret key.

### 2. Schedule background checks — `Application.onCreate()`

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val updater = ApexHubUpdater(
            context = this,
            config = ApexHubConfig(
                publicKey = "pk_live_YOUR_KEY_HERE",
                channel = "stable",        // "stable" | "beta" | "nightly"
                checkIntervalHours = 6L,   // background check frequency (>= 1)
            )
        )

        // Safe to call on every launch — WorkManager deduplicates by work name.
        updater.schedulePeriodicCheck(appDisplayName = "My App")
    }
}
```

### 3. Check on launch — `MainActivity.onCreate()`

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var updater: ApexHubUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updater = ApexHubUpdater(
            context = this,
            config = ApexHubConfig(publicKey = "pk_live_YOUR_KEY_HERE"),
        )

        // One-liner: check → dialog → download (SHA-256 verified) → install
        lifecycleScope.launch {
            updater.checkAndPrompt(activity = this@MainActivity)
        }
    }
}
```

That's it. The SDK handles the update dialog, download progress, integrity verification, and the
Android system-installer flow.

---

## At a glance

```kotlin
// Check only (never throws)
val result = updater.checkForUpdate()          // UpdateCheckResult

// Full flow with default UI + callbacks
updater.checkAndUpdate(
    activity          = this,
    onUpdateFound     = { info -> true },       // return false to skip
    onProgress        = { pct  -> /* 0..100 */ },
    onReadyToInstall  = { act  -> /* APK verified */ },
    onError           = { msg  -> /* handle */ },
)

// Background scheduling
updater.schedulePeriodicCheck(appDisplayName = "My App")
updater.cancelPeriodicCheck()

// Analytics (fire-and-forget)
updater.trackEvent(appId = "app_123", eventType = "custom", eventName = "purchase_complete")
```

---

## Documentation

| Guide | Contents |
|---|---|
| [docs/getting-started.md](docs/getting-started.md) | Install, permissions, key, initialization, first update |
| [docs/configuration.md](docs/configuration.md) | Every `ApexHubConfig` field, defaults, validation rules |
| [docs/ota-updates.md](docs/ota-updates.md) | The full update flow: check → prompt → download → install, strategies, mandatory updates |
| [docs/background-checks.md](docs/background-checks.md) | WorkManager scheduling, constraints, notifications, battery |
| [docs/analytics.md](docs/analytics.md) | `trackEvent`, payload, attribution, dashboards |
| [docs/api-reference.md](docs/api-reference.md) | Public API surface: classes, methods, models, exceptions |
| [docs/backend-api.md](docs/backend-api.md) | The REST endpoints the SDK calls |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Common failures and fixes |
| [docs/known-issues.md](docs/known-issues.md) | Known limitations + workarounds |
| [RELEASE_NOTES.md](RELEASE_NOTES.md) | Version history |

---

## Permissions

The SDK ships its own manifest and **merges these automatically** — you don't add anything:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

It also contributes the `FileProvider` (`${applicationId}.apexhub.fileprovider`) and the
`res/xml/apexhub_file_paths.xml` used to share the downloaded APK with the system installer.

> On Android 8.0+ the user must also grant **"Install unknown apps"** for your app. The SDK walks
> them through this — see [docs/ota-updates.md](docs/ota-updates.md#the-install-permission-flow).

---

## License

MIT © ApexHub Team
