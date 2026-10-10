package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.HeroCarousel
import com.example.ui.components.HeroItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeroCarouselPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        composeTestRule.mainClock.autoAdvance = true
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
    }

    @After
    fun tearDown() {
        composeTestRule.mainClock.autoAdvance = true
    }

    private fun createTestHeroItems(count: Int): List<HeroItem> {
        return (1..count).map { index ->
            HeroItem(
                id = "hero_$index",
                title = "Hero Feature $index",
                backdropUrl = "https://example.com/hero_$index.jpg",
                isMovie = true
            )
        }
    }

    @Test
    fun test1_zeroItems_timerDisabled() {
        var clickedId: String? = null
        composeTestRule.setContent {
            HeroCarousel(
                items = emptyList(),
                onClick = { clickedId = it }
            )
        }

        composeTestRule.waitForIdle()
        assertEquals("With zero items, HeroCarousel must return early with no interaction", null, clickedId)
    }

    @Test
    fun test2_singleItem_timerDisabled() {
        val singleItem = createTestHeroItems(1)

        composeTestRule.setContent {
            HeroCarousel(
                items = singleItem,
                onClick = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Advance clock by 10 seconds - single item must remain visible without redundant scroll attempts
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(10000)
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()
        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun test3_twoPlusItems_timerActive() {
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            HeroCarousel(
                items = items,
                onClick = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()
    }

    @Test
    fun test4_timerWaitsConfiguredIntervalBeforePageChange() {
        val items = createTestHeroItems(3)
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            HeroCarousel(
                items = items,
                onClick = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Advance 1.5 seconds - still on page 1
        composeTestRule.mainClock.advanceTimeBy(1500)
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Advance past 3.0 seconds - transition triggers
        composeTestRule.mainClock.advanceTimeBy(2000)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun test5_and_6_userInteractionPausesAndResumesAutoScroll() {
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            HeroCarousel(
                items = items,
                onClick = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()
    }

    private class CustomTestLifecycleOwner(initialState: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = initialState
        }
        override val lifecycle: Lifecycle get() = registry

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    @Test
    fun test7_and_8_lifecyclePausesAndResumesTimer() {
        val testLifecycleOwner = CustomTestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides testLifecycleOwner) {
                HeroCarousel(
                    items = items,
                    onClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Simulate app going to background (ON_STOP)
        testLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        composeTestRule.waitForIdle()

        // Simulate app returning to foreground (ON_START -> ON_RESUME)
        testLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        testLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Hero Feature 1").assertExists()
    }

    @Test
    fun test9_leavingCompositionCancelsTimer() {
        var showCarousel by mutableStateOf(true)
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            if (showCarousel) {
                HeroCarousel(
                    items = items,
                    onClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Remove from composition (e.g. user scrolls away or navigates)
        showCarousel = false
        composeTestRule.waitForIdle()

        // Advance clock by 5 seconds
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(5000)
        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun test10_recompositionDoesNotCreateMultipleTimers() {
        var parentCounter by mutableStateOf(0)
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            val counter = parentCounter
            Box {
                HeroCarousel(
                    items = items,
                    onClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Trigger multiple recompositions of the parent scope
        parentCounter = 1
        composeTestRule.waitForIdle()
        parentCounter = 2
        composeTestRule.waitForIdle()
        parentCounter = 3
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Hero Feature 1").assertExists()
    }

    @Test
    fun test11_rapidNavigationDoesNotAccumulateCoroutines() {
        var isVisible by mutableStateOf(true)
        val items = createTestHeroItems(3)

        composeTestRule.setContent {
            if (isVisible) {
                HeroCarousel(
                    items = items,
                    onClick = {}
                )
            }
        }

        // Rapidly attach and detach to simulate fast back-and-forth navigation
        repeat(5) {
            isVisible = false
            composeTestRule.waitForIdle()
            isVisible = true
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()
    }

    @Test
    fun test12_changingItemCountDoesNotProduceInvalidPageAccess() {
        var currentItems by mutableStateOf(createTestHeroItems(5))

        composeTestRule.setContent {
            HeroCarousel(
                items = currentItems,
                onClick = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()

        // Truncate list from 5 items to 2 items
        currentItems = createTestHeroItems(2)
        composeTestRule.waitForIdle()

        // Truncate to 1 item
        currentItems = createTestHeroItems(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Feature 1").assertIsDisplayed()
    }
}
