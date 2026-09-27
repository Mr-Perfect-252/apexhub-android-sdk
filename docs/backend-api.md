# Backend API

The REST contract between the SDK and the ApexHub backend. You don't call these yourself — the SDK
does — but they're useful for debugging, proxies, CI checks, and self-hosting.

**Base URL:** `ApexHubConfig.baseUrl` (default `https://apex-hub-production.vercel.app`).

Every request carries:

| Header | Value |
|---|---|
| `X-Api-Key` | your public key (`pk_live_…`) — the only credential the SDK sends |
| `X-Sdk-Version` | `1.0.1` |
| `X-Platform` | `android` (update check only) |

The public key is safe to ship in an app; it is not a secret.

---

## `GET /api/update/{packageName}`

The OTA update check.

**Query parameters**

| Param | Required | Description |
|---|---|---|
| `channel` | yes | `stable` / `beta` / `nightly` (matched against the release's channel) |
| `installed` | yes | the device's current `versionCode` (`0` if unknown) |

**Example**

```bash
curl "https://apex-hub-production.vercel.app/api/update/com.example.myapp?channel=stable&installed=12" \
  -H "X-Api-Key: pk_live_YOUR_KEY"
```

**200 — update available**

```json
{
  "updateAvailable": true,
  "latestVersion": "1.1.1",
  "versionCode": 3,
  "downloadUrl": "https://apex-hub-production.vercel.app/api/releases/20825bdf-…/download",
  "sha256": "916b51278e73de15772eae557f90b6b4f190d131d10c2e95c851b8427e7090ce",
  "mandatory": false,
  "releaseNotes": "Fixed file paths",
  "rolloutPercent": 100,
  "channel": "stable",
  "certificateFingerprint": ""
}
```

**200 — up to date**

```json
{ "updateAvailable": false }
```

An excluded staged rollout also returns `updateAvailable: false` (with a `rolloutStatus` field).

**Errors**

| Status | Body | Cause |
|---|---|---|
| `401` | `{"error":"Missing or invalid public API key"}` | `X-Api-Key` absent or not `pk_…` |
| `404` | `{"error":"App not found or invalid API key"}` | no app matches the key/package |

### About `downloadUrl`

`downloadUrl` is an **ApexHub HTTPS endpoint** (`…/api/releases/{id}/download`), not a third-party URL.
When the device fetches it, the backend streams the APK from private storage server-side and returns
the bytes with `Content-Type: application/vnd.android.package-archive`. The device needs no
credentials. This keeps storage private while giving the SDK a stable, provider-agnostic URL.

The SDK's downloader follows redirects, so intermediate 302s are handled for you.

### Verification the SDK performs

- `versionCode` > installed `versionCode` (the backend only returns `updateAvailable: true` in that
  case).
- After download, `sha256` must match the file exactly, or the SDK deletes it.

---

## `POST /api/analytics/event`

Ingests one analytics event (`ApexHubUpdater.trackEvent`).

```bash
curl -X POST "https://apex-hub-production.vercel.app/api/analytics/event" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: pk_live_YOUR_KEY" \
  -d '{
        "app_id":"app_YOUR_APP_ID",
        "event_type":"custom",
        "event_name":"purchase_complete",
        "device_id":"…",
        "os_version":"Android 14 (API 34)",
        "device_model":"Google Pixel 8",
        "session_sec":0,
        "metadata":{"plan":"pro"}
      }'
```

**Attribution.** The backend resolves the app from the **`X-Api-Key`** header (or `authorization`),
falling back to a `package_name` field in the body. The `app_id` field is stored on the event but does
**not** determine which app it belongs to.

**Responses**

| Status | Body |
|---|---|
| `201` | `{"ok":true,"event_id":"<uuid>"}` |
| `404` | `{"error":"App not found for provided API key or package name"}` |
| `500` | `{"error":"Failed to record analytics event"}` |

Rate limiting applies (the analytics limiter is generous — see the backend config).

---

## Related endpoints (not used by the SDK)

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | liveness probe (`{ status, version, timestamp }`) |
| `GET /api/apps` | public marketplace listing |
| `GET /api/apps/{pkg}` | app detail |
| `GET /api/analytics/summary?app_id=…` | developer dashboard aggregates (auth required) |
| `POST /api/releases/upload` | publish an APK (secret key / auth) |
| `POST /api/releases/from-drive` | publish from a Google Drive link |

---

## Versioning & compatibility

- The SDK only depends on the two endpoints above; the response shape of `downloadUrl` and `sha256`
  is stable and the SDK tolerates unknown/absent fields (Gson null-safe reads).
- `certificateFingerprint` is **informational** in the SDK. Enforcement (rejecting an APK signed with
  a different key) happens server-side at upload time.

---

## Self-hosting / custom backends

Point `baseUrl` at your deployment:

```kotlin
ApexHubConfig(publicKey = "pk_live_…", baseUrl = "https://my-apexhub.example.com")
```

Your deployment must expose `GET /api/update/{packageName}` and `POST /api/analytics/event` with the
headers above, return `downloadUrl` values the device can reach, and (for production) serve them over
**HTTPS** — Android blocks cleartext by default.
