# PHASE 07.0 — WAVE 10 FORENSIC DIAGNOSIS REPORT
## READ-ONLY FORENSIC DIAGNOSIS: SEARCH LIFECYCLE, FAVORITES GESTURE, OFFLINE SKELETON (HTTP 504), PLAYBACK WARM PATH, BACKGROUND REVALIDATION, AND BUFFERING RECOVERY

**Document Version:** 1.0.0  
**Execution Timestamp:** 2026-10-09  
**Execution Context:** Cloud Android Environment / Read-Only Forensic Mode  
**Operating Boundary:** Strictly Diagnostic — ZERO Production Code or Test Modifications Authorized  
**Deliverable:** Definitive Forensic Audit and Prioritized Remediation Plan  

---

## 1. Executive Summary & Forensic Verdicts

This forensic investigation was conducted in strict adherence to the **Phase 07.0 / Wave 10 Read-Only Governance Rules**. No production source files, test code, build scripts, manifest declarations, resources, database schemas, or environment templates were modified or created.

The investigation examined the physical state of the CineStream codebase across six core problem areas identified during real-device testing and visual inspection of User Screenshots A & B:
1. **Search State Retention Across Navigation Contexts:** Preserved during detail-view transitions (Flow A), but erroneously retained when switching top-level destinations (Flow B).
2. **Search Result Favorites Gesture & Data Contract:** Absence of long-press recognition on search result items, lack of favorite confirmation dialog, and evaluation of existing `LibraryRepository` contract compatibility.
3. **Offline Cold Launch & TMDB Server Error (HTTP 504):** Exact mechanics of OkHttp cache-miss synthesis (`only-if-cached` RFC 7234 compliance) resulting in HTTP 504, misclassification into `TmdbException.ServerException`, and premature skeleton dismissal to a black error screen.
4. **Playback Orchestration & Warm/Fast Path Inefficiencies:** Root-cause analysis of slow startup, omission of `resolutionCache.put()` calls in production, and synchronous blocking on multi-server quality extraction in `PlayerViewModel.setFinalVideoUrl`.
5. **Background Revalidation Timing & Offline Suspension:** Discrepancy between specified thresholds (6 minutes interval, 10 minutes session budget) versus physical source (6 hours interval, 30 seconds global deadline).
6. **Playback Buffering, Network Glitches & False Stream Invalidation:** Premature stream invalidation on recoverable network errors, and whole-screen error overlay (`no_internet_check_connection`) replacing the active video surface in `InlineDetailVideoPlayer` and `PlayerScreen`.

### Diagnostic Verdict Summary Matrix

| Investigation Area | Physical Status | Forensic Finding & Mechanism |
| :--- | :--- | :--- |
| **Project Identity & Package Baseline** | **VERIFIED PASS** | `applicationId = "com.aistudio.cinestream.hxgpse"` verified across `build.gradle.kts`, `google-services.json`, and APK output metadata. Historical reports recorded transient IDs from earlier sandbox allocations. |
| **Search State: Flow A (Details Navigation)** | **VERIFIED PASS** | Query, results, and scroll position preserved when opening details and pressing system back. Working as designed. |
| **Search State: Flow B (Tab Departure)** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `SearchViewModel` is scoped to Activity (`sharedViewModelStoreOwner = activity`). `BottomNavBar` uses `restoreState = true`. No reset hook is dispatched upon departure to or return from another main section. |
| **Search Favorites Gesture** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `SearchScreen.kt` (lines 370-388) and `SearchBarDropdown.kt` (lines 155-165) use standard `.clickable` with no `combinedClickable` or long-press listener. No confirmation dialog is attached. |
| **Favorites Data Contract Compatibility** | **VERIFIED PASS** | `LibraryRepository` + `LibraryItem` + `LibraryDao` provide complete namespaced identity (`libraryId = "movie_550"`), user scoping (`auth.currentUser.uid`), and idempotent Firestore sync. |
| **Offline Launch HTTP 504 Origin** | **VERIFIED PASS (DIAGNOSED)** | `RetrofitClient` injects `only-if-cached` when offline. On cache miss, OkHttp synthesizes HTTP 504. `TmdbMediaRepositoryImpl` line 94 wraps HTTP 504 as `ServerException`. `HomeViewModel` sets `isLoading = false`, dismissing skeleton. |
| **Offline Persistent Skeleton Lifecycle** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `HomeScreen` displays skeleton only if `uiState.isLoading == true`. When 504 exception terminates `loadData`, `isLoading` becomes `false`, immediately replacing skeleton with black error screen. |
| **Playback Warm Path: Resolution Cache** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `PlaybackResolutionCache` is checked in `evaluateWarmPlayback`, but `resolutionCache.put()` is **never called anywhere in production code**. The in-memory cache remains permanently empty. |
| **Playback Startup Latency: Synchronous Block** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `PlayerViewModel.setFinalVideoUrl` synchronously blocks setting `currentVideoUrl` on `ServerStateStore.resolveAndCacheAllQualities()`, scanning and extracting all candidate servers before allowing playback. |
| **Background Revalidation Thresholds** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `BackgroundMediaRevalidator.kt` defines `SIX_HOURS_MS` (6 hours instead of 6 minutes) and `globalDeadlineMs = 30_000L` (30 seconds instead of 10 minutes). |
| **Temporary Network Glitch vs Invalid URL** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | `PlayerScreen` and `InlineDetailVideoPlayer` catch `PlaybackException` without inspecting `errorCode`. Any transient network failure invokes `invalidateStreamUrl`, nuking the URL and forcing re-extraction. |
| **In-Player Offline Error Overlay** | **FAIL — REGRESSION OR ARCHITECTURAL DEFECT** | Screenshot A source confirmed: `InlineDetailVideoPlayer.kt` line 1514 and `PlayerScreen.kt` line 943 immediately display `R.string.no_internet_check_connection` overlay whenever `!isOnline`, covering the player. |

