# PHASE 06.8 — COMPLETE SECURITY / PRIVACY / MANIFEST / WEBVIEW / RELEASE-HARDENING FORENSIC AUDIT REPORT

**Project:** CineStream Users App (`CineStream Pro`)  
**Target:** `CineStream-Pro00-main.zip`  
**Mode:** STRICT READ-ONLY / ZERO PRODUCTION MODIFICATIONS  
**Audit Type:** Forensic Security, Privacy, Attack Surface, Release Hardening, and Device Security Audit  
**Date:** October 6, 2026  
**Artifact:** `/PHASES/PHASE_06.8_SECURITY_PRIVACY_MANIFEST_WEBVIEW_RELEASE_HARDENING_FORENSIC_AUDIT_REPORT.md`  

---

## 0. Executive Summary & Verification of Invariants

This forensic audit evaluates the complete security posture, attack surface, device permissions, component exposure, WebView architecture, network communication, peer-to-peer protocols, cryptographic integrity, storage security, secret management, and release build hardening of the CineStream Pro users application.

The analysis is based strictly on the current source code artifact with zero assumptions and zero modifications.

### 0.1 High-Level Vulnerability Summary

| Vulnerability ID | Category | Severity | Description | Code Reference |
|---|---|---|---|---|
| **SEC-01** | **P2P File System** | **CRITICAL** | Path Traversal & Arbitrary File Overwrite in P2P file transfer. Unsanitized `id` and `extension` allow writing outside `movies/` directory into databases/shared_prefs. | `MediaStorageUtils.kt:98-103`, `P2PManager.kt:714` |
| **SEC-02** | **WebView Security** | **HIGH** | `allowFileAccess = true`, `allowContentAccess = true`, and `mixedContentMode = ALWAYS_ALLOW` enabled on invisible background WebView rendering untrusted third-party scraper websites. | `BackgroundWebView.kt:64, 81-83` |
| **SEC-03** | **Network Security** | **HIGH** | Global cleartext HTTP traffic enabled (`usesCleartextTraffic="true"`) without Network Security Configuration or domain whitelist. | `AndroidManifest.xml:41` |
| **SEC-04** | **P2P Transport** | **HIGH** | Unauthenticated, unencrypted cleartext TCP ServerSocket and UDP DatagramSocket exposed on port 8888; background service started automatically at application startup. | `P2PManager.kt:234-237, 524-549` |
| **SEC-05** | **Privacy / Lifecycle** | **HIGH** | FCM token dissociation on logout is completely uncalled (`dissociateTokenOnLogout` is dead code); push notifications continue delivering to device post-logout. | `FcmTokenManager.kt:177-192`, `AuthRepository.kt:506-513` |
| **SEC-06** | **Play Policy / Privacy**| **HIGH** | Complete absence of In-App Account Deletion; user deletion is hard-blocked by Firestore security rules (`allow delete: if isAdmin()`). | `firestore.rules:109`, `AuthRepository.kt` |
| **SEC-07** | **Release Hardening** | **HIGH** | ProGuard / R8 minification and obfuscation disabled in release build type (`isMinifyEnabled = false`); release build signs with debug keystore. | `app/build.gradle.kts:47, 49` |
| **SEC-08** | **Integrity Verification**| **MEDIUM** | App update download delegates to untargeted system browser intent (`ACTION_VIEW`); remote `apkSha256` checksum is parsed but never validated on-device. | `AppUpdateDialog.kt:137-140`, `AppConfig.kt:83` |
| **SEC-09** | **Hardcoded Secrets** | **MEDIUM** | Hardcoded StartApp App ID (`208324071`), hardcoded hotspot fallback password (`cinestream123`), and live TMDB API key committed in `.env.example`. | `MainActivity.kt:66`, `HotspotManager.kt:61`, `.env.example:7` |
| **SEC-10** | **Authentication Race**| **MEDIUM** | Silent anonymous account creation invoked in background extension sync managers overrides unauthenticated guest state. | `ManagedExtensionRealtimeSyncManager.kt:77`, `FirebaseFirestoreManagedExtensionDataSource.kt:45` |
| **SEC-11** | **Data Backup** | **MEDIUM** | Full application backup enabled (`allowBackup="true"`) with unconfigured template backup rules; databases and credentials extractable via ADB backup. | `AndroidManifest.xml:33`, `res/xml/backup_rules.xml` |
| **SEC-12** | **Permission Hygiene** | **LOW** | Obsolete legacy storage permissions (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage`) declared but unreferenced in code. | `AndroidManifest.xml:11-12, 40` |

---

## 1. Complete Attack Surface Inventory

Every external input entering the CineStream application was inventoried, categorized, and assigned a trust boundary.

### 1.1 External Input Matrix

| External Input Source | Input Type | Trust Level | Entry Point in Code | Validation Mechanism | Threat Vector |
|---|---|---|---|---|---|
| **Firebase Auth** | Auth Tokens, UIDs, Emails | **SEMI-TRUSTED** | `AuthRepository.init`, `FirebaseAuth.getInstance()` | Google Identity SDK validation; client assumes UID authenticity | Stale session tokens, anonymous session hijacking |
| **Firebase Firestore** | User Profiles, Configurations, Remote Extensions | **SEMI-TRUSTED** | `AuthRepository.fromDocument`, `AppConfig.fromDocument`, `ManagedExtensionDto` | Firestore Security Rules validate field types; schema parsing handles missing fields | Client-authoritative economy manipulation in temp mode |
| **Firebase Cloud Messaging (FCM)** | Remote Push Payloads | **UNTRUSTED** | `AppFirebaseMessagingService.onMessageReceived` | Payload null-checks; preferences validation | Spoofed push notifications, malicious deep links |
| **TMDB API** | Metadata JSON (Movies, Series, Cast, Images) | **SEMI-TRUSTED** | `RetrofitClient`, `TmdbApiService` | Moshi JSON adapters | Content spoofing, broken image URLs |
| **Cloudinary** | Image Upload Responses, CDN URLs | **SEMI-TRUSTED** | `AuthRepository.uploadProfilePicture` | Unsigned upload preset verification; HTTPS secure URL check | Storage quota exhaustion, profile picture spoofing |
| **Scraper Websites** | Raw HTML, Scraped Video Streams, Embedded iframes | **UNTRUSTED** | `WitanimeScraper`, `AkwamScraper`, `ArabseedScraper`, `BackgroundWebView` | Jsoup HTML parsing; regex URL extraction | Malicious JavaScript injection, redirects, clickjacking |
| **WebView URLs** | Navigation URLs, Redirects | **UNTRUSTED** | `BackgroundWebView.kt`, `InteractiveChallengeWebView.kt`, `PlayerScreen.kt` | Regex host verification; Turnstile challenge detection | Arbitrary cross-origin navigation, SSRF |
| **WebView JavaScript** | Executed Scripts, DOM Modifications | **UNTRUSTED** | `evaluateJavascript()` in `BackgroundWebView`, `InteractiveChallengeWebView` | Hardcoded injected helper strings | Script injection from compromised remote scrapers |
| **WebView Cookies** | Session Cookies, Cloudflare Clearances | **UNTRUSTED** | `CookieManager`, `PerSiteSessionStore` | Hostname domain scoping | Cookie poisoning, session fixation |
| **HTTP Headers** | Custom Headers, User-Agent, Referer | **UNTRUSTED** | `StreamDownloaderService.kt`, `RetrofitClient.kt` | Header sanitization removes auth/cookie tokens | Header injection (CRLF), origin spoofing |
| **P2P TCP Socket** | Handshake JSON, Transfer Headers, Raw Byte Stream | **UNTRUSTED** | `P2PManager.handleIncomingClientSocket`, `listenForIncomingFromPeer` | `JSONObject` parsing, user confirmation prompt | **Path Traversal**, arbitrary file overwrite, DoS |
| **P2P UDP Packets** | Multicast Discovery Pings (`CINESTREAM_PING`) | **UNTRUSTED** | `P2PManager.startUdpDiscoveryResponder` | Semicolon split on string prefix | Broadcast amplification, device enumeration |
| **Google Nearby Payloads**| Byte Payloads, File Streams | **UNTRUSTED** | `P2PManager.PayloadCallback` | `JSONObject` metadata payload | Memory exhaustion, malicious payload parsing |
| **QR Code Payloads** | Encoded URIs (`cinestream://p2p?...`) | **UNTRUSTED** | `QrCodeScannerDialog.kt`, `ShareScreen.kt` | ZXing decode; `Uri.parse()` query parameter extraction | Malicious network redirect, local hotspot takeover |
| **Android Intent Extras** | Service Commands, File IDs, URLs | **UNTRUSTED** | `StreamDownloaderService.onStartCommand` | String extraction; type safety in internal intents | Service manipulation if exported (service is unexported) |
| **App Update URLs** | Remote APK Download URL | **UNTRUSTED** | `AppConfig.apkUrl`, `AppUpdateDialog.kt` | String extraction; passed to system browser | Malicious APK sideloading if Firestore compromised |
| **File Names / Paths** | Media IDs, Download File Extensions | **UNTRUSTED** | `MediaStorageUtils.getDestinationFile` | **None**; directly concatenated into `File(dir, fileName)` | **Directory traversal outside sandbox** |
| **User Text Inputs** | Chat messages, support requests, search queries | **UNTRUSTED** | Compose `TextField`, `ChatViewModel`, `SupportViewModel` | Trimming, blank checks, length limits | Stored XSS in WebViews, database pollution |

---

## 2. AndroidManifest Forensics

A complete component-by-component audit was conducted on `/app/src/main/AndroidManifest.xml`.

```xml
Package Name: com.aistudio.cinestream.ivkgns
Namespace: com.example
Compile SDK: 35 | Target SDK: 36 | Min SDK: 24
```

### 2.1 Manifest Component Inventory

| Component Name | Type | Exported | Intent Filter | Permissions Required | Authentication Required | Risk Level |
|---|---|---|---|---|---|---|
| `com.example.MainActivity` | Activity | **true** | `android.intent.action.MAIN`<br>`android.intent.category.LAUNCHER` | None | None | **LOW** (Standard Launcher) |
| `com.example.ui.screens.crash.CrashActivity` | Activity | **false** | None | None | None | **NONE** (Internal Only) |
| `com.example.utils.StreamDownloaderService` | Service | **false** | None | None (Runs with `foregroundServiceType="dataSync"`) | None | **LOW** (Internal Only) |
| `com.example.services.AppFirebaseMessagingService` | Service | **false** | `com.google.firebase.MESSAGING_EVENT` | Protected by `BIND_JOB_SERVICE` / FCM internal receiver | None | **LOW** (Standard FCM) |
| *Content Providers* | Provider | — | None | None | None | **NONE** (No Providers Registered) |
| *Broadcast Receivers* | Receiver | — | None | None | None | **NONE** (No Static Receivers Registered) |

### 2.2 Application-Level Manifest Flags Audit

1. **`android:allowBackup="true"` (MEDIUM RISK):**
   - The manifest enables full application backup.
   - The backup configuration files (`@xml/backup_rules` and `@xml/data_extraction_rules`) contain only commented-out templates.
   - **Vulnerability:** An attacker with physical access or USB debugging enabled can invoke `adb backup` to extract the full Room SQLite database (`app_database`), user credentials, cached session cookies, and DataStore preferences.
2. **`android:usesCleartextTraffic="true"` (HIGH RISK):**
   - Enables cleartext HTTP traffic globally across the application.
   - Bypasses Android 9+ (API 28) default cleartext protection.
   - **Vulnerability:** Video streams, images, or metadata fetched over plain HTTP are vulnerable to active Man-in-the-Middle (MITM) tampering, traffic analysis, and injection.
3. **`android:requestLegacyExternalStorage="true"` (LOW RISK / POLICY SMELL):**
   - Legacy flag intended for API 29 compatibility during the transition to Scoped Storage.
   - Redundant on modern Android versions (API 30+) where Scoped Storage is enforced.
   - Disallowed or flagged by modern Google Play Console submission policies unless heavily justified.
4. **Absence of Deep Links / Intent Filters:**
   - `MainActivity` declares only `MAIN` / `LAUNCHER`.
   - The custom scheme `cinestream://p2p` used for QR codes is **not** registered as an intent filter in the manifest; QR codes are parsed strictly within the internal scanner dialog. This prevents third-party apps from sending malicious P2P intents directly to `MainActivity`.

---

## 3. Permission Justification & Residue Audit

Every permission declared in `/app/src/main/AndroidManifest.xml` was evaluated against actual production code references.

### 3.1 Permission Matrix

| Permission Name | Protection Level | Declared in Manifest | Runtime Requested | Actual Feature in Code | Necessity | Legacy Residue Risk |
|---|---|---|---|---|---|---|
| `android.permission.INTERNET` | Normal | Yes | No (Install-time) | Network API calls, video streaming, Firebase, Cloudinary, StartApp | **Required** | None |
| `android.permission.ACCESS_NETWORK_STATE` | Normal | Yes | No (Install-time) | `NetworkUtils.isInternetAvailable()`, OkHttp caching | **Required** | None |
| `android.permission.POST_NOTIFICATIONS` | Dangerous (API 33+) | Yes | Yes (`MainActivity.kt:75`) | System tray notifications for downloads, P2P transfers, FCM | **Required** | None |
| `android.permission.RECORD_AUDIO` | Dangerous | Yes | Yes (`ChatScreen.kt:105`) | In-chat voice note recording via `AudioRecorder.kt` | **Required** | None |
| `android.permission.CAMERA` | Dangerous | Yes | Yes (`ShareScreen.kt:280`) | QR code scanner dialog via `QrCodeScannerDialog.kt` | **Required** | None |
| `android.permission.BLUETOOTH` | Normal (Legacy < API 31) | Yes | No | Google Nearby Connections legacy communication | **Required (Legacy)**| None |
| `android.permission.BLUETOOTH_ADMIN` | Normal (Legacy < API 31) | Yes | No | Google Nearby Connections legacy communication | **Required (Legacy)**| None |
| `android.permission.ACCESS_WIFI_STATE` | Normal | Yes | No | Wi-Fi network information, IP detection in `NetworkUtils` | **Required** | None |
| `android.permission.CHANGE_WIFI_STATE` | Normal | Yes | No | Local-only hotspot management in `HotspotManager` | **Required** | None |
| `android.permission.CHANGE_NETWORK_STATE` | Normal / System | Yes | No | Network binding in `HotspotManager.connectToHotspot` | **Required** | None |
| `android.permission.CHANGE_WIFI_MULTICAST_STATE` | Normal | Yes | No | UDP discovery socket in `P2PManager` | **Required** | None |
| `android.permission.ACCESS_COARSE_LOCATION` | Dangerous | Yes | Yes (`ShareScreen.kt:273`) | Google Nearby Connections & Wi-Fi scanning (API < 33) | **Required** | None |
| `android.permission.ACCESS_FINE_LOCATION` | Dangerous | Yes | Yes (`ShareScreen.kt:265, 272`) | Google Nearby Connections & Wi-Fi scanning (API < 33) | **Required** | None |
| `android.permission.BLUETOOTH_SCAN` | Dangerous (API 31+) | Yes | Yes (`ShareScreen.kt:262`) | Google Nearby Connections BLE discovery | **Required** | None |
| `android.permission.BLUETOOTH_ADVERTISE` | Dangerous (API 31+) | Yes | Yes (`ShareScreen.kt:263`) | Google Nearby Connections BLE advertising | **Required** | None |
| `android.permission.BLUETOOTH_CONNECT` | Dangerous (API 31+) | Yes | Yes (`ShareScreen.kt:264`) | Google Nearby Connections Bluetooth socket | **Required** | None |
| `android.permission.NEARBY_WIFI_DEVICES` | Dangerous (API 33+) | Yes | Yes (`ShareScreen.kt:266`) | Wi-Fi Direct / Local Hotspot socket connection | **Required** | Version mismatch bug in `ShareScreen` |
| `android.permission.FOREGROUND_SERVICE` | Normal | Yes | No (Install-time) | `StreamDownloaderService` download manager | **Required** | None |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Normal (API 34+) | Yes | No (Install-time) | Required foreground service type for data sync downloads | **Required** | None |
| `android.permission.READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) | Dangerous (Legacy) | Yes | **No (Never requested)** | **Zero references in Kotlin code.** Media files stored in `filesDir`. | **UNNECESSARY** | **Legacy Residue** |
| `android.permission.WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) | Dangerous (Legacy) | Yes | **No (Never requested)** | **Zero references in Kotlin code.** Media files stored in `filesDir`. | **UNNECESSARY** | **Legacy Residue** |

