# PHASE 06.7 — COMPLETE ECONOMY / SUBSCRIPTION / ADS / REWARD / LEADERBOARD / FEATURE CONTROL / APP UPDATE / SETTINGS FORENSIC AUDIT REPORT

**Project:** CineStream Users App (`CineStream Pro`)  
**Target:** `CineStream-Pro00-main.zip`  
**Mode:** STRICT READ-ONLY / ZERO PRODUCTION MODIFICATIONS  
**Audit Type:** Forensic Business-Logic, Data, UI, Lifecycle, Security, and Performance Audit  
**Date:** October 6, 2026  
**Artifact:** `/PHASES/PHASE_06.7_ECONOMY_SUBSCRIPTION_ADS_REWARD_LEADERBOARD_CONFIG_SETTINGS_FORENSIC_AUDIT_REPORT.md`  

---

## 0. Executive Summary & Verification of Invariants

This forensic audit evaluates every economic, subscription, advertising, reward, leaderboard, remote configuration, feature flag, app update, and application settings component in the CineStream Pro users application.

### Key High-Level Findings:
1. **Subscription-Quality Decoupling (PASS — Canonical Invariant Preserved):**
   - Active `FREE`, `PRO_LITE`, and `PRO` tiers all possess full access to all provider-available streaming and download resolutions (`360p`, `480p`, `720p`, `1080p`, `4K`).
   - Subscription governs **strictly and exclusively** ad removal (`UserSecurityManager.isAdFree()`).
   - Technical quality restrictions (`allowedQuality`) are managed independently in `/users/{uid}` and default to unrestricted (`true`).
2. **Rewarded Ads Direct Points Exploitation (HIGH — Missing Ad SDK Trigger):**
   - In `PointsEarningViewModel.watchRewardedAd()` (`PointsEarningViewModel.kt:175-195`), invoking "Watch Ad" does not invoke StartApp or any ad network SDK. It immediately calls `PointsEarningRepository.claimRewardedAd()`, awarding 15 points without actually rendering an advertisement.
3. **Client-Side Device Clock Rollback Vulnerability (HIGH — Offline Entitlement):**
   - `SubscriptionNormalizer.normalizeSubscriptionStatus()` and `UserRestrictions.isSubscriptionExpired` compare `expiresAt <= System.currentTimeMillis()`. Rolling the Android device clock backward in system settings artificially restores expired subscriptions to active status while offline or during cached sessions.
4. **Temporary Client-Authoritative Economy Mode (MEDIUM — Architectural Disclosure):**
   - `PointsEarningRepository` operates in `TEMPORARY_ECONOMY_MODE` (`isTemporaryEconomyModeEnabled = true`, `trustedBackendUrl = null`). Transactions are executed directly by the client against Firestore via `TemporaryFirebaseEconomyRepository`. There is no server-side verification (SSV) for ad tokens or task proofs.
5. **Canonical Pricing Enforced (PASS — Legacy Prices Eliminated):**
   - Canonical redemption costs (`50`, `250`, `350`, `1000`) are strictly enforced in `EconomyConfig.kt:48-53` and `TemporaryFirebaseEconomyRepository.kt:530-536`.
   - Historical prices (`320`, `800`) are completely absent from the codebase.
6. **App Update Checksum Verification Bypassed (MEDIUM — External Intent Hand-off):**
   - While `AppConfig` parses `apkSha256`, `AppUpdateDialog.kt:135-144` delegates the update download to the external system browser via `Intent.ACTION_VIEW`. Consequently, the SHA-256 checksum is never validated on-device prior to installation, and URLs are not constrained to HTTPS.
7. **Local Settings Leakage Across Logout (MEDIUM — DataStore Isolation Gap):**
   - Blocked users (`blocked_users`), friend requests (`friend_requests`), P2P transfer history, and support chat records in Room are stored on-device without user partitioning and survive logout.

---

## 1. Canonical Business Contract Verification

### 1.1 Invariant Rules Evaluation
| Contract Rule | Expected Behavior | Actual Source Implementation | Status |
|---|---|---|---|
| **Subscription Purpose** | Ad removal ONLY | `UserSecurityManager.isAdFree()` drives interstitial suppression in `AdManager.kt:42, 54`. Quality is never checked. | **PASS** |
| **FREE Tier** | Ads ON; All qualities | `isAdFree = false`; `UserRestrictions.isQualityAllowed()` returns `true` (unrestricted). | **PASS** |
| **PRO_LITE Tier** | Ads OFF; All qualities | `isAdFree = true`; `UserRestrictions.isQualityAllowed()` returns `true` (unrestricted). | **PASS** |
| **PRO Tier** | Ads OFF; All qualities | `isAdFree = true`; `UserRestrictions.isQualityAllowed()` returns `true` (unrestricted). | **PASS** |
| **4K Quality** | Driven by stream source only | `ServerStateStore.kt` and `PlayerViewModel.kt` parse provider streams directly; subscription tier never restricts or manufactures 4K. | **PASS** |
| **Download Limits** | Decoupled from subscription | `UserRestrictions.downloadLimit` is an independent technical account field, never set or modified by subscription tiers. | **PASS** |
| **Scraper Access** | Decoupled from subscription | `ManagedMediaOrchestrator.kt` discovers and queries scrapers regardless of user tier. | **PASS** |

