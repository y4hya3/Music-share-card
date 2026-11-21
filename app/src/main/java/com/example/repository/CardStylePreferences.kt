package com.example.repository

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardShadowColorMode
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LiveWallpaperArtworkFillMode
import com.example.model.LiveWallpaperCornerStyle
import com.example.model.LiveWallpaperMode
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
    private const val KEY_USE_TRANSPARENT_BG = "use_transparent_bg"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_FLOATING_GLASS_BLUR = "floating_glass_blur_radius"
    private const val KEY_CARD_OPACITY = "card_opacity"
    private const val KEY_LYRICS_ART_BLUR = "lyrics_art_blur_radius"
    private const val KEY_DARK_OVERLAY_ALPHA = "dark_overlay_alpha"
    private const val KEY_GLOW_INTENSITY = "glow_intensity"
    private const val KEY_SHADOW_COLOR_MODE = "card_shadow_color_mode"
    private const val KEY_CUSTOM_SHADOW_COLOR = "custom_shadow_color"
    private const val KEY_SHADOW_INVERSE = "card_shadow_inverse"
    private const val KEY_CORNER_RADIUS_DP = "corner_radius_dp"
    private const val KEY_CARD_SCALE = "card_scale"
    private const val KEY_FIT_TO_SCREEN_PREVIEW = "fit_to_screen_preview"
    private const val KEY_ENABLE_WAVEFORM = "enable_waveform"
    private const val KEY_WAVEFORM_COLOR = "waveform_color"
    private const val KEY_WAVEFORM_BEAT_SYNC = "waveform_beat_sync"
    private const val KEY_WAVEFORM_SPEED = "waveform_speed_multiplier"
    private const val KEY_WAVEFORM_FORCE = "waveform_force"
    private const val KEY_LYRICS_WORD_SELECTION = "lyrics_word_selection_mode"
    private const val KEY_SHOW_WORDMARK = "show_wordmark"
    private const val KEY_AUTO_SEARCH_ONLINE_ART = "auto_search_online_art"
    private const val KEY_CUSTOM_LYRICS = "custom_lyrics"
    private const val KEY_LYRICS_BG_MODE = "lyrics_bg_mode"
    private const val KEY_AUTO_SEARCH_CANVAS = "auto_search_canvas"
    private const val KEY_LIVE_WALLPAPER_ENABLED = "live_wallpaper_enabled"
    private const val KEY_LIVE_WALLPAPER_MODE = "live_wallpaper_mode"
    private const val KEY_LIVE_WALLPAPER_ART_FILL_MODE = "live_wallpaper_art_fill_mode"
    private const val KEY_LIVE_WALLPAPER_CORNER_STYLE = "live_wallpaper_corner_style"
    private const val KEY_LIVE_WALLPAPER_BLUR = "live_wallpaper_blur_radius"
    private const val KEY_LIVE_WALLPAPER_EDGE_BLUR = "live_wallpaper_edge_blur_fade"
    private const val KEY_LIVE_WALLPAPER_SHOW_CARD = "live_wallpaper_show_card"
    private const val KEY_LIVE_WALLPAPER_IDLE_REVERT_ENABLED = "live_wallpaper_idle_revert_enabled"
    private const val KEY_LIVE_WALLPAPER_IDLE_DELAY_SEC = "live_wallpaper_idle_delay_sec"
    private const val KEY_LIVE_WALLPAPER_IDLE_PHOTO_PATH = "live_wallpaper_idle_photo_path"
    private const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
    private const val KEY_SPOTIFY_SP_DC_COOKIE = "spotify_sp_dc_cookie"
    private const val KEY_SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
    private const val KEY_SPOTIFY_TOKEN_EXPIRY_MS = "spotify_token_expiry_ms"
    private const val KEY_SPOTIFY_TOKEN_LAST_REFRESHED_MS = "spotify_token_last_refreshed_ms"
    private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_ACCOUNT_LABEL = "spotify_account_label"

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
        val liveWallpaperMode = enumValueOrDefault(
            prefs.getString(KEY_LIVE_WALLPAPER_MODE, defaults.liveWallpaperMode.name),
            defaults.liveWallpaperMode
        )
        val liveWallpaperArtworkFillMode = enumValueOrDefault(
            prefs.getString(KEY_LIVE_WALLPAPER_ART_FILL_MODE, defaults.liveWallpaperArtworkFillMode.name),
            defaults.liveWallpaperArtworkFillMode
        )
        val liveWallpaperCornerStyle = enumValueOrDefault(
            prefs.getString(KEY_LIVE_WALLPAPER_CORNER_STYLE, defaults.liveWallpaperCornerStyle.name),
            defaults.liveWallpaperCornerStyle
        )
        val shadowColorMode = enumValueOrDefault(
            prefs.getString(KEY_SHADOW_COLOR_MODE, defaults.cardShadowColorMode.name),
            defaults.cardShadowColorMode
        )

        val customColorArgb = prefs.getInt(KEY_CUSTOM_LOGO_COLOR, defaults.customLogoColor.toArgb())
        val customShadowColorArgb = prefs.getInt(KEY_CUSTOM_SHADOW_COLOR, defaults.customShadowColor.toArgb())
        val waveformColorArgb = prefs.getInt(KEY_WAVEFORM_COLOR, defaults.waveformColor.toArgb())

        return CardStyleConfig(
            brandMode = brandMode,
            logoColorMode = logoColorMode,
            customLogoColor = Color(customColorArgb),
            gradientStyle = gradientStyle,
            cardLayoutType = cardLayoutType,
            lyricsCardBackgroundMode = lyricsBgMode,
            useBlurredAlbumBackground = prefs.getBoolean(KEY_USE_BLURRED_BG, defaults.useBlurredAlbumBackground),
            useTransparentBackground = prefs.getBoolean(KEY_USE_TRANSPARENT_BG, defaults.useTransparentBackground),
            backgroundBlurRadius = prefs.getFloat(KEY_BLUR_RADIUS, defaults.backgroundBlurRadius).coerceIn(0f, 40f),
            cardBlurRadius = prefs.getFloat(KEY_FLOATING_GLASS_BLUR, defaults.cardBlurRadius).coerceIn(0f, 40f),
            cardOpacity = prefs.getFloat(KEY_CARD_OPACITY, defaults.cardOpacity).coerceIn(0.0f, 1.0f),
            lyricsArtBlurRadius = prefs.getFloat(KEY_LYRICS_ART_BLUR, defaults.lyricsArtBlurRadius).coerceIn(0f, 40f),
            darkOverlayAlpha = prefs.getFloat(KEY_DARK_OVERLAY_ALPHA, defaults.darkOverlayAlpha).coerceIn(0f, 0.7f),
            glowIntensity = prefs.getFloat(KEY_GLOW_INTENSITY, defaults.glowIntensity).coerceIn(0f, 1f),
            cardShadowColorMode = shadowColorMode,
            customShadowColor = Color(customShadowColorArgb),
            cardShadowInverse = prefs.getBoolean(KEY_SHADOW_INVERSE, defaults.cardShadowInverse),
            cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS_DP, defaults.cornerRadiusDp).coerceIn(0, 32),
            cardScale = prefs.getFloat(KEY_CARD_SCALE, defaults.cardScale).coerceIn(0.60f, 1.20f),
            isFitToScreenPreview = prefs.getBoolean(KEY_FIT_TO_SCREEN_PREVIEW, defaults.isFitToScreenPreview),
            enableWaveform = prefs.getBoolean(KEY_ENABLE_WAVEFORM, defaults.enableWaveform),
            waveformColor = Color(waveformColorArgb),
            waveformBeatSync = prefs.getBoolean(KEY_WAVEFORM_BEAT_SYNC, defaults.waveformBeatSync),
            waveformSpeedMultiplier = prefs.getFloat(KEY_WAVEFORM_SPEED, defaults.waveformSpeedMultiplier).coerceIn(0.4f, 2.5f),
            waveformForce = prefs.getFloat(KEY_WAVEFORM_FORCE, defaults.waveformForce).coerceIn(0.5f, 2.2f),
            lyricsWordSelectionMode = prefs.getBoolean(KEY_LYRICS_WORD_SELECTION, defaults.lyricsWordSelectionMode),
            showLogoWordmark = prefs.getBoolean(KEY_SHOW_WORDMARK, defaults.showLogoWordmark),
            autoSearchOnlineArt = prefs.getBoolean(KEY_AUTO_SEARCH_ONLINE_ART, defaults.autoSearchOnlineArt),
            autoSearchAnimatedCanvas = prefs.getBoolean(KEY_AUTO_SEARCH_CANVAS, defaults.autoSearchAnimatedCanvas),
            liveWallpaperEnabled = prefs.getBoolean(KEY_LIVE_WALLPAPER_ENABLED, defaults.liveWallpaperEnabled),
            liveWallpaperMode = liveWallpaperMode,
            liveWallpaperArtworkFillMode = liveWallpaperArtworkFillMode,
            liveWallpaperCornerStyle = liveWallpaperCornerStyle,
            liveWallpaperBlurRadius = prefs.getFloat(KEY_LIVE_WALLPAPER_BLUR, defaults.liveWallpaperBlurRadius).coerceIn(0f, 40f),
            liveWallpaperEdgeBlurFade = prefs.getFloat(KEY_LIVE_WALLPAPER_EDGE_BLUR, defaults.liveWallpaperEdgeBlurFade).coerceIn(0f, 1f),
            liveWallpaperShowCard = prefs.getBoolean(KEY_LIVE_WALLPAPER_SHOW_CARD, defaults.liveWallpaperShowCard),
            liveWallpaperIdleRevertEnabled = prefs.getBoolean(
                KEY_LIVE_WALLPAPER_IDLE_REVERT_ENABLED,
                defaults.liveWallpaperIdleRevertEnabled
            ),
            liveWallpaperIdleDelaySec = prefs.getInt(
                KEY_LIVE_WALLPAPER_IDLE_DELAY_SEC,
                defaults.liveWallpaperIdleDelaySec
            ).coerceIn(1, 86_400),
            liveWallpaperIdlePhotoPath = prefs.getString(
                KEY_LIVE_WALLPAPER_IDLE_PHOTO_PATH,
                defaults.liveWallpaperIdlePhotoPath
            ) ?: "",
            spotifyAccessToken = prefs.getString(KEY_SPOTIFY_ACCESS_TOKEN, defaults.spotifyAccessToken) ?: "",
            spotifySpDcCookie = prefs.getString(KEY_SPOTIFY_SP_DC_COOKIE, defaults.spotifySpDcCookie) ?: "",
            spotifyRefreshToken = prefs.getString(KEY_SPOTIFY_REFRESH_TOKEN, defaults.spotifyRefreshToken) ?: "",
            spotifyTokenExpiryMs = prefs.getLong(KEY_SPOTIFY_TOKEN_EXPIRY_MS, defaults.spotifyTokenExpiryMs),
            spotifyTokenLastRefreshedMs = prefs.getLong(KEY_SPOTIFY_TOKEN_LAST_REFRESHED_MS, defaults.spotifyTokenLastRefreshedMs),
            spotifyClientId = prefs.getString(KEY_SPOTIFY_CLIENT_ID, defaults.spotifyClientId) ?: "",
            spotifyAccountLabel = prefs.getString(KEY_SPOTIFY_ACCOUNT_LABEL, defaults.spotifyAccountLabel) ?: "",
            customLyrics = prefs.getString(KEY_CUSTOM_LYRICS, defaults.customLyrics) ?: ""
        )
    }

    fun save(context: Context, config: CardStyleConfig) {
        buildEditor(context, config).commit()
    }

    fun saveAsync(context: Context, config: CardStyleConfig) {
        buildEditor(context, config).apply()
    }

    private fun buildEditor(context: Context, config: CardStyleConfig) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRAND_MODE, config.brandMode.name)
            .putString(KEY_LOGO_COLOR_MODE, config.logoColorMode.name)
            .putInt(KEY_CUSTOM_LOGO_COLOR, config.customLogoColor.toArgb())
            .putString(KEY_GRADIENT_STYLE, config.gradientStyle.name)
            .putString(KEY_CARD_LAYOUT_TYPE, config.cardLayoutType.name)
            .putString(KEY_LYRICS_BG_MODE, config.lyricsCardBackgroundMode.name)
            .putBoolean(KEY_USE_BLURRED_BG, config.useBlurredAlbumBackground)
            .putBoolean(KEY_USE_TRANSPARENT_BG, config.useTransparentBackground)
            .putFloat(KEY_BLUR_RADIUS, config.backgroundBlurRadius.coerceIn(0f, 40f))
            .putFloat(KEY_FLOATING_GLASS_BLUR, config.cardBlurRadius.coerceIn(0f, 40f))
            .putFloat(KEY_CARD_OPACITY, config.cardOpacity.coerceIn(0.0f, 1.0f))
            .putFloat(KEY_LYRICS_ART_BLUR, config.lyricsArtBlurRadius.coerceIn(0f, 40f))
            .putFloat(KEY_DARK_OVERLAY_ALPHA, config.darkOverlayAlpha.coerceIn(0f, 0.7f))
            .putFloat(KEY_GLOW_INTENSITY, config.glowIntensity.coerceIn(0f, 1f))
            .putString(KEY_SHADOW_COLOR_MODE, config.cardShadowColorMode.name)
            .putInt(KEY_CUSTOM_SHADOW_COLOR, config.customShadowColor.toArgb())
            .putBoolean(KEY_SHADOW_INVERSE, config.cardShadowInverse)
            .putInt(KEY_CORNER_RADIUS_DP, config.cornerRadiusDp.coerceIn(0, 32))
            .putFloat(KEY_CARD_SCALE, config.cardScale.coerceIn(0.60f, 1.20f))
            .putBoolean(KEY_FIT_TO_SCREEN_PREVIEW, config.isFitToScreenPreview)
            .putBoolean(KEY_ENABLE_WAVEFORM, config.enableWaveform)
            .putInt(KEY_WAVEFORM_COLOR, config.waveformColor.toArgb())
            .putBoolean(KEY_WAVEFORM_BEAT_SYNC, config.waveformBeatSync)
            .putFloat(KEY_WAVEFORM_SPEED, config.waveformSpeedMultiplier.coerceIn(0.4f, 2.5f))
            .putFloat(KEY_WAVEFORM_FORCE, config.waveformForce.coerceIn(0.5f, 2.2f))
            .putBoolean(KEY_LYRICS_WORD_SELECTION, config.lyricsWordSelectionMode)
            .putBoolean(KEY_SHOW_WORDMARK, config.showLogoWordmark)
            .putBoolean(KEY_AUTO_SEARCH_ONLINE_ART, config.autoSearchOnlineArt)
            .putBoolean(KEY_AUTO_SEARCH_CANVAS, config.autoSearchAnimatedCanvas)
            .putBoolean(KEY_LIVE_WALLPAPER_ENABLED, config.liveWallpaperEnabled)
            .putString(KEY_LIVE_WALLPAPER_MODE, config.liveWallpaperMode.name)
            .putString(KEY_LIVE_WALLPAPER_ART_FILL_MODE, config.liveWallpaperArtworkFillMode.name)
            .putString(KEY_LIVE_WALLPAPER_CORNER_STYLE, config.liveWallpaperCornerStyle.name)
            .putFloat(KEY_LIVE_WALLPAPER_BLUR, config.liveWallpaperBlurRadius.coerceIn(0f, 40f))
            .putFloat(KEY_LIVE_WALLPAPER_EDGE_BLUR, config.liveWallpaperEdgeBlurFade.coerceIn(0f, 1f))
            .putBoolean(KEY_LIVE_WALLPAPER_SHOW_CARD, config.liveWallpaperShowCard)
            .putBoolean(KEY_LIVE_WALLPAPER_IDLE_REVERT_ENABLED, config.liveWallpaperIdleRevertEnabled)
            .putInt(KEY_LIVE_WALLPAPER_IDLE_DELAY_SEC, config.liveWallpaperIdleDelaySec.coerceIn(1, 86_400))
            .putString(KEY_LIVE_WALLPAPER_IDLE_PHOTO_PATH, config.liveWallpaperIdlePhotoPath)
            .putString(KEY_SPOTIFY_ACCESS_TOKEN, config.spotifyAccessToken)
            .putString(KEY_SPOTIFY_SP_DC_COOKIE, config.spotifySpDcCookie)
            .putString(KEY_SPOTIFY_REFRESH_TOKEN, config.spotifyRefreshToken)
            .putLong(KEY_SPOTIFY_TOKEN_EXPIRY_MS, config.spotifyTokenExpiryMs)
            .putLong(KEY_SPOTIFY_TOKEN_LAST_REFRESHED_MS, config.spotifyTokenLastRefreshedMs)
            .putString(KEY_SPOTIFY_CLIENT_ID, config.spotifyClientId)
            .putString(KEY_SPOTIFY_ACCOUNT_LABEL, config.spotifyAccountLabel)
            .putString(KEY_CUSTOM_LYRICS, config.customLyrics)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return try {
            enumValueOf<T>(raw)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