### 3.2 Key Permission Findings

1. **Unnecessary Legacy Storage Permissions:**
   - Both `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` are declared in `AndroidManifest.xml:11-12`.
   - The application stores downloaded media files strictly in app-internal storage (`context.filesDir/movies`).
   - Profile picture selection utilizes standard activity launchers.
   - No code references `MediaStore` writes or external storage paths.
   - **Remediation:** Remove both permissions entirely from `AndroidManifest.xml`.
2. **`NEARBY_WIFI_DEVICES` API Level Guard Mismatch:**
   - In `ShareScreen.kt:260-267`, `Manifest.permission.NEARBY_WIFI_DEVICES` is included in the runtime permission list when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31).
   - However, `NEARBY_WIFI_DEVICES` was introduced in Android 13 (`Build.VERSION_CODES.TIRAMISU`, API 33).
   - On Android 12 (API 31 and 32), requesting this permission results in a framework warning or failed permission state.

---

## 4. Authentication Security & Identity Lifecycle

The authentication architecture was audited across `FirebaseAuth`, `FirebaseFirestore`, `AuthRepository`, `AuthViewModel`, `UserSecurityManager`, and background sync workers.

### 4.1 Authentication Lifecycle Audit

```
Unauthenticated Guest
       │
       ├── Background Extension Sync ──> auth.signInAnonymously() [SILENT CREATION]
       │                                         │
       │                                         ▼
       │                               FirebaseAuth.currentUser != null
       │                                         │
       ├── Explicit User Action ─────────────────┼──────────────────────────────┐
       │                                         │                              │
       ▼                                         ▼                              ▼
Email/Password Sign-In               Google Sign-In (CredentialManager)     Sign-Out (signOut())
       │                                         │                              │
       └───────────────────┬─────────────────────┘                              ▼
                           ▼                                            auth.signOut()
                Auth State Listener Triggered                           [DATA RETENTION GAPS]
                           │                                            - FCM token retained
                           ▼                                            - Room support retained
                   getCurrentUser()                                     - Room notifs retained
              Fetch /users/{auth.uid}                                   - DataStore retained
```

