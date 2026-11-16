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
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    val spotifyTokenAccepted: Boolean? = null,
    val refreshedToken: SpotifyTokenResult? = null
)

data class MatchScoreBreakdown(
    val totalScore: Int,
    val titleScore: Int,
    val artistScore: Int
)

/**
 * Smart search ("scratching") engine for animated song canvases & high-res music clips.
 *
 * Anti-mismatch accuracy safeguards:
 * 1. Strips noisy suffixes ("- Topic", "VEVO", "(Official Video)", "[Lyrics]", "(feat. ...)") and accents.
 * 2. Evaluates Title Score and Artist Score separately and rejects unrelated candidates while supporting
 *    all music players (YouTube Music, Apple Music, SoundCloud, Samsung Music, Spotify, local players).
 * 3. Uses Pathfinder + Cross-platform (Deezer/iTunes -> Songlink) + Spotify Canvas protobuf
 *    (`EntityCanvazRequest`) with bounded in-memory caching to avoid /v1/search 429 rate limits.
 */
object AnimatedCanvasSearchService {

    private const val TAG = "AnimatedCanvasSearch"
    private val GENERIC_APP_ARTISTS = setOf(
        "spotify", "youtube music", "youtube", "apple music", "echosnap", "bitchord",
        "soundcloud", "music", "music player", "samsung music", "tidal", "deezer",
        "amazon music", "audiomack", "anghami", "unknown", "unknown artist", "<unknown>",
        "various", "various artists"
    )

    @Volatile
    private var spotifySearchCooldownUntilMs: Long = 0L

    @Volatile
    private var cachedClientToken: Pair<String, Long>? = null

    @Volatile
    private var cachedPathfinderSearchHash: Pair<String, Long>? = null

