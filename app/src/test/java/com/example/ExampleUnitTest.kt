package com.example

import androidx.compose.ui.graphics.Color
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import com.example.palette.PaletteColorExtractor
import com.example.service.AnimatedCanvasSearchService
import com.example.ui.components.resolveGradientStops
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local unit tests for Bitchord models, style configurations, and palette extraction.
 */
class ExampleUnitTest {

    @Test
    fun testDefaultShareCardConfig() {
        val config = CardStyleConfig()
        assertEquals(CardBrandMode.BITCHORD, config.brandMode)
        assertTrue(config.useBlurredAlbumBackground)
        assertEquals(24f, config.backgroundBlurRadius, 0.001f)
        assertEquals(0.25f, config.darkOverlayAlpha, 0.001f)
        assertEquals(16, config.cornerRadiusDp)
        assertEquals(1.0f, config.cardScale, 0.001f)
        assertFalse(config.autoSearchOnlineArt)
        assertEquals(LogoColorMode.RED, config.logoColorMode)
        assertEquals(GradientStyle.ADAPTIVE_HARMONY, config.gradientStyle)
    }

    @Test
    fun testGradientStopsAndScaleConfig() {
        val config = CardStyleConfig(
            gradientStyle = GradientStyle.CINEMATIC_AURORA,
            cardScale = 0.62f,
            autoSearchOnlineArt = true
        )
        val stops = resolveGradientStops(CardPalette.Default, config)
        assertEquals(3, stops.size)
        assertEquals(0.62f, config.cardScale, 0.001f)
        assertTrue(config.autoSearchOnlineArt)

        val largeConfig = config.copy(cardScale = 1.18f)
        assertEquals(1.18f, largeConfig.cardScale, 0.001f)
    }

    @Test
    fun testBrandModes() {
        val bitchordConfig = CardStyleConfig(brandMode = CardBrandMode.BITCHORD)
        assertEquals("Bitchord", bitchordConfig.brandMode.wordmark)

        val spotifyConfig = CardStyleConfig(
            brandMode = CardBrandMode.SPOTIFY,
            logoColorMode = LogoColorMode.GREEN
        )
        assertEquals("Spotify", spotifyConfig.brandMode.wordmark)
        assertEquals(Color(0xFF1DB954), spotifyConfig.resolveLogoBadgeColor(CardPalette.Default))

        val appleMusicConfig = CardStyleConfig(
            brandMode = CardBrandMode.APPLE_MUSIC,
            logoColorMode = LogoColorMode.RED
        )
        assertEquals("Apple Music", appleMusicConfig.brandMode.wordmark)
        assertEquals(Color(0xFFFF2B44), appleMusicConfig.resolveLogoBadgeColor(CardPalette.Default))
    }

    @Test
    fun testLogoColorModes() {
        val customColor = Color(0xFF00FF00)
        val palette = CardPalette(
            vibrantColor = Color(0xFF123456),
            darkMutedColor = Color(0xFF654321)
        )

        val redConfig = CardStyleConfig(logoColorMode = LogoColorMode.RED)
        assertEquals(Color(0xFFFF2B44), redConfig.resolveLogoBadgeColor(palette))

        val templateConfig = CardStyleConfig(logoColorMode = LogoColorMode.TEMPLATE_DYNAMIC)
        assertEquals(palette.vibrantColor, templateConfig.resolveLogoBadgeColor(palette))

        val greenConfig = CardStyleConfig(logoColorMode = LogoColorMode.GREEN)
        assertEquals(Color(0xFF1DB954), greenConfig.resolveLogoBadgeColor(palette))

        val whiteConfig = CardStyleConfig(logoColorMode = LogoColorMode.WHITE)
        assertEquals(Color.White, whiteConfig.resolveLogoBadgeColor(palette))

        val customConfig = CardStyleConfig(
            logoColorMode = LogoColorMode.CUSTOM,
            customLogoColor = customColor
        )
        assertEquals(customColor, customConfig.resolveLogoBadgeColor(palette))
    }

    @Test
    fun testMediaTrackInfo() {
        val track = MediaTrackInfo(
            title = "SICKO MODE",
            artist = "Travis Scott",
            album = "ASTROWORLD",
            appName = "EchoSnap",
            packageName = "com.example.music"
        )

        assertEquals("SICKO MODE", track.title)
        assertEquals("Travis Scott", track.artist)
        assertEquals("ASTROWORLD", track.album)
        assertEquals("EchoSnap", track.appName)
    }

