package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Episode
import com.example.domain.models.Season
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.utils.MediaDetailsCacheManager
import com.example.utils.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeriesDetailsUiState(
    val isLoading: Boolean = false,
    val series: Series? = null,
    val error: String? = null,
    val selectedSeason: Season? = null,
    val episodes: List<Episode> = emptyList(),
    val isEpisodesLoading: Boolean = false,
    val visibleEpisodesCount: Int = 10,
    val isLoadingMore: Boolean = false
) {
    val isSeasonSynced: Boolean
        get() {
            val s = selectedSeason ?: return true
            if (episodes.isEmpty()) return true
            return episodes.all { it.seasonNumber == s.seasonNumber }
        }
}

class SeriesDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    companion object {
        private val cache = mutableMapOf<String, SeriesDetailsUiState>()
        private val seasonEpisodesCache = mutableMapOf<String, List<Episode>>()

        fun getCachedState(seriesId: String): SeriesDetailsUiState? = cache[seriesId]

        fun saveState(seriesId: String, state: SeriesDetailsUiState) {
            // Guarantee cache safety (Part 7): never save mismatched season/episodes state
            if (state.selectedSeason != null && state.episodes.isNotEmpty()) {
                val expectedSeason = state.selectedSeason.seasonNumber
                val hasMismatch = state.episodes.any { it.seasonNumber != expectedSeason }
                if (hasMismatch) {
                    val syncedEpisodes = state.episodes.filter { it.seasonNumber == expectedSeason }
                    cache[seriesId] = state.copy(episodes = syncedEpisodes)
                    return
                }
            }
            cache[seriesId] = state
        }

        fun updateAndCache(seriesId: String, transform: (SeriesDetailsUiState) -> SeriesDetailsUiState) {
            val current = cache[seriesId] ?: SeriesDetailsUiState()
            saveState(seriesId, transform(current))
        }

        fun getCachedEpisodes(seriesId: String, seasonNumber: Int): List<Episode>? =
            seasonEpisodesCache["${seriesId}_$seasonNumber"]

        fun saveCachedEpisodes(seriesId: String, seasonNumber: Int, episodes: List<Episode>) {
            if (episodes.isNotEmpty()) {
                val validEpisodes = episodes.filter { it.seasonNumber == seasonNumber }
                if (validEpisodes.isNotEmpty()) {
                    seasonEpisodesCache["${seriesId}_$seasonNumber"] = validEpisodes
                }
            }
        }