    private val clientDeviceId: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "")
    }

    private val odesliTrackCache = ConcurrentHashMap<String, SpotifyTrackMatch>()
    private val queryTrackCache = ConcurrentHashMap<String, Pair<Long, List<SpotifyTrackMatch>>>()

    suspend fun searchCanvases(
        rawQuery: String,
        spotifyToken: String,
        spotifySpDcCookie: String = "",
        spotifyTokenExpiryMs: Long = 0L,
        spotifyRefreshToken: String = "",
        spotifyClientId: String = "",
        targetTitle: String = "",
        targetArtist: String = "",
        spotifyTrackId: String = "",
        spDcWebFallback: (suspend (String) -> SpotifyTokenResult?)? = null,
        maxResults: Int = 8
    ): AnimatedCanvasSearchResult = withContext(Dispatchers.IO) {
        val cleaned = normalizeQueryText(rawQuery)
        if (cleaned.isEmpty()) {
            return@withContext AnimatedCanvasSearchResult(
                candidates = emptyList(),
                statusMessage = "Enter a song or artist to search animated canvases."
            )
        }

        var cleanTitle = normalizeQueryText(targetTitle).ifEmpty { cleaned }
        var cleanArtist = normalizeArtistText(targetArtist)
        var hasRealTargetArtist = cleanArtist.isNotEmpty() &&
            cleanArtist.lowercase(Locale.US) !in GENERIC_APP_ARTISTS

        // Many non-Spotify players (YouTube, SoundCloud, local players) embed "Artist - Title" inside title
        if (cleanTitle.contains(" - ")) {
            val parts = cleanTitle.split(" - ", limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                if (!hasRealTargetArtist) {
                    cleanArtist = normalizeArtistText(parts[0])
                    cleanTitle = normalizeQueryText(parts[1]).ifEmpty { cleanTitle }
                    hasRealTargetArtist = cleanArtist.isNotEmpty() &&
                        cleanArtist.lowercase(Locale.US) !in GENERIC_APP_ARTISTS
                } else if (parts[0].equals(cleanArtist, ignoreCase = true) ||
                    cleanArtist.contains(parts[0], ignoreCase = true)
                ) {
                    cleanTitle = normalizeQueryText(parts[1]).ifEmpty { cleanTitle }
                }
            }
        }

        var rawTokenNormalized = normalizeSpotifyToken(spotifyToken)
        var effectiveSpDc = SpotifyAuthManager.extractSpDcValue(spotifySpDcCookie)
        if (SpotifyAuthManager.looksLikeSpDcCookie(rawTokenNormalized)) {
            if (effectiveSpDc.isEmpty()) {
                effectiveSpDc = SpotifyAuthManager.extractSpDcValue(rawTokenNormalized)
            }
            rawTokenNormalized = ""
        }

        var activeToken = rawTokenNormalized
        var refreshedToken: SpotifyTokenResult? = null

        var tokenAccepted: Boolean? = null
        var spotifyNote = ""

        coroutineScope {
            val spotifyDeferred = if (
                activeToken.isNotEmpty() ||
                effectiveSpDc.isNotEmpty() ||
                (spotifyRefreshToken.isNotBlank() && spotifyClientId.isNotBlank())
            ) {
                async {
                    val now = System.currentTimeMillis()
                    val tokenNeedsSpDcRefresh = effectiveSpDc.isNotEmpty() &&
                        (activeToken.isEmpty() || (spotifyTokenExpiryMs > 0L && now >= spotifyTokenExpiryMs - 60_000L))
                    if (tokenNeedsSpDcRefresh) {
                        val exchanged = SpotifyAuthManager.exchangeSpDcForAccessToken(effectiveSpDc)
                            ?: spDcWebFallback?.invoke(effectiveSpDc)
                        if (exchanged != null) {
                            refreshedToken = exchanged
                            activeToken = exchanged.accessToken
                        }
                    }

                    var outcome = if (activeToken.isNotEmpty()) {
                        querySpotifyTracksAndCanvases(
                            cleanQuery = cleaned,
                            cleanTitle = cleanTitle,
                            cleanArtist = if (hasRealTargetArtist) cleanArtist else "",
                            knownSpotifyTrackId = spotifyTrackId,
                            bearerToken = activeToken
                        )
                    } else {
                        SpotifyQueryOutcome(emptyList(), tokenAccepted = false, note = "Refreshing Spotify session...")
                    }

                    if (!outcome.tokenAccepted && effectiveSpDc.isNotEmpty()) {
                        val exchanged = SpotifyAuthManager.exchangeSpDcForAccessToken(effectiveSpDc)
                            ?: spDcWebFallback?.invoke(effectiveSpDc)
                        if (exchanged != null) {
                            refreshedToken = exchanged
                            activeToken = exchanged.accessToken
                            outcome = querySpotifyTracksAndCanvases(
                                cleanQuery = cleaned,
                                cleanTitle = cleanTitle,
                                cleanArtist = if (hasRealTargetArtist) cleanArtist else "",
                                knownSpotifyTrackId = spotifyTrackId,
                                bearerToken = activeToken
                            )
                        }
                    }

                    if (!outcome.tokenAccepted && spotifyRefreshToken.isNotBlank() && spotifyClientId.isNotBlank()) {
                        val renewed = SpotifyAuthManager.refreshPkceToken(spotifyClientId, spotifyRefreshToken)
                        if (renewed != null) {
                            refreshedToken = renewed
                            activeToken = renewed.accessToken
                            outcome = querySpotifyTracksAndCanvases(
                                cleanQuery = cleaned,
                                cleanTitle = cleanTitle,
                                cleanArtist = if (hasRealTargetArtist) cleanArtist else "",
                                knownSpotifyTrackId = spotifyTrackId,
                                bearerToken = activeToken
                            )
                        }
                    }
                    outcome
                }
            } else {
                null
            }

            val itunesVideoDeferred = async {
                val primaryTerm = if (hasRealTargetArtist && cleanTitle.isNotEmpty()) {
                    "$cleanTitle $cleanArtist"
                } else {
                    cleaned
                }
                val effArtist = if (hasRealTargetArtist) cleanArtist else ""
                var results = queryItunesMusicVideos(
                    query = primaryTerm,
                    targetTitle = cleanTitle,
                    targetArtist = effArtist,
                    limit = 12
                )
                if (results.isEmpty() && cleanTitle != primaryTerm) {
                    results = queryItunesMusicVideos(
                        query = cleanTitle,
                        targetTitle = cleanTitle,
                        targetArtist = effArtist,
                        limit = 12
                    )
                }
                if (results.isEmpty()) {
                    results = queryItunesMusicVideos(
                        query = primaryTerm,
                        targetTitle = cleanTitle,
                        targetArtist = effArtist,
                        limit = 12,
                        country = "US"
                    )
                }
                results
            }

            val onlineCoverClipDeferred = async {
                val primaryTerm = if (hasRealTargetArtist && cleanTitle.isNotEmpty()) {
                    "$cleanTitle $cleanArtist"
                } else {
                    cleaned
                }
                val effArtist = if (hasRealTargetArtist) cleanArtist else ""
                queryOnlineAnimatedAndHdSources(
                    query = primaryTerm,
                    targetTitle = cleanTitle,
                    targetArtist = effArtist
                )
            }

            val spotifyOutcome = spotifyDeferred?.await()
            if (spotifyOutcome != null) {
                tokenAccepted = spotifyOutcome.tokenAccepted
                spotifyNote = spotifyOutcome.note
            }
            val spotifyCandidates = spotifyOutcome?.candidates.orEmpty()
            val itunesCandidates = itunesVideoDeferred.await()
            val onlineCandidates = onlineCoverClipDeferred.await()

            val merged = (spotifyCandidates + itunesCandidates + onlineCandidates)
                .distinctBy { it.videoUrl ?: it.hdPosterUrl ?: it.id }
                .sortedWith(
                    compareByDescending<AnimatedCanvasCandidate> { it.isLoopingVideo }
                        .thenByDescending { it.source == "Spotify Canvas" }
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
                if (spotifyNote.isNotEmpty() &&
                    !(spotifyNote.contains("throttled", ignoreCase = true) && videoCount > 0)
                ) {
                    append(spotifyNote)
                    if (withThumbnails.isNotEmpty()) append(" • ")
                }
                if (withThumbnails.isEmpty()) {
                    val targetDesc = if (hasRealTargetArtist) "\"$cleanTitle\" by $cleanArtist" else "\"$cleaned\""
                    if (spotifyNote.isEmpty()) {
                        append("No exact animated canvas matched $targetDesc")
                    } else {
                        append("No exact match for $targetDesc")
                    }
                } else {
                    append("Matched $videoCount animated clip${if (videoCount == 1) "" else "s"} (${withThumbnails.size} exact result${if (withThumbnails.size == 1) "" else "s"})")
                }
            }

            AnimatedCanvasSearchResult(
                candidates = withThumbnails,
                statusMessage = status,
                spotifyTokenAccepted = tokenAccepted,
                refreshedToken = refreshedToken
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

    fun extractSpotifyTrackId(rawInput: String?, packageNameHint: String = ""): String {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        "spotify:track:([A-Za-z0-9]{22})".toRegex().find(raw)?.groupValues?.getOrNull(1)?.let {
            return it
        }
        "open\\.spotify\\.com/(?:intl-[a-z-]+/)?track/([A-Za-z0-9]{22})".toRegex().find(raw)
            ?.groupValues?.getOrNull(1)?.let {
                return it
            }
        if (packageNameHint.contains("spotify", ignoreCase = true) &&
            "^[A-Za-z0-9]{22}$".toRegex().matches(raw)
        ) {
            return raw
        }
        return ""
    }

    fun normalizeQueryText(input: String): String {
        val noDiacritics = stripDiacritics(input)
        return noDiacritics
            .replace("\\([^)]*(feat\\.|ft\\.|official|video|audio|lyric|remaster|version|live|edit|mix|explicit|clean|hd|4k|bonus|deluxe|single|soundtrack|from\\s+\")[^)]*\\)".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("\\[[^\\]]*\\]".toRegex(), " ")
            .replace("\\s+[-–—]\\s+(official|lyric|audio|video|remaster|single|ep|radio\\s+edit).*".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("[\"“”'’]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun normalizeArtistText(input: String): String {
        val noDiacritics = stripDiacritics(input)
        return noDiacritics
            .replace("\\r?\\n.*".toRegex(), "")
            .replace("\\s*-\\s*Topic$".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("VEVO$".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s*Official$".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s*(feat\\.|ft\\.|featuring|with|&|,|•|·|—|–|\\||/|\\bx\\b)\\s*.*".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun stripDiacritics(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(normalized, "")
    }

    fun evaluateMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String
    ): MatchScoreBreakdown {
        val cTitle = normalizeQueryText(candidateTitle).lowercase(Locale.US)
        val cArtistRaw = stripDiacritics(candidateArtist).lowercase(Locale.US)
        val cArtistPrimary = normalizeArtistText(candidateArtist).lowercase(Locale.US)
        val tTitle = normalizeQueryText(targetTitle).lowercase(Locale.US)
        val tArtist = normalizeArtistText(targetArtist).lowercase(Locale.US)

        var titleScore = 0
        if (tTitle.isNotEmpty()) {
            val combinedCandidate = listOf(cTitle, cArtistPrimary)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (cTitle == tTitle) {
                titleScore = 65
            } else if (cTitle.contains(tTitle) || tTitle.contains(cTitle)) {
                titleScore = 48
            } else if (tArtist.isEmpty() && combinedCandidate.contains(tTitle)) {
                titleScore = 46
            } else {
                val tTokens = tTitle.split(" ").filter { it.length > 1 }.toSet()
                val titleTokens = cTitle.split(" ").filter { it.length > 1 }.toSet()
                val combinedTokens = combinedCandidate.split(" ").filter { it.length > 1 }.toSet()
                if (tTokens.isNotEmpty()) {
                    val primaryOverlap = tTokens.intersect(titleTokens).size
                    val primaryScore = (primaryOverlap * 40) / tTokens.size
                    val combinedScore = if (tArtist.isEmpty() && primaryOverlap > 0) {
                        val combinedOverlap = tTokens.intersect(combinedTokens).size
                        (combinedOverlap * 42) / tTokens.size
                    } else {
                        0
                    }
                    titleScore = maxOf(primaryScore, combinedScore)
                }
            }
        }

        var artistScore = 0
        if (tArtist.isNotEmpty()) {
            if (cArtistPrimary == tArtist || cArtistRaw.contains(tArtist) || (cArtistPrimary.isNotEmpty() && tArtist.contains(cArtistPrimary))) {
                artistScore = 40
            } else {
                val aTokens = tArtist.split(" ").filter { it.length > 1 }.toSet()
                val caTokens = cArtistRaw.split(" ", ",", "&", "/").filter { it.length > 1 }.toSet()
                if (aTokens.isNotEmpty()) {
                    val overlap = aTokens.intersect(caTokens).size
                    if (overlap > 0) {
                        artistScore = (overlap * 30) / aTokens.size
                    }
                }
            }
        }

        var penalty = 0
        val lowerCandidate = candidateTitle.lowercase(Locale.US)
        val lowerCandidateArtist = candidateArtist.lowercase(Locale.US)
        if (lowerCandidate.contains("karaoke") && !tTitle.contains("karaoke")) penalty += 45
        if (lowerCandidate.contains("tribute") && !tTitle.contains("tribute")) penalty += 40
        if (lowerCandidate.contains("instrumental") && !tTitle.contains("instrumental")) penalty += 35
        if (lowerCandidate.contains("slowed") && !tTitle.contains("slowed")) penalty += 30
        if (lowerCandidate.contains("nightcore") && !tTitle.contains("nightcore")) penalty += 30
        if (lowerCandidateArtist.contains("karaoke") && !tArtist.contains("karaoke")) penalty += 50

        val total = (titleScore + artistScore - penalty).coerceAtLeast(0)
        return MatchScoreBreakdown(totalScore = total, titleScore = titleScore, artistScore = artistScore)
    }

    fun scoreMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String
    ): Int = evaluateMatch(candidateTitle, candidateArtist, targetTitle, targetArtist).totalScore

    private fun isVerifiedMatch(
        breakdown: MatchScoreBreakdown,
        hasTargetTitle: Boolean,
        hasTargetArtist: Boolean
    ): Boolean {
        if (!hasTargetTitle && !hasTargetArtist) return breakdown.totalScore >= 30
        if (hasTargetArtist && breakdown.artistScore <= 0) return false
        if (hasTargetTitle && breakdown.titleScore < 28) return false
        return if (hasTargetTitle && hasTargetArtist) {
            breakdown.totalScore >= 56
        } else {
            breakdown.totalScore >= 38
        }
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

    private suspend fun querySpotifyTracksAndCanvases(
        cleanQuery: String,
        cleanTitle: String,
        cleanArtist: String,
        knownSpotifyTrackId: String,
        bearerToken: String
    ): SpotifyQueryOutcome {
        return try {
            val collectedTracks = linkedMapOf<String, SpotifyTrackMatch>()
            var tokenAuthFailed = false
            var searchRateLimited = false

            // 1. Direct MediaMetadata Spotify Track ID (instant, zero REST /search calls)
            if (knownSpotifyTrackId.isNotBlank()) {
                collectedTracks[knownSpotifyTrackId] = SpotifyTrackMatch(
                    id = knownSpotifyTrackId,
                    uri = "spotify:track:$knownSpotifyTrackId",
                    title = cleanTitle.ifBlank { cleanQuery },
                    artist = cleanArtist,
                    coverUrl = null,
                    matchScore = 105
                )
            }

            val cacheKey = "${cleanTitle.lowercase(Locale.US)}|${cleanArtist.lowercase(Locale.US)}"
            val cachedTracks = queryTrackCache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < 15 * 60_000L) list else null
            }
            cachedTracks?.forEach { m -> collectedTracks.putIfAbsent(m.id, m) }

            // 2. Active/recent user playback + Spotify Pathfinder + MusicBrainz/ISRC + Deezer/iTunes -> Songlink
            if (collectedTracks.isEmpty()) {
                coroutineScope {
                    val currentlyPlayingDeferred = async {
                        fetchMatchingCurrentlyPlayingTrack(cleanTitle, cleanArtist, bearerToken)
                    }
                    val recentlyPlayedDeferred = async {
                        fetchMatchingRecentlyPlayedTracks(cleanTitle, cleanArtist, bearerToken)
                    }
                    val pathfinderDeferred = async {
                        searchTracksViaPathfinder(cleanQuery, cleanTitle, cleanArtist, bearerToken)
                    }
                    val musicBrainzDeferred = async {
                        resolveSpotifyTracksViaMusicBrainz(cleanQuery, cleanTitle, cleanArtist)
                    }
                    val odesliDeferred = async {
                        resolveSpotifyTracksViaSonglink(cleanQuery, cleanTitle, cleanArtist)
                    }

                    val cpResult = currentlyPlayingDeferred.await()
                    if (cpResult.authError) tokenAuthFailed = true
                    cpResult.match?.let { m ->
                        val existing = collectedTracks[m.id]
                        if (existing == null || m.matchScore > existing.matchScore) {
                            collectedTracks[m.id] = m
                        }
                    }

                    recentlyPlayedDeferred.await().forEach { m ->
                        val existing = collectedTracks[m.id]
                        if (existing == null || m.matchScore > existing.matchScore) {
                            collectedTracks[m.id] = m
                        }
                    }

                    pathfinderDeferred.await().forEach { m ->
                        val existing = collectedTracks[m.id]
                        if (existing == null) {
                            collectedTracks[m.id] = m
                        } else if (existing.coverUrl == null && m.coverUrl != null) {
                            collectedTracks[m.id] = existing.copy(coverUrl = m.coverUrl)
                        }
                    }

                    musicBrainzDeferred.await().forEach { m ->
                        val existing = collectedTracks[m.id]
                        if (existing == null) {
                            collectedTracks[m.id] = m
                        } else if (existing.coverUrl == null && m.coverUrl != null) {
                            collectedTracks[m.id] = existing.copy(coverUrl = m.coverUrl)
                        }
                    }

                    odesliDeferred.await().forEach { m ->
                        val existing = collectedTracks[m.id]
                        if (existing == null) {
                            collectedTracks[m.id] = m
                        } else if (existing.coverUrl == null && m.coverUrl != null) {
                            collectedTracks[m.id] = existing.copy(coverUrl = m.coverUrl)
                        }
                    }
                }
            }

            if (tokenAuthFailed && collectedTracks.isEmpty()) {
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = false,
                    note = "Spotify session expired — reconnect or paste sp_dc in Edit"
                )
            }

            // 3. Conditional /v1/search fallback ONLY if Stages 1-2 found zero tracks and cooldown elapsed
            val nowMs = System.currentTimeMillis()
            if (collectedTracks.isEmpty() && nowMs >= spotifySearchCooldownUntilMs) {
                val structuredQuery = if (cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
                    "track:\"$cleanTitle\" artist:\"$cleanArtist\""
                } else {
                    cleanQuery
                }
                val encoded = URLEncoder.encode(structuredQuery, "UTF-8")
                val searchUrl = "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=6"

                val resp = httpGetWithAuthDetailed(searchUrl, bearerToken)
                val statusCode = resp.statusCode
                val body = resp.body
                if (statusCode == 401 || statusCode == 403) {
                    return SpotifyQueryOutcome(
                        candidates = emptyList(),
                        tokenAccepted = false,
                        note = "Spotify session expired ($statusCode) — reconnect or update sp_dc"
                    )
                } else if (statusCode == 429) {
                    val cooldownMs = ((resp.retryAfterSec ?: 15L) * 1000L).coerceIn(5_000L, 30_000L)
                    spotifySearchCooldownUntilMs = System.currentTimeMillis() + cooldownMs
                    searchRateLimited = true
                } else if (statusCode in 200..299 && !body.isNullOrBlank()) {
                    var parsed = parseSpotifyTracks(body, cleanTitle, cleanArtist)
                    if (parsed.isEmpty() && structuredQuery != cleanQuery) {
                        val broadUrl = "https://api.spotify.com/v1/search?q=${URLEncoder.encode(cleanQuery, "UTF-8")}&type=track&limit=6"
                        val broadResp = httpGetWithAuthDetailed(broadUrl, bearerToken)
                        if (broadResp.statusCode == 429) {
                            val cooldownMs = ((broadResp.retryAfterSec ?: 15L) * 1000L).coerceIn(5_000L, 30_000L)
                            spotifySearchCooldownUntilMs = System.currentTimeMillis() + cooldownMs
                            searchRateLimited = true
                        } else if (broadResp.statusCode in 200..299 && !broadResp.body.isNullOrBlank()) {
                            parsed = parseSpotifyTracks(broadResp.body, cleanTitle, cleanArtist)
                        }
                    }
                    parsed.forEach { m -> collectedTracks.putIfAbsent(m.id, m) }
                }
            } else if (collectedTracks.isEmpty() && nowMs < spotifySearchCooldownUntilMs) {
                searchRateLimited = true
            }

            val tracks = collectedTracks.values.toList()
            if (tracks.isEmpty()) {
                val msg = if (searchRateLimited) {
                    "Spotify track search throttled — showing online sources"
                } else {
                    "No Spotify track match"
                }
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = true,
                    note = msg
                )
            }

            if (queryTrackCache.size > 48) {
                queryTrackCache.clear()
            }
            queryTrackCache[cacheKey] = System.currentTimeMillis() to tracks

            val canvasOutcome = fetchSpotifyCanvases(tracks.map { it.uri }, bearerToken)
            if (canvasOutcome.statusCode == 401) {
                return SpotifyQueryOutcome(
                    candidates = emptyList(),
                    tokenAccepted = false,
                    note = "Spotify session expired (401) — log in again"
                )
            }
            val canvasByTrackUri = canvasOutcome.entries

            val out = mutableListOf<AnimatedCanvasCandidate>()
            tracks.sortedByDescending { it.matchScore }.forEach { track ->
                val canvasInfo = canvasByTrackUri[track.uri]
                if (canvasInfo != null && canvasInfo.url.isNotBlank()) {
                    val isVideo = canvasInfo.type != 0 || canvasInfo.url.endsWith(".mp4", ignoreCase = true)
                    out.add(
                        AnimatedCanvasCandidate(
                            id = "sp_canvas_${track.id}",
                            title = track.title,
                            artist = track.artist,
                            videoUrl = if (isVideo) canvasInfo.url else null,
                            hdPosterUrl = if (!isVideo) canvasInfo.url else track.coverUrl,
                            source = "Spotify Canvas",
                            resolutionLabel = if (isVideo) "Canvas MP4" else "Canvas HD",
                            matchScore = track.matchScore + 30,
                            isLoopingVideo = isVideo
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
            val note = when {
                canvasCount > 0 -> "Spotify Canvas unlocked ($canvasCount loop${if (canvasCount == 1) "" else "s"})"
                canvasOutcome.statusCode == 403 -> "Spotify Canvas unavailable (403) — try reconnecting Spotify"
                else -> "Spotify connected (no Canvas loop on track)"
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
                tokenAccepted = true,
                note = "Spotify lookup fallback active"
            )
        }
    }

    private data class CurrentlyPlayingResult(
        val match: SpotifyTrackMatch? = null,
        val authError: Boolean = false
    )

    private fun fetchMatchingCurrentlyPlayingTrack(
        targetTitle: String,
        targetArtist: String,
        bearerToken: String
    ): CurrentlyPlayingResult {
        return try {
            val (code, body) = httpGetWithAuth(
                "https://api.spotify.com/v1/me/player/currently-playing",
                bearerToken
            )
            if (code == 401) return CurrentlyPlayingResult(authError = true)
            if (code !in 200..299 || body.isNullOrBlank()) return CurrentlyPlayingResult()
            val item = JSONObject(body).optJSONObject("item") ?: return CurrentlyPlayingResult()
            val id = item.optString("id", "").trim()
            val uri = item.optString("uri", "").trim()
            val name = item.optString("name", "").trim()
            if (id.isEmpty() || uri.isEmpty() || name.isEmpty()) return CurrentlyPlayingResult()

            val artistsArr = item.optJSONArray("artists")
            val artistNames = buildList {
                if (artistsArr != null) {
                    for (a in 0 until artistsArr.length()) {
                        val aName = artistsArr.optJSONObject(a)?.optString("name", "").orEmpty()
                        if (aName.isNotBlank()) add(aName)
                    }
                }
            }.joinToString(", ")

            val breakdown = evaluateMatch(name, artistNames, targetTitle, targetArtist)
            if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                return CurrentlyPlayingResult()
            }

            val bestCover = item.optJSONObject("album")?.optJSONArray("images")
                ?.optJSONObject(0)?.optString("url", "")?.trim()?.ifEmpty { null }
            CurrentlyPlayingResult(
                match = SpotifyTrackMatch(
                    id = id,
                    uri = uri,
                    title = name,
                    artist = artistNames,
                    coverUrl = bestCover,
                    matchScore = breakdown.totalScore + 15
                )
            )
        } catch (_: Exception) {
            CurrentlyPlayingResult()
        }
    }

    private fun fetchMatchingRecentlyPlayedTracks(
        targetTitle: String,
        targetArtist: String,
        bearerToken: String
    ): List<SpotifyTrackMatch> {
        return try {
            val (code, body) = httpGetWithAuth(
                "https://api.spotify.com/v1/me/player/recently-played?limit=15",
                bearerToken
            )
            if (code !in 200..299 || body.isNullOrBlank()) return emptyList()
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            val matches = mutableListOf<SpotifyTrackMatch>()
            for (i in 0 until items.length()) {
                val trackObj = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                val id = trackObj.optString("id", "").trim()
                val uri = trackObj.optString("uri", "").trim()
                val name = trackObj.optString("name", "").trim()
                if (id.isEmpty() || uri.isEmpty() || name.isEmpty()) continue
                val artistsArr = trackObj.optJSONArray("artists")
                val artistNames = buildList {
                    if (artistsArr != null) {
                        for (a in 0 until artistsArr.length()) {
                            val aName = artistsArr.optJSONObject(a)?.optString("name", "").orEmpty()
                            if (aName.isNotBlank()) add(aName)
                        }
                    }
                }.joinToString(", ")
                val breakdown = evaluateMatch(name, artistNames, targetTitle, targetArtist)
                if (isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    val cover = trackObj.optJSONObject("album")?.optJSONArray("images")
                        ?.optJSONObject(0)?.optString("url", "")?.trim()?.ifEmpty { null }
                    matches.add(
                        SpotifyTrackMatch(
                            id = id,
                            uri = uri,
                            title = name,
                            artist = artistNames,
                            coverUrl = cover,
                            matchScore = breakdown.totalScore + 8
                        )
                    )
                }
            }
            matches
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Queries Spotify Web Player's internal Pathfinder GraphQL searchDesktop endpoint
     * using the Web Player Bearer token + client-token + live persistedQuery hash.
     */
    private fun searchTracksViaPathfinder(
        cleanQuery: String,
        targetTitle: String,
        targetArtist: String,
        bearerToken: String
    ): List<SpotifyTrackMatch> {
        return try {
            val clientToken = fetchSpotifyClientToken() ?: return emptyList()
            val queryText = if (targetTitle.isNotEmpty() && targetArtist.isNotEmpty()) {
                "$targetTitle $targetArtist"
            } else {
                cleanQuery
            }
            val liveHash = fetchLivePathfinderSearchHash(forceRefresh = false)
            val fallbackHashes = listOfNotNull(
                liveHash,
                "75bbf6bfcfdf85b8fc828417bfad92b7cd66bf7f556d85670f4da8292373ebec",
                "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428",
                "1d021289df50166c61630e02f002ec91182b518e56238728f077264e34f347f5"
            ).distinct()

            for (hash in fallbackHashes) {
                val result = executePathfinderSearchQuery(
                    queryText = queryText,
                    targetTitle = targetTitle,
                    targetArtist = targetArtist,
                    bearerToken = bearerToken,
                    clientToken = clientToken,
                    sha256Hash = hash
                )
                if (result.matches.isNotEmpty()) {
                    cachedPathfinderSearchHash = hash to (System.currentTimeMillis() + 3_600_000L)
                    return result.matches
                }
                if (!result.persistedQueryError) {
                    break
                }
            }

            // If every cached/known hash returned PersistedQueryNotFound, refresh directly from open.spotify.com
            val refreshedHash = fetchLivePathfinderSearchHash(forceRefresh = true)
            if (!refreshedHash.isNullOrBlank() && refreshedHash !in fallbackHashes) {
                val retried = executePathfinderSearchQuery(
                    queryText = queryText,
                    targetTitle = targetTitle,
                    targetArtist = targetArtist,
                    bearerToken = bearerToken,
                    clientToken = clientToken,
                    sha256Hash = refreshedHash
                )
                if (retried.matches.isNotEmpty()) {
                    return retried.matches
                }
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class PathfinderSearchAttempt(
        val matches: List<SpotifyTrackMatch> = emptyList(),
        val persistedQueryError: Boolean = false
    )

    private fun executePathfinderSearchQuery(
        queryText: String,
        targetTitle: String,
        targetArtist: String,
        bearerToken: String,
        clientToken: String,
        sha256Hash: String
    ): PathfinderSearchAttempt {
        val variables = JSONObject().apply {
            put("searchTerm", queryText)
            put("offset", 0)
            put("limit", 8)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includeArtistHasConcertsField", false)
            put("includePreReleases", false)
            put("includeLocalConcertsField", false)
            put("includeAuthors", false)
        }.toString()
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$sha256Hash"}}"""
        val url = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchDesktop" +
            "&variables=${URLEncoder.encode(variables, "UTF-8")}" +
            "&extensions=${URLEncoder.encode(extensions, "UTF-8")}"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("client-token", clientToken)
            setRequestProperty("app-platform", "WebPlayer")
            setRequestProperty("spotify-app-version", "1.2.52.442.g0a11b2a0")
            setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
            setRequestProperty("Origin", "https://open.spotify.com")
            setRequestProperty("Referer", "https://open.spotify.com/")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                cachedClientToken = null
            }
            if (code !in 200..299) {
                return PathfinderSearchAttempt()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val errorsArr = root.optJSONArray("errors")
            val isPersistedErr = errorsArr != null && (0 until errorsArr.length()).any { idx ->
                errorsArr.optJSONObject(idx)?.optString("message", "")
                    ?.contains("PersistedQuery", ignoreCase = true) == true
            }
            val items = root.optJSONObject("data")
                ?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")
                ?.optJSONArray("items")
                ?: root.optJSONObject("data")
                    ?.optJSONObject("searchDesktop")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("items")
                ?: return PathfinderSearchAttempt(persistedQueryError = isPersistedErr)

            val out = mutableListOf<SpotifyTrackMatch>()
            for (i in 0 until items.length()) {
                val itemObj = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                val data = itemObj.optJSONObject("data")
                    ?: itemObj.optJSONObject("itemUnion")?.optJSONObject("data")
                    ?: continue
                val uri = data.optString("uri", "").trim()
                val id = data.optString("id", "").ifBlank { extractSpotifyTrackId(uri) }
                val name = data.optString("name", "").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                val finalUri = uri.ifEmpty { "spotify:track:$id" }

                val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
                val artistNames = buildList {
                    if (artistItems != null) {
                        for (a in 0 until artistItems.length()) {
                            val aName = artistItems.optJSONObject(a)
                                ?.optJSONObject("profile")
                                ?.optString("name", "")
                                .orEmpty()
                            if (aName.isNotBlank()) add(aName)
                        }
                    }
                }.joinToString(", ")

                val breakdown = evaluateMatch(name, artistNames, targetTitle, targetArtist)
                if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    continue
                }

                val coverSources = data.optJSONObject("albumOfTrack")
                    ?.optJSONObject("coverArt")
                    ?.optJSONArray("sources")
                var bestCover: String? = null
                var bestWidth = -1
                if (coverSources != null) {
                    for (s in 0 until coverSources.length()) {
                        val src = coverSources.optJSONObject(s) ?: continue
                        val w = src.optInt("width", 0)
                        val coverUrl = src.optString("url", "").trim()
                        if (coverUrl.isNotEmpty() && w >= bestWidth) {
                            bestWidth = w
                            bestCover = coverUrl
                        }
                    }
                }

                out.add(
                    SpotifyTrackMatch(
                        id = id,
                        uri = finalUri,
                        title = name,
                        artist = artistNames,
                        coverUrl = bestCover,
                        matchScore = breakdown.totalScore + 6
                    )
                )
            }
            PathfinderSearchAttempt(matches = out, persistedQueryError = isPersistedErr)
        } catch (_: Exception) {
            PathfinderSearchAttempt()
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchLivePathfinderSearchHash(forceRefresh: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedPathfinderSearchHash?.let { (hash, exp) ->
                if (hash.length == 64 && now < exp) return hash
            }
        }
        return try {
            val html = httpGetRawWithDesktopUa("https://open.spotify.com/") ?: return null
            val hashRegex = """"searchDesktop"\s*,\s*"query"\s*,\s*"([a-f0-9]{64})"""".toRegex()
            hashRegex.find(html)?.groupValues?.getOrNull(1)?.let { found ->
                cachedPathfinderSearchHash = found to (now + 3_600_000L)
                return found
            }
            val scriptRegex = """https://open\.spotifycdn\.com/cdn/build/[^"'\s]+\.js""".toRegex()
            val scripts = scriptRegex.findAll(html).map { it.value }.distinct()
                .sortedByDescending { it.contains("web-player", ignoreCase = true) || it.contains("xpui", ignoreCase = true) }
                .take(4)
                .toList()
            for (scriptUrl in scripts) {
                val js = httpGetRawWithDesktopUa(scriptUrl) ?: continue
                val found = hashRegex.find(js)?.groupValues?.getOrNull(1)
                if (found != null) {
                    cachedPathfinderSearchHash = found to (now + 3_600_000L)
                    return found
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGetRawWithDesktopUa(urlString: String): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
                setRequestProperty("Accept", "text/html,application/javascript,*/*")
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves verified Spotify Track IDs via MusicBrainz (`url-rels` + Deezer ISRC lookup)
     * without hitting Spotify /v1/search.
     */
    private fun resolveSpotifyTracksViaMusicBrainz(
        cleanQuery: String,
        targetTitle: String,
        targetArtist: String
    ): List<SpotifyTrackMatch> {
        return try {
            val mbQuery = if (targetTitle.isNotEmpty() && targetArtist.isNotEmpty()) {
                "recording:\"$targetTitle\" AND artist:\"$targetArtist\""
            } else {
                cleanQuery
            }
            val url = "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode(mbQuery, "UTF-8")}&fmt=json&limit=5"
            val (code, body) = httpGetWithAuth(url, bearerToken = null)
            if (code !in 200..299 || body.isNullOrBlank()) return emptyList()
            val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
            val out = mutableListOf<SpotifyTrackMatch>()
            for (i in 0 until min(recordings.length(), 4)) {
                val rec = recordings.optJSONObject(i) ?: continue
                val mbid = rec.optString("id", "").trim()
                val title = rec.optString("title", "").trim()
                if (mbid.isEmpty() || title.isEmpty()) continue
                val creditArr = rec.optJSONArray("artist-credit")
                val artistNames = buildList {
                    if (creditArr != null) {
                        for (c in 0 until creditArr.length()) {
                            val name = creditArr.optJSONObject(c)?.optString("name", "").orEmpty()
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }.joinToString(", ")
                val breakdown = evaluateMatch(title, artistNames, targetTitle, targetArtist)
                if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    continue
                }
                val relUrl = "https://musicbrainz.org/ws/2/recording/$mbid?inc=url-rels&fmt=json"
                val (relCode, relBody) = httpGetWithAuth(relUrl, bearerToken = null)
                if (relCode !in 200..299 || relBody.isNullOrBlank()) continue
                val relations = JSONObject(relBody).optJSONArray("relations") ?: continue
                for (r in 0 until relations.length()) {
                    val resource = relations.optJSONObject(r)
                        ?.optJSONObject("url")
                        ?.optString("resource", "")
                        .orEmpty()
                    val spId = extractSpotifyTrackId(resource)
                    if (spId.isNotEmpty() && out.none { it.id == spId }) {
                        out.add(
                            SpotifyTrackMatch(
                                id = spId,
                                uri = "spotify:track:$spId",
                                title = title,
                                artist = artistNames.ifBlank { targetArtist },
                                coverUrl = null,
                                matchScore = breakdown.totalScore + 8
                            )
                        )
                        if (out.size >= 2) return out
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves verified Spotify Track IDs via Deezer + iTunes -> Songlink/Odesli,
     * with title-only fallback if a non-Spotify player sent a noisy/channel artist string.
     */
    private suspend fun resolveSpotifyTracksViaSonglink(
        cleanQuery: String,
        targetTitle: String,
        targetArtist: String
    ): List<SpotifyTrackMatch> = coroutineScope {
        val searchTerm = if (targetTitle.isNotEmpty() && targetArtist.isNotEmpty()) {
            "$targetTitle $targetArtist"
        } else {
            cleanQuery
        }
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")

        val itunesUrlsDeferred = async { findVerifiedTrackUrlsFromItunes(encoded, targetTitle, targetArtist) }
        val deezerUrlsDeferred = async { findVerifiedTrackUrlsFromDeezer(encoded, targetTitle, targetArtist) }

        var itunesUrls = itunesUrlsDeferred.await()
        var deezerUrls = deezerUrlsDeferred.await()

        // If strict artist+title yielded nothing (e.g. YouTube channel/album noise in artist), retry with cleanTitle alone
        if (itunesUrls.isEmpty() && deezerUrls.isEmpty() && targetTitle.length >= 3 && searchTerm != targetTitle) {
            val titleOnlyEncoded = URLEncoder.encode(targetTitle, "UTF-8")
            val retryItunes = async {
                findVerifiedTrackUrlsFromItunes(titleOnlyEncoded, targetTitle, targetArtist).ifEmpty {
                    findVerifiedTrackUrlsFromItunes(titleOnlyEncoded, targetTitle, "")
                }
            }
            val retryDeezer = async {
                findVerifiedTrackUrlsFromDeezer(titleOnlyEncoded, targetTitle, targetArtist).ifEmpty {
                    findVerifiedTrackUrlsFromDeezer(titleOnlyEncoded, targetTitle, "")
                }
            }
            itunesUrls = retryItunes.await()
            deezerUrls = retryDeezer.await()
        }

        // Interleave Deezer & iTunes (Deezer maps cleanly to Spotify via ISRC in Odesli)
        val seedUrls = buildList {
            deezerUrls.getOrNull(0)?.let { add(it) }
            itunesUrls.getOrNull(0)?.let { add(it) }
            deezerUrls.getOrNull(1)?.let { add(it) }
            itunesUrls.getOrNull(1)?.let { add(it) }
        }.distinctBy { it.second }

        if (seedUrls.isEmpty()) return@coroutineScope emptyList()

        val resolved = mutableListOf<SpotifyTrackMatch>()
        var attempts = 0
        for (seed in seedUrls) {
            if (attempts >= 3 && resolved.isNotEmpty()) break
            attempts++
            val match = queryOdesliForSpotifyTrack(seed, targetTitle, targetArtist)
            if (match != null && resolved.none { it.id == match.id }) {
                resolved.add(match)
                if (resolved.size >= 2) break
            }
        }
        resolved
    }

    private fun findVerifiedTrackUrlsFromItunes(
        encodedTerm: String,
        targetTitle: String,
        targetArtist: String
    ): List<Pair<Int, String>> {
        return try {
            val (code, body) = httpGetWithAuth(
                "https://itunes.apple.com/search?term=$encodedTerm&entity=song&limit=8",
                bearerToken = null
            )
            if (code !in 200..299 || body.isNullOrBlank()) return emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<Pair<Int, String>>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val trackUrl = item.optString("trackViewUrl", "").trim()
                val title = item.optString("trackName", "").trim()
                val artist = item.optString("artistName", "").trim()
                if (trackUrl.isEmpty() || title.isEmpty()) continue
                val breakdown = evaluateMatch(title, artist, targetTitle, targetArtist)
                if (isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    list.add(Pair(breakdown.totalScore, trackUrl))
                }
            }
            list.sortedByDescending { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findVerifiedTrackUrlsFromDeezer(
        encodedTerm: String,
        targetTitle: String,
        targetArtist: String
    ): List<Pair<Int, String>> {
        return try {
            val (code, body) = httpGetWithAuth(
                "https://api.deezer.com/search/track?q=$encodedTerm&limit=8",
                bearerToken = null
            )
            if (code !in 200..299 || body.isNullOrBlank()) return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val list = mutableListOf<Pair<Int, String>>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val link = item.optString("link", "").trim()
                val title = item.optString("title", "").trim()
                val artist = item.optJSONObject("artist")?.optString("name", "").orEmpty().trim()
                if (link.isEmpty() || title.isEmpty()) continue
                val breakdown = evaluateMatch(title, artist, targetTitle, targetArtist)
                if (isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    list.add(Pair(breakdown.totalScore, link))
                }
            }
            list.sortedByDescending { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryOdesliForSpotifyTrack(
        scoredUrl: Pair<Int, String>,
        targetTitle: String,
        targetArtist: String
    ): SpotifyTrackMatch? {
        odesliTrackCache[scoredUrl.second]?.let { cached ->
            return cached.copy(matchScore = scoredUrl.first + 10)
        }
        return try {
            val encodedUrl = URLEncoder.encode(scoredUrl.second, "UTF-8")
            val endpoint = "https://api.song.link/v1-alpha.1/links?url=$encodedUrl&userCountry=US"
            var (code, body) = httpGetWithAuth(endpoint, bearerToken = null)
            if (code == 429) {
                try {
                    Thread.sleep(650L)
                } catch (_: InterruptedException) {
                }
                val retry = httpGetWithAuth(endpoint, bearerToken = null)
                code = retry.first
                body = retry.second
            }
            if (code !in 200..299 || body.isNullOrBlank()) return null
            val root = JSONObject(body)
            val spotifyNode = root.optJSONObject("linksByPlatform")?.optJSONObject("spotify") ?: return null
            val spUrl = spotifyNode.optString("url", "").trim()
            val uniqueId = spotifyNode.optString("entityUniqueId", "").trim()
            val spId = extractSpotifyTrackId(spUrl).ifEmpty {
                uniqueId.substringAfter("SPOTIFY_SONG::", "").takeIf { "^[A-Za-z0-9]{22}$".toRegex().matches(it) }.orEmpty()
            }
            if (spId.isEmpty()) return null

            val entity = root.optJSONObject("entitiesByUniqueId")?.optJSONObject(uniqueId)
            val title = entity?.optString("title", "")?.ifBlank { targetTitle } ?: targetTitle
            val artist = entity?.optString("artistName", "")?.ifBlank { targetArtist } ?: targetArtist
            val thumb = entity?.optString("thumbnailUrl", "")?.ifBlank { null }

            val match = SpotifyTrackMatch(
                id = spId,
                uri = "spotify:track:$spId",
                title = title,
                artist = artist,
                coverUrl = thumb,
                matchScore = scoredUrl.first + 10
            )
            if (odesliTrackCache.size > 64) {
                odesliTrackCache.clear()
            }
            odesliTrackCache[scoredUrl.second] = match
            match
        } catch (_: Exception) {
            null
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

            val breakdown = evaluateMatch(name, artistNames, targetTitle, targetArtist)
            if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                continue
            }

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
                    matchScore = breakdown.totalScore
                )
            )
        }
        return list
    }

    internal data class ParsedCanvazEntry(
        val url: String,
        val entityUri: String,
        val type: Int
    )

    private data class CanvasFetchOutcome(
        val entries: Map<String, ParsedCanvazEntry> = emptyMap(),
        val statusCode: Int = 0
    )

    private fun fetchSpotifyCanvases(
        trackUris: List<String>,
        bearerToken: String
    ): CanvasFetchOutcome {
        if (trackUris.isEmpty()) return CanvasFetchOutcome()
        val requestBytes = encodeEntityCanvazRequest(trackUris)
        val firstAttempt = postCanvazProtobuf(requestBytes, bearerToken, clientToken = null)
        if (firstAttempt.statusCode == 200 || firstAttempt.entries.isNotEmpty()) {
            return firstAttempt
        }
        if (firstAttempt.statusCode == 400 || firstAttempt.statusCode == 401 || firstAttempt.statusCode == 403) {
            val clientToken = fetchSpotifyClientToken()
            if (!clientToken.isNullOrBlank()) {
                val secondAttempt = postCanvazProtobuf(requestBytes, bearerToken, clientToken = clientToken)
                if (secondAttempt.entries.isNotEmpty() || secondAttempt.statusCode == 200) {
                    return secondAttempt
                }
            }
        }
        return firstAttempt
    }

    private fun postCanvazProtobuf(
        requestBytes: ByteArray,
        bearerToken: String,
        clientToken: String?
    ): CanvasFetchOutcome {
        return try {
            val conn = (URL("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("Accept", "application/x-protobuf")
                if (!clientToken.isNullOrBlank()) {
                    setRequestProperty("client-token", clientToken)
                    setRequestProperty("app-platform", "WebPlayer")
                    setRequestProperty("spotify-app-version", "1.2.46.25.g7f189073")
                } else {
                    setRequestProperty("User-Agent", "Spotify/8.9.0 Android/33")
                }
            }
            try {
                conn.outputStream.use { it.write(requestBytes) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val respBytes = conn.inputStream.use { it.readBytes() }
                    CanvasFetchOutcome(
                        entries = decodeEntityCanvazEntries(respBytes),
                        statusCode = code
                    )
                } else {
                    CanvasFetchOutcome(statusCode = code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify canvas protobuf request failed: ${e.message}")
            CanvasFetchOutcome()
        }
    }

    private fun fetchSpotifyClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.let { (tok, exp) ->
            if (tok.isNotBlank() && now < exp) return tok
        }
        return try {
            val payload = """
                {
                  "client_data": {
                    "client_version": "1.2.52.442.g0a11b2a0",
                    "client_id": "d8a5ed958d274c2e8ee717e6a4b0971d",
                    "js_sdk_data": {
                      "device_brand": "unknown",
                      "device_model": "unknown",
                      "os": "android",
                      "device_id": "$clientDeviceId"
                    }
                  }
                }
            """.trimIndent()
            val conn = (URL("https://clienttoken.spotify.com/v1/clienttoken").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
                setRequestProperty("Origin", "https://open.spotify.com")
                setRequestProperty("Referer", "https://open.spotify.com/")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val granted = JSONObject(body).optJSONObject("granted_token")
                    val token = granted?.optString("token", "")?.ifBlank { null }
                    if (token != null) {
                        val ttlSec = granted.optLong("refresh_after_seconds", 1200L).coerceIn(120L, 3600L)
                        cachedClientToken = token to (System.currentTimeMillis() + ttlSec * 1000L)
                    }
                    token
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun encodeEntityCanvazRequest(trackUris: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (uri in trackUris) {
            val uriBytes = uri.toByteArray(Charsets.UTF_8)
            val entityMsg = ByteArrayOutputStream()
            entityMsg.write(0x0A)
            writeVarint(entityMsg, uriBytes.size)
            entityMsg.write(uriBytes)

            val entityBytes = entityMsg.toByteArray()
            out.write(0x0A)
            writeVarint(out, entityBytes.size)
            out.write(entityBytes)
        }
        return out.toByteArray()
    }

    internal fun decodeEntityCanvazResponse(bytes: ByteArray): Map<String, String> =
        decodeEntityCanvazEntries(bytes).mapValues { it.value.url }

    internal fun decodeEntityCanvazEntries(bytes: ByteArray): Map<String, ParsedCanvazEntry> {
        val result = linkedMapOf<String, ParsedCanvazEntry>()
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
                    val entry = parseSingleCanvazMessage(sub)
                    if (entry != null) {
                        result[entry.entityUri] = entry
                    }
                }
                offset = end
            } else {
                offset = skipProtobufField(bytes, offset, wireType) ?: break
            }
        }
        return result
    }

    private fun parseSingleCanvazMessage(bytes: ByteArray): ParsedCanvazEntry? {
        var canvasUrl: String? = null
        var entityUri: String? = null
        var type = 2 // default VIDEO_LOOPING
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
            } else if (wireType == 0 && fieldNumber == 4) {
                val (valLong, afterVal) = readVarint(bytes, offset) ?: break
                type = valLong.toInt()
                offset = afterVal
            } else {
                offset = skipProtobufField(bytes, offset, wireType) ?: break
            }
        }
        return if (!canvasUrl.isNullOrBlank() && !entityUri.isNullOrBlank()) {
            ParsedCanvazEntry(url = canvasUrl, entityUri = entityUri, type = type)
        } else {
            null
        }
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
        limit: Int,
        country: String? = null
    ): List<AnimatedCanvasCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val countryParam = if (!country.isNullOrBlank()) "&country=$country" else ""
            val url = "https://itunes.apple.com/search?term=$encoded&entity=musicVideo&limit=$limit$countryParam"
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
                val breakdown = evaluateMatch(trackName, artistName, targetTitle, targetArtist)
                if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                    continue
                }

                val artwork100 = item.optString("artworkUrl100", "").trim()
                val hdPoster = if (artwork100.isNotEmpty()) {
                    artwork100.replace("100x100bb", "1000x1000bb")
                } else {
                    null
                }

                list.add(
                    AnimatedCanvasCandidate(
                        id = "itunes_mv_${item.optLong("trackId", i.toLong())}",
                        title = trackName,
                        artist = artistName,
                        videoUrl = previewVideoUrl,
                        hdPosterUrl = hdPoster,
                        source = "iTunes Video",
                        resolutionLabel = "HD Video",
                        matchScore = breakdown.totalScore,
                        isLoopingVideo = true
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scratches additional online music sources (Apple Music motion cover MP4s if present on album pages,
     * iTunes high-res album art, and Deezer HD cover catalog) so non-Spotify players always have rich results.
     */
    private suspend fun queryOnlineAnimatedAndHdSources(
        query: String,
        targetTitle: String,
        targetArtist: String
    ): List<AnimatedCanvasCandidate> = coroutineScope {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val itunesSongDeferred = async {
            try {
                val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=6"
                val (code, body) = httpGetWithAuth(url, bearerToken = null)
                if (code !in 200..299 || body.isNullOrBlank()) return@async emptyList()
                val results = JSONObject(body).optJSONArray("results") ?: return@async emptyList()
                val out = mutableListOf<AnimatedCanvasCandidate>()
                var checkedAppleMotion = false
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("trackName", "").trim()
                    val artist = item.optString("artistName", "").trim()
                    val breakdown = evaluateMatch(title, artist, targetTitle, targetArtist)
                    if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                        continue
                    }
                    val artwork100 = item.optString("artworkUrl100", "").trim()
                    val hdPoster = if (artwork100.isNotEmpty()) {
                        artwork100.replace("100x100bb", "1000x1000bb")
                    } else {
                        null
                    }
                    val collectionUrl = item.optString("collectionViewUrl", "").trim()
                    if (!checkedAppleMotion && collectionUrl.startsWith("https://music.apple.com/")) {
                        checkedAppleMotion = true
                        val motionMp4 = scrapeAppleMusicMotionMp4(collectionUrl)
                        if (!motionMp4.isNullOrBlank()) {
                            out.add(
                                AnimatedCanvasCandidate(
                                    id = "apple_motion_${item.optLong("collectionId", i.toLong())}",
                                    title = title,
                                    artist = artist,
                                    videoUrl = motionMp4,
                                    hdPosterUrl = hdPoster,
                                    source = "Apple Motion",
                                    resolutionLabel = "Motion MP4",
                                    matchScore = breakdown.totalScore + 22,
                                    isLoopingVideo = true
                                )
                            )
                        }
                    }
                    if (!hdPoster.isNullOrBlank() && out.none { it.hdPosterUrl == hdPoster }) {
                        out.add(
                            AnimatedCanvasCandidate(
                                id = "itunes_hd_${item.optLong("trackId", i.toLong())}",
                                title = title,
                                artist = artist,
                                videoUrl = null,
                                hdPosterUrl = hdPoster,
                                source = "iTunes HD",
                                resolutionLabel = "1000×1000",
                                matchScore = breakdown.totalScore,
                                isLoopingVideo = false
                            )
                        )
                    }
                    if (out.size >= 3) break
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        val deezerDeferred = async {
            try {
                val url = "https://api.deezer.com/search/track?q=$encoded&limit=6"
                val (code, body) = httpGetWithAuth(url, bearerToken = null)
                if (code !in 200..299 || body.isNullOrBlank()) return@async emptyList()
                val data = JSONObject(body).optJSONArray("data") ?: return@async emptyList()
                val out = mutableListOf<AnimatedCanvasCandidate>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    val artist = item.optJSONObject("artist")?.optString("name", "").orEmpty().trim()
                    val breakdown = evaluateMatch(title, artist, targetTitle, targetArtist)
                    if (!isVerifiedMatch(breakdown, targetTitle.isNotEmpty(), targetArtist.isNotEmpty())) {
                        continue
                    }
                    val coverXl = item.optJSONObject("album")?.optString("cover_xl", "").orEmpty().trim()
                    if (coverXl.isNotEmpty()) {
                        out.add(
                            AnimatedCanvasCandidate(
                                id = "deezer_hd_${item.optLong("id", i.toLong())}",
                                title = title,
                                artist = artist,
                                videoUrl = null,
                                hdPosterUrl = coverXl,
                                source = "Deezer HD",
                                resolutionLabel = "1000×1000",
                                matchScore = breakdown.totalScore,
                                isLoopingVideo = false
                            )
                        )
                    }
                    if (out.size >= 2) break
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        (itunesSongDeferred.await() + deezerDeferred.await())
    }

    private fun scrapeAppleMusicMotionMp4(albumUrl: String): String? {
        return try {
            val html = httpGetRawWithDesktopUa(albumUrl) ?: return null
            val mp4Regex = """https://[^"'\s\\]+(?:mvod|itunes)[^"'\s\\]+\.(?:mp4|m4v)""".toRegex(RegexOption.IGNORE_CASE)
            mp4Regex.find(html)?.value?.replace("\\/", "/")
        } catch (_: Exception) {
            null
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

    private data class HttpGetResponse(
        val statusCode: Int,
        val body: String?,
        val retryAfterSec: Long? = null
    )

    private fun httpGetWithAuth(urlString: String, bearerToken: String?): Pair<Int, String?> {
        val res = httpGetWithAuthDetailed(urlString, bearerToken)
        return Pair(res.statusCode, res.body)
    }

    private fun httpGetWithAuthDetailed(urlString: String, bearerToken: String?): HttpGetResponse {
        var currentUrl = urlString
        var redirects = 0
        val isSonglink = urlString.contains("song.link", ignoreCase = true)
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = if (isSonglink) 9000 else 6000
                readTimeout = if (isSonglink) 11000 else 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "EchoSnap/1.2.1 (Android)")
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return HttpGetResponse(code, null)
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                val retryAfterSec = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }
                return HttpGetResponse(code, body, retryAfterSec)
            } finally {
                conn.disconnect()
            }
        }
        return HttpGetResponse(0, null)
    }

    private fun downloadBitmap(urlString: String, maxDimension: Int): Bitmap? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "EchoSnap/1.2.1 (Android)")
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
