# Analytics

The SDK can ship custom events to your ApexHub dashboard with a single call. Failures are silent by
design — analytics never blocks or crashes a user flow.

---

## `trackEvent`

```kotlin
suspend fun trackEvent(
    appId: String,
    eventType: String,
    eventName: String,
    metadata: Map<String, Any> = emptyMap(),
)
```

```kotlin
lifecycleScope.launch {
    updater.trackEvent(
        appId     = "app_YOUR_APP_ID",
        eventType = "custom",
        eventName = "purchase_complete",
        metadata  = mapOf("plan" to "pro", "price" to 9.99),
    )
}
```

| Parameter | Meaning |
|---|---|
| `appId` | Your app's ID from the Console. See [attribution](#attribution) — this is *informational*, the backend resolves the app from the API key. |
| `eventType` | Broad category: `install`, `update`, `crash`, `custom`, … Free-form string. |
| `eventName` | Specific event: `button_click`, `purchase_complete`, … Free-form string. |
| `metadata` | Arbitrary JSON-serialisable key/value map attached to the event. |

The call is a suspend function that performs the network request on `Dispatchers.IO`, but you should
treat it as **fire-and-forget**: it wraps everything in a `try/catch` and swallows all errors.

---

## What the SDK attaches automatically

`trackEvent` enriches the event with device metadata gathered once per call:

| Field | Source |
|---|---|
| `device_id` | Random UUID, generated on first use and persisted in `SharedPreferences("apexhub_prefs")` → stable per install |
| `os_version` | `"Android <release> (API <sdk>)"` |
| `device_model` | `"<MANUFACTURER> <MODEL>"` |
| `session_sec` | Always `0` from this entry point (no session tracking) |

The `device_id` is not a hardware identifier — it's a random value regenerated on reinstall, so it
does not track users across installs.

---

## The request

```
POST {baseUrl}/api/analytics/event
Headers
  X-Api-Key:      pk_live_…
  X-Sdk-Version:  1.0.1
Content-Type:     application/json
Body
{
  "app_id":       "app_YOUR_APP_ID",
  "event_type":   "custom",
  "event_name":   "purchase_complete",
  "device_id":    "…",
  "os_version":   "Android 14 (API 34)",
  "device_model": "Google Pixel 8",
  "session_sec":  0,
  "metadata":     { "plan": "pro", "price": 9.99 }
}
```

The backend responds `201 { "ok": true, "event_id": "…" }` on success and `404` if it cannot map the
key to an app. The SDK ignores both — see [backend-api.md](backend-api.md#post-apianalyticsevent).

---

## Attribution

The backend identifies which app an event belongs to from the **`X-Api-Key` header** (your public
key), falling back to a `package_name` in the body. This means:

- The `appId` you pass to `trackEvent` does **not** affect which app the event lands in — the API key
  does. Pass the correct key (`ApexHubConfig.publicKey`) and events are attributed automatically.
- If the API key doesn't match any app, the event is dropped with `404` (silently, from the SDK's
  point of view).

If you operate multiple apps, use each app's own public key.

---

## Viewing events

Events appear in **ApexHub Console → your app → Analytics**, alongside aggregates the backend computes
on read (`GET /api/analytics/summary`): active devices, launches, crashes, average session, and an OS
distribution breakdown.

---

## Scope: what this SDK does *and doesn't* cover

**Included:** custom event tracking with device metadata (above).

**Not included:** automatic session tracking, automatic install/update events, and crash reporting.
The SDK provides the transport for the events *you* emit; it does not instrument your app for you.

If you need richer product analytics (sessions, screen views, funnels) and automatic crash capture,
install the companion Maven artifact **`apex-analytics`** — no other setup is needed, it is a normal
Maven Central dependency:

```kotlin
dependencies {
    implementation("io.github.mr-perfect-252:apex-analytics:1.0.0")
}
```

`apex-analytics` talks to **ApexHub only** (its ingestion endpoints are baked into the SDK) and is
activated by passing your app's `pk_live_…` key as `apiKey` to `OpenAnalytics.init(...)`. It ingests
at `POST /api/v1/track` and `POST /api/v1/crash-report`. Both feeds land in the same dashboard as
`trackEvent`, so you can use one or both.

---

## Best practices

- **Batch high-frequency events** rather than calling `trackEvent` in a tight loop — one request is
  made per call.
- **Keep `metadata` small and flat.** Prefer primitive values; nested maps are serialised as JSON.
- **Don't put PII in events.** `metadata` is arbitrary strings — treat it as you would any telemetry.
- **Use stable `eventName` values.** They are the grouping key on the dashboard; changing them splits
  your history.

---

## Related

- [configuration.md](configuration.md) — `baseUrl` and `publicKey`
- [backend-api.md](backend-api.md) — the ingest contract