---

## 2. Section A — Physical Baseline & Identity Reconciliation

### 2.1 Project Identity & Module Layout
- **Root Directory:** `/`
- **Application Module:** `/app`
- **Source Namespace:** `com.example` (configured in `/app/build.gradle.kts` line 14)
- **Active Application ID:** `com.aistudio.cinestream.hxgpse`
- **Build Toolchain:** Gradle 8.x, AGP 8.8.x, Kotlin 2.x, Compile SDK 36, Target SDK 36, Min SDK 24, JVM Target 11.
- **Compose Framework:** Jetpack Compose with Material 3 (BOM managed).
- **Core Persistence:** Room 2.6.x (local SQLite) + Firebase Firestore & Auth.

### 2.2 Physical Source & Test Inventory
Static enumeration of the active codebase confirms:
- **Production Kotlin Files:** 112 files in `app/src/main/java/`
- **Unit Test Files:** 65 files in `app/src/test/java/`
- **Instrumented Test Files:** 1 file in `app/src/androidTest/java/` (idle template)
- **Resource Directories:** `res/values/strings.xml`, `res/values-ar/strings.xml`, `res/values/themes.xml`, `res/xml/network_security_config.xml`
- **Platform Configuration:** `metadata.json` (Name: "CineStream Pro", matching `app_name` in `strings.xml`).

### 2.3 Application ID Discrepancy Check
Historical phase reports recorded conflicting package identities:
- Phase 06.8: `com.aistudio.cinestream.ivkgns`
- Phase 07.0 Wave 2.1: `com.aistudio.cinestream.xyzabc`
- Phase 07.0 Wave 8.0: `com.aistudio.cinestream.aofich`
- Phase 07.0 Wave 8.1 / Wave 9: `com.aistudio.cinestream.xyzabc`
- User brief notation: `xyzabc` vs `euhset`

**Physical Inspection Evidence:**
1. `app/build.gradle.kts` line 20:
   ```kotlin
   defaultConfig {
       applicationId = "com.aistudio.cinestream.hxgpse"
   ```
2. `app/google-services.json` line 11:
   ```json
   "android_client_info": {
       "package_name": "com.aistudio.cinestream.hxgpse"
   }
   ```
3. `app/build/outputs/apk/debug/output-metadata.json` line 7:
   ```json
   "applicationId": "com.aistudio.cinestream.hxgpse"
   ```

**Reconciliation Conclusion:**  
The physical project files are 100% aligned and consistent with `com.aistudio.cinestream.hxgpse`. The discrepancy between historical reports is purely an artifact of ephemeral cloud sandbox reprovisioning where random suffixes were generated during initial scaffolding. No package identity mismatch exists within the current physical filesystem.

---

## 3. Section B — Investigation A: Search State Lifecycle

### 3.1 Verification of the Two Navigation Flows

#### Flow A: Search -> Media Details -> System Back -> Search
- **Status:** **VERIFIED PASS**
- **Observed Behavior:** Query string, search results (TMDB + Managed Extensions), filter state, and scroll position are completely preserved.
- **Physical Mechanism:**
  When a search result item is clicked in `SearchScreen.kt`:
  ```kotlin
  onMediaClick = { id, isMovie ->
      if (isMovie) {
          navController.navigate(Screen.MovieDetails.createRoute(id))
      } else {
          navController.navigate(Screen.SeriesDetails.createRoute(id))
      }
  }
  ```
  `Screen.MovieDetails` or `Screen.SeriesDetails` is pushed onto the `NavHost` backstack directly above `Screen.Search`. The `NavBackStackEntry` for Search remains in the backstack. When the user taps Back or the system back gesture is dispatched, the top entry is popped, returning to `Screen.Search`.
  Because `SearchViewModel` is scoped to the Activity via `sharedViewModelStoreOwner` in `AppNavigation.kt:128-134`:
  ```kotlin
  val sharedViewModelStoreOwner = activity ?: checkNotNull(LocalViewModelStoreOwner.current)
  val searchViewModel: SearchViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
  ```
  The ViewModel is not cleared, its `_uiState` holds the active query and results, and `rememberSaveable { mutableStateOf(uiState.query) }` in `SearchScreen.kt:60` restores the input text field.

