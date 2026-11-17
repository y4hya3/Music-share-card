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
import java.util.Locale

data class LyricsCandidate(
    val id: String,
    val provider: String,
    val title: String,
    val artist: String,
    val lines: List<String>,
    val isSynced: Boolean = false
)

data class LyricsSearchOutcome(
    val candidates: List<LyricsCandidate>,
    val providersQueried: Int,
    val providersMatched: Int,
    val statusMessage: String
)

/**
 * Multi-provider lyrics search engine aggregating 16 public lyrics sources concurrently
 * so users can auto-search song lyrics and pick exact lines/words for the Lyrics Card.
 */
object LyricsSearchService {

    val PROVIDERS: List<String> = listOf(
        "LRCLIB Synced",
        "LRCLIB Search",
        "LRCLIB Global",
        "Lyrics.ovh",
        "Lyrist",
        "Genius",
        "ChartLyrics",
        "NetEase Music",
        "Kugou",
        "QQ Music",
        "AZLyrics",
        "Musixmatch",
        "Lyrics.com",
        "LyricsFreak",
        "Megalobiz",
        "SomeRandomApi"
    )

    suspend fun searchAcrossProviders(
        title: String,
        artist: String
    ): LyricsSearchOutcome = withContext(Dispatchers.IO) {
        val cleanTitle = AnimatedCanvasSearchService.normalizeQueryText(title)
            .ifBlank { title.trim() }
        val cleanArtist = AnimatedCanvasSearchService.normalizeArtistText(artist)
            .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .orEmpty()

        if (cleanTitle.isEmpty()) {
            return@withContext LyricsSearchOutcome(
                candidates = emptyList(),
                providersQueried = 0,
                providersMatched = 0,
                statusMessage = "Enter a song title to search lyrics."
            )
        }

        val results = coroutineScope {
            val jobs = listOf<suspend () -> LyricsCandidate?>(
                { queryLrcLibExact(cleanTitle, cleanArtist) },
                { queryLrcLibTrackSearch(cleanTitle, cleanArtist) },
                { queryLrcLibBroad(cleanTitle, cleanArtist) },
                { queryLyricsOvh(cleanTitle, cleanArtist) },
                { queryLyrist(cleanTitle, cleanArtist) },
                { queryGenius(cleanTitle, cleanArtist) },
                { queryChartLyrics(cleanTitle, cleanArtist) },
                { queryNetEase(cleanTitle, cleanArtist) },
                { queryKugou(cleanTitle, cleanArtist) },
                { queryQqMusic(cleanTitle, cleanArtist) },
                { queryAzLyrics(cleanTitle, cleanArtist) },
                { queryMusixmatch(cleanTitle, cleanArtist) },
                { queryLyricsDotCom(cleanTitle, cleanArtist) },
                { queryLyricsFreak(cleanTitle, cleanArtist) },
                { queryMegalobiz(cleanTitle, cleanArtist) },
                { querySomeRandomApi(cleanTitle, cleanArtist) }
            )

            jobs.map { fn ->
                async {
                    withTimeoutOrNull(6_000L) {
                        try {
                            fn()
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val sorted = results
            .filter { it.lines.isNotEmpty() }
            .sortedWith(
                compareByDescending<LyricsCandidate> {
                    AnimatedCanvasSearchService.scoreMatch(it.title, it.artist, cleanTitle, cleanArtist)
                }.thenByDescending { it.isSynced }.thenByDescending { it.lines.size }
            )

        val matchedCount = sorted.map { it.provider }.distinct().size
        val status = if (sorted.isEmpty()) {
            "Searched ${PROVIDERS.size} providers — no lyrics matched \"$cleanTitle\". Edit manually below."
        } else {
            "Searched ${PROVIDERS.size} providers • Matched $matchedCount source${if (matchedCount == 1) "" else "s"}"
        }

        LyricsSearchOutcome(
            candidates = sorted,
            providersQueried = PROVIDERS.size,
            providersMatched = matchedCount,
            statusMessage = status
        )
    }

    internal fun parseCleanLines(rawText: String?): List<String> {
        if (rawText.isNullOrBlank()) return emptyList()
        val unescaped = rawText
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace("<[^>]+>".toRegex(), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
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
            .filter { it.isNotEmpty() }
            .distinctLinesAdjacent()
            .take(180)
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

    private fun queryLrcLibExact(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val body = httpGet(url) ?: return null
        val obj = JSONObject(body)
        val raw = obj.optString("syncedLyrics", "").ifBlank { obj.optString("plainLyrics", "") }
        val lines = parseCleanLines(raw)
        if (lines.isEmpty()) return null
        return LyricsCandidate(
            id = "lrclib_exact",
            provider = "LRCLIB Synced",
            title = obj.optString("trackName", title),
            artist = obj.optString("artistName", artist),
            lines = lines,
            isSynced = obj.optString("syncedLyrics", "").isNotBlank()
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
        for (i in 0 until minOf(arr.length(), 4)) {
            val obj = arr.optJSONObject(i) ?: continue
            val raw = obj.optString("plainLyrics", "").ifBlank { obj.optString("syncedLyrics", "") }
            val lines = parseCleanLines(raw)
            if (lines.isNotEmpty()) {
                return LyricsCandidate(
                    id = "lrclib_search_$i",
                    provider = "LRCLIB Search",
                    title = obj.optString("trackName", title),
                    artist = obj.optString("artistName", artist),
                    lines = lines,
                    isSynced = obj.optString("syncedLyrics", "").isNotBlank()
                )
            }
        }
        return null
    }

    private fun queryLrcLibBroad(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val body = httpGet("https://lrclib.net/api/search?q=${enc(q)}") ?: return null
        val arr = JSONArray(body)
        for (i in 0 until minOf(arr.length(), 5)) {
            val obj = arr.optJSONObject(i) ?: continue
            val t = obj.optString("trackName", "")
            val a = obj.optString("artistName", "")
            if (AnimatedCanvasSearchService.evaluateMatch(t, a, title, artist).titleScore < 20) continue
            val raw = obj.optString("plainLyrics", "").ifBlank { obj.optString("syncedLyrics", "") }
            val lines = parseCleanLines(raw)
            if (lines.isNotEmpty()) {
                return LyricsCandidate(
                    id = "lrclib_broad_$i",
                    provider = "LRCLIB Global",
                    title = t.ifBlank { title },
                    artist = a.ifBlank { artist },
                    lines = lines,
                    isSynced = obj.optString("syncedLyrics", "").isNotBlank()
                )
            }
        }
        return null
    }

    private fun queryLyricsOvh(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val url = "https://api.lyrics.ovh/v1/${encPath(artist)}/${encPath(title)}"
        val body = httpGet(url) ?: return null
        val raw = JSONObject(body).optString("lyrics", "")
        val lines = parseCleanLines(raw)
        if (lines.isEmpty()) return null
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
        val lines = parseCleanLines(obj.optString("lyrics", ""))
        if (lines.isEmpty()) return null
        return LyricsCandidate(
            id = "lyrist",
            provider = "Lyrist",
            title = obj.optString("title", title).ifBlank { title },
            artist = obj.optString("artist", artist).ifBlank { artist },
            lines = lines
        )
    }

    private fun queryGenius(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val searchBody = httpGet("https://genius.com/api/search/multi?q=${enc(q)}") ?: return null
        val sections = JSONObject(searchBody)
            .optJSONObject("response")
            ?.optJSONArray("sections") ?: return null
        var songUrl: String? = null
        var matchedTitle = title
        var matchedArtist = artist
        for (s in 0 until sections.length()) {
            val hits = sections.optJSONObject(s)?.optJSONArray("hits") ?: continue
            for (h in 0 until hits.length()) {
                val res = hits.optJSONObject(h)?.optJSONObject("result") ?: continue
                val u = res.optString("url", "")
                if (u.startsWith("https://genius.com/") && u.endsWith("-lyrics")) {
                    val gt = res.optString("title", title)
                    val ga = res.optJSONObject("primary_artist")?.optString("name", artist) ?: artist
                    if (AnimatedCanvasSearchService.evaluateMatch(gt, ga, title, artist).titleScore >= 20) {
                        songUrl = u
                        matchedTitle = gt
                        matchedArtist = ga
                        break
                    }
                }
            }
            if (songUrl != null) break
        }
        val html = songUrl?.let { httpGet(it, accept = "text/html") } ?: return null
        val blocks = "<div[^>]*data-lyrics-container=\"true\"[^>]*>([\\s\\S]*?)</div>"
            .toRegex(RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .joinToString("\n")
        val lines = parseCleanLines(blocks)
            .filterNot { it.startsWith("[") && it.endsWith("]") }
        if (lines.isEmpty()) return null
        return LyricsCandidate("genius", "Genius", matchedTitle, matchedArtist, lines)
    }

    private fun queryChartLyrics(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val xml = httpGet(
            "http://api.chartlyrics.com/apiv1.asmx/SearchLyricDirect?artist=${enc(artist)}&song=${enc(title)}",
            accept = "application/xml,text/xml"
        ) ?: return null
        val raw = "<Lyric>([\\s\\S]*?)</Lyric>".toRegex().find(xml)?.groupValues?.getOrNull(1)
        val lines = parseCleanLines(raw)
        if (lines.isEmpty()) return null
        return LyricsCandidate("chartlyrics", "ChartLyrics", title, artist, lines)
    }

    private fun queryNetEase(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val search = httpGet(
            "https://music.163.com/api/search/get/web?s=${enc(q)}&type=1&offset=0&limit=3",
            extraHeaders = mapOf("Referer" to "https://music.163.com/")
        ) ?: return null
        val songs = JSONObject(search).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val songObj = songs.optJSONObject(0) ?: return null
        val id = songObj.optLong("id", 0L)
        if (id <= 0L) return null
        val lyricJson = httpGet(
            "https://music.163.com/api/song/lyric?os=pc&id=$id&lv=1&tv=-1",
            extraHeaders = mapOf("Referer" to "https://music.163.com/")
        ) ?: return null
        val raw = JSONObject(lyricJson).optJSONObject("lrc")?.optString("lyric", "")
        val lines = parseCleanLines(raw)
        if (lines.isEmpty()) return null
        return LyricsCandidate(
            id = "netease_$id",
            provider = "NetEase Music",
            title = songObj.optString("name", title),
            artist = artist,
            lines = lines,
            isSynced = true
        )
    }

    private fun queryKugou(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val res = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(q)}&duration=&hash=")
            ?: return null
        val candidate = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val id = candidate.optString("id", "")
        val accessKey = candidate.optString("accesskey", "")
        if (id.isEmpty() || accessKey.isEmpty()) return null
        val dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8")
            ?: return null
        val contentB64 = JSONObject(dl).optString("content", "")
        if (contentB64.isBlank()) return null
        val decoded = String(android.util.Base64.decode(contentB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val lines = parseCleanLines(decoded)
        if (lines.isEmpty()) return null
        return LyricsCandidate("kugou_$id", "Kugou", title, artist, lines, isSynced = true)
    }

    private fun queryQqMusic(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val body = httpGet(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=2&w=${enc(q)}&format=json",
            extraHeaders = mapOf("Referer" to "https://y.qq.com/")
        ) ?: return null
        val song = JSONObject(body).optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list")?.optJSONObject(0) ?: return null
        val songMid = song.optString("songmid", "")
        if (songMid.isBlank()) return null
        val lyrBody = httpGet(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1",
            extraHeaders = mapOf("Referer" to "https://y.qq.com/")
        ) ?: return null
        val raw = JSONObject(lyrBody).optString("lyric", "")
        val lines = parseCleanLines(raw)
        if (lines.isEmpty()) return null
        return LyricsCandidate("qq_$songMid", "QQ Music", song.optString("songname", title), artist, lines, isSynced = true)
    }

    private fun queryAzLyrics(title: String, artist: String): LyricsCandidate? {
        if (artist.isEmpty()) return null
        val slugArtist = artist.lowercase(Locale.US).replace("[^a-z0-9]".toRegex(), "").removePrefix("the")
        val slugTitle = title.lowercase(Locale.US).replace("[^a-z0-9]".toRegex(), "")
        if (slugArtist.isEmpty() || slugTitle.isEmpty()) return null
        val html = httpGet("https://www.azlyrics.com/lyrics/$slugArtist/$slugTitle.html", accept = "text/html")
            ?: return null
        val match = "Usage of azlyrics\\.com content by any third-party lyrics provider is prohibited by our licensing agreement\\. Sorry about that\\.\\s*-->([\\s\\S]*?)</div>"
            .toRegex()
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val lines = parseCleanLines(match)
        if (lines.isEmpty()) return null
        return LyricsCandidate("azlyrics", "AZLyrics", title, artist, lines)
    }

    private fun queryMusixmatch(title: String, artist: String): LyricsCandidate? {
        val q = "$artist $title".trim()
        val searchHtml = httpGet("https://www.musixmatch.com/search/${encPath(q)}", accept = "text/html") ?: return null
        val trackPath = """href="(/lyrics/[^"]+)"""".toRegex().find(searchHtml)?.groupValues?.getOrNull(1)
            ?: return null
        val pageHtml = httpGet("https://www.musixmatch.com$trackPath", accept = "text/html") ?: return null
        val parts = """class="lyrics__content__ok"[^>]*>([\s\S]*?)</span>""".toRegex()
            .findAll(pageHtml)
            .map { it.groupValues[1] }
            .joinToString("\n")
        val lines = parseCleanLines(parts)
        if (lines.isEmpty()) return null
        return LyricsCandidate("musixmatch", "Musixmatch", title, artist, lines)
    }

    private fun queryLyricsDotCom(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val searchHtml = httpGet("https://www.lyrics.com/lyrics/${encPath(q)}", accept = "text/html") ?: return null
        val matchPath = """href="(/lyric/\d+/[^"]+)"""".toRegex().find(searchHtml)?.groupValues?.getOrNull(1)
            ?: return null
        val pageHtml = httpGet("https://www.lyrics.com$matchPath", accept = "text/html") ?: return null
        val body = """id="lyric-body-text"[^>]*>([\s\S]*?)</pre>""".toRegex()
            .find(pageHtml)?.groupValues?.getOrNull(1) ?: return null
        val lines = parseCleanLines(body)
        if (lines.isEmpty()) return null
        return LyricsCandidate("lyrics_com", "Lyrics.com", title, artist, lines)
    }

    private fun queryLyricsFreak(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val searchHtml = httpGet("https://www.lyricsfreak.com/search.php?q=${enc(q)}", accept = "text/html")
            ?: return null
        val songPath = """href="(https?://www\.lyricsfreak\.com/[^"]+\d+\.html|/[^"]+\d+\.html)""""
            .toRegex()
            .find(searchHtml)?.groupValues?.getOrNull(1) ?: return null
        val fullUrl = if (songPath.startsWith("http")) songPath else "https://www.lyricsfreak.com$songPath"
        val pageHtml = httpGet(fullUrl, accept = "text/html") ?: return null
        val body = """id="content"[^>]*>([\s\S]*?)</div>""".toRegex()
            .find(pageHtml)?.groupValues?.getOrNull(1) ?: return null
        val lines = parseCleanLines(body)
        if (lines.isEmpty()) return null
        return LyricsCandidate("lyricsfreak", "LyricsFreak", title, artist, lines)
    }

    private fun queryMegalobiz(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val html = httpGet("https://www.megalobiz.com/search/all?qry=${enc(q)}", accept = "text/html") ?: return null
        val lrcPath = """href="(/lrc/maker/[^"]+)"""".toRegex().find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val pageHtml = httpGet("https://www.megalobiz.com$lrcPath", accept = "text/html") ?: return null
        val body = """id="lrc_[^"]*details"[^>]*>([\s\S]*?)</span>""".toRegex()
            .find(pageHtml)?.groupValues?.getOrNull(1) ?: return null
        val lines = parseCleanLines(body)
        if (lines.isEmpty()) return null
        return LyricsCandidate("megalobiz", "Megalobiz", title, artist, lines, isSynced = true)
    }

    private fun querySomeRandomApi(title: String, artist: String): LyricsCandidate? {
        val q = "$title $artist".trim()
        val body = httpGet("https://some-random-api.com/lyrics?title=${enc(q)}") ?: return null
        val obj = JSONObject(body)
        val lines = parseCleanLines(obj.optString("lyrics", ""))
        if (lines.isEmpty()) return null
        return LyricsCandidate(
            id = "somerandomapi",
            provider = "SomeRandomApi",
            title = obj.optString("title", title).ifBlank { title },
            artist = obj.optString("author", artist).ifBlank { artist },
            lines = lines
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