---

## 2. Subscription Field Inventory & Legacy Mirror Audit

### 2.1 Complete Field Matrix
| Field Name | Canonical / Legacy | Reader | Writer | Storage Path | Expiration Logic | Logout Cleanup | Rules Protection |
|---|---|---|---|---|---|---|---|
| `subscriptionTier` | Canonical | `SubscriptionNormalizer`, `UserSecurityManager` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | Expiration resets status to `EXPIRED`, tier remains for history | Cleared in memory (`reset()`) | `allow update: if isOwner(uid)` (in temp mode) |
| `planId` | Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | Retains SKU string (e.g. `pro_lite_7d`) | Cleared in memory | Protected |
| `durationDays` | Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | Integer duration in days | Cleared in memory | Protected |
| `subscriptionStatus` | Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | `ACTIVE`, `EXPIRED`, `CANCELED`, `PENDING` | Resets to `ACTIVE` (FREE) | Protected |
| `subscriptionSource` | Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | `POINTS`, `MONEY`, `ADMIN_GRANT`, `LEGACY` | Resets to `LEGACY` | Protected |
| `subscriptionReferenceId`| Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | UUID or payment transaction reference | Cleared | Protected |
| `subscriptionStartedAt` | Canonical | `SubscriptionNormalizer` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | Unix timestamp (ms) | Cleared | Protected |
| `subscriptionExpiresAt` | Canonical | `SubscriptionNormalizer`, `UserRestrictions` | `TemporaryFirebaseEconomyRepository` | `/users/{uid}` | Compared against current time | Cleared | Protected |
| `isPremium` | Legacy Mirror | `UserRestrictions` fallback | `TemporaryFirebaseEconomyRepository:611` | `/users/{uid}` | Mirror boolean (`true` if active Pro/Pro Lite) | Resets to `false` | Protected |
| `isPro` | Legacy Mirror | Legacy UI widgets | `TemporaryFirebaseEconomyRepository:612` | `/users/{uid}` | Mirror boolean | Resets to `false` | Protected |
| `plan` | Legacy Mirror | Legacy UI widgets | `TemporaryFirebaseEconomyRepository:613` | `/users/{uid}` | Lowercase tier string (`"pro"`, `"pro_lite"`) | Resets to `""` | Protected |
| `proPlan` | Legacy Mirror | Legacy UI widgets | `TemporaryFirebaseEconomyRepository:614` | `/users/{uid}` | SKU mirror | Resets to `""` | Protected |
| `proExpiresAt` | Legacy Mirror | Fallback timestamp reader | `TemporaryFirebaseEconomyRepository:615` | `/users/{uid}` | Mirror timestamp | Cleared | Protected |

### 2.2 Precedence Order
In `SubscriptionNormalizer.kt:69-100`:
1. `rawTier` (`subscriptionTier`) takes absolute precedence if present.
2. `rawPlan` (`plan` / `proPlan`) is inspected only if `subscriptionTier` is null.
3. `rawIsPremium` is inspected only if both `subscriptionTier` and `plan` are null.
4. Default is `FREE`.

---

## 3. Subscription State Machine & Lifecycle Transitions

### 3.1 Allowed State Machine Transitions
```
                ┌──────────────────────────────────────────────┐
                │                                              │
                ▼                                              │
          ┌───────────┐      Points Redemption (1d, 7d, 10d)   │
          │   FREE    │ ──────────────────────────────────────►│
          └─────┬─────┘                                        │
                │                                              ▼
                │ Points Redemption (30d)               ┌──────────────┐
                ├──────────────────────────────────────►│   PRO_LITE   │
                │                                       └──────┬───────┘
                │                                              │
                │                                              │ Upgrade (30d)
                ▼                                              ▼
          ┌───────────┐◄───────────────────────────────────────┘
          │    PRO    │
          └─────┬─────┘
                │
                │ Same-Tier Extension (stack duration onto expiresAt)
                ├──────────────────────────────────────┐
                ▼                                      │
          [PRO Extended] ◄─────────────────────────────┘
```

### 3.2 Transition Verification Details
- **FREE → PRO_LITE:** Allowed via redemption of `pro_lite_1d` (50 pts), `pro_lite_7d` (250 pts), or `pro_lite_10d` (350 pts). Sets `subscriptionStartedAt = now`, `subscriptionExpiresAt = now + (days * 86400000L)`.
- **FREE → PRO:** Allowed via redemption of `pro_30d` (1000 pts). Sets `subscriptionStartedAt = now`, `subscriptionExpiresAt = now + 30d`.
- **PRO_LITE → PRO (Upgrade):** Allowed. Overwrites tier to `PRO`, resets `subscriptionStartedAt = now`, sets `expiresAt = now + 30d`.
- **PRO → PRO_LITE (Downgrade):** **STRICTLY FORBIDDEN.**
  In `TemporaryFirebaseEconomyRepository.kt:575-578`:
  ```kotlin
  if (currentTier == CanonicalSubscriptionTier.PRO && isCurrentlyActive && targetTier == CanonicalSubscriptionTier.PRO_LITE) {
      throw IllegalStateException("SUBSCRIPTION_STATE_INVALID")
  }
  ```
  Active PRO users cannot downgrade to PRO_LITE.
