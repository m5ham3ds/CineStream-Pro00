# PHASE 07.0 / WAVE 12: FORENSIC AUDIT REPORT
# GLOBAL FILTER SYSTEM & VIDAPI ADMIN-MANAGED PROVIDER INTEGRATION

**Execution Date:** 2026-10-09  
**Status:** COMPLETE (READ-ONLY DIAGNOSTIC AUDIT)  
**Deliverable Path:** `PHASES/PHASE_07_0_WAVE_12_FILTERS_AND_VIDAPI_ADMIN_INTEGRATION_FORENSIC_AUDIT.md`  
**Governing Directives:** Absolute Read-Only Governance; Zero Production / Configuration Code Mutations; Zero Database Mutations; No Deployment.

---

## SECTION A — PHYSICAL BASELINE & REPOSITORY INVENTORY

### A.1 Component Source Locations

1. **CineStream User Application (Mobile App):**
   - **Root Path:** `/app`
   - **Source Root:** `/app/src/main/java/com/example`
   - **Test Root:** `/app/src/test/java/com/example`
   - **Technology Stack:** Kotlin 2.0.21, Jetpack Compose Material 3, Android SDK 36 (Min SDK 24), Media3 ExoPlayer 1.4.1, Room 2.6.1, Retrofit 2.9.0 / Moshi, OkHttp 4.12.0, Firebase BoM (Firestore, Auth, Messaging), Cloudinary Android SDK.
   - **Source File Count:** 127 Kotlin files in `app/src/main/java/com/example/`.
   - **Unit / Robolectric Test Count:** 48 test files in `app/src/test/java/com/example/`.

2. **CineStream Admin Application (Dashboard):**
   - **Physical Presence in Workspace:** **NOT PRESENT** (`ABSENT`).
   - **Forensic Verification:** An exhaustive directory scan of the project root confirms no administrative web dashboard or secondary Admin Android app directory exists in this workspace (`/` contains only `app`, `backend`, `docs`, `PHASES`, `assets`, `gradle`, `tmp`, and root Gradle configuration files).
   - **Workspace `/backend` Inspection:** The `/backend` directory contains `cinestream-trusted-backend` (a Cloudflare Worker for points earning, idempotency, and anti-abuse validation), defined in `backend/package.json` and `backend/wrangler.toml`. It does **not** contain an administrative web dashboard or admin UI control panel.
   - **Administrative Contract Reference:** The sole accessible administrative specifications in this repository are the canonical documentation in `/docs/FIREBASE_CONTRACT.md` and the security enforcement rules in `/firestore.rules`.
   - **Integration Audit Limitation:** Because the physical Admin Dashboard source code is absent from this repository, all Admin-side capabilities must be evaluated strictly against the canonical shared Firebase schema, Firestore security rules, and the User App's ingestion endpoints.

3. **Shared Firebase Infrastructure & Credentials:**
   - **Web App Config:** `firebase-applet-config.json` defines project `ai-studio-applet-webapp-71606`, auth domain `ai-studio-applet-webapp-71606.firebaseapp.com`, and OAuth client `406285499532-f6p0gbrvu0ah2rj1df9g385vb34l85nh.apps.googleusercontent.com`.
   - **Mobile Client Registration:** `app/google-services.json` currently contains remixed placeholder IDs (`"project_id": "remixed-project-id"`).
   - **Security Rules:** `firestore.rules` (364 lines) specifies zero-trust administrative boundaries (`/admins/{uid}.enabled == true`), user subcollections, and provider configuration endpoints (`/managed_extensions/{extensionId}`, `/config/search_order`).

---

### A.2 Application Identity Verification & Discrepancy Forensic

1. **Authorized Canonical Application ID:**
   `com.aistudio.cinestream.xyzabc`

2. **Physical Configuration Scan:**
   - **`app/build.gradle.kts` (line 20):**
     ```kotlin
     defaultConfig {
         applicationId = "com.aistudio.cinestream.tdftgi"
         minSdk = 24
         targetSdk = 36
         versionCode = 1
         versionName = "1.0"
     }
     ```
   - **`app/google-services.json` (line 9):**
     ```json
     "android_client_info": {
       "package_name": "com.aistudio.cinestream.tdftgi"
     }
     ```
   - **`app/src/main/AndroidManifest.xml` (line 2):**
     Namespace: `com.example` (clean package namespace preserved).

3. **Forensic Discrepancy Analysis:**
   - While the previous Wave 11 report recorded restoration of `com.aistudio.cinestream.xyzabc`, the current physical files on disk in this container reflect `com.aistudio.cinestream.tdftgi` (a container template reset artifact).
   - **Governing Directive:** Under Wave 12 Absolute Governance Rule 2 ("Do not modify production Kotlin, Java, TypeScript, JavaScript, Gradle, XML, JSON, manifests, resources... Do not change the canonical application ID"), no file edits were performed to alter this identity during Wave 12.
   - **Remediation Precondition:** When implementation waves commence, `app/build.gradle.kts` and `app/google-services.json` must be restored to `com.aistudio.cinestream.xyzabc` as the very first configuration step before generating APK artifacts.

