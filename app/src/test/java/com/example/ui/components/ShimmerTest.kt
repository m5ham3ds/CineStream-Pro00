package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShimmerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testCardShimmer_rendersWithoutException() {
        composeTestRule.setContent {
            CardShimmer(modifier = Modifier.size(100.dp).testTag("shimmer_card"))
        }
        composeTestRule.onNodeWithTag("shimmer_card").assertIsDisplayed()
    }

    @Test
    fun testShimmerEffectModifier_rendersWithoutException() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(80.dp).shimmerEffect().testTag("shimmer_box"))
        }
        composeTestRule.onNodeWithTag("shimmer_box").assertIsDisplayed()
    }

    @Test
    fun testMultipleShimmerInstances_coexistWithoutConflict() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(50.dp).testTag("card_1")) {
                CardShimmer()
            }
            Box(modifier = Modifier.size(50.dp).testTag("card_2")) {
                CardShimmer()
            }
            Box(modifier = Modifier.size(50.dp).testTag("card_3")) {
                CardShimmer()
            }
        }
        composeTestRule.onNodeWithTag("card_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("card_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("card_3").assertIsDisplayed()
    }

    @Test
    fun testShimmerDrawPhase_doesNotTriggerRecompositionOnAnimationTicks() {
        var compositionCount = 0

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            SideEffect {
                compositionCount++
            }
            CardShimmer(modifier = Modifier.size(120.dp).testTag("animating_shimmer"))
        }

        assertEquals("Initial composition count must be 1", 1, compositionCount)

        // Advance animation clock through multiple animation frames (650 ms = half period)
        composeTestRule.mainClock.advanceTimeBy(650L)

        // Verify that advancing the animation clock does NOT cause recomposition
        assertEquals(
            "Draw-phase shimmer must not cause recomposition when animation advances",
            1,
            compositionCount
        )

        // Advance through full animation cycle (1300 ms)
        composeTestRule.mainClock.advanceTimeBy(650L)

        assertEquals(
            "Draw-phase shimmer must maintain 1 composition pass after full cycle",
            1,
            compositionCount
        )
    }

    @Test
    fun testShimmerDisappears_whenImageStateChanges() {
        var isLoaded by mutableStateOf(false)

        composeTestRule.setContent {
            Box(modifier = Modifier.size(100.dp)) {
                if (!isLoaded) {
                    CardShimmer(modifier = Modifier.size(100.dp).testTag("loading_shimmer"))
                } else {
                    Box(modifier = Modifier.size(100.dp).testTag("loaded_content"))
                }
            }
        }

        composeTestRule.onNodeWithTag("loading_shimmer").assertIsDisplayed()

        // Simulate image load completion
        isLoaded = true

        composeTestRule.onNodeWithTag("loaded_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("loading_shimmer").assertDoesNotExist()
    }
}