- **Same-Tier Stacking:** Allowed. In `TemporaryFirebaseEconomyRepository.kt:580-587`, if current tier matches target tier and is active, new expiration is `currentExpiresAt + (durationDays * 86400000L)`.

---

## 4. Subscription Expiration Forensics

### 4.1 Expiration Pipeline Trace
```
/users/{uid}.subscriptionExpiresAt (Firestore)
   │
   ▼
SubscriptionNormalizer.fromDocument()
   │
   ▼
SubscriptionNormalizer.normalizeSubscriptionStatus()
   │ (compares expiresAt <= currentTimeMillis)
   ▼
UserSubscriptionState (isExpired, isActive, isAdFree)
   │
   ▼
UserSecurityManager.restrictionsFlow (StateFlow)
   │
   ▼
AdManager (isAdFree() -> boolean)
   │
   ▼
Ad Display Decision (Interstitial / Banner / Forced Ads)
```

### 4.2 Clock & Offline Vulnerability Analysis
- **Local Clock Vulnerability:** Expiration comparison relies on `System.currentTimeMillis()` (`SubscriptionModels.kt:106, 171`). If a user rolls their device clock backward while offline or between sync intervals, `expiresAt <= currentTimeMillis` evaluates to `false`, artificially keeping `isAdFree = true`.
- **Session Expiration:** If an active subscription expires while the user is actively streaming or browsing, `UserSecurityManager` listens via Firestore snapshot listener on `/users/{uid}`. When the server or background worker updates the document, the client updates in real time. However, if the document remains unmodified in Firestore, client-side expiration occurs upon the next recomposition or `normalizeSubscriptionStatus` evaluation.

---

## 5. Ad System Forensics & Interstitial Gating

### 5.1 AdManager Architecture (`AdManager.kt`)
- **SDK:** StartApp SDK (`StartAppAd`).
- **Preloading (`preload`):**
  - Gated by: `if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) return`.
  - Suppressed immediately for active `PRO` and `PRO_LITE` users.
- **Interstitial Display (`showInterstitial`):**
  - Gated by: `if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) { onDismissed?.invoke(); return }`.
  - Non-blocking callback: if ad suppression is active, `onDismissed` is invoked synchronously without stalling player or navigation handoff.
  - Fail-safe fallback: if ad fails to load or `adNotDisplayed` fires, `onDismissed` is called immediately (`AdManager.kt:80, 95`).

---

## 6. Rewarded Ad Points Forensics

### 6.1 Critical Vulnerability in Rewarded Ad Earning
In `PointsEarningViewModel.kt:148-198`:
```kotlin
fun watchRewardedAd(context: Context) {
    ...
    _isWatchingRewardedAd.value = true
    viewModelScope.launch {
        try {
            val req = RewardedAdClaimRequest(userId = uid)
            val result = PointsEarningRepository.claimRewardedAd(req)
            ...
```
**Forensic Finding (CRITICAL GAP):**
`watchRewardedAd()` **never invokes any ad SDK** (`StartAppAd` or Google AdMob rewarded ad). It does not load an ad, does not present an ad activity, and does not listen for a reward callback. It directly invokes `PointsEarningRepository.claimRewardedAd(req)`, crediting the user with 15 points immediately upon tapping the button.
While daily cap (5 ads) and cooldown (300 seconds) are enforced by Firestore transactions in `TemporaryFirebaseEconomyRepository.kt:406-415`, the actual ad is entirely skipped.

---

## 7. Daily Login Reward & Streak Forensics

### 7.1 Ladder & Boundaries
- **Ladder:** `listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)` (`EconomyConfig.kt:54`).
- **Day Boundary:** Strictly calculated in **UTC**:
  ```kotlin
  SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
  ```
- **Streak Logic:**
  - If `lastClaimDate == yesterdayUtc`: `newStreak = currentStreak + 1`.
  - If `lastClaimDate != yesterdayUtc`: streak resets to `1`.
  - Reward calculation: `ladder[(newStreak - 1) % ladder.size]`.
- **Double-Claim Prevention:**
  - Evaluated inside Firestore transaction: `if (lastClaimDate == todayUtc) throw IllegalStateException("ALREADY_CLAIMED")`.
  - Impossible to claim twice in the same UTC day.

---

## 8. Points Wallet & Ledger Invariants

### 8.1 Wallet & Subcollection Structure
- **Wallet Balances:** Stored directly on user document `/users/{uid}`:
  - `pointsBalance: Long` (current spendable balance)
  - `totalPointsEarned: Long` (cumulative earned points)
  - `totalPointsSpent: Long` (cumulative spent points)
- **Ledger Records:** Stored in subcollection `/users/{uid}/point_transactions/{txId}`:
  - Fields: `id`, `txId`, `userId`, `type`, `amount`, `balanceBefore`, `balanceAfter`, `referenceId`, `description`, `createdAt`.
- **Balance Invariants:**
  - `amount > 0` for earn events (`DAILY_LOGIN`, `REWARDED_AD`, `TASK_REWARD`, `LEADERBOARD_REWARD`).
  - `amount < 0` for redemption events (`SUBSCRIPTION_REDEMPTION`).
  - In `redeemSubscription`: `if (balanceBefore < cost) throw IllegalStateException("INSUFFICIENT_POINTS")`. Points cannot become negative.

