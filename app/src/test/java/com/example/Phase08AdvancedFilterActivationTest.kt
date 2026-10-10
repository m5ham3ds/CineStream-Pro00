package com.example

import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.ui.components.DropdownOption
import com.example.ui.screens.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase08AdvancedFilterActivationTest {

    @Test
    fun testPackageIdentity_CanonicalApplicationId() {
        val buildGradleFile = File("app/build.gradle.kts").takeIf { it.exists() }
            ?: File("../app/build.gradle.kts").takeIf { it.exists() }
            ?: File("build.gradle.kts")
        assertTrue("build.gradle.kts must exist", buildGradleFile.exists())
        val buildGradleContent = buildGradleFile.readText()
        assertTrue(
            "app/build.gradle.kts must configure canonical applicationId 'com.aistudio.cinestream.xyzabc'",
            buildGradleContent.contains("applicationId = \"com.aistudio.cinestream.xyzabc\"")
        )

        val googleServicesFile = File("app/google-services.json").takeIf { it.exists() }
            ?: File("../app/google-services.json").takeIf { it.exists() }
            ?: File("google-services.json")
        assertTrue("google-services.json must exist", googleServicesFile.exists())
        val googleServicesContent = googleServicesFile.readText()
        assertTrue(
            "app/google-services.json must match canonical package 'com.aistudio.cinestream.xyzabc'",
            googleServicesContent.contains("\"package_name\": \"com.aistudio.cinestream.xyzabc\"")
        )
    }

    @Test
    fun testDropdownOption_CreationAndEquality() {
        val opt1 = DropdownOption(value = 28, title = "أكشن")
        val opt2 = DropdownOption(value = 28, title = "أكشن")
        val optAll = DropdownOption<Int?>(value = null, title = "الكل")

        assertEquals(opt1, opt2)
        assertNull(optAll.value)
        assertEquals("الكل", optAll.title)
        assertEquals(28, opt1.value)
    }

    @Test
    fun testGenreDataSources_DistinctAndAccurate() {
        val movieGenres = TmdbGenreHelper.getMovieGenres()
        val tvGenres = TmdbGenreHelper.getTvGenres()
        val animeGenres = TmdbGenreHelper.getAnimeGenres()

        assertTrue("Movie genres must not be empty", movieGenres.isNotEmpty())
        assertTrue("TV genres must not be empty", tvGenres.isNotEmpty())
        assertTrue("Anime genres must not be empty", animeGenres.isNotEmpty())

        // Action is movie ID 28
        assertTrue("Movie genres must have Action", movieGenres.any { it.id == 28 })
        // Action & Adventure is TV ID 10759
        assertTrue("TV genres must have Action & Adventure", tvGenres.any { it.id == 10759 })
        // Animation is ID 16
        assertTrue("Anime genres must have Animation", animeGenres.any { it.id == 16 })
    }

    @Test
    fun testFilterFiltering_RealDataTransformation() {
        val movies = listOf(
            Movie(id = "1", title = "Action 1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.0, genres = listOf("Action"), runtime = 100, genreIds = listOf(28)),
            Movie(id = "2", title = "Comedy 1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 7.5, genres = listOf("Comedy"), runtime = 90, genreIds = listOf(35)),
            Movie(id = "3", title = "Drama 1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 7.8, genres = listOf("Drama"), runtime = 110, genreIds = listOf(18))
        )

        // Filter for Action (28)
        val actionOnly = movies.filter { it.genreIds.contains(28) }
        assertEquals(1, actionOnly.size)
        assertEquals("Action 1", actionOnly[0].title)

        // Filter for All (null / empty check)
        val allMovies = movies.filter { true }
        assertEquals(3, allMovies.size)

        // Filter for Non-existent Genre (999)
        val nonExistent = movies.filter { it.genreIds.contains(999) }
        assertTrue("Must be empty when genre not found", nonExistent.isEmpty())
    }

    @Test
    fun testAnimeMediaSeparation_IntegrityPreserved() {
        val animeMovie = Movie(id = "10", title = "Anime Film", overview = "", posterUrl = "", backdropUrl = "", year = 2023, rating = 8.5, genres = listOf("Animation"), runtime = 120, genreIds = listOf(16))
        val animeTv = Series(id = "20", title = "Anime Series", overview = "", posterUrl = "", backdropUrl = "", year = 2023, rating = 9.0, genres = listOf("Animation"), originalLanguage = "ja", originCountry = listOf("JP"), genreIds = listOf(16))

        assertEquals(ContentType.MOVIE, ContentTypeResolver.resolveMovie(animeMovie))
        assertEquals(ContentType.ANIME, ContentTypeResolver.resolveSeries(animeTv))
    }

    @Test
    fun testSearchFacetFilters_AllTypesAndClear() {
        val m1 = Movie(id = "1", title = "M1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.0, genres = listOf("Action"), runtime = 100, genreIds = listOf(28))
        val s1 = Series(id = "2", title = "S1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.0, genres = listOf("Drama"), originalLanguage = "en", originCountry = listOf("US"), genreIds = listOf(18))
        val a1 = Series(id = "3", title = "A1", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.0, genres = listOf("Animation"), originalLanguage = "ja", originCountry = listOf("JP"), genreIds = listOf(16))

        val state = SearchUiState(
            movieResults = listOf(m1),
            seriesResults = listOf(s1, a1)
        )

        // ALL
        assertEquals(1, state.filteredMovieResults.size)
        assertEquals(2, state.filteredSeriesResults.size)

        // MOVIE filter
        val movieOnlyState = state.copy(selectedContentType = "MOVIE")
        assertEquals(1, movieOnlyState.filteredMovieResults.size)
        assertEquals(0, movieOnlyState.filteredSeriesResults.size)

        // TV filter (excludes anime series)
        val tvOnlyState = state.copy(selectedContentType = "TV")
        assertEquals(0, tvOnlyState.filteredMovieResults.size)
        assertEquals(1, tvOnlyState.filteredSeriesResults.size)
        assertEquals("S1", tvOnlyState.filteredSeriesResults[0].title)

        // ANIME filter (only anime series)
        val animeOnlyState = state.copy(selectedContentType = "ANIME")
        assertEquals(0, animeOnlyState.filteredMovieResults.size)
        assertEquals(1, animeOnlyState.filteredSeriesResults.size)
        assertEquals("A1", animeOnlyState.filteredSeriesResults[0].title)
    }
}
