package com.example

import com.example.BuildConfig
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.models.TmdbException
import com.example.domain.repository.MediaRepository
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.movies.MoviesViewModel
import com.example.ui.screens.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Phase07Wave9RemediationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // TRACK A: TMDB CONFIGURATION & ERROR DIFFERENTIATION
    // =========================================================================

    @Test
    fun testTrackA_01_tmdbApiKeyRestoredAndNotPlaceholder() {
        val apiKey = BuildConfig.TMDB_API_KEY
        assertNotNull("TMDB_API_KEY must not be null", apiKey)
        assertTrue("TMDB_API_KEY must not be blank", apiKey.isNotBlank())
        assertNotEquals("TMDB_API_KEY must not be placeholder token", "YOUR_TMDB_API_KEY", apiKey)
        assertEquals("TMDB_API_KEY should match restored active key", "7fe9c75d9f8106f12b42bc50fa7f6671", apiKey)
    }

    @Test
    fun testTrackA_02_errorDifferentiationTypesHierarchy() {
        val authError = TmdbException.AuthException("HTTP 401: Unauthorized")
        val networkError = TmdbException.NetworkException("Connection reset")
        val serverError = TmdbException.ServerException(500, "Internal Server Error")
        val unknownError = TmdbException.UnknownException("Unexpected error")

        assertTrue(authError is TmdbException)
        assertTrue(networkError is TmdbException)
        assertTrue(serverError is TmdbException)
        assertTrue(unknownError is TmdbException)

        assertEquals(500, serverError.statusCode)
        assertTrue(authError.message?.contains("401") == true)
        assertTrue(networkError.message?.contains("Connection reset") == true)
    }

    // =========================================================================
    // TRACK B: SKELETON LIFECYCLE & NON-DESTRUCTIVE REFRESH
    // =========================================================================

    @Test
    fun testTrackB_01_homeViewModelPreservesDataOnRefreshFailure() = testScope.runTest {
        var callCount = 0
        val sampleMovie = Movie(id = "1", title = "Spider-Man", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.5, genres = listOf("Action"), runtime = 120)

        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flow {
                if (callCount == 0) {
                    emit(listOf(sampleMovie))
                } else {
                    throw TmdbException.NetworkException("No network available")
                }
            }
            override fun getTrendingMovies(): Flow<List<Movie>> = flow {
                if (callCount == 0) {
                    emit(listOf(sampleMovie))
                } else {
                    throw TmdbException.NetworkException("No network available")
                }
            }
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = Pair(emptyList(), emptyList())
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = HomeViewModel(fakeRepo)
        advanceUntilIdle()

        // Verify initial state has data
        assertEquals(1, viewModel.uiState.value.trendingMovies.size)
        assertEquals("Spider-Man", viewModel.uiState.value.trendingMovies[0].title)

        // Force refresh during network failure
        callCount = 1
        viewModel.loadData(forceRefresh = true)
        advanceUntilIdle()

        // Non-destructive: previous data must STILL remain visible!
        assertEquals("Existing data must not be wiped on failed refresh", 1, viewModel.uiState.value.trendingMovies.size)
        assertFalse("isLoading should be false after failure", viewModel.uiState.value.isLoading)
    }

    @Test
    fun testTrackB_02_moviesViewModelPreservesDataOnRefresh() = testScope.runTest {
        var callCount = 0
        val sampleMovie = Movie(id = "101", title = "Inception", overview = "", posterUrl = "", backdropUrl = "", year = 2010, rating = 9.0, genres = listOf("Sci-Fi"), runtime = 148)

        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flow {
                if (callCount == 0) emit(listOf(sampleMovie))
                else throw TmdbException.NetworkException("Offline")
            }
            override fun getTrendingMovies(): Flow<List<Movie>> = flow {
                if (callCount == 0) emit(listOf(sampleMovie))
                else throw TmdbException.NetworkException("Offline")
            }
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = Pair(emptyList(), emptyList())
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = MoviesViewModel(fakeRepo)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.movies.isNotEmpty())

        callCount = 1
        viewModel.loadMovies(forceRefresh = true)
        advanceUntilIdle()

        assertTrue("Movies data must be preserved on failed refresh", viewModel.uiState.value.movies.isNotEmpty())
    }

    // =========================================================================
    // TRACK C: SEARCH STATE PRESERVATION & SUBMISSION
    // =========================================================================

    @Test
    fun testTrackC_01_searchViewModelSubmitSearchUpdatesQueryAndPerformsSearch() = testScope.runTest {
        val sampleMovie = Movie(id = "202", title = "Interstellar", overview = "", posterUrl = "", backdropUrl = "", year = 2014, rating = 8.7, genres = emptyList(), runtime = 169)

        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingMovies(): Flow<List<Movie>> = flowOf(listOf(sampleMovie))
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> {
                return if (query == "Interstellar") {
                    Pair(listOf(sampleMovie), emptyList())
                } else {
                    Pair(emptyList(), emptyList())
                }
            }
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        // Trigger immediate submitSearch
        viewModel.submitSearch("Interstellar")
        advanceUntilIdle()

        assertEquals("Interstellar", viewModel.uiState.value.query)
        assertEquals(1, viewModel.uiState.value.movieResults.size)
        assertEquals("Interstellar", viewModel.uiState.value.movieResults[0].title)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun testTrackC_02_searchViewModelBlankQueryClearsResults() = testScope.runTest {
        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = Pair(emptyList(), emptyList())
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = SearchViewModel(fakeRepo)
        viewModel.onQueryChange("test")
        advanceUntilIdle()
        assertEquals("test", viewModel.uiState.value.query)

        viewModel.onQueryChange("")
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.movieResults.isEmpty())
        assertTrue(viewModel.uiState.value.seriesResults.isEmpty())
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun testTrackA_03_envExampleExactRestorationMatch() {
        val rootEnvFile = java.io.File(".env.example")
        val altEnvFile = java.io.File("../.env.example")
        val file = if (rootEnvFile.exists()) rootEnvFile else altEnvFile
        assertTrue(".env.example must exist on disk", file.exists())
        val content = file.readText()

        assertTrue("Must contain TMDB_API_KEY", content.contains("TMDB_API_KEY=7fe9c75d9f8106f12b42bc50fa7f6671"))
        assertTrue("Must contain WEB_CLIENT_ID", content.contains("WEB_CLIENT_ID=979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com"))
        assertTrue("Must contain CLOUDINARY_UPLOAD_PRESET=ml_default", content.contains("CLOUDINARY_UPLOAD_PRESET=ml_default"))
        assertTrue("Must contain CLOUDINARY_CLOUD_NAME", content.contains("CLOUDINARY_CLOUD_NAME=979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com"))
        assertFalse("Must NOT contain placeholder YOUR_TMDB_API_KEY", content.contains("YOUR_TMDB_API_KEY"))
    }

    @Test
    fun testTrackC_03_searchViewModelCancellationLatestQueryWins() = testScope.runTest {
        val movieA = Movie(id = "1", title = "Avatar", overview = "", posterUrl = "", backdropUrl = "", year = 2009, rating = 7.9, genres = emptyList(), runtime = 162)
        val movieB = Movie(id = "2", title = "Batman", overview = "", posterUrl = "", backdropUrl = "", year = 2022, rating = 7.8, genres = emptyList(), runtime = 176)

        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> {
                return if (query == "Batman") {
                    Pair(listOf(movieB), emptyList())
                } else if (query == "Avatar") {
                    Pair(listOf(movieA), emptyList())
                } else {
                    Pair(emptyList(), emptyList())
                }
            }
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = SearchViewModel(fakeRepo)
        // Submit Avatar, then immediately submit Batman before idle
        viewModel.submitSearch("Avatar")
        viewModel.submitSearch("Batman")
        advanceUntilIdle()

        assertEquals("Batman", viewModel.uiState.value.query)
        assertEquals(1, viewModel.uiState.value.movieResults.size)
        assertEquals("Batman", viewModel.uiState.value.movieResults[0].title)
    }

    @Test
    fun testTrackC_04_whitespaceOnlyQueryDoesNotSearch() = testScope.runTest {
        val fakeRepo = object : MediaRepository {
            override fun getMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getTrendingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getTrendingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getArabicMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getArabicSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getUpcomingSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getUpcomingAnime(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getAnimeMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesMovies(): Flow<List<Movie>> = flowOf(emptyList())
            override fun getNewReleasesSeries(): Flow<List<Series>> = flowOf(emptyList())
            override fun getNewReleasesAnime(): Flow<List<Series>> = flowOf(emptyList())
            override suspend fun getMovieById(id: String): Movie? = null
            override suspend fun getSeriesById(id: String): Series? = null
            override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = Pair(emptyList(), emptyList())
            override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode> = emptyList()
            override suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails? = null
        }

        val viewModel = SearchViewModel(fakeRepo)
        viewModel.submitSearch("    ")
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.movieResults.isEmpty())
        assertFalse(viewModel.uiState.value.isSearching)
    }
}
