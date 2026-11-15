package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class SpotifyTokenResult(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val accountLabel: String = ""
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
            .commit()

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

    fun clearSpotifyWebCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {
        }
    }

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
