package com.example.service

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.min

data class AnimatedCanvasCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val videoUrl: String?,
    val hdPosterUrl: String?,
    val source: String,
    val resolutionLabel: String,
    val matchScore: Int,
    val isLoopingVideo: Boolean,
    val thumbnailBitmap: Bitmap? = null
)

data class AnimatedCanvasSearchResult(
    val candidates: List<AnimatedCanvasCandidate>,
    val statusMessage: String,
    val spotifyTokenAccepted: Boolean? = null
)

/**
 * Smart search ("scratching") engine for animated song canvases & high-res music clips.
 *
 * Strategy:
 * 1. Normalizes noisy media notification titles (removes "(Official Video)", "[Lyrics]",
 *    "feat.", remaster tags, etc.) into clean track + artist tokens.
 * 2. When the user supplies a Spotify Access Token:
 *    - Queries Spotify Web API (`GET /v1/search?type=track`) for exact track URIs and 640px+ art.
 *    - Encodes protobuf `EntityCanvazRequest` and posts to Spotify Canvas endpoint
 *      (`https://spclient.wg.spotify.com/canvaz-cache/v0/canvases`) to extract looping MP4 Canvases.
 * 3. Simultaneously searches public high-res animated music video loops (`iTunes Music Video API`
 *    returning HD .m4v/.mp4 clips + 1000x1000 artwork) and ranks every candidate by smart token overlap.
 */
object AnimatedCanvasSearchService {

    private const val TAG = "AnimatedCanvasSearch"

    suspend fun searchCanvases(
        rawQuery: String,
        spotifyToken: String,
        targetTitle: String = "",
        targetArtist: String = "",
        maxResults: Int = 8
    ): AnimatedCanvasSearchResult = withContext(Dispatchers.IO) {
        val cleaned = normalizeQueryText(rawQuery)
        if (cleaned.isEmpty()) {
            return@withContext AnimatedCanvasSearchResult(
                candidates = emptyList(),
                statusMessage = "Enter a song or artist to search animated canvases."
            )
        }

        val cleanTitle = normalizeQueryText(targetTitle).ifEmpty { cleaned }
        val cleanArtist = normalizeArtistText(targetArtist)
        val trimmedToken = normalizeSpotifyToken(spotifyToken)

        var tokenAccepted: Boolean? = null
        var spotifyNote = ""

        coroutineScope {
            val spotifyDeferred = if (trimmedToken.isNotEmpty()) {
                async {
                    querySpotifyTracksAndCanvases(
                        cleanQuery = cleaned,
                        cleanTitle = cleanTitle,
                        cleanArtist = cleanArtist,
                        bearerToken = trimmedToken
                    )
                }
            } else {
                null
            }

            val itunesVideoDeferred = async {
                var results = queryItunesMusicVideos(cleaned, cleanTitle, cleanArtist, limit = 8)
                if (results.isEmpty() && cleanTitle != cleaned) {
                    results = queryItunesMusicVideos(cleanTitle, cleanTitle, cleanArtist, limit = 6)
                }
                results
            }

            val spotifyOutcome = spotifyDeferred?.await()
            if (spotifyOutcome != null) {
                tokenAccepted = spotifyOutcome.tokenAccepted
                spotifyNote = spotifyOutcome.note
            }
            val spotifyCandidates = spotifyOutcome?.candidates.orEmpty()
            val itunesCandidates = itunesVideoDeferred.await()

            val merged = (spotifyCandidates + itunesCandidates)
                .distinctBy { it.videoUrl ?: it.hdPosterUrl ?: it.id }
                .sortedWith(
                    compareByDescending<AnimatedCanvasCandidate> { it.isLoopingVideo }
                        .thenByDescending { it.source.startsWith("Spotify Canvas") }
                        .thenByDescending { it.matchScore }
                )
                .take(maxResults)

            val withThumbnails = merged.map { candidate ->
                async {
                    val posterUrl = candidate.hdPosterUrl
                    val thumb = if (!posterUrl.isNullOrBlank()) {
                        downloadBitmap(posterUrl, maxDimension = 320)
                    } else {
                        null
                    }
                    val finalThumb = thumb ?: candidate.videoUrl?.let { url ->
                        extractVideoFrame(url, maxDimension = 320)
                    }
                    candidate.copy(thumbnailBitmap = finalThumb)
                }
            }.awaitAll()

            val videoCount = withThumbnails.count { it.isLoopingVideo }
            val status = buildString {
                if (spotifyNote.isNotEmpty()) {
                    append(spotifyNote)
                    if (withThumbnails.isNotEmpty()) append(" • ")
                }
                if (withThumbnails.isEmpty()) {
                    append("No animated canvas found for \"$cleaned\"")
                } else {
                    append("Found $videoCount animated clip${if (videoCount == 1) "" else "s"} & ${withThumbnails.size} HD match${if (withThumbnails.size == 1) "" else "es"}")
                }
            }

            AnimatedCanvasSearchResult(
                candidates = withThumbnails,
                statusMessage = status,
                spotifyTokenAccepted = tokenAccepted
            )
        }
    }

