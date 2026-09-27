# Getting Started

Get in-app OTA updates working in about 10 minutes.

- [1. Requirements](#1-requirements)
- [2. Add the dependency](#2-add-the-dependency)
- [3. Get your public key](#3-get-your-public-key)
- [4. Initialize and schedule background checks](#4-initialize-and-schedule-background-checks)
- [5. Check for updates on launch](#5-check-for-updates-on-launch)
- [6. Verify it works](#6-verify-it-works)

---

## 1. Requirements

| | |
|---|---|
| Min SDK | 21 (Android 5.0) |
| Compile / Target SDK | 34 |
| AndroidX | required (`android.useAndroidX=true` in `gradle.properties`) |
| Host Activity | must use an **AppCompat theme** (the SDK dialogs use `androidx.appcompat.app.AlertDialog`) |
| Kotlin coroutines | the update flow is suspending — call it from a `lifecycleScope`/`viewModelScope` |

To actually install an update on Android 8.0+, the user must grant your app the **"Install unknown
apps"** permission. The SDK handles the request flow for you; see
[ota-updates.md](ota-updates.md#the-install-permission-flow).

---

## 2. Add the dependency

The SDK is on **Maven Central** — no credentials or extra repositories.

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

Dependencies (`okhttp`, `gson`, `androidx.core`, `androidx.appcompat`, `androidx.work`) come along
transitively. There is nothing else to add to your manifest — see
[Permissions](#permissions-are-merged-automatically).

---

## 3. Get your public key

**ApexHub Console → your app → Settings → Public key** (looks like `pk_live_…`).

Use it in `ApexHubConfig.publicKey`. The SDK enforces the `pk_live_` / `pk_test_` prefix at
construction time and throws `IllegalArgumentException` otherwise.

> The **secret key** (`sk_live_…`) is for CI/CD publishing only. Never embed it in an app.

Not sure of your package name? `ApexHubConfig.packageName` defaults to the host app's application
id, which is almost always what you want.

---

## 4. Initialize and schedule background checks

Create the updater once and schedule the periodic worker — `Application.onCreate()` is the right
place:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val updater = ApexHubUpdater(
            context = this,
            config = ApexHubConfig(
                publicKey = "pk_live_YOUR_KEY_HERE",
                channel = "stable",
                checkIntervalHours = 6L,
            )
        )

        updater.schedulePeriodicCheck(appDisplayName = getString(R.string.app_name))
    }
}
```

`schedulePeriodicCheck` is idempotent: it enqueues unique periodic work, so calling it on every
launch does not stack jobs. It runs on an **unmetered** network by default (see
[`allowMeteredNetwork`](configuration.md#allowmeterednetwork)).

---

## 5. Check for updates on launch

For the foreground flow, use the one-liner in your launcher Activity:

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

        lifecycleScope.launch {
            updater.checkAndPrompt(activity = this@MainActivity)
        }
    }
}
```

`checkAndPrompt` runs the whole flow: check → show the update dialog → download (SHA-256 verified) →
launch the system installer. It is silent on errors (a network blip never interrupts the user) — use
`checkAndUpdate` if you want to handle failures yourself.

For full control over the UI, see [ota-updates.md](ota-updates.md).

---

## 6. Verify it works

1. Publish a release with a **higher `versionCode`** than your installed build (Console → your app →
   Releases → Upload, or the CLI).
2. Launch the app (or wait for the background check).
3. You should see the **"Update available"** dialog; tapping **Update Now** downloads and starts the
   system installer.

Watch `logcat` with tag **`ApexHub`** to follow the flow:

```bash
adb logcat -s ApexHub
```

Sample output:

```
D/ApexHub: Checking for update: pkg=com.example.myapp installed=12 channel=stable
D/ApexHub: Update check result: available=true latest=1.3.0 code=13
D/ApexHub: Downloading update 1.3.0 from https://apex-hub-production.vercel.app/api/releases/…/download
D/ApexHub: APK download complete: 8420916 bytes
D/ApexHub: Launching system installer for apexhub-update-1.3.0.apk
```

If nothing happens, see [troubleshooting.md](troubleshooting.md).

---

## Permissions are merged automatically

The SDK contains its own `AndroidManifest.xml`, so these merge into your app — **do not add them**:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

The SDK also contributes the FileProvider below (used to hand the APK to the installer):

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.apexhub.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true" />
```

> If you already declare a `FileProvider` with the same authority, remove the duplicate — the SDK's
> entry would otherwise collide with yours at manifest-merge time.

---

## Next steps

- [configuration.md](configuration.md) — every config field
- [ota-updates.md](ota-updates.md) — custom UI, update strategies, mandatory updates
- [background-checks.md](background-checks.md) — WorkManager behaviour
- [analytics.md](analytics.md) — `trackEvent`