    @Test
    fun testSpotifyLyricsCardAndManualLyrics() {
        val sampleTrack = MediaTrackInfo(
            title = "Custom Track",
            artist = "Independent Artist"
        )
        val blankConfig = CardStyleConfig(
            cardLayoutType = CardLayoutType.SPOTIFY_LYRICS_CARD,
            customLyrics = ""
        )
        assertTrue(blankConfig.resolveLyricsDisplayLines(sampleTrack).contains("Manual Lyrics"))

        val manualConfig = blankConfig.copy(
            customLyrics = "Line one of my lyrics\nLine two of my lyrics"
        )
        assertEquals(
            "Line one of my lyrics\nLine two of my lyrics",
            manualConfig.resolveLyricsDisplayLines(sampleTrack)
        )
        assertEquals(LyricsCardBackgroundMode.PALETTE_COLOR, manualConfig.lyricsCardBackgroundMode)

        val artBgConfig = manualConfig.copy(lyricsCardBackgroundMode = LyricsCardBackgroundMode.SONG_ART)
        assertEquals(LyricsCardBackgroundMode.SONG_ART, artBgConfig.lyricsCardBackgroundMode)
    }

    @Test
    fun testSmartCanvasQueryNormalizationAndTokenCleanup() {
        val cleaned = AnimatedCanvasSearchService.normalizeQueryText(
            "Blinding Lights (Official Music Video) [Lyrics] (feat. Rosalía)"
        )
        assertEquals("Blinding Lights", cleaned)
        assertEquals("Travis Scott", AnimatedCanvasSearchService.normalizeArtistText("Travis Scott - Topic"))
        assertEquals("Rosalia", AnimatedCanvasSearchService.normalizeArtistText("Rosalía feat. The Weeknd"))
        assertEquals("abc123", AnimatedCanvasSearchService.normalizeSpotifyToken("  Bearer abc123  "))

        val exactBreakdown = AnimatedCanvasSearchService.evaluateMatch(
            "SICKO MODE",
            "Travis Scott",
            "SICKO MODE (feat. Drake)",
            "Travis Scott"
        )
        val wrongArtistBreakdown = AnimatedCanvasSearchService.evaluateMatch(
            "SICKO MODE",
            "Random Cover Singer",
            "SICKO MODE",
            "Travis Scott"
        )
        assertTrue(exactBreakdown.artistScore > 0)
        assertEquals(0, wrongArtistBreakdown.artistScore)
        assertTrue(exactBreakdown.totalScore > wrongArtistBreakdown.totalScore)

        val protoReq = AnimatedCanvasSearchService.encodeEntityCanvazRequest(
            listOf("spotify:track:2xLMifQCjDGFmkHkpNLD9h")
        )
        assertTrue(protoReq.isNotEmpty())

        assertEquals(
            "2xLMifQCjDGFmkHkpNLD9h",
            AnimatedCanvasSearchService.extractSpotifyTrackId("spotify:track:2xLMifQCjDGFmkHkpNLD9h")
        )
        assertEquals(
            "0VjIjW4GlUZAMYd2vXMi3b",
            AnimatedCanvasSearchService.extractSpotifyTrackId("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc")
        )
        val normalizedUa = com.example.ui.SpotifyLoginActivity.normalizeBrowserUserAgent(
            "Mozilla/5.0 (Linux; Android 10; M2006C3MG; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Mobile Safari/537.36"
        )
        assertFalse(normalizedUa.contains("; wv"))
        assertTrue(normalizedUa.contains("Chrome/131.0.0.0"))

        assertEquals(
            "AQB_test_cookie_value_1234567890",
            com.example.service.SpotifyAuthManager.extractSpDcValue("sp_dc=AQB_test_cookie_value_1234567890; Path=/")
        )
        assertTrue(
            com.example.service.SpotifyAuthManager.looksLikeSpDcCookie(
                "AQA_very_long_spotify_sp_dc_cookie_value_abcdefghijklmnopqrstuvwxyz123456"
            )
        )
        assertFalse(
            com.example.service.SpotifyAuthManager.looksLikeSpDcCookie(
                "BQD_spotify_bearer_access_token_abcdefghijklmnopqrstuvwxyz123456"
            )
        )

        val customQueryBreakdown = AnimatedCanvasSearchService.evaluateMatch(
            candidateTitle = "SICKO MODE",
            candidateArtist = "Travis Scott",
            targetTitle = "Travis Scott SICKO MODE",
            targetArtist = ""
        )
        assertTrue(customQueryBreakdown.titleScore >= 40)

        // RFC 6238 SHA-1 TOTP standard vector at T=59s -> 94287082 -> last 6 digits 287082
        val rfcKey = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        assertEquals("287082", com.example.service.SpotifyAuthManager.computeTotpCode(rfcKey, 59L))

        val derived = com.example.service.SpotifyAuthManager.deriveSpotifyTotpKeyBytes(intArrayOf(9, 10))
        assertEquals("00", String(derived, Charsets.UTF_8))
    }

    @Test
    fun testPaletteExtractorWithNullBitmap() = runBlocking {
        val palette = PaletteColorExtractor.extractColors(null)
        assertNotNull(palette)
        assertEquals(CardPalette.Default, palette)
    }
}