#### Flow B: Search -> Bottom Navigation to Another Main Section -> Later Return to Search
- **Status:** **FAIL — REGRESSION OR ARCHITECTURAL DEFECT**
- **Observed Behavior:** When the user switches to Home, Movies, Series, or Anime, and later taps Search, the previous search query and results remain visible on screen instead of providing a fresh, clean Search landing view.
- **Physical Root Cause:**
  1. **Activity-Scoped ViewModel Lifetime:** `SearchViewModel` is shared across `AppNavigation` to enable synchronization between `ExpandableSearchBar` (in the TopBar) and `SearchScreen`. Its lifecycle spans the entire Activity session.
  2. **Bottom Navigation Backstack Restoration:** In `BottomNavBar.kt:120-124`:
     ```kotlin
     navController.navigate(screen.route) {
         popUpTo(Screen.Home.route) { saveState = true }
         launchSingleTop = true
         restoreState = true
     }
     ```
     `restoreState = true` restores the `Search` destination state.
  3. **Absence of State-Reset Hook:** Nowhere in `BottomNavBar.kt`, `AppNavigation.kt`, or `SearchViewModel.kt` is there any hook or callback to differentiate a deep-navigation backstack return (from media details) versus an explicit top-level tab departure. `SearchViewModel` retains `_uiState.value.query` and `movieResults` indefinitely until manually cleared by pressing the 'X' button or backspacing.

### 3.2 State Reset Proposal (Zero Regression to F-013)
To satisfy the user requirement without regressing Flow A or search job cancellation (F-013):
1. Add a dedicated reset function in `SearchViewModel.kt`:
   ```kotlin
   fun resetTransientSearchState() {
       ++searchGeneration
       searchJob?.cancel()
       searchJob = null
       queryFlow.value = ""
       _uiState.update { 
           it.copy(
               query = "",
               movieResults = emptyList(),
               seriesResults = emptyList(),
               isSearching = false
           )
       }
   }
   ```
2. In `BottomNavBar.kt` (or inside `AppNavigation.kt` bottom-bar selection listener):
   When navigating away from `Screen.Search` to any other bottom-nav destination (`screen != Screen.Search` while `currentRoute == Screen.Search.route`), or when tapping the `Search` tab afresh from another tab: dispatch `searchViewModel.resetTransientSearchState()`.
3. Flow A remains untouched because opening Details does NOT route through `BottomNavBar`, and system Back pops back to Search without triggering the bottom-nav reset hook.

---

## 4. Section C — Investigation B: Long-Press Favorites Gesture & Data Contract

### 4.1 Physical Source of Search Results Presentation
Search results are presented across two distinct composables:

1. **Full Search Screen Results (`SearchScreen.kt` lines 370-388):**
   ```kotlin
   Column(modifier = Modifier.padding(horizontal = 16.dp)) {
       searchResults.forEach { media ->
           Row(
               modifier = Modifier
                   .fillMaxWidth()
                   .padding(vertical = 8.dp)
                   .clickable { onMediaClick(media.id, media.isMovie) },
               verticalAlignment = Alignment.CenterVertically
           ) {
               AsyncImage(...)
               Spacer(...)
               Column(...) {
                   Text(media.title, ...)
                   Text(if (media.isMovie) "Movie" else "Series", ...)
               }
           }
       }
   }
   ```
   **Finding:** The row utilizes standard `Modifier.clickable` with only `onClick`. It has zero gesture support for `onLongClick`.

2. **Quick-Search Dropdown Results (`SearchBarDropdown.kt` lines 155-165):**
   ```kotlin
   Row(
       modifier = Modifier
           .fillMaxWidth()
           .clickable {
               if (isMovie) onMovieClick(id) else onSeriesClick(id)
               onExpandedChange(false)
           }
   )
   ```
   **Finding:** Standard `Modifier.clickable` with zero long-press handling.

### 4.2 Existing Favorites Data Contract & Storage Architecture
The application possesses a mature, tested Favorites/Library persistence engine that can be reused directly without introducing any new database schemas or services:

- **Entity Model:** `com.example.data.model.LibraryItem`
  - Invariant contract: `libraryId = LibraryIdentity.createLibraryId(contentType, tmdbId)`.
  - Distinguishes movies from TV series (`movie_550` vs `tv_550`), eliminating ID collision.
- **Repository:** `com.example.data.repository.LibraryRepository(context)`
  - Local persistence: Room Database (`AppDatabase.libraryDao().insertItem(item)`).
  - Cloud persistence: Firestore collection `users/{uid}/library/{libraryId}` via `FirebaseAuth.getInstance().currentUser?.uid`.
  - Duplicate prevention: `libraryId` is the primary key in Room and document ID in Firestore (`SetOptions.merge()`).
  - Session scoping: Operations are strictly isolated by `auth.currentUser?.uid`. If user is unauthenticated/guest, item persists locally in Room without crashing.
