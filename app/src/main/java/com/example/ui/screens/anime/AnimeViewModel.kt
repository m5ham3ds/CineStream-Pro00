package com.example.ui.screens.anime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Series
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

data class AnimeUiState(
    val isLoading: Boolean = true,
    val trendingAnime: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreAnime: List<Series> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class AnimeViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeUiState())
    val uiState: StateFlow<AnimeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var genreJob: Job? = null

    init {
        loadData()
        loadGenres()
        connectivityObserver?.let { observer ->
            viewModelScope.launch {
                observer.observe()
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        if (isConnected) {
                            val state = _uiState.value
                            val hasData = state.series.isNotEmpty() || state.trendingAnime.isNotEmpty()
                            if (!hasData || state.error != null) {
                                loadData(forceRefresh = false)
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
                val genres = repository.getAnimeGenres()
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
                    genreAnime = emptyList(),
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
            repository.getAnimeByGenre(genre.id)
                .catch { _uiState.update { it.copy(isGenreLoading = false) } }
                .collect { list ->
                    _uiState.update { it.copy(genreAnime = list, isGenreLoading = false) }
                }
        }
    }

    private fun handleLoadError(e: Throwable) {
        val state = _uiState.value
        val hasData = state.series.isNotEmpty() || state.trendingAnime.isNotEmpty()
        if (!hasData) {
            _uiState.update { it.copy(error = e.message ?: "Failed to load anime", isLoading = false) }
        }
    }

    fun loadData(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingAnime.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Preserves existing cached anime on refresh without destructive clearCache()
            try {
                supervisorScope {
                    launch {
                        repository.getTrendingAnime().catch { handleLoadError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(trendingAnime = list, isLoading = false, error = null) }
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { handleLoadError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(newEpisodes = list, isLoading = false, error = null) }
                            }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { handleLoadError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(series = list, isLoading = false, error = null) }
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { handleLoadError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(upcomingAnime = list, isLoading = false, error = null) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handleLoadError(e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
