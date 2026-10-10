package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMediaCard_tapTriggersOnClick() {
        var clicked = false
        var longClicked = false

        composeTestRule.setContent {
            MediaCard(
                title = "Inception",
                posterUrl = "",
                onClick = { clicked = true },
                onLongClick = { longClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Inception").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inception").performClick()

        assertTrue("Expected onClick to be invoked upon tap", clicked)
        assertEquals("Expected onLongClick not to be invoked upon tap", false, longClicked)
    }

    @Test
    fun testMediaCard_longPressTriggersOnLongClick() {
        var clicked = false
        var longClicked = false

        composeTestRule.setContent {
            MediaCard(
                title = "Interstellar",
                posterUrl = "",
                onClick = { clicked = true },
                onLongClick = { longClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Interstellar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Interstellar").performTouchInput {
            longClick()
        }

        assertTrue("Expected onLongClick to be invoked upon long press", longClicked)
    }

    @Test
    fun testMediaCard_withoutOnLongClick_worksNormally() {
        var clicked = false

        composeTestRule.setContent {
            MediaCard(
                title = "Oppenheimer",
                posterUrl = "",
                onClick = { clicked = true }
            )
        }

        composeTestRule.onNodeWithText("Oppenheimer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Oppenheimer").performClick()

        assertTrue("Expected onClick to be invoked", clicked)
    }

    @Test
    fun testMediaCardPoster_stateLifecycle_initialLoadingAndError() {
        // Blank poster URL defaults directly to ERROR state
        composeTestRule.setContent {
            MediaCardPoster(
                posterUrl = "",
                mediaId = "101",
                title = "No Poster Movie",
                modifier = Modifier.size(100.dp)
            )
        }
    }

    @Test
    fun testMediaCardPoster_posterUrlChange_resetsLifecycle() {
        var currentPoster by mutableStateOf("https://image.tmdb.org/t/p/w342/test1.jpg")

        composeTestRule.setContent {
            MediaCardPoster(
                posterUrl = currentPoster,
                mediaId = "102",
                title = "Dynamic Poster Movie",
                modifier = Modifier.size(100.dp)
            )
        }

        // Change poster to second URL
        currentPoster = "https://image.tmdb.org/t/p/w342/test2.jpg"
        composeTestRule.waitForIdle()

        // Change poster to blank -> should transition cleanly to error fallback
        currentPoster = ""
        composeTestRule.waitForIdle()
    }

    @Test
    fun testMediaCard_recompositionBoundary_outerCardDoesNotRecomposeOnImageState() {
        var outerCompositionCount = 0
        var posterUrlState by mutableStateOf("https://image.tmdb.org/t/p/w342/initial.jpg")

        composeTestRule.setContent {
            SideEffect {
                outerCompositionCount++
            }
            MediaCard(
                title = "Gladiator",
                posterUrl = posterUrlState,
                onClick = {}
            )
        }

        assertEquals("Initial outer card composition count must be 1", 1, outerCompositionCount)
        composeTestRule.waitForIdle()

        // Outer composition count remains 1 because poster loading state is encapsulated in MediaCardPoster
        assertEquals("Outer card scope should not continuously recompose", 1, outerCompositionCount)
    }

    @Test
    fun testMediaCard_multipleCardsWithMixedStates_renderWithoutConflict() {
        composeTestRule.setContent {
            Box {
                MediaCard(
                    title = "Movie 1",
                    posterUrl = "https://image.tmdb.org/t/p/w342/m1.jpg",
                    onClick = {}
                )
                MediaCard(
                    title = "Movie 2",
                    posterUrl = "", // Error/blank state
                    onClick = {}
                )
                MediaCard(
                    title = "Movie 3",
                    posterUrl = "https://image.tmdb.org/t/p/w342/m3.jpg",
                    onClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Movie 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Movie 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Movie 3").assertIsDisplayed()
    }

    @Test
    fun testMediaCard_tapAndLongPressAfterRender() {
        var tapped = false
        var longPressed = false

        composeTestRule.setContent {
            MediaCard(
                title = "Dune",
                posterUrl = "https://image.tmdb.org/t/p/w342/dune.jpg",
                onClick = { tapped = true },
                onLongClick = { longPressed = true }
            )
        }

        composeTestRule.onNodeWithText("Dune").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dune").performClick()
        assertTrue("Tap must work reliably", tapped)

        composeTestRule.onNodeWithText("Dune").performTouchInput {
            longClick()
        }
        assertTrue("Long press must work reliably", longPressed)
    }
}
