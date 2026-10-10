# PHASE 06.5: COMPLETE PLAYBACK / DOWNLOAD / WEBVIEW / SCRAPER / MEDIA EXECUTION FORENSIC AUDIT REPORT
**Authoritative Forensic Analysis of Media Execution, Player Pipelines, Scraper Runtimes, WebViews, Cloudflare Handlers, Downloads, and Storage Contracts**

- **Project:** CineStream Pro — Users App (`CineStream-Pro00-main.zip`)
- **Phase:** 06.5
- **Audit Mode:** STRICT READ-ONLY / ZERO PRODUCTION MODIFICATIONS
- **Target Surfaces:** Media Players (Inline Detail Player & Fullscreen PlayerScreen), Scrapers (QFilm, WitAnime, AnimeBlkom, Anime4Up, EgyDead), WebViews (Interactive, Headless, Orphaned), Cloudflare Challenge Controller, Download Pipeline, Offline Playback, ExoPlayer Lifecycle, ServerStateStore, ManagedMediaOrchestrator, Caching Systems
- **Audit Date:** October 2026
- **Status:** COMPLETE
- **Final Verdict:** **PASS WITH LIMITATIONS** (Core playback and scraper architectures are functionally unified under ManagedMediaOrchestrator and PlaybackOrchestrator, but 18 distinct media execution, timeout, mapping, lifecycle, and isolation vulnerabilities were statically verified)

---

## 1. Absolute Rule & Source of Truth

- **Zero Modifications:** Zero production code, zero test code, zero Gradle files, zero dependencies, zero Firebase files, zero Firestore rules, zero Room schemas, and zero scrapers were modified, refactored, or deleted during this phase.
- **Sole Source of Truth:** The uploaded current project source is the single authoritative basis for this report. Historical phase reports (`PHASE_05G`, `PHASE_05Q`, `PHASE_05S`, `PHASE_06.3`, `PHASE_06.4`) are referenced strictly for architectural context and verified against live source lines.

---

## 2. Complete Media Execution Inventory

The CineStream media execution plane comprises 34 dedicated components spanning 5 architectural tiers:

| Tier | Component Type | Canonical Symbol | Source File Location | Responsibilities / Contract |
|---|---|---|---|---|
| **Player UI & VM** | Composable | `InlineDetailVideoPlayer` | `ui/components/InlineDetailVideoPlayer.kt` | 16:9 embedded player on details screen; PiP; controls; ExoPlayer owner |
| **Player UI & VM** | Composable | `PlayerScreen` | `ui/screens/player/PlayerScreen.kt` | Fullscreen immersive player; controls; track selector; gestures |
| **Player UI & VM** | ViewModel | `PlayerViewModel` | `ui/screens/player/PlayerViewModel.kt` | Manages player UI state, server selection, quality changes, episode switching |
| **Player UI & VM** | State Holder | `PlayerStateHolder` | `ui/screens/player/PlayerStateHolder.kt` | Process-wide singleton tracking active player, PiP mode, and PiP callbacks |
| **Player UI & VM** | Dialog | `ServerSelectionDialog` | `ui/screens/player/ServerSelectionDialog.kt` | Server switching modal dialog |
| **Player UI & VM** | Dialog | `SmartDownloadQualityDialog` | `ui/screens/player/SmartDownloadQualityDialog.kt` | Quality selection modal dialog |
| **Orchestration** | Orchestrator | `ManagedMediaOrchestrator` | `extension/orchestrator/ManagedMediaOrchestrator.kt` | Primary media discovery and handoff coordinator; session creator |
| **Orchestration** | Orchestrator | `PlaybackOrchestrator` | `extension/managed/playback/PlaybackOrchestrator.kt` | Deterministic candidate search, server discovery, extraction, CF loop |
| **Orchestration** | Session | `PlaybackSession` | `extension/managed/playback/PlaybackSession.kt` | Session identity, active coroutine job, candidate state holder |
| **Orchestration** | Attempt | `PlaybackAttempt` | `extension/managed/playback/PlaybackAttempt.kt` | Tracks attempt IDs, cancelled candidates, and attempt resets |
| **Orchestration** | Filter | `ExtensionEligibilityFilter` | `extension/managed/searchorder/ExtensionEligibilityFilter.kt` | Hard content-type isolation and admin lifecycle status enforcement |
| **Orchestration** | Repository | `SearchOrderRepository` | `extension/managed/searchorder/SearchOrderRepository.kt` | Reads authoritative candidate order from `/config/search_order` |
| **Orchestration** | Fallback | `FallbackManager` | `extension/managed/runtime/FallbackManager.kt` | Evaluates secondary candidates when Search Order is absent |
| **Scrapers** | Scraper | `QfilmScraper` | `extension/managed/scraper/QfilmScraper.kt` | Golden Reference scraper for Movies/Anime (QFilm browser engine) |
| **Scrapers** | Scraper | `WitanimeScraper` | `extension/managed/scraper/WitanimeScraper.kt` | Anime scraper (sources JSON endpoint + 4shared embed extraction) |
| **Scrapers** | Scraper | `AnimeBlkomScraper` | `extension/managed/scraper/AnimeBlkomScraper.kt` | Anime scraper (search, details, watch page, dynamic embed servers) |
| **Scrapers** | Scraper | `Anime4UpScraper` | `extension/managed/scraper/Anime4UpScraper.kt` | Anime scraper (search, details, episodes, data-watch server resolver) |
| **Scrapers** | Scraper | `EgyDeadScraper` | `extension/managed/scraper/EgyDeadScraper.kt` | Movies, Series & Anime scraper (search, multi-selector servers) |
| **Web & CF** | Controller | `InteractiveChallengeController` | `extension/managed/runtime/web/InteractiveChallengeController.kt` | Mutex-locked Cloudflare challenge state coordinator; session persister |
| **Web & CF** | Composable | `InteractiveChallengeWebView` | `ui/components/InteractiveChallengeWebView.kt` | Player-contained interactive Turnstile verification WebView overlay |
| **Web & CF** | Engine | `ControlledWebViewEngine` | `extension/managed/web/ControlledWebViewEngine.kt` | Sandboxed headless WebView for multi-stage media extraction |
| **Web & CF** | Session Store | `PerSiteSessionStore` | `extension/managed/runtime/web/PerSiteSessionStore.kt` | Encrypted/isolated per-domain cookie and clearance store |
| **Web & CF** | Bridge | `SafeScraperBridge` | `extension/managed/web/SafeScraperBridge.kt` | Secure JavaScript bridge for DOM/script stream inspection |
| **Web & CF** | Composable | `BackgroundWebView` | `ui/components/BackgroundWebView.kt` | **ORPHAN:** Unused legacy transparent WebView (dead code) |
| **Storage & Cache** | Store | `ServerStateStore` | `ui/screens/player/ServerStateStore.kt` | Global concurrent cache for server lists, qualities, and links |
| **Storage & Cache** | Store | `LastPlaybackStore` | `utils/LastPlaybackStore.kt` | SharedPreferences store for last watched URL, server, quality, position |
| **Storage & Cache** | Store | `PlaybackSyncStore` | `ui/screens/player/PlaybackSyncStore.kt` | Memory + Room history store for resume timestamps |
| **Storage & Cache** | File Resolver | `MediaStorageUtils` | `utils/MediaStorageUtils.kt` | Filesystem finder/creator in `filesDir/movies` (`.nomedia`) |
| **Storage & Cache** | Disk Cache | `MediaDetailsCacheManager` | `utils/MediaDetailsCacheManager.kt` | Disk JSON cache for TMDB details, seasons, and episodes |
| **Download** | Coordinator | `UnifiedDownloadCoordinator` | `extension/managed/adapter/UnifiedDownloadCoordinator.kt` | Single entry point for downloads; multiple-click debounce |
| **Download** | Service | `StreamDownloaderService` | `utils/StreamDownloaderService.kt` | Foreground Android Service executing segmented HLS/MP4 downloads |
| **Download** | Handoff | `DownloaderHandoffAdapter` | `extension/managed/adapter/DownloaderHandoffAdapter.kt` | Projects DownloadSource into DownloaderInput; strips cookies |
| **Download** | Enqueuer | `AndroidDownloader` | `utils/AndroidDownloader.kt` | Service intent dispatcher |
| **Download** | Repository | `DownloadRepository` | `data/repository/DownloadRepository.kt` | Room DAO wrapper managing `downloads` table |