### 4.2 Detailed Authentication Findings

1. **Silent Anonymous User Creation Race (HIGH):**
   - In `ManagedExtensionRealtimeSyncManager.kt:77`, `FirebaseFirestoreManagedExtensionDataSource.kt:45`, and `SearchOrderDataSource.kt:35`:
     ```kotlin
     val auth = FirebaseAuth.getInstance()
     if (auth.currentUser == null) {
         auth.signInAnonymously().await()
     }
     ```
   - When a user logs out or launches the app in guest mode, background extension initialization triggers `auth.signInAnonymously().await()`.
   - This creates an anonymous Firebase user in the background.
   - The `AuthRepository.init` listener immediately detects `auth.currentUser != null` and attaches `UserSecurityManager.listenToUserSecurity(anonymousUid)`.
   - If anonymous authentication is disabled on the Firebase project backend, the call throws an exception and logs a notice. If enabled, it silently flips the app from unauthenticated to an anonymous authenticated state.
2. **Complete Absence of In-App Account Deletion (HIGH — Play Store Violation):**
   - Google Play Developer Policy (Data Safety / User Data) mandates that any app providing account creation must provide a prominent, accessible mechanism to delete the account and associated data.
   - Audit across the entire codebase confirms: **Zero account deletion functions exist.** Neither `FirebaseUser.delete()` nor any `/users/{uid}` deletion RPC is implemented in `AuthRepository`, `AuthViewModel`, or `AccountScreen`.
   - Furthermore, `firestore.rules:109` enforces:
     ```javascript
     allow delete: if isAdmin();
     ```
     Normal users are explicitly forbidden by security rules from deleting their own `/users/{uid}` documents.
