package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.SpotifyAuthManager
import com.example.service.SpotifyTokenResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app Spotify Account Login dialog.
 * Opens Spotify's official login page, lets the user sign in normally, and automatically
 * captures the session Bearer access token from Spotify Web Player once logged in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyWebLoginDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (SpotifyTokenResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Sign in to your Spotify account to auto-capture token") }
    var capturedOnce by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun handleCaptured(token: String, expiryMs: Long) {
        val clean = token.trim()
        if (clean.length < 20 || capturedOnce) return
        capturedOnce = true
        statusText = "Spotify connected! Capturing profile..."
        scope.launch {
            val label = SpotifyAuthManager.fetchSpotifyProfileLabel(clean)
            onTokenCaptured(
                SpotifyTokenResult(
                    accessToken = clean,
                    expiresAtMs = if (expiryMs > 0L) expiryMs else System.currentTimeMillis() + 3_500_000L,
                    accountLabel = label.ifBlank { "Spotify Account" }
                )
            )
        }
    }

    // Periodically check token endpoint while on open.spotify.com in case SPA navigation doesn't reload page
    LaunchedEffect(capturedOnce, webViewRef) {
        while (!capturedOnce) {
            delay(1500)
            val wv = webViewRef ?: continue
            val currentUrl = wv.url.orEmpty()
            if (currentUrl.startsWith("https://open.spotify.com")) {
                wv.evaluateJavascript(TOKEN_CAPTURE_JS, null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Exception) {
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF101216),
            modifier = Modifier
                .fillMaxSize()
                .testTag("spotify_web_login_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF16181E))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (capturedOnce || isLoading) {
                            CircularProgressIndicator(
                                color = Color(0xFF1DB954),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                text = "Spotify Account Login",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = statusText,
                                color = Color.White.copy(alpha = 0.68f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_spotify_login_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Spotify Login",
                            tint = Color.White
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        color = Color(0xFF1DB954),
                        trackColor = Color(0xFF22252E),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                webViewRef = this
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    userAgentString =
                                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                }

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onTokenFound(token: String?, expiryMs: Long) {
                                            if (!token.isNullOrBlank()) {
                                                post { handleCaptured(token, expiryMs) }
                                            }
                                        }
                                    },
                                    "EchoSnapAuthBridge"
                                )

                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        isLoading = true
                                        if (url != null && url.startsWith("https://open.spotify.com")) {
                                            statusText = "Logged in — grabbing Spotify token..."
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        CookieManager.getInstance().flush()
                                        if (url != null && url.startsWith("https://open.spotify.com")) {
                                            statusText = "Syncing Spotify session token..."
                                            view?.evaluateJavascript(TOKEN_CAPTURE_JS, null)
                                        }
                                    }

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString().orEmpty()
                                        if (reqUrl.contains(".spotify.com/")) {
                                            val auth = request?.requestHeaders?.entries
                                                ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                                ?.value
                                            if (!auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
                                                val token = auth.substring(7).trim()
                                                if (token.length >= 20) {
                                                    post { handleCaptured(token, 0L) }
                                                }
                                            }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                }

                                loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                            }
                        }
                    )
                }
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
    var sessionEl = document.getElementById('session') || document.getElementById('appServerConfig');
    if (sessionEl && sessionEl.textContent) {
      try {
        var parsed = JSON.parse(sessionEl.textContent);
        if (parsed && parsed.accessToken && sendTok(parsed.accessToken, parsed.accessTokenExpirationTimestampMs)) {
          return;
        }
      } catch (e) {}
    }
    fetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player', { credentials: 'include' })
      .then(function(r) { return r.ok ? r.json() : null; })
      .then(function(data) {
        if (data && data.accessToken && !data.isAnonymous) {
          sendTok(data.accessToken, data.accessTokenExpirationTimestampMs);
        }
      })
      .catch(function() {});
  } catch (e) {}
})();
"""
