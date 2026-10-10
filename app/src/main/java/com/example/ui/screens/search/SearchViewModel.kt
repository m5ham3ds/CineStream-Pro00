package com.example.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.util.ContentTypeResolver
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.extension.managed.model.ContentType
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val movieResults: List<Movie> = emptyList(),
    val seriesResults: List<Series> = emptyList(),
    val trendingNow: List<Movie> = emptyList(),
    val selectedContentType: String = "ALL", // "ALL", "MOVIE", "TV", "ANIME"
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val movieGenres: List<Genre> = emptyList(),
    val tvGenres: List<Genre> = emptyList(),
    val animeGenres: List<Genre> = emptyList()
) {
    val filteredMovieResults: List<Movie>
        get() {
            if (selectedContentType == "TV" || selectedContentType == "ANIME") return emptyList()
            if (selectedGenreId == null) return movieResults
            val gid = selectedGenreId
            val gname = selectedGenreName
            return movieResults.filter { m ->
                m.genreIds.contains(gid) || (gname != null && m.genres.any { it.equals(gname, ignoreCase = true) })
            }
        }

    val filteredSeriesResults: List<Series>
        get() {
            if (selectedContentType == "MOVIE") return emptyList()
            var list = seriesResults
            if (selectedContentType == "TV") {
                list = list.filter { ContentTypeResolver.resolveSeries(it) != com.example.data.model.ContentType.ANIME }
            } else if (selectedContentType == "ANIME") {
                list = list.filter { ContentTypeResolver.resolveSeries(it) == com.example.data.model.ContentType.ANIME }
            }
            if (selectedGenreId == null) return list
            val gid = selectedGenreId
            val gname = selectedGenreName
            return list.filter { s ->
                s.genreIds.contains(gid) || (gname != null && s.genres.any { it.equals(gname, ignoreCase = true) })
            }
        }

    val currentAvailableGenres: List<Genre>
        get() {
            return when (selectedContentType) {
                "MOVIE" -> movieGenres
                "TV" -> tvGenres
                "ANIME" -> animeGenres
                else -> movieGenres
            }
        }
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: MediaRepository,
    private val managedOrchestrator: ManagedMediaOrchestrator? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var searchGeneration: Long = 0L

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(500)
                .collect { q ->
                    if (q.isBlank()) {
                        searchJob?.cancel()
                        searchJob = null
                        _uiState.update { it.copy(movieResults = emptyList(), seriesResults = emptyList(), isSearching = false) }
                    } else {
                        performSearch(q)
                    }
                }
        }
        
        loadTrending()
        loadGenres()
    }

    private fun loadGenres() {
        viewModelScope.launch {
            try {
                val mg = repository.getMovieGenres()
                val tg = repository.getTvGenres()
                val ag = repository.getAnimeGenres()
                _uiState.update { it.copy(movieGenres = mg, tvGenres = tg, animeGenres = ag) }
            } catch (_: Exception) {}
        }
    }
    
    private fun loadTrending() {
        viewModelScope.launch {
            repository.getTrendingMovies()
                .catch { }
                .collect { movies ->
                    _uiState.update { it.copy(trendingNow = movies.take(10)) }
                }
        }
    }

    fun setContentTypeFilter(type: String) {
        _uiState.update { current ->
            current.copy(
                selectedContentType = type,
                selectedGenreId = null,
                selectedGenreName = null
            )
        }
    }

    fun setGenreFilter(genreId: Int?, genreName: String?) {
        _uiState.update { current ->
            current.copy(
                selectedGenreId = genreId,
                selectedGenreName = genreName
            )
        }
    }

    fun clearFilters() {
        _uiState.update { current ->
            current.copy(
                selectedContentType = "ALL",
                selectedGenreId = null,
                selectedGenreName = null
            )
        }
    }

    fun refresh() {
        if (queryFlow.value.isNotBlank()) {
            performSearch(queryFlow.value)
        } else {
            searchJob?.cancel()
            searchJob = null
            loadTrending()
        }
    }

    fun resetTransientSearchState() {
        ++searchGeneration
        searchJob?.cancel()
        searchJob = null
        queryFlow.value = ""
        _uiState.update {
            it.copy(
                query = "",
                movieResults = emptyList(),
                seriesResults = emptyList(),
                isSearching = false,
                selectedContentType = "ALL",
                selectedGenreId = null,
                selectedGenreName = null
            )
        }
    }

    fun onQueryChange(query: String) {
        ++searchGeneration
        if (query.isBlank()) {
            searchJob?.cancel()
            searchJob = null
            _uiState.update { it.copy(query = "", movieResults = emptyList(), seriesResults = emptyList(), isSearching = false) }
        } else {
            _uiState.update { it.copy(query = query, isSearching = true) }
        }
        queryFlow.value = query
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        onQueryChange(trimmed)
        if (trimmed.isNotBlank()) {
            performSearch(trimmed)
        }
    }

    private fun performSearch(query: String) {
        val currentGen = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 1. Fetch TMDB results
            val (tmdbMovies, tmdbSeries) = repository.searchMulti(query)
            currentCoroutineContext().ensureActive()
            if (searchGeneration != currentGen || _uiState.value.query != query) return@launch

            // 2. Query active Managed Extensions for additional content
            val orchestrator = managedOrchestrator
            val managedResults = if (orchestrator != null && orchestrator.hasActiveExtensions()) {
                try {
                    orchestrator.searchMedia(query).getOrNull()?.items ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            if (searchGeneration != currentGen || _uiState.value.query != query) return@launch

            val additionalMovies = managedResults
                .filter { it.contentType == ContentType.MOVIE }
                .filterNot { item -> tmdbMovies.any { it.title.equals(item.title, ignoreCase = true) } }
                .map { item ->
                    Movie(
                        id = item.id.ifBlank { "managed_${item.title.hashCode()}" },
                        title = item.title,
                        overview = "",
                        posterUrl = item.posterUrl ?: "",
                        backdropUrl = "",
                        year = item.year?.toIntOrNull() ?: 2024,
                        releaseDate = item.year,
                        rating = 8.0,
                        genres = emptyList(),
                        runtime = 120
                    )
                }

            val additionalSeries = managedResults
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.ANIME }
                .filterNot { item -> tmdbSeries.any { it.title.equals(item.title, ignoreCase = true) } }
                .map { item ->
                    Series(
                        id = item.id.ifBlank { "managed_${item.title.hashCode()}" },
                        title = item.title,
                        originalTitle = item.title,
                        overview = "",
                        posterUrl = item.posterUrl ?: "",
                        backdropUrl = "",
                        year = item.year?.toIntOrNull() ?: 2024,
                        firstAirDate = item.year,
                        rating = 8.0,
                        genres = emptyList(),
                        seasons = emptyList()
                    )
                }

            val finalMovies = tmdbMovies + additionalMovies
            val finalSeries = tmdbSeries + additionalSeries

            currentCoroutineContext().ensureActive()
            if (searchGeneration != currentGen || _uiState.value.query != query) return@launch
            _uiState.update { current ->
                if (current.query == query) {
                    current.copy(movieResults = finalMovies, seriesResults = finalSeries, isSearching = false)
                } else {
                    current
                }
            }
        }
    }
}