3. **FCM Token Abandonment on Logout (HIGH — Privacy Leakage):**
   - In `FcmTokenManager.kt:177-192`, `dissociateTokenOnLogout(oldUid)` is defined to delete the device's installation document from `/users/{uid}/fcmTokens/{installationId}`.
   - **Forensic Check:** `dissociateTokenOnLogout` is **never called anywhere in the codebase**.
   - In `AuthRepository.signOut()` (`AuthRepository.kt:506-513`), only `auth.signOut()`, `currentUserFlow.value = null`, `UserSecurityManager.reset()`, and `EconomyConfigRepository.stopListening()` are invoked.
   - **Impact:** The device's active FCM registration token remains registered under the previous user's Firestore profile. Targeted push notifications sent to that user ID by backend administrators will continue to be delivered to the physical device.
4. **Local Data Persistence Cross-Contamination on Logout (MEDIUM):**
   - When a user signs out:
     - `SupportDao` retains all private support messages in the Room database (`support_messages` table).
     - `NotificationDao` retains all received notifications in Room (`notifications` table).
     - `UserPreferencesRepository` retains blocked user IDs (`blocked_users`) and friend request sets (`friend_requests`) in DataStore.
     - `P2PTransferRepository` and `NearbyDeviceRepository` retain history and remembered peer records in `SharedPreferences`.
   - A subsequent user logging in on the same device immediately accesses the previous user's cached support chat and local records.
