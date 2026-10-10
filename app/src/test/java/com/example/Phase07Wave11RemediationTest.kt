package com.example

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.example.data.model.ContentType
import com.example.data.model.LibraryItem
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.extension.managed.playback.PlaybackResolution
import com.example.extension.managed.playback.PlaybackResolutionCache
import com.example.extension.orchestrator.BackgroundMediaRevalidator
import com.example.ui.screens.player.PlaybackErrorClassifier
import com.example.ui.screens.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Phase07Wave11RemediationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // TRACK A: SEARCH TRANSIENT STATE RESET
    // =========================================================================

    @Test
    fun testSearch_resetTransientSearchState_clearsQueryAndResults() = testScope.runTest {
        val sampleMovie = Movie(id = "1", title = "Inception", overview = "", posterUrl = "", backdropUrl = "", year = 2010, rating = 8.8, genres = emptyList(), runtime = 148)
        val dummyRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> {
                return if (query == "Inception") Pair(listOf(sampleMovie), emptyList())
                else Pair(emptyList(), emptyList())
            }
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = SearchViewModel(dummyRepo)
        advanceUntilIdle()

        viewModel.submitSearch("Inception")
        advanceUntilIdle()

        assertEquals("Inception", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.movieResults.isNotEmpty())

        // User leaves Search tab -> resetTransientSearchState invoked
        viewModel.resetTransientSearchState()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.movieResults.isEmpty())
        assertTrue(viewModel.uiState.value.seriesResults.isEmpty())
        assertFalse(viewModel.uiState.value.isSearching)
    }

    // =========================================================================
    // TRACK B: PLAYBACK ERROR CLASSIFIER (TRANSIENT VS PERMANENT)
    // =========================================================================

    @Test
    fun testPlaybackErrorClassifier_transientNetworkErrors() {
        val ioEx = PlaybackException(
            "Connection failed",
            SocketTimeoutException("Read timed out"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertTrue("SocketTimeoutException must be classified as transient", PlaybackErrorClassifier.isTransientNetworkError(ioEx))
        assertFalse("SocketTimeoutException must not be permanent failure", PlaybackErrorClassifier.isPermanentFailure(ioEx))

        val hostEx = PlaybackException(
            "DNS lookup failed",
            UnknownHostException("Unable to resolve host"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertTrue("UnknownHostException must be transient", PlaybackErrorClassifier.isTransientNetworkError(hostEx))
    }

    @Test
    fun testPlaybackErrorClassifier_permanentHttpFailures() {
        val http403 = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://cdn.example.com/video.m3u8")),
            byteArrayOf()
        )
        val pEx403 = PlaybackException("HTTP 403", http403, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertFalse("HTTP 403 must NOT be transient", PlaybackErrorClassifier.isTransientNetworkError(pEx403))
        assertTrue("HTTP 403 must be permanent", PlaybackErrorClassifier.isPermanentFailure(pEx403))

        val http404 = HttpDataSource.InvalidResponseCodeException(
            404,
            "Not Found",
            null,
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://cdn.example.com/video.m3u8")),
            byteArrayOf()
        )
        val pEx404 = PlaybackException("HTTP 404", http404, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertFalse("HTTP 404 must NOT be transient", PlaybackErrorClassifier.isTransientNetworkError(pEx404))
        assertTrue("HTTP 404 must be permanent", PlaybackErrorClassifier.isPermanentFailure(pEx404))
    }

    @Test
    fun testPlaybackErrorClassifier_permanentCodecAndMalformedContainer() {
        val malformedEx = PlaybackException(
            "Malformed container",
            null,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        )
        assertFalse("Malformed container must NOT be transient", PlaybackErrorClassifier.isTransientNetworkError(malformedEx))
        assertTrue("Malformed container must be permanent", PlaybackErrorClassifier.isPermanentFailure(malformedEx))

        val decoderEx = PlaybackException(
            "Decoder init failed",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertFalse("Decoder failure must NOT be transient", PlaybackErrorClassifier.isTransientNetworkError(decoderEx))
        assertTrue("Decoder failure must be permanent", PlaybackErrorClassifier.isPermanentFailure(decoderEx))
    }

    // =========================================================================
    // TRACK C: REVALIDATION TIMING CONSTANTS
    // =========================================================================

    @Test
    fun testBackgroundMediaRevalidator_timingConstants() {
        assertEquals("Revalidation interval must be 6 minutes (360,000ms)", 6 * 60 * 1000L, BackgroundMediaRevalidator.SIX_MINUTES_MS)
        assertEquals("SIX_HOURS_MS alias must equal SIX_MINUTES_MS", BackgroundMediaRevalidator.SIX_MINUTES_MS, BackgroundMediaRevalidator.SIX_HOURS_MS)
    }

    // =========================================================================
    // TRACK D: PLAYBACK RESOLUTION CACHE POPULATION & LOOKUP
    // =========================================================================

    @Test
    fun testPlaybackResolutionCache_putAndFindValidResolution() {
        val cache = PlaybackResolutionCache()
        val resolution = PlaybackResolution(
            mediaIdentity = "123:movie:1:1",
            contentType = com.example.extension.managed.model.ContentType.MOVIE,
            mediaId = "123",
            season = 1,
            episode = 1,
            providerId = "managed_test",
            selectedQuality = "1080p",
            streamUrl = "https://cdn.example.com/stream.m3u8",
            expiresAt = System.currentTimeMillis() + 3600000L
        )

        cache.put(resolution)

        val retrieved = cache.findValidResolution(
            mediaId = "123",
            contentType = com.example.extension.managed.model.ContentType.MOVIE,
            episode = 1,
            requestedQuality = "1080p"
        )

        assertNotNull("Retrieved resolution must not be null", retrieved)
        assertEquals("https://cdn.example.com/stream.m3u8", retrieved?.streamUrl)
        assertEquals("1080p", retrieved?.selectedQuality)
    }

    // =========================================================================
    // TRACK E: FAVORITES LONG-PRESS DATA CONTRACT COMPATIBILITY
    // =========================================================================

    @Test
    fun testFavorites_libraryItemCreationFromSearchResult() {
        val movieItem = LibraryItem.fromLegacy(
            id = "550",
            title = "Fight Club",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            isMovie = true
        )
        assertEquals("movie_550", movieItem.libraryId)
        assertEquals("550", movieItem.tmdbId)
        assertEquals(ContentType.MOVIE, movieItem.contentType)
        assertTrue(movieItem.isMovie)

        val seriesItem = LibraryItem.fromLegacy(
            id = "1399",
            title = "Game of Thrones",
            posterUrl = "https://image.tmdb.org/t/p/w500/got.jpg",
            isMovie = false
        )
        assertEquals("tv_1399", seriesItem.libraryId)
        assertEquals("1399", seriesItem.tmdbId)
        assertEquals(ContentType.TV, seriesItem.contentType)
        assertFalse(seriesItem.isMovie)
    }

    // =========================================================================
    // TRACK F: CANONICAL APPLICATION IDENTITY
    // =========================================================================

    @Test
    fun testCanonicalApplicationId_configuredInBuildGradle() {
        val buildGradle = java.io.File("app/build.gradle.kts").takeIf { it.exists() }
            ?: java.io.File("../app/build.gradle.kts").takeIf { it.exists() }
            ?: java.io.File("build.gradle.kts")
        val content = buildGradle.readText()
        assertTrue("app/build.gradle.kts must configure canonical applicationId 'com.aistudio.cinestream.xyzabc'",
            content.contains("applicationId = \"com.aistudio.cinestream.xyzabc\""))
    }

    @Test
    fun testCanonicalApplicationId_matchingGoogleServicesJson() {
        val googleServices = java.io.File("app/google-services.json").takeIf { it.exists() }
            ?: java.io.File("../app/google-services.json").takeIf { it.exists() }
            ?: java.io.File("google-services.json")
        val content = googleServices.readText()
        assertTrue("app/google-services.json must match canonical package 'com.aistudio.cinestream.xyzabc'",
            content.contains("\"package_name\": \"com.aistudio.cinestream.xyzabc\""))
    }
}
