package com.example.model

import androidx.compose.ui.graphics.Color

enum class CardBrandMode(val label: String, val wordmark: String) {
    BITCHORD("Bitchord", "Bitchord"),
    SPOTIFY("Spotify", "Spotify"),
    APPLE_MUSIC("Apple Music", "Apple Music")
}

enum class LogoColorMode(val label: String) {
    RED("Signature Red"),
    TEMPLATE_DYNAMIC("Template Color"),
    GREEN("Green Badge"),
    WHITE("Classic White"),
    CUSTOM("Custom")
}

enum class GradientStyle(val label: String) {
    ADAPTIVE_HARMONY("Adaptive Harmony"),
    VIBRANT_TO_DARK_MUTED("Vibrant → Dark"),
    DOMINANT_TO_DARK_MUTED("Dominant → Dark"),
    LIGHT_VIBRANT_TO_DARK_VIBRANT("Studio Glow"),
    CINEMATIC_AURORA("Cinematic Aurora"),
    DEEP_NIGHT("Deep Dark Mode"),
    SUNSET_GLOW("Warm Sunset")
}

enum class CardLayoutType(val label: String) {
    SPOTIFY_INNER_CARD("Floating Glass Card"),
    FLOATING_ARTWORK("Floating Glow Art"),
    MINIMAL_CLEAN("Minimal Story"),
    SPOTIFY_LYRICS_CARD("Lyrics Card")
}

enum class LyricsCardBackgroundMode(val label: String) {
    PALETTE_COLOR("Palette Color"),
    SONG_ART("Song Cover Art")
}

data class CardStyleConfig(
    val brandMode: CardBrandMode = CardBrandMode.BITCHORD,
    val logoColorMode: LogoColorMode = LogoColorMode.RED,
    val customLogoColor: Color = Color(0xFFFF2B44),
    val gradientStyle: GradientStyle = GradientStyle.ADAPTIVE_HARMONY,
    val cardLayoutType: CardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
    val lyricsCardBackgroundMode: LyricsCardBackgroundMode = LyricsCardBackgroundMode.PALETTE_COLOR,
    val useBlurredAlbumBackground: Boolean = true,
    val backgroundBlurRadius: Float = 24f, // 0f (sharp) to 40f (heavily blurred)
    val darkOverlayAlpha: Float = 0.25f, // rgba(0, 0, 0, 0.25)
    val glowIntensity: Float = 0.75f, // soft drop shadow depth
    val cornerRadiusDp: Int = 16, // border-radius: 16px
    val cardScale: Float = 1.0f, // 0.60f..1.20f unified card size multiplier
    val showLogoWordmark: Boolean = true,
    val autoSearchOnlineArt: Boolean = false,
    val autoSearchAnimatedCanvas: Boolean = false,
    val spotifyAccessToken: String = "",
    val spotifySpDcCookie: String = "",
    val spotifyRefreshToken: String = "",
    val spotifyTokenExpiryMs: Long = 0L,
    val spotifyClientId: String = "",
    val spotifyAccountLabel: String = "",
    val customLyrics: String = ""
) {
    fun resolveLogoBadgeColor(palette: CardPalette): Color {
        return when (logoColorMode) {
            LogoColorMode.RED -> Color(0xFFFF2B44)
            LogoColorMode.TEMPLATE_DYNAMIC -> palette.vibrantColor
            LogoColorMode.GREEN -> Color(0xFF1DB954)
            LogoColorMode.WHITE -> Color.White
            LogoColorMode.CUSTOM -> customLogoColor
        }
    }

    fun resolveLyricsDisplayLines(track: MediaTrackInfo): String {
        val trimmed = customLyrics.trim()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return when {
            track.title.contains("SICKO MODE", ignoreCase = true) ->
                "Sun is down, freezin' cold\nThat's how we already know\nWinter's here"
            track.title.contains("Blinding Lights", ignoreCase = true) ->
                "I said, ooh, I'm blinded by the lights\nNo, I can't sleep until I feel your touch"
            track.title.contains("Get Lucky", ignoreCase = true) ->
                "We're up all night to get lucky\nWe're up all night to get some"
            track.title.contains("BIRDS OF A FEATHER", ignoreCase = true) ->
                "Birds of a feather, we should stick together\nI know I said I'd never think I wasn't better alone"
            else ->
                "Add your favorite lyric lines\nin Edit → Manual Lyrics"
        }
    }
}