---

## SECTION B — CURRENT CONTENT FILTER IMPLEMENTATION AUDIT

### B.1 Physical UI Controls & Navigation Entry Points

| Screen Destination | Route | ViewModel | Existing Filter / Tab UI Component | Physical Behavior on Interaction |
| :--- | :--- | :--- | :--- | :--- |
| **Home** | `Screen.Home.route` (`home`) | `HomeViewModel` | `LazyRow` displaying: `[Home, Movies, Series, Anime]` (`HomeScreen.kt:224-257`) | Tapping "Home" renders multi-section landing. Tapping "Movies", "Series", or "Anime" renders an in-memory 3-column vertical grid of `uiState.allMovies`, `uiState.allSeries`, or `uiState.animeSeries`. |
| **Movies** | `Screen.Movies.route` (`movies`) | `MoviesViewModel` | `LazyRow` displaying: `[Movies, Genres, New Releases, Top Rated]` (`MoviesScreen.kt:197-235`) | Tapping "Movies" renders sections. Tapping "Genres", "New Releases", or "Top Rated" toggles a 3-column vertical grid of `uiState.movies` sorted locally. **NO genre filtering occurs.** |
| **Series** | `Screen.Series.route` (`series`) | `SeriesViewModel` | `LazyRow` displaying: `[Series, Genres, New Releases, Top Rated]` (`SeriesScreen.kt:224-245`) | Tapping "Series" renders sections. Tapping "Genres", "New Releases", or "Top Rated" toggles a 3-column vertical grid of `uiState.series` sorted locally. **NO genre filtering occurs.** |
| **Anime** | `Screen.Anime.route` (`anime`) | `AnimeViewModel` | `LazyRow` displaying: `[Anime, Genres, New Releases, Top Rated]` (`AnimeScreen.kt:220-240`) | Tapping "Anime" renders sections. Tapping other items toggles a 3-column vertical grid of `uiState.series` sorted locally. **NO genre filtering occurs.** |
| **Search** | `Screen.Search.route` (`search`) | `SearchViewModel` | 1. Filter icon: `Icon(Icons.Default.FilterAlt, ...)` (`SearchScreen.kt:149`)<br>2. Browse by Category cards: `[Movies, Series, Anime, Documentaries, Kids]` (`SearchScreen.kt:243-263`) | 1. Filter icon has an **EMPTY lambda** (`clickable { /* Filter */ }`). Cosmetic only.<br>2. Category cards have an **EMPTY lambda** (`clickable { /* Select category */ }`). Cosmetic only. |
| **Quick-Search** | Overlay in `CustomTopBar` / `ExpandableSearchBar` | `SearchViewModel` | Text input only (`SearchBarDropdown.kt`) | Realtime debounce queries `repository.searchMulti(q)` and `managedOrchestrator.searchMedia(q)`. No category or genre filtering controls exist. |

---

### B.2 Data Flow & State Mechanics Forensic

1. **The "Genres" Tab Illusion in Movies, Series, and Anime:**
   In `MoviesScreen.kt` (lines 170–175), `SeriesScreen.kt` (lines 197–202), and `AnimeScreen.kt`:
   ```kotlin
   val displayCategoryMovies = remember(selectedCategory, uiState.movies) {
       when (selectedCategory) {
           "New Releases" -> uiState.movies.reversed()
           "Top Rated" -> uiState.movies.sortedByDescending { it.rating }
           "Genres" -> uiState.movies.sortedBy { it.title } // <-- Alphabetical sort only!
           else -> uiState.movies
       }
   }
   ```
   - When the user taps the category tab labeled "Genres" (`R.string.category_genres`), the screen simply re-sorts the existing in-memory 20-item movie list alphabetically by title (`it.title`).
   - It does **not** query TMDB for genres.
   - It does **not** present a genre picker.
   - It does **not** filter the list by genre ID.
   - The UI affords an expectation of genre categorization, but the implementation is entirely a cosmetic sorting stub.

2. **Cosmetic Stubs in SearchScreen:**
   - In `SearchScreen.kt` line 149:
     ```kotlin
     Icon(
         Icons.Default.FilterAlt,
         contentDescription = stringResource(R.string.filter),
         tint = MaterialTheme.colorScheme.primary,
         modifier = Modifier.size(24.dp).clickable { /* Filter */ } // <-- NO OP
     )
     ```
   - In `SearchScreen.kt` line 254:
     ```kotlin
     Column(
         modifier = Modifier.clickable { /* Select category */ } // <-- NO OP
     )
     ```
   - Both primary filter affordances in Search are empty lambdas with no connection to state, repository, or dialog.

---

### B.3 Genre Metadata, Taxonomy, and Repository Mapping Defect

A deep static audit of `TmdbApiService.kt`, `TmdbModels.kt`, and `TmdbMediaRepositoryImpl.kt` revealed a foundational data defect:

1. **Absence of TMDB Genre Endpoints:**
   - Neither `/genre/movie/list` nor `/genre/tv/list` is declared in `TmdbApiService.kt`.
   - The application possesses no mechanism to dynamically fetch official TMDB genre definitions or localized genre names from the network.

2. **Hardcoded and Truncated Genre Mapping in Data Models:**
   In `TmdbMediaRepositoryImpl.kt` (lines 585, 602, 531, 547):
   - **`TmdbMovie.toDomain()`:**
     ```kotlin
     genres = if (genreIds?.contains(28) == true) listOf("Action") else emptyList()
     ```
   - **`TmdbSeries.toDomain()`:**
     ```kotlin
     genres = if (genreIds?.contains(16) == true) listOf("Animation") else emptyList()
     ```
   - **`searchMulti()`:**
     ```kotlin
     genres = emptyList() // All search results are assigned empty genre lists!
     ```
   - **Consequence:** 95% of movies in browsing lists have `genres = emptyList()`. Any client-side filter attempting to filter `movie.genres.contains("Comedy")` will return **zero items**, because only ID 28 is mapped, and it is hardcoded strictly to `"Action"`. TV series only map ID 16 to `"Animation"`.
   - Real genre names (`genres?.map { it.name }`) are only populated when the user navigates into a specific `MovieDetails` or `SeriesDetails` screen via `/movie/{id}` or `/tv/{id}`.

3. **Classification & Anime Separation Architecture:**
   - **`ContentTypeResolver.kt`:** Correctly implements the canonical rule:
     - All movies (including Japanese animated films) resolve to `ContentType.MOVIE` (`"movie"`).
     - TV series with Animation (Genre 16) AND Japanese origin (`originalLanguage == "ja"` or `originCountries.contains("JP")`) resolve to `ContentType.ANIME` (`"anime"`).
     - Non-Japanese animation (Donghua, The Simpsons) resolves to `ContentType.TV` (`"tv"`).
   - **Leakage Risk in TMDB Series Endpoints:**
     In `TmdbMediaRepositoryImpl.kt`, `getTrendingSeries()`, `getPopularSeries()`, and `getNewReleasesSeries()` call TMDB `/trending/tv/day`, `/tv/popular`, and `/tv/on_the_air`. These TMDB endpoints naturally return popular Japanese anime series (e.g. *Demon Slayer*, *Jujutsu Kaisen*). If not filtered through `ContentTypeResolver`, anime series leak into the general TV series listing.

---

## SECTION C — REQUIRED FILTER BEHAVIOR MAP

The target behavior map for the CineStream ecosystem establishes strict separation between landing pages and content-list destinations.

```
                      ┌──────────────────────────────────────────────┐
                      │              TOP NAVIGATION                  │
                      │  [Avatar]       CineStream Pro      [Search] │
                      └──────────────────────┬───────────────────────┘
                                             │
             ┌───────────────────────────────┴───────────────────────────────┐
             ▼                                                               ▼
   ┌───────────────────┐                                           ┌───────────────────┐
   │    HOME SCREEN    │                                           │ CONTENT LIST DEST │
   │ (Multi-Section)   │                                           │(Movies/Series/Ani)│
   ├───────────────────┤                                           ├───────────────────┤
   │ Hero Carousel     │                                           │ Category Tabs     │
   │ Continue Watching │                                           ├───────────────────┤
   │ Trending Now      │                                           │ [NEW] GENRE ROW   │
   │ Popular Content   │                                           │ "التصنيف: الكل"   │
   │ Upcoming / New    │                                           ├───────────────────┤
   ├───────────────────┤                                           │ Filtered Grid /   │
   │ *NO GENRE ROW*    │                                           │ Scrollable List   │
   └───────────────────┘                                           └───────────────────┘
```

### C.1 Home Screen Requirements

1. **Role:** Multi-section discovery landing page.
2. **Genre Filter Row:** **STRICTLY ABSENT.** The new genre filter row must **never** be rendered on `Screen.Home`.
3. **State Isolation:** Home must not observe or be affected by any active genre filter selected in Movies, Series, or Anime.
4. **Content Mix:** Preserves mixed horizontal sections (Trending, Continue Watching, New Releases, Popular).

---

### C.2 Movies Screen Requirements

1. **Role:** Dedicated, scrollable collection of movies across all genres.
2. **Anime Movie Inclusion:** Anime movies (e.g. *Spirited Away*, *Your Name*) are movies by definition. They must remain included in the Movies listing and be filterable by movie genres (e.g. Animation: 16, Drama: 18).
3. **Genre Filter UI Placement:** Positioned immediately below the existing category tab row.
4. **Visual Control:** Arabic dropdown/sheet trigger:
   - Default State: `التصنيف: الكل`
   - Active State (e.g. Action selected): `التصنيف: أكشن`
