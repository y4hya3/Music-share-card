package com.example.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LyricsCandidate(
    val id: String,
    val provider: String,
    val title: String,
    val artist: String,
    val lines: List<String>,
    val isSynced: Boolean = false,
    val matchScore: Int = 0
)

data class LyricsSearchOutcome(
    val candidates: List<LyricsCandidate>,
    val providersQueried: Int,
    val providersMatched: Int,
    val statusMessage: String
)

internal data class ResolvedLyricsQuery(
    val title: String,
    val artist: String,
    val rawQuery: String
)

/**
 * Multi-provider smart lyrics search engine aggregating 17 public lyrics sources concurrently
 * (led by Genius & LRCLIB) with strict artist + title anti-mismatch verification, canonical
 * iTunes/Deezer query disambiguation, and Genius nested-DOM / preloaded-state extraction.
 */
object LyricsSearchService {

    val PROVIDERS: List<String> = listOf(
        "Genius",
        "LRCLIB Synced",
        "LRCLIB Search",
        "LRCLIB Global",
        "Lyrics.ovh",
        "Lyrist",
        "Musixmatch",
        "AZLyrics",
        "ChartLyrics",
        "NetEase Music",
        "Kugou",
        "QQ Music",
        "Lyrics.com",
        "LyricsFreak",
        "Megalobiz",
        "SomeRandomApi"
    )

    private val GENERIC_APP_OR_UNKNOWN_ARTISTS = setOf(
        "spotify", "youtube music", "youtube", "apple music", "echosnap", "bitchord",
        "soundcloud", "music", "music player", "samsung music", "tidal", "deezer",
        "amazon music", "audiomack", "anghami", "unknown", "unknown artist", "<unknown>",
        "various", "various artists", "musicolet", "poweramp", "vlc", "vlc for android",
        "retro music", "oto music", "pulsar", "phonograph", "aimp", "foobar2000",
        "neutron", "blackplayer", "mi music", "lark player", "gaana", "jiosaavn",
        "wynk", "symfonium", "namida", "metrolist", "innertune", "rimusic"
    )

    private val NOISE_LYRICS_PATTERNS = listOf(
        "^\\d+\\s+contributors?.*".toRegex(RegexOption.IGNORE_CASE),
        "^translations?$".toRegex(RegexOption.IGNORE_CASE),
        "^read more$".toRegex(RegexOption.IGNORE_CASE),
        "^you might also like$".toRegex(RegexOption.IGNORE_CASE),
        "^embed$".toRegex(RegexOption.IGNORE_CASE),
        "^see .+ live$".toRegex(RegexOption.IGNORE_CASE),
        "^get tickets as low as.*".toRegex(RegexOption.IGNORE_CASE),
        "^.*lyrics$".toRegex(RegexOption.IGNORE_CASE),
        "^submit corrections.*".toRegex(RegexOption.IGNORE_CASE),
        "^powered by .*".toRegex(RegexOption.IGNORE_CASE),
        "^we are not licensed to display.*".toRegex(RegexOption.IGNORE_CASE),
        "^unfortunately,? we are not authorized.*".toRegex(RegexOption.IGNORE_CASE),
        "^instrumental$".toRegex(RegexOption.IGNORE_CASE)
    )

