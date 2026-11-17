package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.service.MusicNotificationListenerService
import com.example.ui.components.AnimatedCanvasVideoBackground
import com.example.ui.components.BlurredAlbumBackground
import com.example.ui.components.DynamicIslandWaveformBars
import com.example.ui.components.EditCardBottomSheet
import com.example.ui.components.MusicStoryCard
import com.example.ui.components.NotificationStatusBanner
import com.example.ui.components.SettingsDialog
import com.example.ui.components.ShareActionsBar
import com.example.ui.components.TransparentEditorCheckerboard
import com.example.util.CardShareManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class QuickControlTab(val label: String) {
    BLUR("BG Blur"),
    CARD_BLUR("Glass Blur"),
    SIZE("Size"),
    OPACITY("Glass Opacity")
}

private enum class LiveScrubTarget {
    NONE,
    BG_BLUR,
    CARD_BLUR,
    CARD_SIZE,
    CARD_OPACITY
}

/**
 * Adaptive Main Share Screen supporting:
 * - Zero-lag 60fps live slider dragging via transient local preview overrides
 * - Live Scrubbing Preview on Card Size & Card Opacity (200ms M3 Expressive fade keeping ONLY
 *   the active slider & minimal value badge in place while fading surrounding chrome)
 * - Preview Scale Toggle (Full-Screen edge-to-edge vs Fit-to-Screen centered 9:16 preview)
 *   with fixed anchored overlay controls and full 9:16 export resolution
 */