---

## 9. Reward Tasks Architecture & Claim Forensics

### 9.1 Tasks Pipeline Trace
- Tasks Catalog: `/reward_tasks/{taskId}`
  - Fields: `title`, `description`, `rewardPoints`, `isActive`, `expiresAt`.
- User Claim History: `/users/{uid}/task_claims/{taskId}`
  - Fields: `taskId`, `userId`, `rewardPoints`, `claimedAt`.
- **Claim Atomicity:**
  In `TemporaryFirebaseEconomyRepository.kt:233-325`:
  Inside a single Firestore transaction:
  1. Checks task document exists and `isActive == true`.
  2. Checks `expiresAt == null || expiresAt >= now`.
  3. Checks `task_claims/{taskId}` does NOT exist (`if (claimDoc.exists()) throw TASK_ALREADY_CLAIMED`).
  4. Increments `pointsBalance` and `totalPointsEarned`.
  5. Writes `task_claims/{taskId}` document.
  6. Writes `/users/{uid}/point_transactions/{txId}` ledger document.
- **Idempotency Verdict:** Duplicate claims are impossible.

---

## 10. Subscription Redemption Engine & Pricing Audit

### 10.1 Canonical Pricing Verification
In `EconomyConfig.kt:48-53` and `TemporaryFirebaseEconomyRepository.kt:530-536`:
| SKU | Canonical Tier | Duration | Points Cost | Code Verification |
|---|---|---|---|---|
| `pro_lite_1d` | `PRO_LITE` | 1 Day | **50** points | `EconomyConfig.kt:49`, `TemporaryFirebaseEconomyRepository.kt:532` |
| `pro_lite_7d` | `PRO_LITE` | 7 Days | **250** points | `EconomyConfig.kt:50`, `TemporaryFirebaseEconomyRepository.kt:533` |
| `pro_lite_10d` | `PRO_LITE` | 10 Days | **350** points | `EconomyConfig.kt:51`, `TemporaryFirebaseEconomyRepository.kt:534` |
| `pro_30d` | `PRO` | 30 Days | **1000** points | `EconomyConfig.kt:52`, `TemporaryFirebaseEconomyRepository.kt:535` |

**Historical Cost Elimination:**
Historical costs (`320` and `800`) were searched across all source code files; **zero occurrences exist**.

---

## 11. Leaderboard Subsystem & Weekly Rankings Forensics

### 11.1 Leaderboard Status Classification
- **CURRENTLY IMPLEMENTED IN CLIENT:**
  - Real-time observation of `/leaderboard/weekly_current` via `PointsRepository.observeWeeklyLeaderboard()` (`PointsRepository.kt:126-159`).
  - Parsing of `rankings` list: `rank`, `username`, `displayName`, `photoUrl`, `weeklyEarnedPoints`, `reward`, `tier`.
  - UI display in `SubscriptionScreen.kt:1749-2040` (Leaderboard tab) with search and filters (All, Active, Pro).
- **DEFERRED TO BACKEND / SERVER:**
  - Aggregation of weekly earned points across users.
  - Cycle rollover on Monday 00:00 UTC.
  - Archiving to `/leaderboard_history/{cycleId}`.
  - Automated payout of rewards (Top 1: 500 pts + 7d PRO_LITE; Top 2: 300 pts + 1d PRO_LITE; Top 3: 150 pts).
- **Security Invariant:** The client application contains **zero write operations** to `/leaderboard` or `/leaderboard_history`. Firestore security rules restrict write access to `isAdmin()` only (`firestore.rules`).

---

## 12. Feature Control & Remote Config Architecture

### 12.1 Features Parsing & Canonical Messages
In `EconomyConfigRepository.kt:106-121`:
- Document: `/config/features`
- Fields: `subscriptions`, `points`, `dailyLogin`, `rewardedAds`, `tasks`, `leaderboard`, `disabledMessage`.
- States: `ACTIVE`, `COMING_SOON`, `DISABLED`.
- Canonical Coming Soon Message:
  `FeaturesConfig.COMING_SOON_MESSAGE = "هذه الميزة ستضاف قريبًا"` (`EconomyModels.kt:31`).
- Fallback parsing:
  `parseFeatureState(raw, fallback = FeatureState.COMING_SOON)`.
  If an unparseable state string is encountered, it safely resolves to `COMING_SOON`.

---

## 13. Economy Remote Config Forensics

### 13.1 Dynamic Config Fallbacks
In `EconomyConfigRepository.kt:123-152`:
- Document: `/config/economy`
- If document is missing or malformed:
  - `redemptionCosts` falls back to default map (`50, 250, 350, 1000`).
  - `dailyLoginRewards` falls back to default ladder (`10, 15, 20, 25, 30, 40, 50`).
  - `rewardedAdPoints` defaults to `15L`.
  - `rewardedAdDailyCap` defaults to `5`.
  - `rewardedAdCooldownSeconds` defaults to `300L`.
- Values are bounded and protected by null-coalescing defaults.

---

## 14. Legacy Config & Path Hygiene Audit

