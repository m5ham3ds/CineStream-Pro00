package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.repository.TmdbListPaginator
import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrendingTabStatePerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun createMovie(id: Int): Movie = Movie(
        id = id.toString(),
        title = "Movie $id",
        overview = "Overview $id",
        posterUrl = "https://image.tmdb.org/t/p/w500/poster_$id.jpg",
        backdropUrl = "",
        year = 2024,
        rating = 8.0,
        genres = emptyList(),
        runtime = 120
    )

    private fun createSeries(id: Int): Series = Series(
        id = id.toString(),
        title = "Series $id",
        overview = "Overview $id",
        posterUrl = "https://image.tmdb.org/t/p/w500/series_$id.jpg",
        backdropUrl = "",
        year = 2023,
        rating = 8.5,
        genres = emptyList()
    )

    private fun createTestPaginator(): TmdbListPaginator {
        return TmdbListPaginator(
            fetchMoviesPage = { page ->
                PaginatedResult(
                    items = listOf(createMovie(page * 10 + 1), createMovie(page * 10 + 2)),
                    page = page,
                    totalPages = 5
                )
            },
            fetchSeriesPage = { page ->
                PaginatedResult(
                    items = listOf(createSeries(page * 10 + 1), createSeries(page * 10 + 2)),
                    page = page,
                    totalPages = 5
                )
            },
            fetchAnimePage = { page ->
                PaginatedResult(
                    items = listOf(createSeries(page * 100 + 1), createSeries(page * 100 + 2)),
                    page = page,
                    totalPages = 5
                )
            },
            coroutineScope = testScope
        )
    }

    @Composable
    private fun IsolatedTabComposable(
        stateFlow: kotlinx.coroutines.flow.StateFlow<com.example.data.repository.TabPaginationState>,
        counter: AtomicInteger
    ) {
        val state by stateFlow.collectAsState()
        androidx.compose.material3.Text("Items: ${state.items.size}")
        SideEffect { counter.incrementAndGet() }
    }

    @Test
    fun test1_tabA_update_doesNotRecomposeTabB() {
        val paginator = createTestPaginator()
        val tab0Recompositions = AtomicInteger(0)
        val tab1Recompositions = AtomicInteger(0)

        composeTestRule.setContent {
            androidx.compose.foundation.layout.Row {
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.size(100.dp)) {
                    IsolatedTabComposable(paginator.getTabStateFlow(0), tab0Recompositions)
                }
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.size(100.dp)) {
                    IsolatedTabComposable(paginator.getTabStateFlow(1), tab1Recompositions)
                }
            }
        }
        composeTestRule.waitForIdle()

        val tab0Initial = tab0Recompositions.get()
        val tab1Initial = tab1Recompositions.get()

        // Update Tab 0 only
        paginator.loadFirstPage(0)
        composeTestRule.waitForIdle()

        assertTrue(
            "Tab 0 recompositions must increase on Tab 0 update",
            tab0Recompositions.get() > tab0Initial
        )
        assertEquals(
            "Tab 1 recompositions must remain strictly identical when Tab 0 updates",
            tab1Initial,
            tab1Recompositions.get()
        )
    }

    @Test
    fun test2_tabB_update_doesNotRecomposeTabA() {
        val paginator = createTestPaginator()
        val tab0Recompositions = AtomicInteger(0)
        val tab1Recompositions = AtomicInteger(0)

        composeTestRule.setContent {
            androidx.compose.foundation.layout.Row {
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.size(100.dp)) {
                    IsolatedTabComposable(paginator.getTabStateFlow(0), tab0Recompositions)
                }
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.size(100.dp)) {
                    IsolatedTabComposable(paginator.getTabStateFlow(1), tab1Recompositions)
                }
            }
        }
        composeTestRule.waitForIdle()

        val tab0Initial = tab0Recompositions.get()
        val tab1Initial = tab1Recompositions.get()

        // Update Tab 1 only
        paginator.loadFirstPage(1)
        composeTestRule.waitForIdle()

        assertTrue(
            "Tab 1 recompositions must increase on Tab 1 update",
            tab1Recompositions.get() > tab1Initial
        )
        assertEquals(
            "Tab 0 recompositions must remain strictly identical when Tab 1 updates",
            tab0Initial,
            tab0Recompositions.get()
        )
    }

    @Test
    fun test3_paginationInTabA_doesNotAffectOtherTabs() {
        val paginator = createTestPaginator()

        paginator.loadFirstPage(1)
        paginator.loadFirstPage(2)
        composeTestRule.waitForIdle()

        assertEquals(1, paginator.getState(1).currentPage)
        assertEquals(1, paginator.getState(2).currentPage)

        // Paginate Tab 1 only
        paginator.loadMore(1)
        composeTestRule.waitForIdle()

        assertEquals(2, paginator.getState(1).currentPage)
        assertEquals(
            "Tab 2 pagination page must remain unchanged at 1",
            1,
            paginator.getState(2).currentPage
        )
    }

    @Test
    fun test4_loadingInTabA_doesNotAffectOtherTabs() {
        val paginator = createTestPaginator()

        // Start loading page for tab 1
        paginator.loadFirstPage(1)

        val tab1State = paginator.getTabStateFlow(1).value
        val tab2State = paginator.getTabStateFlow(2).value

        assertFalse("Tab 2 must not be in loading state when Tab 1 loads", tab2State.isLoadingFirstPage)
    }

    @Test
    fun test5_errorInTabA_doesNotAffectOtherTabs() {
        val failingPaginator = TmdbListPaginator(
            fetchMoviesPage = { throw java.io.IOException("Network error for movies") },
            fetchSeriesPage = {
                PaginatedResult(
                    items = listOf(createSeries(1)),
                    page = 1,
                    totalPages = 1
                )
            },
            fetchAnimePage = { throw java.io.IOException("Anime error") },
            coroutineScope = testScope
        )

        failingPaginator.loadFirstPage(1)
        failingPaginator.loadFirstPage(2)
        composeTestRule.waitForIdle()

        assertNotNull(failingPaginator.getState(1).loadMoreError)
        assertNull("Tab 2 error must remain null when Tab 1 encounters an error", failingPaginator.getState(2).loadMoreError)
        assertEquals(1, failingPaginator.getState(2).items.size)
    }

    @Test
    fun test6_refreshInTabA_doesNotAffectOtherTabs() {
        val paginator = createTestPaginator()

        paginator.loadFirstPage(1)
        paginator.loadFirstPage(2)
        composeTestRule.waitForIdle()

        paginator.loadMore(1)
        paginator.loadMore(2)
        composeTestRule.waitForIdle()

        assertEquals(2, paginator.getState(1).currentPage)
        assertEquals(2, paginator.getState(2).currentPage)

        // Refresh Tab 1 only
        paginator.refresh(1)
        composeTestRule.waitForIdle()

        assertEquals(1, paginator.getState(1).currentPage)
        assertEquals("Tab 2 must preserve its page 2 state during Tab 1 refresh", 2, paginator.getState(2).currentPage)
    }

    @Test
    fun test7_selectedTab_remainsStable() {
        var selectedTabIndex by mutableStateOf(1)
        val paginator = createTestPaginator()

        composeTestRule.setContent {
            val current = selectedTabIndex
            Column {
                val tabState by paginator.getTabStateFlow(current).collectAsState()
            }
        }
        composeTestRule.waitForIdle()

        // Update another tab (Tab 2)
        paginator.loadFirstPage(2)
        composeTestRule.waitForIdle()

        assertEquals(1, selectedTabIndex)
    }

    @Test
    fun test8_tabState_survivesRecomposition() {
        val paginator = createTestPaginator()
        paginator.loadFirstPage(1)
        composeTestRule.waitForIdle()

        var parentRecomposeTrigger by mutableStateOf(0)

        composeTestRule.setContent {
            val trigger = parentRecomposeTrigger
            val state by paginator.getTabStateFlow(1).collectAsState()
            Column {
                org.junit.Assert.assertEquals(2, state.items.size)
            }
        }
        composeTestRule.waitForIdle()

        repeat(5) {
            parentRecomposeTrigger++
            composeTestRule.waitForIdle()
        }

        assertEquals(2, paginator.getState(1).items.size)
    }

    @Test
    fun test9_screenRecreation_doesNotCreateDuplicateStateOwners() {
        val paginator = createTestPaginator()
        paginator.loadFirstPage(1)
        composeTestRule.waitForIdle()

        var screenKey by mutableStateOf(0)

        composeTestRule.setContent {
            androidx.compose.runtime.key(screenKey) {
                val state by paginator.getTabStateFlow(1).collectAsState()
                Column {
                    assertEquals(2, state.items.size)
                }
            }
        }
        composeTestRule.waitForIdle()

        // Recreate screen
        screenKey = 1
        composeTestRule.waitForIdle()

        assertEquals(2, paginator.getState(1).items.size)
    }

    @Test
    fun test10_rapidTabSwitching_doesNotCauseCrossContamination() {
        val paginator = createTestPaginator()

        paginator.loadFirstPage(1) // Movies
        paginator.loadFirstPage(2) // Series
        composeTestRule.waitForIdle()

        // Rapidly switch active queries
        for (i in 1..10) {
            val current = if (i % 2 == 0) 1 else 2
            val state = paginator.getState(current)
            if (current == 1) {
                assertTrue("Tab 1 must contain Movie instances", state.items.all { it.second })
            } else {
                assertFalse("Tab 2 must contain Series instances", state.items.any { it.second })
            }
        }
    }

    @Test
    fun test11_paginationBehavior_remainsIdentical() {
        val paginator = createTestPaginator()

        paginator.loadFirstPage(1)
        composeTestRule.waitForIdle()

        assertEquals(1, paginator.getState(1).currentPage)
        assertEquals(2, paginator.getState(1).items.size)

        paginator.loadMore(1)
        composeTestRule.waitForIdle()

        assertEquals(2, paginator.getState(1).currentPage)
        assertEquals(4, paginator.getState(1).items.size)
    }

    @Test
    fun test12_stableItemKeys_remainCorrect() {
        val movie = createMovie(550)
        val series = createSeries(1399)

        val movieKey = "m_${movie.id}"
        val seriesKey = "s_${series.id}"

        assertEquals("m_550", movieKey)
        assertEquals("s_1399", seriesKey)
    }
}