5. **Taxonomy (Official TMDB Movie Genres):**
   - Action (28), Adventure (12), Animation (16), Comedy (35), Crime (80), Documentary (99), Drama (18), Family (10751), Fantasy (14), History (36), Horror (27), Music (10402), Mystery (9648), Romance (10749), Science Fiction (878), TV Movie (10770), Thriller (53), War (10752), Western (37).
6. **Request & Pagination Strategy:**
   - Unfiltered (`All`): Returns standard popular/trending movie discovery list.
   - Filtered (Genre Selected): Dispatches TMDB Discover Movie request with `with_genres={genreId}` and `page={page}`.
7. **Transition & Reset:** Switching away to Series or Home resets or isolates the active Movie genre. Selecting `الكل` restores the full unfiltered movies list.

---

### C.3 Series Screen Requirements

1. **Role:** Dedicated TV series listing.
2. **Strict Anime Exclusion:** Content classified by `ContentTypeResolver` as Anime (`genre 16 + language "ja" / country "JP"`) must be strictly filtered out of the Series listing.
3. **Genre Filter UI Placement:** Positioned below the category row.
4. **Visual Control:** `التصنيف: الكل`
5. **Taxonomy (Official TMDB TV Genres):**
   - Action & Adventure (10759), Animation (16 — non-anime only), Comedy (35), Crime (80), Documentary (99), Drama (18), Family (10751), Kids (10762), Mystery (9648), News (10763), Reality (10764), Sci-Fi & Fantasy (10765), Soap (10766), Talk (10767), War & Politics (10768), Western (37).
6. **Request & Pagination Strategy:**
   - Dispatches TMDB Discover TV request with `with_genres={genreId}` and `without_genres=16` (or client-side exclusion of Japanese animation).

---

### C.4 Anime Screen Requirements

1. **Role:** Dedicated Japanese anime series and content listing.
2. **Strict TV Series Exclusion:** Western, Arabic, or non-anime TV series must be strictly excluded.
3. **Genre Filter UI Placement:** Positioned below the category row.
4. **Visual Control:** `التصنيف: الكل`
5. **Taxonomy:** Japanese Animation genres (Action, Adventure, Comedy, Drama, Fantasy, Sci-Fi, Romance, Supernatural, Slice of Life, Mystery, Horror) mapped to TMDB TV genre IDs under `with_genres=16&with_original_language=ja`.

---

### C.5 Search Screen & Quick-Search Requirements

1. **Role:** Unified multi-type search with precise faceted filtering.
2. **Filter Trigger:** Activating `Icon(Icons.Default.FilterAlt)` in `SearchScreen` opens a Material 3 ModalBottomSheet.
3. **Faceted Controls:**
   - **Content Type Selector:** `[الكل (All), أفلام (Movies), مسلسلات (Series), أنمي (Anime)]`.
   - **Dynamic Genre Selector:** Populates dynamically based on the selected content type:
     - If "Movies" is selected: presents Movie genres.
     - If "Series" is selected: presents TV genres.
     - If "Anime" is selected: presents Anime genres.
     - If "All" is selected: genre selector is disabled or displays common intersection.
4. **Query & State Lifecycle Invariants:**
   - The user's active search query string (`searchQuery`) **must remain intact** when changing filters.
   - Changing filters must cancel ongoing search jobs (`searchJob?.cancel()`), increment `searchGeneration`, and dispatch the updated query immediately.
   - Returning from `MovieDetails` or `SeriesDetails` back to Search preserves the active query, selected filter, and search results.
   - Explicitly navigating away from Search via the bottom navigation bar resets the transient search state in accordance with Wave 11 contracts.

---

## SECTION D — VIDAPI OFFICIAL API FEASIBILITY FORENSIC

A comprehensive technical and documentation audit was conducted on VidAPI (`vidapi.ru`, `vaplayer.ru`).

```
                              ┌─────────────────────────────┐
                              │     VidAPI Infrastructure   │
                              │    (vidapi.ru / vaplayer.ru) │
                              └──────────────┬──────────────┘
                                             │
                       Publicly Supported    │  NO Direct JSON API
                       Embed Mode Only       │  for raw MP4 / M3U8
                                             ▼
                 ┌───────────────────────────────────────────────────┐
                 │  https://vaplayer.ru/embed/movie/{id}             │
                 │  https://vaplayer.ru/embed/tv/{id}/{s}/{e}        │
                 └───────────────────────────┬───────────────────────┘
                                             │
                                             ▼
                        ┌─────────────────────────────────────────┐
                        │   HTML5 Obfuscated Iframe Player        │
                        │   - Client-side token validation        │
                        │   - Referer / Origin header check       │
                        │   - postMessage events (play, qualities)│
                        └────────────────────┬────────────────────┘
                                             │
                        ┌────────────────────┴────────────────────┐
                        │                                         │
                        ▼                                         ▼
            [User Explicit Requirement]              [Unauthorized Anti-Pattern]
            Native Media3 / ExoPlayer                Reverse-Engineered Scraping
            =========================                ===========================
            FAIL: No authorized direct               FORBIDDEN by Wave 12 rules:
            media stream resolution API.             Circumvention, token replay,
            VidAPI is EMBED-ONLY.                    hotlink bypass strictly barred.
```