### 14.1 Forensic Check for `/config/global`
- Searched codebase for `/config/global` or `document("global")`.
- **Result:** **ZERO occurrences**.
- In `firestore.rules`:
  ```firestore
  match /config/{document=**} {
    allow read, write: if false;
  }
  ```
  Any attempt to read `/config/global` is rejected with `PermissionDenied`.

---

## 15. Ads vs Subscription Invariant Verification

### 15.1 Component Audit Matrix
| Subsystem / Component | File | Inspects Subscription? | Inspects Ad-Free? | Modifies Quality / Scrapers? | Compliance Status |
|---|---|---|---|---|---|
| **ExoPlayer Preparation** | `PlayerViewModel.kt:365-420` | No | No | No (uses stream URL) | **PASS** |
| **Inline Video Player** | `InlineDetailVideoPlayer.kt:500-600` | No | No | No (uses stream URL) | **PASS** |
| **Download Quality Dialog**| `DownloadQualitySheet.kt` | No | No | No (lists stream qualities) | **PASS** |
| **Downloader Service** | `StreamDownloaderService.kt` | No | No | No (downloads direct stream) | **PASS** |
| **Scraper Discovery** | `ManagedMediaOrchestrator.kt` | No | No | No (queries providers directly) | **PASS** |
| **AdManager Interstitial** | `AdManager.kt:54` | Yes (via `isAdFree()`) | Yes | Suppresses ads only | **PASS** |
| **AdManager Preload** | `AdManager.kt:42` | Yes (via `isAdFree()`) | Yes | Suppresses preloading only | **PASS** |

---

## 16. App Update Engine & Security Forensics

### 16.1 Version Parsing & Priority Resolution
In `AppUpdateManager.kt:133-246`:
- **Priority 1:** Canonical `/app_updates` collection:
  - Iterates documents, filters `isActive == true`, extracts `versionCode`.
  - Enforces `targetCode > currentCode` to prevent downgrades.
  - Respects `minVersionCode` for mandatory updates.
- **Priority 2 (Fallback):** `/config/app` document (`AppConfig.kt`):
  - Used if `/app_updates` collection is empty or unreachable.

---

## 17. Update Download & Security

### 17.1 Security Deficiencies in App Update
In `AppUpdateDialog.kt:134-144`:
```kotlin
Button(
    onClick = {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
)
```
**Forensic Findings:**
1. **No HTTPS Enforcement:** `updateInfo.downloadUrl` is parsed without validating scheme (`https://`).
2. **SHA-256 Bypass:** Although `AppConfig.apkSha256` exists in the data model, no verification is performed because the app delegates download and execution entirely to the external Android browser / package installer.

---

## 18. Settings Architecture & Account Isolation Forensics

### 18.1 Settings Inventory & Persistence Matrix
| Setting | UI Control | Persistence Mechanism | Account Scoped? | Cleared on Sign-Out? | Impact / Effect |
|---|---|---|---|---|---|
| **Theme Mode** | System / Light / Dark | DataStore (`theme_mode`) | No (Device-wide) | No | App UI color scheme |
| **Primary Color** | Red / Blue / Green / Purple / Yellow | DataStore (`primary_color`) | No (Device-wide) | No | Accent branding color |
| **App Language** | System / English / Arabic | DataStore (`app_language`) | No (Device-wide) | No | Locale configuration |
| **Start Screen** | Home / Search / Downloads / etc. | DataStore (`start_screen`) | No (Device-wide) | No | Initial navigation tab |
| **Max Concurrent Downloads** | 1 to 5 | DataStore (`max_concurrent_downloads`) | No (Device-wide) | No | Download queue concurrency |
| **Max Segments** | 1 to 16 | DataStore (`max_segments`) | No (Device-wide) | No | Multi-threaded segment count |
| **Download Network** | All / Wi-Fi only | DataStore (`download_network`) | No (Device-wide) | No | Metered network guard |
| **Seek Duration** | 5s / 10s / 15s | DataStore (`playback_seek_duration`) | No (Device-wide) | No | Player forward/rewind step |
| **Controls Timeout** | 5s / 10s / 15s / 30s | DataStore (`playback_controls_timeout`) | No (Device-wide) | No | Player overlay dismiss timer |
| **Blocked Users** | Blocked Users List | DataStore (`blocked_users`) | No (Device-wide) | **No (LEAK)** | Local user blocks |
| **Friend Requests** | Public Profile Button | DataStore (`friend_requests`) | No (Device-wide) | **No (LEAK)** | Local friend request stubs |
| **P2P Transfer History** | Recent Transfers | SharedPrefs (`transfers_json`) | No (Device-wide) | **No (LEAK)** | P2P transfer history |
| **Support Chat** | Help & Support Screen | Room (`support_messages`) | No (Device-wide) | **No (LEAK)** | Prior user support messages |

---

## 19. Points / Subscription Account Isolation

### 19.1 Account Switch Simulation (User A → User B)
- **Points Balance:** **ISOLATED.** Keyed to `/users/{uid}`. On logout, `UserSecurityManager.reset()` clears memory. When User B logs in, User B's document is observed.
- **Subscription Tier:** **ISOLATED.** Keyed to `/users/{uid}`. Reset to `FREE` in memory on sign-out.
- **Daily Login Streak:** **ISOLATED.** Keyed to `/users/{uid}/dailyStreak` and `lastDailyLoginDate`.
- **Ad Watch Cooldown:** **ISOLATED.** Keyed to `/users/{uid}/lastRewardedAdWatchedAt`.
- **Task Claims:** **ISOLATED.** Subcollection `/users/{uid}/task_claims` prevents cross-user inheritance.
- **Local Settings / Support Chat:** **NOT ISOLATED.** Device DataStore settings and Room `support_messages` persist across account changes.

