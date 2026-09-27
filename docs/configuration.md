# Configuration Reference

Everything is configured through a single immutable `ApexHubConfig` object passed to
`ApexHubUpdater`.

```kotlin
val config = ApexHubConfig(
    publicKey            = "pk_live_YOUR_KEY_HERE",  // required
    packageName          = null,                     // default: host app's applicationId
    channel              = "stable",                 // "stable" | "beta" | "nightly"
    baseUrl              = "https://apex-hub-production.vercel.app",
    checkIntervalHours   = 6L,                       // >= 1
    updateStrategy       = UpdateStrategy.FLEXIBLE,
    allowMeteredNetwork  = false,
)
```

---

## `publicKey` — `String` (required)

Your app's public key from the Console (`pk_live_…` or `pk_test_…`).

**Validated at construction:**
```kotlin
require(publicKey.startsWith("pk_live_") || publicKey.startsWith("pk_test_"))
```
A key with any other prefix throws `IllegalArgumentException` immediately. This is deliberate:
public keys are safe to embed and are the only credential the SDK sends (`X-Api-Key`).

## `packageName` — `String?` (default `null`)

The Android package name to check updates for. When `null`, the SDK uses the host app's own
`context.packageName`.

Override this only for unusual setups (e.g. a shared updater process). The resolved value is sent
to the backend as the path parameter of `GET /api/update/{packageName}`.

## `channel` — `String` (default `"stable"`)

The release channel to subscribe to. The backend matches it exactly against the release row's
`channel` column, so any string your releases use works; the conventional values are:

| Channel | Intended use |
|---|---|
| `stable` | Production users |
| `beta` | Opt-in testers |
| `nightly` | Internal builds |

Sent as the `channel` query parameter. A channel with no live release simply returns
`updateAvailable: false`.

## `baseUrl` — `String` (default `https://apex-hub-production.vercel.app`)

The ApexHub API base URL. The default (`BuildConfig.DEFAULT_BASE_URL`, compiled into the SDK) points
at the production backend. **Override only for self-hosted deployments or local testing.**
Trailing slashes are trimmed.

```kotlin
baseUrl = "http://10.0.2.2:3001"   // Android emulator → localhost backend
```

> The download URL comes from the backend's response, not from `baseUrl`. The backend is responsible
> for returning a URL the device can reach (and, in production, always **HTTPS** — Android blocks
> cleartext traffic by default).

## `checkIntervalHours` — `Long` (default `6L`)

How often the **background** WorkManager job checks for updates.

**Validated:** must be `>= 1` or construction throws `IllegalArgumentException`.

This only affects `schedulePeriodicCheck(...)`; foreground checks you trigger yourself run
immediately. WorkManager treats the interval as a *minimum*, and the OS may defer runs (see
[background-checks.md](background-checks.md)).

## `updateStrategy` — `UpdateStrategy` (default `FLEXIBLE`)

Controls how the built-in dialog behaves.

| Value | Behaviour |
|---|---|
| `UpdateStrategy.FLEXIBLE` | The dialog has a **"Later"** button and is dismissible. Use for normal releases. |
| `UpdateStrategy.IMMEDIATE` | The dialog is **non-dismissible** (no "Later", `setCancelable(false)`). Use for critical security patches. |

See [ota-updates.md](ota-updates.md#update-strategies) for how this interacts with the release's
`mandatory` flag.

## `allowMeteredNetwork` — `Boolean` (default `false`)

When `false` (default), the background worker requires an **`UNMETERED`** network — it won't burn
mobile data. Set to `true` to allow background checks on cellular (`NetworkType.CONNECTED`).

This only constrains the *background* worker. Foreground downloads triggered from your Activity are
never gated by this flag.

---

## `UpdateStrategy` enum

```kotlin
enum class UpdateStrategy {
    FLEXIBLE,   // user can dismiss and update later
    IMMEDIATE   // app is blocked until the update is installed
}
```

---

## Validation summary

| Field | Rule | On violation |
|---|---|---|
| `publicKey` | starts with `pk_live_` or `pk_test_` | `IllegalArgumentException` at construction |
| `checkIntervalHours` | `>= 1` | `IllegalArgumentException` at construction |
| all others | none | — |

Because both checks run in the `init` block, a misconfigured `ApexHubConfig` fails fast and loudly —
you'll see it immediately in your logs rather than as a silent no-op later.

---

## Config for multiple environments

`ApexHubConfig` is a `data class`, so you can derive variants cheaply:

```kotlin
val base = ApexHubConfig(publicKey = BuildConfig.APEXHUB_KEY)

val config = if (BuildConfig.DEBUG) {
    base.copy(channel = "beta", checkIntervalHours = 1L)
} else {
    base
}
```