5. **Client-Authoritative Profile & Economy State in Firestore Rules (MEDIUM):**
   - In `firestore.rules:97-106`, `allow update` permits the document owner to modify all fields except `role`, `admin`, `isAdmin`, bans, and device/download limits.
   - Because `subscriptionTier`, `subscriptionStatus`, `subscriptionExpiresAt`, and `pointsBalance` are omitted from the forbidden keys list, an authenticated client can directly modify their subscription tier to `PRO` and extend expiration dates via standard Firestore SDK writes.

---

## 5. WebView, Scraper & JavaScript Attack Surface

The application utilizes WebViews in three distinct contexts:
1. `BackgroundWebView.kt`: Invisible background scraper verification and Cloudflare Turnstile automated bypass.
2. `InteractiveChallengeWebView.kt`: Player-contained interactive Turnstile challenge solver.
3. `PlayerScreen.kt`: Hidden WebView fallback for video extraction.

### 5.1 WebView Configuration Comparison Matrix

| Setting / Property | `BackgroundWebView.kt` | `InteractiveChallengeWebView.kt` | `PlayerScreen.kt` | Security Evaluation |
|---|---|---|---|---|
| **JavaScript Enabled** | `true` | `true` | `true` | Required for Cloudflare and dynamic video extraction. |
| **DOM Storage** | `true` | `true` | `true` | Required for Turnstile tokens. |
| **`allowFileAccess`** | **`true` (HIGH RISK)** | `false` | Default (`false` on API 30+) | **CRITICAL FLAW in BackgroundWebView:** Permits reading local `file://` URIs. |
| **`allowContentAccess`** | **`true` (HIGH RISK)** | `false` | Default (`true`) | Permits reading content providers. |
| **Mixed Content Mode** | **`ALWAYS_ALLOW` (HIGH)** | `NEVER_ALLOW` | Default (`NEVER_ALLOW`) | **CRITICAL FLAW in BackgroundWebView:** Allows unencrypted HTTP content in HTTPS. |
| **SSL Error Handling** | `handler?.cancel()` | Default (Cancels) | Default (Cancels) | **PASS:** Does not bypass invalid SSL certificates. |
| **Visual Presentation** | **`alpha = 0.01f` (Overlay)** | Contained inside Player UI | Contained inside Player UI | **CRITICAL FLAW in BackgroundWebView:** Full-screen invisible overlay. |
| **JavaScript Interface** | None | None | None | **PASS:** No native Android objects exposed via `@JavascriptInterface`. |
| **Third-Party Cookies** | `true` | `true` | `true` | Required for Cloudflare clearance tokens across CDN domains. |

### 5.2 Deep-Dive: `BackgroundWebView.kt` Vulnerabilities

1. **Arbitrary File Access Vulnerability:**
   - Line 81: `settings.allowFileAccess = true`.
   - Line 82: `settings.allowContentAccess = true`.
   - The WebView navigates to remote, untrusted third-party scraper websites (e.g. streaming indexers, anime scrapers).
   - If a malicious or compromised scraper returns an HTML payload with an iframe pointing to `file:///data/data/com.aistudio.cinestream.ivkgns/...`, the WebView engine allows file reading.
2. **Cleartext Downgrade via Mixed Content:**
   - Line 83: `settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW`.
   - If a scraper site loads assets over cleartext HTTP, the WebView executes those scripts without warning, exposing the session to active network injection.
3. **Clickjacking & Touch Interception via Invisible Overlay:**
   - Line 64: `modifier = Modifier.fillMaxSize().alpha(0.01f)`.
   - The composable renders a full-screen WebView with an opacity of `0.01` (virtually invisible to human eyes).
   - Lines 121-130 inject a JavaScript loop that automatically clicks DOM elements matching `.cf-turnstile-wrapper, #challenge-stage, input[type="checkbox"], #challenge-form`.
   - If an untrusted site loads an ad redirect or click-to-subscribe banner covering the viewport, the automated script clicks it automatically without user awareness.

---

## 6. Network, TLS, Cleartext & Protocol Security

### 6.1 Cleartext Traffic Audit

- **Manifest Declaration:** `android:usesCleartextTraffic="true"` (`AndroidManifest.xml:41`).
- **Network Security Configuration:** **Completely absent.** There is no `res/xml/network_security_config.xml`.
- **Evaluation:**
  - Cleartext HTTP is allowed application-wide for any domain.
  - Video streams from third-party hosting providers often use plain `http://` URLs (e.g., legacy CDN CDNs or direct IP servers). Enabling `usesCleartextTraffic` is common in media apps, but doing so without a `network_security_config` that restricts cleartext strictly to media domains creates a broad vulnerability surface for API calls.

### 6.2 TLS & OkHttp Interceptor Security

1. **Certificate Pinning:**
   - None of the OkHttpClient instances (`RetrofitClient.kt`, `StreamDownloaderService.kt`, `DownloadManagerService.kt`, `PointsEarningRepository.kt`) configure certificate pinning (`CertificatePinner`).
   - Network requests rely strictly on the system CA trust store.
2. **Header Sanitization (PASS):**
   - In `StreamDownloaderService.kt:310-320`, custom headers passed to download tasks are strictly sanitized before logging:
     ```kotlin
     val sanitized = customHeaders.filterNot { (k, _) ->
         k.equals("authorization", ignoreCase = true) ||
         k.equals("cookie", ignoreCase = true) ||
         k.equals("set-cookie", ignoreCase = true) ||
         k.contains("token", ignoreCase = true) ||
         k.contains("secret", ignoreCase = true)
     }
     ```
   - This prevents authorization tokens, session cookies, and secrets from leaking into Android logcat.