---

## 20. Business Logic Threading & Concurrency Forensics

### 20.1 Threading Execution Evaluation
- **Firestore Transactions (`runTransaction`):** Executed asynchronously via `await()` on `Dispatchers.IO` in repositories.
- **Wallet Observation:** Flows collected on `viewModelScope` via `stateIn(SharingStarted.WhileSubscribed(5000))`.
- **Thread Blocking (`runBlocking`):** No `runBlocking` found in economy or subscription transactions (unlike NotificationRepository and FCM).

---

## 21. Business Logic Error Handling & Edge Cases

### 21.1 Edge Case Evaluation
- **Insufficient Points:** Throws `INSUFFICIENT_POINTS`, mapped cleanly to Arabic UI message `"رصيد النقاط غير كافٍ للاشتراك في هذه الباقة"`.
- **Active Pro Downgrade:** Throws `SUBSCRIPTION_STATE_INVALID`, mapped to `"لا يمكن تخفيض اشتراك PRO النشط إلى PRO LITE"`.
- **Daily Cap Reached:** Throws `DAILY_CAP_REACHED`, mapped to `"تم الوصول إلى الحد الأقصى لمشاهدة الإعلانات اليوم"`.
- **Missing Economy Document:** Falls back to canonical in-memory defaults.

---

## 22. UI Correctness & Transparency Audit

### 22.1 SubscriptionScreen & PointsScreen Findings
- **Quality Transparency:** UI never promises or claims "4K streaming unlocked with Pro".
- **Points Balance Display:** Real-time authoritative display bound to `viewModel.wallet.pointsBalance`.
- **Misleading Element:** In `SubscriptionScreen.kt`, the "Watch Ad for Points" button states `+15 Points`, but clicking it executes the claim without showing an ad.

---

## 23. Economy Security & Zero-Trust Verification

### 23.1 Security Reality Assessment
- **Temporary Firebase Economy Mode:**
  The app currently operates in non-trusted client-driven mode.
  Firestore security rules permit authenticated users to update their own document at `/users/{uid}`. A modified client or script could craft arbitrary Firestore updates to inflate `pointsBalance` or set `subscriptionTier = "PRO"`.
- **Production Requirement:**
  True zero-trust economy requires deploying Cloud Functions or Cloudflare Workers with service accounts to mutate balances and verify ad SSV callbacks.

---

## 24. Required Subscription Matrix

| Tier | Ads | Quality | Download | Scraper | Server | Expiration | Evidence | Status |
|---|---|---|---|---|---|---|---|---|
| **FREE** | ON | Source-available | Source-available | All | All | None | `UserRestrictions.kt:80, 105` | **PASS** |
| **PRO_LITE** | OFF | Source-available | Source-available | All | All | 1d / 7d / 10d | `UserRestrictions.kt:80`, `AdManager.kt:54` | **PASS** |
| **PRO** | OFF | Source-available | Source-available | All | All | 30d | `UserRestrictions.kt:80`, `AdManager.kt:54` | **PASS** |

---

## 25. Required Economy Matrix

| Operation | Points | Transaction | Ledger | Idempotency | Thread | Failure | Account Isolation | Risk |
|---|---|---|---|---|---|---|---|---|
| **Daily Login** | 10 to 50 | Firestore `runTransaction` | `point_transactions` | Yes (`lastDailyLoginDate`) | `Dispatchers.IO` | Atomic rollback | Isolated (`/users/{uid}`) | LOW |
| **Rewarded Ad** | 15 | Firestore `runTransaction` | `point_transactions` | Cooldown + Cap | `Dispatchers.IO` | Atomic rollback | Isolated (`/users/{uid}`) | **HIGH (No Ad shown)** |
| **Task Claim** | 10 to 500 | Firestore `runTransaction` | `point_transactions` | Yes (`task_claims/{id}`) | `Dispatchers.IO` | Atomic rollback | Isolated (`/users/{uid}`) | LOW |
| **Redemption** | -50 to -1000 | Firestore `runTransaction` | `point_transactions` | Yes (Balance check) | `Dispatchers.IO` | Atomic rollback | Isolated (`/users/{uid}`) | LOW |

---

## 26. Required Feature Matrix

| Feature | Config Document | Default State | ACTIVE | COMING_SOON | DISABLED | UI Behavior | Offline Behavior | Risk |
|---|---|---|---|---|---|---|---|---|
| **Subscriptions**| `/config/features` | `ACTIVE` | Shows Plans | Shows Toast | Shows Toast | Tab gated | Uses cached/default | LOW |
| **Points** | `/config/features` | `ACTIVE` | Shows Wallet | Shows Toast | Shows Toast | Tab gated | Uses cached/default | LOW |
| **Daily Login** | `/config/features` | `ACTIVE` | Claimable | Disabled Button| Hidden/Disabled | Card gated | Uses cached/default | LOW |
| **Rewarded Ads** | `/config/features` | `ACTIVE` | Claimable | Disabled Button| Hidden/Disabled | Card gated | Uses cached/default | LOW |
| **Tasks** | `/config/features` | `ACTIVE` | Interactive | Coming Soon Msg| Hidden/Disabled | Tab gated | Uses cached/default | LOW |
| **Leaderboard** | `/config/features` | `ACTIVE` | Rendered | Coming Soon Msg| Hidden/Disabled | Tab gated | Uses cached/default | LOW |

