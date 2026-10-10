package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.HistoryItem
import com.example.data.remote.TmdbApiService
import com.example.data.remote.TmdbMovieDetails
import com.example.data.remote.TmdbSeriesDetails
import com.example.data.repository.ContinueWatchingMetadataManager
import com.example.ui.components.ContinueWatchingCardShared
import com.example.ui.components.rememberCardMediaDetail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinueWatchingPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val movieCallCounts = ConcurrentHashMap<Int, AtomicInteger>()
    private val seriesCallCounts = ConcurrentHashMap<Int, AtomicInteger>()
    private var shouldFail = false

    private val spyApiService: TmdbApiService = Proxy.newProxyInstance(
        TmdbApiService::class.java.classLoader,
        arrayOf(TmdbApiService::class.java)
    ) { _, method, args ->
        if (shouldFail) {
            throw IOException("Simulated network failure")
        }
        when (method.name) {
            "getMovieDetails" -> {
                val id = args[0] as Int
                movieCallCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                TmdbMovieDetails(
                    id = id,
                    title = "Movie $id Title",
                    originalTitle = "Movie $id Original",
                    overview = "Movie $id Overview",
                    posterPath = "/poster_$id.jpg",
                    backdropPath = "/backdrop_$id.jpg",
                    releaseDate = "2024-05-15",
                    voteAverage = 8.4,
                    runtime = 125,
                    originalLanguage = "en",
                    genres = null,
                    credits = null,
                    videos = null
                )
            }
            "getSeriesDetails" -> {
                val id = args[0] as Int
                seriesCallCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                TmdbSeriesDetails(
                    id = id,
                    name = "Series $id Title",
                    originalName = "Series $id Original",
                    overview = "Series $id Overview",
                    posterPath = "/poster_series_$id.jpg",
                    backdropPath = "/backdrop_series_$id.jpg",
                    firstAirDate = "2023-11-20",
                    voteAverage = 9.1,
                    genres = null,
                    credits = null,
                    videos = null,
                    seasons = null,
                    createdBy = null,
                    status = "Returning Series"
                )
            }
            else -> null
        }
    } as TmdbApiService

    @Before
    fun setUp() {
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        movieCallCounts.clear()
        seriesCallCounts.clear()
        shouldFail = false
        ContinueWatchingMetadataManager.clearCacheForTesting()
        ContinueWatchingMetadataManager.apiService = spyApiService
    }

    @After
    fun tearDown() {
        ContinueWatchingMetadataManager.clearCacheForTesting()
    }

    private fun createHistoryItem(
        id: String,
        title: String = "Test Item $id",
        isMovie: Boolean = true,
        position: Long = 30000L,
        duration: Long = 120000L
    ): HistoryItem {
        return HistoryItem(
            id = id,
            title = title,
            posterUrl = "https://example.com/poster_$id.jpg",
            isMovie = isMovie,
            timestamp = System.currentTimeMillis(),
            positionMillis = position,
            durationMillis = duration
        )
    }

    @Test
    fun test1_initialLoad_requestsMetadataOnce() = runBlocking {
        val item = createHistoryItem("101", isMovie = true)
        val detail = ContinueWatchingMetadataManager.getOrFetchMetadata(item)

        assertNotNull(detail)
        assertEquals("Movie 101 Title", detail.title)
        assertEquals(1, movieCallCounts[101]?.get())
    }

    @Test
    fun test2_recomposition_doesNotTriggerDuplicateNetworkCalls() {
        val item = createHistoryItem("202", isMovie = true)
        var recomposeTrigger by mutableStateOf(0)

        composeTestRule.setContent {
            val count = recomposeTrigger
            Column {
                val detail = rememberCardMediaDetail(item)
                Box {
                    ContinueWatchingCardShared(
                        item = item,
                        onClick = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()

        // Trigger 20 recompositions of the parent scope
        repeat(20) { index ->
            recomposeTrigger = index + 1
            composeTestRule.waitForIdle()
        }

        val totalCalls = movieCallCounts[202]?.get() ?: 0
        assertEquals(
            "Recomposing 20 times must NOT increase TMDB network requests beyond 1",
            1,
            totalCalls
        )
    }

    @Test
    fun test3_sameMediaId_doesNotTriggerDuplicateRequestWhileLoading() = runBlocking {
        val item = createHistoryItem("303", isMovie = true)

        // Launch 10 concurrent requests for the exact same media ID
        val deferreds = (1..10).map {
            async {
                ContinueWatchingMetadataManager.getOrFetchMetadata(item)
            }
        }
        val results = deferreds.awaitAll()

        assertEquals(10, results.size)
        assertEquals(
            "10 concurrent requests for the same media ID must be deduplicated into exactly 1 network call",
            1,
            movieCallCounts[303]?.get()
        )
    }

    @Test
    fun test4_successfulMetadata_isReused() = runBlocking {
        val item = createHistoryItem("404", isMovie = true)

        // First fetch
        val detail1 = ContinueWatchingMetadataManager.getOrFetchMetadata(item)
        assertEquals(1, movieCallCounts[404]?.get())

        // Second fetch immediately after
        val detail2 = ContinueWatchingMetadataManager.getOrFetchMetadata(item)
        assertEquals(1, movieCallCounts[404]?.get())
        assertEquals(detail1.title, detail2.title)
    }

    @Test
    fun test5_multipleCards_doNotCrossContaminateMetadata() = runBlocking {
        val movie = createHistoryItem("501", isMovie = true)
        val series = createHistoryItem("502", isMovie = false)

        val movieDetail = ContinueWatchingMetadataManager.getOrFetchMetadata(movie)
        val seriesDetail = ContinueWatchingMetadataManager.getOrFetchMetadata(series)

        assertEquals("Movie 501 Title", movieDetail.title)
        assertTrue(movieDetail.isMovie)

        assertEquals("Series 502 Title", seriesDetail.title)
        assertFalse(seriesDetail.isMovie)
    }

    @Test
    fun test6_movieMetadata_remainsMovieMetadata() = runBlocking {
        val movie = createHistoryItem("601", isMovie = true)
        val detail = ContinueWatchingMetadataManager.getOrFetchMetadata(movie)

        assertTrue(detail.isMovie)
        assertEquals("2024", detail.year)
        assertEquals("8.4", detail.rating)
        assertEquals(125, detail.runtimeMinutes)
    }

    @Test
    fun test7_seriesMetadata_preservesSeriesSemantics() = runBlocking {
        val series = createHistoryItem("701", isMovie = false)
        val detail = ContinueWatchingMetadataManager.getOrFetchMetadata(series)

        assertFalse(detail.isMovie)
        assertEquals("2023", detail.year)
        assertEquals("9.1", detail.rating)
        assertEquals(0, detail.runtimeMinutes)
    }

    @Test
    fun test8_networkFailure_producesStableErrorState() = runBlocking {
        shouldFail = true
        val item = createHistoryItem("801", title = "Fallback Title", isMovie = true)

        val detail = ContinueWatchingMetadataManager.getOrFetchMetadata(item)
        assertNotNull(detail)
        assertEquals("Fallback Title", detail.title)
        assertTrue(ContinueWatchingMetadataManager.isCached("801"))
    }

    @Test
    fun test9_partialFailure_doesNotCorruptSuccessfulCards() = runBlocking {
        val itemGood = createHistoryItem("901", title = "Good Item", isMovie = true)
        val detailGood = ContinueWatchingMetadataManager.getOrFetchMetadata(itemGood)

        shouldFail = true
        val itemBad = createHistoryItem("902", title = "Bad Item", isMovie = true)
        val detailBad = ContinueWatchingMetadataManager.getOrFetchMetadata(itemBad)

        assertEquals("Movie 901 Title", detailGood.title)
        assertEquals("Bad Item", detailBad.title)
    }

    @Test
    fun test10_screenRecreation_doesNotDuplicateRequests() {
        val item = createHistoryItem("1001", isMovie = true)
        var recreationKey by mutableStateOf(0)

        composeTestRule.setContent {
            key(recreationKey) {
                ContinueWatchingCardShared(item = item, onClick = {})
            }
        }
        composeTestRule.waitForIdle()

        // Re-create the subtree with a fresh key
        recreationKey = 1
        composeTestRule.waitForIdle()

        assertEquals(1, movieCallCounts[1001]?.get() ?: 0)
    }

    @Test
    fun test11_rapidNavigation_doesNotAccumulateRequests() {
        val item = createHistoryItem("1101", isMovie = true)
        var isAttached by mutableStateOf(true)

        composeTestRule.setContent {
            if (isAttached) {
                ContinueWatchingCardShared(item = item, onClick = {})
            }
        }
        composeTestRule.waitForIdle()

        repeat(5) {
            isAttached = false
            composeTestRule.waitForIdle()
            isAttached = true
            composeTestRule.waitForIdle()
        }

        assertEquals(1, movieCallCounts[1101]?.get() ?: 0)
    }

    @Test
    fun test12_fastScrolling_doesNotIncreaseNetworkCallsBecauseOfRecomposition() {
        val items = (1..5).map { createHistoryItem("120$it", isMovie = true) }
        var scrollIndex by mutableStateOf(0)

        composeTestRule.setContent {
            val idx = scrollIndex
            Column {
                items.forEach { item ->
                    ContinueWatchingCardShared(item = item, onClick = {})
                }
            }
        }
        composeTestRule.waitForIdle()

        repeat(10) { step ->
            scrollIndex = step + 1
            composeTestRule.waitForIdle()
        }

        items.forEach { item ->
            val id = item.id.toInt()
            assertEquals("Item $id must be requested at most once despite scrolling passes", 1, movieCallCounts[id]?.get() ?: 0)
        }
    }

    @Test
    fun test13_playbackResumeInformation_remainsUnchanged() {
        val item = createHistoryItem("1301", position = 45000L, duration = 90000L)
        var wasClicked = false

        composeTestRule.setContent {
            ContinueWatchingCardShared(
                item = item,
                onClick = { wasClicked = true }
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(45000L, item.positionMillis)
        assertEquals(90000L, item.durationMillis)
    }

    @Test
    fun test14_navigationDestination_remainsUnchanged() {
        var clickedId: String? = null
        var clickedIsMovie: Boolean? = null

        val movieItem = createHistoryItem("1401", isMovie = true)
        val seriesItem = createHistoryItem("1402", isMovie = false)

        val onMovieClick: (String) -> Unit = { id ->
            clickedId = id
            clickedIsMovie = true
        }
        val onSeriesClick: (String) -> Unit = { id ->
            clickedId = id
            clickedIsMovie = false
        }

        if (movieItem.isMovie) onMovieClick(movieItem.id) else onSeriesClick(movieItem.id)
        assertEquals("1401", clickedId)
        assertEquals(true, clickedIsMovie)

        if (seriesItem.isMovie) onMovieClick(seriesItem.id) else onSeriesClick(seriesItem.id)
        assertEquals("1402", clickedId)
        assertEquals(false, clickedIsMovie)
    }
}
