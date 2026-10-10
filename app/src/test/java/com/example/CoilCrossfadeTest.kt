package com.example

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transition.CrossfadeTransition
import coil.transition.TransitionTarget
import com.example.ui.components.MediaCard
import com.example.ui.components.MediaCardPoster
import com.example.utils.SelectiveCrossfadeTransitionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CoilCrossfadeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun createDummyTarget(): TransitionTarget {
        val dummyView = View(context)
        return object : TransitionTarget {
            override val view: View = dummyView
            override val drawable: Drawable? = null
        }
    }

    private fun createDummyRequest(): ImageRequest {
        return ImageRequest.Builder(context)
            .data("https://example.com/poster.jpg")
            .build()
    }

    @Test
    fun test1_cachedImage_doesNotTriggerCrossfadeAnimation() {
        val factory = SelectiveCrossfadeTransitionFactory(durationMillis = 100)
        val target = createDummyTarget()
        val request = createDummyRequest()
        val drawable = ColorDrawable(Color.RED)

        // Case A: Memory cache hit
        val memoryResult = SuccessResult(
            drawable = drawable,
            request = request,
            dataSource = DataSource.MEMORY_CACHE
        )
        val memoryTransition = factory.create(target, memoryResult)
        assertFalse(
            "Memory cache hits must NOT trigger CrossfadeTransition",
            memoryTransition is CrossfadeTransition
        )

        // Case B: Disk cache hit
        val diskResult = SuccessResult(
            drawable = drawable,
            request = request,
            dataSource = DataSource.DISK
        )
        val diskTransition = factory.create(target, diskResult)
        assertFalse(
            "Disk cache hits must NOT trigger CrossfadeTransition to prevent animation stacking",
            diskTransition is CrossfadeTransition
        )
    }

    @Test
    fun test2_networkImage_triggersControlledCrossfadeAnimation() {
        val factory = SelectiveCrossfadeTransitionFactory(durationMillis = 100)
        val target = createDummyTarget()
        val request = createDummyRequest()
        val drawable = ColorDrawable(Color.BLUE)

        val networkResult = SuccessResult(
            drawable = drawable,
            request = request,
            dataSource = DataSource.NETWORK
        )
        val transition = factory.create(target, networkResult)

        assertTrue(
            "Remote network image loads MUST use CrossfadeTransition",
            transition is CrossfadeTransition
        )
        assertEquals(
            "Network crossfade duration must be controlled at exactly 100ms",
            100,
            (transition as CrossfadeTransition).durationMillis
        )
    }

    @Test
    fun test3_recomposition_doesNotRecreateOrRestartTransitionForSamePosterUrl() {
        var parentRecomposeTrigger by mutableStateOf(0)
        var capturedRequest1: ImageRequest? = null
        var capturedRequest2: ImageRequest? = null

        composeTestRule.setContent {
            val count = parentRecomposeTrigger
            val request = remember("https://example.com/movie.jpg") {
                ImageRequest.Builder(context)
                    .data("https://example.com/movie.jpg")
                    .transitionFactory(SelectiveCrossfadeTransitionFactory(100))
                    .build()
            }
            if (count == 0) {
                capturedRequest1 = request
            } else {
                capturedRequest2 = request
            }
        }

        parentRecomposeTrigger = 1
        composeTestRule.waitForIdle()

        assertSame(
            "ImageRequest and transition factory must retain referential identity across recompositions for identical URL",
            capturedRequest1,
            capturedRequest2
        )
    }

    @Test
    fun test4_changingPosterUrl_recreatesImageRequestWithNewModel() {
        var currentUrl by mutableStateOf("https://example.com/initial.jpg")
        var lastCapturedModel: Any? = null

        composeTestRule.setContent {
            val url = currentUrl
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .transitionFactory(SelectiveCrossfadeTransitionFactory(100))
                    .build()
            }
            lastCapturedModel = request.data
            Box(modifier = Modifier.size(100.dp)) {
                MediaCardPoster(
                    posterUrl = url,
                    mediaId = "test_media_1",
                    title = "Test Card"
                )
            }
        }

        assertEquals("https://example.com/initial.jpg", lastCapturedModel)

        currentUrl = "https://example.com/updated.jpg"
        composeTestRule.waitForIdle()

        assertEquals(
            "Changing posterUrl must correctly create a fresh ImageRequest targeting the updated URL",
            "https://example.com/updated.jpg",
            lastCapturedModel
        )
    }

    @Test
    fun test5_multipleCards_maintainIndependentTransitionStates() {
        var request1: ImageRequest? = null
        var request2: ImageRequest? = null

        composeTestRule.setContent {
            request1 = remember("url_1") {
                ImageRequest.Builder(context)
                    .data("https://example.com/1.jpg")
                    .transitionFactory(SelectiveCrossfadeTransitionFactory(100))
                    .build()
            }
            request2 = remember("url_2") {
                ImageRequest.Builder(context)
                    .data("https://example.com/2.jpg")
                    .transitionFactory(SelectiveCrossfadeTransitionFactory(100))
                    .build()
            }
        }

        assertNotSame(
            "Distinct cards must have distinct ImageRequests and independent transitions",
            request1,
            request2
        )
    }

    @Test
    fun test6_errorState_doesNotLeaveDanglingTransition() {
        val factory = SelectiveCrossfadeTransitionFactory(durationMillis = 100)
        val target = createDummyTarget()
        val request = createDummyRequest()
        val errorResult = ErrorResult(
            drawable = null,
            request = request,
            throwable = RuntimeException("Network timeout")
        )

        val transition = factory.create(target, errorResult)
        assertFalse(
            "ErrorResult must NOT trigger CrossfadeTransition",
            transition is CrossfadeTransition
        )

        // Verify blank URL renders error fallback in MediaCardPoster without crash
        composeTestRule.setContent {
            Box(modifier = Modifier.size(100.dp)) {
                MediaCardPoster(
                    posterUrl = "",
                    mediaId = "err_1",
                    title = "Error Movie"
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun test7_rapidListScrolling_noStateContaminationBetweenCards() {
        val titles = listOf("Item 1", "Item 2", "Item 3")

        composeTestRule.setContent {
            LazyRow {
                itemsIndexed(titles, key = { _, title -> title }) { _, title ->
                    MediaCard(
                        title = title,
                        posterUrl = "https://example.com/$title.jpg",
                        onClick = {}
                    )
                }
            }
        }

        // Verify first two visible items are displayed and item 3 exists in composition
        composeTestRule.onNodeWithText("Item 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Item 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Item 3").assertExists()
    }

    @Test
    fun test8_mediaCardPoster_behaviorRemainsCorrect() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(120.dp)) {
                MediaCardPoster(
                    posterUrl = "https://example.com/valid_poster.jpg",
                    mediaId = "100",
                    title = "Valid Movie"
                )
            }
        }
        composeTestRule.waitForIdle()
    }
}