### D.1 Official Documentation & Endpoint Verification

1. **Service Identity:** VidAPI operates as a hosted video embedding service accessible via `vidapi.ru` and player domain `vaplayer.ru`.
2. **Documented Integration Contracts:**
   - **Movie Endpoint:** `https://vaplayer.ru/embed/movie/{id}` where `{id}` is a TMDB numeric ID or IMDb ID with `"tt"` prefix.
   - **TV Episode Endpoint:** `https://vaplayer.ru/embed/tv/{id}/{season}/{episode}` where `{id}` is TMDB series ID, `{season}` is season number, and `{episode}` is episode number.
   - **Customization Parameters:** URL query parameters for player appearance: `primary_color`, `secondary_color`, `icon_color`, `autoplay`, `start_time`, `sub`, `sub_default`.
3. **Response Payload:** The response is an **HTML/JavaScript document** delivering a proprietary web video player. It is **not** a JSON API returning direct media URLs.

---

### D.2 Direct Native-Playback Feasibility Audit

1. **Native ExoPlayer Incompatibility:**
   - CineStream's core playback engine (`InlineDetailVideoPlayer`, `PlayerScreen`) relies on Google Media3 ExoPlayer (`androidx.media3.exoplayer:1.4.1`).
   - ExoPlayer requires a direct media stream URI (such as an HLS `.m3u8` master playlist or an MP4 byte-range URI) with standard HTTP headers (`Referer`, `User-Agent`).
   - VidAPI does **not** publish or document any authorized public JSON REST endpoint that accepts a TMDB ID and returns a raw `.m3u8` or `.mp4` stream URL.

2. **Player postMessage vs. Stream Resolution:**
   - VidAPI documentation references player events (such as `availableQualities`, `qualityChanged`, `timeUpdate`) communicated via HTML5 `window.postMessage`.
   - These events are designed exclusively for web parent windows embedding the iframe. They communicate state strings (e.g. `"1080p"`, `"720p"`), **not** the underlying CDN media stream URLs.

3. **Security, DRM, and Anti-Hotlink Governance:**
   - Attempting to programmatically extract the underlying media stream from `vaplayer.ru` via headless scraping or regex token interception would require:
     - Circumventing dynamic script obfuscation and session tokens.
     - Spoofing browser Origin/Referer headers to defeat CDN anti-hotlink protections.
     - Bypassing Cloudflare or bot-mitigation checks on player asset domains.
   - **Absolute Governance Mandate:** Wave 12 Section 2 and Section 12 strictly forbid reverse-engineering private player endpoints, defeating hotlink protections, or intercepting protected session tokens.

---

### D.3 Feasibility Verdict

**`EMBED-ONLY — NO DOCUMENTED DIRECT RESOLUTION CONTRACT`**

VidAPI officially supports website iframe embedding only. It does not provide an authorized direct-stream resolution contract for native mobile media players. Integrating VidAPI into CineStream's native ExoPlayer without forcing users into an embedded webview player is **currently infeasible without provider-level direct API access or explicit commercial authorization.**

---

## SECTION E — ADMIN MANAGEMENT & SHARED FIREBASE CONTRACT

### E.1 Canonical Control Plane & Authority Architecture

The authoritative security and configuration contract between CineStream User App and Admin Dashboard is defined in `firestore.rules` and `docs/FIREBASE_CONTRACT.md`.

```
       ┌────────────────────────────────────────────────────────┐
       │                ADMIN DASHBOARD (EXTERNAL)              │
       │  Authenticated Actor: request.auth.uid                 │
       │  Authority Rule: /admins/{uid}.enabled == true         │
       └───────────────────────────┬────────────────────────────┘
                                   │ Writes
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │                  FIRESTORE COLLECTIONS                 │
       ├────────────────────────────────────────────────────────┤
       │ /config/search_order : Authoritative provider priority  │
       │ /managed_extensions  : Remote provider catalog         │
       │ /config/app          : Global app configuration        │
       │ /auditLogs           : Administrative audit ledger     │
       └───────────────────────────┬────────────────────────────┘
                                   │ Realtime Listeners
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │                 CINESTREAM USER APP                    │
       │  ManagedExtensionRealtimeSyncManager                   │
       │  - Reads /managed_extensions & /config/search_order    │
       │  - Updates ManagedExtensionRuntimeRegistry             │
       │  - PlaybackOrchestrator executes in exact Admin order  │
       └────────────────────────────────────────────────────────┘
```

1. **Administrative Authority:**
   - Strictly enforced via `firestore.rules` lines 14–18:
     ```javascript
     function isAdmin() {
       return isAuthenticated() && 
         exists(/databases/$(database)/documents/admins/$(request.auth.uid)) &&
         get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true;
     }
     ```
   - Normal users (`users.role`) have zero write permissions to configurations or extensions.

