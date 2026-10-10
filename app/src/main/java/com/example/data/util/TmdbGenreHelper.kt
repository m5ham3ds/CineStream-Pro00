package com.example.data.util

import com.example.domain.models.Genre
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative TMDB Genre Taxonomy & Localization Helper.
 * Maps TMDB numerical genre IDs to domain Genre models and names,
 * with Arabic localization and dynamic network cache support.
 */
object TmdbGenreHelper {

    // Official TMDB Movie Genres (ID -> Arabic Name)
    val MOVIE_GENRES_MAP = mapOf(
        28 to "أكشن",
        12 to "مغامرة",
        16 to "رسوم متحركة",
        35 to "كوميديا",
        80 to "جريمة",
        99 to "وثائقي",
        18 to "دراما",
        10751 to "عائلي",
        14 to "فانتازيا",
        36 to "تاريخي",
        27 to "رعب",
        10402 to "موسيقى",
        9648 to "غموض",
        10749 to "رومانسي",
        878 to "خيال علمي",
        10770 to "فيلم تلفزيوني",
        53 to "إثارة",
        10752 to "حرب",
        37 to "غربي"
    )

    // English Fallback
    val MOVIE_GENRES_EN_MAP = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    // Official TMDB TV Genres (ID -> Arabic Name)
    val TV_GENRES_MAP = mapOf(
        10759 to "حركة ومغامرة",
        16 to "رسوم متحركة",
        35 to "كوميديا",
        80 to "جريمة",
        99 to "وثائقي",
        18 to "دراما",
        10751 to "عائلي",
        10762 to "أطفال",
        9648 to "غموض",
        10763 to "أخبار",
        10764 to "واقعي",
        10765 to "خيال علمي وفانتازيا",
        10766 to "دراما طويلة",
        10767 to "حوار",
        10768 to "حرب وسياسة",
        37 to "غربي"
    )

    // English Fallback
    val TV_GENRES_EN_MAP = mapOf(
        10759 to "Action & Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        10762 to "Kids",
        9648 to "Mystery",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
        37 to "Western"
    )

    // Specific anime curated genre taxonomy
    val ANIME_GENRES_MAP = mapOf(
        10759 to "أكشن ومغامرات",
        35 to "كوميديا",
        18 to "دراما",
        10765 to "خيال علمي وفانتازيا",
        9648 to "غموض ورعب",
        16 to "رسوم متحركة",
        10751 to "عائلي"
    )

    private val dynamicMovieGenres = ConcurrentHashMap<Int, String>()
    private val dynamicTvGenres = ConcurrentHashMap<Int, String>()

    fun registerMovieGenres(genres: List<Genre>) {
        genres.forEach { dynamicMovieGenres[it.id] = it.name }
    }

    fun registerTvGenres(genres: List<Genre>) {
        genres.forEach { dynamicTvGenres[it.id] = it.name }
    }

    fun getMovieGenreNames(genreIds: List<Int>?): List<String> {
        if (genreIds.isNullOrEmpty()) return emptyList()
        return genreIds.mapNotNull { id ->
            dynamicMovieGenres[id]
                ?: MOVIE_GENRES_MAP[id]
                ?: MOVIE_GENRES_EN_MAP[id]
        }
    }

    fun getTvGenreNames(genreIds: List<Int>?): List<String> {
        if (genreIds.isNullOrEmpty()) return emptyList()
        return genreIds.mapNotNull { id ->
            dynamicTvGenres[id]
                ?: TV_GENRES_MAP[id]
                ?: TV_GENRES_EN_MAP[id]
        }
    }

    fun getMovieGenres(): List<Genre> {
        return MOVIE_GENRES_MAP.map { (id, name) ->
            Genre(id = id, name = dynamicMovieGenres[id] ?: name)
        }
    }

    fun getTvGenres(): List<Genre> {
        return TV_GENRES_MAP.map { (id, name) ->
            Genre(id = id, name = dynamicTvGenres[id] ?: name)
        }
    }

    fun getAnimeGenres(): List<Genre> {
        return ANIME_GENRES_MAP.map { (id, name) ->
            Genre(id = id, name = name)
        }
    }
}
