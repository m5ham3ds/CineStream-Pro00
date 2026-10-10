package com.example.ui.screens.home
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.draw.*
import androidx.compose.foundation.shape.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.res.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.*
import com.example.ui.components.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.*
import com.example.data.util.ContentTypeResolver
import com.example.data.repository.*
import com.example.domain.models.*
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.lazy.*
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.material3.pulltorefresh.*
import com.example.ui.screens.home.HomeViewModel







@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    onNavigateToAnime: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    LaunchedEffect(historyItems) {
        if (historyItems.isNotEmpty()) {
            com.example.data.repository.ContinueWatchingMetadataManager.preload(historyItems, this)
        }
    }
    val libraryRepository = remember { LibraryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()

    val hasRemoteContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.allMovies.isNotEmpty() ||
            uiState.popularMovies.isNotEmpty() ||
            uiState.trendingSeries.isNotEmpty() ||
            uiState.popularSeries.isNotEmpty() ||
            uiState.allSeries.isNotEmpty() ||
            uiState.actionMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.trendingAnime.isNotEmpty() ||
            uiState.animeSeries.isNotEmpty()

    LaunchedEffect(Unit) {
        if (!hasRemoteContent) {
            viewModel.loadData()
        }
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    var bottomSheetIsMovie by remember { mutableStateOf(true) }
    var bottomSheetContentType by remember { mutableStateOf(ContentType.MOVIE) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    val onMediaLongClick: (id: String, title: String, posterUrl: String, isMovie: Boolean, contentType: String) -> Unit =
        remember {
            { id, title, posterUrl, isMovie, contentType ->
                bottomSheetIsMovie = isMovie
                bottomSheetContentType = contentType
                selectedMediaId = id
                selectedMediaTitle = title
                selectedMediaPoster = posterUrl
                showBottomSheet = true
            }
        }

    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf<Genre?>(null) }

    val homeGenres = remember {
        val movieG = com.example.data.util.TmdbGenreHelper.getMovieGenres()
        val tvG = com.example.data.util.TmdbGenreHelper.getTvGenres()
        (movieG + tvG).distinctBy { it.name }
    }
    val allGenreStr = stringResource(R.string.genre_all)
    val homeGenreOptions = remember(homeGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + homeGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }

    val ptrState = rememberPullToRefreshState()
    
    val trendingMix = remember(uiState.trendingMovies, uiState.trendingSeries, selectedGenre) {
        val filteredMovies = if (selectedGenre == null) uiState.trendingMovies else uiState.trendingMovies.filter { m ->
            m.genreIds.contains(selectedGenre!!.id) || m.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
        val filteredSeries = if (selectedGenre == null) uiState.trendingSeries else uiState.trendingSeries.filter { s ->
            s.genreIds.contains(selectedGenre!!.id) || s.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
        (filteredMovies.take(5) + filteredSeries.map { 
            Movie(id = it.id, title = it.title, overview = it.overview, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl, year = it.year, rating = it.rating, genres = it.genres, runtime = 0, genreIds = it.genreIds)
        }.take(5))
    }
    val newReleasesMix = remember(uiState.newReleasesMovies, uiState.newReleasesSeries, selectedGenre) {
        val filteredMovies = if (selectedGenre == null) uiState.newReleasesMovies else uiState.newReleasesMovies.filter { m ->
            m.genreIds.contains(selectedGenre!!.id) || m.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
        val filteredSeries = if (selectedGenre == null) uiState.newReleasesSeries else uiState.newReleasesSeries.filter { s ->
            s.genreIds.contains(selectedGenre!!.id) || s.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
        (filteredMovies.take(10) + filteredSeries.map { 
            Movie(id = it.id, title = it.title, overview = it.overview, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl, year = it.year, rating = it.rating, genres = it.genres, runtime = 0, genreIds = it.genreIds)
        }.take(10))
    }
    val upcomingMix = remember(uiState.upcomingMovies, selectedGenre) {
        val filtered = if (selectedGenre == null) uiState.upcomingMovies else uiState.upcomingMovies.filter { m ->
            m.genreIds.contains(selectedGenre!!.id) || m.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
        filtered.take(10)
    }
    val filteredTrendingMovies = remember(uiState.trendingMovies, selectedGenre) {
        if (selectedGenre == null) uiState.trendingMovies else uiState.trendingMovies.filter { m ->
            m.genreIds.contains(selectedGenre!!.id) || m.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
    }
    val filteredTrendingSeries = remember(uiState.trendingSeries, selectedGenre) {
        if (selectedGenre == null) uiState.trendingSeries else uiState.trendingSeries.filter { s ->
            s.genreIds.contains(selectedGenre!!.id) || s.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
    }
    val filteredAnimeSeries = remember(uiState.animeSeries, selectedGenre) {
        if (selectedGenre == null) uiState.animeSeries else uiState.animeSeries.filter { s ->
            s.genreIds.contains(selectedGenre!!.id) || s.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadData(forceRefresh = true) },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasRemoteContent) {
            val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(context)
            if (uiState.isLoading || isOffline) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MediaScreenSkeleton()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        val isAuthError = uiState.error?.contains("401") == true ||
                                uiState.error?.contains("Auth") == true ||
                                uiState.error?.contains("API key") == true
                        val errorText = when {
                            isAuthError -> "TMDB API key configuration required in Secrets panel."
                            !uiState.error.isNullOrBlank() -> uiState.error!!
                            else -> stringResource(R.string.check_net_retry)
                        }
                        Text(
                            text = errorText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.loadData(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // Hero Section
                item {
                    val heroMovies = remember(uiState.trendingMovies, selectedGenre) {
                        if (selectedGenre == null) uiState.trendingMovies
                        else {
                            val filtered = uiState.trendingMovies.filter { m ->
                                m.genreIds.contains(selectedGenre!!.id) || m.genres.any { it.contains(selectedGenre!!.name, ignoreCase = true) }
                            }
                            if (filtered.isNotEmpty()) filtered else uiState.trendingMovies
                        }
                    }
                    if (heroMovies.isNotEmpty()) {
                        HeroCarousel(items = heroMovies.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, true, contentType = ContentType.MOVIE) }, onClick = onMovieClick)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Premium Category Dropdown Selector
                item(key = "home_genre_dropdown_selector") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        CineStreamDropdownSelector(
                            label = stringResource(R.string.filter_category),
                            selectedOption = DropdownOption(
                                value = selectedGenre,
                                title = selectedGenre?.name ?: stringResource(R.string.genre_all),
                                icon = Icons.Default.FilterAlt
                            ),
                            options = homeGenreOptions,
                            expanded = isCategoryDropdownExpanded,
                            onExpandedChange = { isCategoryDropdownExpanded = it },
                            onOptionSelected = { option ->
                                selectedGenre = option.value
                            },
                            leadingIcon = Icons.Default.FilterAlt,
                            testTag = "home_genre_dropdown"
                        )

                        AnimatedVisibility(
                            visible = selectedGenre != null,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                ActiveFilterChip(
                                    filterName = selectedGenre?.name ?: "",
                                    onClear = { selectedGenre = null }
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            // 1. Continue Watching
            if (historyItems.isNotEmpty()) {
                item(key = "section_continue_watching") {
                    SectionTitle(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(historyItems, key = { it.id }) { item ->
                            ContinueWatchingCardShared(
                                item = item,
                                onClick = {
                                    if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id)
                                },
                                onPlayClick = {
                                    if (item.isMovie) {
                                        if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                    } else {
                                        if (onPlaySeries != null) onPlaySeries(item.id) else onSeriesClick(item.id)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 2. Trending Now
            if (uiState.trendingMovies.isNotEmpty() || uiState.trendingSeries.isNotEmpty()) {
                item(key = "section_trending_now") {
                    SectionTitle(stringResource(R.string.trending_now), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(trendingMix, key = { index, item -> "trending_${if (index < 5) "m" else "s"}_${item.id}" }) { index, item ->
                            val isMovie = index < 5
                            val resolvedType = if (isMovie) ContentType.MOVIE else ContentTypeResolver.resolveSeries(uiState.trendingSeries[index - 5])
                            val onClick = remember(item.id, isMovie) {
                                { if (isMovie) onMovieClick(item.id) else onSeriesClick(item.id) }
                            }
                            val onLongClick = remember(item.id, isMovie, resolvedType, item.title, item.posterUrl) {
                                { onMediaLongClick(item.id, item.title, item.posterUrl, isMovie, resolvedType) }
                            }
                            MediaCard(
                                title = item.title,
                                posterUrl = item.posterUrl,
                                rank = index + 1,
                                rating = item.rating,
                                year = item.year.toString(),
                                isMovie = isMovie,
                                contentType = resolvedType,
                                mediaId = item.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 3. New Releases
            if (uiState.newReleasesMovies.isNotEmpty() || uiState.newReleasesSeries.isNotEmpty()) {
                item(key = "section_new_releases") {
                    SectionTitle(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(newReleasesMix, key = { index, item -> "new_releases_${if (index < 10) "m" else "s"}_${item.id}" }) { index, item ->
                            val isMovie = index < 10
                            val resolvedType = if (isMovie) ContentType.MOVIE else ContentTypeResolver.resolveSeries(uiState.newReleasesSeries[index - 10])
                            val onClick = remember(item.id, isMovie) {
                                { if (isMovie) onMovieClick(item.id) else onSeriesClick(item.id) }
                            }
                            val onLongClick = remember(item.id, isMovie, resolvedType, item.title, item.posterUrl) {
                                { onMediaLongClick(item.id, item.title, item.posterUrl, isMovie, resolvedType) }
                            }
                            MediaCard(
                                title = item.title,
                                posterUrl = item.posterUrl,
                                rank = index + 1,
                                rating = item.rating,
                                year = item.year.toString(),
                                isMovie = isMovie,
                                contentType = resolvedType,
                                mediaId = item.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 4. Trending Movies
            if (filteredTrendingMovies.isNotEmpty()) {
                item(key = "section_trending_movies") {
                    SectionTitle(stringResource(R.string.trending_movies), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(filteredTrendingMovies, key = { _, movie -> movie.id }) { index, movie ->
                            val onClick = remember(movie.id) {
                                { onMovieClick(movie.id) }
                            }
                            val onLongClick = remember(movie.id, movie.title, movie.posterUrl) {
                                { onMediaLongClick(movie.id, movie.title, movie.posterUrl, true, ContentType.MOVIE) }
                            }
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rank = index + 1,
                                rating = movie.rating,
                                year = movie.year.toString(),
                                isMovie = true,
                                contentType = ContentType.MOVIE,
                                mediaId = movie.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 5. Trending Series
            if (filteredTrendingSeries.isNotEmpty()) {
                item(key = "section_trending_series") {
                    SectionTitle(stringResource(R.string.trending_series), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(filteredTrendingSeries, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            val onClick = remember(series.id) {
                                { onSeriesClick(series.id) }
                            }
                            val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                            }
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rank = index + 1,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                    androidx.compose.ui.platform.LocalContext.current,
                                    series.seasons.size,
                                    if (series.year > 0) series.year.toString() else ""
                                ),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 6. Trending Anime
            if (filteredAnimeSeries.isNotEmpty()) {
                item(key = "section_trending_anime") {
                    SectionTitle(stringResource(R.string.trending_anime), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(filteredAnimeSeries, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            val onClick = remember(series.id) {
                                { onSeriesClick(series.id) }
                            }
                            val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                            }
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rank = index + 1,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                    androidx.compose.ui.platform.LocalContext.current,
                                    series.seasons.size,
                                    if (series.year > 0) series.year.toString() else ""
                                ),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 7. Coming Soon
            if (upcomingMix.isNotEmpty()) {
                item(key = "section_coming_soon") {
                    SectionTitle(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(upcomingMix, key = { _, item -> "upcoming_${item.id}" }) { index, item ->
                            val onClick = remember(item.id) {
                                { onMovieClick(item.id) }
                            }
                            val onLongClick = remember(item.id, item.title, item.posterUrl) {
                                { onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) }
                            }
                            MediaCard(
                                title = item.title,
                                posterUrl = item.posterUrl,
                                rank = index + 1,
                                rating = item.rating,
                                year = item.year.toString(),
                                isMovie = true,
                                contentType = ContentType.MOVIE,
                                mediaId = item.id,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            val hasFilteredContent = trendingMix.isNotEmpty() ||
                newReleasesMix.isNotEmpty() ||
                filteredTrendingMovies.isNotEmpty() ||
                filteredTrendingSeries.isNotEmpty() ||
                filteredAnimeSeries.isNotEmpty() ||
                upcomingMix.isNotEmpty()

            if (!hasFilteredContent && selectedGenre != null) {
                item(key = "section_empty_genre_filter") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.no_genre_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { selectedGenre = null },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.filter_clear))
                            }
                        }
                    }
                }
            }
        }
    }
}

    if (showBottomSheet) {
        MediaActionBottomSheet(
            isMovie = bottomSheetIsMovie,
            mediaId = selectedMediaId,
            title = selectedMediaTitle,
            posterUrl = selectedMediaPoster,
            contentType = bottomSheetContentType,
            onDismissRequest = { showBottomSheet = false },
            onDownloadStart = { quality ->
                scope.launch {
                    downloadRepository.addToDownloads(DownloadItem(
                        id = selectedMediaId,
                        mediaId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster,
                        isMovie = bottomSheetIsMovie,
                        quality = quality
                    ))
                    Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                }
            },
            onAddToLibrary = {
                scope.launch {
                    val libItem = LibraryItem.create(
                        contentType = bottomSheetContentType,
                        tmdbId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster
                    )
                    libraryRepository.addToLibrary(libItem)
                    Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}



@Composable
fun SectionTitle(title: String, onSeeAllClick: (() -> Unit)? = null) {
    val parts = title.split(" ", limit = 2)
    val firstWord = parts.getOrNull(0) ?: ""
    val rest = parts.getOrNull(1) ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row {
                Text(
                    text = firstWord,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (rest.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rest,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        
        if (onSeeAllClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSeeAllClick() }
            ) {
                Text(
                    text = stringResource(R.string.see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.see_all),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    

}
}
