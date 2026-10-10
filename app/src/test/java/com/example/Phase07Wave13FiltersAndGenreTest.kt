package com.example

import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.ui.screens.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase07Wave13FiltersAndGenreTest {

    @Test
    fun testTmdbGenreHelper_MovieGenres() {
        val actionNames = TmdbGenreHelper.getMovieGenreNames(listOf(28))
        assertEquals(listOf("أكشن"), actionNames)

        val multiGenres = TmdbGenreHelper.getMovieGenreNames(listOf(28, 35, 878))
        assertEquals(listOf("أكشن", "كوميديا", "خيال علمي"), multiGenres)

        val allMovieGenres = TmdbGenreHelper.getMovieGenres()
        assertTrue("Movie genres list should not be empty", allMovieGenres.isNotEmpty())
        assertTrue("Movie genres should contain Action (28)", allMovieGenres.any { it.id == 28 })
    }

    @Test
    fun testTmdbGenreHelper_TvAndAnimeGenres() {
        val tvNames = TmdbGenreHelper.getTvGenreNames(listOf(10759, 35))
        assertEquals(listOf("حركة ومغامرة", "كوميديا"), tvNames)

        val tvGenres = TmdbGenreHelper.getTvGenres()
        assertTrue("TV genres list should not be empty", tvGenres.isNotEmpty())

        val animeGenres = TmdbGenreHelper.getAnimeGenres()
        assertTrue("Anime genres list should not be empty", animeGenres.isNotEmpty())
    }

    @Test
    fun testContentTypeResolver_AnimeMoviesRemainMovies() {
        // Japanese animation movie (e.g., Spirited Away) must resolve to MOVIE
        val movieType = ContentTypeResolver.resolve(
            isMovie = true,
            genreIds = listOf(16),
            originalLanguage = "ja",
            originCountries = listOf("JP")
        )
        assertEquals(ContentType.MOVIE, movieType)

        val movie = Movie(
            id = "123",
            title = "Spirited Away",
            overview = "Anime movie",
            posterUrl = "",
            backdropUrl = "",
            year = 2001,
            rating = 8.5,
            genres = listOf("Animation"),
            runtime = 125,
            genreIds = listOf(16)
        )
        assertEquals(ContentType.MOVIE, ContentTypeResolver.resolveMovie(movie))
    }

    @Test
    fun testContentTypeResolver_AnimeSeriesResolvesToAnime() {
        // Japanese animation TV series must resolve to ANIME
        val animeType = ContentTypeResolver.resolve(
            isMovie = false,
            genreIds = listOf(16),
            originalLanguage = "ja",
            originCountries = listOf("JP")
        )
        assertEquals(ContentType.ANIME, animeType)

        val animeSeries = Series(
            id = "456",
            title = "Attack on Titan",
            overview = "Anime series",
            posterUrl = "",
            backdropUrl = "",
            year = 2013,
            rating = 9.0,
            genres = listOf("Animation"),
            originalLanguage = "ja",
            originCountry = listOf("JP"),
            genreIds = listOf(16)
        )
        assertEquals(ContentType.ANIME, ContentTypeResolver.resolveSeries(animeSeries))
    }

    @Test
    fun testContentTypeResolver_WesternSeriesResolvesToTv() {
        // Non-Japanese series or non-animation must resolve to TV
        val westernAnimation = ContentTypeResolver.resolve(
            isMovie = false,
            genreIds = listOf(16),
            originalLanguage = "en",
            originCountries = listOf("US")
        )
        assertEquals(ContentType.TV, westernAnimation)

        val liveAction = ContentTypeResolver.resolve(
            isMovie = false,
            genreIds = listOf(18),
            originalLanguage = "en",
            originCountries = listOf("US")
        )
        assertEquals(ContentType.TV, liveAction)
    }

    @Test
    fun testSearchUiState_FacetedFiltering() {
        val movie1 = Movie(
            id = "1",
            title = "Action Movie",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2024,
            rating = 8.0,
            genres = listOf("Action"),
            runtime = 120,
            genreIds = listOf(28)
        )
        val movie2 = Movie(
            id = "2",
            title = "Comedy Movie",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2024,
            rating = 7.5,
            genres = listOf("Comedy"),
            runtime = 100,
            genreIds = listOf(35)
        )
        val tvSeries = Series(
            id = "3",
            title = "Drama Series",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2023,
            rating = 8.2,
            genres = listOf("Drama"),
            originalLanguage = "en",
            genreIds = listOf(18)
        )
        val animeSeries = Series(
            id = "4",
            title = "Anime Show",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2022,
            rating = 8.8,
            genres = listOf("Animation"),
            originalLanguage = "ja",
            originCountry = listOf("JP"),
            genreIds = listOf(16)
        )

        val baseState = SearchUiState(
            movieResults = listOf(movie1, movie2),
            seriesResults = listOf(tvSeries, animeSeries)
        )

        // All filter
        assertEquals(2, baseState.filteredMovieResults.size)
        assertEquals(2, baseState.filteredSeriesResults.size)

        // Filter by MOVIE
        val movieFilterState = baseState.copy(selectedContentType = "MOVIE")
        assertEquals(2, movieFilterState.filteredMovieResults.size)
        assertEquals(0, movieFilterState.filteredSeriesResults.size)

        // Filter by TV (should exclude Anime)
        val tvFilterState = baseState.copy(selectedContentType = "TV")
        assertEquals(0, tvFilterState.filteredMovieResults.size)
        assertEquals(1, tvFilterState.filteredSeriesResults.size)
        assertEquals("Drama Series", tvFilterState.filteredSeriesResults[0].title)

        // Filter by ANIME
        val animeFilterState = baseState.copy(selectedContentType = "ANIME")
        assertEquals(0, animeFilterState.filteredMovieResults.size)
        assertEquals(1, animeFilterState.filteredSeriesResults.size)
        assertEquals("Anime Show", animeFilterState.filteredSeriesResults[0].title)

        // Filter by Genre Action (28)
        val genreFilterState = baseState.copy(selectedGenreId = 28, selectedGenreName = "Action")
        assertEquals(1, genreFilterState.filteredMovieResults.size)
        assertEquals("Action Movie", genreFilterState.filteredMovieResults[0].title)
        assertEquals(0, genreFilterState.filteredSeriesResults.size)
    }
}