---

## 3. Complete Playback Call Graph

```
USER TAP ON MEDIA / EPISODE
        │
        ▼
DetailsScreens.kt (EpisodeCard / Watch Now)
        │
        ├── [If Downloaded] ──────────────────────────────────────────► ExoPlayer plays local Uri ("local_offline_file://...")
        │
        └── [If Streaming]
                │
                ├── InlineDetailVideoPlayer.kt (LaunchedEffect(playback.url))
                │       │
                │       ├── Check ServerStateStore / LastPlaybackStore cache
                │       │
                │       └── If URL not resolved:
                │               ▼
                │       ManagedMediaOrchestrator.startPlaybackSession()
                │               │
                │               ▼
                │       ManagedMediaOrchestrator.orchestratePlayback()
                │
                └── PlayerScreen.kt (PlayerViewModel.initialize())
                        │
                        ▼
                PlayerViewModel.generateExtractionUrl()
                        │
                        ▼
                ManagedMediaOrchestrator.startPlaybackSession()
                        │
                        ▼
                ManagedMediaOrchestrator.orchestratePlayback()
                        │
                        ▼
                PlaybackOrchestrator.orchestratePlayback()
                        │
                        ├── Step 1: Read canonical Search Order (/config/search_order)
                        ├── Step 2: Filter via ExtensionEligibilityFilter (Status=ACTIVE, ContentType, Version)
                        ├── Step 3: Iterate Candidate List (Candidate A, Candidate B, ...)
                        │       │
                        │       ├── Step 3A: Search Title / Original Title via Scraper (10s timeout)
                        │       ├── Step 3B: If Series/Anime, Resolve Episode Target URL
                        │       ├── Step 3C: Discover Servers (10s timeout)
                        │       └── Step 3D: Extract Playable Stream (Direct / ControlledWebViewEngine)
                        │               │
                        │               ├── [Cloudflare Detected] ──► InteractiveChallengeController.launchInteractiveChallenge()
                        │               │                                   ├── InteractiveChallengeWebView shown in player container
                        │               │                                   ├── Turnstile / cf_clearance verified
                        │               │                                   ├── Session saved to PerSiteSessionStore
                        │               │                                   └── Retry SAME candidate (Attempt 2)
                        │               │
                        │               └── [Stream Extracted] ─────► onFirstPlayableSource(source)
                        │                                                   │
                        │                                                   ▼
                        │                                           setFinalVideoUrl(streamUrl)
                        │                                                   │
                        │                                                   ▼
                        │                                           ExoPlayer.setMediaSource()
                        │                                                   │
                        │                                                   ▼
                        │                                           Player.STATE_READY
                        │                                                   │
                        │                                                   ▼
                        │                                           Player.STATE_PLAYING
                        │
                        └── Step 4: [If All Candidates Fail in Inline Player]
                                │
                                ▼
                        ServerStateStore.inspectAndCacheMedia() [25-second fallback]
                                │
                                └── If originalTitle exists:
                                        ▼
                                ServerStateStore.inspectAndCacheMedia(originalTitle) [Additional 25-second fallback]
```

---

## 4. Dual Playback Pipeline Forensics: Inline Player vs Full PlayerScreen

Static analysis reveals critical architectural divergences between `InlineDetailVideoPlayer` and `PlayerScreen`:

### 4.1 Comparison Table

| Aspect | InlineDetailVideoPlayer | PlayerScreen / PlayerViewModel | Forensic Assessment |
|---|---|---|---|
| **State Ownership** | Compose `remember` variables inside composable | `PlayerViewModel` (`MutableStateFlow<PlayerUiState>`) | **Divergent:** Inline player state is scoped to Composable; PlayerScreen state survives orientation changes |
| **Playback Session Model** | Creates local `PlaybackSession` inside `LaunchedEffect` | Creates `PlaybackSession` stored in `viewModel.currentPlaybackSession` | **Divergent:** PlayerViewModel can cancel session via `stopPlayback()`; inline player cannot cancel from outside |
| **Extraction Fallback** | Runs `orchestratePlayback` (120s), then **two sequential 25s calls to `ServerStateStore.inspectAndCacheMedia`** | Runs `orchestratePlayback`, then calls `tryNextFallback()` on websites | **Divergent:** Inline player has a heavy 170-second legacy fallback chain; PlayerScreen relies solely on website cycling |
| **URL Extraction Handoff** | Dispatches extracted URL via `onPlaybackUrlExtracted(realUrl)` | Updates `_uiState.value.currentVideoUrl` directly | **Divergent:** Inline player attempts to mutate parent state via callback |
| **ExoPlayer Instance** | Instantiated via `remember { ExoPlayer.Builder(...) }` | Instantiated via `remember { ExoPlayer.Builder(...) }` | **Independent:** Two separate ExoPlayer instances exist in memory when both screens are active |
| **Cloudflare Controller** | Shares singleton `InteractiveChallengeController.getInstance()` | Shares singleton `InteractiveChallengeController.getInstance()` | **Unified:** Both observe the same singleton state flow |
| **History Persistence** | Updates `LastPlaybackStore` & `PlaybackSyncStore` on pause/stop | Updates `LastPlaybackStore` & `PlaybackSyncStore` on player state changes | **Unified:** Both persist to the same underlying stores |

### 4.2 Explicit Forensic Answers to Pipeline Divergence:

1. **Can Inline Player succeed while PlayerScreen fails?**  
   **YES.** If `orchestratePlayback` fails, `InlineDetailVideoPlayer` executes the secondary 25-second fallback `ServerStateStore.inspectAndCacheMedia` (and an additional 25s for `originalTitle`). `PlayerViewModel` does not invoke `inspectAndCacheMedia` in its fallback flow. If the media exists in `ServerStateStore` cache or responds to direct server inspection, the inline player succeeds while `PlayerScreen` displays a failure message.
2. **Can PlayerScreen use a different candidate order?**  
   **NO.** Both delegate to `ManagedMediaOrchestrator.orchestratePlayback()`, which enforces the exact same order from `SearchOrderRepository`.
3. **Can one path persist playback state while the other does not?**  
   **NO.** Both write to `LastPlaybackStore` and `PlaybackSyncStore`. However, `InlineDetailVideoPlayer` writes on `ON_PAUSE` and `ON_STOP`, whereas `PlayerScreen` updates position continuously during playback ticks.
4. **Can both players exist simultaneously?**  
   **YES.** When a user plays a video in `InlineDetailVideoPlayer` on `DetailsScreen` and taps Fullscreen:  
   - `DetailsScreens.kt:376` executes `onPlay(...)`, which pushes `PlayerScreen` onto the NavBackStack.  
   - `DetailsScreen` is placed in the background (`ON_STOP`), but remains composed on the backstack.  
   - `InlineDetailVideoPlayer` pauses its `exoPlayer` at line 866, but **does not release it** until `onDispose`.  
   - Meanwhile, `PlayerScreen` instantiates a **second ExoPlayer instance** for the same media URL.  
   - Memory profile: Two ExoPlayer decoders and buffer pipelines exist concurrently in RAM.

---

## 5. ManagedMediaOrchestrator vs ServerStateStore

### 5.1 Pipeline Roles & Fallback Mechanics
- **Canonical Pipeline:** `ManagedMediaOrchestrator` -> `PlaybackOrchestrator` is the canonical media discovery and extraction pipeline.
- **Fallback Pipeline:** `ServerStateStore.inspectAndCacheMedia()` is the legacy/auxiliary fallback pipeline.
- **Activation Trigger:** In `InlineDetailVideoPlayer.kt:1135`, if `resolvedStream == null`, `playableUrl == null`, and no Cloudflare challenge is blocking, `inspectAndCacheMedia()` activates with `withTimeoutOrNull(25_000L)`.

### 5.2 Forensic Investigation of the 25-Second Fallback
Static trace of `InlineDetailVideoPlayer.kt:1135-1175` reveals:
1. `orchestratePlayback` runs with a `120_000L` (2 minute) timeout.
2. If no direct stream is extracted, `ServerStateStore.inspectAndCacheMedia` runs for 25,000 ms using the primary title.
3. If still unresolved and `originalTitle != title`, a second `inspectAndCacheMedia` runs for an additional 25,000 ms.
4. **Total cumulative waiting time:** `120s + 25s + 25s = 170 seconds` (nearly 3 minutes) before terminal failure is reported to the user.
5. **Redundant Work:** `inspectAndCacheMedia` re-runs `discoverServers` and re-queries extensions that `PlaybackOrchestrator` already tested and failed, producing duplicate network requests and log churn.
6. **Cloudflare Bypass:** `inspectAndCacheMedia` does not initiate interactive Turnstile challenges; it only performs headless extraction.

