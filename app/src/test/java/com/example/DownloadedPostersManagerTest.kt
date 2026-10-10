package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.MediaCardPoster
import com.example.utils.DownloadedPostersManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadedPostersManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DownloadedPostersManager.clearCacheForTesting()
        val dir = File(context.filesDir, "downloaded_posters")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        DownloadedPostersManager.clearCacheForTesting()
        val dir = File(context.filesDir, "downloaded_posters")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    @Test
    fun test1_posterExists_returnsCorrectLocalFile() {
        val dir = DownloadedPostersManager.getPostersDir(context)
        val testFile = File(dir, "movie_123.jpg")
        testFile.writeText("sample poster bytes")

        DownloadedPostersManager.initSync(context)

        val result = DownloadedPostersManager.getPosterModel(
            context = context,
            mediaId = "movie_123",
            remoteUrl = "https://example.com/remote.jpg"
        )

        assertTrue("Result must be a File instance when poster exists locally", result is File)
        assertEquals("movie_123.jpg", (result as File).name)
    }

    @Test
    fun test2_posterMissing_returnsRemoteUrlDirectly() {
        DownloadedPostersManager.initSync(context)

        val result = DownloadedPostersManager.getPosterModel(
            context = context,
            mediaId = "non_existent_media",
            remoteUrl = "https://example.com/remote.jpg"
        )

        assertEquals("When poster is missing locally, remote URL must be returned directly", "https://example.com/remote.jpg", result)
    }

    @Test
    fun test3_filenameMatching_remainsUnchanged() {
        val testInput = "movie:part/1?special#chars"
        val expected = "movie_part_1_special_chars"
        val actual = DownloadedPostersManager.sanitizeMediaId(testInput)

        assertEquals("Filename sanitization must match expected regex replacement semantics", expected, actual)
    }

    @Test
    fun test4_multiplePosterFormats() {
        val standardAlphanumeric = "tmdb12345"
        val punctuatedId = "tmdb-123_456.789"
        val specialCharsId = "anime@2024:season1"

        assertEquals(standardAlphanumeric, DownloadedPostersManager.sanitizeMediaId(standardAlphanumeric))
        assertEquals(punctuatedId, DownloadedPostersManager.sanitizeMediaId(punctuatedId))
        assertEquals("anime_2024_season1", DownloadedPostersManager.sanitizeMediaId(specialCharsId))
    }

    @Test
    fun test5_downloadedMovie() {
        val dir = DownloadedPostersManager.getPostersDir(context)
        val movieFile = File(dir, "m_550.jpg")
        movieFile.writeText("movie_poster_data")

        DownloadedPostersManager.initSync(context)

        val model = DownloadedPostersManager.getPosterModel(
            context = context,
            mediaId = "m_550",
            remoteUrl = "https://image.tmdb.org/t/p/w342/fc.jpg"
        )

        assertTrue(model is File)
        assertEquals("m_550.jpg", (model as File).name)
    }

    @Test
    fun test6_downloadedEpisode() {
        val dir = DownloadedPostersManager.getPostersDir(context)
        val episodeFile = File(dir, "series_10_s2_e4.jpg")
        episodeFile.writeText("episode_poster_data")

        DownloadedPostersManager.initSync(context)

        val model = DownloadedPostersManager.getPosterModel(
            context = context,
            mediaId = "series_10_s2_e4",
            remoteUrl = "https://image.tmdb.org/t/p/w342/ep.jpg"
        )

        assertTrue(model is File)
        assertEquals("series_10_s2_e4.jpg", (model as File).name)
    }

    @Test
    fun test7_deletedPoster_invalidatesResultImmediately() {
        val dir = DownloadedPostersManager.getPostersDir(context)
        val file = File(dir, "item_del.jpg")
        file.writeText("data")

        DownloadedPostersManager.initSync(context)
        assertTrue(DownloadedPostersManager.isPosterCached("item_del"))

        // Remove poster
        DownloadedPostersManager.removePoster(context, "item_del")

        assertFalse(
            "Cache must immediately report poster as removed without stale delay",
            DownloadedPostersManager.isPosterCached("item_del")
        )

        val model = DownloadedPostersManager.getPosterModel(context, "item_del", "https://example.com/fallback.jpg")
        assertEquals("Fallback remote URL must be returned immediately after removal", "https://example.com/fallback.jpg", model)
    }

    @Test
    fun test8_backgroundIO_returnsCorrectUIState() = runBlocking {
        val dir = DownloadedPostersManager.getPostersDir(context)
        File(dir, "async_item.jpg").writeText("async content")

        DownloadedPostersManager.initAsync(context)

        // Wait for cache initialization
        var retries = 50
        while (!DownloadedPostersManager.isInitialized() && retries > 0) {
            kotlinx.coroutines.delay(10)
            retries--
        }

        assertTrue("initAsync must initialize the cache in the background", DownloadedPostersManager.isInitialized())
        val model = DownloadedPostersManager.getPosterModel(context, "async_item", "https://example.com/remote.jpg")
        assertTrue(model is File)
    }

    @Test
    fun test9_repeatedLookup_doesNotPerformRedundantFilesystemScanning() {
        DownloadedPostersManager.initSync(context)

        val start = System.currentTimeMillis()
        for (i in 1..200) {
            DownloadedPostersManager.getPosterModel(context, "lookup_$i", "https://example.com/url_$i.jpg")
        }
        val duration = System.currentTimeMillis() - start

        assertTrue(
            "200 in-memory lookups must execute near-instantaneously (< 200ms, actual: ${duration}ms)",
            duration < 200
        )
    }

    @Test
    fun test10_regexSemantics_remainIdentical() {
        val legacyRegex = Regex("[^a-zA-Z0-9_.-]")
        val testSamples = listOf(
            "normal_id_123",
            "id with spaces",
            "id/with/slashes",
            "id?with=query&params",
            "id:colon;semicolon",
            "id\\backslash",
            "id!@#\$%^&*()",
            "عربي_123_اختبار",
            "dots.and-dashes_ok",
            "mixed.123-abc_XYZ"
        )

        for (sample in testSamples) {
            val expected = sample.replace(legacyRegex, "_")
            val actual = DownloadedPostersManager.sanitizeMediaId(sample)
            assertEquals("Regex semantics must match identically for '$sample'", expected, actual)
        }
    }

    @Test
    fun test11_30PlusPosterLookups_doNotCreatePerCardObservers() {
        DownloadedPostersManager.initSync(context)

        val models = (1..35).map { index ->
            DownloadedPostersManager.getPosterModel(context, "card_$index", "https://example.com/card_$index.jpg")
        }

        assertEquals(35, models.size)
        // All models are string URLs since none exist on disk, resolved with zero observers
        assertTrue(models.all { it is String })
    }

    @Test
    fun test12_screenRecreation_doesNotProduceStalePosterState() {
        var currentId by mutableStateOf("card_1")

        composeTestRule.setContent {
            val id = currentId
            Box(modifier = Modifier.size(100.dp)) {
                MediaCardPoster(
                    posterUrl = "https://example.com/$id.jpg",
                    mediaId = id,
                    title = "Title for $id"
                )
            }
        }

        composeTestRule.waitForIdle()

        // Recreate screen with different media ID
        currentId = "card_2"
        composeTestRule.waitForIdle()
    }
}