3. **Referer & Origin Spoofing:**
   - `StreamDownloaderService.kt:322-340` dynamically manufactures `Origin` and `Referer` headers based on the download URL host to bypass scraper hotlink protections. While functionally necessary for scraper compatibility, it illustrates that client headers are synthetic.

---

## 7. P2P, Nearby & Socket Attack Surface

The peer-to-peer file sharing and discovery engine (`P2PManager.kt`, 1840 lines) contains the highest concentration of network attack surface in the application.

### 7.1 P2P Architecture Overview

```
                      ┌───────────────────────────────────────────────┐
                      │              P2PManager (Init)                │
                      │  Automatically starts background listeners:   │
                      │   - TCP ServerSocket (port 8888)              │
                      │   - UDP Broadcast Responder (port 8889)       │
                      └──────────────────────┬────────────────────────┘
                                             │
                       Local Wi-Fi Network / Hotspot
                                             │
             ┌───────────────────────────────┼───────────────────────────────┐
             ▼                               ▼                               ▼
     UDP Ping Broadcast              TCP Probe Handshake             TCP File Transfer
  "CINESTREAM_PING:<id>"         {"type": "probe", ...}        {"type": "file_transfer",
             │                               │                   "id": "<id>",
             ▼                               ▼                   "extension": "<ext>",
  "CINESTREAM_PONG:..."           {"type": "probe_ack"}           "fileSize": <bytes>}
                                                                             │
                                                                             ▼
                                                                  MediaStorageUtils
                                                                 getDestinationFile()
                                                                             │
                                                                             ▼
                                                                   [PATH TRAVERSAL BUG]
```

### 7.2 Detailed P2P Forensic Findings

#### 1. CRITICAL: Path Traversal & Arbitrary File Overwrite (`SEC-01`)
- **Code Reference:** `MediaStorageUtils.kt:98-103`:
  ```kotlin
  fun getDestinationFile(context: Context, id: String, extension: String? = null): File {
      val dir = getMediaDirectory(context)
      val cleanExt = extension?.trim()?.removePrefix(".")?.ifEmpty { null }
      val fileName = if (cleanExt != null) "${id}.${cleanExt}" else "${id}.mp4"
      return File(dir, fileName)
  }
  ```
- **Call Site in P2P Stream Receiver:** `P2PManager.kt:687-716`:
  ```kotlin
  val id = header.getString("id")
  val extension = header.optString("extension", "mp4").ifEmpty { "mp4" }
  ...
  val destFile = MediaStorageUtils.getDestinationFile(context, id, extension)
  val fileOut = BufferedOutputStream(FileOutputStream(destFile), bufferSize)
  ```
- **Vulnerability Mechanism:**
  - The sender controls the JSON header `id` and `extension`.
  - In standard Java `File(parent, child)`, if `child` contains directory traversal sequences like `../../`, Java resolves the path relative to `parent` without restricting the canonical path to `parent`.
  - If a sender sends:
    `"id": "../databases/app_database"` and `"extension": ""`
    `destFile` resolves to `/data/data/com.aistudio.cinestream.ivkgns/databases/app_database`!
  - `FileOutputStream(destFile)` immediately overwrites the app's SQLite database with arbitrary incoming byte data!
  - Alternatively, an attacker can overwrite `shared_prefs`, cache files, or other app-private files.
- **Root Cause:** Complete absence of canonical path validation:
  ```kotlin
  // MISSING CHECK:
  if (!destFile.canonicalPath.startsWith(dir.canonicalPath)) {
      throw SecurityException("Path traversal attempt detected")
  }
  ```

#### 2. Unencrypted, Unauthenticated Transport Protocol
- All TCP socket connections (`port 8888`) and UDP datagrams operate over raw, unencrypted sockets without TLS.
- Any device on the same local Wi-Fi network or mobile hotspot can passively capture video files, metadata payloads, user display names, and device models transmitted via P2P.
- Packet injection or man-in-the-middle tampering is trivially possible on untrusted Wi-Fi networks.

#### 3. Automatic Background Server Binding
- In `P2PManager.kt:234-237`:
  ```kotlin
  init {
      startBackgroundService(Build.MODEL)
  }
  ```
- Whenever the application initializes `AppContainer`, `P2PManager` binds a `ServerSocket` on port 8888 and a `DatagramSocket` on the broadcast port.
- This occurs even when the user is not on the `ShareScreen` and has not initiated file sharing.
- While the user is prompted to accept or decline incoming connections (`_pendingConnectionRequest.value`), the socket port remains permanently open and discoverable on any network the device joins.

#### 4. QR Code Payload Validation Gap
- In `ShareScreen.kt:1921-1927`:
  ```kotlin
  val uri = Uri.parse(scannedCode)
  val rawIp = uri.getQueryParameter("ip")
  val ipsParam = uri.getQueryParameter("ips")
  val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8888
  val name = uri.getQueryParameter("name") ?: "Nearby Device"
  val ssid = uri.getQueryParameter("ssid")
  val key = uri.getQueryParameter("key")
  ```
- The parser does not validate that `uri.scheme == "cinestream"` or `uri.host == "p2p"`.
- If an attacker generates a QR code with any URI scheme containing `ip` and `port` parameters, the scanner extracts them and initiates a connection attempt to the attacker's IP.

---

## 8. File, Storage, Cache & Content Provider Security

### 8.1 Internal Storage Partitioning