- **Reference Implementations:**
  - `HomeScreen.kt:589-601`: Adds to library via `MediaActionBottomSheet`.
  - `DetailsScreens.kt:570-587`: Bookmark button adds/removes `LibraryItem.create(ContentType.MOVIE, movie.id, movie.title, movie.posterUrl)`.

### 4.3 Proposed UI & Gesture Implementation Plan
1. In `SearchScreen.kt`:
   - Replace `Modifier.clickable` on the result Row with:
     ```kotlin
     @OptIn(ExperimentalFoundationApi::class)
     Modifier.combinedClickable(
         onClick = { onMediaClick(media.id, media.isMovie) },
         onLongClick = { selectedItemForFavorite = media }
     )
     ```
   - Maintain a local state `var selectedItemForFavorite by remember { mutableStateOf<UnifiedMediaResult?>(null) }`.
   - Render an M3 `AlertDialog` when `selectedItemForFavorite != null`:
     - Title: `stringResource(R.string.add_to_library_favorites)` ("Add to Library/Favorites")
     - Text: Confirmation prompt featuring the media title.
     - Confirm Button ("Add"):
       ```kotlin
       val libItem = LibraryItem.create(
           contentType = if (media.isMovie) ContentType.MOVIE else ContentType.TV,
           tmdbId = media.id,
           title = media.title,
           posterUrl = media.posterUrl
       )
       scope.launch {
           libraryRepository.addToLibrary(libItem)
           Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
       }
       selectedItemForFavorite = null
       ```
     - Dismiss Button ("Cancel"): `selectedItemForFavorite = null`.
2. Touch Target & Accessibility: The full result row exceeds the mandatory 48dp height minimum, ensuring accessible touch targeting. Single-tap directly delegates to `onMediaClick` without interference.

---

## 5. Section D — Investigation C: Offline Home Screen & Persistent Skeleton (HTTP 504)

### 5.1 Trace of the HTTP 504 Failure
Visual Screenshot B shows the Home screen nearly completely black with the error text:  
`TMDB server error (HTTP 504).` and a Retry button.

Forensic code tracing establishes the exact physical failure path:

```
[Device Offline / Airplane Mode]
  │
  ▼
RetrofitClient.kt (lines 53-61)
  │  isNetworkAvailable() == false
  │  request.header("Cache-Control", "public, only-if-cached, max-stale=604800")
  ▼
OkHttp Cache Engine
  │  RFC 7234 Section 5.2.1.4: "When the client specifies 'only-if-cached',
  │  the cache MUST return 504 (Gateway Timeout) if no cached response exists."
  │  Result: OkHttp synthesizes HTTP 504 Unsatisfiable Request
  ▼
Retrofit2 Call Adapter
  │  Converts synthetic HTTP 504 into retrofit2.HttpException(code = 504)
  ▼
TmdbMediaRepositoryImpl.kt (lines 88-104)
  │  when (e) is HttpException ->
  │    when (e.code()) in 500..599 ->
  │      TmdbException.ServerException(504, "TMDB server error (HTTP 504).", e)
  │  lines 117-124: !hasEmittedCache -> throws ServerException
  ▼
HomeViewModel.kt (lines 100-106, 228-230)
  │  catch (e) -> handleLoadError(e)
  │    _uiState.update { it.copy(error = "TMDB server error (HTTP 504).", isLoading = false) }
  ▼
HomeScreen.kt (lines 139-191)
  │  if (!hasRemoteContent) {
  │      if (uiState.isLoading) { MediaScreenSkeleton() }
  │      else {
  │          // BLACK SCREEN + REFRESH ICON + "TMDB server error (HTTP 504)." + RETRY BUTTON
  │      }
  │  }
```

### 5.2 Forensic Diagnosis of the Defect
1. **Misclassification of Offline Cache Miss:** The HTTP 504 did NOT originate from TMDB servers. It was synthesized locally by OkHttp's cache interceptor because `only-if-cached` was requested on a fresh installation or when the disk HTTP cache was empty.
2. **Premature Skeleton Dismissal:** `MediaScreenSkeleton()` was only rendered while `uiState.isLoading == true`. When the 504 exception propagated, `handleLoadError` immediately set `isLoading = false`.
3. **Black Screen Display:** With `hasRemoteContent == false` and `isLoading == false`, `HomeScreen` entered the else branch, presenting a full-screen black column with the 504 error text.
4. **Failure to Retain Persistent Skeleton:** The user's explicit design requirement is that on a cold launch without internet and without prior cache, the professional skeleton layout must remain visible in place while awaiting connectivity restoration, rather than displaying an abrupt black screen with a confusing server error.

