package com.example.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.domain.repository.MediaRepository
import com.example.utils.NetworkConnectivityObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class MoviesUiState(
    val isLoading: Boolean = true,
    val trendingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreMovies: List<Movie> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class MoviesViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var genreJob: Job? = null

    init {
        loadMovies()
        loadGenres()
        connectivityObserver?.let { observer ->
            viewModelScope.launch {
                observer.observe()
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        if (isConnected) {
                            val state = _uiState.value
                            val hasData = state.movies.isNotEmpty() || state.trendingMovies.isNotEmpty()
                            if (!hasData || state.error != null) {
                                loadMovies(forceRefresh = false)
                                loadGenres()
                            }
                        }
                    }
            }
        }
    }

    fun loadGenres() {
        viewModelScope.launch {
            try {
                val genres = repository.getMovieGenres()
                _uiState.update { it.copy(availableGenres = genres) }
            } catch (_: Exception) { }
        }
    }

    fun selectGenre(genre: Genre?) {
        genreJob?.cancel()
        if (genre == null) {
            _uiState.update {
                it.copy(
                    selectedGenreId = null,
                    selectedGenreName = null,
                    genreMovies = emptyList(),
                    isGenreLoading = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedGenreId = genre.id,
                selectedGenreName = genre.name,
                isGenreLoading = true
            )
        }

        genreJob = viewModelScope.launch {
            repository.getMoviesByGenre(genre.id)
                .catch { _uiState.update { it.copy(isGenreLoading = false) } }
                .collect { movies ->
                    _uiState.update { it.copy(genreMovies = movies, isGenreLoading = false) }
                }
        }
    }

    fun loadMovies(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.movies.isNotEmpty() || _uiState.value.trendingMovies.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Preserves existing cached movies on refresh without destructive clearCache()
            var arabicList: List<Movie> = emptyList()
            var trendingList: List<Movie> = emptyList()
            var newReleasesList: List<Movie> = emptyList()
            var allMoviesList: List<Movie> = emptyList()
            var upcomingList: List<Movie> = emptyList()

            fun updateCombined() {
                val combinedTrending = (trendingList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedNewReleases = (newReleasesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedAllMovies = (allMoviesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val sortedUpcoming = upcomingList.sortedByDescending { it.rating }.distinctBy { it.id }

                val hasData = combinedTrending.isNotEmpty() || combinedAllMovies.isNotEmpty() || combinedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingMovies = if (combinedTrending.isNotEmpty()) combinedTrending else it.trendingMovies,
                        newReleasesMovies = if (combinedNewReleases.isNotEmpty()) combinedNewReleases else it.newReleasesMovies,
                        movies = if (combinedAllMovies.isNotEmpty()) combinedAllMovies else it.movies,
                        upcomingMovies = if (sortedUpcoming.isNotEmpty()) sortedUpcoming else it.upcomingMovies,
                        isLoading = if (hasData) false else it.isLoading,
                        error = if (hasData) null else it.error
                    )
                }
            }

            fun handleError(e: Throwable) {
                val state = _uiState.value
                val hasData = state.movies.isNotEmpty() || state.trendingMovies.isNotEmpty()
                if (!hasData) {
                    _uiState.update { it.copy(error = e.message ?: "Failed to load movies", isLoading = false) }
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getArabicMovies().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                arabicList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getTrendingMovies().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                trendingList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                newReleasesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getMovies().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                allMoviesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                upcomingList = list
                                updateCombined()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handleError(e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
