package com.example.data.remote

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Path

interface TmdbApiService {
    // Movies
    @GET("trending/movie/day")
    suspend fun getTrendingMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>
    
    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>
    
    @GET("movie/now_playing")
    suspend fun getNewReleasesMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "credits,videos"
    ): TmdbMovieDetails

    // Series
    @GET("trending/tv/day")
    suspend fun getTrendingSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>
    
    @GET("tv/popular")
    suspend fun getPopularSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>
    
    @GET("tv/on_the_air")
    suspend fun getNewReleasesSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    @GET("tv/{tv_id}")
    suspend fun getSeriesDetails(
        @Path("tv_id") seriesId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "credits,videos"
    ): TmdbSeriesDetails
    
    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getSeasonDetails(
        @Path("tv_id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonDetails
    

    @GET("discover/movie")
    suspend fun getUpcomingMoviesDiscover(
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("primary_release_date.gte") minDate: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>

    @GET("discover/tv")
    suspend fun getAnimeSeries(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "16",
        @Query("with_original_language") withOriginalLanguage: String = "ja",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>
    
    @GET("discover/movie")
    suspend fun getAnimeMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "16",
        @Query("with_original_language") withOriginalLanguage: String = "ja",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>


    @GET("discover/tv")
    suspend fun getUpcomingSeriesDiscover(
        @Query("api_key") apiKey: String,
        @Query("without_genres") withoutGenres: String = "16",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("first_air_date.gte") minDate: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    @GET("discover/tv")
    suspend fun getUpcomingAnimeDiscover(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "16",
        @Query("with_original_language") withOriginalLanguage: String = "ja",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("first_air_date.gte") minDate: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    @GET("discover/tv")
    suspend fun getAiringTodayAnime(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String = "16",
        @Query("with_original_language") withOriginalLanguage: String = "ja",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("air_date.gte") minDate: String,
        @Query("air_date.lte") maxDate: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    @GET("discover/movie")
    suspend fun getArabicMovies(
        @Query("api_key") apiKey: String,
        @Query("with_original_language") withOriginalLanguage: String = "ar",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>
    
    @GET("discover/tv")
    suspend fun getArabicSeries(
        @Query("api_key") apiKey: String,
        @Query("with_original_language") withOriginalLanguage: String = "ar",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    // Genres
    @GET("genre/movie/list")
    suspend fun getMovieGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): TmdbGenreResponse

    @GET("genre/tv/list")
    suspend fun getTvGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): TmdbGenreResponse

    @GET("discover/movie")
    suspend fun getMoviesByGenreDiscover(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMovie>

    @GET("discover/tv")
    suspend fun getSeriesByGenreDiscover(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String,
        @Query("without_genres") withoutGenres: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    @GET("discover/tv")
    suspend fun getAnimeByGenreDiscover(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String,
        @Query("with_original_language") withOriginalLanguage: String = "ja",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbSeries>

    // Search
    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbResponse<TmdbMulti>

    @GET("person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "combined_credits"
    ): TmdbPersonDetails
}