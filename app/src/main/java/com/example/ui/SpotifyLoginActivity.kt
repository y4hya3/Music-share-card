package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.repository.MediaNotificationRepository
import com.example.service.SpotifyAuthManager
import com.example.service.SpotifyTokenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated full-screen WebView Activity for Spotify Account Login.
 *
 * Hosted in its own ComponentActivity (rather than a Compose Dialog inside ModalBottomSheet)
 * so Android WebView hardware/software surface compositing, soft keyboard input (adjustResize),
 * and Chrome/WebView cookies work reliably across all devices without black screen issues.
 */
class SpotifyLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var errorBanner: LinearLayout
    private lateinit var errorMessageView: TextView

    private val capturedOnce = AtomicBoolean(false)
    private val cookieExchangeInFlight = AtomicBoolean(false)
    private var cookiePollingJob: Job? = null
    private var browserUserAgent: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF16181E.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF121212.toInt())
        )

        val root = buildContentView()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        configureAndLoadWebView()
        startCookieAndSessionPoller()
    }

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF16181E.toInt())
            val padH = dp(12)
            val padV = dp(10)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = pillBg(0xFF262A34.toInt())
            minimumWidth = dp(44)
            minimumHeight = dp(44)
            contentDescription = "Close Spotify Login"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = "Spotify Account Login"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
        }

        statusTextView = TextView(this).apply {
            text = "Loading Spotify sign-in page..."
            setTextColor(0xFFB3B8C4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }
        titleCol.addView(titleView)
        titleCol.addView(statusTextView)

        val reloadBtn = Button(this).apply {
            text = "Reload"
            isAllCaps = false
            setTextColor(0xFF1DB954.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            background = pillBg(0xFF1F2D24.toInt())
            minimumHeight = dp(40)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                errorBanner.visibility = View.GONE
                loadingOverlay.visibility = View.VISIBLE
                webView?.reload()
            }
        }

        header.addView(closeBtn)
        header.addView(titleCol)
        header.addView(reloadBtn)
        root.addView(header)

        // Horizontal Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF1DB954.toInt())
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF1DB954.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            )
        }
        root.addView(progressBar)

        // Error Banner (shown only on network/load errors)
        errorBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF3A1E22.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        errorMessageView = TextView(this).apply {
            setTextColor(0xFFFFB4BC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val retryBtn = Button(this).apply {
            text = "Retry"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pillBg(0xFFFF2B44.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                errorBanner.visibility = View.GONE
                loadingOverlay.visibility = View.VISIBLE
                webView?.loadUrl(SPOTIFY_LOGIN_URL)
            }
        }
        errorBanner.addView(errorMessageView)
        errorBanner.addView(retryBtn)
        root.addView(errorBanner)

        // WebView + Loading Overlay Container
        val webContainer = FrameLayout(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF121212.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        webView = wv
        webContainer.addView(wv)

        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
        }
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF1DB954.toInt())
        }
        val loadingLabel = TextView(this).apply {
            text = "Connecting to Spotify Login..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        val loadingHint = TextView(this).apply {
            text = "Sign in to automatically sync your Canvas token"
            setTextColor(0xFFA0A6B4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(24), dp(6), dp(24), 0)
            gravity = Gravity.CENTER
        }
        loadingOverlay.addView(spinner)
        loadingOverlay.addView(loadingLabel)
        loadingOverlay.addView(loadingHint)
        webContainer.addView(loadingOverlay)

        root.addView(webContainer)
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureAndLoadWebView() {
        val wv = webView ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        val rawDefaultUa = try {
            WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        browserUserAgent = normalizeBrowserUserAgent(rawDefaultUa)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = browserUserAgent
        }

        wv.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onTokenFound(token: String?, expiryMs: Long) {
                    if (!token.isNullOrBlank()) {
                        runOnUiThread { handleCapturedToken(token, expiryMs) }
                    }
                }
            },
            "EchoSnapAuthBridge"
        )

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                val url = view?.url.orEmpty()
                if (newProgress >= 70 && !isPostLoginCaptureUrl(url)) {
                    loadingOverlay.visibility = View.GONE
                }
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // Handle intent:// links cleanly without leaving WebView for Spotify native app
                if (url.startsWith("intent://")) {
                    val fallbackPrefix = "browser_fallback_url="
                    val idx = url.indexOf(fallbackPrefix)
                    if (idx >= 0) {
                        val rest = url.substring(idx + fallbackPrefix.length)
                        val end = rest.indexOf(';').let { if (it >= 0) it else rest.length }
                        val decoded = android.net.Uri.decode(rest.substring(0, end))
                        if (decoded.startsWith("http")) {
                            view?.loadUrl(decoded)
                        }
                    }
                    return true
                }
                // Block spotify:// app redirect so login completes in-app
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                errorBanner.visibility = View.GONE
                view?.evaluateJavascript("try { Object.defineProperty(navigator, 'webdriver', { get: function() { return undefined; } }); } catch(e) {}", null)
                view?.evaluateJavascript(TOKEN_CAPTURE_JS, null)
                if (url != null) {
                    when {
                        url.contains("accounts.spotify.com") && url.contains("/status") -> {
                            statusTextView.text = "Signed in! Capturing Spotify token..."
                            loadingOverlay.visibility = View.VISIBLE
                            tryFetchTokenViaCookies()
                        }
                        url.contains("open.spotify.com") -> {
                            statusTextView.text = "Signed in! Syncing Spotify token..."
                            loadingOverlay.visibility = View.VISIBLE
                            tryFetchTokenViaCookies()
                        }
                        url.contains("accounts.spotify.com") -> {
                            loadingOverlay.visibility = View.GONE
                            statusTextView.text = "Sign in to your Spotify account"
                        }
                        else -> {
                            statusTextView.text = "Verifying login..."
                        }
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (isPostLoginCaptureUrl(url)) {
                    loadingOverlay.visibility = View.VISIBLE
                    view?.evaluateJavascript(TOKEN_CAPTURE_JS, null)
                    tryFetchTokenViaCookies()
                } else {
                    loadingOverlay.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                if (isPostLoginCaptureUrl(url)) {
                    loadingOverlay.visibility = View.VISIBLE
                    statusTextView.text = "Capturing Spotify session token..."
                    view?.evaluateJavascript(TOKEN_CAPTURE_JS, null)
                    tryFetchTokenViaCookies()
                } else {
                    loadingOverlay.visibility = View.GONE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    loadingOverlay.visibility = View.GONE
                    val desc = error?.description?.toString()?.ifBlank { "Network error" } ?: "Network error"
                    statusTextView.text = "Failed to load Spotify login"
                    errorMessageView.text = "Cannot load Spotify login ($desc). Check internet connection."
                    errorBanner.visibility = View.VISIBLE
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                loadingOverlay.visibility = View.GONE
                errorMessageView.text = "WebView renderer restarted. Tap Retry."
                errorBanner.visibility = View.VISIBLE
                return true
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
                        SpotifyAuthManager.latestCapturedClientToken = ct.trim()
                    }
                    val auth = headers?.entries
                        ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                        ?.value
                    if (!auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
                        val token = auth.substring(7).trim()
                        if (token.length >= 20) {
                            runOnUiThread { handleCapturedToken(token, 0L) }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.loadUrl(SPOTIFY_LOGIN_URL)
    }

    private fun isPostLoginCaptureUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return (url.contains("accounts.spotify.com") && url.contains("/status")) ||
            url.contains("open.spotify.com")
    }

    private fun startCookieAndSessionPoller() {
        cookiePollingJob?.cancel()
        cookiePollingJob = lifecycleScope.launch {
            var postLoginChecks = 0
            var navigatedToSilentWebPlayer = false
            while (isActive && !capturedOnce.get()) {
                delay(350)
                val wv = webView ?: continue
                val currentUrl = wv.url.orEmpty()
                val cookiesOpen = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                val cookiesAccounts = CookieManager.getInstance().getCookie("https://accounts.spotify.com").orEmpty()
                val hasSessionCookie = cookiesOpen.contains("sp_dc=") || cookiesAccounts.contains("sp_dc=")

                if (hasSessionCookie || isPostLoginCaptureUrl(currentUrl)) {
                    postLoginChecks++
                    loadingOverlay.visibility = View.VISIBLE
                    tryFetchTokenViaCookies()
                    if (currentUrl.startsWith("https://open.spotify.com")) {
                        wv.evaluateJavascript(TOKEN_CAPTURE_JS, null)
                    }
                    // After ~1.8s, if TOTP HTTP exchange hasn't already completed, load open.spotify.com
                    // with a Desktop Chrome UA behind the overlay so Spotify's own bundle mints /api/token
                    if (!navigatedToSilentWebPlayer && postLoginChecks >= 5) {
                        navigatedToSilentWebPlayer = true
                        wv.settings.userAgentString = SpotifyAuthManager.DESKTOP_BROWSER_UA
                        wv.loadUrl("https://open.spotify.com/")
                    } else if (postLoginChecks >= 36 && hasSessionCookie) {
                        // Never hang indefinitely on the spinner: save captured sp_dc & finish cleanly
                        val spDc = extractSpDcFromCookies()
                        if (spDc.isNotBlank()) {
                            handleCapturedSpDcOnly(spDc)
                        }
                    }
                }
            }
        }
    }

    private fun extractSpDcFromCookies(): String {
        val cm = CookieManager.getInstance()
        val domains = listOf(
            "https://open.spotify.com",
            "https://accounts.spotify.com",
            "https://spotify.com"
        )
        for (domain in domains) {
            val spDc = SpotifyAuthManager.extractSpDcFromCookieHeader(cm.getCookie(domain))
            if (spDc.isNotBlank()) return spDc
        }
        return ""
    }

    private fun tryFetchTokenViaCookies() {
        if (capturedOnce.get()) return
        val spDc = extractSpDcFromCookies()
        if (spDc.isBlank()) return
        if (!cookieExchangeInFlight.compareAndSet(false, true)) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val exchanged = SpotifyAuthManager.exchangeSpDcForAccessToken(
                    rawSpDc = spDc,
                    userAgent = browserUserAgent
                )
                if (exchanged != null) {
                    withContext(Dispatchers.Main) {
                        handleCapturedToken(exchanged.accessToken, exchanged.expiresAtMs, spDc)
                    }
                }
            } finally {
                cookieExchangeInFlight.set(false)
            }
        }
    }

    private fun handleCapturedSpDcOnly(spDc: String) {
        if (!capturedOnce.compareAndSet(false, true)) return
        cookiePollingJob?.cancel()
        MediaNotificationRepository.applySpotifyLoginResult(
            SpotifyTokenResult(
                accessToken = "",
                expiresAtMs = 0L,
                accountLabel = "Spotify (sp_dc)",
                spDcCookie = spDc
            )
        )
        Toast.makeText(
            this@SpotifyLoginActivity,
            "Spotify sp_dc session saved!",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun handleCapturedToken(
        token: String,
        expiryMs: Long,
        capturedSpDc: String = extractSpDcFromCookies()
    ) {
        val clean = token.trim()
        if (clean.length < 20) return
        if (!capturedOnce.compareAndSet(false, true)) return

        cookiePollingJob?.cancel()
        statusTextView.text = "Connected! Saving Spotify session..."

        lifecycleScope.launch {
            val label = SpotifyAuthManager.fetchSpotifyProfileLabel(clean).ifBlank { "Spotify Account" }
            val finalSpDc = capturedSpDc.ifBlank { extractSpDcFromCookies() }
            MediaNotificationRepository.applySpotifyLoginResult(
                SpotifyTokenResult(
                    accessToken = clean,
                    expiresAtMs = if (expiryMs > 0L) expiryMs else System.currentTimeMillis() + 3_500_000L,
                    accountLabel = label,
                    spDcCookie = finalSpDc
                )
            )
            Toast.makeText(
                this@SpotifyLoginActivity,
                "Spotify connected ($label)",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cookiePollingJob?.cancel()
        try {
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun pillBg(colorInt: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(colorInt)
            cornerRadius = dp(10).toFloat()
        }

    companion object {
        private const val SPOTIFY_LOGIN_URL =
            "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Faccounts.spotify.com%2Fen%2Fstatus"
        private const val SPOTIFY_TOKEN_ENDPOINT =
            "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"

        fun normalizeBrowserUserAgent(rawUa: String): String {
            var ua = rawUa.replace("; wv", "").replace(";wv", "")
            val chromeRegex = "Chrome/(\\d+)\\.\\d+\\.\\d+\\.\\d+".toRegex()
            val match = chromeRegex.find(ua)
            val major = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (major == null || major < 126) {
                ua = if (match != null) {
                    ua.replaceRange(match.range, "Chrome/131.0.0.0")
                } else {
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                }
            }
            return ua
        }

        fun createIntent(context: Context): Intent =
            Intent(context, SpotifyLoginActivity::class.java).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
    }
}

private const val TOKEN_CAPTURE_JS = """
(function() {
  try {
    function sendTok(tok, exp) {
      if (tok && tok.length > 20 && window.EchoSnapAuthBridge) {
        window.EchoSnapAuthBridge.onTokenFound(String(tok), Number(exp || 0));
        return true;
      }
      return false;
    }
    var bodyText = (document.body && document.body.innerText) ? document.body.innerText.trim() : '';
    if (bodyText.indexOf('"accessToken"') >= 0) {
      try {
        var jsonBody = JSON.parse(bodyText);
        if (jsonBody && jsonBody.accessToken && !jsonBody.isAnonymous &&
            sendTok(jsonBody.accessToken, jsonBody.accessTokenExpirationTimestampMs)) {
          return;
        }
      } catch (e) {}
    }
    var sessionEl = document.getElementById('session') || document.getElementById('appServerConfig');
    if (sessionEl && sessionEl.textContent) {
      try {
        var parsed = JSON.parse(sessionEl.textContent);
        if (parsed && parsed.accessToken && !parsed.isAnonymous &&
            sendTok(parsed.accessToken, parsed.accessTokenExpirationTimestampMs)) {
          return;
        }
      } catch (e) {}
    }
    if (!window.__echoSnapHookedFetch && window.fetch) {
      window.__echoSnapHookedFetch = true;
      var origFetch = window.fetch;
      window.fetch = function() {
        var args = arguments;
        try {
          var init = args[1];
          if (init && init.headers) {
            var h = init.headers;
            var auth = (typeof h.get === 'function') ? h.get('Authorization') : (h['Authorization'] || h['authorization']);
            if (auth && String(auth).indexOf('Bearer ') === 0) {
              sendTok(String(auth).substring(7), 0);
            }
          }
        } catch (e) {}
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