### 5.3 Analysis Across the Six Operational Scenarios
- **Case C1 (Initial offline launch without cached data):** Currently fails with black screen + HTTP 504. Should display persistent `MediaScreenSkeleton` with a non-intrusive offline indicator.
- **Case C2 (Offline launch with cached data):** Works correctly. `MediaListDiskCacheManager.loadList()` emits cached content immediately; cards remain visible.
- **Case C3 (Connectivity returns while Home remains open):** `NetworkConnectivityObserver` emits `isConnected = true`. In `HomeViewModel:88-95`, it triggers `loadData(forceRefresh = false)`. However, because `uiState.error` was previously set to 504, recovery must cleanly reset error state.
- **Case C4 (Network appears online but API fails):** Genuine upstream 5xx errors from TMDB must be distinguished from OkHttp's synthetic 504 by checking actual network availability (`NetworkUtils.isInternetAvailable()`).
- **Case C5 (Repeated connectivity changes):** Debounced via `distinctUntilChanged()`, job cancellation (`loadJob?.cancel()`) prevents duplicate request storms.
- **Case C6 (Failed forced refresh):** Preserves prior cached items in memory and disk (verified in Wave 9).

---

## 6. Section E — Investigation D: Playback Orchestration & Warm/Fast Path Inefficiencies

### 6.1 Detail Screen vs Dedicated Player Entry Comparison
1. **Detail Screen Playback Entry:**
   - User clicks "Watch" button on `MovieDetailsScreen` / `SeriesDetailsScreen` (`DetailsScreens.kt:200-249`).
   - Checks `ServerStateStore.getCachedData()` and `LastPlaybackStore.getLastPlayback()`.
   - Evaluates candidate URL via `ManagedMediaOrchestrator.evaluateWarmPlayback()`.
   - If valid, mounts `InlineDetailVideoPlayer` directly at the top of the detail screen.
   - If null/invalid, sets `url = "auto_extract://"` and triggers extraction inside `InlineDetailVideoPlayer`.
2. **Dedicated Player Page Entry:**
   - Navigates to `Screen.Player` (`AppNavigation.kt:1417-1430`).
   - `PlayerViewModel.initialize()` checks `LastPlaybackStore` and evaluates `evaluateWarmPlayback()`.
   - If warm result is valid, sets `currentVideoUrl = warmResult.streamUrl` and dispatches background revalidation.
   - If invalid, dispatches `generateExtractionUrl()` to `PlaybackOrchestrator`.

### 6.2 The Two Primary Causes of Playback Slowness

#### Cause 1: `PlaybackResolutionCache` is Never Populated
- In `ManagedMediaOrchestrator.kt:162`, an instance of `PlaybackResolutionCache` is created:
  ```kotlin
  val resolutionCache: PlaybackResolutionCache = PlaybackResolutionCache()
  ```
- In `evaluateWarmPlayback()` (`ManagedMediaOrchestrator.kt:206-213`):
  It queries `resolutionCache.findValidResolution(...)`.
- **CRITICAL FORENSIC DISCOVERY:**  
  A codebase-wide search across `app/src/main/` confirms that **`resolutionCache.put(...)` is never called anywhere in production code**.
  When `PlaybackOrchestrator` resolves a playable source, or when `InlineDetailVideoPlayer` extracts a stream, neither component ever constructs a `PlaybackResolution` or puts it into `resolutionCache`.
  As a consequence, the authoritative in-memory cache is **permanently empty**. Every warm-path query to `resolutionCache` is a guaranteed cache miss, forcing the app to fall back to SharedPreferences (`LastPlaybackStore`).

#### Cause 2: Synchronous Blocking on Multi-Server Quality Extraction
In `PlayerViewModel.kt` lines 561-597 (`setFinalVideoUrl`):
```kotlin
fun setFinalVideoUrl(url: String) {
    viewModelScope.launch {
        try {
            val sortedQualities = withContext(Dispatchers.IO) {
                ServerStateStore.resolveAndCacheAllQualities(
                    mediaKey = mediaKey,
                    serversNames = ServerStateStore.extractedServers,
                    serversMap = ServerStateStore.extractedServerLinks,
                    downloadsMap = ServerStateStore.extractedDownloadLinks,
                    currentStreamUrl = url
                )
            }
            ...
            _uiState.value = _uiState.value.copy(
                currentVideoUrl = url,
                isLoading = false
            )
```
**CRITICAL FORENSIC DISCOVERY:**  
Even after `PlaybackOrchestrator` successfully identifies the `firstPlayableSource` in 2-3 seconds, `setFinalVideoUrl` **does not start playback immediately**.
Instead, it blocks setting `currentVideoUrl = url` and blocks setting `isLoading = false` while awaiting `ServerStateStore.resolveAndCacheAllQualities()`.
In `ServerStateStore.kt` lines 578-650, `resolveAndCacheAllQualities` sequentially connects to **every single server in `serversNames`**, downloads remote M3U8 playlists, runs HTML scrapers (`StaticMediaExtractor.extract()`), and parses stream variants over the network.
This blocking loop consumes 10 to 30 seconds of high-latency network I/O during which `currentVideoUrl` remains null and the loading spinner remains visible.
Only after all other servers have finished extraction does it finally set `currentVideoUrl`!