@Composable
fun BitChordShareScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentTrack by MediaNotificationRepository.currentTrack.collectAsState()
    val palette by MediaNotificationRepository.extractedPalette.collectAsState()
    val persistedStyleConfig by MediaNotificationRepository.styleConfig.collectAsState()
    val isAccessGranted by MediaNotificationRepository.isNotificationAccessEnabled.collectAsState()
    val activeCanvasUrl by MediaNotificationRepository.activeCanvasUrl.collectAsState()

    var showEditSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var isProcessingAction by remember { mutableStateOf(false) }
    var actionStatusText by remember { mutableStateOf("") }

    // Transient 60fps local overrides during slider dragging (never lag behind prefs/IO)
    var dragBlurRadius by remember { mutableStateOf<Float?>(null) }
    var dragCardScale by remember { mutableStateOf<Float?>(null) }
    var dragCardOpacity by remember { mutableStateOf<Float?>(null) }
    var dragCardBlur by remember { mutableStateOf<Float?>(null) }
    var dragLyricsBlur by remember { mutableStateOf<Float?>(null) }

    var quickControlTab by remember { mutableStateOf(QuickControlTab.BLUR) }
    var liveScrubTarget by remember { mutableStateOf(LiveScrubTarget.NONE) }

    val effectiveStyleConfig = remember(
        persistedStyleConfig,
        dragBlurRadius,
        dragCardScale,
        dragCardOpacity,
        dragCardBlur,
        dragLyricsBlur
    ) {
        persistedStyleConfig.copy(
            backgroundBlurRadius = dragBlurRadius ?: persistedStyleConfig.backgroundBlurRadius,
            cardScale = dragCardScale ?: persistedStyleConfig.cardScale,
            cardOpacity = dragCardOpacity ?: persistedStyleConfig.cardOpacity,
            cardBlurRadius = dragCardBlur ?: persistedStyleConfig.cardBlurRadius,
            lyricsArtBlurRadius = dragLyricsBlur ?: persistedStyleConfig.lyricsArtBlurRadius
        )
    }

    val isLiveScrubbingCard = liveScrubTarget != LiveScrubTarget.NONE
    val chromeAlpha by animateFloatAsState(
        targetValue = if (isLiveScrubbingCard) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = EaseInOut),
        label = "live_scrub_chrome_alpha"
    )

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
                it.copy(enableWaveform = true, waveformBeatSync = true)
            }
            Toast.makeText(context, "Live audio beat sync enabled", Toast.LENGTH_SHORT).show()
        } else {
            MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
                it.copy(waveformBeatSync = false)
            }
            Toast.makeText(context, "Using track tempo groove (microphone permission denied)", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleAudioBeatSyncRequest(enableSync: Boolean) {
        if (!enableSync) {
            MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
                it.copy(waveformBeatSync = false)
            }
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
                    it.copy(enableWaveform = true, waveformBeatSync = true)
                }
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun isGifExportExpected(style: CardStyleConfig): Boolean =
        CardShareManager.isGifExportActive(style)

    fun isVideoExportExpected(style: CardStyleConfig, canvasUrl: String?): Boolean {
        if (style.useTransparentBackground) return false
        if (!canvasUrl.isNullOrBlank()) return true
        return style.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD && style.enableWaveform
    }

    fun triggerSaveToGallery() {
        if (!isProcessingAction) {
            isProcessingAction = true
            val latestTrack = MediaNotificationRepository.currentTrack.value
            val latestPalette = MediaNotificationRepository.extractedPalette.value
            val latestStyle = MediaNotificationRepository.styleConfig.value
            val latestCanvas = MediaNotificationRepository.activeCanvasUrl.value
            actionStatusText = when {
                isGifExportExpected(latestStyle) -> "Exporting transparent 9:16 GIF..."
                latestStyle.useTransparentBackground -> "Saving transparent 9:16 PNG..."
                isVideoExportExpected(latestStyle, latestCanvas) -> "Exporting 9:16 MP4 video..."
                else -> "Saving to Pictures/EchoSnap..."
            }
            coroutineScope.launch {
                try {
                    CardShareManager.saveCardToGallery(
                        context = context,
                        track = latestTrack,
                        palette = latestPalette,
                        styleConfig = latestStyle,
                        activeCanvasVideoUrl = latestCanvas
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isProcessingAction = false
                }
            }
        }
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            triggerSaveToGallery()
        } else {
            Toast.makeText(context, "Storage permission needed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleSaveImageClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        triggerSaveToGallery()
    }

    fun handleRefreshClick() {
        val accessNow = MusicNotificationListenerService.isNotificationAccessGranted(context)
        if (!accessNow) {
            Toast.makeText(context, "Enable Notification Access to read music cover art", Toast.LENGTH_SHORT).show()
            MusicNotificationListenerService.openNotificationAccessSettings(context)
            return
        }
        val synced = MediaNotificationRepository.refreshFromActiveMedia(context, force = true)
        if (synced) {
            val syncedTrack = MediaNotificationRepository.currentTrack.value
            Toast.makeText(context, "Synced: ${syncedTrack.title}", Toast.LENGTH_SHORT).show()
        } else {
            MusicNotificationListenerService.rebindIfPossible(context)
            Toast.makeText(context, "Play a song in any music app, then tap Refresh", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePreviewScale() {
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
            current.copy(isFitToScreenPreview = !current.isFitToScreenPreview)
        }
    }

    fun runShareAction(status: String, block: suspend () -> Unit) {
        if (!isProcessingAction) {
            isProcessingAction = true
            actionStatusText = status
            coroutineScope.launch {
                try {
                    block()
                } finally {
                    isProcessingAction = false
                }
            }
        }
    }

    LaunchedEffect(isAccessGranted) {
        if (isAccessGranted) {
            MediaNotificationRepository.refreshFromActiveMedia(context)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val isWideLandscape = screenWidth >= 600.dp && screenWidth > screenHeight
        val isPortraitTablet = screenWidth >= 600.dp && !isWideLandscape

        val fitProgress by animateFloatAsState(
            targetValue = if (effectiveStyleConfig.isFitToScreenPreview) 1f else 0f,
            animationSpec = tween(durationMillis = 260, easing = EaseInOut),
            label = "preview_scale_fit_progress"
        )

        if (!isWideLandscape) {
            // PORTRAIT VISUAL STAGE
            // Canonical 360dp x 640dp stage (= 1080x1920 / 3) guarantees Fit-to-Screen matches 9:16 Export 1:1
            val topChromeReserve = if (isAccessGranted) 86.dp else 156.dp
            val bottomChromeReserve = 210.dp
            val clearMidHeight = (screenHeight - topChromeReserve - bottomChromeReserve).coerceAtLeast(200.dp)
            val clearMidWidth = (screenWidth - 36.dp).coerceAtLeast(130.dp)
            val fitHeight = minOf(clearMidHeight, clearMidWidth * (16f / 9f))
            val fitWidth = fitHeight * (9f / 16f)

            val stageWidth = lerp(screenWidth, fitWidth, fitProgress)
            val stageHeight = lerp(screenHeight, fitHeight, fitProgress)
            val stageCorner = lerp(0.dp, 22.dp, fitProgress)
            val stageElevation = lerp(0.dp, 20.dp, fitProgress)
            val centerShiftY = lerp(
                0.dp,
                (topChromeReserve - bottomChromeReserve) * 0.42f,
                fitProgress
            )
            val fullCardSlotHeight = (screenHeight - topChromeReserve - bottomChromeReserve - 20.dp).coerceAtLeast(220.dp)
            val canonicalRefWidth = 360.dp
            val maxZoomByHeight = (fullCardSlotHeight / 470.dp).coerceIn(0.62f, 1.35f)
            val fullCardZoom = minOf(
                if (isPortraitTablet) {
                    (screenWidth * 0.75f / canonicalRefWidth).coerceIn(0.95f, 1.30f)
                } else {
                    (screenWidth / canonicalRefWidth).coerceIn(0.86f, 1.14f)
                },
                maxZoomByHeight
            )
            val fitCardZoom = (fitWidth / canonicalRefWidth).coerceAtLeast(0.32f)
            val cardZoom = androidx.compose.ui.util.lerp(fullCardZoom, fitCardZoom, fitProgress)
            val fullCenterY = ((topChromeReserve - bottomChromeReserve) * 0.30f)
            val cardOffsetY = lerp(fullCenterY, 0.dp, fitProgress)

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = centerShiftY)
                    .size(width = stageWidth, height = stageHeight)
                    .shadow(elevation = stageElevation, shape = RoundedCornerShape(stageCorner))
                    .clip(RoundedCornerShape(stageCorner))
                    .then(
                        if (fitProgress > 0.02f) {
                            Modifier.border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.24f * fitProgress),
                                shape = RoundedCornerShape(stageCorner)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .testTag("story_visual_stage"),
                contentAlignment = Alignment.Center
            ) {
                BlurredAlbumBackground(
                    track = currentTrack,
                    palette = palette,
                    styleConfig = effectiveStyleConfig,
                    activeCanvasVideoUrl = if (showPreviewDialog) null else activeCanvasUrl,
                    isCanvasPaused = showPreviewDialog,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("fullscreen_blurred_background")
                )

                Box(
                    modifier = Modifier
                        .offset(y = cardOffsetY)
                        .requiredSize(width = 360.dp, height = 640.dp)
                        .graphicsLayer {
                            scaleX = cardZoom
                            scaleY = cardZoom
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MusicStoryCard(
                        track = currentTrack,
                        palette = palette,
                        styleConfig = effectiveStyleConfig,
                        maxCardWidth = 340.dp,
                        maxArtSize = 310.dp,
                        modifier = Modifier.testTag("center_story_card")
                    )
                }
            }
        } else {
            // LANDSCAPE TABLET BACKGROUND STAGE (edge-to-edge under both panes unless Fit-to-Screen renders card stage in left pane)
            if (fitProgress < 0.05f) {
                BlurredAlbumBackground(
                    track = currentTrack,
                    palette = palette,
                    styleConfig = effectiveStyleConfig,
                    activeCanvasVideoUrl = if (showPreviewDialog) null else activeCanvasUrl,
                    isCanvasPaused = showPreviewDialog,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("fullscreen_blurred_background")
                )
            } else {
                BlurredAlbumBackground(
                    track = currentTrack,
                    palette = palette,
                    styleConfig = effectiveStyleConfig.copy(useTransparentBackground = false),
                    activeCanvasVideoUrl = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.25f }
                )
            }
        }

        // FIXED OVERLAY CHROME LAYER (Controls stay anchored in exact positions regardless of Preview Scale toggle)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (effectiveStyleConfig.isFitToScreenPreview) {
                                val paneFitH = minOf(maxHeight - 16.dp, (maxWidth - 16.dp) * 16f / 9f).coerceAtLeast(220.dp)
                                val paneFitW = paneFitH * (9f / 16f)
                                val paneCardZoom = (paneFitW / 360.dp).coerceAtLeast(0.32f)
                                Box(
                                    modifier = Modifier
                                        .size(width = paneFitW, height = paneFitH)
                                        .shadow(20.dp, RoundedCornerShape(22.dp))
                                        .clip(RoundedCornerShape(22.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(22.dp))
                                        .testTag("story_visual_stage"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BlurredAlbumBackground(
                                        track = currentTrack,
                                        palette = palette,
                                        styleConfig = effectiveStyleConfig,
                                        activeCanvasVideoUrl = if (showPreviewDialog) null else activeCanvasUrl,
                                        isCanvasPaused = showPreviewDialog,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .requiredSize(width = 360.dp, height = 640.dp)
                                            .graphicsLayer {
                                                scaleX = paneCardZoom
                                                scaleY = paneCardZoom
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        MusicStoryCard(
                                            track = currentTrack,
                                            palette = palette,
                                            styleConfig = effectiveStyleConfig,
                                            maxCardWidth = 340.dp,
                                            maxArtSize = 310.dp,
                                            modifier = Modifier.testTag("center_story_card")
                                        )
                                    }
                                }
                            } else {
                                val adaptiveArtMax = (maxHeight - 170.dp).coerceIn(150.dp, 360.dp)
                                val adaptiveCardMax = minOf(maxWidth * 0.85f, 380.dp)
                                MusicStoryCard(
                                    track = currentTrack,
                                    palette = palette,
                                    styleConfig = effectiveStyleConfig,
                                    maxCardWidth = adaptiveCardMax,
                                    maxArtSize = adaptiveArtMax,
                                    modifier = Modifier.testTag("center_story_card")
                                )
                            }

                            if (isProcessingAction) {
                                ProcessingOverlay(actionStatusText)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .widthIn(min = 310.dp, max = 420.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ShareScreenTopBar(
                                isFitToScreen = effectiveStyleConfig.isFitToScreenPreview,
                                onTogglePreviewScale = { togglePreviewScale() },
                                onOpenSettings = { showSettingsDialog = true },
                                onClose = { findActivity(context)?.finish() },
                                onEdit = { showEditSheet = true },
                                onRefresh = { handleRefreshClick() },
                                compactTitle = true,
                                modifier = Modifier.graphicsLayer { alpha = chromeAlpha }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            NotificationStatusBanner(
                                isAccessGranted = isAccessGranted,
                                onEnableClick = {
                                    MusicNotificationListenerService.openNotificationAccessSettings(context)
                                },
                                onRefreshClick = { handleRefreshClick() },
                                modifier = Modifier.graphicsLayer { alpha = chromeAlpha }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            QuickControlSliderPill(
                                activeTab = quickControlTab,
                                onTabSelect = { quickControlTab = it },
                                blurRadiusPx = effectiveStyleConfig.backgroundBlurRadius,
                                cardBlurRadiusPx = effectiveStyleConfig.cardBlurRadius,
                                cardScale = effectiveStyleConfig.cardScale,
                                cardOpacity = effectiveStyleConfig.cardOpacity,
                                chromeAlpha = chromeAlpha,
                                activeScrubTarget = liveScrubTarget,
                                onBlurChange = { newRadius ->
                                    dragBlurRadius = newRadius.coerceIn(0f, 40f)
                                },
                                onBlurScrubChange = { isDown ->
                                    liveScrubTarget = if (isDown) LiveScrubTarget.BG_BLUR else LiveScrubTarget.NONE
                                },
                                onBlurFinish = {
                                    liveScrubTarget = LiveScrubTarget.NONE
                                    val committed = (dragBlurRadius ?: persistedStyleConfig.backgroundBlurRadius).coerceIn(0f, 40f)
                                    dragBlurRadius = null
                                    MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                        current.copy(
                                            useBlurredAlbumBackground = true,
                                            useTransparentBackground = false,
                                            backgroundBlurRadius = committed
                                        )
                                    }
                                },
                                onCardBlurChange = { newBlur ->
                                    dragCardBlur = newBlur.coerceIn(0f, 40f)
                                },
                                onCardBlurScrubChange = { isDown ->
                                    liveScrubTarget = if (isDown) LiveScrubTarget.CARD_BLUR else LiveScrubTarget.NONE
                                },
                                onCardBlurFinish = {
                                    liveScrubTarget = LiveScrubTarget.NONE
                                    val committed = (dragCardBlur ?: persistedStyleConfig.cardBlurRadius).coerceIn(0f, 40f)
                                    dragCardBlur = null
                                    MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                        current.copy(cardBlurRadius = committed)
                                    }
                                },
                                onCardScaleChange = { newScale ->
                                    dragCardScale = newScale.coerceIn(0.60f, 1.20f)
                                },
                                onCardScaleScrubChange = { isDown ->
                                    liveScrubTarget = if (isDown) LiveScrubTarget.CARD_SIZE else LiveScrubTarget.NONE
                                },
                                onCardScaleFinish = {
                                    liveScrubTarget = LiveScrubTarget.NONE
                                    val committed = (dragCardScale ?: persistedStyleConfig.cardScale).coerceIn(0.60f, 1.20f)
                                    dragCardScale = null
                                    MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                        current.copy(cardScale = committed)
                                    }
                                },
                                onCardOpacityChange = { newOpacity ->
                                    dragCardOpacity = newOpacity.coerceIn(0.0f, 1.0f)
                                },
                                onCardOpacityScrubChange = { isDown ->
                                    liveScrubTarget = if (isDown) LiveScrubTarget.CARD_OPACITY else LiveScrubTarget.NONE
                                },
                                onCardOpacityFinish = {
                                    liveScrubTarget = LiveScrubTarget.NONE
                                    val committed = (dragCardOpacity ?: persistedStyleConfig.cardOpacity).coerceIn(0.0f, 1.0f)
                                    dragCardOpacity = null
                                    MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                        current.copy(cardOpacity = committed)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            ShareActionsBar(
                                isGridLayout = true,
                                onCopyLink = {
                                    CardShareManager.copyTrackLink(context, currentTrack, effectiveStyleConfig.brandMode)
                                },
                                onSaveToGallery = { handleSaveImageClick() },
                                onPreviewCard = { showPreviewDialog = true },
                                onShareWhatsApp = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = when {
                                        isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for WhatsApp..."
                                        isVideoExportExpected(s, c) -> "Exporting 9:16 video for WhatsApp..."
                                        else -> "Preparing for WhatsApp..."
                                    }
                                    runShareAction(msg) {
                                        CardShareManager.shareToWhatsApp(context, t, p, s, c)
                                    }
                                },
                                onShareInstagramStory = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = when {
                                        isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Instagram..."
                                        isVideoExportExpected(s, c) -> "Exporting 9:16 video for Instagram Story..."
                                        else -> "Preparing Instagram Story..."
                                    }
                                    runShareAction(msg) {
                                        CardShareManager.shareToInstagramStory(context, t, p, s, c)
                                    }
                                },
                                onShareSnapchatStory = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = when {
                                        isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Snapchat..."
                                        isVideoExportExpected(s, c) -> "Exporting 9:16 video for Snapchat Story..."
                                        else -> "Preparing Snapchat Story..."
                                    }
                                    runShareAction(msg) {
                                        CardShareManager.shareToSnapchatStory(context, t, p, s, c)
                                    }
                                },
                                onShareDiscord = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = when {
                                        isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Discord..."
                                        isVideoExportExpected(s, c) -> "Exporting 9:16 video for Discord..."
                                        else -> "Preparing for Discord..."
                                    }
                                    runShareAction(msg) {
                                        CardShareManager.shareToDiscord(context, t, p, s, c)
                                    }
                                },
                                onNativeShare = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = when {
                                        isGifExportExpected(s) -> "Exporting transparent 9:16 GIF..."
                                        isVideoExportExpected(s, c) -> "Exporting 9:16 MP4 video..."
                                        else -> "Opening Share Sheet..."
                                    }
                                    runShareAction(msg) {
                                        CardShareManager.shareStoryCard(context, t, p, s, c)
                                    }
                                },
                                modifier = Modifier
                                    .graphicsLayer { alpha = chromeAlpha }
                                    .testTag("bottom_share_actions_bar")
                            )
                        }
                    }
                } else {
                    // PORTRAIT FIXED OVERLAY CONTROLS
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ShareScreenTopBar(
                            isFitToScreen = effectiveStyleConfig.isFitToScreenPreview,
                            onTogglePreviewScale = { togglePreviewScale() },
                            onOpenSettings = { showSettingsDialog = true },
                            onClose = { findActivity(context)?.finish() },
                            onEdit = { showEditSheet = true },
                            onRefresh = { handleRefreshClick() },
                            modifier = Modifier
                                .widthIn(max = if (isPortraitTablet) 640.dp else 600.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .graphicsLayer { alpha = chromeAlpha }
                        )

                        NotificationStatusBanner(
                            isAccessGranted = isAccessGranted,
                            onEnableClick = {
                                MusicNotificationListenerService.openNotificationAccessSettings(context)
                            },
                            onRefreshClick = { handleRefreshClick() },
                            modifier = Modifier
                                .widthIn(max = if (isPortraitTablet) 560.dp else 600.dp)
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                                .graphicsLayer { alpha = chromeAlpha }
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isProcessingAction) {
                                ProcessingOverlay(actionStatusText)
                            }
                        }

                        // ANCHORED BOTTOM QUICK SLIDER PILL (BG Blur / Glass Blur / Size / Glass Opacity)
                        // All tabs use Live Scrubbing Preview: surrounding chrome fades smoothly (200ms) while active slider stays locked in place
                        QuickControlSliderPill(
                            activeTab = quickControlTab,
                            onTabSelect = { quickControlTab = it },
                            blurRadiusPx = effectiveStyleConfig.backgroundBlurRadius,
                            cardBlurRadiusPx = effectiveStyleConfig.cardBlurRadius,
                            cardScale = effectiveStyleConfig.cardScale,
                            cardOpacity = effectiveStyleConfig.cardOpacity,
                            chromeAlpha = chromeAlpha,
                            activeScrubTarget = liveScrubTarget,
                            onBlurChange = { newRadius ->
                                dragBlurRadius = newRadius.coerceIn(0f, 40f)
                            },
                            onBlurScrubChange = { isDown ->
                                liveScrubTarget = if (isDown) LiveScrubTarget.BG_BLUR else LiveScrubTarget.NONE
                            },
                            onBlurFinish = {
                                liveScrubTarget = LiveScrubTarget.NONE
                                val committed = (dragBlurRadius ?: persistedStyleConfig.backgroundBlurRadius).coerceIn(0f, 40f)
                                dragBlurRadius = null
                                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                    current.copy(
                                        useBlurredAlbumBackground = true,
                                        useTransparentBackground = false,
                                        backgroundBlurRadius = committed
                                    )
                                }
                            },
                            onCardBlurChange = { newBlur ->
                                dragCardBlur = newBlur.coerceIn(0f, 40f)
                            },
                            onCardBlurScrubChange = { isDown ->
                                liveScrubTarget = if (isDown) LiveScrubTarget.CARD_BLUR else LiveScrubTarget.NONE
                            },
                            onCardBlurFinish = {
                                liveScrubTarget = LiveScrubTarget.NONE
                                val committed = (dragCardBlur ?: persistedStyleConfig.cardBlurRadius).coerceIn(0f, 40f)
                                dragCardBlur = null
                                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                    current.copy(cardBlurRadius = committed)
                                }
                            },
                            onCardScaleChange = { newScale ->
                                dragCardScale = newScale.coerceIn(0.60f, 1.20f)
                            },
                            onCardScaleScrubChange = { isDown ->
                                liveScrubTarget = if (isDown) LiveScrubTarget.CARD_SIZE else LiveScrubTarget.NONE
                            },
                            onCardScaleFinish = {
                                liveScrubTarget = LiveScrubTarget.NONE
                                val committed = (dragCardScale ?: persistedStyleConfig.cardScale).coerceIn(0.60f, 1.20f)
                                dragCardScale = null
                                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                    current.copy(cardScale = committed)
                                }
                            },
                            onCardOpacityChange = { newOpacity ->
                                dragCardOpacity = newOpacity.coerceIn(0.0f, 1.0f)
                            },
                            onCardOpacityScrubChange = { isDown ->
                                liveScrubTarget = if (isDown) LiveScrubTarget.CARD_OPACITY else LiveScrubTarget.NONE
                            },
                            onCardOpacityFinish = {
                                liveScrubTarget = LiveScrubTarget.NONE
                                val committed = (dragCardOpacity ?: persistedStyleConfig.cardOpacity).coerceIn(0.0f, 1.0f)
                                dragCardOpacity = null
                                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                                    current.copy(cardOpacity = committed)
                                }
                            },
                            modifier = Modifier
                                .widthIn(max = 540.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                        )

                        ShareActionsBar(
                            onCopyLink = {
                                CardShareManager.copyTrackLink(context, currentTrack, effectiveStyleConfig.brandMode)
                            },
                            onSaveToGallery = { handleSaveImageClick() },
                            onPreviewCard = { showPreviewDialog = true },
                            onShareWhatsApp = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = when {
                                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for WhatsApp..."
                                    isVideoExportExpected(s, c) -> "Exporting 9:16 video for WhatsApp..."
                                    else -> "Preparing for WhatsApp..."
                                }
                                runShareAction(msg) {
                                    CardShareManager.shareToWhatsApp(context, t, p, s, c)
                                }
                            },
                            onShareInstagramStory = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = when {
                                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Instagram..."
                                    isVideoExportExpected(s, c) -> "Exporting 9:16 video for Instagram Story..."
                                    else -> "Preparing Instagram Story..."
                                }
                                runShareAction(msg) {
                                    CardShareManager.shareToInstagramStory(context, t, p, s, c)
                                }
                            },
                            onShareSnapchatStory = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = when {
                                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Snapchat..."
                                    isVideoExportExpected(s, c) -> "Exporting 9:16 video for Snapchat Story..."
                                    else -> "Preparing Snapchat Story..."
                                }
                                runShareAction(msg) {
                                    CardShareManager.shareToSnapchatStory(context, t, p, s, c)
                                }
                            },
                            onShareDiscord = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = when {
                                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF for Discord..."
                                    isVideoExportExpected(s, c) -> "Exporting 9:16 video for Discord..."
                                    else -> "Preparing for Discord..."
                                }
                                runShareAction(msg) {
                                    CardShareManager.shareToDiscord(context, t, p, s, c)
                                }
                            },
                            onNativeShare = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = when {
                                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF..."
                                    isVideoExportExpected(s, c) -> "Exporting 9:16 MP4 video..."
                                    else -> "Opening Share Sheet..."
                                }
                                runShareAction(msg) {
                                    CardShareManager.shareStoryCard(context, t, p, s, c)
                                }
                            },
                            modifier = Modifier
                                .graphicsLayer { alpha = chromeAlpha }
                                .testTag("bottom_share_actions_bar")
                        )
                    }
                }
            }
        }
    }

    if (showEditSheet) {
        EditCardBottomSheet(
            palette = palette,
            styleConfig = effectiveStyleConfig,
            currentTrack = currentTrack,
            onDismiss = {
                dragBlurRadius = null
                dragCardScale = null
                dragCardOpacity = null
                dragCardBlur = null
                dragLyricsBlur = null
                showEditSheet = false
            },
            onStyleChange = { newConfig ->
                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { newConfig }
            },
            onLiveBlurPreview = { dragBlurRadius = it },
            onLiveCardScalePreview = { dragCardScale = it },
            onLiveCardOpacityPreview = { dragCardOpacity = it },
            onLiveCardBlurPreview = { dragCardBlur = it },
            onLiveLyricsBlurPreview = { dragLyricsBlur = it },
            onRequestAudioBeatSync = { enableSync ->
                handleAudioBeatSyncRequest(enableSync)
            },
            onTrackSelect = { selectedTrack ->
                MediaNotificationRepository.selectSampleTrack(selectedTrack)
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            styleConfig = effectiveStyleConfig,
            currentTrack = currentTrack,
            palette = palette,
            isAccessGranted = isAccessGranted,
            onStyleChange = { updated ->
                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { updated }
            },
            onRequestAudioBeatSync = { enableSync ->
                handleAudioBeatSyncRequest(enableSync)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showPreviewDialog) {
        CleanExportPreviewDialog(
            track = currentTrack,
            palette = palette,
            styleConfig = effectiveStyleConfig,
            activeCanvasVideoUrl = activeCanvasUrl,
            onDismiss = { showPreviewDialog = false },
            onSaveClick = {
                showPreviewDialog = false
                handleSaveImageClick()
            },
            onShareClick = {
                showPreviewDialog = false
                val t = MediaNotificationRepository.currentTrack.value
                val p = MediaNotificationRepository.extractedPalette.value
                val s = MediaNotificationRepository.styleConfig.value
                val c = MediaNotificationRepository.activeCanvasUrl.value
                val msg = when {
                    isGifExportExpected(s) -> "Exporting transparent 9:16 GIF..."
                    isVideoExportExpected(s, c) -> "Exporting 9:16 MP4 video..."
                    else -> "Opening Share Sheet..."
                }
                runShareAction(msg) {
                    CardShareManager.shareStoryCard(context, t, p, s, c)
                }
            }
        )
    }
}

@Composable
private fun CleanExportPreviewDialog(
    track: MediaTrackInfo,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    activeCanvasVideoUrl: String?,
    onDismiss: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    val context = LocalContext.current
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showHud by remember { mutableStateOf(true) }
    val hasAnimatedCanvas = !activeCanvasVideoUrl.isNullOrBlank() && !styleConfig.useTransparentBackground
    val hasAnimatedWaveform = styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD &&
        styleConfig.enableWaveform

    LaunchedEffect(track, palette, styleConfig, activeCanvasVideoUrl) {
        val rendered = if (hasAnimatedCanvas || hasAnimatedWaveform) {
            CardShareManager.generateTransparentCardOverlayBitmap(
                context = context,
                track = track,
                palette = palette,
                styleConfig = styleConfig,
                targetWidth = 1080,
                targetHeight = 1920,
                includeWaveform = !hasAnimatedWaveform
            )
        } else {
            CardShareManager.generateStoryBitmap(context, track, palette, styleConfig)
        }
        val previous = previewBitmap
        previewBitmap = rendered
        if (previous != null && previous !== rendered && !previous.isRecycled) {
            previous.recycle()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val current = previewBitmap
            if (current != null && !current.isRecycled) {
                current.recycle()
            }
            previewBitmap = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showHud = !showHud }
                .testTag("export_preview_dialog"),
            contentAlignment = Alignment.Center
        ) {
            val bmp = previewBitmap
            if (hasAnimatedCanvas || hasAnimatedWaveform) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fitByHeight = maxWidth * 16f >= maxHeight * 9f
                    Box(
                        modifier = Modifier
                            .then(if (fitByHeight) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = fitByHeight)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0E0E12))
                    ) {
                        BlurredAlbumBackground(
                            track = track,
                            palette = palette,
                            styleConfig = styleConfig,
                            activeCanvasVideoUrl = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (hasAnimatedCanvas && activeCanvasVideoUrl != null) {
                            AnimatedCanvasVideoBackground(
                                videoUrl = activeCanvasVideoUrl,
                                blurRadiusPx = styleConfig.backgroundBlurRadius,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imgBmp,
                                contentDescription = "Animated 9:16 Export Preview",
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (hasAnimatedWaveform) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val wfRect = remember(
                                    track.title,
                                    track.artist,
                                    styleConfig.cardScale,
                                    styleConfig.cornerRadiusDp
                                ) {
                                    CardShareManager.computeFloatingGlassWaveformRect(
                                        targetWidth = 1080,
                                        targetHeight = 1920,
                                        track = track,
                                        styleConfig = styleConfig
                                    )
                                }
                                val wfLeft = maxWidth * (wfRect.left / 1080f)
                                val wfTop = maxHeight * (wfRect.top / 1920f)
                                val wfW = maxWidth * (wfRect.width() / 1080f)
                                val wfH = maxHeight * (wfRect.height() / 1920f)
                                DynamicIslandWaveformBars(
                                    track = track,
                                    color = styleConfig.waveformColor,
                                    beatSyncEnabled = styleConfig.waveformBeatSync,
                                    speedMultiplier = styleConfig.waveformSpeedMultiplier,
                                    animateAlways = true,
                                    modifier = Modifier
                                        .offset(x = wfLeft, y = wfTop)
                                        .size(width = wfW, height = wfH)
                                )
                            }
                        }
                    }
                }
            } else if (bmp != null && !bmp.isRecycled) {
                val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fitByHeight = maxWidth * 16f >= maxHeight * 9f
                    Box(
                        modifier = Modifier
                            .then(if (fitByHeight) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = fitByHeight)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        if (styleConfig.useTransparentBackground) {
                            TransparentEditorCheckerboard(modifier = Modifier.fillMaxSize())
                        }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = "Share Card 9:16 Export Preview",
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFF2B44))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Rendering 9:16 preview...",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                }
            }

            if (showHud) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = when {
                                styleConfig.useTransparentBackground && hasAnimatedWaveform ->
                                    "Transparent 9:16 GIF Sticker • Tap to hide UI"
                                styleConfig.useTransparentBackground ->
                                    "Transparent 9:16 PNG • Tap to hide UI"
                                hasAnimatedCanvas ->
                                    "Canvas Video Preview (9:16) • Tap to hide UI"
                                hasAnimatedWaveform ->
                                    "8s Waveform Video Preview (9:16) • Tap to hide UI"
                                else ->
                                    "Export Preview (9:16) • Tap image to hide UI"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .testTag("close_preview_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSaveClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("preview_save_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onShareClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("preview_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareScreenTopBar(
    isFitToScreen: Boolean,
    onTogglePreviewScale: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    compactTitle: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.28f), CircleShape)
                .testTag("top_bar_close_button")
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        Text(
            text = "Share",
            color = Color.White,
            fontSize = if (compactTitle) 15.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 4.dp)
                .testTag("top_bar_title")
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.28f), CircleShape)
                    .testTag("top_bar_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }

            IconButton(
                onClick = onTogglePreviewScale,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isFitToScreen) Color(0xFFFF2B44).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.28f),
                        CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = if (isFitToScreen) 0.45f else 0.16f),
                        shape = CircleShape
                    )
                    .testTag("preview_scale_toggle_button")
            ) {
                PreviewScaleVectorIcon(
                    isFitToScreen = isFitToScreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            FilledTonalButton(
                onClick = onEdit,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.92f),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("card_edit_pill_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit card",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Edit",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.28f), CircleShape)
                    .testTag("top_bar_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun PreviewScaleVectorIcon(
    isFitToScreen: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
        val pad = size.minDimension * 0.12f
        val arm = size.minDimension * 0.22f
        val w = size.width
        val h = size.height

        if (isFitToScreen) {
            // Centered mini 9:16 card inside brackets
            val cardH = h * 0.62f
            val cardW = cardH * (9f / 16f)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset((w - cardW) / 2f, (h - cardH) / 2f),
                size = Size(cardW, cardH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = stroke
            )
            // Outer corners
            drawLine(Color.White.copy(alpha = 0.7f), Offset(pad, pad), Offset(pad + arm, pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(pad, pad), Offset(pad, pad + arm), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(w - pad, h - pad), Offset(w - pad - arm, h - pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(w - pad, h - pad), Offset(w - pad, h - pad - arm), strokeWidth = stroke.width, cap = StrokeCap.Round)
        } else {
            // 4 corner full-screen brackets
            drawLine(Color.White, Offset(pad, pad), Offset(pad + arm, pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(pad, pad), Offset(pad, pad + arm), strokeWidth = stroke.width, cap = StrokeCap.Round)

            drawLine(Color.White, Offset(w - pad, pad), Offset(w - pad - arm, pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(w - pad, pad), Offset(w - pad, pad + arm), strokeWidth = stroke.width, cap = StrokeCap.Round)

            drawLine(Color.White, Offset(pad, h - pad), Offset(pad + arm, h - pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(pad, h - pad), Offset(pad, h - pad - arm), strokeWidth = stroke.width, cap = StrokeCap.Round)

            drawLine(Color.White, Offset(w - pad, h - pad), Offset(w - pad - arm, h - pad), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(w - pad, h - pad), Offset(w - pad, h - pad - arm), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ProcessingOverlay(statusText: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.76f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFFFF2B44))
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Unified Bottom Quick Control Pill with BG Blur, Glass Blur, Card Size, and Glass Opacity.
 * All sliders implement Live Scrubbing Preview: touching/dragging fades surrounding pill chrome
 * & screen toolbars (200ms) while keeping the active slider + minimal value badge locked in place.
 */
@Composable
private fun QuickControlSliderPill(
    activeTab: QuickControlTab,
    onTabSelect: (QuickControlTab) -> Unit,
    blurRadiusPx: Float,
    cardBlurRadiusPx: Float,
    cardScale: Float,
    cardOpacity: Float,
    chromeAlpha: Float,
    activeScrubTarget: LiveScrubTarget,
    onBlurChange: (Float) -> Unit,
    onBlurScrubChange: (Boolean) -> Unit,
    onBlurFinish: () -> Unit,
    onCardBlurChange: (Float) -> Unit,
    onCardBlurScrubChange: (Boolean) -> Unit,
    onCardBlurFinish: () -> Unit,
    onCardScaleChange: (Float) -> Unit,
    onCardScaleScrubChange: (Boolean) -> Unit,
    onCardScaleFinish: () -> Unit,
    onCardOpacityChange: (Float) -> Unit,
    onCardOpacityScrubChange: (Boolean) -> Unit,
    onCardOpacityFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isScrubbing = activeScrubTarget != LiveScrubTarget.NONE

    val pillShape = RoundedCornerShape(18.dp)
    val pillBgAlpha by animateFloatAsState(
        targetValue = if (isScrubbing) 0.14f else 0.46f,
        animationSpec = tween(durationMillis = 200, easing = EaseInOut),
        label = "quick_pill_bg_alpha"
    )
    val labelText = when (activeTab) {
        QuickControlTab.BLUR -> "BG Blur"
        QuickControlTab.CARD_BLUR -> "Glass Blur"
        QuickControlTab.SIZE -> "Card Size"
        QuickControlTab.OPACITY -> "Glass Opacity"
    }
    val valueText = when (activeTab) {
        QuickControlTab.BLUR -> "${blurRadiusPx.roundToInt()}px"
        QuickControlTab.CARD_BLUR -> "${cardBlurRadiusPx.roundToInt()}px"
        QuickControlTab.SIZE -> "${(cardScale * 100f).roundToInt()}%"
        QuickControlTab.OPACITY -> "${(cardOpacity * 100f).roundToInt()}%"
    }

    val blurInteraction = remember { MutableInteractionSource() }
    val cardBlurInteraction = remember { MutableInteractionSource() }
    val sizeInteraction = remember { MutableInteractionSource() }
    val opacityInteraction = remember { MutableInteractionSource() }

    val blurDragged by blurInteraction.collectIsDraggedAsState()
    val blurPressed by blurInteraction.collectIsPressedAsState()
    val cardBlurDragged by cardBlurInteraction.collectIsDraggedAsState()
    val cardBlurPressed by cardBlurInteraction.collectIsPressedAsState()
    val sizeDragged by sizeInteraction.collectIsDraggedAsState()
    val sizePressed by sizeInteraction.collectIsPressedAsState()
    val opacityDragged by opacityInteraction.collectIsDraggedAsState()
    val opacityPressed by opacityInteraction.collectIsPressedAsState()

    LaunchedEffect(activeTab, blurDragged, blurPressed, cardBlurDragged, cardBlurPressed, sizeDragged, sizePressed, opacityDragged, opacityPressed) {
        when (activeTab) {
            QuickControlTab.BLUR -> onBlurScrubChange(blurDragged || blurPressed)
            QuickControlTab.CARD_BLUR -> onCardBlurScrubChange(cardBlurDragged || cardBlurPressed)
            QuickControlTab.SIZE -> onCardScaleScrubChange(sizeDragged || sizePressed)
            QuickControlTab.OPACITY -> onCardOpacityScrubChange(opacityDragged || opacityPressed)
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = pillBgAlpha), pillShape)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isScrubbing) 0.06f else 0.14f),
                shape = pillShape
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("external_blur_control_container")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer { alpha = chromeAlpha }
            ) {
                QuickControlTab.values().forEach { tab ->
                    val selected = tab == activeTab
                    Surface(
                        onClick = { onTabSelect(tab) },
                        shape = RoundedCornerShape(50),
                        color = if (selected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.10f),
                        modifier = Modifier.testTag("quick_tab_${tab.name.lowercase()}")
                    ) {
                        Text(
                            text = tab.label,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Surface(
                color = Color.Black.copy(alpha = if (isScrubbing) 0.58f else 0.25f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = if (isScrubbing) "$labelText • $valueText" else valueText,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                        .testTag("background_blur_value_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        when (activeTab) {
            QuickControlTab.BLUR -> {
                Slider(
                    value = blurRadiusPx.coerceIn(0f, 40f),
                    onValueChange = {
                        onBlurScrubChange(true)
                        onBlurChange(it)
                    },
                    onValueChangeFinished = onBlurFinish,
                    valueRange = 0f..40f,
                    interactionSource = blurInteraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onBlurScrubChange(true)
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    onBlurScrubChange(false)
                                }
                            }
                        }
                        .testTag("background_blur_slider")
                )
            }
            QuickControlTab.CARD_BLUR -> {
                Slider(
                    value = cardBlurRadiusPx.coerceIn(0f, 40f),
                    onValueChange = {
                        onCardBlurScrubChange(true)
                        onCardBlurChange(it)
                    },
                    onValueChangeFinished = onCardBlurFinish,
                    valueRange = 0f..40f,
                    interactionSource = cardBlurInteraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onCardBlurScrubChange(true)
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    onCardBlurScrubChange(false)
                                }
                            }
                        }
                        .testTag("quick_card_blur_slider")
                )
            }
            QuickControlTab.SIZE -> {
                Slider(
                    value = cardScale.coerceIn(0.60f, 1.20f),
                    onValueChange = { newScale ->
                        onCardScaleScrubChange(true)
                        onCardScaleChange(newScale)
                    },
                    onValueChangeFinished = onCardScaleFinish,
                    valueRange = 0.60f..1.20f,
                    interactionSource = sizeInteraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onCardScaleScrubChange(true)
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    onCardScaleScrubChange(false)
                                }
                            }
                        }
                        .testTag("quick_card_size_slider")
                )
            }
            QuickControlTab.OPACITY -> {
                Slider(
                    value = cardOpacity.coerceIn(0.0f, 1.0f),
                    onValueChange = { newOpacity ->
                        onCardOpacityScrubChange(true)
                        onCardOpacityChange(newOpacity)
                    },
                    onValueChangeFinished = onCardOpacityFinish,
                    valueRange = 0.0f..1.0f,
                    interactionSource = opacityInteraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onCardOpacityScrubChange(true)
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    onCardOpacityScrubChange(false)
                                }
                            }
                        }
                        .testTag("quick_card_opacity_slider")
                )
            }
        }
    }
}

private tailrec fun findActivity(context: Context): Activity? {
    return when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