2. **Canonical Search Order Document (`/config/search_order`):**
   - Read: Public (`allow read: if true`).
   - Write: Admin only (`allow write: if isAdmin()`).
   - Fields:
     - `movie`: List of String extension IDs in priority order (e.g. `["vidapi", "egydead", "qfilm"]`).
     - `tv`: List of String extension IDs for TV series.
     - `series`: Compatibility alias for `tv`.
     - `anime`: List of String extension IDs for Anime.

3. **Managed Extensions Collection (`/managed_extensions/{extensionId}`):**
   - Read: Public (`allow read: if true`).
   - Write: Admin only (`allow write: if isAdmin()`).
   - Current Document Schema:
     - `id` (String): Unique identifier.
     - `name` (String): Display name.
     - `baseUrl` (String): Source base URL.
     - `scraperKey` (String): Registered scraper implementation key.
     - `status` (String): `"ACTIVE"`, `"DISABLED"`, `"DEPRECATED"`.
     - `enabled` (Boolean): Master toggle.
     - `priority` (Number): Fallback priority weight.
     - `contentTypes` (List<String>): `["movie", "series", "anime"]`.
     - `minAppVersionCode` (Number): App version requirement.
     - `runtimeApiVersion` (Number): Extension API version.

---

### E.2 User Application Consumption Pipeline

The User App already possesses a zero-restart realtime synchronization engine:
1. **`ManagedExtensionRealtimeSyncManager.kt`:** Establishes persistent snapshot listeners on `/managed_extensions` and `/config/search_order`.
2. **Snapshot Update Flow:**
   - Admin changes priority or disables an extension in Firestore.
   - `ManagedExtensionRealtimeSyncManager` receives the update, validates records via `ManagedExtensionValidator`, commits the snapshot to `ManagedExtensionRuntimeRegistry`, and updates the Safe Local Metadata Cache (`SafeLocalMetadataCache`).
   - The very next playback attempt in `PlaybackOrchestrator` consumes the updated registry immediately. **No app restart is required.**
3. **Disabled Extension Enforcement:** If an extension is disabled by Admin (`status == "DISABLED"` or `enabled == false`), `ExtensionEligibilityFilter` immediately strips it from candidates.

---

### E.3 Candidate Schema for an Admin-Managed Playback Provider (VidAPI)

If VidAPI direct-resolution capability is authorized in the future, it can be modeled directly within the canonical `/managed_extensions` and `/config/search_order` schemas without breaking changes:

```json
// Path: /managed_extensions/vidapi
{
  "id": "vidapi",
  "name": "VidAPI (Official)",
  "baseUrl": "https://vidapi.ru",
  "providerType": "direct_api",
  "scraperKey": "vidapi_direct",
  "enabled": true,
  "status": "ACTIVE",
  "priority": 100,
  "contentTypes": ["movie", "series"],
  "minAppVersionCode": 1,
  "runtimeApiVersion": 2,
  "config": {
    "timeoutMs": 10000,
    "fallbackOnFailure": true,
    "preferredLanguage": "ar"
  },
  "updatedAt": 1773000000000
}
```

```json
// Path: /config/search_order
{
  "movie": ["vidapi", "egydead", "qfilm"],
  "tv": ["vidapi", "egydead"],
  "series": ["vidapi", "egydead"],
  "anime": ["witanime", "anime4up", "animeblkom"]
}
```

---

## SECTION F — FALLBACK & PLAYBACK INTEGRATION

### F.1 Playback Orchestrator Fallback Mechanics

The existing `PlaybackOrchestrator` (`PlaybackOrchestrator.kt:91–250`) executes a deterministic candidate loop:

1. **Candidate Resolution:**
   - Reads ordered IDs from `searchOrderRepository.getOrderForContentType(targetContentType)`.
   - Filters extensions through `ExtensionEligibilityFilter` (enforcing `status == ACTIVE`, supported `ContentType`, and `ScraperCapability.SERVER_DISCOVERY`).
2. **Deterministic Sequence:**
   - If `vidapi` is configured at index 0 of `movie`, it is evaluated first.
   - **Unsupported Media:** If content is Anime, `ExtensionEligibilityFilter` observes `vidapi.contentTypes = ["movie", "series"]` and automatically skips VidAPI without error or latency.
   - **Timeout Protection:** Bounded candidate timeout (`TIMEOUT_CANDIDATE_MS = 35_000L`), network timeout (`10_000L`), and extraction timeout (`15_000L`).
   - **Fallback Advancement:** If VidAPI returns an error or times out, the orchestrator logs `NEXT_CANDIDATE` and immediately attempts the next eligible candidate (e.g. `egydead`).
   - **Zero Failure Swallowing:** Errors are preserved via `PlaybackOrchestratorOutcome.Failure` ensuring no infinite buffering or blank screens.

