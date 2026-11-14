package com.example

import androidx.compose.ui.graphics.Color
import com.example.model.CardBrandMode
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.LogoColorMode
import com.example.model.MediaTrackInfo
import com.example.palette.PaletteColorExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertEquals(LogoColorMode.RED, config.logoColorMode)
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
            appName = "Bitchord",
            packageName = "com.example.music"
        )

        assertEquals("SICKO MODE", track.title)
        assertEquals("Travis Scott", track.artist)
        assertEquals("ASTROWORLD", track.album)
        assertEquals("Bitchord", track.appName)
    }

    @Test
    fun testPaletteExtractorWithNullBitmap() = runBlocking {
        val palette = PaletteColorExtractor.extractColors(null)
        assertNotNull(palette)
        assertEquals(CardPalette.Default, palette)
    }
}