---

## 27. Required Settings Matrix

| Setting | Source | Persistence | Account Scoped | Logout Cleanup | Effect | UI Mapping | Risk |
|---|---|---|---|---|---|---|---|
| **Theme** | UI Toggle | DataStore | No | No | App theme | SettingsScreen | NONE |
| **Color** | UI Grid | DataStore | No | No | App color | SettingsScreen | NONE |
| **Language** | BottomSheet | DataStore | No | No | Localization | SettingsScreen | NONE |
| **Start Screen** | BottomSheet | DataStore | No | No | Navigation | SettingsScreen | NONE |
| **Downloads** | Dialog | DataStore | No | No | Concurrency | SettingsScreen | NONE |
| **Playback** | Dialog | DataStore | No | No | Seek / timeout | SettingsScreen | NONE |
| **Blocks** | Profile UI | DataStore | No | **No (LEAK)** | User block | BlockedUsers | **MEDIUM** |
| **Support Chat** | Help Screen | Room | No | **No (LEAK)** | Chat history | HelpSupport | **MEDIUM** |

---

## 28. Explicit Answers to the 30 Critical Questions

1. **Can FREE users access every source-provided quality?**  
   **YES.** `UserRestrictions.isQualityAllowed()` returns `true` when technical limit `allowedQuality` is null.
2. **Can PRO_LITE access every source-provided quality?**  
   **YES.** Decoupled from subscription tier.
3. **Can PRO access every source-provided quality?**  
   **YES.** Decoupled from subscription tier.
4. **Can subscription ever manufacture 4K?**  
   **NO.** Quality strictly depends on scraper-extracted source variants.
5. **Can subscription restrict download quality?**  
   **NO.** Downloads mirror source availability without tier checks.
6. **Can subscription restrict scraper availability?**  
   **NO.** Scrapers query openly for all users.
7. **Can expired PRO still suppress ads?**  
   **NO.** Expiration invalidates `isAdFree`, causing `AdManager` to show interstitials.
8. **Can device clock rollback extend subscription?**  
   **YES.** `SubscriptionNormalizer` checks `expiresAt <= System.currentTimeMillis()`. Rolling the clock back bypasses expiration while offline or un-synced.
9. **Can rewarded ad callbacks award points twice?**  
   **NO.** Cooldown and daily cap in Firestore transaction prevent double credit.
10. **Can daily login reward be claimed twice?**  
    **NO.** Enforced atomically by `lastDailyLoginDate == todayUtc`.
11. **Can task claim be duplicated?**  
    **NO.** Enforced atomically by checking `task_claims/{taskId}` existence.
12. **Can subscription redemption be duplicated?**  
    **NO.** Balance is atomically deducted in transaction.
13. **Can points become negative?**  
    **NO.** Transaction verifies `balanceBefore >= cost` prior to deduction.
14. **Can points exceed 1,000,000?**  
    **YES.** Stored as `Long` with no programmatic ceiling.
15. **Can logout leak points?**  
    **NO.** Memory state is cleared via `UserSecurityManager.reset()`.
16. **Can logout leak subscription?**  
    **NO.** Memory tier resets to `FREE` via `UserSecurityManager.reset()`.
17. **Can old 320/800 redemption prices remain active?**  
    **NO.** Canonical prices `50, 250, 350, 1000` are strictly enforced; 320 and 800 do not exist.
18. **Can malformed economy config break the wallet?**  
    **NO.** Safe fallbacks in `EconomyConfigRepository` prevent crashes.
19. **Can leaderboard rewards be distributed twice?**  
    **NO (Client side).** Client does not distribute rewards; logic is deferred to server.
20. **Can leaderboard week boundary use local time instead of UTC?**  
    **NO.** Client reads static `/leaderboard/weekly_current`.
21. **Can feature flags fail open?**  
    **NO.** Default fallback for unparseable strings is `COMING_SOON`.
22. **Can DISABLED feature become active through fallback?**  
    **NO.** Explicit `"disabled"` strings parse strictly to `DISABLED`.
23. **Can app update downgrade the application?**  
    **NO.** Enforced by `targetCode > currentCode`.
24. **Can an invalid APK URL be accepted?**  
    **YES.** `updateInfo.downloadUrl` is not validated for URL scheme before `Intent.ACTION_VIEW`.
25. **Can SHA256 verification be bypassed?**  
    **YES.** Delegating update download to external browser skips in-app SHA256 verification.
26. **Can a setting survive logout when it should not?**  
    **YES.** Device DataStore settings, blocked users, and Room support chat survive logout.
27. **Can old `isPremium` or `isPro` logic alter quality?**  
    **NO.** Quality checks never inspect `isPremium` or `isPro`.