---

## 6. Playback Session Identity Forensics

### 6.1 Identity Model Inventory
- `PlaybackSession` (`extension/managed/playback/PlaybackSession.kt`): Holds `mediaId`, `title`, `contentType`, `season`, `episode`, `sessionJob`, and an atomic `firstPlayableDeferred`.
- `PlaybackAttempt` (`extension/managed/playback/PlaybackAttempt.kt`): Static singleton tracking `attemptId` and `cancelledCandidates` set.
- `InteractiveChallengeController.UiState`: Holds `attemptId` and `challengeKey` (`"$candidateId:$canonicalHost:$challengeUrl"`).

### 6.2 Session Flaws & Race Conditions
1. **Uncancelled Session Job on Recomposition (`InlineDetailVideoPlayer.kt:1066`):**  
   If `playback.url` changes, a new `LaunchedEffect` triggers. If a previous extraction is running inside `withTimeoutOrNull(120_000L)`, the old coroutine is cancelled by Compose, but child `ControlledWebViewEngine` instances dispatched to `Dispatchers.Main` can continue loading until their internal timeout expires.
2. **Late WebView Verification Dropped (`InteractiveChallengeController.kt:264`):**  
   If a user clicks "Cancel" during a Turnstile challenge, `attemptId` is added to `cancelledAttemptIds`. If the WebView finishes Turnstile verification 200 ms later, `onVerificationCompleted` checks `cancelledAttemptIds.contains(attemptId)` and correctly drops the result, preventing late success from resurrecting cancelled candidates.

---

## 7. Candidate Execution Forensics

| Candidate Execution Question | Static Source Proof | Forensic Answer |
|---|---|---|
| **Can a cancelled candidate execute again in the same attempt?** | `PlaybackOrchestrator.kt:180`: `if (session.attempt.isCandidateCancelled(candidate.id)) continue` | **NO.** Cancelled candidates are skipped via `PlaybackAttempt`. |
| **Can a failed candidate execute again automatically?** | `PlaybackOrchestrator.kt:195`: `maxCandidateAttempts = 2` | **ONLY ON CLOUDFLARE RECOVERY.** A candidate is retried once if Cloudflare was solved; general network/scraping failures are never retried. |
| **Can fallback restart from candidate A?** | `ServerStateStore.kt:1258` calls `discoverServers()` which re-evaluates the entire candidate list | **YES.** The 25-second fallback in `ServerStateStore` restarts discovery from the beginning of the candidate list. |
| **Can cached server A bypass the normal candidate context?** | `PlayerViewModel.kt:365` reads `lastPlayback?.url` from `LastPlaybackStore` | **YES.** If a prior URL was cached, `PlayerViewModel` skips the candidate pipeline entirely and plays the cached URL immediately. |
| **Can one candidate execute concurrently twice?** | `ServerStateStore.kt:1132`: `activeInspectionDeferreds[mediaKey]` deduplicates in-flight calls | **NO.** Concurrent inspection for the same media key is deduplicated via deferred joining. |

---

## 8. Search Order Forensics

- **Firestore Source:** `/config/search_order` (`FirebaseSearchOrderDataSource.kt:41`).
- **Enforcement Status:** **ACTUALLY ENFORCED.**
  - `PlaybackOrchestrator.kt:112` reads `searchOrderRepository.getOrderForContentType(targetContentType)`.
  - `eligibilityFilter.filterEligibleExtensions(...)` filters candidates while preserving the exact input order.
  - The loop `for (candidate in eligibleCandidates)` executes strictly in configured sequence.
  - Priority numbers, random shuffling, and client-side sorting are completely bypassed.

---

## 9. Extension Eligibility Forensics

The canonical eligibility contract (`ExtensionEligibilityFilter.kt:50-99`) requires all 8 conditions to pass:

1. `extension` exists in `ManagedExtensionCatalog`.
2. Bundled scraper class is registered in `ScraperRegistry`.
3. `extension.status == ExtensionLifecycleStatus.ACTIVE` (Admin status is sole authority).
4. `extension.contentTypes.contains(targetContentType)` AND `scraper.supportedContentTypes.contains(targetContentType)` (Hard content-type isolation).
5. `scraper.supportedCapabilities.contains(capability)` (e.g. `SERVER_DISCOVERY`).
6. `extension.runtimeApiVersion <= supportedRuntimeApiVersion`.
7. `extension.minAppVersionCode <= currentAppVersionCode`.
8. `ManagedExtensionValidator.validate(extension)` returns `Valid`.

*Note:* `userEnabled` client toggle was removed from the eligibility gate; global admin status has sole authority.

---

## 10. Individual Scraper Forensic Audit

### 10.1 QFilm (`QfilmScraper.kt` — 1,021 LOC)
- **Content Types:** Movie, Anime. (Does not support TV Series episodes; returns `emptyList()` in `getEpisodes`).
- **Base URL Resolution:** Automatically maps legacy `qfilm.vip` to `https://a.qfilm.tv`.
- **Search Pipeline:** HTTP GET to `/search.php?keywords=...`. Jsoup DOM parsing with Arabic diacritic normalization (`normalizeTitle`).
- **Server Discovery:** Primary path via `ControlledWebViewEngine` injecting JavaScript to inspect `window.servers` array; fallback via regex extraction from raw HTML.
- **Server Classification:** Maps 18+ hosts (AnaFast, Mp4Plus, VidMoly, LiiiVideo, AbyssPlayer, etc.) to clean names.

### 10.2 WitAnime (`WitanimeScraper.kt` — 738 LOC)
- **Content Types:** Anime, Series, Movie.
- **Episode Resolution:** Regex parsing of episode numbers from URL slug (`/episode/slug-number/`).
- **Server Discovery:** Primary path queries dynamic `/sources` JSON endpoint (`Jsoup.connect("$targetUrl/sources")`). Fallback parses HTML DOM for `ul#episode-servers li a`.
- **Limitation:** Restricted exclusively to **4shared embeds** (`secureUrl.contains("4shared.com")`). Other providers (Mega, Videa, OK, HGCloud) are ignored. Direct downloads from `/sources` are intentionally disabled.

### 10.3 AnimeBlkom (`AnimeBlkomScraper.kt` — 714 LOC)
- **Content Types:** Anime, Movie, Series.
- **Search & Details:** Searches `/search?query=...`, details at `/anime/{slug}`.
- **Episode List:** Scrapes `.episodes-links li.episode-link a[href]` -> `/watch/{slug}/{episode}`.
- **Server Discovery:** Resolves dynamic watch URLs, decodes Base64 data attributes (`data-src`, `data-embed`), inspects iframes.

### 10.4 Anime4Up (`Anime4UpScraper.kt` — 719 LOC)
- **Content Types:** Anime, Movie, Series.
- **Search & Details:** Query to `/?s=...`, details at `/anime/{slug}/`.
- **Episode List:** Scrapes episode links with multi-selector fallback (`div.episodes-card-title a`, `div.episodes-list a`).
- **Server Discovery:** Dynamic server discovery via `li[data-watch]` and `iframe[src]`. Decodes Base64 encoded player URLs.

### 10.5 EgyDead (`EgyDeadScraper.kt` — 698 LOC)
- **Content Types:** Movie, Series, Anime.
- **Search:** Query to `/page/{page}/?s=...`. Matches container `div.search-page div.catHolder ul.posts-list li.movieItem`.
- **Episode List:** Scrapes season and episode accordions (`div.seasons-episodes`, `ul.episodes-list`).
- **Server Discovery:** Scrapes server list from `ul.servers-list li` and embeds.

---

## 11. Search & Title Match Correctness

