package com.example.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object MediaStorageUtils {

    private val SAFE_SEGMENT_REGEX = Regex("^[a-zA-Z0-9_\\-\\.]+$")
    private val SAFE_EXTENSION_REGEX = Regex("^[a-zA-Z0-9]{1,8}$")
    private val SUPPORTED_VIDEO_EXTENSIONS = listOf("mp4", "mkv", "webm", "ts", "avi", "mov", "m4v")

    /**
     * Get the dedicated internal, app-private directory for downloaded movies/series/anime.
     * Stored in context.filesDir ("media"), keeping them isolated from system file scanners.
     * Includes a .nomedia file as additional protection.
     */
    fun getMediaDirectory(context: Context): File {
        val dir = File(context.filesDir, "media")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: Exception) {}
        }
        val moviesDir = File(dir, "movies")
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val seriesDir = File(dir, "series")
        if (!seriesDir.exists()) seriesDir.mkdirs()
        return dir
    }

    /**
     * Canonical containment check to prevent path traversal (F-001).
     * Verifies that candidate file is strictly contained within approved root.
     */
    fun verifyContained(candidate: File, approvedRoot: File): File {
        val canonicalFile = candidate.canonicalFile
        val canonicalRoot = approvedRoot.canonicalFile
        val filePath = canonicalFile.path
        val rootPath = canonicalRoot.path

        val isInside = filePath == rootPath || filePath.startsWith(rootPath + File.separator)
        if (!isInside) {
            throw SecurityException(
                "Path traversal security violation: '$filePath' escapes approved media root '$rootPath'"
            )
        }
        return canonicalFile
    }

    /**
     * Validates and sanitizes an ID segment (F-001).
     * Rejects traversal sequences, path separators, null bytes, empty/whitespace, and malformed strings.
     */
    fun sanitizeSegment(rawId: String): String {
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) {
            throw SecurityException("Security violation: Media ID cannot be empty or blank")
        }
        if (trimmed.contains("\u0000") || trimmed.contains("%00")) {
            throw SecurityException("Security violation: Media ID contains null bytes")
        }
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw SecurityException("Security violation: Path separators are forbidden in Media ID: '$trimmed'")
        }
        if (trimmed.contains("..")) {
            throw SecurityException("Security violation: Directory traversal sequences forbidden: '$trimmed'")
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.matches(Regex("^[a-zA-Z]:.*"))) {
            throw SecurityException("Security violation: Absolute paths forbidden in Media ID: '$trimmed'")
        }
        if (trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw SecurityException("Security violation: Media ID cannot start or end with dot: '$trimmed'")
        }
        if (trimmed.length > 128) {
            throw SecurityException("Security violation: Media ID length exceeds maximum limit of 128 characters")
        }
        if (!SAFE_SEGMENT_REGEX.matches(trimmed)) {
            throw SecurityException("Security violation: Media ID contains illegal characters: '$trimmed'")
        }
        return trimmed
    }

    /**
     * Validates and sanitizes file extension.
     */
    fun sanitizeExtension(extension: String?): String {
        if (extension.isNullOrBlank()) return "mp4"
        val clean = extension.trim().removePrefix(".")
        if (clean.contains("\u0000") || clean.contains("/") || clean.contains("\\") || clean.contains("..")) {
            throw SecurityException("Security violation: Illegal file extension: '$extension'")
        }
        if (!SAFE_EXTENSION_REGEX.matches(clean)) {
            throw SecurityException("Security violation: Malformed file extension: '$extension'")
        }
        val lower = clean.lowercase()
        return if (lower in SUPPORTED_VIDEO_EXTENSIONS) lower else "mp4"
    }

    /**
     * Opens a secure FileOutputStream strictly validated against path traversal (F-001).
     */
    fun openSecureOutputStream(file: File, context: Context): FileOutputStream {
        val root = getMediaDirectory(context)
        val canonical = verifyContained(file, root)
        val parent = canonical.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (canonical.isDirectory) {
            throw SecurityException("Cannot open FileOutputStream on a directory: '${canonical.path}'")
        }
        return FileOutputStream(canonical)
    }

    /**
     * Canonical path for movie destination: movies/{movieId}.{ext}
     */
    fun getMovieFile(context: Context, movieId: String, extension: String? = "mp4"): File {
        val safeId = sanitizeSegment(movieId)
        val safeExt = sanitizeExtension(extension)
        val root = getMediaDirectory(context)
        val moviesDir = File(root, "movies")
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val target = File(moviesDir, "$safeId.$safeExt")
        return verifyContained(target, root)
    }

    /**
     * Canonical path for series episode destination: series/{seriesId}/s{season}e{episode}.{ext}
     */
    fun getSeriesEpisodeFile(
        context: Context,
        seriesId: String,
        season: Int,
        episode: Int,
        extension: String? = "mp4"
    ): File {
        require(season >= 0) { "Season number must be non-negative" }
        require(episode >= 0) { "Episode number must be non-negative" }
        val safeSeries = sanitizeSegment(seriesId)
        val safeExt = sanitizeExtension(extension)
        val root = getMediaDirectory(context)
        val seriesRoot = File(root, "series")
        val targetSeriesDir = File(seriesRoot, safeSeries)
        if (!targetSeriesDir.exists()) targetSeriesDir.mkdirs()
        val target = File(targetSeriesDir, "s${season}e${episode}.$safeExt")
        return verifyContained(target, root)
    }

    /**
     * Create the destination file for a new download or transfer in internal storage (F-001 & F-012).
     */
    fun getDestinationFile(
        context: Context,
        id: String,
        extension: String? = null,
        isMovie: Boolean = true,
        seriesId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): File {
        val cleanExt = sanitizeExtension(extension)
        if (!isMovie && seriesId != null && season != null && episode != null) {
            return getSeriesEpisodeFile(context, seriesId, season, episode, cleanExt)
        }
        val seriesParsed = parseSeriesIdentity(id)
        if (seriesParsed != null) {
            val (sId, sNum, epNum) = seriesParsed
            return getSeriesEpisodeFile(context, sId, sNum, epNum, cleanExt)
        }
        return getMovieFile(context, id, cleanExt)
    }

    /**
     * Parses structured series episode identity tokens:
     * - series_{seriesId}_s{season}e{episode}
     * - {seriesId}_s{season}e{episode}
     * - {seriesId}_s{season}_e{episode}
     * - {seriesId}_{season}_{episode}
     */
    private fun parseSeriesIdentity(id: String): Triple<String, Int, Int>? {
        val trimmed = id.trim()
        val sRegex = Regex("^(?:series_)?([a-zA-Z0-9\\-]+)_s(\\d+)[_eE](\\d+)$", RegexOption.IGNORE_CASE)
        val sMatch = sRegex.find(trimmed)
        if (sMatch != null) {
            val sId = sMatch.groupValues[1]
            val sNum = sMatch.groupValues[2].toIntOrNull() ?: 1
            val epNum = sMatch.groupValues[3].toIntOrNull() ?: 1
            return Triple(sId, sNum, epNum)
        }
        val numRegex = Regex("^([a-zA-Z0-9\\-]+)_(\\d+)_(\\d+)$")
        val numMatch = numRegex.find(trimmed)
        if (numMatch != null) {
            val sId = numMatch.groupValues[1]
            val sNum = numMatch.groupValues[2].toIntOrNull() ?: 1
            val epNum = numMatch.groupValues[3].toIntOrNull() ?: 1
            return Triple(sId, sNum, epNum)
        }
        return null
    }

    /**
     * Find existing downloaded media file for a given item id across canonical namespace (F-012).
     * Eliminates substringAfter("_"), episode-number-only matching, and ambiguous directory scanning.
     */
    fun findMediaFile(
        context: Context,
        id: String,
        isMovie: Boolean? = null,
        seriesId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): File? {
        val root = getMediaDirectory(context)

        // 1. If explicit series coordinates provided
        if (isMovie == false && seriesId != null && season != null && episode != null) {
            val safeSeries = try { sanitizeSegment(seriesId) } catch (_: Exception) { return null }
            val seriesDir = File(File(root, "series"), safeSeries)
            for (ext in SUPPORTED_VIDEO_EXTENSIONS) {
                val f = File(seriesDir, "s${season}e${episode}.$ext")
                if (f.exists() && f.isFile && f.length() > 0L) return f
            }
            return null
        }

        // 2. If ID encodes full series identity
        val parsed = parseSeriesIdentity(id)
        if (parsed != null) {
            val (sId, sNum, epNum) = parsed
            val safeSeries = try { sanitizeSegment(sId) } catch (_: Exception) { return null }
            val seriesDir = File(File(root, "series"), safeSeries)
            for (ext in SUPPORTED_VIDEO_EXTENSIONS) {
                val f = File(seriesDir, "s${sNum}e${epNum}.$ext")
                if (f.exists() && f.isFile && f.length() > 0L) return f
            }
            // Check legacy full identity in movies folder (exact full identity match only)
            val legacyMoviesDir = File(context.filesDir, "movies")
            if (legacyMoviesDir.exists()) {
                val safeFullId = try { sanitizeSegment(id) } catch (_: Exception) { return null }
                for (ext in SUPPORTED_VIDEO_EXTENSIONS) {
                    val f = File(legacyMoviesDir, "$safeFullId.$ext")
                    if (f.exists() && f.isFile && f.length() > 0L) return f
                }
            }
            return null
        }

        // 3. Movie or Exact ID lookup
        val safeId = try { sanitizeSegment(id) } catch (_: Exception) { return null }
        val moviesDir = File(root, "movies")
        for (ext in SUPPORTED_VIDEO_EXTENSIONS) {
            val f = File(moviesDir, "$safeId.$ext")
            if (f.exists() && f.isFile && f.length() > 0L) return f
        }
        val bareFile = File(moviesDir, safeId)
        if (bareFile.exists() && bareFile.isFile && bareFile.length() > 0L) return bareFile

        // 4. Exact legacy check in filesDir/movies (strictly full ID, never episode number)
        val legacyMoviesDir = File(context.filesDir, "movies")
        if (legacyMoviesDir.exists() && legacyMoviesDir != moviesDir) {
            for (ext in SUPPORTED_VIDEO_EXTENSIONS) {
                val f = File(legacyMoviesDir, "$safeId.$ext")
                if (f.exists() && f.isFile && f.length() > 0L) return f
            }
            val f = File(legacyMoviesDir, safeId)
            if (f.exists() && f.isFile && f.length() > 0L) return f
        }

        return null
    }

    /**
     * Check if a media file exists and has content for a given id.
     */
    fun hasDownloadedMedia(context: Context, id: String): Boolean {
        val file = findMediaFile(context, id)
        return file != null && file.exists() && file.length() > 0L
    }

    /**
     * Get the real file size on disk in bytes for a given item id.
     */
    fun getActualFileSize(context: Context, id: String): Long {
        val file = findMediaFile(context, id)
        return if (file != null && file.exists()) file.length() else 0L
    }

    /**
     * Format byte count into human-readable size (e.g., 450 MB, 1.25 GB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }
}