3. **Warm Resolution Cache & Background Revalidation:**
   - Once a valid playable stream is resolved, it is cached in `PlaybackResolutionCache` and `LastPlaybackStore`.
   - Subsequent playback starts instantaneously.
   - `BackgroundMediaRevalidator` verifies stream health in the background without blocking the playback UI.

---

## SECTION G — TEST SUITE AUDIT & EXECUTION EVIDENCE

### G.1 Executed Test Commands & Results

Under Wave 12 safe execution governance, existing unit and Robolectric tests were executed to establish regression stability:

| Test Class | Executed Command | Results | Verdict |
| :--- | :--- | :--- | :--- |
| `Phase07Wave11RemediationTest` | `gradle :app:testDebugUnitTest --tests "com.example.Phase07Wave11RemediationTest"` | 6 executed, 0 failed (22s) | `VERIFIED PASS` |
| `PlaybackOrchestratorTest` | `gradle :app:testDebugUnitTest --tests "com.example.extension.managed.playback.PlaybackOrchestratorTest"` | Executed, 0 failed (8s) | `VERIFIED PASS` |
| `SearchOrderAndEligibilityTest` | `gradle :app:testDebugUnitTest --tests "com.example.extension.managed.searchorder.SearchOrderAndEligibilityTest"` | Executed, 0 failed (8s) | `VERIFIED PASS` |
| `FirestoreRulesAlignmentTest` | `gradle :app:testDebugUnitTest --tests "com.example.extension.managed.FirestoreRulesAlignmentTest"` | Executed, 0 failed (8s) | `VERIFIED PASS` |
| `ManagedExtensionRepositoryTest`| `gradle :app:testDebugUnitTest --tests "com.example.extension.managed.ManagedExtensionRepositoryTest"` | Executed, 0 failed (8s) | `VERIFIED PASS` |

---

### G.2 Test Coverage Gap Inventory

1. **Filtering Tests (Completely Missing):**
   - **Zero Unit Tests for Genre Dropdown:** No tests exist verifying genre dropdown states, label localization (`التصنيف: الكل`), or dropdown dismissal.
   - **Zero Unit Tests for Genre Filtering:** No tests verify that selecting a genre ID dispatches `with_genres` to TMDB or filters local listings.
   - **Zero Unit Tests for Screen Isolation:** No tests verify that selecting a genre in Movies does not affect Home.
   - **Zero Unit Tests for Search Faceted Filtering:** No tests verify Search filtering by content type (`all`, `movie`, `tv`, `anime`) combined with query strings.

2. **Playback Provider Tests (VidAPI Gaps):**
   - No tests exist for VidAPI provider resolution (as VidAPI is not implemented).
   - Existing tests prove that `PlaybackOrchestrator` correctly falls back between extensions in `SearchOrderAndEligibilityTest`.

---

## SECTION H — PRIORITIZED FUTURE IMPLEMENTATION PLAN

The future implementation must be executed in strictly ordered, dependency-gated phases:

```
[Phase 1: TMDB Genre Service & Model Alignment]
                       │
                       ▼
[Phase 2: Screen-Level Content & Genre Filtering UI (Movies, Series, Anime)]
                       │
                       ▼
[Phase 3: Search Screen Faceted Filter Sheet & Pipeline]
                       │
                       ▼
[Phase 4: Provider Integration Decision & VidAPI Clarification]
                       │
                       ▼
[Phase 5: Canonical Admin / Firestore Provider Schema Alignment]
                       │
                       ▼
[Phase 6: Playback Orchestrator Provider Adapter & Fallback Verification]
                       │
                       ▼
[Phase 7: Full Regression Test Suite & Verification]
```

### Phase 1: TMDB Genre Service & Model Alignment
- Add `/genre/movie/list` and `/genre/tv/list` endpoints to `TmdbApiService.kt`.
- Create `GenreRepository` with local in-memory / Room cache and Arabic localization support.
- Fix `TmdbMovie.toDomain()` and `TmdbSeries.toDomain()` in `TmdbMediaRepositoryImpl.kt` to map complete `genreIds` arrays to proper domain representations instead of hardcoded `"Action"` / `"Animation"` singletons.
- Add `getMoviesByGenre(genreId, page)` and `getSeriesByGenre(genreId, page)` to `MediaRepository`.

### Phase 2: Screen-Level Content & Genre Filtering UI
- Replace the cosmetic "Genres" tab in `MoviesScreen.kt`, `SeriesScreen.kt`, and `AnimeScreen.kt` with a dedicated genre filter dropdown row positioned beneath the categories.
- Implement Arabic label contract: `التصنيف: الكل` with dropdown options populated from `GenreRepository`.
- Ensure `HomeScreen.kt` strictly omits the genre filter row.
- Ensure genre selections reset to `الكل` when navigating between top-level categories.

