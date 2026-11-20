package com.example.service

import android.graphics.Bitmap
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineCoverCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val fullResUrl: String,
    val source: String,
    val resolutionLabel: String,
    val thumbnailBitmap: Bitmap? = null
)

/**
 * Searches online music metadata catalogs (iTunes & Deezer public endpoints) for
 * high-resolution square album cover artwork (up to 1000x1000px).
 * Zero API keys required.
 */
object CoverArtSearchService {

    suspend fun searchCoverArt(
        query: String,
        maxResults: Int = 8
    ): List<OnlineCoverCandidate> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val itunesDeferred = async { queryItunes(trimmed, limit = 6) }
            val deezerDeferred = async { queryDeezer(trimmed, limit = 6) }

            val combined = (itunesDeferred.await() + deezerDeferred.await())
                .distinctBy { it.fullResUrl }
                .take(maxResults)

            // Download compact 240px preview thumbnails concurrently for snappy picker display
            combined.map { candidate ->
                async {
                    val thumb = downloadBitmap(candidate.thumbnailUrl, maxDimension = 280)
                    candidate.copy(thumbnailBitmap = thumb)
                }
            }.awaitAll().filter { it.thumbnailBitmap != null }
        }
    }

    suspend fun fetchFullResolutionArt(
        candidate: OnlineCoverCandidate,
        maxDimension: Int = 1024
    ): Bitmap? = withContext(Dispatchers.IO) {
        val targetUrl = if (maxDimension >= 1500 && candidate.fullResUrl.contains("1000x1000bb")) {
            candidate.fullResUrl.replace("1000x1000bb", "3000x3000bb")
        } else {
            candidate.fullResUrl
        }
        val full = downloadBitmap(targetUrl, maxDimension = maxDimension)
            ?: if (targetUrl != candidate.fullResUrl) {
                downloadBitmap(candidate.fullResUrl, maxDimension = maxDimension)
            } else {
                null
            }
        if (full != null) {
            val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(full)
            if (trimmed !== full && !full.isRecycled) {
                full.recycle()
            }
            trimmed
        } else {
            candidate.thumbnailBitmap
        }
    }

    fun pickBestMatchingCoverCandidate(
        candidates: List<OnlineCoverCandidate>,
        targetTitle: String,
        targetArtist: String,
        targetAlbum: String = ""
    ): OnlineCoverCandidate? {
        if (candidates.isEmpty()) return null
        val normTitle = AnimatedCanvasSearchService.normalizeQueryText(targetTitle).lowercase()
        val normArtist = AnimatedCanvasSearchService.normalizeArtistText(targetArtist).lowercase()
        val normAlbum = AnimatedCanvasSearchService.normalizeQueryText(targetAlbum).lowercase()

        fun tokens(s: String): Set<String> =
            s.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

        val titleTokens = tokens(normTitle)
        val artistTokens = tokens(normArtist)
        val albumTokens = tokens(normAlbum)

        var best: OnlineCoverCandidate? = null
        var bestScore = Int.MIN_VALUE

        for (c in candidates) {
            val cTitle = AnimatedCanvasSearchService.normalizeQueryText(c.title).lowercase()
            val cArtist = AnimatedCanvasSearchService.normalizeArtistText(c.artist).lowercase()
            val cAlbum = AnimatedCanvasSearchService.normalizeQueryText(c.album).lowercase()

            val cTitleTokens = tokens(cTitle)
            val cArtistTokens = tokens(cArtist)
            val cAlbumTokens = tokens(cAlbum)

            val titleOverlap = if (titleTokens.isNotEmpty()) {
                titleTokens.count { it in cTitleTokens || cTitle.contains(it) }
            } else 0
            val artistOverlap = if (artistTokens.isNotEmpty()) {
                artistTokens.count { it in cArtistTokens || cArtist.contains(it) }
            } else 0
            val albumOverlap = if (albumTokens.isNotEmpty()) {
                albumTokens.count { it in cAlbumTokens || cAlbum.contains(it) }
            } else 0

            val titleMatched = normTitle.isNotEmpty() && (
                cTitle == normTitle ||
                    cTitle.contains(normTitle) ||
                    normTitle.contains(cTitle) ||
                    titleOverlap >= maxOf(1, (titleTokens.size + 1) / 2)
                )
            val artistMatched = normArtist.isEmpty() ||
                cArtist == normArtist ||
                cArtist.contains(normArtist) ||
                normArtist.contains(cArtist) ||
                artistOverlap >= 1

            if (!titleMatched || !artistMatched) continue

            var score = titleOverlap * 30 + artistOverlap * 25 + albumOverlap * 20
            if (cTitle == normTitle) score += 60
            if (normArtist.isNotEmpty() && cArtist == normArtist) score += 50
            if (normAlbum.isNotEmpty() && cAlbum == normAlbum) score += 45
            if (c.source.contains("iTunes", ignoreCase = true)) score += 10

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun queryItunes(query: String, limit: Int): List<OnlineCoverCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit"
            val body = httpGetString(url) ?: return emptyList()
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return emptyList()

            val list = mutableListOf<OnlineCoverCandidate>()
            val seenAlbums = HashSet<String>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val artwork100 = item.optString("artworkUrl100", "").trim()
                if (artwork100.isEmpty()) continue

                val thumbUrl = artwork100.replace("100x100bb", "300x300bb")
                val hdUrl = artwork100.replace("100x100bb", "1000x1000bb")
                val trackName = item.optString("trackName", "").ifBlank {
                    item.optString("collectionName", "Unknown")
                }
                val artistName = item.optString("artistName", "")
                val albumName = item.optString("collectionName", "")

                val dedupeKey = "$albumName|$artistName".lowercase()
                if (!seenAlbums.add(dedupeKey) && list.size >= 3) continue

                list.add(
                    OnlineCoverCandidate(
                        id = "itunes_${item.optLong("trackId", i.toLong())}",
                        title = trackName,
                        artist = artistName,
                        album = albumName,
                        thumbnailUrl = thumbUrl,
                        fullResUrl = hdUrl,
                        source = "iTunes HD",
                        resolutionLabel = "1000×1000"
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryDeezer(query: String, limit: Int): List<OnlineCoverCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=$limit"
            val body = httpGetString(url) ?: return emptyList()
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return emptyList()

            val list = mutableListOf<OnlineCoverCandidate>()
            val seenAlbums = HashSet<String>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val albumObj = item.optJSONObject("album") ?: continue
                val coverXl = albumObj.optString("cover_xl", "").trim()
                    .ifEmpty { albumObj.optString("cover_big", "").trim() }
                if (coverXl.isEmpty()) continue

                val coverMed = albumObj.optString("cover_medium", "").trim()
                    .ifEmpty { coverXl }
                val artistName = item.optJSONObject("artist")?.optString("name", "") ?: ""
                val albumName = albumObj.optString("title", "")
                val trackName = item.optString("title", "").ifBlank { albumName }

                val dedupeKey = "$albumName|$artistName".lowercase()
                if (!seenAlbums.add(dedupeKey) && list.size >= 3) continue

                list.add(
                    OnlineCoverCandidate(
                        id = "deezer_${item.optLong("id", i.toLong())}",
                        title = trackName,
                        artist = artistName,
                        album = albumName,
                        thumbnailUrl = coverMed,
                        fullResUrl = coverXl,
                        source = "Deezer HD",
                        resolutionLabel = if (albumObj.optString("cover_xl").isNotEmpty()) "1000×1000" else "500×500"
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun httpGetString(urlString: String): String? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BitChord/1.0 Android")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code in 200..299) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun downloadBitmap(urlString: String, maxDimension: Int): Bitmap? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BitChord/1.0 Android")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code in 200..299) {
                    return conn.inputStream.use {
                        BitmapBlurUtils.decodeSampledFromStream(it, maxDimension = maxDimension)
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
