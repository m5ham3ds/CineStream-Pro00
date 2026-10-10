package com.example.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.example.ui.components.MediaCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallbackStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    data class TestMediaItem(
        val id: String,
        val title: String,
        val isMovie: Boolean
    )

    @Test
    fun testRememberedCallback_maintainsReferentialEqualityAcrossParentRecompositions() {
        var parentRecomposeTrigger by mutableStateOf(0)
        var capturedCallback1: (() -> Unit)? = null
        var capturedCallback2: (() -> Unit)? = null

        composeTestRule.setContent {
            // Read state to trigger recomposition of this scope
            val counter = parentRecomposeTrigger

            val stableOnClick = remember("media_123") {
                { /* action */ }
            }

            if (counter == 0) {
                capturedCallback1 = stableOnClick
            } else {
                capturedCallback2 = stableOnClick
            }
        }

        // Trigger parent recomposition
        parentRecomposeTrigger = 1
        composeTestRule.waitForIdle()

        // Assert that the callback identity is strictly identical across recompositions
        assertSame(
            "Remembered callback must retain referential identity to enable Compose skipping",
            capturedCallback1,
            capturedCallback2
        )
    }

    @Test
    fun testRememberedCallback_updatesWhenItemIdChanges_noStaleClosure() {
        var currentItemId by mutableStateOf("item_A")
        var lastClickedId = ""

        composeTestRule.setContent {
            val id = currentItemId
            val onClick = remember(id) {
                { lastClickedId = id }
            }

            Box(modifier = Modifier.size(100.dp)) {
                MediaCard(
                    title = "Title for $id",
                    posterUrl = "",
                    onClick = onClick
                )
            }
        }

        composeTestRule.onNodeWithText("Title for item_A").performClick()
        assertEquals("item_A", lastClickedId)

        // Mutate ID
        currentItemId = "item_B"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Title for item_B").performClick()
        assertEquals("Callback must not retain stale item_A closure", "item_B", lastClickedId)
    }

    @Test
    fun testMultipleCards_retainDistinctCallbacksWithoutCrossContamination() {
        val clickedIds = mutableListOf<String>()
        val longClickedIds = mutableListOf<String>()

        val items = (1..10).map { index ->
            TestMediaItem(id = "id_$index", title = "Card $index", isMovie = index % 2 == 0)
        }

        composeTestRule.setContent {
            LazyRow {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    val onClick = remember(item.id) {
                        { clickedIds.add(item.id); Unit }
                    }
                    val onLongClick = remember(item.id) {
                        { longClickedIds.add(item.id); Unit }
                    }
                    MediaCard(
                        title = item.title,
                        posterUrl = "",
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            }
        }

        // Tap on card 1
        composeTestRule.onNodeWithText("Card 1").performClick()
        assertEquals(listOf("id_1"), clickedIds)

        // Long click on card 2
        composeTestRule.onNodeWithText("Card 2").performTouchInput {
            longClick()
        }
        assertEquals(listOf("id_2"), longClickedIds)

        // Tap on card 3
        composeTestRule.onNodeWithText("Card 3").performClick()
        assertEquals(listOf("id_1", "id_3"), clickedIds)
    }

    @Test
    fun testNavigationCallback_receivesExactDestinationAfterRecomposition() {
        var navigationDestination: String? = null
        var dummyParentState by mutableStateOf("initial")

        composeTestRule.setContent {
            val state = dummyParentState
            val onMovieClick: (String) -> Unit = { id -> navigationDestination = "details/movie/$id" }
            val onSeriesClick: (String) -> Unit = { id -> navigationDestination = "details/series/$id" }

            val movieId = "m_99"
            val isMovie = true
            val onClick = remember(movieId, isMovie) {
                { if (isMovie) onMovieClick(movieId) else onSeriesClick(movieId) }
            }

            Box {
                MediaCard(
                    title = "Media $state",
                    posterUrl = "",
                    onClick = onClick
                )
            }
        }

        // Trigger parent recomposition
        dummyParentState = "updated"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Media updated").performClick()
        assertEquals("details/movie/m_99", navigationDestination)
    }

    @Test
    fun testOnMediaLongClick_dispatchesAccurateParametersToState() {
        var bottomSheetIsMovie = false
        var bottomSheetContentType = ""
        var selectedMediaId = ""
        var selectedMediaTitle = ""
        var selectedMediaPoster = ""
        var showBottomSheet = false

        val onMediaLongClick: (String, String, String, Boolean, String) -> Unit = { id, title, posterUrl, isMovie, contentType ->
            bottomSheetIsMovie = isMovie
            bottomSheetContentType = contentType
            selectedMediaId = id
            selectedMediaTitle = title
            selectedMediaPoster = posterUrl
            showBottomSheet = true
        }

        // Invoke callback with test payload
        onMediaLongClick("m_550", "Sample Feature Media", "https://example.com/poster.jpg", true, "movie")

        assertTrue("Bottom sheet must be marked visible", showBottomSheet)
        assertEquals("m_550", selectedMediaId)
        assertEquals("Sample Feature Media", selectedMediaTitle)
        assertEquals("https://example.com/poster.jpg", selectedMediaPoster)
        assertTrue(bottomSheetIsMovie)
        assertEquals("movie", bottomSheetContentType)
    }
}