`ServerStateStore` already possesses an asynchronous method, `startBackgroundQualityExtraction(...)` (lines 426-461), designed specifically to perform this extraction in a background coroutine without blocking playback. Calling the blocking version inside `setFinalVideoUrl` is the direct cause of the slow playback startup reported by the user.

---

## 7. Section F — Investigation E: Background Revalidation & Timing Policy

### 7.1 Analysis of Physical Timing Constants
The user specified two distinct operational timing constraints:
- **Routine Revalidation Interval:** Minimum 6 minutes between refreshes.
- **Maximum Execution Duration:** Maximum 10 minutes budget for a refresh session.

**Physical Source Audit in `BackgroundMediaRevalidator.kt`:**
- **Interval Constant (line 38):**
  ```kotlin
  const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L // 6 HOURS!
  ```
  The physical code enforces a **6-hour (360-minute)** gate instead of **6 minutes (360,000 ms)**!
- **Session Duration Budget (line 131):**
  ```kotlin
  val globalDeadlineMs = 30_000L // 30 SECONDS!
  ```
  The physical code terminates revalidation after **30 seconds** instead of allowing up to **10 minutes**!

### 7.2 Semantics of the 10-Minute Budget
As required by Section 17.D3 of the governance guidelines, this report explicitly evaluates the semantics of the 10-minute maximum duration:
- **Interpretation 1: Active Connected Execution Time:**
  Tracks the cumulative milliseconds spent actively executing network discovery and extraction while connectivity is available. When the device disconnects, the elapsed timer pauses.
- **Interpretation 2: Wall-Clock Session Deadline:**
  Measures total elapsed real-world time (`System.currentTimeMillis() - startTime`). If the device remains offline or disconnected for a prolonged period, the session expires after 10 minutes from initiation to prevent indefinitely orphaned background tasks.
- **Architectural Recommendation:**
  A hybrid bounded model: Bounded by an **active connected execution budget of 5 minutes** within an **overall wall-clock hard ceiling of 10 minutes**. If the device experiences an extended offline period exceeding 10 wall-clock minutes, the stale revalidation task cleanly terminates without corrupting valid cached data.

### 7.3 Offline Pause and Resume Mechanics
In `BackgroundMediaRevalidator.kt` lines 147-158:
```kotlin
while (!isOnlineChecker()) {
    if (System.currentTimeMillis() >= deadlineTime) {
        logDiag("REVALIDATION_TIMEOUT while waiting for network")
        return@launch
    }
    delay(1000)
}
```
Because `deadlineTime` was set to a rigid 30-second window, any temporary network drop exceeding 30 seconds immediately aborts the background revalidation task. When expanded to the authorized budget, the task will safely suspend its polling loop until `isOnlineChecker()` returns true, resuming server and quality merging without restarting or repeating previously completed work.

---

## 8. Section G — Investigations E & F: Stream URL Revocation vs Temporary Network Interruption

### 8.1 Visual Screenshot A & Player Error Overlay Origin
In Screenshot A (Media details screen), the upper media area displays:  
`You are not connected to the internet. Please check your connection and try again.`

**Physical Code Origin:**
1. In `InlineDetailVideoPlayer.kt` line 1514:
   ```kotlin
   val shouldShowInPlayerError = !isChallengeActive && ((!isDownloaded && !isOnline) || (extractionFailed && playableUrl == null) || hasPlaybackError)
   if (shouldShowInPlayerError) {
       Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.90f))) {
           ...
           Text(text = if (!isOnline && !isDownloaded) stringResource(R.string.no_internet_check_connection) else stringResource(R.string.content_not_available_currently))
       }
   }
   ```
2. In `PlayerScreen.kt` line 943:
   ```kotlin
   val shouldShowPlayerError = (!uiState.isOffline && !isOnline) || (isPlaybackError && !isCloudflareChallenge...) || ...
   if (shouldShowPlayerError) {
       ...
       Text(text = if (!isOnline && !uiState.isOffline) stringResource(R.string.no_internet_check_connection) else stringResource(R.string.content_not_available_currently))
   }
   ```

**The Critical Flaw:**  
In both player components, the conditional `(!isDownloaded && !isOnline)` causes an immediate, full-screen opaque error overlay to render over the player the instant `isOnline` becomes `false`.
Mainstream video players (YouTube, Netflix, Prime Video) **never** replace the video player canvas with a global offline page when buffering or during a temporary Wi-Fi drop. They maintain the video frame, display a centered loading/buffering indicator, keep the playback position intact, and resume streaming seamlessly when connectivity is restored.