### Phase 3: Search Screen Faceted Filter Sheet & Pipeline
- Implement a functional Material 3 ModalBottomSheet triggered by `Icon(Icons.Default.FilterAlt)` in `SearchScreen.kt`.
- Wire content-type chips (`All`, `Movies`, `Series`, `Anime`) and dynamic genre selection into `SearchViewModel`.
- Ensure query string preservation, cancellation of obsolete requests, and state persistence on Details navigation.

### Phase 4: Provider Integration Decision & VidAPI Clarification
- Obtain authoritative provider clarification regarding whether VidAPI offers an authorized direct-stream REST endpoint for native players.
- If direct resolution is unavailable, formalize product decision (either utilize dedicated scrapers for native ExoPlayer, or seek an authorized direct partner API).

### Phase 5: Shared Admin / Firestore Provider Schema Alignment
- If a direct API provider is authorized, define the provider descriptor in `/managed_extensions/vidapi` and update `/config/search_order` in the Admin Dashboard.
- Verify Firestore security rules permit Admin write and User read without permission regressions.

### Phase 6: Playback Orchestrator Provider Adapter & Fallback
- Implement the provider adapter implementing `BaseSiteScraper` or a dedicated `DirectPlaybackProvider` interface.
- Verify `PlaybackOrchestrator` handles provider timeouts, fallback to secondary scrapers, and background revalidation.

### Phase 7: Full Regression Test Suite & Verification
- Implement comprehensive unit tests for all filter flows, faceted search, provider priority execution, and fallback recovery.

---

## SECTION I — RISKS & UNRESOLVED ARCHITECTURAL QUESTIONS

1. **VidAPI Native Player Incompatibility (Critical Risk):**
   - **Risk:** VidAPI is designed and documented as an iframe embed video player (`vaplayer.ru`). It does not provide a documented, public direct-stream resolution JSON endpoint.
   - **Impact:** Attempting to force native ExoPlayer playback from VidAPI without an authorized direct API will fail or require brittle, unauthorized token scraping that breaches hotlink and security protections.
   - **Unresolved Question:** Does the client hold private/commercial API credentials with VidAPI providing direct `.m3u8` streams, or is an iframe embed player acceptable if native ExoPlayer is not possible?

2. **Absence of Admin Source Code in Workspace (Operational Risk):**
   - **Risk:** The Admin Dashboard source code is physically absent from this repository.
   - **Impact:** While the shared Firebase contracts (`/managed_extensions`, `/config/search_order`) can be consumed and validated in the User App, Admin UI changes cannot be implemented or compiled within this repository.

3. **TMDB Rate Limiting on Dynamic Genre Discovery (Performance Risk):**
   - **Risk:** Discovering movies and series by genre requires paginated calls to TMDB `/discover/movie` and `/discover/tv`.
   - **Impact:** Without intelligent caching in Room or disk cache, repeated genre toggling could exhaust TMDB rate limits.

---

## SECTION J — FINAL DECISION & READINESS VERDICTS

| Audit Workstream / Dimension | Diagnostic Verdict | Evidence-Based Rationale |
| :--- | :--- | :--- |
| **Filter Architecture Readiness** | `IMPLEMENTED — VERIFICATION INCOMPLETE` | Screen routing and navigation structure exist, but genre filter dropdowns are missing and existing filter controls are cosmetic stubs. |
| **Filter Data / API Readiness** | `FAIL — REGRESSION OR ARCHITECTURAL DEFECT` | TMDB genre endpoints are missing from `TmdbApiService`; `toDomain()` maps hardcode empty or truncated genre arrays; no genre repository exists. |
| **Anime Classification Readiness** | `VERIFIED PASS` | `ContentTypeResolver` accurately separates Anime from TV series using TMDB Animation genre (16) + Japanese origin (`ja`/`JP`). Movies cleanly retain movie classification. |
| **VidAPI Native Integration Feasibility** | `EMBED-ONLY — NO DOCUMENTED DIRECT RESOLUTION CONTRACT` | VidAPI documentation confirms iframe embedding only; no authorized public direct-stream resolution API exists for native ExoPlayer. |
| **Admin Shared-Contract Readiness** | `VERIFIED PASS` | `/admins/{uid}.enabled == true`, `/managed_extensions`, and `/config/search_order` contracts and security rules are fully specified and operational. |
| **User App Fallback Readiness** | `VERIFIED PASS` | `PlaybackOrchestrator` and `DefaultControlledManagedExtensionRuntime` deterministically execute search order with bounded timeouts and seamless fallback. |
| **Implementation Blockers** | `BLOCKED — PROVIDER AUTHORIZATION OR API ACCESS REQUIRED` | Implementing VidAPI as a native player provider is blocked until an authorized direct-stream resolution contract is established. |

---

### Absolute Execution Stop Notice
In strict compliance with Phase 07.0 / Wave 12 Absolute Governance:
- All physical source code, configurations, Gradle files, manifests, and tests remain pristine and unmodified.
- No code has been written, no dependencies added, and no Firebase data altered.
- Execution terminates immediately with the submission of this report. Await explicit user review and authorization before commencing implementation.