    suspend fun extractHighResPosterOrFrame(candidate: AnimatedCanvasCandidate): Bitmap? =
        withContext(Dispatchers.IO) {
            val videoFrame = candidate.videoUrl?.let { extractVideoFrame(it, maxDimension = 1080) }
            if (videoFrame != null) {
                return@withContext BitmapBlurUtils.trimLetterboxBarsIfPresent(videoFrame)
            }
            val poster = candidate.hdPosterUrl?.let { downloadBitmap(it, maxDimension = 1024) }
            poster?.let { BitmapBlurUtils.trimLetterboxBarsIfPresent(it) } ?: candidate.thumbnailBitmap
        }

    fun normalizeSpotifyToken(rawToken: String): String {
        val cleaned = rawToken.trim()
        return if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
            cleaned.substring(7).trim()
        } else {
            cleaned
        }
    }

    fun normalizeQueryText(input: String): String {
        return input
            .replace("\\([^)]*(feat\\.|ft\\.|official|video|audio|lyric|remaster|version|live|edit|mix|explicit|clean)[^)]*\\)".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("\\[[^\\]]*\\]".toRegex(), " ")
            .replace("\\s+[-–—]\\s+(official|lyric|audio|video).*".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("[\"“”'’]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun normalizeArtistText(input: String): String {
        return input
            .replace("\\s*(feat\\.|ft\\.|with|&|,|•|x)\\s*.*".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun scoreMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String
    ): Int {
        val cTitle = normalizeQueryText(candidateTitle).lowercase(Locale.US)
        val cArtist = normalizeArtistText(candidateArtist).lowercase(Locale.US)
        val tTitle = normalizeQueryText(targetTitle).lowercase(Locale.US)
        val tArtist = normalizeArtistText(targetArtist).lowercase(Locale.US)

        var score = 0
        if (tTitle.isNotEmpty() && cTitle == tTitle) {
            score += 65
        } else if (tTitle.isNotEmpty() && (cTitle.contains(tTitle) || tTitle.contains(cTitle))) {
            score += 42
        } else if (tTitle.isNotEmpty()) {
            val tTokens = tTitle.split(" ").filter { it.length > 1 }.toSet()
            val cTokens = cTitle.split(" ").filter { it.length > 1 }.toSet()
            if (tTokens.isNotEmpty()) {
                val overlap = tTokens.intersect(cTokens).size
                score += (overlap * 35) / tTokens.size
            }
        }

        if (tArtist.isNotEmpty()) {
            if (cArtist == tArtist || candidateArtist.lowercase(Locale.US).contains(tArtist)) {
                score += 35
            } else {
                val aTokens = tArtist.split(" ").filter { it.length > 1 }.toSet()
                val caTokens = candidateArtist.lowercase(Locale.US).split(" ").filter { it.length > 1 }.toSet()
                if (aTokens.isNotEmpty()) {
                    val overlap = aTokens.intersect(caTokens).size
                    score += (overlap * 25) / aTokens.size
                }
            }
        }

        // Penalize obvious cover / karaoke / slowed versions unless explicitly queried
        val lowerCandidate = candidateTitle.lowercase(Locale.US)
        if (lowerCandidate.contains("karaoke") && !tTitle.contains("karaoke")) score -= 40
        if (lowerCandidate.contains("tribute") && !tTitle.contains("tribute")) score -= 35
        if (lowerCandidate.contains("slowed") && !tTitle.contains("slowed")) score -= 25

        return score.coerceAtLeast(0)
    }

    private data class SpotifyQueryOutcome(
        val candidates: List<AnimatedCanvasCandidate>,
        val tokenAccepted: Boolean,
        val note: String
    )

    private data class SpotifyTrackMatch(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String,
        val coverUrl: String?,
        val matchScore: Int
    )

    private fun querySpotifyTracksAndCanvases(
        cleanQuery: String,
        cleanTitle: String,
        cleanArtist: String,
        bearerToken: String
    ): SpotifyQueryOutcome {
        return try {
            val structuredQuery = if (cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
                "track:$cleanTitle artist:$cleanArtist"
            } else {
                cleanQuery
            }
            val encoded = URLEncoder.encode(structuredQuery, "UTF-8")
            val searchUrl = "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=6"

            val (statusCode, body) = httpGetWithAuth(searchUrl, bearerToken)
            if (statusCode == 401 || statusCode == 403) {
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = false,
                    note = "Spotify token expired/rejected ($statusCode)"
                )
            }
            if (statusCode !in 200..299 || body.isNullOrBlank()) {
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = false,
                    note = "Spotify lookup unavailable ($statusCode)"
                )
            }

            var tracks = parseSpotifyTracks(body, cleanTitle, cleanArtist)
            // Fallback to free-text search if field-filtered search found nothing
            if (tracks.isEmpty() && structuredQuery != cleanQuery) {
                val broadUrl = "https://api.spotify.com/v1/search?q=${URLEncoder.encode(cleanQuery, "UTF-8")}&type=track&limit=6"
                val (broadCode, broadBody) = httpGetWithAuth(broadUrl, bearerToken)
                if (broadCode in 200..299 && !broadBody.isNullOrBlank()) {
                    tracks = parseSpotifyTracks(broadBody, cleanTitle, cleanArtist)
                }
            }

            if (tracks.isEmpty()) {
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = true,
                    note = "Spotify connected (no track match)"
                )
            }

            // Query Spotify Canvas cache via protobuf EntityCanvazRequest
            val canvasByTrackUri = fetchSpotifyCanvases(tracks.map { it.uri }, bearerToken)

            val out = mutableListOf<AnimatedCanvasCandidate>()
            tracks.sortedByDescending { it.matchScore }.forEach { track ->
                val canvasUrl = canvasByTrackUri[track.uri]
                if (!canvasUrl.isNullOrBlank()) {
                    out.add(
                        AnimatedCanvasCandidate(
                            id = "sp_canvas_${track.id}",
                            title = track.title,
                            artist = track.artist,
                            videoUrl = canvasUrl,
                            hdPosterUrl = track.coverUrl,
                            source = "Spotify Canvas",
                            resolutionLabel = "Canvas MP4",
                            matchScore = track.matchScore + 25,
                            isLoopingVideo = true
                        )
                    )
                } else if (!track.coverUrl.isNullOrBlank()) {
                    out.add(
                        AnimatedCanvasCandidate(
                            id = "sp_hd_${track.id}",
                            title = track.title,
                            artist = track.artist,
                            videoUrl = null,
                            hdPosterUrl = track.coverUrl,
                            source = "Spotify HD Art",
                            resolutionLabel = "640×640",
                            matchScore = track.matchScore,
                            isLoopingVideo = false
                        )
                    )
                }
            }

            val canvasCount = out.count { it.isLoopingVideo }
            val note = if (canvasCount > 0) {
                "Spotify Canvas unlocked ($canvasCount loop${if (canvasCount == 1) "" else "s"})"
            } else {
                "Spotify connected (matched ${out.size} track${if (out.size == 1) "" else "s"})"
            }

            SpotifyQueryOutcome(
                candidates = out,
                tokenAccepted = true,
                note = note
            )
        } catch (e: Exception) {
            Log.w(TAG, "Spotify search failed", e)
            SpotifyQueryOutcome(
                candidates = emptyList(),
                tokenAccepted = false,
                note = "Spotify lookup error"
            )
        }
    }

    private fun parseSpotifyTracks(
        jsonBody: String,
        targetTitle: String,
        targetArtist: String
    ): List<SpotifyTrackMatch> {
        val root = JSONObject(jsonBody)
        val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<SpotifyTrackMatch>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id", "").trim()
            val uri = item.optString("uri", "").trim()
            val name = item.optString("name", "").trim()
            if (id.isEmpty() || uri.isEmpty() || name.isEmpty()) continue

            val artistsArr = item.optJSONArray("artists")
            val artistNames = buildList {
                if (artistsArr != null) {
                    for (a in 0 until artistsArr.length()) {
                        val aName = artistsArr.optJSONObject(a)?.optString("name", "").orEmpty()
                        if (aName.isNotBlank()) add(aName)
                    }
                }
            }.joinToString(", ")

            val imagesArr = item.optJSONObject("album")?.optJSONArray("images")
            var bestCoverUrl: String? = null
            var bestArea = -1
            if (imagesArr != null) {
                for (m in 0 until imagesArr.length()) {
                    val img = imagesArr.optJSONObject(m) ?: continue
                    val w = img.optInt("width", 0)
                    val h = img.optInt("height", 0)
                    val url = img.optString("url", "").trim()
                    if (url.isNotEmpty() && w * h >= bestArea) {
                        bestArea = w * h
                        bestCoverUrl = url
                    }
                }
            }

            list.add(
                SpotifyTrackMatch(
                    id = id,
                    uri = uri,
                    title = name,
                    artist = artistNames,
                    coverUrl = bestCoverUrl,
                    matchScore = scoreMatch(name, artistNames, targetTitle, targetArtist)
                )
            )
        }
        return list
    }

    /**
     * Queries Spotify's `canvaz-cache/v0/canvases` endpoint with a minimal protobuf
     * `EntityCanvazRequest` payload and extracts any returned looping `.mp4` canvas URLs.
     */
    private fun fetchSpotifyCanvases(
        trackUris: List<String>,
        bearerToken: String
    ): Map<String, String> {
        if (trackUris.isEmpty()) return emptyMap()
        return try {
            val requestBytes = encodeEntityCanvazRequest(trackUris)
            val conn = (URL("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("Accept", "application/x-protobuf")
                setRequestProperty("User-Agent", "Spotify/8.9.0 Android/33")
            }
            try {
                conn.outputStream.use { it.write(requestBytes) }
                if (conn.responseCode in 200..299) {
                    val respBytes = conn.inputStream.use { it.readBytes() }
                    decodeEntityCanvazResponse(respBytes)
                } else {
                    emptyMap()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify canvas protobuf request failed: ${e.message}")
            emptyMap()
        }
    }

    internal fun encodeEntityCanvazRequest(trackUris: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (uri in trackUris) {
            val uriBytes = uri.toByteArray(Charsets.UTF_8)
            val entityMsg = ByteArrayOutputStream()
            // Field 1 (string entity_uri): wire tag (1 << 3) | 2 = 0x0A
            entityMsg.write(0x0A)
            writeVarint(entityMsg, uriBytes.size)
            entityMsg.write(uriBytes)

            val entityBytes = entityMsg.toByteArray()
            // Field 1 (repeated Entity entities): wire tag 0x0A
            out.write(0x0A)
            writeVarint(out, entityBytes.size)
            out.write(entityBytes)
        }
        return out.toByteArray()
    }

    internal fun decodeEntityCanvazResponse(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, nextOffset) = readVarint(bytes, offset) ?: break
            offset = nextOffset
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 2) {
                val (len, afterLen) = readVarint(bytes, offset) ?: break
                val end = min(bytes.size, afterLen + len.toInt().coerceAtLeast(0))
                if (fieldNumber == 1 && end > afterLen) {
                    val sub = bytes.copyOfRange(afterLen, end)
                    val (url, entityUri) = parseSingleCanvazMessage(sub)
                    if (!url.isNullOrBlank() && !entityUri.isNullOrBlank()) {
                        result[entityUri] = url
                    }
                }
                offset = end
            } else {
                offset = skipProtobufField(bytes, offset, wireType) ?: break
            }
        }
        return result
    }

    private fun parseSingleCanvazMessage(bytes: ByteArray): Pair<String?, String?> {
        var canvasUrl: String? = null
        var entityUri: String? = null
        var offset = 0
        while (offset < bytes.size) {
            val (tag, nextOffset) = readVarint(bytes, offset) ?: break
            offset = nextOffset
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 2) {
                val (len, afterLen) = readVarint(bytes, offset) ?: break
                val end = min(bytes.size, afterLen + len.toInt().coerceAtLeast(0))
                if (end > afterLen) {
                    if (fieldNumber == 2) {
                        val str = String(bytes, afterLen, end - afterLen, Charsets.UTF_8)
                        if (str.startsWith("http")) canvasUrl = str
                    } else if (fieldNumber == 5) {
                        val str = String(bytes, afterLen, end - afterLen, Charsets.UTF_8)
                        if (str.startsWith("spotify:")) entityUri = str
                    }
                }
                offset = end
            } else {
                offset = skipProtobufField(bytes, offset, wireType) ?: break
            }
        }
        return Pair(canvasUrl, entityUri)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return
            } else {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < bytes.size && shift <= 63) {
            val b = bytes[i].toInt() and 0xFF
            i++
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return Pair(value, i)
            }
            shift += 7
        }
        return null
    }

    private fun skipProtobufField(bytes: ByteArray, start: Int, wireType: Int): Int? {
        return when (wireType) {
            0 -> readVarint(bytes, start)?.second
            1 -> if (start + 8 <= bytes.size) start + 8 else null
            2 -> {
                val (len, after) = readVarint(bytes, start) ?: return null
                min(bytes.size, after + len.toInt().coerceAtLeast(0))
            }
            5 -> if (start + 4 <= bytes.size) start + 4 else null
            else -> null
        }
    }

    private fun queryItunesMusicVideos(
        query: String,
        targetTitle: String,
        targetArtist: String,
        limit: Int
    ): List<AnimatedCanvasCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&entity=musicVideo&limit=$limit"
            val (code, body) = httpGetWithAuth(url, bearerToken = null)
            if (code !in 200..299 || body.isNullOrBlank()) return emptyList()

            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<AnimatedCanvasCandidate>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val previewVideoUrl = item.optString("previewUrl", "").trim()
                if (previewVideoUrl.isEmpty()) continue

                val trackName = item.optString("trackName", "").ifBlank { "Music Video" }
                val artistName = item.optString("artistName", "")
                val artwork100 = item.optString("artworkUrl100", "").trim()
                val hdPoster = if (artwork100.isNotEmpty()) {
                    artwork100.replace("100x100bb", "1000x1000bb")
                } else {
                    null
                }
                val score = scoreMatch(trackName, artistName, targetTitle, targetArtist)
                if (score < 20 && targetTitle.isNotBlank()) continue

                list.add(
                    AnimatedCanvasCandidate(
                        id = "itunes_mv_${item.optLong("trackId", i.toLong())}",
                        title = trackName,
                        artist = artistName,
                        videoUrl = previewVideoUrl,
                        hdPosterUrl = hdPoster,
                        source = "HD Clip",
                        resolutionLabel = "HD Video",
                        matchScore = score,
                        isLoopingVideo = true
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractVideoFrame(videoUrl: String, maxDimension: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoUrl, HashMap<String, String>())
            val rawFrame = retriever.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            val safe = BitmapBlurUtils.toSafeSoftwareBitmap(rawFrame, maxDimension = maxDimension)
            if (safe !== rawFrame && !rawFrame.isRecycled) {
                rawFrame.recycle()
            }
            safe
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun httpGetWithAuth(urlString: String, bearerToken: String?): Pair<Int, String?> {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BitChord/1.0 Android")
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return Pair(code, null)
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }
                return Pair(code, body)
            } finally {
                conn.disconnect()
            }
        }
        return Pair(0, null)
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
