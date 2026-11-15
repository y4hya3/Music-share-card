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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.service.MusicNotificationListenerService
import com.example.ui.components.AnimatedCanvasVideoBackground
import com.example.ui.components.BlurredAlbumBackground
import com.example.ui.components.EditCardBottomSheet
import com.example.ui.components.MusicStoryCard
import com.example.ui.components.NotificationStatusBanner
import com.example.ui.components.ShareActionsBar
import com.example.util.CardShareManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Adaptive Main Share Screen optimized for both compact phones (Redmi 9C) and
 * 10.4" tablets in portrait & landscape (Samsung Galaxy Tab A7 LTE):
 * - Landscape wide screens (>= 600dp width & landscape): Two-pane layout (floating card left,
 *   controls + external blur slider + 3x2 share grid right) with zero clipping.
 * - Portrait phones & tablets: Vertical layout with dynamic BoxWithConstraints card scaling.
 */
@Composable
fun BitChordShareScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentTrack by MediaNotificationRepository.currentTrack.collectAsState()
    val palette by MediaNotificationRepository.extractedPalette.collectAsState()
    val styleConfig by MediaNotificationRepository.styleConfig.collectAsState()
    val isAccessGranted by MediaNotificationRepository.isNotificationAccessEnabled.collectAsState()
    val activeCanvasUrl by MediaNotificationRepository.activeCanvasUrl.collectAsState()

    var showEditSheet by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var isProcessingAction by remember { mutableStateOf(false) }
    var actionStatusText by remember { mutableStateOf("") }

    fun triggerSaveToGallery() {
        if (!isProcessingAction) {
            isProcessingAction = true
            val latestTrack = MediaNotificationRepository.currentTrack.value
            val latestPalette = MediaNotificationRepository.extractedPalette.value
            val latestStyle = MediaNotificationRepository.styleConfig.value
            val latestCanvas = MediaNotificationRepository.activeCanvasUrl.value
            actionStatusText = if (!latestCanvas.isNullOrBlank()) {
                "Exporting animated Canvas video..."
            } else {
                "Saving to Pictures/EchoSnap..."
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
        val synced = MediaNotificationRepository.refreshFromActiveMedia(context)
        if (synced) {
            val syncedTrack = MediaNotificationRepository.currentTrack.value
            Toast.makeText(context, "Synced: ${syncedTrack.title}", Toast.LENGTH_SHORT).show()
        } else {
            MusicNotificationListenerService.rebindIfPossible(context)
            Toast.makeText(context, "Play a song in any music app, then tap Refresh", Toast.LENGTH_SHORT).show()
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

    LaunchedEffect(Unit) {
        MediaNotificationRepository.refreshFromActiveMedia(context)
    }

    LaunchedEffect(isAccessGranted) {
        if (isAccessGranted) {
            MediaNotificationRepository.refreshFromActiveMedia(context)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12))
    ) {
        // LAYER 0: Full-screen blurred song/album cover background + rgba(0, 0, 0, 0.25) overlay
        BlurredAlbumBackground(
            track = currentTrack,
            palette = palette,
            styleConfig = styleConfig,
            activeCanvasVideoUrl = activeCanvasUrl,
            modifier = Modifier
                .fillMaxSize()
                .testTag("fullscreen_blurred_background")
        )

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
                val isWideLandscape = maxWidth >= 600.dp && maxWidth > maxHeight
                val isPortraitTablet = maxWidth >= 600.dp && !isWideLandscape

                if (isWideLandscape) {
                    // TABLET LANDSCAPE TWO-PANE LAYOUT (e.g., Samsung Galaxy Tab A7 LTE Landscape)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Pane: Centered Floating Share Card scaled to fit viewport height cleanly
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val adaptiveArtMax = (maxHeight - 170.dp).coerceIn(150.dp, 360.dp)
                            val adaptiveCardMax = minOf(maxWidth * 0.85f, 380.dp)

                            MusicStoryCard(
                                track = currentTrack,
                                palette = palette,
                                styleConfig = styleConfig,
                                maxCardWidth = adaptiveCardMax,
                                maxArtSize = adaptiveArtMax,
                                modifier = Modifier.testTag("center_story_card")
                            )

                            if (isProcessingAction) {
                                ProcessingOverlay(actionStatusText)
                            }
                        }

                        // Right Pane: Header, Banner, External Blur Slider, and 3x2 Share Grid
                        Column(
                            modifier = Modifier
                                .widthIn(min = 310.dp, max = 420.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ShareScreenTopBar(
                                onClose = { findActivity(context)?.finish() },
                                onEdit = { showEditSheet = true },
                                onRefresh = { handleRefreshClick() },
                                compactTitle = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            NotificationStatusBanner(
                                isAccessGranted = isAccessGranted,
                                onEnableClick = {
                                    MusicNotificationListenerService.openNotificationAccessSettings(context)
                                },
                                onRefreshClick = { handleRefreshClick() }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            ExternalBlurControlPill(
                                blurRadiusPx = styleConfig.backgroundBlurRadius,
                                onBlurChange = { newRadius ->
                                    MediaNotificationRepository.updateStyleConfig { current ->
                                        current.copy(
                                            useBlurredAlbumBackground = true,
                                            backgroundBlurRadius = newRadius.coerceIn(0f, 40f)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            ShareActionsBar(
                                isGridLayout = true,
                                onCopyLink = {
                                    CardShareManager.copyTrackLink(context, currentTrack, styleConfig.brandMode)
                                },
                                onSaveToGallery = { handleSaveImageClick() },
                                onPreviewCard = { showPreviewDialog = true },
                                onShareWhatsApp = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for WhatsApp..." else "Preparing for WhatsApp..."
                                    runShareAction(msg) {
                                        CardShareManager.shareToWhatsApp(context, t, p, s, c)
                                    }
                                },
                                onShareInstagramStory = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Instagram Story..." else "Preparing Instagram Story..."
                                    runShareAction(msg) {
                                        CardShareManager.shareToInstagramStory(context, t, p, s, c)
                                    }
                                },
                                onShareSnapchatStory = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Snapchat Story..." else "Preparing Snapchat Story..."
                                    runShareAction(msg) {
                                        CardShareManager.shareToSnapchatStory(context, t, p, s, c)
                                    }
                                },
                                onShareDiscord = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Discord..." else "Preparing for Discord..."
                                    runShareAction(msg) {
                                        CardShareManager.shareToDiscord(context, t, p, s, c)
                                    }
                                },
                                onNativeShare = {
                                    val t = MediaNotificationRepository.currentTrack.value
                                    val p = MediaNotificationRepository.extractedPalette.value
                                    val s = MediaNotificationRepository.styleConfig.value
                                    val c = MediaNotificationRepository.activeCanvasUrl.value
                                    val msg = if (!c.isNullOrBlank()) "Exporting Canvas video..." else "Opening Share Sheet..."
                                    runShareAction(msg) {
                                        CardShareManager.shareStoryCard(context, t, p, s, c)
                                    }
                                },
                                modifier = Modifier.testTag("bottom_share_actions_bar")
                            )
                        }
                    }
                } else {
                    // PORTRAIT LAYOUT (Redmi 9C & Samsung Galaxy Tab A7 Portrait)
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ShareScreenTopBar(
                            onClose = { findActivity(context)?.finish() },
                            onEdit = { showEditSheet = true },
                            onRefresh = { handleRefreshClick() },
                            modifier = Modifier
                                .widthIn(max = if (isPortraitTablet) 640.dp else 600.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                        )

                        // CENTER: Sharp Floating Share Card sized dynamically to prevent clipping
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val maxW = if (isPortraitTablet) minOf(maxWidth * 0.72f, 390.dp) else minOf(maxWidth * 0.88f, 330.dp)
                            val maxArt = (maxHeight - 150.dp).coerceIn(145.dp, if (isPortraitTablet) 360.dp else 300.dp)

                            MusicStoryCard(
                                track = currentTrack,
                                palette = palette,
                                styleConfig = styleConfig,
                                maxCardWidth = maxW,
                                maxArtSize = maxArt,
                                modifier = Modifier.testTag("center_story_card")
                            )

                            if (isProcessingAction) {
                                ProcessingOverlay(actionStatusText)
                            }
                        }

                        // EXTERNAL BLUR SLIDER CONTROL (0px..40px, kept strictly outside card)
                        ExternalBlurControlPill(
                            blurRadiusPx = styleConfig.backgroundBlurRadius,
                            onBlurChange = { newRadius ->
                                MediaNotificationRepository.updateStyleConfig { current ->
                                    current.copy(
                                        useBlurredAlbumBackground = true,
                                        backgroundBlurRadius = newRadius.coerceIn(0f, 40f)
                                    )
                                }
                            },
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                        )

                        // BOTTOM SHARE ACTIONS BAR
                        ShareActionsBar(
                            onCopyLink = {
                                CardShareManager.copyTrackLink(context, currentTrack, styleConfig.brandMode)
                            },
                            onSaveToGallery = { handleSaveImageClick() },
                            onPreviewCard = { showPreviewDialog = true },
                            onShareWhatsApp = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for WhatsApp..." else "Preparing for WhatsApp..."
                                runShareAction(msg) {
                                    CardShareManager.shareToWhatsApp(context, t, p, s, c)
                                }
                            },
                            onShareInstagramStory = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Instagram Story..." else "Preparing Instagram Story..."
                                runShareAction(msg) {
                                    CardShareManager.shareToInstagramStory(context, t, p, s, c)
                                }
                            },
                            onShareSnapchatStory = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Snapchat Story..." else "Preparing Snapchat Story..."
                                runShareAction(msg) {
                                    CardShareManager.shareToSnapchatStory(context, t, p, s, c)
                                }
                            },
                            onShareDiscord = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video for Discord..." else "Preparing for Discord..."
                                runShareAction(msg) {
                                    CardShareManager.shareToDiscord(context, t, p, s, c)
                                }
                            },
                            onNativeShare = {
                                val t = MediaNotificationRepository.currentTrack.value
                                val p = MediaNotificationRepository.extractedPalette.value
                                val s = MediaNotificationRepository.styleConfig.value
                                val c = MediaNotificationRepository.activeCanvasUrl.value
                                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video..." else "Opening Share Sheet..."
                                runShareAction(msg) {
                                    CardShareManager.shareStoryCard(context, t, p, s, c)
                                }
                            },
                            modifier = Modifier.testTag("bottom_share_actions_bar")
                        )
                    }
                }
            }
        }
    }

    if (showEditSheet) {
        EditCardBottomSheet(
            palette = palette,
            styleConfig = styleConfig,
            currentTrack = currentTrack,
            onDismiss = { showEditSheet = false },
            onStyleChange = { newConfig ->
                MediaNotificationRepository.updateStyleConfig { newConfig }
            },
            onTrackSelect = { selectedTrack ->
                MediaNotificationRepository.selectSampleTrack(selectedTrack)
            }
        )
    }

    if (showPreviewDialog) {
        CleanExportPreviewDialog(
            track = currentTrack,
            palette = palette,
            styleConfig = styleConfig,
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
                val msg = if (!c.isNullOrBlank()) "Exporting Canvas video..." else "Opening Share Sheet..."
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
    val hasAnimatedCanvas = !activeCanvasVideoUrl.isNullOrBlank()

    LaunchedEffect(track, palette, styleConfig, activeCanvasVideoUrl) {
        val rendered = if (hasAnimatedCanvas) {
            CardShareManager.generateTransparentCardOverlayBitmap(
                context = context,
                track = track,
                palette = palette,
                styleConfig = styleConfig,
                targetWidth = 1080,
                targetHeight = 1920
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
            if (hasAnimatedCanvas && activeCanvasVideoUrl != null) {
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
                        AnimatedCanvasVideoBackground(
                            videoUrl = activeCanvasVideoUrl,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && styleConfig.backgroundBlurRadius > 2f) {
                                        Modifier.blur(
                                            radius = (styleConfig.backgroundBlurRadius * 0.45f).dp,
                                            edgeTreatment = BlurredEdgeTreatment.Rectangle
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                        if (bmp != null && !bmp.isRecycled) {
                            val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imgBmp,
                                contentDescription = "Animated Canvas 9:16 Export Preview",
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else if (bmp != null && !bmp.isRecycled) {
                val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = imgBmp,
                    contentDescription = "Share Card 9:16 Export Preview",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
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
                // Top bar
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
                            text = if (hasAnimatedCanvas) {
                                "Canvas Video Preview (9:16) • Tap to hide UI"
                            } else {
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
                            .size(44.dp)
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

                // Bottom quick Save / Share buttons
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
            text = "Share this music",
            color = Color.White,
            fontSize = if (compactTitle) 15.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("top_bar_title")
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(
                onClick = onEdit,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.92f),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("card_edit_pill_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit card",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
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
private fun ProcessingOverlay(statusText: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
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

@Composable
private fun ExternalBlurControlPill(
    blurRadiusPx: Float,
    onBlurChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.44f), pillShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), pillShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("external_blur_control_container")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Background Blur",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${blurRadiusPx.roundToInt()}px",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("background_blur_value_text")
            )
        }

        Slider(
            value = blurRadiusPx.coerceIn(0f, 40f),
            onValueChange = onBlurChange,
            valueRange = 0f..40f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFFFF2B44),
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag("background_blur_slider")
        )
    }
}

private tailrec fun findActivity(context: Context): Activity? {
    return when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
