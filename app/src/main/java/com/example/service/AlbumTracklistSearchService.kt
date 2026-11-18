package com.example.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class ParsedAlbumQuery(
    val album: String,
    val artist: String
)

private data class AlbumHeaderStub(
    val id: String,
    val source: String,
    val albumName: String,
    val artistName: String,
    val releaseYear: String,
    val trackCountHint: Int,
    val isSingleHint: Boolean,
    val score: Int,
    val fetchTracks: suspend () -> List<String>
)

/**
 * Smart multi-catalog album + tracklist search engine (iTunes + Deezer + MusicBrainz).
 *
 * Designed to avoid false "featured-on" albums or wrong artists when searching short/numeric
 * album names (e.g. "27" by "ElGrandeToto") or natural queries like "album 27 by elgrandetoto".
 */
object AlbumTracklistSearchService {

    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val PAREN_SUFFIX_REGEX = Regex(
        "\\s*[(\\[](?:feat\\b|ft\\b|featuring\\b|avec\\b|with\\b|con\\b|com\\b|mit\\b|prod\\b|remaster(?:ed)?|deluxe|bonus|explicit|clean|version|edit|live|mono|stereo)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_FEAT_REGEX = Regex(
        "\\s+(?:[-–—]\\s*)?(?:feat\\.|feat\\b|ft\\.|ft\\b|featuring\\b)[^)\\]]*$",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_AVEC_WITH_REGEX = Regex(
        "^(.+?)\\s+(?:[-–—]\\s*)?(?:avec|with)\\s+.+$",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_TRACK_NUMBER_DELIMITER = Regex("(?<=^|\\s)\\d{1,2}\\s*[-–—.)]+\\s*")
    private val TRACK_NUM_PREFIX_REGEX = Regex("^\\d{1,3}(?:[.)]+\\s*|\\s*[-–—]+\\s+)")
    private val FILE_EXT_REGEX = Regex("\\.(?:mp3|m4a|flac|wav|ogg|aac|opus|wma)$", RegexOption.IGNORE_CASE)
    private val LEADING_ALBUM_WORD_REGEX = Regex("^(?:the\\s+|l')?album\\s+", RegexOption.IGNORE_CASE)
    private val BY_SPLIT_REGEX = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)

    private val GENERIC_COMPILATION_ARTISTS = setOf(
        "various artists", "various", "compilation", "soundtrack", "original soundtrack", "va"
    )

    fun normalizeMatchKey(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        val noExt = FILE_EXT_REGEX.replace(noAccents.trim(), "")
        val noTrackNum = TRACK_NUM_PREFIX_REGEX.replace(noExt.trim(), "")
        val noParenSuffix = PAREN_SUFFIX_REGEX.replace(noTrackNum, " ").trim()
        val noTrailingFeat = TRAILING_FEAT_REGEX.replace(noParenSuffix, " ")
        return noTrailingFeat
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Produces the core title key with both featuring tags ("feat", "ft", "featuring")
     * and localized "avec" / "with" featured-artist clauses stripped (when preceded by a song title).
     */
    fun normalizeCoreTitleKey(raw: String): String {
        val base = normalizeMatchKey(raw)
        if (base.isEmpty()) return ""
        val match = TRAILING_AVEC_WITH_REGEX.matchEntire(base)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: base
    }

    /**
     * Parses queries such as:
     * - "album 27 by elgrandetoto" -> album="27", artist="elgrandetoto"
     * - "27 - ElGrandeToto" -> album="27", artist="ElGrandeToto"
     * - album="27", artist="ElGrandeToto" -> album="27", artist="ElGrandeToto"
     */
    fun parseSmartAlbumQuery(albumQuery: String, artistHint: String = ""): ParsedAlbumQuery {
        var cleanAlbum = LEADING_ALBUM_WORD_REGEX.replace(albumQuery.trim(), "").trim()
        var cleanArtist = artistHint.trim()

        if (cleanArtist.isEmpty()) {
            val byParts = BY_SPLIT_REGEX.split(cleanAlbum, limit = 2).map { it.trim() }
            if (byParts.size == 2 && byParts[0].isNotEmpty() && byParts[1].isNotEmpty()) {
                cleanAlbum = LEADING_ALBUM_WORD_REGEX.replace(byParts[0], "").trim()
                cleanArtist = byParts[1]
            } else if (cleanAlbum.contains(" - ")) {
                val dashParts = cleanAlbum.split(" - ", limit = 2).map { it.trim() }
                if (dashParts.size == 2 && dashParts[0].isNotEmpty() && dashParts[1].isNotEmpty()) {
                    cleanAlbum = LEADING_ALBUM_WORD_REGEX.replace(dashParts[0], "").trim()
                    cleanArtist = dashParts[1]
                }
            }
        }
        return ParsedAlbumQuery(album = cleanAlbum.ifBlank { albumQuery.trim() }, artist = cleanArtist)
    }

    /**
     * Parses manually entered or pasted song names — supporting both multi-line / semicolon lists
     * AND single-line numbered lists such as "1- Song A 2- Song B 3- Song C" or "1. A 2. B",
     * stripping leading track numbers and deduplicating case-insensitively.
     */
    fun parseManualSongNames(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val expandedChunks = raw.split('\n', '\r', ';').flatMap { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                emptyList()
            } else {
                val numberMatches = INLINE_TRACK_NUMBER_DELIMITER.findAll(trimmed).toList()
                if (numberMatches.size >= 2) {
                    buildList {
                        val prefixBeforeFirst = trimmed.substring(0, numberMatches.first().range.first).trim()
                        if (prefixBeforeFirst.isNotEmpty()) add(prefixBeforeFirst)
                        for (idx in numberMatches.indices) {
                            val start = numberMatches[idx].range.last + 1
                            val end = if (idx + 1 < numberMatches.size) {
                                numberMatches[idx + 1].range.first
                            } else {
                                trimmed.length
                            }
                            if (start < end) {
                                val segment = trimmed.substring(start, end).trim()
                                if (segment.isNotEmpty()) add(segment)
                            }
                        }
                    }
                } else {
                    listOf(TRACK_NUM_PREFIX_REGEX.replace(trimmed, "").trim())
                }
            }
        }
        return expandedChunks
            .asSequence()
            .map { TRACK_NUM_PREFIX_REGEX.replace(it.trim(), "").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()
    }

    fun scoreAlbumCandidateMatch(
        candidateAlbum: String,
        candidateArtist: String,
        targetAlbum: String,
        targetArtist: String,
        trackCountHint: Int = 0,
        isSingleHint: Boolean = false
    ): Int {
        val normTargetAlbum = normalizeMatchKey(targetAlbum)
        val compactTargetAlbum = normTargetAlbum.replace(" ", "")
        val normCandAlbum = normalizeMatchKey(candidateAlbum)
        val compactCandAlbum = normCandAlbum.replace(" ", "")

        if (normCandAlbum.isEmpty() || normTargetAlbum.isEmpty()) return Int.MIN_VALUE / 2

        var score = 0
        val wholeWordRegex = runCatching {
            Regex("(?:^|\\s)${Regex.escape(normTargetAlbum)}(?:\\s|$)")
        }.getOrNull()

        score += when {
            normCandAlbum == normTargetAlbum ||
                (compactTargetAlbum.isNotEmpty() && compactCandAlbum == compactTargetAlbum) -> 340
            normCandAlbum.startsWith("$normTargetAlbum ") -> 220
            wholeWordRegex?.containsMatchIn(normCandAlbum) == true -> 150
            normTargetAlbum.length >= 3 &&
                (normCandAlbum.contains(normTargetAlbum) || normTargetAlbum.contains(normCandAlbum)) -> 70
            else -> -200
        }

        val normTargetArtist = normalizeMatchKey(targetArtist)
        val compactTargetArtist = normTargetArtist.replace(" ", "")
        val normCandArtist = normalizeMatchKey(candidateArtist)
        val compactCandArtist = normCandArtist.replace(" ", "")

        if (normTargetArtist.isNotEmpty()) {
            if (normCandArtist in GENERIC_COMPILATION_ARTISTS && normTargetArtist !in GENERIC_COMPILATION_ARTISTS) {
                score -= 220
            } else {
                val targetTokens = normTargetArtist.split(" ").filter { it.length >= 2 }
                val candTokens = normCandArtist.split(" ").filter { it.length >= 2 }.toSet()
                val overlap = if (targetTokens.isEmpty()) 0 else targetTokens.count { it in candTokens }

                score += when {
                    normCandArtist == normTargetArtist ||
                        (compactTargetArtist.isNotEmpty() && compactCandArtist == compactTargetArtist) -> 300
                    compactTargetArtist.length >= 3 && compactCandArtist.length >= 3 &&
                        (compactCandArtist.contains(compactTargetArtist) || compactTargetArtist.contains(compactCandArtist)) -> 180
                    overlap > 0 -> (110 * overlap) / targetTokens.size
                    else -> -220
                }
            }
        } else if (normCandArtist in GENERIC_COMPILATION_ARTISTS) {
            score -= 40
        }

        if (isSingleHint) score -= 25
        score += trackCountHint.coerceIn(0, 20)
        return score
    }

    suspend fun searchAlbumsWithSongs(
        albumQuery: String,
        artistHint: String = "",
        maxAlbums: Int = 6
    ): List<AlbumTracklistCandidate> = withContext(Dispatchers.IO) {
        val cleanRaw = albumQuery.trim()
        if (cleanRaw.isEmpty()) return@withContext emptyList()

        val parsed = parseSmartAlbumQuery(cleanRaw, artistHint)
        val combinedTerm = listOf(parsed.album, parsed.artist)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        // Query iTunes & Deezer concurrently so regional/Francophone/Arabic/numeric albums aren't hidden
        val stubs = coroutineScope {
            val itunesCombined = async {
                runCatching { fetchItunesAlbumStubs(combinedTerm, limit = 10, attributeAlbumTerm = false) }
                    .getOrDefault(emptyList())
            }
            val itunesAlbumTerm = async {
                runCatching { fetchItunesAlbumStubs(parsed.album, limit = 10, attributeAlbumTerm = true) }
                    .getOrDefault(emptyList())
            }
            val deezerStructured = async {
                val structuredQ = if (parsed.artist.isNotEmpty()) {
                    "album:\"${parsed.album}\" artist:\"${parsed.artist}\""
                } else {
                    parsed.album
                }
                runCatching { fetchDeezerAlbumStubs(structuredQ, limit = 10) }
                    .getOrDefault(emptyList())
            }
            val deezerBroad = async {
                if (parsed.artist.isNotEmpty()) {
                    runCatching { fetchDeezerAlbumStubs(combinedTerm, limit = 8) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            (itunesCombined.await() + itunesAlbumTerm.await() + deezerStructured.await() + deezerBroad.await())
        }.toMutableList()

        val rankedStubs = rankAndDedupeStubs(stubs, parsed)

        // Fallback to MusicBrainz + iTunes song-to-collection lookup if top results have weak album/artist relevance
        val bestScore = rankedStubs.firstOrNull()?.score ?: Int.MIN_VALUE
        val finalStubs = if (rankedStubs.isEmpty() || (parsed.artist.isNotEmpty() && bestScore < 350)) {
            coroutineScope {
                val mbDeferred = async {
                    runCatching { fetchMusicBrainzAlbumStubs(parsed.album, parsed.artist, limit = 6) }
                        .getOrDefault(emptyList())
                }
                val itunesSongFallbackDeferred = async {
                    runCatching { fetchItunesAlbumsViaSongSearch(combinedTerm, limit = 15) }
                        .getOrDefault(emptyList())
                }
                rankAndDedupeStubs(stubs + mbDeferred.await() + itunesSongFallbackDeferred.await(), parsed)
            }
        } else {
            rankedStubs
        }

        // Fetch full tracklists in parallel only for the top ranked albums
        val topStubs = finalStubs.take((maxAlbums + 2).coerceAtMost(10))
        val hydrated = coroutineScope {
            topStubs.map { stub ->
                async {
                    val tracks = runCatching { stub.fetchTracks() }.getOrDefault(emptyList())
                    if (tracks.isEmpty()) {
                        null
                    } else {
                        stub to AlbumTracklistCandidate(
                            id = stub.id,
                            albumName = stub.albumName,
                            artistName = stub.artistName,
                            trackTitles = tracks,
                            releaseYear = stub.releaseYear,
                            source = stub.source
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Final sort re-checking tracklist size tie-breaker & dedupe by (album, artist)
        hydrated
            .sortedByDescending { (stub, cand) ->
                stub.score + cand.trackTitles.size.coerceAtMost(20)
            }
            .map { it.second }
            .distinctBy {
                "${normalizeMatchKey(it.albumName).replace(" ", "")}|${normalizeMatchKey(it.artistName).replace(" ", "")}"
            }
            .take(maxAlbums)
    }

    private fun rankAndDedupeStubs(
        rawStubs: List<AlbumHeaderStub>,
        parsed: ParsedAlbumQuery
    ): List<AlbumHeaderStub> {
        return rawStubs
            .filter { it.albumName.isNotBlank() }
            .map { stub ->
                val score = scoreAlbumCandidateMatch(
                    candidateAlbum = stub.albumName,
                    candidateArtist = stub.artistName,
                    targetAlbum = parsed.album,
                    targetArtist = parsed.artist,
                    trackCountHint = stub.trackCountHint,
                    isSingleHint = stub.isSingleHint
                )
                stub.copy(score = score)
            }
            .sortedByDescending { it.score }
            .distinctBy {
                "${normalizeMatchKey(it.albumName).replace(" ", "")}|${normalizeMatchKey(it.artistName).replace(" ", "")}"
            }
    }

    private fun fetchItunesAlbumStubs(
        query: String,
        limit: Int,
        attributeAlbumTerm: Boolean
    ): List<AlbumHeaderStub> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val attrParam = if (attributeAlbumTerm) "&attribute=albumTerm" else ""
        val searchUrl = "https://itunes.apple.com/search?term=$encoded&entity=album$attrParam&limit=$limit"
        val rawSearch = httpGetText(searchUrl) ?: return emptyList()
        val arr = JSONObject(rawSearch).optJSONArray("results") ?: return emptyList()

        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val collectionId = item.optLong("collectionId", 0L)
                val albumName = item.optString("collectionName").trim()
                val artistName = item.optString("artistName").trim()
                val trackCount = item.optInt("trackCount", 0)
                val releaseDate = item.optString("releaseDate").take(4)
                if (collectionId <= 0L || albumName.isEmpty()) continue

                add(
                    AlbumHeaderStub(
                        id = "itunes_$collectionId",
                        source = "iTunes",
                        albumName = albumName,
                        artistName = artistName,
                        releaseYear = releaseDate,
                        trackCountHint = trackCount,
                        isSingleHint = trackCount in 1..2 && albumName.contains("Single", ignoreCase = true),
                        score = 0,
                        fetchTracks = { fetchItunesAlbumTracks(collectionId) }
                    )
                )
            }
        }
    }

    private fun fetchItunesAlbumsViaSongSearch(query: String, limit: Int): List<AlbumHeaderStub> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit"
        val rawSearch = httpGetText(searchUrl) ?: return emptyList()
        val arr = JSONObject(rawSearch).optJSONArray("results") ?: return emptyList()

        val seenCollections = HashSet<Long>()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val collectionId = item.optLong("collectionId", 0L)
                val albumName = item.optString("collectionName").trim()
                val artistName = item.optString("artistName").trim()
                val trackCount = item.optInt("trackCount", 0)
                val releaseDate = item.optString("releaseDate").take(4)
                if (collectionId <= 0L || albumName.isEmpty() || !seenCollections.add(collectionId)) continue

                add(
                    AlbumHeaderStub(
                        id = "itunes_$collectionId",
                        source = "iTunes",
                        albumName = albumName,
                        artistName = artistName,
                        releaseYear = releaseDate,
                        trackCountHint = trackCount,
                        isSingleHint = trackCount in 1..2 && albumName.contains("Single", ignoreCase = true),
                        score = 0,
                        fetchTracks = { fetchItunesAlbumTracks(collectionId) }
                    )
                )
            }
        }
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

    private fun fetchDeezerAlbumStubs(query: String, limit: Int): List<AlbumHeaderStub> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://api.deezer.com/search/album?q=$encoded&limit=$limit"
        val rawSearch = httpGetText(searchUrl) ?: return emptyList()
        val arr = JSONObject(rawSearch).optJSONArray("data") ?: return emptyList()

        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val albumId = item.optLong("id", 0L)
                val albumName = item.optString("title").trim()
                val artistName = item.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
                val nbTracks = item.optInt("nb_tracks", 0)
                val recordType = item.optString("record_type").trim().lowercase(Locale.US)
                if (albumId <= 0L || albumName.isEmpty()) continue

                add(
                    AlbumHeaderStub(
                        id = "deezer_$albumId",
                        source = "Deezer",
                        albumName = albumName,
                        artistName = artistName,
                        releaseYear = "",
                        trackCountHint = nbTracks,
                        isSingleHint = recordType == "single",
                        score = 0,
                        fetchTracks = { fetchDeezerAlbumTracks(albumId) }
                    )
                )
            }
        }
    }

    private fun fetchDeezerAlbumTracks(albumId: Long): List<String> {
        val tracksJson = httpGetText("https://api.deezer.com/album/$albumId/tracks?limit=150") ?: return emptyList()
        val tracksArr = JSONObject(tracksJson).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (t in 0 until tracksArr.length()) {
                val tObj = tracksArr.optJSONObject(t) ?: continue
                val title = tObj.optString("title").trim()
                if (title.isNotEmpty()) add(title)
            }
        }.distinct()
    }

    private fun fetchMusicBrainzAlbumStubs(
        album: String,
        artist: String,
        limit: Int
    ): List<AlbumHeaderStub> {
        val luceneQuery = buildString {
            append("release:\"").append(album.replace("\"", "")).append("\"")
            if (artist.isNotBlank()) {
                append(" AND artist:\"").append(artist.replace("\"", "")).append("\"")
            }
        }
        val encoded = URLEncoder.encode(luceneQuery, "UTF-8")
        val url = "https://musicbrainz.org/ws/2/release/?query=$encoded&limit=$limit&fmt=json"
        val raw = httpGetText(url, userAgent = "EchoSnap/1.3.0 (https://echosnap.app)") ?: return emptyList()
        val releases = JSONObject(raw).optJSONArray("releases") ?: return emptyList()

        return buildList {
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                val mbid = rel.optString("id").trim()
                val title = rel.optString("title").trim()
                if (mbid.isEmpty() || title.isEmpty()) continue
                val artistCredit = rel.optJSONArray("artist-credit")
                val artistName = buildString {
                    if (artistCredit != null && artistCredit.length() > 0) {
                        val first = artistCredit.optJSONObject(0)
                        append(first?.optString("name")?.ifBlank { first.optJSONObject("artist")?.optString("name") ?: "" }.orEmpty())
                    }
                }.trim()
                val trackCount = rel.optInt("track-count", 0)
                val date = rel.optString("date").take(4)

                add(
                    AlbumHeaderStub(
                        id = "mb_$mbid",
                        source = "MusicBrainz",
                        albumName = title,
                        artistName = artistName,
                        releaseYear = date,
                        trackCountHint = trackCount,
                        isSingleHint = false,
                        score = 0,
                        fetchTracks = { fetchMusicBrainzTracks(mbid) }
                    )
                )
            }
        }
    }

    private fun fetchMusicBrainzTracks(mbid: String): List<String> {
        val url = "https://musicbrainz.org/ws/2/release/$mbid?inc=recordings&fmt=json"
        val raw = httpGetText(url, userAgent = "EchoSnap/1.3.0 (https://echosnap.app)") ?: return emptyList()
        val mediaArr = JSONObject(raw).optJSONArray("media") ?: return emptyList()
        val titles = mutableListOf<String>()
        for (m in 0 until mediaArr.length()) {
            val tracks = mediaArr.optJSONObject(m)?.optJSONArray("tracks") ?: continue
            for (t in 0 until tracks.length()) {
                val trackTitle = tracks.optJSONObject(t)?.optString("title")?.trim().orEmpty()
                if (trackTitle.isNotEmpty()) titles += trackTitle
            }
        }
        return titles.distinct()
    }

    private fun httpGetText(
        urlString: String,
        userAgent: String = SpotifyAuthManager.DESKTOP_BROWSER_UA
    ): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("User-Agent", userAgent)
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