28. **Can client-side economy operations be replayed?**  
    **NO.** Transaction checks and unique transaction IDs mitigate replay within Firebase.
29. **Can ad rewards be trusted without server-side verification?**  
    **NO.** Currently operating in client-authoritative temporary economy mode.
30. **Can backend dependency be missing while UI claims secure economy?**  
    **YES.** `trustedBackendUrl` is null, falling back to temporary client Firebase operations.

---

## 29. Positive Architectural Findings

1. **Strict Subscription-Quality Decoupling:** Complete preservation of the canonical contract: subscriptions remove ads only.
2. **Atomic Firestore Transactions:** Daily login, task claims, and redemptions all execute via atomic transactions with balance and claim guards.
3. **Canonical Pricing Adherence:** Exact adherence to 50, 250, 350, 1000 point tiers.
4. **UTC Date Consistency:** Daily login strictly uses UTC day boundaries.
5. **No `/config/global` Leakage:** The deprecated path is completely eradicated.

---

## 30. Evidence Classification

| Finding Domain | Evidence Level | Verification Method |
|---|---|---|
| **Subscription Quality Decoupling** | STATICALLY VERIFIED | Traced `UserRestrictions.kt:105-128`, `PlayerViewModel.kt` |
| **Rewarded Ad Direct Claim** | STATICALLY VERIFIED | Traced `PointsEarningViewModel.kt:175-195` |
| **Clock Rollback Vulnerability** | STATICALLY VERIFIED | Traced `SubscriptionNormalizer.kt:106, 171` |
| **Canonical SKU Pricing** | STATICALLY VERIFIED | Traced `EconomyConfig.kt:48-53`, `TemporaryFirebaseEconomyRepository.kt:530` |
| **App Update External Intent** | STATICALLY VERIFIED | Traced `AppUpdateDialog.kt:135-144` |
| **Local Settings Persistence** | STATICALLY VERIFIED | Traced `UserPreferencesRepository.kt`, `AuthRepository.signOut()` |

---

## 31. Master Findings Matrix

| ID | Severity | Domain | File & Line | Root Cause | Impact | Evidence |
|---|---|---|---|---|---|---|
| **ECO-06-01** | **HIGH** | Ads / Points | `PointsEarningViewModel.kt:178` | "Watch Ad" dispatches points claim without showing any ad SDK widget | Users earn 15 points every 5 minutes without watching ads | STATICALLY VERIFIED |
| **ECO-06-02** | **HIGH** | Subscription | `SubscriptionNormalizer.kt:106` | Compares expiration against `System.currentTimeMillis()` | Device clock rollback artificially extends expired subscriptions offline | STATICALLY VERIFIED |
| **ECO-06-03** | **MEDIUM** | Economy Security | `PointsEarningRepository.kt:56` | `trustedBackendUrl = null`, relies on client-authoritative Firebase mode | Lack of server-side verification enables client script exploitation | STATICALLY VERIFIED |
| **ECO-06-04** | **MEDIUM** | App Update | `AppUpdateDialog.kt:137` | Hands off download to external browser via `Intent.ACTION_VIEW` | SHA-256 verification in `AppConfig` is completely bypassed | STATICALLY VERIFIED |
| **ECO-06-05** | **MEDIUM** | Settings / Privacy | `UserPreferencesRepository.kt:37` | Blocked users and friend requests stored in local DataStore without user key | Leaks blocked users and friend requests across accounts on shared device | STATICALLY VERIFIED |
| **ECO-06-06** | **LOW** | App Update | `AppUpdateManager.kt:119` | Checks `url.isNotBlank()` but does not require `https://` | Insecure HTTP update links could be launched | STATICALLY VERIFIED |

---

## 32. Final Verdict & Risk Stratification

### Overall Economy / Subscription Status:
**PASS WITH LIMITATIONS**

### Severity Counts:
- **P0 (Critical):** 0
- **P1 (High):** 2 (`ECO-06-01`, `ECO-06-02`)
- **P2 (Medium):** 3 (`ECO-06-03`, `ECO-06-04`, `ECO-06-05`)
- **P3 (Low):** 1 (`ECO-06-06`)
- **INFO:** 0

### Summary of Most Dangerous Risks:
- **Most Dangerous Subscription Bug:** Offline clock rollback artificially preserves ad-free status (`ECO-06-02`).
- **Most Dangerous Points Bug:** Rewarded ad button grants points directly without loading or rendering any advertisement (`ECO-06-01`).
- **Most Dangerous Update Security Bug:** In-app SHA256 verification is bypassed by launching download links in the external browser (`ECO-06-04`).
- **Most Dangerous Settings Isolation Bug:** Shared-device account switching inherits local blocked users and support chat history (`ECO-06-05`).

---

## 33. Hard Stop Compliance Confirmation

- **Zero modifications** were applied to production code, tests, Gradle files, dependencies, Firebase configurations, Firestore rules, Room schemas, or UI files.
- The forensic audit was completed in strict read-only mode based purely on static code analysis of the uploaded project files.
- The full audit report is saved to `/PHASES/PHASE_06.7_ECONOMY_SUBSCRIPTION_ADS_REWARD_LEADERBOARD_CONFIG_SETTINGS_FORENSIC_AUDIT_REPORT.md`.