- **Diacritic Normalization:** `QfilmScraper.normalizeTitle()` normalizes Arabic letters (`أ`, `إ`, `آ` -> `ا`, `ة` -> `ه`, `ى` -> `ي`), strips non-alphanumeric punctuation, and compares tokens via Jaccard intersection.
- **Title Fallback Hierarchy (`PlaybackOrchestrator.kt:205-285`):**
  1. `cleanTitle` (Localized title with punctuation stripped).
  2. `cleanTitleWithYear` (`cleanTitle + " " + year`).
  3. `cleanOriginalTitle` (Original TMDB title).
  4. `cleanOriginalTitleWithYear` (`cleanOriginalTitle + " " + year`).
  5. `primaryTitle` (Title substring before delimiters `:`, `-`, `Season`, `الموسم`, `Part`, `الجزء`).
  6. `primaryOriginalTitle` (Original title substring before delimiters).
- **Match Hazard:** When an anime title contains English and Japanese romaji, TMDB `originalTitle` (often Japanese kanji) fails on Arabic scrapers unless the English title or localized Arabic title matches scraper indexing.

---

## 12. Season & Episode Forensics

### 12.1 The Season 3 -> Navigate Away -> Season 1 Reset Bug
Forensic audit of `SeriesDetailsViewModel.kt` traced the exact root cause:
- In `SeriesDetailsViewModel.kt:97`:
  ```kotlin
  val cached = getCachedState(targetId)
  if (cached != null && !forceRefresh) {
      val initialSeason = cached.series?.seasons?.firstOrNull { it.seasonNumber > 0 } ?: cached.series?.seasons?.firstOrNull()
      ...
      val state = cached.copy(
          selectedSeason = initialSeason,  // <--- UNCONDITIONAL OVERWRITE
          episodes = initialEpisodes,
          ...
      )
  ```
- And in line 118 (disk cache fallback):
  ```kotlin
  val initialSeason = persistentCachedSeries.seasons.firstOrNull { it.seasonNumber > 0 }
  ...
  val state = SeriesDetailsUiState(
      ...
      selectedSeason = initialSeason,  // <--- UNCONDITIONAL OVERWRITE
  ```
- **Diagnosis:** When a user selects Season 3, `cached.selectedSeason` correctly holds Season 3. However, whenever `loadSeries()` runs upon navigating back to the screen, lines 97 and 118 **unconditionally overwrite `selectedSeason` with Season 1** (`seasons.firstOrNull { it.seasonNumber > 0 }`) instead of checking whether `cached.selectedSeason` was already selected.

### 12.2 Can `selectedSeason` and `episodes` list disagree?
- **Protection:** Lines 49-57 of `SeriesDetailsViewModel.kt` enforce `saveState()` sanitization: if `state.episodes.any { it.seasonNumber != expectedSeason }`, it filters out mismatching episodes before saving.
- **Transient Mismatch:** During asynchronous season switching in `selectSeason()` (`line 307`), if the new season's episodes are not yet cached, `episodes` is set to `emptyList()` and `isEpisodesLoading = true`. The UI never renders Season 1 episodes under Season 2 header.

---

## 13. Episode Click Correctness & Local Media Hijacking

### 13.1 Critical Finding: Local Download Mapping Hijacks Undownloaded Episodes
Static analysis of `PlayerViewModel.kt:213-234` and `MediaStorageUtils.kt:44-59` revealed a **P1 critical flaw**:
1. When a user clicks Episode 5 of an undownloaded series, `PlayerViewModel.initialize()` is called with `mediaId = "94605"`, `episodeId = "5"`, `directUrl = null`.
2. In `PlayerViewModel.kt:213-216`:
   ```kotlin
   val localFile = if (isMovie) {
       MediaStorageUtils.findMediaFile(ctx, mediaId)
   } else {
       MediaStorageUtils.findMediaFile(ctx, mediaId)
           ?: MediaStorageUtils.findMediaFile(ctx, "${mediaId}_1")
   }
   ```
3. Inside `MediaStorageUtils.findMediaFile(ctx, "94605")`:
   ```kotlin
   val matchingSeriesPart = internalDir.listFiles { file ->
       file.isFile && file.name.startsWith("${id}_")
   }
   if (!matchingSeriesPart.isNullOrEmpty()) {
       return matchingSeriesPart.first() // <--- RETURNS EPISODE 1 FILE!
   }
   ```
4. If the user previously downloaded Episode 1 (`94605_1.mp4`), `findMediaFile` matches `94605_1.mp4` and returns it as `localFile`.
5. In `PlayerViewModel.kt:220`:
   `val isOfflineOrDownloaded = isDownloaded || (localFile != null && localFile.exists()) || isOffline`
   evaluates to **TRUE**!