    suspend fun searchAcrossProviders(
        title: String,
        artist: String
    ): LyricsSearchOutcome = withContext(Dispatchers.IO) {
        val initial = parseSmartQuery(title, artist)
        if (initial.title.isEmpty()) {
            return@withContext LyricsSearchOutcome(
                candidates = emptyList(),
                providersQueried = 0,
                providersMatched = 0,
                statusMessage = "Enter a song title to search lyrics."
            )
        }

        // If artist is missing (e.g. manual "Title Artist" search or "<unknown>" tag) or messy,
        // resolve canonical title + artist via iTunes/Deezer first so exact providers hit cleanly.
        val resolved = resolveCanonicalTrackIfNeeded(initial)
        val searchTitle = resolved.title
        val searchArtist = resolved.artist

        val results = coroutineScope {
            val jobs = listOf<suspend () -> LyricsCandidate?>(
                { queryGenius(searchTitle, searchArtist) },
                { queryLrcLibExact(searchTitle, searchArtist) },
                { queryLrcLibTrackSearch(searchTitle, searchArtist) },
                { queryLrcLibBroad(searchTitle, searchArtist, resolved.rawQuery) },
                { queryLyricsOvh(searchTitle, searchArtist) },
                { queryLyrist(searchTitle, searchArtist) },
                { queryMusixmatch(searchTitle, searchArtist) },
                { queryAzLyrics(searchTitle, searchArtist) },
                { queryChartLyrics(searchTitle, searchArtist) },
                { queryNetEase(searchTitle, searchArtist) },
                { queryKugou(searchTitle, searchArtist) },
                { queryQqMusic(searchTitle, searchArtist) },
                { queryLyricsDotCom(searchTitle, searchArtist) },
                { queryLyricsFreak(searchTitle, searchArtist) },
                { queryMegalobiz(searchTitle, searchArtist) },
                { querySomeRandomApi(searchTitle, searchArtist) }
            )

            jobs.map { fn ->
                async {
                    withTimeoutOrNull(6_500L) {
                        try {
                            fn()
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val verified = results
            .mapNotNull { candidate ->
                if (candidate.lines.size < 3) return@mapNotNull null
                val breakdown = evaluateLyricsMatch(
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                    targetTitle = searchTitle,
                    targetArtist = searchArtist
                )
                if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = searchArtist.isNotEmpty())) {
                    return@mapNotNull null
                }
                candidate.copy(matchScore = breakdown.totalScore)
            }
            .sortedWith(
                compareByDescending<LyricsCandidate> { it.matchScore >= 90 }
                    .thenByDescending { it.matchScore }
                    .thenByDescending { it.provider == "Genius" }
                    .thenByDescending { it.isSynced }
                    .thenByDescending { it.lines.size }
            )

        val matchedCount = verified.map { it.provider }.distinct().size
        val status = if (verified.isEmpty()) {
            "Searched ${PROVIDERS.size} providers (incl. Genius) — no verified match for \"$searchTitle\"${if (searchArtist.isNotEmpty()) " by $searchArtist" else ""}. Try editing query or enter lyrics below."
        } else {
            val top = verified.first()
            "Matched \"${top.title}\" by ${top.artist.ifBlank { searchArtist }} • $matchedCount source${if (matchedCount == 1) "" else "s"} (Genius & ${PROVIDERS.size - 1} others)"
        }

        LyricsSearchOutcome(
            candidates = verified,
            providersQueried = PROVIDERS.size,
            providersMatched = matchedCount,
            statusMessage = status
        )
    }

    /**
     * Parses freeform queries ("Artist - Title", "Title by Artist", filenames, YouTube noise tags)
     * into structured normalized title and artist fields.
     */
    internal fun parseSmartQuery(rawTitle: String, rawArtist: String): ResolvedLyricsQuery {
        val rawCombined = rawTitle.trim()
        var cleanArtist = AnimatedCanvasSearchService.normalizeArtistText(rawArtist)
            .takeIf { it.isNotBlank() && !isGenericArtist(it) }
            .orEmpty()

        var titleCandidate = rawTitle.trim()
            .replace("\\.(?:mp3|flac|m4a|wav|ogg|aac|opus|wma)$".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("^\\d{1,3}\\s*(?:[-–—.)]+)\\s*".toRegex(), "")
            .trim()

        // Split "Artist - Title" when present in track title or manual query
        val dashParts = titleCandidate.split("\\s+[-–—]\\s+".toRegex(), limit = 2)
        if (dashParts.size == 2 && dashParts[0].isNotBlank() && dashParts[1].isNotBlank()) {
            val leftClean = AnimatedCanvasSearchService.normalizeArtistText(dashParts[0])
            val rightClean = AnimatedCanvasSearchService.normalizeQueryText(dashParts[1])
            val leftLooksLikeArtist = leftClean.any { it.isLetter() } && !isGenericArtist(leftClean)
            if (cleanArtist.isEmpty() && leftLooksLikeArtist && rightClean.isNotBlank()) {
                cleanArtist = leftClean
                titleCandidate = dashParts[1]
            } else if (cleanArtist.isNotEmpty() &&
                (dashParts[0].equals(cleanArtist, ignoreCase = true) ||
                    leftClean.equals(cleanArtist, ignoreCase = true))
            ) {
                titleCandidate = dashParts[1]
            } else if (cleanArtist.isNotEmpty() &&
                (dashParts[1].equals(cleanArtist, ignoreCase = true) ||
                    AnimatedCanvasSearchService.normalizeArtistText(dashParts[1]).equals(cleanArtist, ignoreCase = true))
            ) {
                titleCandidate = dashParts[0]
            }
        }

        // Split "Title by Artist" in manual queries when artist is still blank
        if (cleanArtist.isEmpty()) {
            val byMatch = "^(.+?)\\s+\\bby\\b\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
                .find(titleCandidate)
            if (byMatch != null) {
                val left = byMatch.groupValues[1].trim()
                val right = AnimatedCanvasSearchService.normalizeArtistText(byMatch.groupValues[2])
                if (left.isNotEmpty() && right.isNotEmpty() && !isGenericArtist(right)) {
                    titleCandidate = left
                    cleanArtist = right
                }
            }
        }

        val cleanTitle = AnimatedCanvasSearchService.normalizeQueryText(titleCandidate)
            .ifBlank { titleCandidate.trim() }

        return ResolvedLyricsQuery(
            title = cleanTitle,
            artist = cleanArtist,
            rawQuery = listOf(cleanTitle, cleanArtist).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { rawCombined }
        )
    }

    private fun isGenericArtist(artist: String): Boolean {
        val lower = stripDiacritics(artist).trim().lowercase(Locale.US)
        return lower.isEmpty() || lower in GENERIC_APP_OR_UNKNOWN_ARTISTS
    }

    private suspend fun resolveCanonicalTrackIfNeeded(initial: ResolvedLyricsQuery): ResolvedLyricsQuery {
        // Already have a clear title + artist pair
        if (initial.artist.isNotEmpty() && initial.title.split(" ").size <= 6) {
            return initial
        }

        val q = initial.rawQuery.trim()
        if (q.length < 3) return initial

        val canonical = withTimeoutOrNull(2_000L) {
            try {
                queryItunesCanonicalTrack(q, initial.title, initial.artist)
            } catch (_: Exception) {
                null
            }
        }
        return canonical ?: initial
    }

    private fun queryItunesCanonicalTrack(
        query: String,
        fallbackTitle: String,
        fallbackArtist: String
    ): ResolvedLyricsQuery? {
        val body = httpGet("https://itunes.apple.com/search?term=${enc(query)}&media=music&entity=song&limit=6")
            ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        var bestScore = -1
        var bestTitle = ""
        var bestArtist = ""

        val qLower = stripDiacritics(query).lowercase(Locale.US)
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val t = AnimatedCanvasSearchService.normalizeQueryText(item.optString("trackName", ""))
            val a = AnimatedCanvasSearchService.normalizeArtistText(item.optString("artistName", ""))
            if (t.isEmpty() || a.isEmpty()) continue

            var score = 0
            if (fallbackArtist.isNotEmpty()) {
                val b = evaluateLyricsMatch(t, a, fallbackTitle, fallbackArtist)
                if (!isVerifiedLyricsMatch(b, hasTargetArtist = true)) continue
                score = b.totalScore
            } else {
                // Freeform query like "blinding lights the weeknd" — verify BOTH title & artist appear in query
                val tLow = stripDiacritics(t).lowercase(Locale.US)
                val aLow = stripDiacritics(a).lowercase(Locale.US)
                if (qLower.contains(tLow)) score += 55
                if (qLower.contains(aLow)) score += 45
                if (score == 0) {
                    val qTokens = tokenize(qLower)
                    val candTokens = tokenize("$tLow $aLow")
                    val overlap = qTokens.intersect(candTokens).size
                    if (candTokens.isNotEmpty()) {
                        score = (overlap * 90) / max(qTokens.size, candTokens.size)
                    }
                }
            }
            if (score > bestScore && score >= 60) {
                bestScore = score
                bestTitle = t
                bestArtist = a
            }
        }

        if (bestTitle.isNotEmpty() && bestArtist.isNotEmpty()) {
            return ResolvedLyricsQuery(title = bestTitle, artist = bestArtist, rawQuery = "$bestTitle $bestArtist")
        }
        return null
    }

    internal fun evaluateLyricsMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String
    ): MatchScoreBreakdown {
        val cTitle = normalizeForComparison(AnimatedCanvasSearchService.normalizeQueryText(candidateTitle))
            .ifEmpty { normalizeForComparison(candidateTitle) }
        val tTitle = normalizeForComparison(AnimatedCanvasSearchService.normalizeQueryText(targetTitle))
            .ifEmpty { normalizeForComparison(targetTitle) }

        val cArtistRaw = normalizeForComparison(candidateArtist)
        val cArtistPrimary = normalizeForComparison(AnimatedCanvasSearchService.normalizeArtistText(candidateArtist))
        val tArtist = normalizeForComparison(AnimatedCanvasSearchService.normalizeArtistText(targetArtist))
            .ifEmpty { normalizeForComparison(targetArtist) }

        var titleScore = 0
        if (tTitle.isNotEmpty() && cTitle.isNotEmpty()) {
            val tTokens = tokenize(tTitle)
            val cTokens = tokenize(cTitle)
            titleScore = when {
                cTitle == tTitle -> 65
                cTokens.isNotEmpty() && cTokens == tTokens -> 62
                cTokens.isNotEmpty() && tTokens.isNotEmpty() &&
                    (cTitle.startsWith(tTitle) || tTitle.startsWith(cTitle)) -> {
                    val ratio = min(tTokens.size, cTokens.size).toFloat() / max(tTokens.size, cTokens.size).toFloat()
                    (46 + (16 * ratio).toInt()).coerceAtMost(62)
                }
                tTokens.isNotEmpty() && cTokens.isNotEmpty() -> {
                    val overlap = tTokens.intersect(cTokens).size
                    val coverage = overlap.toFloat() / tTokens.size.toFloat()
                    val precision = overlap.toFloat() / cTokens.size.toFloat()
                    when {
                        overlap == 0 -> 0
                        coverage >= 0.99f && precision >= 0.50f -> 56
                        else -> ((coverage * 34f) + (precision * 20f)).toInt()
                    }
                }
                cTitle.contains(tTitle) || tTitle.contains(cTitle) -> 45
                else -> 0
            }
        }

        var artistScore = 0
        if (tArtist.isNotEmpty() && cArtistRaw.isNotEmpty()) {
            val tArtistTokens = tokenize(tArtist)
            val cArtistTokens = tokenize(cArtistRaw)
            val cPrimaryTokens = tokenize(cArtistPrimary)
            artistScore = when {
                cArtistPrimary == tArtist || cArtistRaw == tArtist -> 45
                cPrimaryTokens.isNotEmpty() && cPrimaryTokens == tArtistTokens -> 42
                cArtistRaw.contains(tArtist) || (cArtistPrimary.isNotEmpty() && tArtist.contains(cArtistPrimary)) -> 38
                tArtistTokens.isNotEmpty() && cArtistTokens.isNotEmpty() -> {
                    val overlap = tArtistTokens.intersect(cArtistTokens).size
                    if (overlap > 0) {
                        val coverage = overlap.toFloat() / tArtistTokens.size.toFloat()
                        (coverage * 32f).toInt().coerceAtLeast(1)
                    } else {
                        0
                    }
                }
                else -> 0
            }
        }

        var penalty = 0
        val lowerCandTitle = candidateTitle.lowercase(Locale.US)
        val lowerCandArtist = candidateArtist.lowercase(Locale.US)
        val lowerTargetTitle = targetTitle.lowercase(Locale.US)
        val lowerTargetArtist = targetArtist.lowercase(Locale.US)

        val unwantedTokens = listOf(
            "karaoke" to 55,
            "tribute" to 50,
            "instrumental" to 55,
            "slowed" to 40,
            "nightcore" to 40,
            "sped up" to 35,
            "parody" to 55,
            "cover" to 35,
            "remix" to 30,
            "romaji" to 35,
            "romanized" to 35,
            "translation" to 45,
            "translated" to 45,
            "8d audio" to 40
        )
        for ((tag, weight) in unwantedTokens) {
            if (lowerCandTitle.contains(tag) && !lowerTargetTitle.contains(tag)) penalty += weight
            if (lowerCandArtist.contains(tag) && !lowerTargetArtist.contains(tag)) penalty += weight
        }

        val total = (titleScore + artistScore - penalty).coerceAtLeast(0)
        return MatchScoreBreakdown(totalScore = total, titleScore = titleScore, artistScore = artistScore)
    }

    internal fun isVerifiedLyricsMatch(
        breakdown: MatchScoreBreakdown,
        hasTargetArtist: Boolean
    ): Boolean {
        if (breakdown.titleScore < 35) return false
        if (hasTargetArtist) {
            if (breakdown.artistScore < 20) return false
            return breakdown.totalScore >= 68
        }
        return breakdown.totalScore >= 46
    }

    private fun normalizeForComparison(text: String): String {
        return stripDiacritics(text)
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun tokenize(text: String): Set<String> {
        return text.split(" ")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun stripDiacritics(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(normalized, "")
    }

    internal fun parseCleanLines(rawText: String?): List<String> {
        if (rawText.isNullOrBlank()) return emptyList()
        val unescaped = rawText
            .replace("\\u003c", "<", ignoreCase = true)
            .replace("\\u003e", ">", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace("</(?:p|div|li|h[1-6])>".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace("<[^>]+>".toRegex(), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        return unescaped
            .lines()
            .map { line ->
                // Strip LRC timestamps [00:12.34] or metadata headers [ar:...]
                val noStamp = line.replace("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\]".toRegex(), "").trim()
                if (noStamp.matches("^\\[(?:ti|ar|al|by|length|offset):.*\\]$".toRegex(RegexOption.IGNORE_CASE))) {
                    ""
                } else {
                    noStamp.replace("\\s+".toRegex(), " ").trim()
                }
            }
            .filter { line ->
                line.isNotEmpty() && NOISE_LYRICS_PATTERNS.none { it.matches(line) }
            }
            .distinctLinesAdjacent()
            .take(180)
    }

    /**
     * Extracts full multi-section Genius lyrics safely even when `<div data-lyrics-container="true">`
     * contains nested `<div>` wrappers, Contributor/Translation header widgets, or buttons.
     */
    internal fun extractGeniusLyricsFromHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()

        // Strip non-div noise first so tag balance stays intact
        val preCleaned = html
            .replace("<script[\\s\\S]*?</script>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<style[\\s\\S]*?</style>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<svg[\\s\\S]*?</svg>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<button[\\s\\S]*?</button>".toRegex(RegexOption.IGNORE_CASE), "")

        // Remove Genius contributor / translation widgets using balanced <div> tracking
        // so inner <div> wrappers never corrupt outer data-lyrics-container depth.
        val sanitized = stripBalancedDivBlocks(
            html = preCleaned,
            startPattern = "<div[^>]*data-exclude-from-selection=[\"']true[\"'][^>]*>".toRegex(RegexOption.IGNORE_CASE)
        )

        val containers = extractBalancedDivBodies(
            html = sanitized,
            startPattern = "<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>".toRegex(RegexOption.IGNORE_CASE)
        )

        val rawExtracted = if (containers.isNotEmpty()) {
            containers.joinToString("\n")
        } else {
            extractGeniusPreloadedLyrics(html).orEmpty()
        }

        if (rawExtracted.isBlank()) return emptyList()

        return parseCleanLines(rawExtracted)
            .filterNot { line ->
                // Strip [Verse 1], [Chorus], [Intro: Artist] structural brackets from story card lines
                line.startsWith("[") && line.endsWith("]")
            }
    }

    private fun stripBalancedDivBlocks(html: String, startPattern: Regex): String {
        val divOpenRegex = "<div\\b".toRegex(RegexOption.IGNORE_CASE)
        val divCloseRegex = "</div\\s*>".toRegex(RegexOption.IGNORE_CASE)
        val out = StringBuilder(html.length)
        var copyFrom = 0

        while (copyFrom < html.length) {
            val startMatch = startPattern.find(html, copyFrom)
            if (startMatch == null) {
                out.append(html, copyFrom, html.length)
                break
            }
            out.append(html, copyFrom, startMatch.range.first)

            var depth = 1
            var cursor = startMatch.range.last + 1
            while (depth > 0 && cursor < html.length) {
                val nextOpen = divOpenRegex.find(html, cursor)
                val nextClose = divCloseRegex.find(html, cursor) ?: break
                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    depth++
                    cursor = nextOpen.range.last + 1
                } else {
                    depth--
                    cursor = nextClose.range.last + 1
                }
            }
            copyFrom = cursor.coerceAtLeast(startMatch.range.last + 1)
        }
        return out.toString()
    }

    private fun extractBalancedDivBodies(html: String, startPattern: Regex): List<String> {
        val bodies = ArrayList<String>()
        val divOpenRegex = "<div\\b".toRegex(RegexOption.IGNORE_CASE)
        val divCloseRegex = "</div\\s*>".toRegex(RegexOption.IGNORE_CASE)

        var searchFrom = 0
        while (searchFrom < html.length) {
            val startMatch = startPattern.find(html, searchFrom) ?: break
            val contentStart = startMatch.range.last + 1
            var depth = 1
            var cursor = contentStart

            while (depth > 0 && cursor < html.length) {
                val nextOpen = divOpenRegex.find(html, cursor)
                val nextClose = divCloseRegex.find(html, cursor) ?: break

                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    depth++
                    cursor = nextOpen.range.last + 1
                } else {
                    depth--
                    if (depth == 0) {
                        bodies.add(html.substring(contentStart, nextClose.range.first))
                    }
                    cursor = nextClose.range.last + 1
                }
            }
            searchFrom = cursor.coerceAtLeast(startMatch.range.last + 1)
        }
        return bodies
    }

    private fun extractGeniusPreloadedLyrics(html: String): String? {
        val bodyHtmlMatch = "\"lyricsData\"\\s*:\\s*\\{[\\s\\S]{0,1200}?\"html\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
            .toRegex()
            .find(html)
            ?: return null
        val escaped = bodyHtmlMatch.groupValues[1]
        return escaped
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\n", "\n")
            .replace("\\n", "\n")
    }

    private fun extractHtmlTitleAndMeta(html: String): String {
        val ogTitle = "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"
            .toRegex(RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!ogTitle.isNullOrBlank()) return ogTitle
        return "<title[^>]*>([^<]+)</title>"
            .toRegex(RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            .orEmpty()
    }

    private fun parseHtmlPageTitlePair(rawPageTitle: String, fallbackTitle: String, fallbackArtist: String): Pair<String, String> {
        val cleaned = rawPageTitle
            .replace("\\s*[-|]\\s*(?:Lyrics|AZLyrics|Genius|Musixmatch|Lyrics\\.com|LyricsFreak).*".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s+Lyrics$".toRegex(RegexOption.IGNORE_CASE), "")
            .trim()
        val dashSplit = cleaned.split("\\s+[–—-]\\s+".toRegex(), limit = 2)
        if (dashSplit.size == 2 && dashSplit[0].isNotBlank() && dashSplit[1].isNotBlank()) {
            // Genius/AZLyrics use "Artist – Song"
            val p0 = dashSplit[0].trim()
            val p1 = dashSplit[1].trim()
            val p0MatchesArtist = fallbackArtist.isNotEmpty() &&
                evaluateLyricsMatch(fallbackTitle, p0, fallbackTitle, fallbackArtist).artistScore >= 20
            val p1MatchesArtist = fallbackArtist.isNotEmpty() &&
                evaluateLyricsMatch(fallbackTitle, p1, fallbackTitle, fallbackArtist).artistScore >= 20
            return when {
                p1MatchesArtist && !p0MatchesArtist -> p0 to p1
                else -> p1 to p0
            }
        }
        return cleaned.ifBlank { fallbackTitle } to fallbackArtist
    }

    private fun toGeniusSlug(value: String): String {
        return stripDiacritics(value)
            .replace("&", " and ")
            .replace("['’]".toRegex(), "")
            .replace("[^A-Za-z0-9]+".toRegex(), "-")
            .trim('-')
    }

    private fun List<String>.distinctLinesAdjacent(): List<String> {
        val out = ArrayList<String>(size)
        for (line in this) {
            if (out.isEmpty() || out.last() != line) {
                out.add(line)
            }
        }
        return out
    }

    // ==================== 1. GENIUS (SEARCH API + NESTED DOM + CANONICAL SLUG) ====================

    private fun queryGenius(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val collectedHits = ArrayList<GeniusHit>()

        // Try both Genius multi-search and dedicated song-search APIs
        listOf(
            "https://genius.com/api/search/multi?per_page=8&q=${enc(q)}",
            "https://genius.com/api/search/song?per_page=8&q=${enc(q)}"
        ).forEach { endpoint ->
            if (collectedHits.size >= 8) return@forEach
            val body = httpGet(
                endpoint,
                extraHeaders = mapOf(
                    "Referer" to "https://genius.com/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ) ?: return@forEach
            val response = try {
                JSONObject(body).optJSONObject("response")
            } catch (_: Exception) {
                null
            } ?: return@forEach

            val sections = response.optJSONArray("sections")
            if (sections != null) {
                for (s in 0 until sections.length()) {
                    val sec = sections.optJSONObject(s) ?: continue
                    val secType = sec.optString("type", "")
                    if (secType.isNotEmpty() && secType != "song" && secType != "top_hit") continue
                    val hits = sec.optJSONArray("hits") ?: continue
                    collectGeniusHits(hits, collectedHits)
                }
            }
            val directHits = response.optJSONArray("hits")
            if (directHits != null) {
                collectGeniusHits(directHits, collectedHits)
            }
        }

        // Rank ALL hits strictly and take best verified song match
        val bestHits = collectedHits
            .distinctBy { it.url }
            .mapNotNull { hit ->
                val breakdown = evaluateLyricsMatch(hit.title, hit.artist, title, artist)
                if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) {
                    return@mapNotNull null
                }
                hit to breakdown.totalScore
            }
            .sortedByDescending { it.second }
            .take(3)

        for ((hit, score) in bestHits) {
            val html = httpGet(
                hit.url,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                extraHeaders = mapOf("Referer" to "https://genius.com/")
            ) ?: continue
            val lines = extractGeniusLyricsFromHtml(html)
            if (lines.size >= 3) {
                return LyricsCandidate(
                    id = "genius_${hit.url.hashCode()}",
                    provider = "Genius",
                    title = hit.title,
                    artist = hit.artist,
                    lines = lines,
                    matchScore = score
                )
            }
        }

        // Fallback to direct Genius canonical slug URL when search endpoints throttle
        if (artist.isNotEmpty()) {
            val slug = toGeniusSlug("$artist $title")
            if (slug.length >= 3) {
                val slugUrl = "https://genius.com/$slug-lyrics"
                val html = httpGet(
                    slugUrl,
                    accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    extraHeaders = mapOf("Referer" to "https://genius.com/")
                )
                if (!html.isNullOrBlank()) {
                    val (pageTitle, pageArtist) = parseHtmlPageTitlePair(
                        extractHtmlTitleAndMeta(html),
                        fallbackTitle = title,
                        fallbackArtist = artist
                    )
                    val breakdown = evaluateLyricsMatch(pageTitle, pageArtist, title, artist)
                    if (isVerifiedLyricsMatch(breakdown, hasTargetArtist = true)) {
                        val lines = extractGeniusLyricsFromHtml(html)
                        if (lines.size >= 3) {
                            return LyricsCandidate(
                                id = "genius_slug",
                                provider = "Genius",
                                title = pageTitle,
                                artist = pageArtist,
                                lines = lines,
                                matchScore = breakdown.totalScore
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private data class GeniusHit(val title: String, val artist: String, val url: String)

    private fun collectGeniusHits(hits: JSONArray, out: MutableList<GeniusHit>) {
        for (h in 0 until hits.length()) {
            val hitObj = hits.optJSONObject(h) ?: continue
            val type = hitObj.optString("type", "song")
            if (type.isNotEmpty() && type != "song") continue
            val res = hitObj.optJSONObject("result") ?: continue
            if (res.optBoolean("instrumental", false)) continue
            val u = res.optString("url", "")
            if (!u.startsWith("https://genius.com/") || !u.endsWith("-lyrics")) continue
            if (u.contains("-tracklist-") || u.contains("-annotated")) continue
            val gt = res.optString("title", "").ifBlank { res.optString("title_with_featured", "") }
            val ga = res.optJSONObject("primary_artist")?.optString("name", "")
                ?.ifBlank { res.optString("artist_names", "") }
                ?: res.optString("artist_names", "")
            if (gt.isNotBlank()) {
                out.add(GeniusHit(gt, ga, u))
            }
        }
    }

    // ==================== 2-4. LRCLIB (EXACT, STRUCTURED SEARCH, GLOBAL) ====================

    private fun queryLrcLibExact(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val body = httpGet(url) ?: return null
        val obj = JSONObject(body)
        if (obj.optBoolean("instrumental", false)) return null
        val matchedTitle = obj.optString("trackName", title).ifBlank { title }
        val matchedArtist = obj.optString("artistName", artist).ifBlank { artist }
        val breakdown = evaluateLyricsMatch(matchedTitle, matchedArtist, title, artist)
        if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = true)) return null

        val synced = obj.optString("syncedLyrics", "")
        val raw = synced.ifBlank { obj.optString("plainLyrics", "") }
        val lines = parseCleanLines(raw)
        if (lines.size < 3) return null
        return LyricsCandidate(
            id = "lrclib_exact",
            provider = "LRCLIB Synced",
            title = matchedTitle,
            artist = matchedArtist,
            lines = lines,
            isSynced = synced.isNotBlank(),
            matchScore = breakdown.totalScore
        )
    }

    private fun queryLrcLibTrackSearch(title: String, artist: String): LyricsCandidate? {
        val url = if (artist.isNotEmpty()) {
            "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        } else {
            "https://lrclib.net/api/search?track_name=${enc(title)}"
        }
        val body = httpGet(url) ?: return null
        val arr = JSONArray(body)
        return pickBestLrcLibCandidate(arr, title, artist, providerLabel = "LRCLIB Search", idPrefix = "lrclib_search")
    }

    private fun queryLrcLibBroad(title: String, artist: String, rawQuery: String): LyricsCandidate? {
        val q = "$title $artist".trim().ifEmpty { rawQuery }
        val body = httpGet("https://lrclib.net/api/search?q=${enc(q)}") ?: return null
        val arr = JSONArray(body)
        return pickBestLrcLibCandidate(arr, title, artist, providerLabel = "LRCLIB Global", idPrefix = "lrclib_broad")
    }

    private fun pickBestLrcLibCandidate(
        arr: JSONArray,
        targetTitle: String,
        targetArtist: String,
        providerLabel: String,
        idPrefix: String
    ): LyricsCandidate? {
        var best: LyricsCandidate? = null
        val hasArtist = targetArtist.isNotEmpty()

        for (i in 0 until min(arr.length(), 10)) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("instrumental", false)) continue
            val t = obj.optString("trackName", "").ifBlank { continue }
            val a = obj.optString("artistName", "")
            val breakdown = evaluateLyricsMatch(t, a, targetTitle, targetArtist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = hasArtist)) continue

            val synced = obj.optString("syncedLyrics", "")
            val raw = if (synced.isNotBlank()) synced else obj.optString("plainLyrics", "")
            val lines = parseCleanLines(raw)
            if (lines.size < 3) continue

            val candidate = LyricsCandidate(
                id = "${idPrefix}_$i",
                provider = providerLabel,
                title = t,
                artist = a,
                lines = lines,
                isSynced = synced.isNotBlank(),
                matchScore = breakdown.totalScore
            )
            if (best == null ||
                candidate.matchScore > best.matchScore ||
                (candidate.matchScore == best.matchScore && candidate.isSynced && !best.isSynced)
            ) {
                best = candidate
            }
        }
        return best
    }

    // ==================== 5-16. VERIFIED ADDITIONAL PROVIDERS ====================

    private fun queryLyricsOvh(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val url = "https://api.lyrics.ovh/v1/${encPath(artist)}/${encPath(title)}"
        val body = httpGet(url) ?: return null
        val raw = JSONObject(body).optString("lyrics", "")
        val lines = parseCleanLines(raw)
        if (lines.size < 3) return null
        return LyricsCandidate("lyrics_ovh", "Lyrics.ovh", title, artist, lines)
    }

    private fun queryLyrist(title: String, artist: String): LyricsCandidate? {
        val url = if (artist.isNotEmpty()) {
            "https://lyrist.vercel.app/api/${encPath(title)}/${encPath(artist)}"
        } else {
            "https://lyrist.vercel.app/api/${encPath(title)}"
        }
        val body = httpGet(url) ?: return null
        val obj = JSONObject(body)
        val matchedTitle = obj.optString("title", "").ifBlank { return null }
        val matchedArtist = obj.optString("artist", "")
        val breakdown = evaluateLyricsMatch(matchedTitle, matchedArtist, title, artist)
        if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) return null

        val lines = parseCleanLines(obj.optString("lyrics", ""))
        if (lines.size < 3) return null
        return LyricsCandidate(
            id = "lyrist",
            provider = "Lyrist",
            title = matchedTitle,
            artist = matchedArtist,
            lines = lines,
            matchScore = breakdown.totalScore
        )
    }

    private fun queryMusixmatch(title: String, artist: String): LyricsCandidate? {
        val q = "$artist $title".trim()
        val searchHtml = httpGet("https://www.musixmatch.com/search/${encPath(q)}", accept = "text/html") ?: return null
        val links = """href="(/lyrics/[^"]+)"""".toRegex()
            .findAll(searchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .toList()

        for (trackPath in links) {
            // Quick URL slug pre-check before downloading page
            val slugReadable = trackPath.removePrefix("/lyrics/").replace("/", " ").replace("-", " ")
            if (artist.isNotEmpty() && evaluateLyricsMatch(slugReadable, slugReadable, title, artist).artistScore == 0) {
                continue
            }
            val pageHtml = httpGet("https://www.musixmatch.com$trackPath", accept = "text/html") ?: continue
            val (pageTitle, pageArtist) = parseHtmlPageTitlePair(extractHtmlTitleAndMeta(pageHtml), title, artist)
            val breakdown = evaluateLyricsMatch(pageTitle, pageArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue

            val parts = """class="lyrics__content__ok"[^>]*>([\s\S]*?)</span>""".toRegex()
                .findAll(pageHtml)
                .map { it.groupValues[1] }
                .joinToString("\n")
            val lines = parseCleanLines(parts)
            if (lines.size >= 3) {
                return LyricsCandidate(
                    id = "musixmatch",
                    provider = "Musixmatch",
                    title = pageTitle,
                    artist = pageArtist,
                    lines = lines,
                    matchScore = breakdown.totalScore
                )
            }
        }
        return null
    }

    private fun queryAzLyrics(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val slugArtist = stripDiacritics(artist).lowercase(Locale.US).replace("[^a-z0-9]".toRegex(), "").removePrefix("the")
        val slugTitle = stripDiacritics(title).lowercase(Locale.US).replace("[^a-z0-9]".toRegex(), "")
        if (slugArtist.isEmpty() || slugTitle.isEmpty()) return null
        val html = httpGet("https://www.azlyrics.com/lyrics/$slugArtist/$slugTitle.html", accept = "text/html")
            ?: return null
        val match = "Usage of azlyrics\\.com content by any third-party lyrics provider is prohibited by our licensing agreement\\. Sorry about that\\.\\s*-->([\\s\\S]*?)</div>"
            .toRegex()
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val lines = parseCleanLines(match)
        if (lines.size < 3) return null
        return LyricsCandidate("azlyrics", "AZLyrics", title, artist, lines)
    }

    private fun queryChartLyrics(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val xml = httpGet(
            "http://api.chartlyrics.com/apiv1.asmx/SearchLyricDirect?artist=${enc(artist)}&song=${enc(title)}",
            accept = "application/xml,text/xml"
        ) ?: return null
        val matchedSong = "<LyricSong>([\\s\\S]*?)</LyricSong>".toRegex().find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val matchedArtist = "<LyricArtist>([\\s\\S]*?)</LyricArtist>".toRegex().find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (matchedSong.isNotEmpty() || matchedArtist.isNotEmpty()) {
            val breakdown = evaluateLyricsMatch(matchedSong.ifEmpty { title }, matchedArtist.ifEmpty { artist }, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = true)) return null
        }
        val raw = "<Lyric>([\\s\\S]*?)</Lyric>".toRegex().find(xml)?.groupValues?.getOrNull(1)
        val lines = parseCleanLines(raw)
        if (lines.size < 3) return null
        return LyricsCandidate(
            id = "chartlyrics",
            provider = "ChartLyrics",
            title = matchedSong.ifEmpty { title },
            artist = matchedArtist.ifEmpty { artist },
            lines = lines
        )
    }

    private fun queryNetEase(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val search = httpGet(
            "https://music.163.com/api/search/get/web?s=${enc(q)}&type=1&offset=0&limit=6",
            extraHeaders = mapOf("Referer" to "https://music.163.com/")
        ) ?: return null
        val songs = JSONObject(search).optJSONObject("result")?.optJSONArray("songs") ?: return null

        var bestId = 0L
        var bestTitle = ""
        var bestArtist = ""
        var bestScore = -1
        for (i in 0 until min(songs.length(), 6)) {
            val songObj = songs.optJSONObject(i) ?: continue
            val sTitle = songObj.optString("name", "")
            val arArr = songObj.optJSONArray("artists") ?: songObj.optJSONArray("ar")
            val sArtist = buildString {
                if (arArr != null) {
                    for (a in 0 until arArr.length()) {
                        val name = arArr.optJSONObject(a)?.optString("name", "").orEmpty()
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(name)
                        }
                    }
                }
            }
            val breakdown = evaluateLyricsMatch(sTitle, sArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            if (breakdown.totalScore > bestScore) {
                bestScore = breakdown.totalScore
                bestId = songObj.optLong("id", 0L)
                bestTitle = sTitle
                bestArtist = sArtist
            }
        }
        if (bestId <= 0L) return null

        val lyricJson = httpGet(
            "https://music.163.com/api/song/lyric?os=pc&id=$bestId&lv=1&tv=-1",
            extraHeaders = mapOf("Referer" to "https://music.163.com/")
        ) ?: return null
        val raw = JSONObject(lyricJson).optJSONObject("lrc")?.optString("lyric", "")
        val lines = parseCleanLines(raw)
        if (lines.size < 3) return null
        return LyricsCandidate(
            id = "netease_$bestId",
            provider = "NetEase Music",
            title = bestTitle,
            artist = bestArtist,
            lines = lines,
            isSynced = true,
            matchScore = bestScore
        )
    }

    private fun queryKugou(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val res = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(q)}&duration=&hash=")
            ?: return null
        val arr = JSONObject(res).optJSONArray("candidates") ?: return null
        var bestId = ""
        var bestKey = ""
        var bestTitle = ""
        var bestArtist = ""
        var bestScore = -1
        for (i in 0 until min(arr.length(), 6)) {
            val cand = arr.optJSONObject(i) ?: continue
            val cTitle = cand.optString("song", "").ifBlank { cand.optString("songname", "") }
            val cArtist = cand.optString("singer", "")
            val breakdown = evaluateLyricsMatch(cTitle, cArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            if (breakdown.totalScore > bestScore) {
                bestScore = breakdown.totalScore
                bestId = cand.optString("id", "")
                bestKey = cand.optString("accesskey", "")
                bestTitle = cTitle
                bestArtist = cArtist
            }
        }
        if (bestId.isEmpty() || bestKey.isEmpty()) return null

        val dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=$bestId&accesskey=$bestKey&fmt=lrc&charset=utf8")
            ?: return null
        val contentB64 = JSONObject(dl).optString("content", "")
        if (contentB64.isBlank()) return null
        val decoded = String(android.util.Base64.decode(contentB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val lines = parseCleanLines(decoded)
        if (lines.size < 3) return null
        return LyricsCandidate("kugou_$bestId", "Kugou", bestTitle, bestArtist, lines, isSynced = true, matchScore = bestScore)
    }

    private fun queryQqMusic(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val body = httpGet(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=6&w=${enc(q)}&format=json",
            extraHeaders = mapOf("Referer" to "https://y.qq.com/")
        ) ?: return null
        val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        var bestMid = ""
        var bestTitle = ""
        var bestArtist = ""
        var bestScore = -1
        for (i in 0 until min(list.length(), 6)) {
            val song = list.optJSONObject(i) ?: continue
            val sTitle = song.optString("songname", "")
            val singerArr = song.optJSONArray("singer")
            val sArtist = buildString {
                if (singerArr != null) {
                    for (a in 0 until singerArr.length()) {
                        val name = singerArr.optJSONObject(a)?.optString("name", "").orEmpty()
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(name)
                        }
                    }
                }
            }
            val breakdown = evaluateLyricsMatch(sTitle, sArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            if (breakdown.totalScore > bestScore) {
                bestScore = breakdown.totalScore
                bestMid = song.optString("songmid", "")
                bestTitle = sTitle
                bestArtist = sArtist
            }
        }
        if (bestMid.isBlank()) return null

        val lyrBody = httpGet(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$bestMid&format=json&nobase64=1",
            extraHeaders = mapOf("Referer" to "https://y.qq.com/")
        ) ?: return null
        val raw = JSONObject(lyrBody).optString("lyric", "")
        val lines = parseCleanLines(raw)
        if (lines.size < 3) return null
        return LyricsCandidate("qq_$bestMid", "QQ Music", bestTitle, bestArtist, lines, isSynced = true, matchScore = bestScore)
    }

    private fun queryLyricsDotCom(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val searchHtml = httpGet("https://www.lyrics.com/lyrics/${encPath(q)}", accept = "text/html") ?: return null
        val matches = """href="(/lyric/\d+/[^"]+)"""".toRegex()
            .findAll(searchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(4)
            .toList()
        for (matchPath in matches) {
            val pageHtml = httpGet("https://www.lyrics.com$matchPath", accept = "text/html") ?: continue
            val (pageTitle, pageArtist) = parseHtmlPageTitlePair(extractHtmlTitleAndMeta(pageHtml), title, artist)
            val breakdown = evaluateLyricsMatch(pageTitle, pageArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            val body = """id="lyric-body-text"[^>]*>([\s\S]*?)</pre>""".toRegex()
                .find(pageHtml)?.groupValues?.getOrNull(1) ?: continue
            val lines = parseCleanLines(body)
            if (lines.size >= 3) {
                return LyricsCandidate("lyrics_com", "Lyrics.com", pageTitle, pageArtist, lines, matchScore = breakdown.totalScore)
            }
        }
        return null
    }

    private fun queryLyricsFreak(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val searchHtml = httpGet("https://www.lyricsfreak.com/search.php?q=${enc(q)}", accept = "text/html")
            ?: return null
        val links = """href="(https?://www\.lyricsfreak\.com/[^"]+\d+\.html|/[^"]+\d+\.html)""""
            .toRegex()
            .findAll(searchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(4)
            .toList()
        for (songPath in links) {
            val fullUrl = if (songPath.startsWith("http")) songPath else "https://www.lyricsfreak.com$songPath"
            val pageHtml = httpGet(fullUrl, accept = "text/html") ?: continue
            val (pageTitle, pageArtist) = parseHtmlPageTitlePair(extractHtmlTitleAndMeta(pageHtml), title, artist)
            val breakdown = evaluateLyricsMatch(pageTitle, pageArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            val body = """id="content"[^>]*>([\s\S]*?)</div>""".toRegex()
                .find(pageHtml)?.groupValues?.getOrNull(1) ?: continue
            val lines = parseCleanLines(body)
            if (lines.size >= 3) {
                return LyricsCandidate("lyricsfreak", "LyricsFreak", pageTitle, pageArtist, lines, matchScore = breakdown.totalScore)
            }
        }
        return null
    }

    private fun queryMegalobiz(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val html = httpGet("https://www.megalobiz.com/search/all?qry=${enc(q)}", accept = "text/html") ?: return null
        val links = """href="(/lrc/maker/[^"]+)"""".toRegex()
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(4)
            .toList()
        for (lrcPath in links) {
            val pageHtml = httpGet("https://www.megalobiz.com$lrcPath", accept = "text/html") ?: continue
            val (pageTitle, pageArtist) = parseHtmlPageTitlePair(extractHtmlTitleAndMeta(pageHtml), title, artist)
            val breakdown = evaluateLyricsMatch(pageTitle, pageArtist, title, artist)
            if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) continue
            val body = """id="lrc_[^"]*details"[^>]*>([\s\S]*?)</span>""".toRegex()
                .find(pageHtml)?.groupValues?.getOrNull(1) ?: continue
            val lines = parseCleanLines(body)
            if (lines.size >= 3) {
                return LyricsCandidate("megalobiz", "Megalobiz", pageTitle, pageArtist, lines, isSynced = true, matchScore = breakdown.totalScore)
            }
        }
        return null
    }

    private fun querySomeRandomApi(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val body = httpGet("https://some-random-api.com/lyrics?title=${enc(q)}") ?: return null
        val obj = JSONObject(body)
        val matchedTitle = obj.optString("title", "").ifBlank { return null }
        val matchedArtist = obj.optString("author", "")
        val breakdown = evaluateLyricsMatch(matchedTitle, matchedArtist, title, artist)
        if (!isVerifiedLyricsMatch(breakdown, hasTargetArtist = artist.isNotEmpty())) return null

        val lines = parseCleanLines(obj.optString("lyrics", ""))
        if (lines.size < 3) return null
        return LyricsCandidate(
            id = "somerandomapi",
            provider = "SomeRandomApi",
            title = matchedTitle,
            artist = matchedArtist,
            lines = lines,
            matchScore = breakdown.totalScore
        )
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encPath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun httpGet(
        urlString: String,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 3) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
}