- All downloaded media items are stored in `context.filesDir/movies/`.
- Directory structure:
  ```
  /data/data/com.aistudio.cinestream.ivkgns/files/movies/
  ├── .nomedia                    <-- Protects from Android MediaScanner
  ├── {id}.mp4                    <-- Downloaded video files
  └── temp_{fileId}/              <-- Chunked / partial download fragments
  ```
- Cached movie and series metadata JSON files are stored in `context.filesDir/media_details_cache/`.
- In `MediaDetailsCacheManager.kt:23, 28`, file names are sanitized:
  ```kotlin
  val safeId = movieId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
  ```
  This sanitization protects `MediaDetailsCacheManager` from traversal, in direct contrast to `MediaStorageUtils.getDestinationFile`.

### 8.2 Content Provider & File Sharing Security

- The app registers **zero** `<provider>` tags in `AndroidManifest.xml`.
- Standard `androidx.core.content.FileProvider` is not configured.
- The app does not expose any content URIs (`content://`) to other applications on the device.
- All file sharing is strictly contained within the proprietary P2P socket engine.

---

## 9. Secrets, API Keys & Hardcoded Credentials

An exhaustive scan was conducted for hardcoded secrets, API keys, passwords, and tokens across source code and build files.

### 9.1 Secrets Inventory

| Secret / Credential | Location | Value / Nature | Risk Assessment |
|---|---|---|---|
| **StartApp App ID** | `MainActivity.kt:66` | `"208324071"` | **Hardcoded in Production Code.** Exposes ad network publisher account ID. |
| **Hotspot Fallback Passphrase** | `HotspotManager.kt:61` | `"cinestream123"` | **Hardcoded Fallback Passphrase.** Predictable Wi-Fi passphrase if softAp configuration returns null. |
| **TMDB API Key** | `.env.example:7` | `7fe9c75d9f8106f12b42bc50fa7f6671` | **Live Active Key in Repository.** Committed to version control; allows unauthorized API consumption. |
| **Google Web Client ID** | `.env.example:8` | `979447256418-rjb9a0991gvve0328n113dme67i4gpv2...` | **Committed OAuth Client ID.** Standard public client identifier (low risk, but should be managed via Secrets). |
| **Cloudinary Cloud Name** | `.env.example:10` | Set to Web Client ID string | Configuration defect; causes upload initialization to fail or misroute. |
| **Firebase Placeholder Config**| `app/google-services.json` | `"remixed-project-id"`, `"remixed-api-key"` | Unprovisioned template data. |

---

## 10. Data Privacy, Logging & Persistence Isolation

### 10.1 Logging Forensics

1. **HttpLoggingInterceptor Level:**
   - In `RetrofitClient.kt:25`:
     ```kotlin
     private val loggingInterceptor = HttpLoggingInterceptor().apply {
         level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
     }
     ```
   - **PASS:** In production release builds (`!BuildConfig.DEBUG`), HTTP network logging is completely disabled (`Level.NONE`).
2. **Standard Android Logcat Calls (`Log.d`, `Log.e`, `Log.w`):**
   - The application contains standard logging statements across repositories (`UserSecurityManager`, `NotificationRepository`, `FcmTokenManager`).
   - Sensitive fields (auth passwords, tokens, full cookies) are explicitly omitted from logs.
   - However, internal user IDs (`uid`), device installation IDs (`installationId`), and peer IP addresses are printed to logcat in debug messages.

### 10.2 Persistence Isolation & Multi-User Gaps

| Storage System | Content Stored | User Partitioned? | Cleared on Sign-Out? | Privacy Assessment |
|---|---|---|---|---|
| **Room `app_database`** | Support Messages (`support_messages`) | **NO** (Global table) | **NO** | **HIGH:** Cross-user chat leakage on shared devices. |
| **Room `app_database`** | Notifications (`notifications`) | **NO** (Global table) | **NO** | **MEDIUM:** Cross-user notification history leakage. |
| **DataStore Preferences** | Blocked Users, Friend Requests | **NO** (Global preferences) | **NO** | **MEDIUM:** Social preferences bleed across accounts. |
| **SharedPreferences** | P2P Transfer History, Remembered Devices | **NO** (Global preferences) | **NO** | **LOW:** Transfer history visible to subsequent users. |
| **Internal Storage** | Downloaded Movies / Episodes | **NO** (Shared directory) | **NO** | **ACCEPTABLE:** Media library is shared on device. |

---

## 11. App Update & Sideloading Security

The update pipeline was traced from Firestore configuration down to the user installation prompt.

```
Firestore: /config/app
  └── apkUrl: "http://example.com/update.apk"
  └── apkSha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
       │
       ▼
AppConfig.fromDocument() [Parses apkUrl and apkSha256]
       │
       ▼
AppUpdateManager.checkForUpdate()
       │
       ▼
AppUpdateDialog.kt (Lines 135-144)
       │
       ├── Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
       │
       ▼
Handed off to External System Browser
       │
       ├── [INTEGRITY CHECK BYPASSED] apkSha256 is NEVER validated
       └── [PROTOCOL UNENFORCED] http:// cleartext URLs permitted
```

### 11.1 Key App Update Findings

1. **SHA-256 Checksum Verification Bypassed:**
   - Although `AppConfig.kt:83` reads `apkSha256` from Firestore, `AppUpdateDialog.kt` delegates the download entirely to the system browser using `Intent.ACTION_VIEW`.
   - The app does not download the APK internally, does not compute the hash of the downloaded file, and does not compare it against `apkSha256`.
2. **Missing Scheme Verification:**
   - The download URL is parsed directly with `Uri.parse(updateInfo.downloadUrl)`.
   - The URL is not verified to use `https://`. A compromised Firestore document pointing to `http://` or a third-party scheme will be launched without warning.

