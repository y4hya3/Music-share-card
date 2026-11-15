package com.example.repository

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode

/**
 * Persists user CardStyleConfig customizations across song changes, different music players,
 * and full app restarts.
 */
object CardStylePreferences {

    private const val PREFS_NAME = "bitchord_card_style_prefs"

    private const val KEY_BRAND_MODE = "brand_mode"
    private const val KEY_LOGO_COLOR_MODE = "logo_color_mode"
    private const val KEY_CUSTOM_LOGO_COLOR = "custom_logo_color"
    private const val KEY_GRADIENT_STYLE = "gradient_style"
    private const val KEY_CARD_LAYOUT_TYPE = "card_layout_type"
    private const val KEY_USE_BLURRED_BG = "use_blurred_bg"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_DARK_OVERLAY_ALPHA = "dark_overlay_alpha"
    private const val KEY_GLOW_INTENSITY = "glow_intensity"
    private const val KEY_CORNER_RADIUS_DP = "corner_radius_dp"
    private const val KEY_CARD_SCALE = "card_scale"
    private const val KEY_SHOW_WORDMARK = "show_wordmark"
    private const val KEY_AUTO_SEARCH_ONLINE_ART = "auto_search_online_art"
    private const val KEY_CUSTOM_LYRICS = "custom_lyrics"
    private const val KEY_LYRICS_BG_MODE = "lyrics_bg_mode"
    private const val KEY_AUTO_SEARCH_CANVAS = "auto_search_canvas"
    private const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"

    fun load(context: Context): CardStyleConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = CardStyleConfig()

        val brandMode = enumValueOrDefault(
            prefs.getString(KEY_BRAND_MODE, defaults.brandMode.name),
            defaults.brandMode
        )
        val logoColorMode = enumValueOrDefault(
            prefs.getString(KEY_LOGO_COLOR_MODE, defaults.logoColorMode.name),
            defaults.logoColorMode
        )
        val gradientStyle = enumValueOrDefault(
            prefs.getString(KEY_GRADIENT_STYLE, defaults.gradientStyle.name),
            defaults.gradientStyle
        )
        val cardLayoutType = enumValueOrDefault(
            prefs.getString(KEY_CARD_LAYOUT_TYPE, defaults.cardLayoutType.name),
            defaults.cardLayoutType
        )
        val lyricsBgMode = enumValueOrDefault(
            prefs.getString(KEY_LYRICS_BG_MODE, defaults.lyricsCardBackgroundMode.name),
            defaults.lyricsCardBackgroundMode
        )

        val customColorArgb = prefs.getInt(KEY_CUSTOM_LOGO_COLOR, defaults.customLogoColor.toArgb())

        return CardStyleConfig(
            brandMode = brandMode,
            logoColorMode = logoColorMode,
            customLogoColor = Color(customColorArgb),
            gradientStyle = gradientStyle,
            cardLayoutType = cardLayoutType,
            lyricsCardBackgroundMode = lyricsBgMode,
            useBlurredAlbumBackground = prefs.getBoolean(KEY_USE_BLURRED_BG, defaults.useBlurredAlbumBackground),
            backgroundBlurRadius = prefs.getFloat(KEY_BLUR_RADIUS, defaults.backgroundBlurRadius).coerceIn(0f, 40f),
            darkOverlayAlpha = prefs.getFloat(KEY_DARK_OVERLAY_ALPHA, defaults.darkOverlayAlpha).coerceIn(0f, 0.7f),
            glowIntensity = prefs.getFloat(KEY_GLOW_INTENSITY, defaults.glowIntensity).coerceIn(0f, 1f),
            cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS_DP, defaults.cornerRadiusDp).coerceIn(0, 32),
            cardScale = prefs.getFloat(KEY_CARD_SCALE, defaults.cardScale).coerceIn(0.75f, 1.15f),
            showLogoWordmark = prefs.getBoolean(KEY_SHOW_WORDMARK, defaults.showLogoWordmark),
            autoSearchOnlineArt = prefs.getBoolean(KEY_AUTO_SEARCH_ONLINE_ART, defaults.autoSearchOnlineArt),
            autoSearchAnimatedCanvas = prefs.getBoolean(KEY_AUTO_SEARCH_CANVAS, defaults.autoSearchAnimatedCanvas),
            spotifyAccessToken = prefs.getString(KEY_SPOTIFY_ACCESS_TOKEN, defaults.spotifyAccessToken) ?: "",
            customLyrics = prefs.getString(KEY_CUSTOM_LYRICS, defaults.customLyrics) ?: ""
        )
    }

    fun save(context: Context, config: CardStyleConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BRAND_MODE, config.brandMode.name)
            .putString(KEY_LOGO_COLOR_MODE, config.logoColorMode.name)
            .putInt(KEY_CUSTOM_LOGO_COLOR, config.customLogoColor.toArgb())
            .putString(KEY_GRADIENT_STYLE, config.gradientStyle.name)
            .putString(KEY_CARD_LAYOUT_TYPE, config.cardLayoutType.name)
            .putString(KEY_LYRICS_BG_MODE, config.lyricsCardBackgroundMode.name)
            .putBoolean(KEY_USE_BLURRED_BG, config.useBlurredAlbumBackground)
            .putFloat(KEY_BLUR_RADIUS, config.backgroundBlurRadius.coerceIn(0f, 40f))
            .putFloat(KEY_DARK_OVERLAY_ALPHA, config.darkOverlayAlpha.coerceIn(0f, 0.7f))
            .putFloat(KEY_GLOW_INTENSITY, config.glowIntensity.coerceIn(0f, 1f))
            .putInt(KEY_CORNER_RADIUS_DP, config.cornerRadiusDp.coerceIn(0, 32))
            .putFloat(KEY_CARD_SCALE, config.cardScale.coerceIn(0.75f, 1.15f))
            .putBoolean(KEY_SHOW_WORDMARK, config.showLogoWordmark)
            .putBoolean(KEY_AUTO_SEARCH_ONLINE_ART, config.autoSearchOnlineArt)
            .putBoolean(KEY_AUTO_SEARCH_CANVAS, config.autoSearchAnimatedCanvas)
            .putString(KEY_SPOTIFY_ACCESS_TOKEN, config.spotifyAccessToken)
            .putString(KEY_CUSTOM_LYRICS, config.customLyrics)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return try {
            enumValueOf<T>(raw)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
