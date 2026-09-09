# ApexHub Android SDK

Official Android SDK for [ApexHub](https://apexhub.app) — the independent Android app store.

Gives your app three capabilities in one library:

| Feature | What it does |
|---|---|
| **OTA Updates** | Periodically checks for new versions, prompts users, downloads & installs |
| **Background checks** | WorkManager job runs every N hours even when app is closed |
| **Analytics** | Sends install, update and custom events to your ApexHub dashboard |

---

## Installation

Add the GitHub Packages repository and the dependency to your app's `build.gradle.kts`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Mr-Perfect-252/apexhub-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR") ?: "").get()
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN") ?: "").get()
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.apexhub:sdk:1.0.0")
}
```

---

## Quickstart

### 1. Get your Public Key

In the [ApexHub Console](https://apexhub.app/console) → your app → **Settings** tab.
Copy the `pk_live_...` key.

### 2. Initialize (Application.onCreate recommended)

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val updater = ApexHubUpdater(
            context = this,
            config = ApexHubConfig(
                publicKey = "pk_live_YOUR_KEY_HERE",
                channel = "stable",            // or "beta", "nightly"
                checkIntervalHours = 6,        // background check frequency
            )
        )

        // Schedule silent background checks — posts a notification when update found
        updater.schedulePeriodicCheck(appDisplayName = "My App")
    }
}
```

### 3. Foreground check on launch (MainActivity.onCreate)

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var updater: ApexHubUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updater = ApexHubUpdater(
            context = this,
            config = ApexHubConfig(publicKey = "pk_live_YOUR_KEY_HERE")
        )

        // One-liner: check → dialog → download → install
        lifecycleScope.launch {
            updater.checkAndPrompt(activity = this@MainActivity)
        }
    }

    // Resume pending install if user went to settings to grant permission
    override fun onResume() {
        super.onResume()
        ApkInstaller.resumePendingInstall(this)
    }
}
```

---

## Advanced: Custom UI

```kotlin
lifecycleScope.launch {
    updater.checkAndUpdate(
        activity = this@MainActivity,
        onUpdateFound = { info ->
            // Show your own dialog — return true to proceed with download
            showCustomUpdateSheet(info.latestVersion, info.releaseNotes)
        },
        onProgress = { percent ->
            progressBar.progress = percent
            progressText.text = "Downloading... $percent%"
        },
        onError = { message ->
            Log.e("ApexHub", "Update check failed: $message")
        }
    )
}
```

---

## Update Strategies

| Strategy | Behaviour |
|---|---|
| `UpdateStrategy.FLEXIBLE` (default) | User can tap "Later" and continue using the app |
| `UpdateStrategy.IMMEDIATE` | Dialog is non-dismissible — use only for critical security patches |

---

## Analytics

```kotlin
lifecycleScope.launch {
    updater.trackEvent(
        appId = "app_YOUR_APP_ID",    // from ApexHub Console
        eventType = "custom",
        eventName = "purchase_complete",
        metadata = mapOf("plan" to "pro", "price" to 9.99)
    )
}
```

Events appear in your ApexHub Console → **Analytics** tab in real time.

---

## AndroidManifest.xml

The SDK merges these permissions automatically via its own manifest:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

No manual additions needed.

---

## License

MIT © ApexHub Team