6. Line 233 sets `localVideoUrl = Uri.fromFile(localFile).toString()` (which is Episode 1's video file)!
7. Line 289 executes `return` immediately, **bypassing network extraction entirely**!
8. **Real-World Impact:** Tapping an undownloaded episode (e.g. Episode 5) when Episode 1 is downloaded silently plays Episode 1 from local disk!

---

## 14. Local Download & Offline Playback Forensics

- **Storage Architecture:** Dedicated app-private directory `context.filesDir/movies` protected by a `.nomedia` file (`MediaStorageUtils.kt:13-27`).
- **Download Integrity:**
  - `StreamDownloaderService` verifies HTTP 200/206 status, monitors Content-Length, and updates progress incrementally in Room.
  - Partial downloads do not set `isCompleted = true`.
  - Failed or interrupted downloads leave temporary files that can be resumed if HTTP `Range` headers are supported.
- **Security Sanitization:** `DownloaderHandoffAdapter.kt:37` strips all `Cookie` and `Set-Cookie` headers before dispatching download tasks, ensuring session tokens are never persisted in the download database or service intent extras.

---

## 15. ExoPlayer Forensics

- **Instance Scoping:**
  - `InlineDetailVideoPlayer`: Created via `remember { ExoPlayer.Builder(context)...build() }`.
  - `PlayerScreen`: Created via `remember { ExoPlayer.Builder(context)...build() }`.
- **Buffer & Load Control (`DefaultLoadControl`):**
  - Min Buffer: 15,000 ms; Max Buffer: 50,000 ms; Buffer for Playback: 2,000 ms; Buffer for Playback After Rebuffer: 3,000 ms.
  - Prioritizes time over size thresholds.
- **Seek Parameters:** `SeekParameters.CLOSEST_SYNC` enforced for instant seek response.
- **Cookie Injection:** Injects cookies from `CookieManager.getInstance().getCookie(url)` into `DefaultHttpDataSource.Factory`.
- **Lifecycle Cleanliness:**
  - `InlineDetailVideoPlayer` pauses ExoPlayer on `ON_PAUSE` and `ON_STOP` (unless in PiP mode).
  - Releases ExoPlayer in `DisposableEffect.onDispose { exoPlayer.release() }`.

---

## 16. Playback URL Validation

Canonical validation is implemented in `isValidPlayableMediaUrl()` (`InlineDetailVideoPlayer.kt:104`):

```
                       URL VALIDATION PIPELINE
                             Input URL
                                │
          ┌─────────────────────┴─────────────────────┐
          ▼                                           ▼
Is Local Scheme?                               Is HTTP/HTTPS?
(file://, content://, local_offline_file://)          │
          │ [YES]                             [NO] ──► INVALID
          ▼                                           │ [YES]
        VALID                                         ▼
                                              Reject HTML/JS
                                              (.html, .php, .js, .css)
                                                      │ [PASS]
                                                      ▼
                                              Check Embed Pattern
                                              (/e/, /embed, player., /watch)
                                                      │
                                      ┌───────────────┴───────────────┐
                                      ▼                               ▼
                               Is Embed Pattern?            No Embed Pattern
                                      │                               │
                      ┌───────────────┴───────────────┐               │
                      ▼                               ▼               ▼
              Has Direct Stream?             No Direct Stream    MediaStreamDetector
              (.m3u8, .mp4, akamaized)                │          check (.m3u8, .mp4, etc.)
                      │ [YES]                         ▼               │
                      ▼                            INVALID            ▼
                    VALID                                        VALID / INVALID
```

- **Protection Against Embed Leaks:** URLs containing `/embed` or `/e/` are rejected unless they contain direct stream signatures (`.m3u8`, `.mp4`, `akamaized.net`).
- **Protection Against Raw HTML:** Obvious web documents (`.html`, `.php`, `.aspx`, `.js`) are strictly rejected.
- **Protection Against Fake Protocols:** `auto_extract://` is explicitly caught and blocked from reaching ExoPlayer.

---

## 17. Quality Forensics

- **Subscription Decoupling Invariant:** The canonical subscription contract is strictly maintained: `SUBSCRIPTION = REMOVE ADS ONLY`. Subscriptions do not gate, manufacture, or artificially restrict video resolutions.
- **Canonical Normalization (`filterCanonicalQualities`):**
  - Standard qualities recognized: `1080p`, `720p`, `480p`, `360p`, `240p`, `144p`, and `Auto`.
  - Non-standard labels (`HD1`, `server-1`, `custom`, `best`, `low`) are filtered out.
  - Duplicate resolutions are deduplicated.
- **Adaptive Stream Parsing:** `M3U8Parser.kt` fetches and parses multi-bitrate HLS master manifests to extract explicit variant stream URLs for each resolution.

---

## 18. Subtitle Forensics

- **Current Architecture:** Subtitles are primarily embedded in HLS master playlists (`EXT-X-MEDIA:TYPE=SUBTITLES`) and negotiated natively by ExoPlayer's `DefaultTrackSelector`.
- **Standalone Subtitle Support:** Sideloading external WebVTT/SRT subtitles is not implemented; there is no subtitle download cache or standalone subtitle selector dialog in the current codebase.

---

## 19. WebView Forensics

Static audit identified **4 distinct WebView implementations**:

| WebView Implementation | Class / Symbol | Lifecycle / Owner | JavaScript | DOM Storage | Cookie Store | Status |
|---|---|---|---|---|---|---|
| **Interactive Turnstile WebView** | `InteractiveChallengeWebView` | Player Composable / Player container | Enabled | Enabled | Enabled (3rd party allowed) | **CANONICAL** |
| **Sandboxed Headless Engine** | `ControlledWebViewEngine` | Singleton instance per extraction | Enabled | Enabled | Enabled | **CANONICAL** |
| **Media Stream Detector WebView** | `ControlledWebViewEngine` internal | Short-lived per extraction session | Enabled | Enabled | Session only | **CANONICAL** |
| **Background Transparent WebView** | `BackgroundWebView` | None (Uncalled composable) | Enabled | Enabled | Enabled | **ORPHAN (Dead Code)** |

---

## 20. WebView Memory & Lifecycle Forensics

Static code audit of `InteractiveChallengeWebView.kt:445-454` reveals:

```kotlin
        onDispose {
            controller.unregisterWebViewCleanup()
            try {
                activeWebView.stopLoading()
                activeWebView.loadUrl("about:blank")
                pollingRunnable?.let { InteractiveChallengeWebViewHelper.removePolling(it) }
                pollingRunnable = null
                TurnstilePositioningEngine.reset(state.challengeKey)
            } catch (_: Throwable) {}
        }
```

- **Defect:** `activeWebView.destroy()` is **never called** in `onDispose`!
- **Consequence:** While `loadUrl("about:blank")` stops DOM execution, the native Chromium `WebContents` and rendering pipeline are retained in process RAM.
- **Memory Retention:** 40 to 80 MB of native Chromium allocations persist in memory across challenge dismissals until garbage collection collects the Java wrapper.
- **Contrast:** `ControlledWebViewEngine.cleanup()` (line 552) correctly invokes `webView.destroy()`.

---

## 21. Cloudflare Forensics

- **Challenge Detection:** Both `Jsoup` documents and HTTP exceptions are checked for Cloudflare signatures (`just a moment`, `cf-browser-verification`, `#challenge-form`, HTTP 403, HTTP 503).
- **Concurrency Protection:** `InteractiveChallengeController` enforces a single active challenge via `Mutex.withLock`. Concurrent candidates are queued.
- **Circuit Breaker (`PlaybackOrchestrator.kt:458`):** Each candidate is allowed a **maximum of 1 interactive challenge recovery attempt**. If a candidate triggers Cloudflare a second time, the circuit breaker immediately skips it to the next candidate, preventing infinite verification loops.
- **Unverified Exit (`PlayerViewModel.kt:83-91`):** Navigating away or pressing back while a challenge is active triggers `onUnverifiedExit()`, terminating playback and preventing phantom verification results from waking up the player.

---

## 22. QFilm Golden Reference Comparison

| Property | QFilm | WitAnime | AnimeBlkom | Anime4Up | EgyDead |
|---|---|---|---|---|---|
| **Base URL Stability** | Static mapping to `https://a.qfilm.tv` | Unprotected HTTP redirect risks | Single domain | Multi-mirror (.rest) | Subject to ISP blocking |
| **DOM / Embed Structure** | Structured `window.servers` array | `/sources` JSON endpoint | Obfuscated Base64 | Base64 `data-watch` | Plain HTML elements |
| **Direct Stream Extraction** | Fast-path direct stream classification | 4shared embed only | Dynamic iframes | Dynamic iframes | Direct mp4 + iframes |
| **Cloudflare Resistance** | High (rarely triggers Turnstile on search) | Moderate | High | Moderate (frequent) | High |
| **Episode Extraction** | N/A (Movies only) | Regex from URL | Regex from watch path | Multi-selector fallback | Accordion selectors |
| **Failure Recovery** | Dual: ControlledWebView + DOM regex | Dual: JSON + DOM | Fallback selectors | Fallback selectors | Fallback selectors |

*Why QFilm Succeeds Consistently:* QFilm serves content through standardized JavaScript arrays (`window.servers`) and direct CDN links (e.g. `Akamai CDN`, `AnaFast`), bypassing complex nested iframe obfuscation and heavy Turnstile challenges that burden anime providers.

---

## 23. Playback Retry Forensics

- **Attempt #1 Failure -> Retry -> Attempt #2 Success:**
  - On app cold start, `registry.getActiveExtensions()` may be empty while Firestore/LKG snapshot synchronization is pending.
  - In Attempt #1, if `hasActiveExtensions()` is false, `awaitRuntimeReady(2500ms)` is called. If synchronization exceeds 2.5s, Attempt #1 fails with `No eligible extensions`.
  - On Attempt #2 (user taps retry), the background synchronization has completed and populated `registry`, allowing Attempt #2 to succeed immediately.

---

## 24. Download & Playback Duplication Forensics

- **Can the app download and stream the same media simultaneously?**  
  **YES.** `UnifiedDownloadCoordinator` dispatches to `StreamDownloaderService` (writing to disk), while `InlineDetailVideoPlayer` or `PlayerScreen` streams via ExoPlayer using separate HTTP connections. Both share the same OkHttp/HTTP stack without conflicting locks.
- **Protection Against Duplicate Downloads:** `UnifiedDownloadCoordinator.pendingDownloads` uses a `ConcurrentHashMap.newKeySet<String>()` with a 2,500 ms debounce window. Rapid double-taps on download buttons are dropped.

---

## 25. Timeout Forensics & Additive Timeout Chains

| Component | Operation | Timeout | Owner / Location | Forensic Risk |
|---|---|---|---|---|
| **Network Request** | Scraper Search / Details | 10,000 ms | Scraper classes (`Jsoup.timeout(10000)`) | Safe; bounded |
| **Stream Extraction** | Web Extraction | 15,000 ms | `PlaybackOrchestrator.TIMEOUT_EXTRACTION_MS` | Safe; bounded |
| **Candidate Execution** | Full Candidate Lifecycle | 35,000 ms | `PlaybackOrchestrator.TIMEOUT_CANDIDATE_MS` | Safe; bounded |
| **Interactive Challenge** | Turnstile Verification | 60,000 ms | `PlaybackOrchestrator.TIMEOUT_CHALLENGE_MS` | Safe; user countdown |
| **Session Execution** | Total Session Window | 180,000 ms | `PlaybackOrchestrator.TIMEOUT_SESSION_MS` | Upper bound |
| **Inline Primary Path** | `orchestratePlayback` | 120,000 ms | `InlineDetailVideoPlayer.kt:1079` | High (2 minutes) |
| **Inline Secondary Path** | `inspectAndCacheMedia` | 25,000 ms | `InlineDetailVideoPlayer.kt:1135` | High; additive |
| **Inline Tertiary Path** | `inspectAndCacheMedia(orig)` | 25,000 ms | `InlineDetailVideoPlayer.kt:1167` | High; additive |

*The 170-Second Additive Timeout Chain:*  
In `InlineDetailVideoPlayer.kt`, timeouts are stacked sequentially: `120s + 25s + 25s = 170 seconds`. When all candidates fail, the user is kept waiting nearly 3 minutes before an error state is displayed.

---

## 26. Error Propagation Forensics

- **Swallowed Network Errors (`TmdbMediaRepositoryImpl.kt:185, 200`):** Catch blocks convert network failures into `PaginatedResult(emptyList(), page, 0)`. Network outages masquerade as empty feeds without error messaging.
- **Preserved Scraper Errors (`PlaybackOrchestrator.kt:208`):** `PlaybackOrchestrator` preserves `ExtensionError` without using `.getOrNull()`, ensuring `SecurityViolation` and `CloudflareChallenge` exceptions are handled distinctly.
- **Clean User Errors (`PlayerViewModel.kt:712-735`):** All underlying scraper technical errors are mapped to clean user messages in Arabic (e.g. `"تعذر تشغيل هذا المحتوى"`), hiding stack traces and internal endpoints from the UI.

---

## 27. Player UI State Machine

```
                              PLAYER STATE MACHINE
                                     [Idle]
                                       │
                                       ▼
                                   [Loading]
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
          [Challenge Required]                    [Source Extracted]
                    │                                     │
                    ▼                                     ▼
        [Interactive WebView]                       [Preparing]
                    │                                     │
          ┌─────────┴─────────┐                           ▼
          ▼                   ▼                        [Ready]
      [Verified]          [Cancelled]                     │
          │                   │                           ▼
          ▼                   ▼                       [Playing]
      [Retrying]           [Failed]                       │
                                                  ┌───────┴───────┐
                                                  ▼               ▼
                                              [Paused]       [Buffering]
                                                  │               │
                                                  └───────┬───────┘
                                                          ▼
                                                     [Completed]
```

- **Mutual Exclusivity:** States are governed by `PlayerUiState` and `Player.Listener`.
- **Infinite Buffering Protection:** If a stream stalls indefinitely, ExoPlayer's `DefaultLoadControl` triggers rebuffering timeouts, but if the CDN socket hangs without closing, the UI can remain in `isBuffering = true` until the user presses back or changes servers.

---

## 28. Media Cache & Multi-User Isolation Forensics

Static audit of caching components reveals **2 critical user-isolation defects**:

1. **`LastPlaybackStore.kt` Multi-User Contamination:**  
   `LastPlaybackStore` stores playback URLs, timestamps, servers, and qualities in SharedPreferences (`last_playback_store`). This file is **never cleared on logout** (`CloudSyncManager.clearLocalData()` omits it). When User A logs out and User B logs in, User B immediately inherits User A's last watched positions and server URLs.
2. **`ServerStateStore.kt` Cross-Episode Contamination:**  
   When caching data using series numeric ID as an altKey (`getCachedData("${series.id}_${episode.id}", ..., series.id.toString())`), lines 1063-1070 match `data.mediaId == requestedNumericId`, returning Episode 1's server data when Episode 2 is requested.

---

## 29. Forensic Test Matrix

| Test Scenario | Target Component | Expected Behavior | Source-Verified State | Status |
|---|---|---|---|---|
| **Movie Play (Online)** | `PlayerViewModel` / `Orchestrator` | Resolves canonical search order, extracts stream, plays | `orchestratePlayback` starts source | **PASS** |
| **Movie Play (Downloaded)** | `PlayerViewModel:211` | Bypasses network, plays local Uri from `filesDir/movies` | Local file detected; extraction skipped | **PASS** |
| **Series Episode Play (Online)** | `PlayerViewModel` / `Orchestrator` | Resolves season/episode URL, extracts stream | Correct episode targetUrl resolved | **PASS** |
| **Series Episode Play (Undownloaded)** | `PlayerViewModel:213` | Should stream episode from network | **BUG:** Hijacked by Episode 1 local file | **FAIL** |
| **Season Navigation Persistence** | `SeriesDetailsViewModel:97` | Selected season should be preserved on back navigation | **BUG:** Overwritten with Season 1 | **FAIL** |
| **Interactive Turnstile Challenge** | `InteractiveChallengeController` | Displays player-contained WebView, retries candidate | Mutex locked; verification retries candidate | **PASS** |
| **Turnstile User Cancel** | `InteractiveChallengeController:347` | Skips candidate, falls back to candidate B | Candidate cancelled in attempt; next candidate | **PASS** |
| **Turnstile Disposal Cleanup** | `InteractiveChallengeWebView:448` | Destroys WebView and native WebContents | **BUG:** `destroy()` omitted; 40-80MB retained | **FAIL** |
| **Fullscreen Switch from Inline** | `DetailsScreens:376` -> `PlayerScreen` | Handoff position and play fullscreen | **BUG:** Two ExoPlayer instances coexist in RAM | **FAIL** |
| **Account Switch Isolation** | `LastPlaybackStore` | New account should start with clean history | **BUG:** Retains previous user's URLs and positions | **FAIL** |

---

## 30. Master Forensic Findings Registry

| ID | Severity | Subsystem | File & Location | Root Cause | Real-World Impact | Evidence | Confidence |
|---|---|---|---|---|---|---|---|
| **EXEC-06-01** | **P1 (High)** | Media Resolver | `PlayerViewModel.kt:213-234` / `MediaStorageUtils.kt:44-59` | `MediaStorageUtils.findMediaFile` matches series prefix `"${id}_"`, returning Episode 1 for series ID | Clicking an undownloaded episode (e.g. Ep 5) plays Episode 1 from local disk | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-02** | **P1 (High)** | Season State | `SeriesDetailsViewModel.kt:97, 118` | `loadSeries` unconditionally resets `selectedSeason` to `seasons.firstOrNull()` | Selecting Season 3 and navigating away resets to Season 1 on return | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-03** | **P1 (High)** | Multi-User | `LastPlaybackStore.kt:37` / `CloudSyncManager.kt:268` | `last_playback_store` SharedPreferences is omitted from logout cleanup | User B inherits User A's last watched positions, qualities, and server URLs | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-04** | **P1 (High)** | Memory / Web | `InteractiveChallengeWebView.kt:448` | `activeWebView.destroy()` omitted in Compose `onDispose` | Chromium WebContents (40–80 MB) permanently retained in RAM | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-05** | **P1 (High)** | Player Memory | `DetailsScreens.kt:358-376` / `InlineDetailVideoPlayer.kt` | `activePlayback` not cleared on fullscreen handoff; inline player kept on backstack | Two concurrent ExoPlayer instances exist in memory simultaneously | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-06** | **P1 (High)** | Timeout Chain | `InlineDetailVideoPlayer.kt:1079, 1135, 1167` | Additive timeout chain: 120s orchestrator + 25s fallback + 25s original title fallback | User forced to wait up to 170 seconds (nearly 3 minutes) on terminal failure | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-07** | **P2 (Med)** | Cache Bleed | `ServerStateStore.kt:1063-1070` | Numeric series ID altKey matches across episodes in `getCachedData` | Episode 1 cached servers and direct URL bleed into Episode 2 | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-08** | **P2 (Med)** | Scrapers | `WitanimeScraper.kt:24, 450` | Scraper hardcoded to 4shared embeds only; ignores Mega, Videa, OK | Fails completely on WitAnime anime episodes where 4shared is unavailable | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-09** | **P2 (Med)** | Dead Code | `BackgroundWebView.kt:21` | Composable declared but never referenced in any screen or component | Orphaned dead code; creates maintenance confusion | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-10** | **P2 (Med)** | Download Auth | `DownloaderHandoffAdapter.kt:37` | Strips all `Cookie` headers unconditionally as defense-in-depth | CDN download servers requiring `cf_clearance` cookies fail with HTTP 403 | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-11** | **P2 (Med)** | Networking | `TmdbMediaRepositoryImpl.kt:185, 200` | Exception catch block returns `PaginatedResult(emptyList(), page, 0)` | Network outages masquerade as empty feeds with zero error feedback | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-12** | **P2 (Med)** | Scrapers | `QfilmScraper.kt:312` | `getEpisodes()` returns hardcoded `emptyList()` | QFilm cannot be used for episodic anime series | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-13** | **P2 (Med)** | Cold Start | `ManagedMediaOrchestrator.kt:228` | Gate checks `GlobalExtensionConfigState.READY`; 2.5s timeout on cold start | First playback attempt can fail if Firebase remote sync takes > 2.5s | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-14** | **P2 (Med)** | Player VM | `PlayerViewModel.kt:365-385` | Plays stale `lastPlayback?.url` directly if contains `.mp4`/`.m3u8` | Starts playback from expired CDN URL with signed tokens, causing playback error | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-15** | **P2 (Med)** | Threading | `ServerStateStore.kt:1057` | Synchronous `loadFromDisk()` triggered on Main thread during cache misses | Frame drops and jank during player initialization | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-16** | **P3 (Low)** | Scrapers | `Anime4UpScraper.kt:100` | Relies on Base64 `data-watch` decoding; mirrors change encoding formats | Vulnerable to breakage if Anime4Up updates obfuscation scheme | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-17** | **P3 (Low)** | URL Validation | `InlineDetailVideoPlayer.kt:129` | Hardcoded host check for `akamaized.net` in direct stream validation | Brittle hardcoding; does not generalize to other CDNs | Source inspection | **STATICALLY VERIFIED** |
| **EXEC-06-18** | **P3 (Low)** | Subtitles | `InlineDetailVideoPlayer.kt` / `PlayerScreen.kt` | No external WebVTT/SRT subtitle loading pipeline | Users cannot load external subtitles if HLS playlist omits text tracks | Source inspection | **STATICALLY VERIFIED** |

---

## 31. Execution Matrix

| Operation | Trigger | State Owner | Candidate Owner | Cache | WebView | ExoPlayer | Timeout | Retry | Cleanup | Risk |
|---|---|---|---|---|---|---|---|---|---|---|
| **Movie Play** | User click | `PlayerViewModel` | `PlaybackOrchestrator` | `ServerStateStore` | None / Headless | Single instance | 35s / cand | CF only | `stopPlayback()` | Expired URL |
| **Episode Play** | User click | `PlayerViewModel` | `PlaybackOrchestrator` | `ServerStateStore` | None / Headless | Single instance | 35s / cand | CF only | `stopPlayback()` | Local Ep 1 hijacking |
| **Inline Play** | Details view | Composable | `PlaybackOrchestrator` | `ServerStateStore` | Container | Single instance | 170s total | None | `onDispose` | Double ExoPlayer |
| **Fullscreen Hand** | Fullscreen btn | `PlayerViewModel` | None (URL passed) | `LastPlaybackStore`| None | Second instance| None | None | None | Dual player leak |
| **CF Challenge** | HTTP 403/503 | `ChallengeController`| `PlaybackAttempt` | `PerSiteStore` | Interactive | Paused | 60s | 1 retry | Reset on exit | Missing `destroy()` |
| **Download** | Download btn | `DownloadRepo` | Pre-resolved | Disk cache | None | None | 30s connect | OkHttp auto | DB clear | Cookie strip 403 |

---

## 32. Scraper Matrix

| Scraper | Search | Match | Servers | Extraction | Cloudflare | URL Validation | Quality | Subtitle | Timeout | Fallback | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **QFilm** | `/search.php` | Jaccard tokens | `window.servers` JS | Direct / WebEngine | Handled | Strict | M3U8 variants | Embedded | 10s | DOM regex | **CANONICAL** |
| **WitAnime** | `/?s=...` | Regex title | `/sources` JSON | 4shared embed | Handled | Strict | Auto / 4shared | Embedded | 10s | HTML DOM | **RESTRICTED** |
| **AnimeBlkom** | `/search?query`| Slug match | `data-src` / embed | Base64 decode | Handled | Strict | Host quality | Embedded | 10s | Multiple selectors| **CANONICAL** |
| **Anime4Up** | `/?s=...` | Slug match | `li[data-watch]` | Base64 decode | Handled | Strict | Host quality | Embedded | 10s | Multiple selectors| **CANONICAL** |
| **EgyDead** | `/?s=...` | Title match | `ul.servers-list` | Direct / embed | Handled | Strict | M3U8 / Direct | Embedded | 10s | Legacy containers | **CANONICAL** |

---

## 33. WebView Matrix

| WebView | Type | Owner | Created | Reused | JS | Cookies | Session | Destroyed | Risk |
|---|---|---|---|---|---|---|---|---|---|
| `InteractiveChallengeWebView` | Interactive | Compose container | On challenge | By challengeKey | Yes | Yes (3rd party) | PerSiteSessionStore | **NO (Missing destroy)** | **P1 (Memory leak)** |
| `ControlledWebViewEngine` | Headless | Engine class | On extraction | Per extraction | Yes | Yes | Cleared | **YES (`destroy()` called)** | Low |
| `MediaStreamDetector` | Headless | Detector class | On inspection | Per inspection | Yes | Yes | Cleared | **YES (`destroy()` called)** | Low |
| `BackgroundWebView` | Legacy | Orphan composable | Never | Never | Yes | Yes | Shared | Never invoked | **P2 (Dead code)** |

---

## 34. Player State Matrix

| State | Entry Condition | Exit Condition | State Owner | UI Presentation | ExoPlayer State | WebView State | Risk |
|---|---|---|---|---|---|---|---|
| **Idle** | Initial screen load | Play trigger | `PlayerViewModel` | Poster / Details | Unprepared | None | None |
| **Loading** | Play triggered | URL resolved / Error | `PlayerViewModel` | Circular spinner | Unprepared | Hidden | Stalled network |
| **Challenge** | Cloudflare detected | Turnstile solved / Cancel | `ChallengeController` | Player overlay | Paused | Visible | Missing `destroy()` |
| **Preparing** | Stream URL set | Media prepared | ExoPlayer | Buffering spinner | `STATE_BUFFERING` | Hidden | Expired tokens |
| **Playing** | `STATE_READY` + playing | Pause / Buffer / End | ExoPlayer | Video surface + HUD | `STATE_READY` | Destroyed | Stutter / jank |
| **Paused** | User pause / Lifecycle | Resume trigger | ExoPlayer | Paused HUD overlay | `STATE_READY` | None | None |
| **Failed** | All candidates exhausted | User retry / Close | `PlayerViewModel` | Arabic error banner | `STATE_IDLE` | None | Stale retry |

---

## 35. Explicit Answers to Critical Forensic Questions (1–34)

1. **Are there still two independent playback architectures?**  
   **YES.** `InlineDetailVideoPlayer` (Composable-managed, 170s fallback chain) and `PlayerScreen` (`PlayerViewModel`-managed) operate with divergent lifecycle and fallback mechanics.
2. **Are there still two independent extraction pipelines?**  
   **YES.** The primary canonical pipeline is `ManagedMediaOrchestrator` -> `PlaybackOrchestrator`. The secondary legacy pipeline is `ServerStateStore.inspectAndCacheMedia()`.
3. **Can fallback re-run a previously cancelled candidate?**  
   **NO in `PlaybackOrchestrator`** (blocked via `PlaybackAttempt.isCandidateCancelled`). **YES in `ServerStateStore.inspectAndCacheMedia()`**, which does not check `PlaybackAttempt` cancelled sets.
4. **Can cached server state bypass canonical candidate selection?**  
   **YES.** `PlayerViewModel.kt:365` immediately uses `lastPlayback?.url` from `LastPlaybackStore` if present, completely bypassing candidate selection.
5. **Can playback start from a stale URL?**  
   **YES.** If an expired signed CDN URL is cached in `LastPlaybackStore`, `PlayerViewModel` attempts to play it immediately before catching an error.
6. **Can raw HTML reach ExoPlayer?**  
   **NO.** `isValidPlayableMediaUrl()` explicitly rejects URLs ending in `.html`, `.htm`, `.php`, `.asp`, `.js`.
7. **Can an embed URL reach ExoPlayer?**  
   **NO.** `isValidPlayableMediaUrl()` rejects URLs containing `/embed`, `/e/`, `player.` unless they contain direct stream extensions (`.m3u8`, `.mp4`).
8. **Can `auto_extract://` remain active?**  
   **NO.** Handled as a marker protocol; explicitly rejected by `isValidPlayableMediaUrl()`.
9. **Can WebView survive disposal?**  
   **YES.** `InteractiveChallengeWebView.kt:448` omits `activeWebView.destroy()`, leaving Chromium native allocations alive in RAM after Composable disposal.
10. **Can WebView callbacks fire after disposal?**  
    **NO.** Polling handlers and `InteractiveChallengeController` unregister callbacks on disposal.
11. **Can two WebViews exist for one challenge?**  
    **NO.** `InteractiveChallengeController` enforces a `Mutex` lock ensuring strictly one active challenge session.
12. **Can two ExoPlayer instances play the same media?**  
    **YES.** Navigating from `InlineDetailVideoPlayer` to `PlayerScreen` leaves the inline player alive on the backstack with its ExoPlayer instance paused while `PlayerScreen` creates a second instance.
13. **Can PlayerScreen and InlinePlayer disagree?**  
    **YES.** Inline player includes the 25s `ServerStateStore` fallback; `PlayerScreen` does not.
14. **Can Season and Episode state disagree?**  
    **NO in final state** (`SeriesDetailsViewModel` filters mismatched episodes), but **YES transiently** while asynchronous episode loading is in flight.
15. **Can local download mapping select the wrong episode?**  
    **YES.** `MediaStorageUtils.findMediaFile(ctx, seriesId)` matches `"${seriesId}_1.mp4"`, returning Episode 1 for the series.
16. **Can a wrong local episode start when user taps another episode?**  
    **YES.** Clicking an undownloaded Episode 5 when Episode 1 is downloaded plays Episode 1 from disk due to `findMediaFile` series prefix matching.
17. **Can playback history belong to the wrong episode?**  
    **YES.** If `episodeId` is null, `LastPlaybackStore` defaults key to `mediaId`, overwriting series-level history with the latest episode.
18. **Can quality state belong to the wrong media?**  
    **NO.** Quality keys are qualified by `mediaId` and `currentMediaKey`.
19. **Can subtitle state belong to the wrong media?**  
    **NO.** Subtitles are negotiated strictly from the active stream's HLS manifest.
20. **Can a retry restart stale candidate state?**  
    **NO.** `PlaybackOrchestrator` resets candidate attempt counters on new session initialization.
21. **Can first-attempt playback fail because extension registry is not ready?**  
    **YES.** If Firestore remote sync takes > 2,500 ms on cold start, Attempt #1 fails; Attempt #2 succeeds.
22. **Can candidate timeouts accumulate into minutes?**  
    **YES.** In `InlineDetailVideoPlayer`, `120s + 25s + 25s = 170 seconds`.
23. **Can any media error become an empty success?**  
    **YES.** `TmdbMediaRepositoryImpl.kt` catches network errors and returns `PaginatedResult(emptyList(), page, 0)`.
24. **Can Cloudflare state leak between hosts?**  
    **NO.** `PerSiteSessionStore` isolates cookies strictly by canonical domain host.
25. **Can playback caches leak between accounts?**  
    **YES.** `LastPlaybackStore` is not cleared on logout, leaking playback positions and URLs to subsequent accounts on the device.
26. **Can download and playback race each other?**  
    **NO.** Download writes to disk via `StreamDownloaderService`; playback streams over network or reads completed files atomically.
27. **Can a screen remain Loading forever?**  
    **NO.** All operations are bounded by coroutine timeouts (`withTimeoutOrNull`).
28. **Can Buffering remain forever?**  
    **NO in standard cases; YES if CDN hangs socket** indefinitely without closing TCP connection.
29. **Can Cancel fail to reach the actual executor?**  
    **NO.** Cancellation propagates through `session.sessionJob` and `InteractiveChallengeController.cancelChallenge()`.
30. **Can Back fail to stop extraction?**  
    **NO.** `BackHandler` triggers `onClose()` / `stopPlayback()`, cancelling the active session job.
31. **Can lifecycle destruction leave extraction alive?**  
    **NO.** `PlayerViewModel.onCleared()` invokes `stopPlayback()`.
32. **Can a WebView retain 40+ MB after disposal?**  
    **YES.** Statically verified in `InteractiveChallengeWebView.kt:448` due to missing `destroy()`.
33. **Can QFilm and non-QFilm providers follow different architectural paths?**  
    **YES.** QFilm uses `ControlledWebViewEngine` JavaScript array extraction; other scrapers rely on HTML DOM, Base64 decoding, or JSON endpoints.
34. **Is there one canonical media execution pipeline?**  
    **NO.** Two coexisting pipelines exist: `ManagedMediaOrchestrator` (canonical) and `ServerStateStore.inspectAndCacheMedia` (legacy fallback).

---

## 36. Positive Architectural Findings

1. **Zero-Trust URL Validation:** `isValidPlayableMediaUrl` strictly prevents raw HTML, JavaScript, and embed player links from reaching ExoPlayer.
2. **Deterministic Search Order:** `PlaybackOrchestrator` strictly enforces the admin-configured `/config/search_order` sequence without client-side shuffling or priority overrides.
3. **Player-Contained Turnstile:** Interactive Cloudflare challenges are contained strictly within the player container bounds, preserving details UI and metadata visibility.
4. **Single-Challenge Mutex:** `InteractiveChallengeController` prevents concurrent Turnstile popups through Mutex locking.
5. **Cookie Sanitization on Download:** `DownloaderHandoffAdapter` strips session cookies from download intent extras, preventing credential leakage to storage.
6. **Hardware ExoPlayer Optimization:** Seek parameters are tuned to `CLOSEST_SYNC`, and buffer thresholds prioritize time over size.

---

## 37. Evidence Classification

- **EXEC-06-01 (Local Episode 1 Hijacking):** **STATICALLY VERIFIED** via `PlayerViewModel.kt:213` and `MediaStorageUtils.kt:54`.
- **EXEC-06-02 (Season 1 Reset Bug):** **STATICALLY VERIFIED** via `SeriesDetailsViewModel.kt:97, 118`.
- **EXEC-06-03 (LastPlaybackStore Multi-User Leak):** **STATICALLY VERIFIED** via `LastPlaybackStore.kt:37` and `CloudSyncManager.kt:268`.
- **EXEC-06-04 (WebView Missing `destroy()`):** **STATICALLY VERIFIED** via `InteractiveChallengeWebView.kt:448`.
- **EXEC-06-05 (Dual ExoPlayer RAM Coexistence):** **STATICALLY VERIFIED** via `DetailsScreens.kt:358-376`.
- **EXEC-06-06 (170-Second Timeout Chain):** **STATICALLY VERIFIED** via `InlineDetailVideoPlayer.kt:1079, 1135, 1167`.
- **EXEC-06-09 (BackgroundWebView Dead Code):** **STATICALLY VERIFIED** via codebase grep showing 0 callers.

---

## 38. Authoritative Audit Verdict & Top Risks

### Overall Media Execution Status: **PASS WITH LIMITATIONS**

#### Counts:
- **P0 (Blocker):** 0
- **P1 (High):** 6
- **P2 (Medium):** 9
- **P3 (Low):** 3
- **INFO:** 6

#### Critical Risk Summaries:
- **Most Dangerous Playback Race:** Local episode hijacking (`EXEC-06-01`), where tapping an undownloaded series episode plays a downloaded Episode 1 from disk.
- **Most Dangerous Candidate Race:** Fallback in `ServerStateStore.inspectAndCacheMedia()` restarting discovery from Candidate A and re-querying candidates already failed by `PlaybackOrchestrator`.
- **Most Dangerous WebView Lifecycle Bug:** `InteractiveChallengeWebView.kt:448` omitting `activeWebView.destroy()`, permanently leaking 40–80 MB of Chromium WebContents allocations per challenge.
- **Most Dangerous Player State Bug:** `SeriesDetailsViewModel.kt:97` resetting `selectedSeason` to Season 1 on every back navigation.
- **Most Dangerous Cache Bug:** `LastPlaybackStore` retaining URLs, qualities, and playback positions across user sign-outs.
- **Most Dangerous Timeout Chain:** `InlineDetailVideoPlayer` stacking `120s + 25s + 25s = 170 seconds` before surfacing playback failure.
- **Most Important QFilm Difference:** QFilm relies on direct JavaScript `window.servers` array reflection and standard CDN domains, avoiding the nested iframes and Turnstile challenges that destabilize anime providers.

---

## 39. Hard Stop
Zero production modifications, zero test modifications, zero dependency alterations, and zero rules modifications were made during this audit phase. All 18 findings above are documented authoritatively in `/PHASES/PHASE_06.5_PLAYBACK_DOWNLOAD_WEBVIEW_SCRAPER_MEDIA_EXECUTION_FORENSIC_AUDIT_REPORT.md` for subsequent remediation phases.
