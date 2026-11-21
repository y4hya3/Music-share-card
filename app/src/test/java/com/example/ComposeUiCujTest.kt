package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardShadowColorMode
import com.example.model.CardStyleConfig
import com.example.model.LiveWallpaperArtworkFillMode
import com.example.model.LiveWallpaperCornerStyle
import com.example.model.MediaTrackInfo
import com.example.repository.CardStylePreferences
import com.example.repository.MediaNotificationRepository
import com.example.ui.BitChordShareScreen
import com.example.ui.components.BlurredAlbumBackground
import com.example.ui.components.EditCardBottomSheet
import com.example.ui.components.MusicStoryCard
import com.example.ui.components.SettingsDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.util.CardShareManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeUiCujTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MediaNotificationRepository.ensureInitialized(context)
        com.example.util.EchoSnapBackupManager.setPromptedInitialFolderAccess(context, true)
        CardStylePreferences.save(context, CardStyleConfig())
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { CardStyleConfig() }
    }

    @Test
    fun mainShareScreen_rendersAllPrimaryControls_andTogglesFitPreviewScale() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                BitChordShareScreen(isAppEntered = true)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("top_bar_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_scale_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("card_edit_pill_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_refresh_button").assertIsDisplayed()
        val cardNode = composeTestRule.onNodeWithTag("music_story_card_container")
        try {
            cardNode.assertIsDisplayed()
        } catch (e: AssertionError) {
            val semanticsNode = cardNode.fetchSemanticsNode()
            val stageNode = composeTestRule.onNodeWithTag("story_visual_stage").fetchSemanticsNode()
            throw AssertionError(
                "music_story_card_container not displayed! cardBoundsInRoot=${semanticsNode.boundsInRoot}, cardBoundsInWindow=${semanticsNode.boundsInWindow}, stageBoundsInRoot=${stageNode.boundsInRoot}, size=${semanticsNode.size}",
                e
            )
        }

        // Accessibility descriptions on custom vector icons & quick tabs
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
        composeTestRule.onNodeWithContentDescription("Edit card").assertExists()
        composeTestRule.onNodeWithContentDescription("Fit preview to screen").assertExists()
        composeTestRule.onNodeWithContentDescription("Preview card").assertExists()
        composeTestRule.onNodeWithTag("quick_tab_blur").assertIsSelected()

        // All 8 share/save actions are present in the bar
        composeTestRule.onNodeWithTag("action_copy_link").assertExists()
        composeTestRule.onNodeWithTag("action_save_image").assertExists()
        composeTestRule.onNodeWithTag("action_preview_card").assertExists()
        composeTestRule.onNodeWithTag("action_whatsapp").assertExists()
        composeTestRule.onNodeWithTag("action_instagram_story").assertExists()
        composeTestRule.onNodeWithTag("action_snapchat_story").assertExists()
        composeTestRule.onNodeWithTag("action_discord").assertExists()
        composeTestRule.onNodeWithTag("action_native_share").assertExists()

        // Tapping preview scale updates persisted config and content description
        composeTestRule.onNodeWithTag("preview_scale_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.styleConfig.value.isFitToScreenPreview)
        composeTestRule.onNodeWithContentDescription("Exit fit preview scale").assertExists()

        // Tapping size tab switches selected tab
        composeTestRule.onNodeWithTag("quick_tab_size").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_tab_size").assertIsSelected()
        composeTestRule.onNodeWithTag("quick_card_size_slider").assertExists()
    }

    @Test
    fun editCardSheet_updatesShadowDepth_inverseShadow_andShadowColor() {
        var currentConfig by mutableStateOf(CardStyleConfig())
        val track = MediaTrackInfo(
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            appName = "Spotify",
            packageName = "com.spotify.music",
            albumArt = null,
            isPlaying = true,
            isSample = false
        )

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                EditCardBottomSheet(
                    palette = CardPalette.Default,
                    styleConfig = currentConfig,
                    currentTrack = track,
                    onDismiss = {},
                    onStyleChange = { currentConfig = it },
                    onTrackSelect = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("shadow_depth_preset_0").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(0f, currentConfig.glowIntensity, 0.001f)

        composeTestRule.onNodeWithTag("shadow_depth_preset_100").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1f, currentConfig.glowIntensity, 0.001f)

        composeTestRule.onNodeWithTag("shadow_inverse_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(currentConfig.cardShadowInverse)

        composeTestRule.onNodeWithTag("shadow_color_RED").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(CardShadowColorMode.RED, currentConfig.cardShadowColorMode)
    }

    @Test
    fun settingsDialog_updatesWallpaperArtworkFill_edges_inverseShadow_andColor() {
        var currentConfig by mutableStateOf(
            CardStyleConfig(
                liveWallpaperArtworkFillMode = LiveWallpaperArtworkFillMode.FLOATING_SQUARE,
                liveWallpaperCornerStyle = LiveWallpaperCornerStyle.CURVED
            )
        )
        val track = MediaTrackInfo(
            title = "After Hours",
            artist = "The Weeknd",
            album = "After Hours",
            appName = "Spotify",
            packageName = "com.spotify.music",
            albumArt = null,
            isPlaying = false,
            isSample = false
        )

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                SettingsDialog(
                    styleConfig = currentConfig,
                    currentTrack = track,
                    palette = CardPalette.Default,
                    isAccessGranted = true,
                    onStyleChange = { newConfig ->
                        currentConfig = newConfig
                        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { newConfig }
                    },
                    onRequestAudioBeatSync = {},
                    onDismiss = {},
                    animateWaveformPreview = false
                )
            }
        }
        composeTestRule.waitForIdle()

        // Toggle Inverse Shadow & Purple shadow color inside Floating Square mode
        composeTestRule.onNodeWithTag("settings_lw_shadow_inverse_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(currentConfig.cardShadowInverse)

        composeTestRule.onNodeWithTag("settings_lw_shadow_color_PURPLE").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(CardShadowColorMode.PURPLE, currentConfig.cardShadowColorMode)

        // Switch sharp vs curved square edges
        composeTestRule.onNodeWithTag("settings_live_wallpaper_corners_sharp").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(LiveWallpaperCornerStyle.SHARP, currentConfig.liveWallpaperCornerStyle)

        // Switch Floating Square -> Full Screen -> back to Floating Square
        composeTestRule.onNodeWithTag("settings_live_wallpaper_fill_fullscreen").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(LiveWallpaperArtworkFillMode.FULL_SCREEN, currentConfig.liveWallpaperArtworkFillMode)

        composeTestRule.onNodeWithTag("settings_live_wallpaper_fill_floating").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(LiveWallpaperArtworkFillMode.FLOATING_SQUARE, currentConfig.liveWallpaperArtworkFillMode)
    }

    @Test
    fun allFourLayouts_renderAndExportInverseRedShadowCleanly_andBlurZeroUpscalesLowRes() {
        val lowResArt = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(AndroidColor.rgb(45, 130, 220))
        }
        val track = MediaTrackInfo(
            title = "All Layouts Regression",
            artist = "EchoSnap",
            album = "Test Suite",
            appName = "Musicolet",
            packageName = "in.krosbits.musicolet",
            albumArt = lowResArt,
            isPlaying = false,
            isSample = false
        )
        var layoutState by mutableStateOf(CardLayoutType.SPOTIFY_INNER_CARD)
        var bgBlurState by mutableStateOf(18f)

        try {
            composeTestRule.setContent {
                val cfg = CardStyleConfig(
                    cardLayoutType = layoutState,
                    backgroundBlurRadius = bgBlurState,
                    glowIntensity = 1.0f,
                    cardShadowInverse = true,
                    cardShadowColorMode = CardShadowColorMode.RED,
                    customLyrics = "Line one of lyrics\nLine two of lyrics"
                )
                MyApplicationTheme(darkTheme = true) {
                    BlurredAlbumBackground(
                        track = track,
                        palette = CardPalette.Default,
                        styleConfig = cfg
                    )
                    MusicStoryCard(
                        track = track,
                        palette = CardPalette.Default,
                        styleConfig = cfg
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Scrub BG blur down to 0px to verify low-res upscaling path recomposes safely
            bgBlurState = 0f
            composeTestRule.waitForIdle()

            // Verify every layout composes & exports cleanly with inverse colored shadow enabled
            CardLayoutType.entries.forEach { layout ->
                layoutState = layout
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("music_story_card_container").assertExists()

                val exported = runBlocking {
                    CardShareManager.generateStoryBitmap(
                        context = context,
                        track = track,
                        palette = CardPalette.Default,
                        styleConfig = CardStyleConfig(
                            cardLayoutType = layout,
                            glowIntensity = 1.0f,
                            cardShadowInverse = true,
                            cardShadowColorMode = CardShadowColorMode.RED,
                            customLyrics = "Line one of lyrics\nLine two of lyrics"
                        ),
                        targetWidth = 540,
                        targetHeight = 960
                    )
                }
                assertEquals(540, exported.width)
                assertEquals(960, exported.height)
                assertFalse(exported.isRecycled)
                exported.recycle()
            }
        } finally {
            if (!lowResArt.isRecycled) lowResArt.recycle()
        }
    }
}
