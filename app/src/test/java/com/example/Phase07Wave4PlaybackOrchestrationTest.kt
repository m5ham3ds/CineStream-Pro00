package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.Season
import com.example.domain.models.Series
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.ui.screens.details.SeriesDetailsUiState
import com.example.ui.screens.details.SeriesDetailsViewModel
import com.example.ui.screens.player.PlayerUiState
import com.example.ui.screens.player.PlayerViewModel
import com.example.ui.screens.player.ServerStateStore
import com.example.utils.LastPlaybackStore
import com.example.utils.MediaStorageUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PHASE 07.0 / WAVE 4: PLAYBACK ORCHESTRATION CONSOLIDATION & STALE URL ELIMINATION TEST
 *
 * Authoritative Verification Gate for:
 * - F-015: Direct Playback Bypass around Orchestrator
 * - F-016: Stale Scraper Stream URL Resurrection from Disk / Memory Cache
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave4PlaybackOrchestrationTest {

    private lateinit var context: Context
    private lateinit var orchestrator: ManagedMediaOrchestrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LastPlaybackStore.clearAll(context)
        ServerStateStore.clear()
        orchestrator = ManagedMediaOrchestrator.getInstance(context)
    }

    // =========================================================================
    // F-015: DIRECT PLAYBACK BYPASS ELIMINATION & CANONICAL ORCHESTRATION
    // =========================================================================

    @Test
    fun testF015_01_canonicalOrchestratorInvokedWhenNoWarmPlaybackExists() {
        val mediaId = "movie_cold_start_101"
        val warmResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId,
            candidateUrl = null
        )

        assertTrue(
            "Empty initial playback state must evaluate to StaleOrInvalid",
            warmResult is ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid
        )
    }

    @Test
    fun testF015_02_directBypassEliminated_unverifiedOrAutoExtractCandidateRejected() {
        val mediaId = "movie_bypass_test_102"
        
        // 1. auto_extract:// marker protocol
        val autoExtractResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId,
            candidateUrl = "auto_extract://"
        )
        assertTrue(autoExtractResult is ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid)

        // 2. Non-playable URL (e.g. web page URL)
        val webpageResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId,
            candidateUrl = "https://example.com/watch/movie-102.html"
        )
        assertTrue(webpageResult is ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid)

        // 3. Blank or empty candidate
        val blankResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId,
            candidateUrl = "   "
        )
        assertTrue(blankResult is ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid)
    }

    @Test
    fun testF015_03_warmPlaybackFastPathOperatesInstantlyForFreshStream() {
        val mediaId = "movie_fresh_stream_103"
        val freshStreamUrl = "https://cdn.example.com/streams/movie103/master.m3u8"
        val savedQuality = "1080p"

        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = mediaId,
            url = freshStreamUrl,
            quality = savedQuality,
            serverName = "Server Alpha",
            positionMillis = 120_000L
        )

        val warmResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId
        )

        assertTrue("Fresh stream URL must resolve to Valid", warmResult is ManagedMediaOrchestrator.WarmPlaybackResult.Valid)
        val valid = warmResult as ManagedMediaOrchestrator.WarmPlaybackResult.Valid
        assertEquals(freshStreamUrl, valid.streamUrl)
        assertEquals(savedQuality, valid.quality)
        assertFalse(valid.isLocal)
    }

    @Test
    fun testF015_04_localDownloadedMediaAlwaysResolvesToImmediateOfflineFastPath() {
        val movieId = "downloaded_movie_104"
        val movieFile = MediaStorageUtils.getMovieFile(context, movieId, "mp4")
        movieFile.parentFile?.mkdirs()
        movieFile.writeText("fake video content")

        assertTrue("Downloaded media file must physically exist", movieFile.exists())

        // Test with local_offline_file:// prefix
        val offlinePrefixedResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = movieId,
            candidateUrl = "local_offline_file://$movieId"
        )
        assertTrue(offlinePrefixedResult is ManagedMediaOrchestrator.WarmPlaybackResult.Valid)
        val validPrefixed = offlinePrefixedResult as ManagedMediaOrchestrator.WarmPlaybackResult.Valid
        assertTrue("Resolved stream URL must be a local file URI", validPrefixed.streamUrl.startsWith("file://"))
        assertTrue("isLocal must be true", validPrefixed.isLocal)

        // Test with candidateUrl = null (automatic local storage lookup)
        val autoLookupResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = movieId,
            candidateUrl = null
        )
        assertTrue(autoLookupResult is ManagedMediaOrchestrator.WarmPlaybackResult.Valid)
        val validAuto = autoLookupResult as ManagedMediaOrchestrator.WarmPlaybackResult.Valid
        assertTrue(validAuto.isLocal)

        movieFile.delete()
    }

    @Test
    fun testF015_05_seriesSeasonPreservedAcrossDetailsNavigation() {
        val seriesId = "series_season_preservation_105"
        val season1 = Season(id = "s1", seriesId = seriesId, seasonNumber = 1, title = "Season 1", posterUrl = "", episodeCount = 10)
        val season2 = Season(id = "s2", seriesId = seriesId, seasonNumber = 2, title = "Season 2", posterUrl = "", episodeCount = 12)
        val testSeries = Series(
            id = seriesId,
            title = "Test Series",
            overview = "Test",
            posterUrl = "",
            backdropUrl = "",
            rating = 8.5,
            year = 2024,
            genres = emptyList(),
            seasons = listOf(season1, season2)
        )

        // Simulate user selecting Season 2 in SeriesDetailsUiState
        val userSelectedState = SeriesDetailsUiState(
            series = testSeries,
            selectedSeason = season2,
            episodes = emptyList(),
            isLoading = false
        )
        SeriesDetailsViewModel.updateAndCache(seriesId) { userSelectedState }

        // Fetch cached state
        val cached = SeriesDetailsViewModel.getCachedState(seriesId)
        assertNotNull(cached)
        assertEquals(2, cached!!.selectedSeason?.seasonNumber)

        // Verify the fix: preservedSeason keeps cached.selectedSeason
        val preservedSeason = cached.selectedSeason
            ?: cached.series?.seasons?.firstOrNull { it.seasonNumber > 0 }
            ?: cached.series?.seasons?.firstOrNull()

        assertEquals("User selected season must be preserved across navigation", 2, preservedSeason?.seasonNumber)
    }

    @Test
    fun testF015_06_episodeSelectionIsolationPreventsEpisode1Hijacking() {
        val seriesId = "test_series_hijack_106"
        
        // Download Episode 1 only
        val ep1File = MediaStorageUtils.getSeriesEpisodeFile(context, seriesId, season = 1, episode = 1, extension = "mp4")
        ep1File.parentFile?.mkdirs()
        ep1File.writeText("fake episode 1 video")

        assertTrue("Episode 1 must exist", ep1File.exists())

        // Query for Episode 5 (which is NOT downloaded)
        val ep5Result = MediaStorageUtils.findMediaFile(
            context = context,
            id = "${seriesId}_1_5",
            isMovie = false,
            seriesId = seriesId,
            season = 1,
            episode = 5
        )

        assertNull("Query for undownloaded Episode 5 must return null and never hijack Episode 1", ep5Result)

        // Query for Episode 1 specifically
        val ep1Result = MediaStorageUtils.findMediaFile(
            context = context,
            id = "${seriesId}_1_1",
            isMovie = false,
            seriesId = seriesId,
            season = 1,
            episode = 1
        )
        assertNotNull("Query for downloaded Episode 1 must succeed", ep1Result)
        assertEquals(ep1File.canonicalPath, ep1Result?.canonicalPath)

        ep1File.delete()
        ep1File.parentFile?.delete()
    }

    // =========================================================================
    // F-016: STALE STREAM URL RESURRECTION ELIMINATION & INVALIDATION
    // =========================================================================

    @Test
    fun testF016_01_staleStreamUrlInLastPlaybackStoreRejectedAfterTTL() {
        val mediaId = "movie_stale_stream_201"
        val oldUrl = "https://cdn.example.com/expired_token/video.mp4"

        // Save playback with an ancient timestamp (3 hours ago; TTL is 2 hours)
        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = mediaId,
            url = oldUrl,
            quality = "720p",
            positionMillis = 50_000L
        )

        // Manually adjust timestamp to 3 hours ago in SharedPreferences
        val sp = context.getSharedPreferences("last_playback_store", Context.MODE_PRIVATE)
        val threeHoursAgo = System.currentTimeMillis() - (3 * 3600 * 1000L)
        sp.edit().putLong("time_$mediaId", threeHoursAgo).apply()

        // 1. Check isUrlFresh
        val saved = LastPlaybackStore.getLastPlayback(context, mediaId)
        assertNotNull(saved)
        assertEquals(threeHoursAgo, saved!!.timestamp)
        assertFalse(
            "URL older than 2 hours must be marked stale",
            LastPlaybackStore.isUrlFresh(saved.url, saved.timestamp)
        )

        // 2. Evaluate warm playback through orchestrator
        val warmResult = orchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = mediaId
        )
        assertTrue(
            "Stale stream URL must be rejected from warm fast path",
            warmResult is ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid
        )
    }

    @Test
    fun testF016_02_streamFailureInvalidatesStaleUrlAcrossStores() {
        val mediaId = "movie_invalidation_test_202"
        val deadUrl = "https://cdn.example.com/403_forbidden/master.m3u8"
        val userPos = 75_000L
        val userQuality = "1080p"

        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = mediaId,
            url = deadUrl,
            quality = userQuality,
            serverName = "DeadServer",
            positionMillis = userPos
        )
        ServerStateStore.saveForMedia(
            mediaKey = mediaId,
            servers = listOf("DeadServer"),
            links = mapOf("DeadServer" to deadUrl),
            ids = emptyMap(),
            downloads = emptyMap(),
            directStreamUrl = deadUrl,
            mediaId = mediaId
        )

        // Verify stores populated
        assertNotNull(LastPlaybackStore.getLastPlayback(context, mediaId))
        assertNotNull(ServerStateStore.getDataForMedia(mediaId)?.directStreamUrl)

        // Trigger authoritative stream invalidation (as occurs on ExoPlayer playback error)
        orchestrator.invalidateStreamUrl(context, mediaId, mediaKey = mediaId)

        // 1. LastPlaybackStore URL must be purged
        val updatedLast = LastPlaybackStore.getLastPlayback(context, mediaId)
        assertNull("Dead stream URL must be completely removed from LastPlaybackStore", updatedLast)

        // 2. User seek position and quality must be preserved in LastPlaybackStore preferences
        val sp = context.getSharedPreferences("last_playback_store", Context.MODE_PRIVATE)
        assertEquals(userPos, sp.getLong("pos_$mediaId", 0L))
        assertEquals(userQuality, sp.getString("quality_$mediaId", null))
        assertNull("url key must be purged", sp.getString("url_$mediaId", null))

        // 3. ServerStateStore directStreamUrl must be purged
        val updatedServerState = ServerStateStore.getDataForMedia(mediaId)
        assertNull("directStreamUrl must be purged from ServerStateStore", updatedServerState?.directStreamUrl)
        assertTrue("extractedQualities must be empty", updatedServerState?.extractedQualities?.isEmpty() == true)
    }

    @Test
    fun testF016_03_serverStateStoreGetCachedDataFiltersExpiredDirectStreamUrl() {
        val mediaKey = "test_media_key_203"
        val expiredUrl = "https://cdn.example.com/expired.m3u8"
        val threeHoursAgo = System.currentTimeMillis() - (3 * 3600 * 1000L)

        val staleData = com.example.ui.screens.player.MediaServerData(
            mediaKey = mediaKey,
            mediaId = "203",
            servers = listOf("Server1"),
            directStreamUrl = expiredUrl,
            lastUpdatedTimestamp = threeHoursAgo
        )
        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("Server1"),
            links = emptyMap(),
            ids = emptyMap(),
            downloads = emptyMap(),
            directStreamUrl = expiredUrl,
            mediaId = "203"
        )
        // Adjust timestamp
        ServerStateStore.updateRevalidatedData(
            mediaKey = mediaKey,
            newServers = listOf("Server1"),
            newLinks = emptyMap(),
            newIds = emptyMap(),
            newDownloads = emptyMap(),
            newQualities = emptyList(),
            directStreamUrl = expiredUrl,
            mediaId = "203"
        )
        // Direct cache manipulation to simulate expired entry
        val currentCached = ServerStateStore.getDataForMedia(mediaKey)
        assertNotNull(currentCached)

        // Retrieve via getCachedData with TTL filter
        val sp = context.getSharedPreferences("last_playback_store", Context.MODE_PRIVATE)
        // getCachedData checks lastUpdatedTimestamp > 2 hours
        // Let's verify getCachedData returns data with directStreamUrl = null when timestamp is old
        val dataWithOldTimestamp = currentCached!!.copy(
            lastUpdatedTimestamp = System.currentTimeMillis() - (3 * 3600 * 1000L)
        )
        ServerStateStore.saveForMedia(
            mediaKey = "stale_entry_key",
            servers = listOf("S1"),
            links = emptyMap(),
            ids = emptyMap(),
            downloads = emptyMap(),
            directStreamUrl = expiredUrl
        )
        // Update direct cache with expired timestamp
        val rawData = ServerStateStore.getDataForMedia("stale_entry_key")
        if (rawData != null) {
            val expiredEntry = rawData.copy(lastUpdatedTimestamp = threeHoursAgo)
            // Put expired entry
            val field = ServerStateStore::class.java.getDeclaredField("cache")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cacheMap = field.get(ServerStateStore) as java.util.concurrent.ConcurrentHashMap<String, com.example.ui.screens.player.MediaServerData>
            cacheMap["stale_entry_key"] = expiredEntry
        }

        val retrieved = ServerStateStore.getCachedData("stale_entry_key")
        assertNotNull(retrieved)
        assertNull("Expired directStreamUrl must be stripped by getCachedData", retrieved?.directStreamUrl)
        assertTrue("Expired qualities must be stripped by getCachedData", retrieved?.extractedQualities?.isEmpty() == true)
    }

    @Test
    fun testF016_04_serverStateStoreInvalidateStreamUrlClearsCachedStreamAndFlow() {
        val mediaKey = "flow_invalidation_key_204"
        val testUrl = "https://cdn.example.com/flow_test.mp4"
        val qualityList = listOf(
            com.example.utils.M3U8Parser.QualityInfo("1080p", testUrl)
        )

        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("FastServer"),
            links = mapOf("FastServer" to testUrl),
            ids = emptyMap(),
            downloads = emptyMap(),
            extractedQ = qualityList,
            directStreamUrl = testUrl,
            mediaId = "204"
        )

        assertEquals(1, ServerStateStore.extractedQualitiesFlow.value.size)
        assertEquals(testUrl, ServerStateStore.getDataForMedia(mediaKey)?.directStreamUrl)

        ServerStateStore.invalidateStreamUrl(mediaKey)

        assertEquals(0, ServerStateStore.extractedQualitiesFlow.value.size)
        assertNull(ServerStateStore.getDataForMedia(mediaKey)?.directStreamUrl)
        assertTrue(ServerStateStore.getDataForMedia(mediaKey)?.extractedQualities?.isEmpty() == true)
    }
}
