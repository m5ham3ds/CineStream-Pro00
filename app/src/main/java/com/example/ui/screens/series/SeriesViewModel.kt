package com.example.ui.screens.series

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

data class SeriesUiState(
    val isLoading: Boolean = true,
    val trendingSeries: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreSeries: List<Series> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class SeriesViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var genreJob: Job? = null

    init {
        loadSeries()
        loadGenres()
        connectivityObserver?.let { observer ->
            viewModelScope.launch {
                observer.observe()
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        if (isConnected) {
                            val state = _uiState.value
                            val hasData = state.series.isNotEmpty() || state.trendingSeries.isNotEmpty()
                            if (!hasData || state.error != null) {
                                loadSeries(forceRefresh = false)
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
                val genres = repository.getTvGenres()
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
                    genreSeries = emptyList(),
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
            repository.getSeriesByGenre(genre.id)
                .catch { _uiState.update { it.copy(isGenreLoading = false) } }
                .collect { list ->
                    _uiState.update { it.copy(genreSeries = list, isGenreLoading = false) }
                }
        }
    }

    fun loadSeries(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingSeries.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Preserves existing cached series on refresh without destructive clearCache()
            var arabicList: List<Series> = emptyList()
            var trendingList: List<Series> = emptyList()
            var newReleasesList: List<Series> = emptyList()
            var allSeriesList: List<Series> = emptyList()
            var upcomingList: List<Series> = emptyList()

            fun updateCombined() {
                val combinedTrending = (trendingList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedNewReleases = (newReleasesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedAllSeries = (allSeriesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val sortedUpcoming = upcomingList.sortedByDescending { it.rating }.distinctBy { it.id }

                val hasData = combinedTrending.isNotEmpty() || combinedAllSeries.isNotEmpty() || combinedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingSeries = if (combinedTrending.isNotEmpty()) combinedTrending else it.trendingSeries,
                        newEpisodes = if (combinedNewReleases.isNotEmpty()) combinedNewReleases else it.newEpisodes,
                        series = if (combinedAllSeries.isNotEmpty()) combinedAllSeries else it.series,
                        upcomingSeries = if (sortedUpcoming.isNotEmpty()) sortedUpcoming else it.upcomingSeries,
                        isLoading = if (hasData) false else it.isLoading,
                        error = if (hasData) null else it.error
                    )
                }
            }

            fun handleError(e: Throwable) {
                val state = _uiState.value
                val hasData = state.series.isNotEmpty() || state.trendingSeries.isNotEmpty()
                if (!hasData) {
                    _uiState.update { it.copy(error = e.message ?: "Failed to load series", isLoading = false) }
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getArabicSeries().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                arabicList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getTrendingSeries().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                trendingList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                newReleasesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getSeries().catch { handleError(it) }.collect { list ->
                            if (list.isNotEmpty()) {
                                allSeriesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { handleError(it) }.collect { list ->
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
