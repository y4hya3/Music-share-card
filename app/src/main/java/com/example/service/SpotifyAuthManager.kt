package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

data class SpotifyTokenResult(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val accountLabel: String = "",
    val spDcCookie: String = ""
)

data class WebViewSpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null
)

data class WebViewSpotifySearchResult(
    val tracks: List<WebViewSpotifyTrack> = emptyList(),
    val capturedPathfinderHash: String? = null,
    val capturedClientToken: String? = null,
    val capturedBearerToken: String? = null,
    val capturedBearerExpiryMs: Long = 0L,
    val capturedSpDcCookie: String? = null,
    val loginRedirectDetected: Boolean = false
)

data class SpotifyWebExchangeOutcome(
    val tokenResult: SpotifyTokenResult? = null,
    val loginRedirectDetected: Boolean = false
)

/**
 * Manages Spotify account authentication and automatic Bearer token capture/refresh:
 * 1. In-app Spotify Web Account Login (captures Web Player / Canvas-capable Bearer tokens automatically).
 * 2. Optional OAuth 2.0 Authorization Code with PKCE (`echosnap://spotify-callback`) for apps with a Client ID.
 */
object SpotifyAuthManager {

    const val REDIRECT_URI = "echosnap://spotify-callback"
    private const val PREFS_PKCE = "echosnap_spotify_pkce"
    private const val KEY_VERIFIER = "pkce_verifier"
    private const val KEY_STATE = "pkce_state"
    private const val KEY_CLIENT_ID = "pkce_client_id"

    @Volatile
    var latestCapturedClientToken: String? = null

    @Volatile
    var latestCapturedClientTokenExpiresAtMs: Long = 0L

    @Volatile
    var latestCapturedSearchHash: String? = null

    fun captureClientToken(token: String?, ttlMs: Long = 15 * 60_000L, nowMs: Long = System.currentTimeMillis()) {
        val clean = token?.trim().orEmpty()
        if (clean.length >= 20) {
            latestCapturedClientToken = clean
            latestCapturedClientTokenExpiresAtMs = nowMs + ttlMs.coerceIn(60_000L, 30 * 60_000L)
        }
    }

    fun getValidCapturedClientToken(nowMs: Long = System.currentTimeMillis()): String? {
        val tok = latestCapturedClientToken?.trim().orEmpty()
        if (tok.length < 20) return null
        val exp = latestCapturedClientTokenExpiresAtMs
        if (exp > 0L && nowMs >= exp) {
            latestCapturedClientToken = null
            latestCapturedClientTokenExpiresAtMs = 0L
            return null
        }
        return tok
    }

    fun invalidateCapturedClientToken() {
        latestCapturedClientToken = null
        latestCapturedClientTokenExpiresAtMs = 0L
    }

