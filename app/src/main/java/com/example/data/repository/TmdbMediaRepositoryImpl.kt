package com.example.data.repository

import com.example.BuildConfig
import com.example.data.remote.RetrofitClient
import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.domain.models.CastMember
import com.example.domain.models.VideoTrailer
import com.example.domain.models.Season
import com.example.domain.models.Episode
import com.example.domain.models.PersonDetails
import java.time.LocalDate

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.domain.models.Genre
import java.util.concurrent.ConcurrentHashMap

class TmdbMediaRepositoryImpl : MediaRepository {
    
    // Fallback to empty string if missing
    private val apiKey = BuildConfig.TMDB_API_KEY
    
    private val listCache = ConcurrentHashMap<String, List<*>>()
    private val movieDetailsCache = ConcurrentHashMap<String, Movie>()
    private val seriesDetailsCache = ConcurrentHashMap<String, Series>()
    private val personDetailsCache = ConcurrentHashMap<String, PersonDetails>()
    private val seasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()

    override fun clearCache() {
        listCache.clear()
        movieDetailsCache.clear()
        seriesDetailsCache.clear()
        personDetailsCache.clear()
        seasonEpisodesCache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> getCachedListFlow(
        cacheKey: String,
        crossinline fetcher: suspend () -> List<T>
    ): Flow<List<T>> = flow {
        val context = com.example.MyApplication.appContext
        // 1. Check in-memory cache
        var cached = listCache[cacheKey] as? List<T>
        if (cached == null || cached.isEmpty()) {
            // 2. Check persistent disk cache (filesDir)
            cached = com.example.utils.MediaListDiskCacheManager.loadList<T>(context, cacheKey)
            if (cached != null && cached.isNotEmpty()) {
                listCache[cacheKey] = cached
            }
        }

        // 3. Emit cached data immediately to UI for instantaneous offline/online rendering
        var hasEmittedCache = false
        if (cached != null && cached.isNotEmpty()) {
            emit(cached)
            hasEmittedCache = true
        }

        // 4. Validate API key if completely unconfigured or placeholder
        if (apiKey.isBlank() || apiKey == "YOUR_TMDB_API_KEY") {
            if (!hasEmittedCache) {
                throw com.example.domain.models.TmdbException.AuthException(
                    "TMDB API key is unconfigured or placeholder. Please configure TMDB_API_KEY in Secrets panel."
                )
            }
            return@flow
        }

        // 5. Fetch fresh data from network in background
        try {
            val fresh = fetcher()
            if (fresh.isNotEmpty()) {
                listCache[cacheKey] = fresh
                com.example.utils.MediaListDiskCacheManager.saveList(context, cacheKey, fresh)
                emit(fresh)
            }
        } catch (e: Throwable) {
            val tmdbError = when (e) {
                is retrofit2.HttpException -> {
                    when (e.code()) {
                        401, 403 -> com.example.domain.models.TmdbException.AuthException(
                            "TMDB API HTTP ${e.code()}: Invalid API key or unauthorized request.",
                            e
                        )
                        504 -> {
                            val isOnline = try {
                                com.example.utils.NetworkUtils.isInternetAvailable(context)
                            } catch (_: Throwable) { false }
                            if (!isOnline) {
                                com.example.domain.models.TmdbException.NetworkException(
                                    "Connection to TMDB failed: Offline cache miss (HTTP 504).",
                                    e
                                )
                            } else {
                                com.example.domain.models.TmdbException.ServerException(
                                    504,
                                    "TMDB server error (HTTP 504).",
                                    e
                                )
                            }
                        }
                        in 500..599 -> com.example.domain.models.TmdbException.ServerException(
                            e.code(),
                            "TMDB server error (HTTP ${e.code()}).",
                            e
                        )
                        else -> com.example.domain.models.TmdbException.UnknownException(
                            "TMDB HTTP error ${e.code()}: ${e.message()}",
                            e
                        )
                    }
                }
                is java.io.IOException -> com.example.domain.models.TmdbException.NetworkException(
                    "Connection to TMDB failed: ${e.message}",
                    e
                )
                is com.example.domain.models.TmdbException -> e
                else -> com.example.domain.models.TmdbException.UnknownException(
                    e.message ?: "Unknown TMDB error",
                    e
                )
            }

            // Network failed. If nothing was emitted yet, attempt fallback from persistent cache
            if (!hasEmittedCache) {
                val fallback = com.example.utils.MediaListDiskCacheManager.loadList<T>(context, cacheKey)
                if (fallback != null && fallback.isNotEmpty()) {
                    listCache[cacheKey] = fallback
                    emit(fallback)
                } else {
                    // Propagate real domain exception instead of silently converting to emptyList()
                    throw tmdbError
                }
            }
            // If cache was already emitted, keep cached content rendered without crashing
        }
    }.flowOn(Dispatchers.IO)

    override fun getUpcomingMovies(): Flow<List<Movie>> = getCachedListFlow("upcoming_movies") {
        val response = RetrofitClient.tmdbApi.getUpcomingMoviesDiscover(apiKey, minDate = LocalDate.now().toString())
        val today = LocalDate.now().toString()
        response.results.filter { (it.releaseDate ?: "") > today }.map { it.toDomain() }
    }

    override fun getAnimeSeries(): Flow<List<Series>> = getCachedListFlow("anime_series") {
        val response = RetrofitClient.tmdbApi.getAnimeSeries(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getAnimeMovies(): Flow<List<Movie>> = getCachedListFlow("anime_movies") {
        val response = RetrofitClient.tmdbApi.getAnimeMovies(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getNewReleasesMovies(): Flow<List<Movie>> = getCachedListFlow("new_releases_movies") {
        val response = RetrofitClient.tmdbApi.getNewReleasesMovies(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getNewReleasesSeries(): Flow<List<Series>> = getCachedListFlow("new_releases_series") {
        val response = RetrofitClient.tmdbApi.getNewReleasesSeries(apiKey)
        response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }.map { it.toDomain() }
    }

    override fun getMovies(): Flow<List<Movie>> = getCachedListFlow("popular_movies") {
        val response = RetrofitClient.tmdbApi.getPopularMovies(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getSeries(): Flow<List<Series>> = getCachedListFlow("popular_series") {
        val response = RetrofitClient.tmdbApi.getPopularSeries(apiKey)
        response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }.map { it.toDomain() }
    }

    override fun getTrendingMovies(): Flow<List<Movie>> = getCachedListFlow("trending_movies") {
        val response = RetrofitClient.tmdbApi.getTrendingMovies(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getArabicMovies(): Flow<List<Movie>> = getCachedListFlow("arabic_movies") {
        val response = RetrofitClient.tmdbApi.getArabicMovies(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getArabicSeries(): Flow<List<Series>> = getCachedListFlow("arabic_series") {
        val response = RetrofitClient.tmdbApi.getArabicSeries(apiKey)
        response.results.map { it.toDomain() }
    }

    override fun getUpcomingSeries(): Flow<List<Series>> = getCachedListFlow("upcoming_series") {
        val response = RetrofitClient.tmdbApi.getUpcomingSeriesDiscover(apiKey, minDate = LocalDate.now().toString())
        val today = LocalDate.now().toString()
        response.results.filter { (it.firstAirDate ?: "") > today }.map { it.toDomain() }
    }

    override fun getUpcomingAnime(): Flow<List<Series>> = getCachedListFlow("upcoming_anime") {
        val response = RetrofitClient.tmdbApi.getUpcomingAnimeDiscover(apiKey, minDate = LocalDate.now().toString())
        val today = LocalDate.now().toString()
        response.results.filter { (it.firstAirDate ?: "") > today }.map { it.toDomain() }
    }

    override fun getNewReleasesAnime(): Flow<List<Series>> = getCachedListFlow("new_releases_anime") {
        val response = RetrofitClient.tmdbApi.getAiringTodayAnime(apiKey, minDate = LocalDate.now().minusMonths(1).toString(), maxDate = LocalDate.now().toString())
        response.results.map { it.toDomain() }
    }

    override fun getTrendingSeries(): Flow<List<Series>> = getCachedListFlow("trending_series") {
        val response = RetrofitClient.tmdbApi.getTrendingSeries(apiKey)
        response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }.map { it.toDomain() }
    }

    override fun getTrendingAnime(): Flow<List<Series>> = getCachedListFlow("trending_anime") {
        val response = RetrofitClient.tmdbApi.getTrendingSeries(apiKey)
        response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) == ContentType.ANIME }.map { it.toDomain() }
    }

    // Phase 05Y-C: Paginated TMDB List Endpoints
    override suspend fun getTrendingMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTrendingMovies(apiKey, page = page)
            PaginatedResult(
                items = response.results.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getTrendingSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTrendingSeries(apiKey, page = page)
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getTrendingAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTrendingSeries(apiKey, page = page)
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) == ContentType.ANIME }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getPopularMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getPopularMovies(apiKey, page = page)
            PaginatedResult(
                items = response.results.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getPopularSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getPopularSeries(apiKey, page = page)
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getPopularAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getAnimeSeries(apiKey, page = page)
            PaginatedResult(
                items = response.results.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getUpcomingMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val response = RetrofitClient.tmdbApi.getUpcomingMoviesDiscover(apiKey, minDate = today, page = page)
            val filtered = response.results.filter { (it.releaseDate ?: "") > today }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getUpcomingSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val response = RetrofitClient.tmdbApi.getUpcomingSeriesDiscover(apiKey, minDate = today, page = page)
            val filtered = response.results.filter { (it.firstAirDate ?: "") > today }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getUpcomingAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val response = RetrofitClient.tmdbApi.getUpcomingAnimeDiscover(apiKey, minDate = today, page = page)
            val filtered = response.results.filter { (it.firstAirDate ?: "") > today }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getNewReleasesMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getNewReleasesMovies(apiKey, page = page)
            PaginatedResult(
                items = response.results.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getNewReleasesSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getNewReleasesSeries(apiKey, page = page)
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            PaginatedResult(
                items = filtered.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

    override suspend fun getNewReleasesAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val monthAgo = LocalDate.now().minusMonths(1).toString()
            val response = RetrofitClient.tmdbApi.getAiringTodayAnime(apiKey, minDate = monthAgo, maxDate = today, page = page)
            PaginatedResult(
                items = response.results.map { it.toDomain() },
                page = response.page,
                totalPages = response.totalPages,
                totalResults = response.totalResults
            )
        } catch (e: Exception) {
            PaginatedResult(emptyList(), page = page, totalPages = 0)
        }
    }

private val tmdbSemaphore = kotlinx.coroutines.sync.Semaphore(5)
    
    private suspend fun getRealSeasonCount(seriesId: Int): Int {
        return try {
            tmdbSemaphore.withPermit {
                RetrofitClient.tmdbApi.getSeriesDetails(seriesId, BuildConfig.TMDB_API_KEY, appendToResponse = "").seasons?.size ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    override suspend fun getMovieById(id: String): Movie? = withContext(Dispatchers.IO) {
        movieDetailsCache[id]?.let { return@withContext it }
        val context = com.example.MyApplication.appContext
        val diskCached = com.example.utils.MediaDetailsCacheManager.getMovieDetails(context, id)
        if (diskCached != null) {
            movieDetailsCache[id] = diskCached
        }

        if (id.startsWith("provider|")) {
            val parts = id.split("|")
            val title = parts.getOrNull(2) ?: "Unknown"
            val thumb = parts.getOrNull(3) ?: ""
            val movie = Movie(
                id = id,
                title = title,
                originalTitle = title,
                overview = "Content from provider ${parts.getOrNull(1)}",
                posterUrl = thumb,
                backdropUrl = thumb,
                year = 2024,
                releaseDate = "2024",
                rating = 0.0,
                genres = emptyList(),
                runtime = 0,
                language = "en",
                cast = emptyList(),
                trailers = emptyList()
            )
            movieDetailsCache[id] = movie
            return@withContext movie
        }
        try {
            val response = RetrofitClient.tmdbApi.getMovieDetails(id.toInt(), apiKey)
            val movie = response.toDomainDetails()
            movieDetailsCache[id] = movie
            com.example.utils.MediaDetailsCacheManager.saveMovieDetails(context, movie)
            movie
        } catch (e: Exception) {
            diskCached ?: com.example.utils.MediaDetailsCacheManager.getMovieDetails(context, id)
        }
    }

    override suspend fun getSeriesById(id: String): Series? = withContext(Dispatchers.IO) {
        seriesDetailsCache[id]?.let { return@withContext it }
        val context = com.example.MyApplication.appContext
        val diskCached = com.example.utils.MediaDetailsCacheManager.getSeriesDetails(context, id)
        if (diskCached != null) {
            seriesDetailsCache[id] = diskCached
        }

        if (id.startsWith("provider|")) {
            val parts = id.split("|")
            val title = parts.getOrNull(2) ?: "Unknown"
            val thumb = parts.getOrNull(3) ?: ""
            val series = Series(
                id = id,
                title = title,
                overview = "Content from provider ${parts.getOrNull(1)}",
                posterUrl = thumb,
                backdropUrl = thumb,
                year = 2024,
                firstAirDate = "2024",
                rating = 0.0,
                genres = emptyList(),
                cast = emptyList(),
                trailers = emptyList(),
                seasons = listOf(
                    com.example.domain.models.Season(
                        id = "${id}_1",
                        seriesId = id,
                        seasonNumber = 1,
                        title = "Season 1",
                        posterUrl = thumb,
                        episodeCount = 0
                    )
                ),
                creator = parts.getOrNull(1),
                status = "Unknown"
            )
            seriesDetailsCache[id] = series
            return@withContext series
        }
        try {
            val response = RetrofitClient.tmdbApi.getSeriesDetails(id.toInt(), apiKey)
            val series = response.toDomainDetails()
            seriesDetailsCache[id] = series
            com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(context, series)
            series
        } catch (e: Exception) {
            diskCached ?: com.example.utils.MediaDetailsCacheManager.getSeriesDetails(context, id)
        }
    }

    override suspend fun getMovieGenres(): List<Genre> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getMovieGenres(apiKey)
            val genres = response.genres.map { Genre(it.id, com.example.data.util.TmdbGenreHelper.MOVIE_GENRES_MAP[it.id] ?: it.name) }
            if (genres.isNotEmpty()) {
                com.example.data.util.TmdbGenreHelper.registerMovieGenres(genres)
            }
            com.example.data.util.TmdbGenreHelper.getMovieGenres()
        } catch (e: Exception) {
            com.example.data.util.TmdbGenreHelper.getMovieGenres()
        }
    }

    override suspend fun getTvGenres(): List<Genre> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTvGenres(apiKey)
            val genres = response.genres.map { Genre(it.id, com.example.data.util.TmdbGenreHelper.TV_GENRES_MAP[it.id] ?: it.name) }
            if (genres.isNotEmpty()) {
                com.example.data.util.TmdbGenreHelper.registerTvGenres(genres)
            }
            com.example.data.util.TmdbGenreHelper.getTvGenres()
        } catch (e: Exception) {
            com.example.data.util.TmdbGenreHelper.getTvGenres()
        }
    }

    override suspend fun getAnimeGenres(): List<Genre> = withContext(Dispatchers.IO) {
        com.example.data.util.TmdbGenreHelper.getAnimeGenres()
    }

    override fun getMoviesByGenre(genreId: Int): Flow<List<Movie>> = getCachedListFlow("movies_genre_$genreId") {
        val response = RetrofitClient.tmdbApi.getMoviesByGenreDiscover(apiKey, withGenres = genreId.toString())
        response.results.map { it.toDomain() }
    }

    override fun getSeriesByGenre(genreId: Int): Flow<List<Series>> = getCachedListFlow("series_genre_$genreId") {
        val response = RetrofitClient.tmdbApi.getSeriesByGenreDiscover(apiKey, withGenres = genreId.toString())
        response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }.map { it.toDomain() }
    }

    override fun getAnimeByGenre(genreId: Int): Flow<List<Series>> = getCachedListFlow("anime_genre_$genreId") {
        val withGenres = if (genreId == 16) "16" else "16,$genreId"
        val response = RetrofitClient.tmdbApi.getAnimeByGenreDiscover(apiKey, withGenres = withGenres)
        response.results.map { it.toDomain() }
    }
    
    override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = withContext(Dispatchers.IO) {
        val context = com.example.MyApplication.appContext
        try {
            val response = RetrofitClient.tmdbApi.searchMulti(apiKey, query)
            val movies = mutableListOf<Movie>()
            val series = mutableListOf<Series>()
            
            response.results.forEach { item ->
                if (item.mediaType == "movie") {
                    val yearInt = item.releaseDate?.take(4)?.toIntOrNull() ?: 2024
                    val rawGenreIds = item.genreIds ?: emptyList()
                    movies.add(Movie(
                        id = item.id.toString(),
                        title = item.title ?: "Unknown",
                        overview = item.overview ?: "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        releaseDate = item.releaseDate,
                        rating = item.voteAverage ?: 0.0,
                        genres = com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds),
                        runtime = 120,
                        genreIds = rawGenreIds
                    ))
                } else if (item.mediaType == "tv") {
                    val yearInt = item.firstAirDate?.take(4)?.toIntOrNull() ?: 2024
                    val count = getRealSeasonCount(item.id)
                    val rawGenreIds = item.genreIds ?: emptyList()
                    series.add(Series(
                        id = item.id.toString(),
                        title = item.name ?: "Unknown",
                        originalTitle = item.originalName ?: item.name,
                        overview = item.overview ?: "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        firstAirDate = item.firstAirDate,
                        rating = item.voteAverage ?: 0.0,
                        genres = com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds),
                        seasons = if (count > 0) {
                            List(count) { idx ->
                                com.example.domain.models.Season(
                                    id = "${item.id}_${idx + 1}",
                                    seriesId = item.id.toString(),
                                    seasonNumber = idx + 1,
                                    title = "Season ${idx + 1}",
                                    posterUrl = item.fullPosterUrl,
                                    episodeCount = 0
                                )
                            }
                        } else {
                            emptyList()
                        },
                        originalLanguage = item.originalLanguage,
                        originCountry = item.originCountry ?: emptyList(),
                        genreIds = rawGenreIds
                    ))
                }
            }
            Pair(movies, series)
        } catch (e: Exception) {
            // Offline fallback: Search all locally cached media
            val offlineResults = com.example.utils.MediaListDiskCacheManager.searchCachedMedia(context, query)
            offlineResults
        }
    }
    
    // Add extension functions to map from TMDB models to Domain models
    private fun com.example.data.remote.TmdbMovie.toDomain(): Movie {
        val yearInt = releaseDate?.take(4)?.toIntOrNull() ?: 2024
        val rawGenreIds = genreIds ?: emptyList()
        return Movie(
            id = id.toString(),
            title = title ?: "Unknown",
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            releaseDate = releaseDate,
            rating = voteAverage ?: 0.0,
            genres = com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds),
            runtime = 120,
            genreIds = rawGenreIds
        )
    }
    
    private fun com.example.data.remote.TmdbSeries.toDomain(): Series {
        val yearInt = firstAirDate?.take(4)?.toIntOrNull() ?: 2024
        val rawGenreIds = genreIds ?: emptyList()
        return Series(
            id = id.toString(),
            title = name ?: "Unknown",
            originalTitle = originalName ?: name,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            firstAirDate = firstAirDate,
            rating = voteAverage ?: 0.0,
            genres = com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds),
            seasons = emptyList(),
            originalLanguage = originalLanguage,
            originCountry = originCountry ?: emptyList(),
            genreIds = rawGenreIds
        )
    }

    
    override suspend fun getPersonDetails(personId: String): PersonDetails? = withContext(Dispatchers.IO) {
        personDetailsCache[personId]?.let { return@withContext it }
        try {
            val response = RetrofitClient.tmdbApi.getPersonDetails(personId.toInt(), apiKey)
            val movies = mutableListOf<Movie>()
            val series = mutableListOf<Series>()
            response.combinedCredits?.cast?.forEach { item ->
                if (item.mediaType == "movie") {
                    val yearInt = item.releaseDate?.take(4)?.toIntOrNull() ?: 2024
                    movies.add(Movie(
                        id = item.id.toString(),
                        title = item.title ?: "Unknown",
                        overview = "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        releaseDate = item.releaseDate,
                        rating = item.voteAverage ?: 0.0,
                        genres = emptyList(),
                        runtime = 120
                    ))
                } else if (item.mediaType == "tv") {
                    val yearInt = item.firstAirDate?.take(4)?.toIntOrNull() ?: 2024
                    val count = getRealSeasonCount(item.id)
                    series.add(Series(
                        id = item.id.toString(),
                        title = item.name ?: "Unknown",
                        originalTitle = item.originalName ?: item.name,
                        overview = "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        firstAirDate = item.firstAirDate,
                        rating = item.voteAverage ?: 0.0,
                        genres = emptyList(),
                        seasons = if (count > 0) {
                            List(count) { idx ->
                                com.example.domain.models.Season(
                                    id = "${item.id}_${idx + 1}",
                                    seriesId = item.id.toString(),
                                    seasonNumber = idx + 1,
                                    title = "Season ${idx + 1}",
                                    posterUrl = item.fullPosterUrl,
                                    episodeCount = 0
                                )
                            }
                        } else {
                            emptyList()
                        }
                    ))
                }
            }
            
            movies.sortByDescending { it.rating }
            series.sortByDescending { it.rating }

            val person = PersonDetails(
                id = response.id.toString(),
                name = response.name ?: "Unknown",
                biography = response.biography ?: "",
                profileUrl = response.fullProfileUrl,
                birthday = response.birthday,
                placeOfBirth = response.placeOfBirth,
                knownFor = response.knownForDepartment,
                movies = movies,
                series = series
            )
            personDetailsCache[personId] = person
            person
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<Episode> = withContext(Dispatchers.IO) {
        val cacheKey = "${seriesId}_$seasonNumber"
        seasonEpisodesCache[cacheKey]?.let { return@withContext it }
        try {
            val response = RetrofitClient.tmdbApi.getSeasonDetails(seriesId.toInt(), seasonNumber, apiKey)
            val episodes = response.episodes?.map {
                Episode(
                    id = it.id.toString(),
                    episodeNumber = it.episodeNumber,
                    title = it.name ?: "Unknown",
                    overview = it.overview ?: "",
                    thumbnailUrl = it.fullStillUrl ?: "",
                    duration = it.runtime ?: 45,
                    rating = it.voteAverage ?: 0.0,
                    seasonNumber = seasonNumber
                )
            } ?: emptyList()
            if (episodes.isNotEmpty()) {
                seasonEpisodesCache[cacheKey] = episodes
            }
            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun com.example.data.remote.TmdbMovieDetails.toDomainDetails(): Movie {
        val yearInt = releaseDate?.take(4)?.toIntOrNull() ?: 2024
        return Movie(
            id = id.toString(),
            title = title ?: "Unknown",
            originalTitle = originalTitle,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            releaseDate = releaseDate,
            rating = voteAverage ?: 0.0,
            genres = genres?.map { it.name } ?: emptyList(),
            runtime = runtime ?: 120,
            language = originalLanguage ?: "en",
            cast = credits?.cast?.take(15)?.map { CastMember(it.id.toString(), it.name, it.character ?: "", it.fullProfileUrl) } ?: emptyList(),
            trailers = videos?.results?.filter { it.site == "YouTube" && it.type == "Trailer" }?.map { VideoTrailer(it.name, it.key, it.type) } ?: emptyList()
        )
    }

    private fun com.example.data.remote.TmdbSeriesDetails.toDomainDetails(): Series {
        val yearInt = firstAirDate?.take(4)?.toIntOrNull() ?: 2024
        return Series(
            id = id.toString(),
            title = name ?: "Unknown",
            originalTitle = originalName ?: name,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            firstAirDate = firstAirDate,
            rating = voteAverage ?: 0.0,
            genres = genres?.map { it.name } ?: emptyList(),
            cast = credits?.cast?.take(15)?.map { CastMember(it.id.toString(), it.name, it.character ?: "", it.fullProfileUrl) } ?: emptyList(),
            trailers = videos?.results?.filter { it.site == "YouTube" && it.type == "Trailer" }?.map { VideoTrailer(it.name, it.key, it.type) } ?: emptyList(),
            seasons = seasons?.map { 
                Season(
                    id = it.id.toString(),
                    seriesId = this.id.toString(),
                    seasonNumber = it.seasonNumber,
                    title = it.name,
                    posterUrl = it.fullPosterUrl ?: fullPosterUrl,
                    episodeCount = it.episodeCount
                ) 
            } ?: emptyList(),
            creator = createdBy?.firstOrNull()?.name,
            status = status,
            originalLanguage = originalLanguage,
            originCountry = originCountry ?: emptyList()
        )
    }
}