        fun clearCache(seriesId: String? = null) {
            if (seriesId != null) {
                cache.remove(seriesId)
                val prefix = "${seriesId}_"
                seasonEpisodesCache.keys.removeAll { it.startsWith(prefix) }
            } else {
                cache.clear()
                seasonEpisodesCache.clear()
            }
        }
    }

    private val _uiState = MutableStateFlow(SeriesDetailsUiState())
    val uiState: StateFlow<SeriesDetailsUiState> = _uiState.asStateFlow()
    private var networkObserverJob: Job? = null
    private var episodeLoadingJob: Job? = null

    fun loadSeries(seriesId: String, forceRefresh: Boolean = false) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        val app = try { AppContainer.application } catch (_: Exception) { null }

        // 1. In-memory Companion cache restoration with strict season/episode synchronization
        val cached = getCachedState(targetId)
        if (cached != null && !forceRefresh) {
            val preservedSeason = cached.selectedSeason
                ?: cached.series?.seasons?.firstOrNull { it.seasonNumber > 0 }
                ?: cached.series?.seasons?.firstOrNull()
            val initialEpisodes = if (preservedSeason != null) {
                (getCachedEpisodes(targetId, preservedSeason.seasonNumber)
                    ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, preservedSeason.seasonNumber) else null)
                    ?: emptyList()).filter { it.seasonNumber == preservedSeason.seasonNumber }
            } else emptyList()

            val state = cached.copy(
                selectedSeason = preservedSeason,
                episodes = initialEpisodes,
                isEpisodesLoading = preservedSeason != null && initialEpisodes.isEmpty()
            )
            _uiState.value = state
            if (preservedSeason != null && initialEpisodes.isEmpty()) {
                loadEpisodes(targetId, preservedSeason.seasonNumber)
            }
        }

        // 2. Persistent disk cache fallback
        val persistentCachedSeries = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
        if (persistentCachedSeries != null && _uiState.value.series == null) {
            val initialSeason = persistentCachedSeries.seasons.firstOrNull { it.seasonNumber > 0 } ?: persistentCachedSeries.seasons.firstOrNull()
            val initialEpisodes = if (app != null && initialSeason != null) {
                (getCachedEpisodes(targetId, initialSeason.seasonNumber)
                    ?: MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, initialSeason.seasonNumber))
                    .filter { it.seasonNumber == initialSeason.seasonNumber }
            } else emptyList()

            val state = SeriesDetailsUiState(
                series = persistentCachedSeries,
                selectedSeason = initialSeason,
                episodes = initialEpisodes,
                isEpisodesLoading = initialSeason != null && initialEpisodes.isEmpty(),
                isLoading = false
            )
            _uiState.value = state
            saveState(targetId, state)
            if (initialSeason != null && initialEpisodes.isEmpty()) {
                loadEpisodes(targetId, initialSeason.seasonNumber)
            }
        }

        // 3. Network fetch with atomic state synchronization
        viewModelScope.launch {
            if (_uiState.value.series == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val series = repository.getSeriesById(targetId)
                if (series != null) {
                    val initialSeason = series.seasons.firstOrNull { it.seasonNumber > 0 } ?: series.seasons.firstOrNull()
                    // If user selected a season while network was in flight, preserve their selection
                    val currentSelected = _uiState.value.selectedSeason
                    val targetSeason = if (currentSelected != null && series.seasons.any { it.seasonNumber == currentSelected.seasonNumber }) {
                        series.seasons.first { it.seasonNumber == currentSelected.seasonNumber }
                    } else {
                        initialSeason
                    }
                    val targetSeasonNumber = targetSeason?.seasonNumber ?: 1

                    val episodesForSeason = if (targetSeason != null) {
                        (getCachedEpisodes(targetId, targetSeasonNumber)
                            ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, targetSeasonNumber) else null)
                            ?: emptyList()).filter { it.seasonNumber == targetSeasonNumber }
                    } else emptyList()

                    val isEpisodesLoading = targetSeason != null && episodesForSeason.isEmpty()
                    val newState = _uiState.value.copy(
                        series = series,
                        isLoading = false,
                        selectedSeason = targetSeason,
                        episodes = episodesForSeason,
                        isEpisodesLoading = isEpisodesLoading,
                        error = null
                    )
                    _uiState.value = newState
                    saveState(targetId, newState)
                    networkObserverJob?.cancel()

                    if (app != null) {
                        if (targetSeason != null && episodesForSeason.isNotEmpty()) {
                            MediaDetailsCacheManager.saveSeriesDetails(app, series, episodesForSeason, targetSeasonNumber)
                        } else {
                            MediaDetailsCacheManager.saveSeriesDetails(app, series)
                        }
                    }

                    if (targetSeason != null && episodesForSeason.isEmpty()) {
                        loadEpisodes(targetId, targetSeasonNumber)
                    }
                } else if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val targetSeasonNumber = initialSeason?.seasonNumber ?: 1
                        val episodesForSeason = if (initialSeason != null) {
                            (getCachedEpisodes(targetId, targetSeasonNumber)
                                ?: MediaDetailsCacheManager.getSeriesEpisodes(app!!, targetId, targetSeasonNumber))
                                .filter { it.seasonNumber == targetSeasonNumber }
                        } else emptyList()

                        val newState = _uiState.value.copy(
                            series = fallback,
                            isLoading = false,
                            selectedSeason = initialSeason,
                            episodes = episodesForSeason,
                            isEpisodesLoading = initialSeason != null && episodesForSeason.isEmpty(),
                            error = null
                        )
                        _uiState.value = newState
                        saveState(targetId, newState)
                        networkObserverJob?.cancel()

                        if (initialSeason != null && episodesForSeason.isEmpty()) {
                            loadEpisodes(targetId, targetSeasonNumber)
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val targetSeasonNumber = initialSeason?.seasonNumber ?: 1
                        val episodesForSeason = if (initialSeason != null) {
                            (getCachedEpisodes(targetId, targetSeasonNumber)
                                ?: MediaDetailsCacheManager.getSeriesEpisodes(app!!, targetId, targetSeasonNumber))
                                .filter { it.seasonNumber == targetSeasonNumber }
                        } else emptyList()

                        val newState = _uiState.value.copy(
                            series = fallback,
                            isLoading = false,
                            selectedSeason = initialSeason,
                            episodes = episodesForSeason,
                            isEpisodesLoading = initialSeason != null && episodesForSeason.isEmpty(),
                            error = null
                        )
                        _uiState.value = newState
                        saveState(targetId, newState)
                        networkObserverJob?.cancel()

                        if (initialSeason != null && episodesForSeason.isEmpty()) {
                            loadEpisodes(targetId, targetSeasonNumber)
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun startNetworkAutoReload(seriesId: String) {
        if (networkObserverJob?.isActive == true) return
        val appContext = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
        networkObserverJob = viewModelScope.launch {
            val observer = com.example.utils.NetworkConnectivityObserver(appContext)
            observer.observe().collect { isConnected ->
                if (isConnected && _uiState.value.series == null) {
                    delay(500)
                    loadSeries(seriesId, forceRefresh = true)
                }
            }
        }
    }

    fun triggerInitialEpisodesLoad() {
        val currentSeries = _uiState.value.series ?: return
        val currentSeason = _uiState.value.selectedSeason ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        val episodesMatchCurrentSeason = _uiState.value.episodes.isNotEmpty() && _uiState.value.episodes.all { it.seasonNumber == currentSeason.seasonNumber }
        if (!episodesMatchCurrentSeason && !_uiState.value.isEpisodesLoading) {
            loadEpisodes(targetId, currentSeason.seasonNumber)
        }
    }

    private fun updateAndCache(seriesId: String, transform: (SeriesDetailsUiState) -> SeriesDetailsUiState) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        _uiState.update { transform(it) }
        saveState(targetId, _uiState.value)
    }

    fun selectSeason(season: Season) {
        val currentSeries = _uiState.value.series ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        val app = try { AppContainer.application } catch (_: Exception) { null }

        // Cancel previous episode loading job to prevent race conditions (Part 10)
        episodeLoadingJob?.cancel()

        // Check if episodes for this season are already cached and validate season identity (Part 8, Part 9)
        val cachedForSeason = (getCachedEpisodes(targetId, season.seasonNumber)
            ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, season.seasonNumber) else null)
            ?: emptyList()).filter { it.seasonNumber == season.seasonNumber }

        val isCached = cachedForSeason.isNotEmpty()

        // Deterministic state transition (Part 5):
        // 1. selectedSeason = season
        // 2. episodes = cachedForSeason (if available) OR emptyList() (immediately invalidating old season episodes)
        // 3. isEpisodesLoading = !isCached
        updateAndCache(targetId) {
            it.copy(
                selectedSeason = season,
                episodes = cachedForSeason,
                isEpisodesLoading = !isCached,
                visibleEpisodesCount = 10,
                isLoadingMore = false
            )
        }

        // Fetch fresh episodes if not cached
        if (!isCached) {
            loadEpisodes(targetId, season.seasonNumber)
        }
    }

    fun loadMoreEpisodes(context: android.content.Context) {
        val currentSeries = _uiState.value.series ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        if (_uiState.value.isLoadingMore) return
        
        viewModelScope.launch {
            updateAndCache(targetId) { it.copy(isLoadingMore = true) }
            
            if (!NetworkUtils.isInternetAvailable(context)) {
                delay(300)
                updateAndCache(targetId) { it.copy(isLoadingMore = false, error = context.getString(com.example.R.string.no_internet_load_episodes)) }
                return@launch
            }
            
            delay(350)
            updateAndCache(targetId) { it.copy(visibleEpisodesCount = it.visibleEpisodesCount + 10, isLoadingMore = false) }
        }
    }

    fun loadEpisodesForSeason(seriesId: String, seasonNumber: Int) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        loadEpisodes(targetId, seasonNumber)
    }

    private fun loadEpisodes(seriesId: String, seasonNumber: Int) {
        val app = try { AppContainer.application } catch (_: Exception) { null }
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId

        episodeLoadingJob?.cancel()
        episodeLoadingJob = viewModelScope.launch {
            // Invalidate episodes if active state has mismatching season episodes (Part 5 & Part 11)
            val currentSelectedSeason = _uiState.value.selectedSeason
            if (currentSelectedSeason?.seasonNumber == seasonNumber) {
                val hasMismatch = _uiState.value.episodes.any { it.seasonNumber != seasonNumber }
                if (hasMismatch || _uiState.value.episodes.isEmpty()) {
                    updateAndCache(targetId) {
                        it.copy(
                            episodes = if (hasMismatch) emptyList() else it.episodes,
                            isEpisodesLoading = true,
                            visibleEpisodesCount = 10
                        )
                    }
                }
            }
            try {
                val rawEpisodes = repository.getSeasonEpisodes(targetId, seasonNumber)
                // Validate episode season identity (Part 9)
                val episodes = rawEpisodes.map {
                    if (it.seasonNumber == seasonNumber) it else it.copy(seasonNumber = seasonNumber)
                }

                if (episodes.isNotEmpty()) {
                    saveCachedEpisodes(targetId, seasonNumber, episodes)
                    if (app != null) {
                        MediaDetailsCacheManager.saveSeriesEpisodes(app, targetId, seasonNumber, episodes)
                        val curr = _uiState.value.series
                        if (curr != null) {
                            MediaDetailsCacheManager.saveSeriesDetails(app, curr, episodes, seasonNumber)
                        }
                    }
                    // Validate: Only update active UI state if selected season is STILL this seasonNumber! (Part 10)
                    if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                        updateAndCache(targetId) {
                            it.copy(
                                episodes = episodes,
                                isEpisodesLoading = false
                            )
                        }
                    }
                } else {
                    val fallback = (getCachedEpisodes(targetId, seasonNumber)
                        ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, seasonNumber) else null)
                        ?: emptyList()).filter { it.seasonNumber == seasonNumber }
                    if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                        updateAndCache(targetId) {
                            it.copy(
                                episodes = fallback,
                                isEpisodesLoading = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val fallback = (getCachedEpisodes(targetId, seasonNumber)
                    ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, seasonNumber) else null)
                    ?: emptyList()).filter { it.seasonNumber == seasonNumber }
                if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                    updateAndCache(targetId) {
                        it.copy(
                            episodes = fallback,
                            isEpisodesLoading = false
                        )
                    }
                }
            }
        }
    }
}