    fun readLiveSpDcFromCookieManager(): String {
        return try {
            val cm = CookieManager.getInstance()
            val domains = listOf(
                "https://open.spotify.com",
                "https://accounts.spotify.com",
                "https://spotify.com"
            )
            for (domain in domains) {
                val spDc = extractSpDcFromCookieHeader(cm.getCookie(domain))
                if (spDc.length >= 20) return spDc
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    fun buildPkceAuthorizeIntent(context: Context, clientId: String): Intent? {
        val cleanId = clientId.trim()
        if (cleanId.isEmpty()) return null

        val verifier = generateCodeVerifier()
        val state = generateRandomUrlSafe(24)
        val challenge = sha256Base64Url(verifier)

        context.applicationContext.getSharedPreferences(PREFS_PKCE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .putString(KEY_CLIENT_ID, cleanId)
            .apply()

        val uri = Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .path("authorize")
            .appendQueryParameter("client_id", cleanId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", "user-read-private user-read-currently-playing")
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun handlePkceCallbackUri(context: Context, uri: Uri?): SpotifyTokenResult? =
        withContext(Dispatchers.IO) {
            if (uri == null || uri.scheme != "echosnap" || uri.host != "spotify-callback") {
                return@withContext null
            }
            val code = uri.getQueryParameter("code") ?: return@withContext null
            val incomingState = uri.getQueryParameter("state") ?: return@withContext null

            val prefs = context.applicationContext.getSharedPreferences(PREFS_PKCE, Context.MODE_PRIVATE)
            val savedState = prefs.getString(KEY_STATE, null)
            val verifier = prefs.getString(KEY_VERIFIER, null)
            val clientId = prefs.getString(KEY_CLIENT_ID, null)
            if (savedState == null || savedState != incomingState || verifier.isNullOrBlank() || clientId.isNullOrBlank()) {
                return@withContext null
            }

            val params = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to verifier
            )
            exchangeTokenRequest(params)
        }

    suspend fun refreshPkceToken(clientId: String, refreshToken: String): SpotifyTokenResult? =
        withContext(Dispatchers.IO) {
            val cleanId = clientId.trim()
            val cleanRefresh = refreshToken.trim()
            if (cleanId.isEmpty() || cleanRefresh.isEmpty()) return@withContext null

            val params = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to cleanRefresh,
                "client_id" to cleanId
            )
            val result = exchangeTokenRequest(params) ?: return@withContext null
            if (result.refreshToken.isBlank()) {
                result.copy(refreshToken = cleanRefresh)
            } else {
                result
            }
        }

    suspend fun fetchSpotifyProfileLabel(accessToken: String): String = withContext(Dispatchers.IO) {
        val clean = AnimatedCanvasSearchService.normalizeSpotifyToken(accessToken)
        if (clean.isEmpty()) return@withContext ""
        try {
            val conn = (URL("https://api.spotify.com/v1/me").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $clean")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    json.optString("display_name", "").ifBlank { json.optString("id", "") }
                } else {
                    ""
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun extractSpDcFromCookieHeader(cookieHeader: String?): String {
        val raw = cookieHeader?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val match = "(?:^|;\\s*)sp_dc=([^;\\s]+)".toRegex().find(raw)
        if (match != null) {
            return match.groupValues[1].trim().trim('"', '\'')
        }
        return ""
    }

    fun extractSpDcValue(rawInput: String?): String {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val fromHeader = extractSpDcFromCookieHeader(trimmed)
        if (fromHeader.isNotEmpty()) return fromHeader
        return trimmed.removePrefix("sp_dc=").trim().trim('"', '\'').substringBefore(';').trim()
    }

    fun looksLikeSpDcCookie(rawInput: String?): Boolean {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.contains("sp_dc=", ignoreCase = true)) return true
        // Spotify sp_dc session cookies begin with AQ... whereas OAuth/Web Bearer tokens begin with BQ...
        return raw.startsWith("AQ") && raw.length >= 40 && !raw.contains(' ')
    }

    @Volatile
    private var cachedTotpSecret: Pair<String, IntArray>? = null

    suspend fun exchangeSpDcForAccessToken(
        rawSpDc: String,
        userAgent: String = DESKTOP_BROWSER_UA
    ): SpotifyTokenResult? = withContext(Dispatchers.IO) {
        val spDc = extractSpDcValue(rawSpDc)
        if (spDc.length < 20) return@withContext null

        // 1. Try Spotify TOTP-authenticated token endpoints (/api/token & /get_access_token)
        val totpPair = fetchLatestTotpSecret()
        if (totpPair != null) {
            val (ver, cipherInts) = totpPair
            val keyBytes = deriveSpotifyTotpKeyBytes(cipherInts)
            val serverSec = fetchSpotifyServerTimeSec() ?: (System.currentTimeMillis() / 1000L)
            val clientSec = System.currentTimeMillis() / 1000L
            val totpServer = computeTotpCode(keyBytes, serverSec)
            val totpClient = computeTotpCode(keyBytes, clientSec)

            val totpUrls = listOf(
                "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver",
                "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver",
                "https://open.spotify.com/get_access_token?reason=init&productType=web_player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver",
                "https://open.spotify.com/get_access_token?reason=transport&productType=web_player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver"
            )
            for (endpoint in totpUrls) {
                val res = fetchTokenJsonWithSpDc(endpoint, spDc, DESKTOP_BROWSER_UA)
                if (res != null) return@withContext res
            }
        }

        // 2. Try direct endpoints + SSR HTML fallback
        val directEndpoints = listOf(
            "https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
            "https://open.spotify.com/get_access_token?reason=init&productType=web_player"
        )
        for (endpoint in directEndpoints) {
            val res = fetchTokenJsonWithSpDc(endpoint, spDc, userAgent)
            if (res != null) return@withContext res
        }

        val htmlRes = fetchTokenFromOpenSpotifyHtml(spDc, DESKTOP_BROWSER_UA, allowAnonymous = false)
        if (htmlRes != null) return@withContext htmlRes

        null
    }

    /**
     * Obtains a short-lived anonymous Web Player Bearer token directly from open.spotify.com
     * without an account cookie so Pathfinder GraphQL search & public track lookups work even
     * when the user hasn't signed in to Spotify.
     */
    suspend fun fetchAnonymousWebToken(): SpotifyTokenResult? = withContext(Dispatchers.IO) {
        try {
            val totpPair = fetchLatestTotpSecret()
            if (totpPair != null) {
                val (ver, cipherInts) = totpPair
                val keyBytes = deriveSpotifyTotpKeyBytes(cipherInts)
                val serverSec = fetchSpotifyServerTimeSec() ?: (System.currentTimeMillis() / 1000L)
                val clientSec = System.currentTimeMillis() / 1000L
                val totpServer = computeTotpCode(keyBytes, serverSec)
                val totpClient = computeTotpCode(keyBytes, clientSec)
                val urls = listOf(
                    "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver",
                    "https://open.spotify.com/get_access_token?reason=transport&productType=web_player&totp=$totpClient&totpServer=$totpServer&totpVer=$ver"
                )
                for (u in urls) {
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", DESKTOP_BROWSER_UA)
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Origin", "https://open.spotify.com")
                        setRequestProperty("Referer", "https://open.spotify.com/")
                        setRequestProperty("App-Platform", "WebPlayer")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            parseWebSessionJson(body, spDc = "", allowAnonymous = true)?.let { return@withContext it }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            fetchTokenFromOpenSpotifyHtml(spDc = "", userAgent = DESKTOP_BROWSER_UA, allowAnonymous = true)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Silent off-screen WebView fallback that injects `sp_dc` (or reuses existing CookieManager session)
     * and loads Spotify Web Player with a Desktop Chrome User-Agent so Spotify's own bundle mints
     * a fresh Bearer token via /api/token even if external TOTP secrets rotated.
     */
    suspend fun exchangeSpDcViaWebView(
        context: Context,
        rawSpDc: String
    ): SpotifyTokenResult? = exchangeSpDcViaWebViewDetailed(context, rawSpDc).tokenResult

    suspend fun exchangeSpDcViaWebViewDetailed(
        context: Context,
        rawSpDc: String
    ): SpotifyWebExchangeOutcome {
        val liveCookieSpDc = readLiveSpDcFromCookieManager()
        val spDc = extractSpDcValue(rawSpDc).ifBlank { liveCookieSpDc }
        if (spDc.length < 20) return SpotifyWebExchangeOutcome()

        return withTimeoutOrNull(14_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<SpotifyWebExchangeOutcome> { cont ->
                    var wv: WebView? = null
                    var finished = false
                    var sawLoginRedirect = false
                    val mainHandler = Handler(Looper.getMainLooper())
                    var loginRedirectCheckRunnable: Runnable? = null

                    fun complete(outcome: SpotifyWebExchangeOutcome) {
                        if (finished) return
                        finished = true
                        loginRedirectCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                        loginRedirectCheckRunnable = null
                        try {
                            wv?.stopLoading()
                            wv?.destroy()
                        } catch (_: Exception) {
                        }
                        wv = null
                        if (cont.isActive) cont.resume(outcome)
                    }

                    try {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        // Keep freshest sp_dc if CookieManager already rotated it, otherwise inject stored sp_dc
                        val activeSpDc = if (liveCookieSpDc.length >= 20 && extractSpDcValue(rawSpDc).isEmpty()) {
                            liveCookieSpDc
                        } else {
                            cm.setCookie(
                                "https://open.spotify.com",
                                "sp_dc=$spDc; Domain=.spotify.com; Path=/; Secure"
                            )
                            cm.flush()
                            spDc
                        }

                        val webView = WebView(context.applicationContext)
                        wv = webView
                        cm.setAcceptThirdPartyCookies(webView, true)
                        webView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            userAgentString = DESKTOP_BROWSER_UA
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        webView.addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onTokenFound(token: String?, expiryMs: Long) {
                                    val clean = token?.trim().orEmpty()
                                    if (clean.length >= 20) {
                                        val latestSpDc = readLiveSpDcFromCookieManager().ifBlank { activeSpDc }
                                        webView.post {
                                            complete(
                                                SpotifyWebExchangeOutcome(
                                                    tokenResult = SpotifyTokenResult(
                                                        accessToken = clean,
                                                        expiresAtMs = if (expiryMs > 0L) expiryMs else System.currentTimeMillis() + 3_500_000L,
                                                        spDcCookie = latestSpDc
                                                    ),
                                                    loginRedirectDetected = false
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            "EchoSnapAuthBridge"
                        )
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                val u = url.orEmpty()
                                if (u.contains("accounts.spotify.com") && u.contains("/login")) {
                                    sawLoginRedirect = true
                                    loginRedirectCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                                    val r = Runnable {
                                        if (!finished && sawLoginRedirect) {
                                            complete(SpotifyWebExchangeOutcome(tokenResult = null, loginRedirectDetected = true))
                                        }
                                    }
                                    loginRedirectCheckRunnable = r
                                    mainHandler.postDelayed(r, 1200L)
                                } else if (u.contains("open.spotify.com")) {
                                    sawLoginRedirect = false
                                    loginRedirectCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                                }
                                view?.evaluateJavascript(WEB_TOKEN_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                val u = url.orEmpty()
                                if (u.contains("accounts.spotify.com") && u.contains("/login")) {
                                    complete(SpotifyWebExchangeOutcome(tokenResult = null, loginRedirectDetected = true))
                                    return
                                }
                                view?.evaluateJavascript(WEB_TOKEN_HOOK_JS, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (reqUrl.contains(".spotify.com/")) {
                                    val headers = request?.requestHeaders
                                    val ct = headers?.entries
                                        ?.firstOrNull { it.key.equals("client-token", ignoreCase = true) }
                                        ?.value
                                    if (!ct.isNullOrBlank()) {
                                        captureClientToken(ct)
                                    }
                                    val isAuthedApiCall = reqUrl.contains("api-partner.spotify.com") ||
                                        reqUrl.contains("spclient.wg.spotify.com") ||
                                        reqUrl.contains("api.spotify.com")
                                    val auth = headers?.entries
                                        ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                        ?.value
                                    if (isAuthedApiCall && !auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
                                        val token = auth.substring(7).trim()
                                        val latestSpDc = readLiveSpDcFromCookieManager().ifBlank { activeSpDc }
                                        if (token.length >= 20 && latestSpDc.length >= 20) {
                                            webView.post {
                                                complete(
                                                    SpotifyWebExchangeOutcome(
                                                        tokenResult = SpotifyTokenResult(
                                                            accessToken = token,
                                                            expiresAtMs = System.currentTimeMillis() + 3_500_000L,
                                                            spDcCookie = latestSpDc
                                                        ),
                                                        loginRedirectDetected = false
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        cont.invokeOnCancellation {
                            loginRedirectCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                            loginRedirectCheckRunnable = null
                            webView.post {
                                try {
                                    webView.stopLoading()
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        webView.resumeTimers()
                        webView.loadUrl("https://open.spotify.com/")
                    } catch (_: Exception) {
                        complete(SpotifyWebExchangeOutcome())
                    }
                }
            }
        } ?: SpotifyWebExchangeOutcome()
    }

    /**
     * Off-screen WebView Spotify search fallback for non-Spotify music players.
     * Loads https://open.spotify.com/search/<query> with the saved/live sp_dc session cookie,
     * captures Spotify's live Pathfinder tracksV2 JSON, DOM track links, sha256Hash,
     * client-token, AND any freshly minted Bearer token / rotated sp_dc cookie.
     */
    suspend fun searchTrackIdsViaWebView(
        context: Context,
        query: String,
        rawSpDc: String
    ): WebViewSpotifySearchResult? {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return null
        val liveCookieSpDc = readLiveSpDcFromCookieManager()
        val spDc = extractSpDcValue(rawSpDc).ifBlank { liveCookieSpDc }

        return withTimeoutOrNull(14_500L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<WebViewSpotifySearchResult?> { cont ->
                    var wv: WebView? = null
                    var finished = false
                    var capturedHash: String? = null
                    var capturedClientToken: String? = null
                    var capturedBearerToken: String? = null
                    var capturedBearerExpiryMs: Long = 0L
                    var loginRedirectDetected = false
                    val mainHandler = Handler(Looper.getMainLooper())
                    var pollRunnable: Runnable? = null

                    fun currentBestSpDc(): String? =
                        readLiveSpDcFromCookieManager().ifBlank { spDc }.takeIf { it.length >= 20 }

                    fun complete(result: WebViewSpotifySearchResult?) {
                        if (finished) return
                        finished = true
                        pollRunnable?.let { mainHandler.removeCallbacks(it) }
                        pollRunnable = null
                        try {
                            wv?.stopLoading()
                            wv?.destroy()
                        } catch (_: Exception) {
                        }
                        wv = null
                        if (cont.isActive) cont.resume(result)
                    }

                    try {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        if (spDc.length >= 20) {
                            cm.setCookie(
                                "https://open.spotify.com",
                                "sp_dc=$spDc; Domain=.spotify.com; Path=/; Secure"
                            )
                            cm.flush()
                        }

                        val webView = WebView(context.applicationContext)
                        wv = webView
                        cm.setAcceptThirdPartyCookies(webView, true)
                        webView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            loadsImagesAutomatically = false
                            userAgentString = DESKTOP_BROWSER_UA
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        webView.addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onPathfinderMeta(hash: String?, clientToken: String?) {
                                    val cleanHash = hash?.trim().orEmpty()
                                    if (cleanHash.length == 64 && cleanHash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                        capturedHash = cleanHash
                                        latestCapturedSearchHash = cleanHash
                                    }
                                    val cleanCt = clientToken?.trim().orEmpty()
                                    if (cleanCt.length >= 20) {
                                        capturedClientToken = cleanCt
                                        captureClientToken(cleanCt)
                                    }
                                }

                                @JavascriptInterface
                                fun onTokenFound(token: String?, expiryMs: Long) {
                                    val cleanTok = token?.trim().orEmpty()
                                    if (cleanTok.length >= 20) {
                                        capturedBearerToken = cleanTok
                                        if (expiryMs > 0L) {
                                            capturedBearerExpiryMs = expiryMs
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onSearchJson(rawJson: String?) {
                                    if (rawJson.isNullOrBlank()) return
                                    val parsed = parseWebViewPathfinderTracks(rawJson)
                                    if (parsed.isNotEmpty()) {
                                        webView.post {
                                            complete(
                                                WebViewSpotifySearchResult(
                                                    tracks = parsed,
                                                    capturedPathfinderHash = capturedHash ?: latestCapturedSearchHash,
                                                    capturedClientToken = capturedClientToken ?: getValidCapturedClientToken(),
                                                    capturedBearerToken = capturedBearerToken,
                                                    capturedBearerExpiryMs = capturedBearerExpiryMs,
                                                    capturedSpDcCookie = currentBestSpDc(),
                                                    loginRedirectDetected = loginRedirectDetected
                                                )
                                            )
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onDomTracksJson(rawJsonArray: String?) {
                                    if (rawJsonArray.isNullOrBlank()) return
                                    val parsed = parseWebViewDomTracks(rawJsonArray)
                                    if (parsed.isNotEmpty()) {
                                        webView.post {
                                            complete(
                                                WebViewSpotifySearchResult(
                                                    tracks = parsed,
                                                    capturedPathfinderHash = capturedHash ?: latestCapturedSearchHash,
                                                    capturedClientToken = capturedClientToken ?: getValidCapturedClientToken(),
                                                    capturedBearerToken = capturedBearerToken,
                                                    capturedBearerExpiryMs = capturedBearerExpiryMs,
                                                    capturedSpDcCookie = currentBestSpDc(),
                                                    loginRedirectDetected = loginRedirectDetected
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            "EchoSnapSearchBridge"
                        )

                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                val u = url.orEmpty()
                                if (u.contains("accounts.spotify.com") && u.contains("/login")) {
                                    loginRedirectDetected = true
                                }
                                view?.evaluateJavascript(WEB_SEARCH_HOOK_JS, null)
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                view?.evaluateJavascript(WEB_SEARCH_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                val u = url.orEmpty()
                                if (u.contains("accounts.spotify.com") && u.contains("/login")) {
                                    loginRedirectDetected = true
                                    complete(
                                        WebViewSpotifySearchResult(
                                            tracks = emptyList(),
                                            capturedPathfinderHash = capturedHash ?: latestCapturedSearchHash,
                                            capturedClientToken = capturedClientToken ?: getValidCapturedClientToken(),
                                            capturedBearerToken = capturedBearerToken,
                                            capturedBearerExpiryMs = capturedBearerExpiryMs,
                                            capturedSpDcCookie = currentBestSpDc(),
                                            loginRedirectDetected = true
                                        )
                                    )
                                    return
                                }
                                view?.evaluateJavascript(WEB_SEARCH_HOOK_JS, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (reqUrl.contains("spotify.com/")) {
                                    val headers = request?.requestHeaders
                                    val ct = headers?.entries
                                        ?.firstOrNull { it.key.equals("client-token", ignoreCase = true) }
                                        ?.value
                                    if (!ct.isNullOrBlank()) {
                                        capturedClientToken = ct.trim()
                                        captureClientToken(ct)
                                    }
                                    val auth = headers?.entries
                                        ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                        ?.value
                                    if (!auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
                                        val bearer = auth.substring(7).trim()
                                        if (bearer.length >= 20 && currentBestSpDc() != null) {
                                            capturedBearerToken = bearer
                                            if (capturedBearerExpiryMs == 0L) {
                                                capturedBearerExpiryMs = System.currentTimeMillis() + 3_500_000L
                                            }
                                        }
                                    }
                                    if (reqUrl.contains("pathfinder") && reqUrl.contains("searchDesktop")) {
                                        val hashMatch = "sha256Hash(?:%22|\")%3A(?:%22|\")([a-f0-9]{64})".toRegex(RegexOption.IGNORE_CASE)
                                            .find(reqUrl)?.groupValues?.getOrNull(1)
                                        if (!hashMatch.isNullOrBlank()) {
                                            capturedHash = hashMatch
                                            latestCapturedSearchHash = hashMatch
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        val ticker = object : Runnable {
                            override fun run() {
                                if (finished) return
                                try {
                                    wv?.evaluateJavascript(WEB_SEARCH_HOOK_JS, null)
                                } catch (_: Exception) {
                                }
                                mainHandler.postDelayed(this, 650L)
                            }
                        }
                        pollRunnable = ticker
                        mainHandler.postDelayed(ticker, 900L)

                        cont.invokeOnCancellation {
                            pollRunnable?.let { mainHandler.removeCallbacks(it) }
                            pollRunnable = null
                            webView.post {
                                try {
                                    webView.stopLoading()
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        webView.resumeTimers()
                        val encodedSearchPath = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
                        val searchUrl = "https://open.spotify.com/search/$encodedSearchPath"
                        webView.loadUrl(searchUrl)
                    } catch (_: Exception) {
                        complete(null)
                    }
                }
            }
        }
    }

    private fun parseWebViewPathfinderTracks(rawJson: String): List<WebViewSpotifyTrack> {
        return try {
            val out = mutableListOf<WebViewSpotifyTrack>()
            val seenIds = hashSetOf<String>()
            val root = JSONObject(rawJson)
            val items = root.optJSONObject("data")
                ?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")
                ?.optJSONArray("items")
                ?: root.optJSONObject("data")
                    ?.optJSONObject("searchDesktop")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("items")

            if (items != null) {
                for (i in 0 until items.length()) {
                    val itemObj = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                    val data = itemObj.optJSONObject("data")
                        ?: itemObj.optJSONObject("itemUnion")?.optJSONObject("data")
                        ?: continue
                    val track = parseTrackObject(data) ?: continue
                    if (seenIds.add(track.id)) {
                        out.add(track)
                    }
                    if (out.size >= 8) break
                }
            }
            if (out.isEmpty()) {
                extractTracksRecursivelyFromJson(root, out, seenIds)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTrackObject(data: JSONObject): WebViewSpotifyTrack? {
        val uri = data.optString("uri", "").trim()
        val id = data.optString("id", "").ifBlank {
            AnimatedCanvasSearchService.extractSpotifyTrackId(uri)
        }
        val title = data.optString("name", "").trim()
        if (id.length != 22 || title.isEmpty()) return null
        if (uri.isNotEmpty() && !uri.startsWith("spotify:track:")) return null

        val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
        val artistsFallback = data.optJSONArray("artists")
        val artistNames = buildList {
            if (artistItems != null) {
                for (a in 0 until artistItems.length()) {
                    val aObj = artistItems.optJSONObject(a) ?: continue
                    val aName = aObj.optJSONObject("profile")?.optString("name", "")
                        ?.ifBlank { aObj.optString("name", "") }
                        .orEmpty()
                    if (aName.isNotBlank()) add(aName)
                }
            } else if (artistsFallback != null) {
                for (a in 0 until artistsFallback.length()) {
                    val aName = artistsFallback.optJSONObject(a)?.optString("name", "").orEmpty()
                    if (aName.isNotBlank()) add(aName)
                }
            }
        }.joinToString(", ")

        val coverSources = data.optJSONObject("albumOfTrack")
            ?.optJSONObject("coverArt")
            ?.optJSONArray("sources")
        var bestCover: String? = null
        var bestWidth = -1
        if (coverSources != null) {
            for (s in 0 until coverSources.length()) {
                val src = coverSources.optJSONObject(s) ?: continue
                val w = src.optInt("width", 0)
                val url = src.optString("url", "").trim()
                if (url.isNotEmpty() && w >= bestWidth) {
                    bestWidth = w
                    bestCover = url
                }
            }
        }
        return WebViewSpotifyTrack(id = id, title = title, artist = artistNames, coverUrl = bestCover)
    }

    internal fun extractTracksRecursivelyFromJson(
        node: Any?,
        out: MutableList<WebViewSpotifyTrack>,
        seenIds: MutableSet<String>,
        depth: Int = 0
    ) {
        if (node == null || depth > 14 || out.size >= 10) return
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri", "")
                if (uri.startsWith("spotify:track:")) {
                    parseTrackObject(node)?.let { t ->
                        if (seenIds.add(t.id)) out.add(t)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext() && out.size < 10) {
                    val k = keys.next()
                    extractTracksRecursivelyFromJson(node.opt(k), out, seenIds, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= 10) break
                    extractTracksRecursivelyFromJson(node.opt(i), out, seenIds, depth + 1)
                }
            }
        }
    }

    private fun parseWebViewDomTracks(rawJsonArray: String): List<WebViewSpotifyTrack> {
        return try {
            val arr = JSONArray(rawJsonArray)
            val out = mutableListOf<WebViewSpotifyTrack>()
            val seen = hashSetOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val title = obj.optString("title", "").trim()
                val artist = obj.optString("artist", "").trim()
                if (id.length == 22 && title.isNotEmpty() && seen.add(id)) {
                    out.add(WebViewSpotifyTrack(id = id, title = title, artist = artist))
                }
                if (out.size >= 8) break
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun deriveSpotifyTotpKeyBytes(cipherInts: IntArray): ByteArray {
        val xored = IntArray(cipherInts.size) { i -> cipherInts[i] xor ((i % 33) + 9) }
        return xored.joinToString("") { it.toString() }.toByteArray(Charsets.UTF_8)
    }

    internal fun computeTotpCode(keyBytes: ByteArray, epochSeconds: Long, timeStep: Long = 30L): String {
        val counter = epochSeconds / timeStep
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFFL).toByte()
            value = value ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun fetchLatestTotpSecret(): Pair<String, IntArray>? {
        cachedTotpSecret?.let { return it }
        val sources = listOf(
            "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json",
            "https://raw.githubusercontent.com/Thereallo1026/spotify-secrets/main/secrets/secretDict.json"
        )
        for (src in sources) {
            try {
                val conn = (URL(src).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 4500
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val latestVer = json.keys().asSequence()
                            .mapNotNull { k -> k.toIntOrNull()?.let { k to it } }
                            .maxByOrNull { it.second }
                            ?.first ?: continue
                        val arr = json.optJSONArray(latestVer) ?: continue
                        val ints = IntArray(arr.length()) { idx -> arr.getInt(idx) }
                        if (ints.isNotEmpty()) {
                            val pair = latestVer to ints
                            cachedTotpSecret = pair
                            return pair
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchSpotifyServerTimeSec(): Long? {
        return try {
            val conn = (URL("https://open.spotify.com/api/server-time").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", DESKTOP_BROWSER_UA)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val sec = JSONObject(body).optLong("serverTime", 0L)
                    if (sec > 1_000_000_000L) sec else null
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

    private fun fetchTokenJsonWithSpDc(
        url: String,
        spDc: String,
        userAgent: String
    ): SpotifyTokenResult? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Cookie", "sp_dc=$spDc")
                setRequestProperty("User-Agent", userAgent.ifBlank { DESKTOP_BROWSER_UA })
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", "https://open.spotify.com/")
                setRequestProperty("Origin", "https://open.spotify.com")
                setRequestProperty("App-Platform", "WebPlayer")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseWebSessionJson(body, spDc)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTokenFromOpenSpotifyHtml(
        spDc: String,
        userAgent: String,
        allowAnonymous: Boolean = false
    ): SpotifyTokenResult? {
        return try {
            val conn = (URL("https://open.spotify.com/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                if (spDc.isNotBlank()) {
                    setRequestProperty("Cookie", "sp_dc=$spDc")
                }
                setRequestProperty("User-Agent", userAgent.ifBlank { DESKTOP_BROWSER_UA })
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val sessionRegex = "<script[^>]*id=\"(?:session|appServerConfig)\"[^>]*>([^<]+)</script>".toRegex()
                for (match in sessionRegex.findAll(html)) {
                    val rawContent = match.groupValues[1].trim()
                    parseWebSessionJson(rawContent, spDc, allowAnonymous)?.let { return it }
                    // Some Spotify builds base64-encode #appServerConfig
                    try {
                        val decoded = String(Base64.decode(rawContent, Base64.DEFAULT), Charsets.UTF_8)
                        parseWebSessionJson(decoded, spDc, allowAnonymous)?.let { return it }
                    } catch (_: Exception) {
                    }
                }
                null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWebSessionJson(
        rawJson: String,
        spDc: String,
        allowAnonymous: Boolean = false
    ): SpotifyTokenResult? {
        return try {
            val json = JSONObject(rawJson)
            val isAnon = json.optBoolean("isAnonymous", true)
            val token = json.optString("accessToken", "").trim()
            val exp = json.optLong("accessTokenExpirationTimestampMs", 0L)
            if ((allowAnonymous || !isAnon) && token.length >= 20) {
                SpotifyTokenResult(
                    accessToken = token,
                    expiresAtMs = if (exp > 0L) exp else System.currentTimeMillis() + 3_500_000L,
                    spDcCookie = spDc
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearSpotifyWebCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {
        }
    }

    const val DESKTOP_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val WEB_TOKEN_HOOK_JS = """
(function() {
  try {
    function sendTok(tok, exp) {
      if (tok && tok.length > 20 && window.EchoSnapAuthBridge) {
        window.EchoSnapAuthBridge.onTokenFound(String(tok), Number(exp || 0));
        return true;
      }
      return false;
    }
    var sessionEl = document.getElementById('session') || document.getElementById('appServerConfig');
    if (sessionEl && sessionEl.textContent) {
      var rawTxt = sessionEl.textContent.trim();
      var candidates = [rawTxt];
      try { candidates.push(atob(rawTxt)); } catch (e) {}
      for (var c = 0; c < candidates.length; c++) {
        try {
          var parsed = JSON.parse(candidates[c]);
          if (parsed && parsed.accessToken && !parsed.isAnonymous &&
              sendTok(parsed.accessToken, parsed.accessTokenExpirationTimestampMs)) {
            return;
          }
        } catch (e) {}
      }
    }
    if (!window.__echoSnapHookedFetch && window.fetch) {
      window.__echoSnapHookedFetch = true;
      var origFetch = window.fetch;
      window.fetch = function() {
        var args = arguments;
        return origFetch.apply(this, args).then(function(resp) {
          try {
            var u = (resp && resp.url) ? String(resp.url) : String(args[0] || '');
            if (u.indexOf('/api/token') >= 0 || u.indexOf('get_access_token') >= 0) {
              resp.clone().json().then(function(data) {
                if (data && data.accessToken && !data.isAnonymous) {
                  sendTok(data.accessToken, data.accessTokenExpirationTimestampMs);
                }
              }).catch(function() {});
            }
          } catch (e) {}
          return resp;
        });
      };
    }
  } catch (e) {}
})();
"""

    private const val WEB_SEARCH_HOOK_JS = """
(function() {
  try {
    var bridge = window.EchoSnapSearchBridge;
    if (!bridge) return;
    function inspectUrlOrBody(urlStr, bodyStr, ct) {
      var u = String(urlStr || '');
      var combined = u + ' ' + String(bodyStr || '');
      var hashMatch = null;
      if (combined.indexOf('searchDesktop') >= 0 || combined.indexOf('search') >= 0) {
        var m = /"sha256Hash"\s*:\s*"([a-f0-9]{64})"/i.exec(combined) || /sha256Hash(?:%22|")%3A(?:%22|")([a-f0-9]{64})/i.exec(combined);
        if (m && m[1]) hashMatch = m[1];
      }
      if (hashMatch || ct) {
        bridge.onPathfinderMeta(hashMatch || '', String(ct || ''));
      }
    }
    var sessionEl = document.getElementById('session') || document.getElementById('appServerConfig');
    if (sessionEl && sessionEl.textContent) {
      var rawTxt = sessionEl.textContent.trim();
      var candidates = [rawTxt];
      try { candidates.push(atob(rawTxt)); } catch (e) {}
      for (var c = 0; c < candidates.length; c++) {
        try {
          var parsed = JSON.parse(candidates[c]);
          if (parsed && parsed.accessToken && !parsed.isAnonymous && parsed.accessToken.length > 20) {
            bridge.onTokenFound(String(parsed.accessToken), Number(parsed.accessTokenExpirationTimestampMs || 0));
            break;
          }
        } catch (e) {}
      }
    }
    if (!window.__echoSnapSearchHookedFetch && window.fetch) {
      window.__echoSnapSearchHookedFetch = true;
      var origFetch = window.fetch;
      window.fetch = function() {
        var args = arguments;
        try {
          var reqUrl = (args[0] && args[0].url) ? String(args[0].url) : String(args[0] || '');
          var init = args[1] || {};
          var h = init.headers;
          var ct = '';
          if (h) {
            ct = (typeof h.get === 'function') ? (h.get('client-token') || '') : (h['client-token'] || h['Client-Token'] || '');
          }
          inspectUrlOrBody(reqUrl, (typeof init.body === 'string' ? init.body : ''), ct);
        } catch (e) {}
        return origFetch.apply(this, args).then(function(resp) {
          try {
            var u = (resp && resp.url) ? String(resp.url) : String(args[0] || '');
            if (u.indexOf('/api/token') >= 0 || u.indexOf('get_access_token') >= 0) {
              resp.clone().json().then(function(data) {
                if (data && data.accessToken && !data.isAnonymous) {
                  bridge.onTokenFound(String(data.accessToken), Number(data.accessTokenExpirationTimestampMs || 0));
                }
              }).catch(function() {});
            }
            if (u.indexOf('pathfinder') >= 0 || u.indexOf('search') >= 0) {
              resp.clone().text().then(function(txt) {
                if (txt && (txt.indexOf('spotify:track:') >= 0 || txt.indexOf('tracksV2') >= 0 || txt.indexOf('searchDesktop') >= 0)) {
                  bridge.onSearchJson(txt);
                }
              }).catch(function() {});
            }
          } catch (e) {}
          return resp;
        });
      };
    }

    if (!window.__echoSnapSearchHookedXhr && window.XMLHttpRequest) {
      window.__echoSnapSearchHookedXhr = true;
      var origOpen = XMLHttpRequest.prototype.open;
      var origSend = XMLHttpRequest.prototype.send;
      var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
      XMLHttpRequest.prototype.open = function(method, url) {
        this.__echoUrl = String(url || '');
        this.__echoCt = '';
        return origOpen.apply(this, arguments);
      };
      XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
        if (name && String(name).toLowerCase() === 'client-token') {
          this.__echoCt = String(value || '');
        }
        return origSetHeader.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function(body) {
        var xhr = this;
        try {
          inspectUrlOrBody(xhr.__echoUrl, typeof body === 'string' ? body : '', xhr.__echoCt);
          xhr.addEventListener('load', function() {
            try {
              var u = String(xhr.__echoUrl || '');
              if ((u.indexOf('pathfinder') >= 0 || u.indexOf('search') >= 0) && xhr.responseText) {
                if (xhr.responseText.indexOf('spotify:track:') >= 0) {
                  bridge.onSearchJson(xhr.responseText);
                }
              }
            } catch (e) {}
          });
        } catch (e) {}
        return origSend.apply(this, arguments);
      };
    }

    var links = document.querySelectorAll('a[href*="/track/"]');
    if (links && links.length > 0) {
      var seen = {};
      var collected = [];
      for (var i = 0; i < links.length; i++) {
        var a = links[i];
        var href = String(a.getAttribute('href') || '');
        var idM = /\/track\/([A-Za-z0-9]{22})/.exec(href);
        if (!idM || !idM[1]) continue;
        var tid = idM[1];
        if (seen[tid]) continue;
        seen[tid] = true;
        var title = (a.innerText || a.textContent || a.getAttribute('aria-label') || '').trim();
        var row = a.closest('[data-testid="tracklist-row"]') || a.closest('[role="row"]') || a.closest('[data-testid="top-result-card"]') || a.parentElement;
        var artist = '';
        if (row) {
          var artistLinks = row.querySelectorAll('a[href*="/artist/"]');
          if (artistLinks && artistLinks.length > 0) {
            var names = [];
            for (var k = 0; k < artistLinks.length; k++) {
              var an = (artistLinks[k].innerText || '').trim();
              if (an) names.push(an);
            }
            artist = names.join(', ');
          }
        }
        if (title) {
          collected.push({ id: tid, title: title, artist: artist });
        }
        if (collected.length >= 8) break;
      }
      if (collected.length > 0) {
        bridge.onDomTracksJson(JSON.stringify(collected));
      }
    }
  } catch (e) {}
})();
"""

    private fun exchangeTokenRequest(params: Map<String, String>): SpotifyTokenResult? {
        return try {
            val formBody = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val access = json.optString("access_token", "").trim()
                    if (access.isEmpty()) return null
                    val refresh = json.optString("refresh_token", "").trim()
                    val expiresInSec = json.optLong("expires_in", 3600L)
                    val expiryMs = System.currentTimeMillis() + (expiresInSec * 1000L)
                    SpotifyTokenResult(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = expiryMs
                    )
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

    private fun generateCodeVerifier(): String = generateRandomUrlSafe(64)

    private fun generateRandomUrlSafe(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rng = SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(chars[rng.nextInt(chars.length)])
            }
        }
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