---

## 12. Release Hardening, ProGuard / R8 & Build Configuration

The release build configuration was audited in `/app/build.gradle.kts` and `/app/proguard-rules.pro`.

### 12.1 Build Configuration Audit

```kotlin
// app/build.gradle.kts:44-52
buildTypes {
    release {
        isCrunchPngs = false
        isMinifyEnabled = false          // <--- VULNERABILITY: R8 Obfuscation DISABLED
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("debugConfig")  // <--- VULNERABILITY: Debug Keystore
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
}
```

### 12.2 Release Hardening Deficiencies

1. **Minification & Obfuscation Disabled (`isMinifyEnabled = false`):**
   - In release builds, R8 code shrinking and ProGuard name obfuscation are completely turned off.
   - **Impact:**
     - The released APK contains unobfuscated class names, method signatures, and internal logic.
     - Reverse engineering, scraper extraction, and security bypassing require zero decompilation effort.
     - Dead code is packaged into the final APK, unnecessarily increasing file size.
2. **Debug Keystore Signing in Release Build:**
   - The release build type explicitly sets `signingConfig = signingConfigs.getByName("debugConfig")`.
   - If built using standard Gradle commands, the release APK is signed with the well-known Android debug key (`androiddebugkey`, password `android`).
   - Debug-signed APKs cannot be published to Google Play and are vulnerable to unauthorized repacking.
3. **Dead / Legacy Rules in `proguard-rules.pro`:**
   - Line 7: `-keep class com.example.ui.screens.player.VideoExtractorBridge { *; }`
     - `VideoExtractorBridge` does not exist in the codebase.
   - Line 32: `-keep class com.example.extensions.** { *; }`
     - The package `com.example.extensions` does not exist in the codebase (extensions reside in `com.example.extension.managed`).

---

## 13. Comprehensive Vulnerability Register & Remediation Roadmap

### 13.1 Vulnerability Register

| ID | Title | Severity | Impact | Required Remediation |
|---|---|---|---|---|
| **SEC-01** | P2P Path Traversal / File Overwrite | **CRITICAL** | Overwrite app databases and shared preferences via unvalidated `id` and `extension`. | Sanitize file names with regex; verify `destFile.canonicalPath.startsWith(dir.canonicalPath)`. |
| **SEC-02** | Unsafe Background WebView Configuration | **HIGH** | Potential local file reading, mixed content execution, and invisible clickjacking. | Set `allowFileAccess = false`, `allowContentAccess = false`, `mixedContentMode = NEVER_ALLOW`. Remove `alpha = 0.01f` background overlay. |
| **SEC-03** | Global Cleartext Traffic Enabled | **HIGH** | MITM attacks against API calls and network payloads. | Implement `network_security_config.xml` to restrict cleartext exclusively to media streaming domains. |
| **SEC-04** | Plaintext TCP/UDP P2P Transport | **HIGH** | Eavesdropping and packet tampering on local Wi-Fi networks. | Wrap ServerSocket/Socket in TLS (SSLServerSocket) or encrypt payloads with ephemeral AES-GCM keys exchanged via QR. |
| **SEC-05** | FCM Token Orphaned on Logout | **HIGH** | Push notifications delivered to former account holders on shared devices. | Invoke `FcmTokenManager.dissociateTokenOnLogout(oldUid)` in `AuthRepository.signOut()`. |
| **SEC-06** | Missing In-App Account Deletion | **HIGH** | Non-compliance with Google Play Developer Policy. | Implement user account deletion in UI/AuthRepository; update Firestore rules to permit owner deletion. |
| **SEC-07** | R8 Minification Disabled & Debug Signing | **HIGH** | Trivial reverse engineering and insecure release packaging. | Set `isMinifyEnabled = true` in release; configure release signing with environment secrets. |
| **SEC-08** | App Update Integrity Check Bypassed | **MEDIUM** | Malicious APK replacement if update URL is intercepted or spoofed. | Download APK via secure foreground task and verify SHA-256 hash before launching package installer. |
| **SEC-09** | Hardcoded Secrets & Committed API Keys | **MEDIUM** | Unauthorized API usage and predictable hotspot passphrases. | Move StartApp ID to secrets; generate secure random hotspot passphrases; revoke committed TMDB key. |
| **SEC-10** | Silent Anonymous Account Creation | **MEDIUM** | Unintended authenticated sessions for guest users; potential auth state conflicts. | Restrict anonymous sign-in to explicitly initiated features; decouple extension sync from auth. |
| **SEC-11** | Unrestricted Application Backup | **MEDIUM** | Local database extraction via ADB backup. | Define explicit `<exclude>` rules in `backup_rules.xml` and `data_extraction_rules.xml` for databases and shared preferences. |
| **SEC-12** | Dead Storage Permissions in Manifest | **LOW** | Policy flags and permission bloat. | Remove `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, and `requestLegacyExternalStorage`. |

---

## 14. Conclusion & Certification

This forensic audit confirms that while CineStream Pro features strong defense mechanisms in specific modules (such as sanitized diagnostic logging, contained interactive Turnstile handling, and owner-validated Firestore subcollections), several critical vulnerabilities exist in P2P transport, background WebView settings, logout data hygiene, and release configuration.

In accordance with the **STRICT READ-ONLY / ZERO PRODUCTION MODIFICATIONS** rule, zero lines of source code or configuration were modified during this phase. All findings, exact file references, and line numbers are certified accurate against the project repository.
