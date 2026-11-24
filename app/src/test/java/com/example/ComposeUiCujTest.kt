package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
        cardNode.assertExists()

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

    @Test
    fun nextSongSwitch_crossfadesCrispUnblurredArtworkCleanlyAcrossLayouts() {
        val artOne = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(AndroidColor.rgb(220, 60, 80))
        }
        val artTwo = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(AndroidColor.rgb(40, 175, 235))
        }
        val trackOne = MediaTrackInfo(
            title = "First Song",
            artist = "Artist One",
            album = "Album One",
            appName = "Musicolet",
            packageName = "in.krosbits.musicolet",
            albumArt = artOne,
            isPlaying = true,
            isSample = false
        )
        val trackTwo = MediaTrackInfo(
            title = "Next Song",
            artist = "Artist Two",
            album = "Album Two",
            appName = "Musicolet",
            packageName = "in.krosbits.musicolet",
            albumArt = artTwo,
            isPlaying = true,
            isSample = false
        )

        var activeTrack by mutableStateOf(trackOne)
        var layoutType by mutableStateOf(CardLayoutType.SPOTIFY_INNER_CARD)
        val unblurredCfg = CardStyleConfig(backgroundBlurRadius = 0f, useBlurredAlbumBackground = true)

        try {
            composeTestRule.setContent {
                val cfg = unblurredCfg.copy(cardLayoutType = layoutType)
                MyApplicationTheme(darkTheme = true) {
                    BlurredAlbumBackground(
                        track = activeTrack,
                        palette = CardPalette.Default,
                        styleConfig = cfg
                    )
                    MusicStoryCard(
                        track = activeTrack,
                        palette = CardPalette.Default,
                        styleConfig = cfg
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("music_story_card_container").assertExists()

            // Switch to next track cleanly across layouts
            CardLayoutType.entries.forEach { layout ->
                layoutType = layout
                activeTrack = if (activeTrack.title == "First Song") trackTwo else trackOne
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("music_story_card_container").assertExists()
            }
        } finally {
            if (!artOne.isRecycled) artOne.recycle()
            if (!artTwo.isRecycled) artTwo.recycle()
        }
    }

    @Test
    fun settingsDialog_showsTransitionOnlyInArtworkMode_andTogglesDimmerPowerSaveBatteryHealthAutoSync() {
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
            CardStyleConfig(
                liveWallpaperMode = com.example.model.LiveWallpaperMode.ANIMATED_CANVAS
            )
        }
        val track = MediaTrackInfo(
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up",
            appName = "Spotify",
            packageName = "com.spotify.music",
            albumArt = null,
            isPlaying = false,
            isSample = false
        )

        composeTestRule.setContent {
            val currentConfig by MediaNotificationRepository.styleConfig.collectAsState()
            MyApplicationTheme(darkTheme = true) {
                SettingsDialog(
                    styleConfig = currentConfig,
                    currentTrack = track,
                    palette = CardPalette.Default,
                    isAccessGranted = true,
                    onStyleChange = { newConfig ->
                        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { newConfig }
                    },
                    onRequestAudioBeatSync = {},
                    onDismiss = {},
                    animateWaveformPreview = false
                )
            }
        }
        composeTestRule.waitForIdle()

        // Next-Song Transition must NOT exist while Animated Canvas mode is active
        composeTestRule.onNodeWithTag("settings_lw_transition_section").assertDoesNotExist()

        // Smooth Canvas-to-Canvas Fade switch & Disk Cache/Prewarm card MUST exist in Animated Canvas mode
        composeTestRule.onNodeWithTag("settings_lw_canvas_smooth_section").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("settings_lw_canvas_cache_card").performScrollTo().assertExists()
        assertTrue(MediaNotificationRepository.styleConfig.value.liveWallpaperSmoothCanvasToCanvas)
        composeTestRule.onNodeWithTag("settings_lw_smooth_canvas_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertFalse(MediaNotificationRepository.styleConfig.value.liveWallpaperSmoothCanvasToCanvas)
        composeTestRule.onNodeWithTag("settings_lw_smooth_canvas_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.styleConfig.value.liveWallpaperSmoothCanvasToCanvas)

        // Apple Music Card label & default check
        assertEquals("Apple Music Card", CardLayoutType.APP_MUSIC_CARD.label)
        assertEquals(CardLayoutType.APP_MUSIC_CARD, CardStyleConfig().cardLayoutType)

        // Switch to Artwork Only -> Next-Song Transition appears and Smooth Canvas Fade hides
        composeTestRule.onNodeWithTag("settings_live_wallpaper_mode_artwork").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            com.example.model.LiveWallpaperMode.ARTWORK_ONLY,
            MediaNotificationRepository.styleConfig.value.liveWallpaperMode
        )
        composeTestRule.onNodeWithTag("settings_lw_transition_section").assertExists()
        composeTestRule.onNodeWithTag("settings_lw_smooth_canvas_switch").assertDoesNotExist()

        // Pick Slide Left & Slow (540ms)
        composeTestRule.onNodeWithTag("settings_lw_transition_SLIDE_LEFT").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            com.example.model.LiveWallpaperTransitionStyle.SLIDE_LEFT,
            MediaNotificationRepository.styleConfig.value.liveWallpaperArtTransition
        )
        composeTestRule.onNodeWithTag("settings_lw_transition_speed_540").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(540, MediaNotificationRepository.styleConfig.value.liveWallpaperArtTransitionDurationMs)

        // Wallpaper Brightness Dimmer toggle reveals dimmer slider
        composeTestRule.onNodeWithTag("settings_lw_dim_slider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_lw_dim_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.styleConfig.value.liveWallpaperDimEnabled)
        composeTestRule.onNodeWithTag("settings_lw_dim_slider").assertExists()

        // Smart Battery Saver Pause Canvas switch toggles cleanly
        assertTrue(MediaNotificationRepository.styleConfig.value.liveWallpaperPowerSavePauseCanvas)
        composeTestRule.onNodeWithTag("settings_lw_power_save_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertFalse(MediaNotificationRepository.styleConfig.value.liveWallpaperPowerSavePauseCanvas)

        // Backup Auto-Sync toggle renders & updates MediaNotificationRepository cleanly
        composeTestRule.onNodeWithTag("settings_backup_auto_sync_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.styleConfig.value.backupAutoSyncEnabled)

        // Background & Battery Health card, Rebind Listener, Battery Settings & YouTube ignore chip
        composeTestRule.onNodeWithTag("settings_battery_health_card").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("settings_rebind_listener_button").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("settings_battery_settings_button").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("settings_ignore_pkg_com.google.android.youtube").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue("com.google.android.youtube" in MediaNotificationRepository.styleConfig.value.ignoredMediaPackages)
    }

    @Test
    fun editCardSheet_selectAppMusicCard_locksCardBlur_andExposesBackgroundBlurAndPhotoShadow() {
        var currentConfig by mutableStateOf(CardStyleConfig(cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD))
        val track = MediaTrackInfo(
            title = "Cheerios",
            artist = "Drake",
            album = "Singles",
            appName = "Apple Music",
            packageName = "com.apple.android.music",
            albumArt = null,
            isPlaying = true,
            isSample = false,
            durationMs = 138_000L,
            positionMs = 29_000L
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

        // Initially on Floating Glass Card: sheet_card_blur_slider exists
        composeTestRule.onNodeWithTag("sheet_card_blur_slider").assertExists()

        // Select Apple Music Card
        composeTestRule.onNodeWithText("Apple Music Card").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("layout_type_APP_MUSIC_CARD").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(CardLayoutType.APP_MUSIC_CARD, currentConfig.cardLayoutType)

        // Card blur slider must be hidden; Background blur slider and Photo Shadow Depth remain available
        composeTestRule.onNodeWithTag("sheet_card_blur_slider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sheet_background_blur_slider").assertExists()
        composeTestRule.onNodeWithText("Photo Shadow Depth").performScrollTo().assertExists()

        // Photo shadow color & inverse toggle still work cleanly
        composeTestRule.onNodeWithTag("shadow_inverse_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(currentConfig.cardShadowInverse)
        composeTestRule.onNodeWithTag("shadow_color_RED").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(CardShadowColorMode.RED, currentConfig.cardShadowColorMode)
    }

    @Test
    fun appMusicCard_onShareScreen_hidesGlassBlurTab_keepsBgBlur_andControlsAreFunctional() {
        MediaNotificationRepository.selectSampleTrack(MediaNotificationRepository.getSampleTracks().first())
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
            CardStyleConfig(cardLayoutType = CardLayoutType.APP_MUSIC_CARD)
        }

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                BitChordShareScreen(isAppEntered = true)
            }
        }
        composeTestRule.waitForIdle()

        // Background blur tab visible, Glass blur tab hidden for App Music Card
        composeTestRule.onNodeWithTag("quick_tab_blur").assertExists()
        composeTestRule.onNodeWithTag("quick_tab_card_blur").assertDoesNotExist()

        // All interactive App Music Card controls exist
        composeTestRule.onNodeWithTag("app_music_card_artwork_box").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_elapsed_text").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_remaining_text").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_timeline_slider").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_star_button").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_prev_button").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_play_pause_button").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_next_button").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_volume_slider").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_airplay_button").assertExists()

        // Favorite star button toggles state
        val initialTrack = MediaNotificationRepository.currentTrack.value
        val wasFav = MediaNotificationRepository.isTrackFavorited(initialTrack)
        composeTestRule.onNodeWithTag("app_music_card_star_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(!wasFav, MediaNotificationRepository.isTrackFavorited(initialTrack))
        composeTestRule.onNodeWithTag("app_music_card_star_button").performClick()
        composeTestRule.waitForIdle()

        // Play/Pause button toggles playback state
        val wasPlaying = MediaNotificationRepository.currentTrack.value.isPlaying
        composeTestRule.onNodeWithTag("app_music_card_play_pause_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(!wasPlaying, MediaNotificationRepository.currentTrack.value.isPlaying)

        // Draggable timeline scrubber seeks cleanly via semantics progress action
        composeTestRule.onNodeWithTag("app_music_card_timeline_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.playbackProgress.value.positionMs >= 60_000L)

        // Draggable volume slider responds cleanly
        composeTestRule.onNodeWithTag("app_music_card_volume_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        composeTestRule.waitForIdle()

        // Next & Previous track buttons cycle cleanly
        val beforeNext = MediaNotificationRepository.currentTrack.value.title
        composeTestRule.onNodeWithTag("app_music_card_next_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(MediaNotificationRepository.currentTrack.value.title != beforeNext)

        composeTestRule.onNodeWithTag("app_music_card_prev_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(beforeNext, MediaNotificationRepository.currentTrack.value.title)
    }

    @Test
    fun shareScreen_tripleTapTogglesHud_withoutBreakingCardControls() {
        MediaNotificationRepository.selectSampleTrack(MediaNotificationRepository.getSampleTracks().first())
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
            CardStyleConfig(cardLayoutType = CardLayoutType.APP_MUSIC_CARD)
        }

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                BitChordShareScreen(isAppEntered = true)
            }
        }
        composeTestRule.waitForIdle()

        // HUD and Apple Music Card start visible
        composeTestRule.onNodeWithTag("top_bar_settings_button").assertExists()
        composeTestRule.onNodeWithTag("external_blur_control_container").assertExists()
        composeTestRule.onNodeWithTag("bottom_share_actions_bar").assertExists()
        composeTestRule.onNodeWithTag("center_story_card").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_airplay_button").assertExists()

        // Triple tap unconsumed background area -> hides UI HUD
        composeTestRule.onNodeWithTag("main_share_screen_root").performTouchInput {
            val tapPos = Offset(18f, centerY)
            click(tapPos)
            advanceEventTime(90L)
            click(tapPos)
            advanceEventTime(90L)
            click(tapPos)
        }
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("top_bar_settings_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("external_blur_control_container").assertDoesNotExist()
        composeTestRule.onNodeWithTag("bottom_share_actions_bar").assertDoesNotExist()
        // Story card & AirPlay button remain visible
        composeTestRule.onNodeWithTag("center_story_card").assertExists()
        composeTestRule.onNodeWithTag("app_music_card_airplay_button").assertExists()

        // Another triple tap -> restores UI HUD
        composeTestRule.onNodeWithTag("main_share_screen_root").performTouchInput {
            val tapPos = Offset(18f, centerY)
            click(tapPos)
            advanceEventTime(90L)
            click(tapPos)
            advanceEventTime(90L)
            click(tapPos)
        }
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("top_bar_settings_button").assertExists()
        composeTestRule.onNodeWithTag("external_blur_control_container").assertExists()
        composeTestRule.onNodeWithTag("bottom_share_actions_bar").assertExists()
    }

    @Test
    fun shareScreen_notificationAccessGrantedBanner_disappearsAfterFiveSeconds() {
        val originalListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        try {
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
            MediaNotificationRepository.checkNotificationAccess(context)
            assertFalse(MediaNotificationRepository.isNotificationAccessEnabled.value)

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = true) {
                    BitChordShareScreen(isAppEntered = true)
                }
            }
            composeTestRule.waitForIdle()

            // Red "Notification Access Required" stays visible while denied
            composeTestRule.onNodeWithTag("notification_status_banner").assertExists()
            composeTestRule.onNodeWithText("Notification Access Required").assertExists()
            composeTestRule.mainClock.advanceTimeBy(6_000L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Notification Access Required").assertExists()

            // Grant notification access -> turns to green "Media Listener Active"
            Settings.Secure.putString(
                context.contentResolver,
                "enabled_notification_listeners",
                "${context.packageName}/com.example.service.MusicNotificationListenerService"
            )
            MediaNotificationRepository.checkNotificationAccess(context)
            composeTestRule.mainClock.advanceTimeBy(400L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Media Listener Active").assertExists()

            // After 5 seconds total -> green message disappears automatically
            composeTestRule.mainClock.advanceTimeBy(5_200L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("notification_status_banner").assertDoesNotExist()
            composeTestRule.onNodeWithText("Media Listener Active").assertDoesNotExist()
        } finally {
            Settings.Secure.putString(
                context.contentResolver,
                "enabled_notification_listeners",
                originalListeners.orEmpty()
            )
            MediaNotificationRepository.checkNotificationAccess(context)
        }
    }
}