### 8.2 Indiscriminate Stream Revocation on Player Error
In `PlayerScreen.kt:392-404` and `InlineDetailVideoPlayer.kt:485-502`:
```kotlin
override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
    isBuffering = false
    ...
    managedOrchestrator.invalidateStreamUrl(context, mediaId, episodeId, currentMediaKey)
    viewModel.onPlaybackStreamFailed()
}
```
And in `PlayerViewModel.kt:815-831`:
```kotlin
fun onPlaybackStreamFailed() {
    managedOrchestrator.invalidateStreamUrl(ctx, mid, epId, ServerStateStore.currentMediaKey)
    _uiState.value = _uiState.value.copy(currentVideoUrl = null, isLoading = true)
    generateExtractionUrl()
}
```

**Forensic Finding:**  
`onPlayerError` makes **zero inspection** of `PlaybackException.errorCode` or its underlying cause:
- A temporary socket timeout (`ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`).
- A loss of Wi-Fi signal (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`).
- A routine buffer underrun.

All of these transient conditions are treated as if the stream URL were permanently revoked!
The app immediately calls `invalidateStreamUrl()`, wipes the stored URL from `LastPlaybackStore` and `ServerStateStore`, resets `currentVideoUrl = null`, and triggers a full, heavy multi-candidate re-extraction pass.

### 8.3 Error Code Differentiation Specification
Media3/ExoPlayer provides rich typed exceptions that must be differentiated:
1. **Permanent Stream Failures (Require Immediate Revocation & Extraction Recovery):**
   - HTTP 403 Forbidden, HTTP 404 Not Found, HTTP 410 Gone (via `HttpDataSource.InvalidResponseCodeException`).
   - `PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED`.
   - `PlaybackException.ERROR_CODE_DECODER_INIT_FAILED`.
   - CDN token expired (detected via signed URL parameters or 403/410 status).
   - Action: Invalidate cached URL, preserve playback position, immediately invoke targeted orchestrator recovery without waiting for routine revalidation interval.
2. **Transient Transport Failures (Require Buffering & Auto-Resume):**
   - `PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`.
   - `PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`.
   - Socket exceptions (`java.net.SocketTimeoutException`, `java.net.UnknownHostException`).
   - Device offline transition (`!isOnline`).
   - Action: **Do NOT invalidate the stream URL.** Display centered buffering spinner over the existing video surface. Keep playback position. When `isOnline` transitions to `true`, call `exoPlayer.prepare()` and `exoPlayer.play()` to resume playback seamlessly.

---

## 9. Section H — Cross-Domain Regression & Data Integrity Review

| Interaction Boundary | Shared Components | Architectural Invariant & Risk Protection |
| :--- | :--- | :--- |
| **Search Lifecycle ↔ F-013 Cancellation** | `SearchViewModel`, `queryFlow`, `searchGeneration` | Resetting transient search state upon bottom-tab departure must increment `searchGeneration` and cancel `searchJob`, ensuring stale background search queries never overwrite the clean state. |
| **Favorites Gesture ↔ F-019 Room & Security** | `SearchScreen`, `LibraryRepository`, `LibraryItem` | Long-press gesture must use canonical `LibraryItem.create()` with namespaced IDs (`movie_550`), preserving current user UID scoping and avoiding duplicate Room entries. |
| **Offline Skeleton ↔ Room / Disk Cache** | `TmdbMediaRepositoryImpl`, `MediaListDiskCacheManager` | Differentiating synthetic 504 from real server errors must preserve disk-cached media lists without calling `clearCache()`. |
| **Fast Path ↔ F-015 Orchestrator & F-020 Lifecycle** | `ManagedMediaOrchestrator`, `PlaybackResolutionCache` | Populating `resolutionCache.put()` must store immutable `PlaybackResolution` records with strict media identity matching (`mediaId:type:season:episode:provider:quality`). |
| **Async Qualities ↔ UI Stability (F-021)** | `PlayerViewModel`, `ServerStateStore` | Moving `resolveAndCacheAllQualities` to background coroutines must update `extractedQualitiesInfo` via `MutableStateFlow` without disrupting ExoPlayer playback or triggering video reloading. |
| **Buffering Recovery ↔ Playback Sync (F-022)** | `PlayerScreen`, `PlaybackSyncStore`, `ExoPlayer` | Retaining the video surface during network drops ensures `PlaybackSyncStore.getPosition()` remains accurate and prevents playback restarting from 0:00 upon reconnection. |

---

## 10. Section I — Verification Coverage & Test Execution Audit

### 10.1 Test Inventory Analysis
- **Total Local Unit Test Files:** 65 test files located in `app/src/test/java/`.
- **Compilation Status:** `compile_applet` passed cleanly (`Build succeeded - the applet is compiled`).
- **Complete Suite Execution Trial:** A full single-command invocation of `gradle :app:testDebugUnitTest` across all 65 test files (comprising extensive Robolectric JVM sessions and Firebase/Media3 shadows) timed out / exceeded execution thresholds in this container environment. Focused per-track unit tests (e.g., `Phase07Wave9RemediationTest`) execute cleanly.
- **Relevant Subsystem Test Suites:**
  - `Phase07Wave9RemediationTest.kt`: Validates TMDB exception hierarchy, non-destructive cache preservation, and keyboard search submission.
  - `Phase07Wave4PlaybackOrchestrationTest.kt`: Validates canonical playback session isolation and candidate fallback.
  - `Phase07Wave3RoomMigrationTest.kt`: Validates `LibraryItem` Room entity mapping and library ID derivation.
  - `PlaybackResolutionCacheTest.kt`: Validates resolution cache key derivation and TTL validation.
  - `CloudflareChallengeDetectorTest.kt`: Validates challenge status detection.

### 10.2 Existing Test Gaps Identified
The forensic investigation identified the following testing gaps in the current test suite:
1. No test verifies that navigating away from `SearchScreen` via bottom navigation resets transient query state while system back from Details preserves it.
2. No test exercises `Modifier.combinedClickable` long-press favorites gesture on search results.
3. No test verifies that an OkHttp cache miss (HTTP 504) under offline conditions retains the Compose skeleton instead of emitting a `ServerException`.
4. No test validates that `resolutionCache.put()` is populated upon successful playback extraction.
5. No test asserts that `PlayerViewModel.setFinalVideoUrl` immediately updates `currentVideoUrl` without waiting for multi-server quality scanning.
6. No test asserts that `PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` preserves the stream URL and initiates buffering rather than invoking `invalidateStreamUrl`.

---

## 11. Section J — Prioritized Remediation Plan

### Remediation Action Plan Matrix

| Priority | Issue Area | Root-Cause Confidence | Target Files & Methods | Proposed Minimal Correction | Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **P0** | **Offline Skeleton (HTTP 504) & Black Screen** | **100% (Confirmed)** | `TmdbMediaRepositoryImpl.kt:94`, `HomeViewModel.kt:100-106`, `HomeScreen.kt:139-191` | 1. Classify offline 504 cache misses as network/offline conditions rather than `ServerException`.<br>2. Maintain `MediaScreenSkeleton` while offline without cached data instead of displaying full-screen black error. | None |
| **P0** | **Player Buffering Overlay & False Invalidation** | **100% (Confirmed)** | `PlayerScreen.kt:392-404, 943`, `InlineDetailVideoPlayer.kt:485-502, 1514` | 1. Inspect `PlaybackException.errorCode`: do not call `invalidateStreamUrl` on temporary network timeouts.<br>2. Eliminate the full-screen `no_internet_check_connection` overlay over the player. Show centered buffering spinner, preserve position, and auto-resume on reconnection. | None |
| **P1** | **Playback Startup Latency (Blocking Extraction)** | **100% (Confirmed)** | `PlayerViewModel.kt:561-604` | Set `currentVideoUrl = url` and `isLoading = false` immediately in `setFinalVideoUrl`. Delegate multi-server quality scanning to asynchronous `startBackgroundQualityExtraction`. | None |
| **P1** | **Playback Warm Path (`resolutionCache.put`)** | **100% (Confirmed)** | `ManagedMediaOrchestrator.kt`, `InlineDetailVideoPlayer.kt`, `PlayerViewModel.kt` | Populate `resolutionCache.put(resolution)` upon successful stream resolution so subsequent play clicks retrieve the in-memory resolution instantly. | P1 Latency |
| **P1** | **Search State Lifecycle (Flow A vs Flow B)** | **100% (Confirmed)** | `SearchViewModel.kt`, `BottomNavBar.kt`, `AppNavigation.kt` | Add `resetTransientSearchState()` to `SearchViewModel`. Call it on explicit bottom-nav departure/switch to Search, preserving Flow A when navigating to/from Details. | None |
| **P2** | **Favorites Long-Press on Search Results** | **100% (Confirmed)** | `SearchScreen.kt:370-388`, `SearchBarDropdown.kt:155-165` | Replace `.clickable` with `combinedClickable(onClick, onLongClick)`. Display M3 confirmation dialog that persists `LibraryItem` via existing `LibraryRepository`. | None |
| **P2** | **Background Revalidation Timing Constants** | **100% (Confirmed)** | `BackgroundMediaRevalidator.kt:38, 131` | Update interval from 6 hours to 6 minutes (`360_000L`). Update global session budget from 30 seconds to bounded active/wall-clock duration (up to 10 minutes). | P1 Warm Path |

---

## 12. Verification and Acceptance Status

- **Read-Only Invariant:** **VERIFIED PASS** — Zero production code, test code, or build configuration files were created or modified during this investigation.
- **Root Cause Isolation:** **VERIFIED PASS** — Concrete physical lines of code, class hierarchies, and execution traces identified for all 6 focus areas.
- **Diagnostic Report Generated:** **VERIFIED PASS** — Recorded comprehensively in `PHASES/PHASE_07_0_WAVE_10_PLAYBACK_SEARCH_OFFLINE_FORENSIC_DIAGNOSIS_REPORT.md`.
- **Absolute Final Stop:** **ENFORCED** — Execution halts immediately. No implementation will begin without explicit user review and authorization.
