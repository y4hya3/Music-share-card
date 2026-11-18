package com.example.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

data class AlbumTracklistCandidate(
    val id: String,
    val albumName: String,
    val artistName: String,
    val trackTitles: List<String>,
    val releaseYear: String = "",
    val source: String
)

/**
 * Searches online music catalogs (iTunes Search/Lookup + Deezer fallback) for an album
 * and returns its full song list so users can verify the album before linking it to a
 * locally saved Canvas for offline auto-apply.
 */
object AlbumTracklistSearchService {

    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val PAREN_SUFFIX_REGEX = Regex("\\s*[(\\[](?:feat\\.|ft\\.|featuring|with|remaster(?:ed)?|deluxe|bonus|explicit|clean|version|edit|live|mono|stereo)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val TRACK_NUM_PREFIX_REGEX = Regex("^\\d{1,3}\\s*[-–—.)]+\\s*")
    private val FILE_EXT_REGEX = Regex("\\.(?:mp3|m4a|flac|wav|ogg|aac|opus|wma)$", RegexOption.IGNORE_CASE)

    fun normalizeMatchKey(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        val noExt = FILE_EXT_REGEX.replace(noAccents.trim(), "")
        val noTrackNum = TRACK_NUM_PREFIX_REGEX.replace(noExt.trim(), "")
        val noSuffix = PAREN_SUFFIX_REGEX.replace(noTrackNum, " ")
        return noSuffix
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    suspend fun searchAlbumsWithSongs(
        albumQuery: String,
        artistHint: String = "",
        maxAlbums: Int = 4
    ): List<AlbumTracklistCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = albumQuery.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val combinedTerm = listOf(cleanQuery, artistHint.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val results = mutableListOf<AlbumTracklistCandidate>()

        // 1. iTunes Search -> Lookup tracklist
        runCatching {
            results += fetchItunesAlbumsWithTracks(combinedTerm, limit = maxAlbums)
        }

        // 2. Retry iTunes with album name alone if artist hint narrowed too strictly
        if (results.isEmpty() && artistHint.isNotBlank()) {
            runCatching {
                results += fetchItunesAlbumsWithTracks(cleanQuery, limit = maxAlbums)
            }
        }

        // 3. Deezer fallback if needed or to enrich missing tracklists
        if (results.isEmpty()) {
            runCatching {
                results += fetchDeezerAlbumsWithTracks(cleanQuery, limit = maxAlbums)
            }
        }

        val normalizedQuery = normalizeMatchKey(cleanQuery)
        results
            .filter { it.albumName.isNotBlank() && it.trackTitles.isNotEmpty() }
            .distinctBy { "${normalizeMatchKey(it.albumName)}|${normalizeMatchKey(it.artistName)}" }
            .sortedByDescending { cand ->
                val normAlb = normalizeMatchKey(cand.albumName)
                var score = 0
                if (normAlb == normalizedQuery) score += 100
                else if (normAlb.contains(normalizedQuery) || normalizedQuery.contains(normAlb)) score += 60
                if (artistHint.isNotBlank() &&
                    normalizeMatchKey(cand.artistName).contains(normalizeMatchKey(artistHint))
                ) {
                    score += 35
                }
                score + cand.trackTitles.size.coerceAtMost(25)
            }
            .take(maxAlbums)
    }

    private fun fetchItunesAlbumsWithTracks(query: String, limit: Int): List<AlbumTracklistCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=${(limit * 2).coerceAtLeast(6)}"
        val rawSearch = httpGetText(searchUrl) ?: return emptyList()
        val arr = JSONObject(rawSearch).optJSONArray("results") ?: return emptyList()

        val output = mutableListOf<AlbumTracklistCandidate>()
        for (i in 0 until arr.length()) {
            if (output.size >= limit) break
            val item = arr.optJSONObject(i) ?: continue
            val collectionId = item.optLong("collectionId", 0L)
            val albumName = item.optString("collectionName").trim()
            val artistName = item.optString("artistName").trim()
            val releaseDate = item.optString("releaseDate").take(4)
            if (collectionId <= 0L || albumName.isEmpty()) continue

            val tracks = fetchItunesAlbumTracks(collectionId)
            if (tracks.isNotEmpty()) {
                output += AlbumTracklistCandidate(
                    id = "itunes_$collectionId",
                    albumName = albumName,
                    artistName = artistName,
                    trackTitles = tracks,
                    releaseYear = releaseDate,
                    source = "iTunes"
                )
            }
        }
        return output
    }

    private fun fetchItunesAlbumTracks(collectionId: Long): List<String> {
        val url = "https://itunes.apple.com/lookup?id=$collectionId&entity=song&limit=200"
        val raw = httpGetText(url) ?: return emptyList()
        val results = JSONObject(raw).optJSONArray("results") ?: return emptyList()
        data class TrackEntry(val disc: Int, val num: Int, val title: String)
        val entries = mutableListOf<TrackEntry>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            if (obj.optString("wrapperType") == "track") {
                val title = obj.optString("trackName").trim()
                if (title.isNotEmpty()) {
                    entries += TrackEntry(
                        disc = obj.optInt("discNumber", 1),
                        num = obj.optInt("trackNumber", i),
                        title = title
                    )
                }
            }
        }
        return entries
            .sortedWith(compareBy({ it.disc }, { it.num }))
            .map { it.title }
            .distinct()
    }

    private fun fetchDeezerAlbumsWithTracks(query: String, limit: Int): List<AlbumTracklistCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://api.deezer.com/search/album?q=$encoded&limit=${(limit * 2).coerceAtLeast(6)}"
        val rawSearch = httpGetText(searchUrl) ?: return emptyList()
        val arr = JSONObject(rawSearch).optJSONArray("data") ?: return emptyList()

        val output = mutableListOf<AlbumTracklistCandidate>()
        for (i in 0 until arr.length()) {
            if (output.size >= limit) break
            val item = arr.optJSONObject(i) ?: continue
            val albumId = item.optLong("id", 0L)
            val albumName = item.optString("title").trim()
            val artistName = item.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
            if (albumId <= 0L || albumName.isEmpty()) continue

            val tracksJson = httpGetText("https://api.deezer.com/album/$albumId/tracks?limit=150") ?: continue
            val tracksArr = JSONObject(tracksJson).optJSONArray("data") ?: continue
            val tracks = buildList {
                for (t in 0 until tracksArr.length()) {
                    val tObj = tracksArr.optJSONObject(t) ?: continue
                    val title = tObj.optString("title").trim()
                    if (title.isNotEmpty()) add(title)
                }
            }.distinct()

            if (tracks.isNotEmpty()) {
                output += AlbumTracklistCandidate(
                    id = "deezer_$albumId",
                    albumName = albumName,
                    artistName = artistName,
                    trackTitles = tracks,
                    source = "Deezer"
                )
            }
        }
        return output
    }

    private fun httpGetText(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
