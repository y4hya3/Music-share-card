package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardShadowColorMode
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.repository.SavedMediaLibrary
import com.example.service.AnimatedCanvasCandidate
import com.example.service.OnlineCoverCandidate
import com.example.service.SpotifyAuthManager
import com.example.ui.SpotifyLoginActivity
import java.util.Locale
import kotlin.math.roundToInt

private enum class SheetLiveScrub {
    NONE,
    CARD_SIZE,
    CARD_OPACITY,
    BG_BLUR,
    CARD_BLUR,
    LYRICS_BLUR
}

private val GlassCardFill = Color(0x16FFFFFF)
private val GlassCardStrongFill = Color(0x22FFFFFF)
private val GlassBorderColor = Color(0x26FFFFFF)

/**
 * Frosted Glass Edit Card Bottom Sheet (v1.3.0).
 * Features:
 * - Translucent frosted-glass styling with Live Scrubbing Preview on Card Size & Opacity
 * - Local Saved Covers & Animated Canvases browser + long-press max-resolution saver
 * - Dynamic Island 4-bar audio waveform customization (Floating Glass Card)
 * - 16-provider lyrics auto-search with interactive multi-line selection
 * - Transparent No-Background PNG option + Full-Screen / Fit-to-Screen toggle
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EditCardBottomSheet(
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    currentTrack: MediaTrackInfo,
    onDismiss: () -> Unit,
    onStyleChange: (CardStyleConfig) -> Unit,
    onLiveBlurPreview: (Float?) -> Unit = {},
    onLiveCardScalePreview: (Float?) -> Unit = {},
    onLiveCardOpacityPreview: (Float?) -> Unit = {},
    onLiveCardBlurPreview: (Float?) -> Unit = {},
    onLiveLyricsBlurPreview: (Float?) -> Unit = {},
    onRequestAudioBeatSync: (Boolean) -> Unit = {},
    onTrackSelect: (MediaTrackInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeScrub by remember { mutableStateOf(SheetLiveScrub.NONE) }
    val isScrubTouchActive = remember { AtomicBoolean(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { activeScrub == SheetLiveScrub.NONE && !isScrubTouchActive.get() }
    )
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val onlineCovers by MediaNotificationRepository.onlineCoverCandidates.collectAsState()
    val isSearchingCovers by MediaNotificationRepository.isSearchingCoverArt.collectAsState()
    val originalPlayerArt by MediaNotificationRepository.originalPlayerArt.collectAsState()
    val selectedCoverId by MediaNotificationRepository.selectedCoverId.collectAsState()

    val canvasCandidates by MediaNotificationRepository.animatedCanvasCandidates.collectAsState()
    val isSearchingCanvas by MediaNotificationRepository.isSearchingCanvas.collectAsState()
    val canvasSearchStatus by MediaNotificationRepository.canvasSearchStatus.collectAsState()
    val spotifyReloginRequired by MediaNotificationRepository.spotifyReloginRequired.collectAsState()
    val activeCanvasId by MediaNotificationRepository.activeCanvasId.collectAsState()
    val activeCanvasUrl by MediaNotificationRepository.activeCanvasUrl.collectAsState()

    val savedCovers by SavedMediaLibrary.savedCovers.collectAsState()
    val savedCanvases by SavedMediaLibrary.savedCanvases.collectAsState()

    val lyricsCandidates by MediaNotificationRepository.lyricsCandidates.collectAsState()
    val isSearchingLyrics by MediaNotificationRepository.isSearchingLyrics.collectAsState()
    val lyricsSearchStatus by MediaNotificationRepository.lyricsSearchStatus.collectAsState()

    var editTitle by remember(currentTrack.title, currentTrack.packageName) {
        mutableStateOf(currentTrack.title)
    }
    var editArtist by remember(currentTrack.artist, currentTrack.packageName) {
        mutableStateOf(currentTrack.artist)
    }
    var coverSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }
    var canvasSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }
    var lyricsSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }
    var revealSpotifyToken by remember { mutableStateOf(false) }
    var showAdvancedSpotifyOptions by remember { mutableStateOf(false) }

    // Long-press save & saved browsers state
    var coverCandidateToSave by remember { mutableStateOf<OnlineCoverCandidate?>(null) }
    var saveCurrentCoverPrompt by remember { mutableStateOf(false) }
    var canvasCandidateToSave by remember { mutableStateOf<AnimatedCanvasCandidate?>(null) }
    var showSavedCoversDialog by remember { mutableStateOf(false) }
    var showSavedCanvasesDialog by remember { mutableStateOf(false) }

    // Interactive lyrics picker state (Lines & Word-by-Word)
    var selectedProviderIndex by remember(lyricsCandidates) { mutableStateOf(0) }
    var selectedLineIndices by remember(lyricsCandidates, selectedProviderIndex) {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var selectedWordCoords by remember(lyricsCandidates, selectedProviderIndex) {
        mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    }

    // Live scrubbing state values inside Edit sheet (works for Card Size, Glass Opacity, BG Blur, Card Blur, Lyrics Blur)
    var latestCardScale by remember(styleConfig.cardScale) { mutableFloatStateOf(styleConfig.cardScale) }
    var latestCardOpacity by remember(styleConfig.cardOpacity) { mutableFloatStateOf(styleConfig.cardOpacity) }
    var latestBgBlur by remember(styleConfig.backgroundBlurRadius) { mutableFloatStateOf(styleConfig.backgroundBlurRadius) }
    var latestCardBlur by remember(styleConfig.cardBlurRadius) { mutableFloatStateOf(styleConfig.cardBlurRadius) }
    var latestLyricsBlur by remember(styleConfig.lyricsArtBlurRadius) { mutableFloatStateOf(styleConfig.lyricsArtBlurRadius) }
    val scrubNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (activeScrub != SheetLiveScrub.NONE || isScrubTouchActive.get()) available else Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                return if (activeScrub != SheetLiveScrub.NONE || isScrubTouchActive.get()) available else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (activeScrub != SheetLiveScrub.NONE || isScrubTouchActive.get()) available else Velocity.Zero
            }
        }
    }

    val chromeAlphaState: State<Float> = animateFloatAsState(
        targetValue = if (activeScrub != SheetLiveScrub.NONE) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = EaseInOut),
        label = "sheet_live_scrub_chrome_alpha"
    )
    val sheetGlassAlphaState: State<Float> = animateFloatAsState(
        targetValue = if (activeScrub != SheetLiveScrub.NONE) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = EaseInOut),
        label = "sheet_glass_surface_alpha"
    )
    fun scrubVisibilityAlpha(target: SheetLiveScrub): Float {
        return if (activeScrub == target) 1f else chromeAlphaState.value
    }

    // Zero-permission Photo/Video pickers (Google Play compliant)
    val coverMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Importing HD cover...", Toast.LENGTH_SHORT).show()
            MediaNotificationRepository.importLocalCoverUri(uri) { saved ->
                val msg = if (saved != null) "Saved cover: ${saved.name}" else "Could not import image"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val canvasVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Importing canvas video...", Toast.LENGTH_SHORT).show()
            MediaNotificationRepository.importLocalCanvasUri(uri) { saved ->
                val msg = if (saved != null) "Saved canvas: ${saved.name}" else "Could not import video"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val sheetShape = remember { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) }
    val sheetBaseBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xD820222E).copy(alpha = 0.85f),
                Color(0xEB12131B).copy(alpha = 0.92f)
            )
        )
    }
    val sheetSurfaceModifier = Modifier
        .fillMaxWidth()
        .clip(sheetShape)
        .drawBehind {
            val a = sheetGlassAlphaState.value.coerceIn(0f, 1f)
            if (a > 0.005f) {
                drawRect(brush = sheetBaseBrush, alpha = a)
            }
        }
        .drawWithContent {
            drawContent()
            val a = sheetGlassAlphaState.value.coerceIn(0f, 1f)
            if (a > 0.005f) {
                val strokePx = 1.dp.toPx()
                val cornerPx = 28.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f * a),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height + cornerPx),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = strokePx)
                )
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.28f),
        dragHandle = null,
        contentColor = Color.White,
        modifier = modifier.testTag("edit_card_bottom_sheet")
    ) {
        Box(
            modifier = sheetSurfaceModifier
                .nestedScroll(scrubNestedScrollConnection)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (isScrubTouchActive.get() || activeScrub != SheetLiveScrub.NONE) {
                                event.changes.forEach { change ->
                                    val dy = change.position.y - change.previousPosition.y
                                    if (kotlin.math.abs(dy) > 0f && !change.isConsumed) {
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 12.dp, bottom = 36.dp)
                    .verticalScroll(scrollState, enabled = activeScrub == SheetLiveScrub.NONE)
            ) {
                // Frosted Glass Drag Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .graphicsLayer { alpha = chromeAlphaState.value }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.30f))
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chromeAlphaState.value },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Customize Share Card",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Frosted Glass Studio • Long-press online art or video to save locally",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_edit_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.80f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 1. CARD BRAND
                Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                    Text(
                        text = "Card Logo & Name",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Text(
                        text = "Choose Bitchord, Spotify, or Apple Music branding.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrandModeChip(
                            brandMode = CardBrandMode.BITCHORD,
                            previewBadgeColor = Color(0xFFFF2B44),
                            previewLogoColorMode = LogoColorMode.RED,
                            isSelected = styleConfig.brandMode == CardBrandMode.BITCHORD,
                            onClick = {
                                val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                                    LogoColorMode.RED
                                } else {
                                    styleConfig.logoColorMode
                                }
                                onStyleChange(
                                    styleConfig.copy(
                                        brandMode = CardBrandMode.BITCHORD,
                                        logoColorMode = nextColor
                                    )
                                )
                            },
                            modifier = Modifier.testTag("brand_mode_bitchord")
                        )

                        BrandModeChip(
                            brandMode = CardBrandMode.SPOTIFY,
                            previewBadgeColor = Color(0xFF1DB954),
                            previewLogoColorMode = LogoColorMode.GREEN,
                            isSelected = styleConfig.brandMode == CardBrandMode.SPOTIFY,
                            onClick = {
                                val nextColor = if (styleConfig.logoColorMode == LogoColorMode.RED) {
                                    LogoColorMode.GREEN
                                } else {
                                    styleConfig.logoColorMode
                                }
                                onStyleChange(
                                    styleConfig.copy(
                                        brandMode = CardBrandMode.SPOTIFY,
                                        logoColorMode = nextColor
                                    )
                                )
                            },
                            modifier = Modifier.testTag("brand_mode_spotify")
                        )

                        BrandModeChip(
                            brandMode = CardBrandMode.APPLE_MUSIC,
                            previewBadgeColor = Color(0xFFFA243C),
                            previewLogoColorMode = LogoColorMode.RED,
                            isSelected = styleConfig.brandMode == CardBrandMode.APPLE_MUSIC,
                            onClick = {
                                val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                                    LogoColorMode.RED
                                } else {
                                    styleConfig.logoColorMode
                                }
                                onStyleChange(
                                    styleConfig.copy(
                                        brandMode = CardBrandMode.APPLE_MUSIC,
                                        logoColorMode = nextColor
                                    )
                                )
                            },
                            modifier = Modifier.testTag("brand_mode_apple_music")
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 2. LOGO BADGE COLOR
                    Text(
                        text = "Logo Badge Color",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LogoColorOption(
                            label = "Red",
                            color = Color(0xFFFF2B44),
                            isSelected = styleConfig.logoColorMode == LogoColorMode.RED,
                            onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.RED)) }
                        )
                        LogoColorOption(
                            label = "Template",
                            color = palette.vibrantColor,
                            isSelected = styleConfig.logoColorMode == LogoColorMode.TEMPLATE_DYNAMIC,
                            onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.TEMPLATE_DYNAMIC)) }
                        )
                        LogoColorOption(
                            label = "Green",
                            color = Color(0xFF1DB954),
                            isSelected = styleConfig.logoColorMode == LogoColorMode.GREEN,
                            onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.GREEN)) }
                        )
                        LogoColorOption(
                            label = "White",
                            color = Color.White,
                            isSelected = styleConfig.logoColorMode == LogoColorMode.WHITE,
                            onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.WHITE)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // 3. HIGH-QUALITY COVER ART SEARCH + SAVED LIBRARY
                    Text(
                        text = "High-Quality Cover Art",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Text(
                        text = "Tap to apply, long-press any cover to rename & download in max resolution locally.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GlassCardFill,
                        border = BorderStroke(1.dp, GlassBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Auto-search HD cover online",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Automatically finds 1000–3000px covers when songs play",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.autoSearchOnlineArt,
                                onCheckedChange = { enabled ->
                                    onStyleChange(styleConfig.copy(autoSearchOnlineArt = enabled))
                                    if (enabled && onlineCovers.isEmpty()) {
                                        MediaNotificationRepository.searchOnlineCovers(
                                            customQuery = coverSearchQuery,
                                            autoApplyBest = !currentTrack.isSample
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF2B44)
                                ),
                                modifier = Modifier.testTag("auto_search_online_art_switch")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = coverSearchQuery,
                            onValueChange = { coverSearchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Song & artist", fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF2B44),
                                unfocusedBorderColor = GlassBorderColor,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("online_cover_search_input")
                        )

                        Button(
                            onClick = {
                                MediaNotificationRepository.searchOnlineCovers(
                                    customQuery = coverSearchQuery,
                                    autoApplyBest = false
                                )
                            },
                            enabled = !isSearchingCovers && coverSearchQuery.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                            modifier = Modifier.testTag("search_online_cover_button")
                        ) {
                            if (isSearchingCovers) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search HD covers",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Find", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item(key = "cover_saved_library") {
                            ActionLibraryTile(
                                title = "Saved",
                                subtitle = "${savedCovers.size} offline",
                                accentColor = Color(0xFFFF2B44),
                                isAddIcon = false,
                                isHighlighted = selectedCoverId.startsWith("saved_cover_"),
                                onClick = { showSavedCoversDialog = true },
                                modifier = Modifier.testTag("cover_saved_library_tile")
                            )
                        }

                        item(key = "cover_add_local") {
                            ActionLibraryTile(
                                title = "+ Add",
                                subtitle = "From Photos",
                                accentColor = Color.White,
                                isAddIcon = true,
                                isHighlighted = false,
                                onClick = {
                                    coverMediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("cover_add_local_tile")
                            )
                        }

                        originalPlayerArt?.let { orig ->
                            item(key = "player_art") {
                                CoverArtChoiceTile(
                                    bitmap = orig,
                                    title = "Player Art",
                                    subtitle = "${orig.width}×${orig.height}",
                                    isSelected = selectedCoverId == "player",
                                    onClick = { MediaNotificationRepository.restoreOriginalPlayerCover() },
                                    onLongClick = { saveCurrentCoverPrompt = true },
                                    modifier = Modifier.testTag("cover_choice_player")
                                )
                            }
                        }

                        items(onlineCovers, key = { it.id }) { candidate ->
                            candidate.thumbnailBitmap?.let { thumb ->
                                CoverArtChoiceTile(
                                    bitmap = thumb,
                                    title = candidate.source,
                                    subtitle = candidate.resolutionLabel,
                                    isSelected = selectedCoverId == candidate.id,
                                    onClick = { MediaNotificationRepository.selectOnlineCover(candidate) },
                                    onLongClick = { coverCandidateToSave = candidate },
                                    modifier = Modifier.testTag("cover_choice_${candidate.id}")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // 3B. ANIMATED SONG CANVAS & SAVED CANVASES
                    Text(
                        text = "Animated Song Canvas",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Text(
                        text = "Only looping video clips appear here. Long-press any video clip to rename & download locally.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GlassCardFill,
                        border = BorderStroke(1.dp, GlassBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Auto-scratch animated canvas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Searches Spotify Canvas + Apple Motion across any music player",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.autoSearchAnimatedCanvas,
                                onCheckedChange = { enabled ->
                                    onStyleChange(styleConfig.copy(autoSearchAnimatedCanvas = enabled))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                ),
                                modifier = Modifier.testTag("auto_search_canvas_switch")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val isSpotifyConnected = styleConfig.spotifyAccessToken.isNotBlank() ||
                        styleConfig.spotifySpDcCookie.isNotBlank()
                    val sessionBorderColor = when {
                        spotifyReloginRequired -> Color(0xFFFFA726).copy(alpha = 0.78f)
                        isSpotifyConnected -> Color(0xFF1DB954).copy(alpha = 0.55f)
                        else -> GlassBorderColor
                    }
                    val sessionBgColor = when {
                        spotifyReloginRequired -> Color(0x55422B14)
                        isSpotifyConnected -> Color(0x55183522)
                        else -> GlassCardFill
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = sessionBgColor,
                        border = BorderStroke(width = 1.dp, color = sessionBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (isSpotifyConnected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (spotifyReloginRequired) Color(0xFFFFA726) else Color(0xFF1DB954))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (spotifyReloginRequired) {
                                                    "Session Expired: ${styleConfig.spotifyAccountLabel.ifBlank { "Spotify Account" }}"
                                                } else {
                                                    "Connected: ${styleConfig.spotifyAccountLabel.ifBlank { "Spotify Account" }}"
                                                },
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.testTag("spotify_connected_label")
                                            )
                                            Text(
                                                text = if (spotifyReloginRequired) {
                                                    "Spotify cookie expired — tap Reconnect Now so Canvas works again"
                                                } else {
                                                    "Auto-renew active for non-Spotify & offline players"
                                                },
                                                color = if (spotifyReloginRequired) Color(0xFFFFD599) else Color(0xFF9BE7B3),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        onClick = {
                                            context.startActivity(SpotifyLoginActivity.createIntent(context))
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (spotifyReloginRequired) Color(0xFFFFA726) else Color(0x66253A2C),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("spotify_reconnect_button")
                                    ) {
                                        Text(
                                            text = if (spotifyReloginRequired) "Reconnect Now" else "Reconnect",
                                            color = if (spotifyReloginRequired) Color(0xFF14110B) else Color(0xFF1DB954),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        )
                                    }
                                    Surface(
                                        onClick = {
                                            MediaNotificationRepository.disconnectSpotifyAccount()
                                            Toast.makeText(context, "Logged out of Spotify", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0x663A2429),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("spotify_logout_button")
                                    ) {
                                        Text(
                                            text = "Log Out",
                                            color = Color(0xFFFF8A96),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        context.startActivity(SpotifyLoginActivity.createIntent(context))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1DB954),
                                        contentColor = Color(0xFF0A0C0B)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_login_button")
                                ) {
                                    Text(
                                        text = "Login to Spotify",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Spotify's Canvas server requires a Spotify login token. Apple Motion previews work without logging in.",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                onClick = { showAdvancedSpotifyOptions = !showAdvancedSpotifyOptions },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("toggle_advanced_spotify_button")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Advanced: sp_dc Cookie / Token / PKCE",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (showAdvancedSpotifyOptions) "Hide" else "Show",
                                        color = Color(0xFF1DB954),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (showAdvancedSpotifyOptions) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = styleConfig.spotifySpDcCookie,
                                        onValueChange = { raw ->
                                            val cleaned = SpotifyAuthManager.extractSpDcValue(raw)
                                            onStyleChange(styleConfig.copy(spotifySpDcCookie = cleaned))
                                        },
                                        singleLine = true,
                                        label = { Text("Spotify sp_dc Cookie", fontSize = 12.sp) },
                                        placeholder = { Text("Paste sp_dc value (AQA...)", fontSize = 11.sp) },
                                        visualTransformation = if (revealSpotifyToken) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF1DB954),
                                            unfocusedBorderColor = GlassBorderColor,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = Color(0xFF1DB954),
                                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("spotify_sp_dc_input")
                                    )

                                    Button(
                                        onClick = {
                                            MediaNotificationRepository.connectWithSpDcCookie(styleConfig.spotifySpDcCookie) { _, msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF1DB954),
                                            contentColor = Color(0xFF0A0C0B)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                        modifier = Modifier.testTag("spotify_apply_sp_dc_button")
                                    ) {
                                        Text("Apply", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = styleConfig.spotifyAccessToken,
                                    onValueChange = { token ->
                                        if (SpotifyAuthManager.looksLikeSpDcCookie(token)) {
                                            val spDc = SpotifyAuthManager.extractSpDcValue(token)
                                            onStyleChange(styleConfig.copy(spotifySpDcCookie = spDc))
                                        } else {
                                            onStyleChange(styleConfig.copy(spotifyAccessToken = token))
                                        }
                                    },
                                    singleLine = true,
                                    label = { Text("Spotify Access Token (manual)") },
                                    placeholder = { Text("Paste Bearer token (or sp_dc cookie)", fontSize = 12.sp) },
                                    visualTransformation = if (revealSpotifyToken) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        Surface(
                                            onClick = { revealSpotifyToken = !revealSpotifyToken },
                                            shape = RoundedCornerShape(8.dp),
                                            color = GlassCardStrongFill,
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .testTag("toggle_spotify_token_visibility")
                                        ) {
                                            Text(
                                                text = if (revealSpotifyToken) "Hide" else "Show",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF1DB954),
                                        unfocusedBorderColor = GlassBorderColor,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = Color(0xFF1DB954),
                                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_token_input")
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = styleConfig.spotifyClientId,
                                        onValueChange = { cid ->
                                            onStyleChange(styleConfig.copy(spotifyClientId = cid))
                                        },
                                        singleLine = true,
                                        label = { Text("Spotify Client ID (PKCE)", fontSize = 12.sp) },
                                        placeholder = { Text("Optional App Client ID", fontSize = 11.sp) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF1DB954),
                                            unfocusedBorderColor = GlassBorderColor,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = Color(0xFF1DB954),
                                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("spotify_client_id_input")
                                    )

                                    Button(
                                        onClick = {
                                            val intent = SpotifyAuthManager.buildPkceAuthorizeIntent(
                                                context = context,
                                                clientId = styleConfig.spotifyClientId
                                            )
                                            if (intent != null) {
                                                context.startActivity(intent)
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Enter a Spotify Client ID or tap Login to Spotify above",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GlassCardStrongFill,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                        modifier = Modifier.testTag("spotify_pkce_oauth_button")
                                    ) {
                                        Text("OAuth", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = canvasSearchQuery,
                            onValueChange = { canvasSearchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Smart canvas query", fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF1DB954),
                                unfocusedBorderColor = GlassBorderColor,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("canvas_search_input")
                        )

                        Button(
                            onClick = {
                                MediaNotificationRepository.searchAnimatedCanvases(
                                    customQuery = canvasSearchQuery,
                                    autoApplyBest = styleConfig.autoSearchAnimatedCanvas
                                )
                            },
                            enabled = !isSearchingCanvas && canvasSearchQuery.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1DB954),
                                contentColor = Color(0xFF0A0A0D)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                            modifier = Modifier.testTag("search_animated_canvas_button")
                        ) {
                            if (isSearchingCanvas) {
                                CircularProgressIndicator(
                                    color = Color(0xFF0A0A0D),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Search animated canvas",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Canvas", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    canvasSearchStatus?.let { status ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = GlassCardFill,
                            border = BorderStroke(1.dp, GlassBorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = status,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("canvas_search_status_text")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item(key = "canvas_static_none") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .width(92.dp)
                                    .height(118.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { MediaNotificationRepository.clearAnimatedCanvas() }
                                    .background(if (activeCanvasId == "none") Color(0x44233028) else GlassCardFill)
                                    .border(
                                        width = if (activeCanvasId == "none") 2.dp else 1.dp,
                                        color = if (activeCanvasId == "none") Color(0xFF1DB954) else GlassBorderColor,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(8.dp)
                                    .testTag("canvas_choice_none")
                            ) {
                                Text(
                                    text = "Static",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Cover Art Only",
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 9.sp,
                                    maxLines = 2
                                )
                            }
                        }

                        item(key = "canvas_saved_library") {
                            ActionLibraryTile(
                                title = "Saved",
                                subtitle = "${savedCanvases.size} videos",
                                accentColor = Color(0xFF1DB954),
                                isAddIcon = false,
                                isHighlighted = activeCanvasId.startsWith("saved_canvas_"),
                                onClick = { showSavedCanvasesDialog = true },
                                modifier = Modifier.testTag("canvas_saved_library_tile")
                            )
                        }

                        item(key = "canvas_add_local") {
                            ActionLibraryTile(
                                title = "+ Add",
                                subtitle = "From Device",
                                accentColor = Color.White,
                                isAddIcon = true,
                                isHighlighted = false,
                                onClick = {
                                    canvasVideoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                modifier = Modifier.testTag("canvas_add_local_tile")
                            )
                        }

                        items(canvasCandidates, key = { it.id }) { candidate ->
                            AnimatedCanvasChoiceTile(
                                candidate = candidate,
                                isSelected = activeCanvasId == candidate.id,
                                onClick = { MediaNotificationRepository.selectAnimatedCanvas(candidate) },
                                onLongClick = { canvasCandidateToSave = candidate },
                                modifier = Modifier.testTag("canvas_choice_${candidate.id}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                }

                // 4. CARD SIZE (Supports Live Scrubbing Preview inside Edit Sheet!)
                val sizeInteraction = remember { MutableInteractionSource() }
                val sizeDragged by sizeInteraction.collectIsDraggedAsState()
                val sizePressed by sizeInteraction.collectIsPressedAsState()
                LaunchedEffect(sizeDragged, sizePressed) {
                    if (sizeDragged || sizePressed) {
                        activeScrub = SheetLiveScrub.CARD_SIZE
                    } else if (activeScrub == SheetLiveScrub.CARD_SIZE) {
                        activeScrub = SheetLiveScrub.NONE
                    }
                }
                Column(
                    modifier = Modifier.graphicsLayer { alpha = scrubVisibilityAlpha(SheetLiveScrub.CARD_SIZE) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Card Size",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        Surface(
                            color = if (activeScrub == SheetLiveScrub.CARD_SIZE) Color(0xAA000000) else Color.Transparent,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "${(styleConfig.cardScale * 100f).roundToInt()}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Slider(
                        value = styleConfig.cardScale.coerceIn(0.60f, 1.20f),
                        onValueChange = { newScale ->
                            val clamped = newScale.coerceIn(0.60f, 1.20f)
                            latestCardScale = clamped
                            activeScrub = SheetLiveScrub.CARD_SIZE
                            onLiveCardScalePreview(clamped)
                        },
                        onValueChangeFinished = {
                            isScrubTouchActive.set(false)
                            activeScrub = SheetLiveScrub.NONE
                            onStyleChange(styleConfig.copy(cardScale = latestCardScale.coerceIn(0.60f, 1.20f)))
                            onLiveCardScalePreview(null)
                        },
                        valueRange = 0.60f..1.20f,
                        interactionSource = sizeInteraction,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF2B44),
                            activeTrackColor = Color(0xFFFF2B44),
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    isScrubTouchActive.set(true)
                                    activeScrub = SheetLiveScrub.CARD_SIZE
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        isScrubTouchActive.set(false)
                                        if (activeScrub == SheetLiveScrub.CARD_SIZE) {
                                            activeScrub = SheetLiveScrub.NONE
                                        }
                                    }
                                }
                            }
                            .testTag("card_size_slider")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4B. GLASS OPACITY (Supports Live Scrubbing Preview inside Edit Sheet! Affects frosted glass only)
                val opacityInteraction = remember { MutableInteractionSource() }
                val opacityDragged by opacityInteraction.collectIsDraggedAsState()
                val opacityPressed by opacityInteraction.collectIsPressedAsState()
                LaunchedEffect(opacityDragged, opacityPressed) {
                    if (opacityDragged || opacityPressed) {
                        activeScrub = SheetLiveScrub.CARD_OPACITY
                    } else if (activeScrub == SheetLiveScrub.CARD_OPACITY) {
                        activeScrub = SheetLiveScrub.NONE
                    }
                }
                Column(
                    modifier = Modifier.graphicsLayer { alpha = scrubVisibilityAlpha(SheetLiveScrub.CARD_OPACITY) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Frosted Glass Opacity",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        Surface(
                            color = if (activeScrub == SheetLiveScrub.CARD_OPACITY) Color(0xAA000000) else Color.Transparent,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "${(styleConfig.cardOpacity * 100f).roundToInt()}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Slider(
                        value = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f),
                        onValueChange = { newOpacity ->
                            val clamped = newOpacity.coerceIn(0.0f, 1.0f)
                            latestCardOpacity = clamped
                            activeScrub = SheetLiveScrub.CARD_OPACITY
                            onLiveCardOpacityPreview(clamped)
                        },
                        onValueChangeFinished = {
                            isScrubTouchActive.set(false)
                            activeScrub = SheetLiveScrub.NONE
                            onStyleChange(styleConfig.copy(cardOpacity = latestCardOpacity.coerceIn(0.0f, 1.0f)))
                            onLiveCardOpacityPreview(null)
                        },
                        valueRange = 0.0f..1.0f,
                        interactionSource = opacityInteraction,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF2B44),
                            activeTrackColor = Color(0xFFFF2B44),
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    isScrubTouchActive.set(true)
                                    activeScrub = SheetLiveScrub.CARD_OPACITY
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        isScrubTouchActive.set(false)
                                        if (activeScrub == SheetLiveScrub.CARD_OPACITY) {
                                            activeScrub = SheetLiveScrub.NONE
                                        }
                                    }
                                }
                            }
                            .testTag("card_opacity_slider")
                    )
                }

                // 5. BACKGROUND STYLE & PREVIEW SCALE TOGGLE
                Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Background Style & Preview Mode",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isBlurredSelected = !styleConfig.useTransparentBackground && styleConfig.useBlurredAlbumBackground
                        val isGradientSelected = !styleConfig.useTransparentBackground && !styleConfig.useBlurredAlbumBackground
                        val isTransparentSelected = styleConfig.useTransparentBackground

                        Surface(
                            onClick = {
                                onStyleChange(
                                    styleConfig.copy(
                                        useBlurredAlbumBackground = true,
                                        useTransparentBackground = false
                                    )
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isBlurredSelected) Color(0xFFFF2B44) else GlassCardFill,
                            border = BorderStroke(1.dp, if (isBlurredSelected) Color.Transparent else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("bg_mode_blurred_album")
                        ) {
                            Text(
                                text = "Blurred Art",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isBlurredSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            onClick = {
                                onStyleChange(
                                    styleConfig.copy(
                                        useBlurredAlbumBackground = false,
                                        useTransparentBackground = false
                                    )
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isGradientSelected) Color(0xFFFF2B44) else GlassCardFill,
                            border = BorderStroke(1.dp, if (isGradientSelected) Color.Transparent else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("bg_mode_palette_gradient")
                        ) {
                            Text(
                                text = "Gradient",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isGradientSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            onClick = {
                                onStyleChange(styleConfig.copy(useTransparentBackground = true))
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isTransparentSelected) Color(0xFFFF2B44) else GlassCardFill,
                            border = BorderStroke(1.dp, if (isTransparentSelected) Color.Transparent else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("bg_mode_transparent_png")
                        ) {
                            Text(
                                text = "No BG (PNG)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isTransparentSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (styleConfig.useTransparentBackground) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val gifModeActive = styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD &&
                            styleConfig.enableWaveform
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0x331DB954),
                            border = BorderStroke(1.dp, Color(0x661DB954)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (gifModeActive) {
                                    "Transparent Animated GIF active: Saves/shares an infinitely looping 9:16 transparent GIF sticker with live 4-bar waveform!"
                                } else {
                                    "Transparent Alpha PNG active: Saves/exports your card with 100% transparent background (works with all 4 card layouts for video/photo editors)."
                                },
                                color = Color(0xFFC8F7D6),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "On-Screen Preview Scale (Export is always full 9:16)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { onStyleChange(styleConfig.copy(isFitToScreenPreview = false)) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (!styleConfig.isFitToScreenPreview) Color(0xFFFF2B44) else GlassCardFill,
                            border = BorderStroke(1.dp, if (!styleConfig.isFitToScreenPreview) Color.Transparent else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_preview_full_screen")
                        ) {
                            Text(
                                text = "Full-Screen",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (!styleConfig.isFitToScreenPreview) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp)
                            )
                        }
                        Surface(
                            onClick = { onStyleChange(styleConfig.copy(isFitToScreenPreview = true)) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (styleConfig.isFitToScreenPreview) Color(0xFFFF2B44) else GlassCardFill,
                            border = BorderStroke(1.dp, if (styleConfig.isFitToScreenPreview) Color.Transparent else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_preview_fit_screen")
                        ) {
                            Text(
                                text = "Fit-to-Screen 9:16",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (styleConfig.isFitToScreenPreview) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp)
                            )
                        }
                    }
                }

                // 5B. BACKGROUND & CANVAS BLUR INTENSITY (Supports Live Scrubbing Preview!)
                if (!styleConfig.useTransparentBackground &&
                    (styleConfig.useBlurredAlbumBackground || !activeCanvasUrl.isNullOrBlank())
                ) {
                    val bgBlurInteraction = remember { MutableInteractionSource() }
                    val bgBlurDragged by bgBlurInteraction.collectIsDraggedAsState()
                    val bgBlurPressed by bgBlurInteraction.collectIsPressedAsState()
                    LaunchedEffect(bgBlurDragged, bgBlurPressed) {
                        if (bgBlurDragged || bgBlurPressed) {
                            activeScrub = SheetLiveScrub.BG_BLUR
                        } else if (activeScrub == SheetLiveScrub.BG_BLUR) {
                            activeScrub = SheetLiveScrub.NONE
                        }
                    }
                    Column(
                        modifier = Modifier.graphicsLayer { alpha = scrubVisibilityAlpha(SheetLiveScrub.BG_BLUR) }
                    ) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (!activeCanvasUrl.isNullOrBlank()) {
                                    "Background & Canvas Blur Intensity"
                                } else {
                                    "Background Blur Intensity"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                            Surface(
                                color = if (activeScrub == SheetLiveScrub.BG_BLUR) Color(0xAA000000) else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "${styleConfig.backgroundBlurRadius.roundToInt()}px",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f),
                            onValueChange = { next ->
                                val clamped = next.coerceIn(0f, 40f)
                                latestBgBlur = clamped
                                activeScrub = SheetLiveScrub.BG_BLUR
                                onLiveBlurPreview(clamped)
                            },
                            onValueChangeFinished = {
                                isScrubTouchActive.set(false)
                                activeScrub = SheetLiveScrub.NONE
                                onStyleChange(
                                    styleConfig.copy(
                                        backgroundBlurRadius = latestBgBlur.coerceIn(0f, 40f)
                                    )
                                )
                                onLiveBlurPreview(null)
                            },
                            valueRange = 0f..40f,
                            interactionSource = bgBlurInteraction,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF2B44),
                                activeTrackColor = Color(0xFFFF2B44),
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        isScrubTouchActive.set(true)
                                        activeScrub = SheetLiveScrub.BG_BLUR
                                        try {
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                            } while (event.changes.any { it.pressed })
                                        } finally {
                                            isScrubTouchActive.set(false)
                                            if (activeScrub == SheetLiveScrub.BG_BLUR) {
                                                activeScrub = SheetLiveScrub.NONE
                                            }
                                        }
                                    }
                                }
                                .testTag("sheet_background_blur_slider")
                        )
                    }
                }

                // 5C & 6. GRADIENTS + CARD LAYOUT STYLE
                Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                    if (!styleConfig.useTransparentBackground && !styleConfig.useBlurredAlbumBackground) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Adaptive Palette Gradients",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GradientStyle.values().forEach { style ->
                                val isSelected = !styleConfig.useBlurredAlbumBackground && styleConfig.gradientStyle == style
                                Surface(
                                    onClick = {
                                        onStyleChange(
                                            styleConfig.copy(
                                                useBlurredAlbumBackground = false,
                                                useTransparentBackground = false,
                                                gradientStyle = style
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFFFF2B44) else GlassCardFill,
                                    border = BorderStroke(1.dp, if (isSelected) Color.Transparent else GlassBorderColor),
                                    modifier = Modifier.testTag("gradient_preset_${style.name}")
                                ) {
                                    Text(
                                        text = style.label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Card Layout Style",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2
                    ) {
                        CardLayoutType.values().forEach { layout ->
                            val isSelected = styleConfig.cardLayoutType == layout
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(cardLayoutType = layout)) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xFFFF2B44) else GlassCardFill,
                                border = BorderStroke(1.dp, if (isSelected) Color.Transparent else GlassBorderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("layout_type_${layout.name}")
                            ) {
                                Text(
                                    text = layout.label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // FLOATING GLASS BLUR SLIDER (Supports Live Scrubbing Preview independently of chromeAlpha!)
                if (styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD) {
                    val cardBlurInteraction = remember { MutableInteractionSource() }
                    val cardBlurDragged by cardBlurInteraction.collectIsDraggedAsState()
                    val cardBlurPressed by cardBlurInteraction.collectIsPressedAsState()
                    LaunchedEffect(cardBlurDragged, cardBlurPressed) {
                        if (cardBlurDragged || cardBlurPressed) {
                            activeScrub = SheetLiveScrub.CARD_BLUR
                        } else if (activeScrub == SheetLiveScrub.CARD_BLUR) {
                            activeScrub = SheetLiveScrub.NONE
                        }
                    }
                    Column(
                        modifier = Modifier.graphicsLayer { alpha = scrubVisibilityAlpha(SheetLiveScrub.CARD_BLUR) }
                    ) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Floating Glass Blur",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f)
                            )
                            Surface(
                                color = if (activeScrub == SheetLiveScrub.CARD_BLUR) Color(0xAA000000) else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "${styleConfig.cardBlurRadius.roundToInt()}px",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Decrease for clearer see-through glass, increase for deeper frosted blur",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.58f),
                            modifier = Modifier
                                .padding(top = 2.dp, bottom = 4.dp)
                                .graphicsLayer { alpha = chromeAlphaState.value }
                        )
                        Slider(
                            value = styleConfig.cardBlurRadius.coerceIn(0f, 40f),
                            onValueChange = { next ->
                                val clamped = next.coerceIn(0f, 40f)
                                latestCardBlur = clamped
                                activeScrub = SheetLiveScrub.CARD_BLUR
                                onLiveCardBlurPreview(clamped)
                            },
                            onValueChangeFinished = {
                                isScrubTouchActive.set(false)
                                activeScrub = SheetLiveScrub.NONE
                                onStyleChange(styleConfig.copy(cardBlurRadius = latestCardBlur.coerceIn(0f, 40f)))
                                onLiveCardBlurPreview(null)
                            },
                            valueRange = 0f..40f,
                            interactionSource = cardBlurInteraction,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF2B44),
                                activeTrackColor = Color(0xFFFF2B44),
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        isScrubTouchActive.set(true)
                                        activeScrub = SheetLiveScrub.CARD_BLUR
                                        try {
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                            } while (event.changes.any { it.pressed })
                                        } finally {
                                            isScrubTouchActive.set(false)
                                            if (activeScrub == SheetLiveScrub.CARD_BLUR) {
                                                activeScrub = SheetLiveScrub.NONE
                                            }
                                        }
                                    }
                                }
                                .testTag("sheet_card_blur_slider")
                        )
                    }
                }

                    // FLOATING GLASS WAVEFORM CARD (fades during live scrub)
                    if (styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD) {
                        Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = GlassCardFill,
                                border = BorderStroke(1.dp, GlassBorderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                            Text(
                                                text = "Dynamic Island 4-Bar Waveform",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = if (styleConfig.useTransparentBackground) {
                                                    "Animated 4 bars on glass card • Saves as transparent looping GIF sticker"
                                                } else {
                                                    "Animated 4 bars on glass card • Exports as 8s MP4 loop video over photo/gradient"
                                                },
                                                color = Color.White.copy(alpha = 0.60f),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Switch(
                                            checked = styleConfig.enableWaveform,
                                            onCheckedChange = { enabled ->
                                                onStyleChange(styleConfig.copy(enableWaveform = enabled))
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = Color(0xFFFF2B44)
                                            ),
                                            modifier = Modifier.testTag("enable_waveform_switch")
                                        )
                                    }

                                    if (styleConfig.enableWaveform) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Waveform Bar Color",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        val swatchOptions = listOf(
                                            "White" to Color.White,
                                            "Green" to Color(0xFF1DB954),
                                            "Red" to Color(0xFFFF2B44),
                                            "Cyan" to Color(0xFF38E8FF),
                                            "Amber" to Color(0xFFFFB830),
                                            "Violet" to Color(0xFFA855F7),
                                            "Pink" to Color(0xFFFF4D9E),
                                            "Accent" to palette.vibrantColor
                                        )
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            swatchOptions.forEach { (label, color) ->
                                                val isPicked = styleConfig.waveformColor.toArgb() == color.toArgb()
                                                val isLightSwatch = color == Color.White || color == Color(0xFF38E8FF) || color == Color(0xFFFFB830)
                                                Box(
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .border(
                                                            width = if (isPicked) 2.5.dp else 1.dp,
                                                            color = if (isPicked) Color.White else GlassBorderColor,
                                                            shape = CircleShape
                                                        )
                                                        .clickable {
                                                            onStyleChange(
                                                                styleConfig.copy(waveformColor = color)
                                                            )
                                                        }
                                                        .testTag("waveform_color_$label"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isPicked) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = label,
                                                            tint = if (isLightSwatch) Color.Black else Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                                Text(
                                                    text = "Sync to Live Audio Beats",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "Zero-latency response to playing beats, or switch off for Default Speed",
                                                    color = Color.White.copy(alpha = 0.58f),
                                                    fontSize = 10.5.sp
                                                )
                                            }
                                            Switch(
                                                checked = styleConfig.waveformBeatSync,
                                                onCheckedChange = { wantsSync ->
                                                    onRequestAudioBeatSync(wantsSync)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Color(0xFF1DB954)
                                                ),
                                                modifier = Modifier.testTag("waveform_beat_sync_switch")
                                            )
                                        }

                                        if (!styleConfig.waveformBeatSync) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            var localEditWaveSpeed by remember(styleConfig.waveformSpeedMultiplier) {
                                                mutableFloatStateOf(styleConfig.waveformSpeedMultiplier.coerceIn(0.4f, 2.5f))
                                            }
                                            var localEditWaveForce by remember(styleConfig.waveformForce) {
                                                mutableFloatStateOf(styleConfig.waveformForce.coerceIn(0.5f, 2.2f))
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Default Wave Speed",
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = String.format(Locale.US, "%.1fx", localEditWaveSpeed),
                                                    color = Color(0xFFFF8A96),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Slider(
                                                value = localEditWaveSpeed,
                                                onValueChange = { next ->
                                                    val snapped = ((next * 10f).toInt() / 10f).coerceIn(0.4f, 2.5f)
                                                    if (snapped != localEditWaveSpeed) {
                                                        localEditWaveSpeed = snapped
                                                        onStyleChange(styleConfig.copy(waveformSpeedMultiplier = snapped))
                                                    }
                                                },
                                                onValueChangeFinished = {
                                                    onStyleChange(styleConfig.copy(waveformSpeedMultiplier = localEditWaveSpeed))
                                                },
                                                valueRange = 0.4f..2.5f,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color(0xFFFF2B44),
                                                    activeTrackColor = Color(0xFFFF2B44),
                                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                                ),
                                                modifier = Modifier.testTag("edit_waveform_speed_slider")
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Wave Force (Height)",
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = String.format(Locale.US, "%.1fx", localEditWaveForce),
                                                    color = Color(0xFF9BE7B3),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Slider(
                                                value = localEditWaveForce,
                                                onValueChange = { next ->
                                                    val snapped = ((next * 10f).toInt() / 10f).coerceIn(0.5f, 2.2f)
                                                    if (snapped != localEditWaveForce) {
                                                        localEditWaveForce = snapped
                                                        onStyleChange(styleConfig.copy(waveformForce = snapped))
                                                    }
                                                },
                                                onValueChangeFinished = {
                                                    onStyleChange(styleConfig.copy(waveformForce = localEditWaveForce))
                                                },
                                                valueRange = 0.5f..2.2f,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color(0xFF1DB954),
                                                    activeTrackColor = Color(0xFF1DB954),
                                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                                ),
                                                modifier = Modifier.testTag("edit_waveform_force_slider")
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(
                                                    0.7f to "Soft",
                                                    1.1f to "Normal",
                                                    1.5f to "Punchy",
                                                    2.0f to "Max"
                                                ).forEach { (presetForce, label) ->
                                                    val active = kotlin.math.abs(localEditWaveForce - presetForce) < 0.09f
                                                    Surface(
                                                        onClick = {
                                                            localEditWaveForce = presetForce
                                                            onStyleChange(styleConfig.copy(waveformForce = presetForce))
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        color = if (active) Color(0xFF1DB954) else Color.White.copy(alpha = 0.10f),
                                                        modifier = Modifier.testTag("edit_force_preset_$presetForce")
                                                    ) {
                                                        Text(
                                                            text = "$label (${String.format(Locale.US, "%.1fx", presetForce)})",
                                                            color = if (active) Color.Black else Color.White,
                                                            fontSize = 10.5.sp,
                                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // LYRICS CARD CONTROLS + 16-PROVIDER AUTO LYRICS SEARCH (LINES & WORD BY WORD)
                    if (styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_LYRICS_CARD) {
                        Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Lyrics Card Background",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LyricsCardBackgroundMode.values().forEach { bgMode ->
                                    val isBgSelected = styleConfig.lyricsCardBackgroundMode == bgMode
                                    Surface(
                                        onClick = {
                                            onStyleChange(styleConfig.copy(lyricsCardBackgroundMode = bgMode))
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isBgSelected) Color(0xFFFF2B44) else GlassCardFill,
                                        border = BorderStroke(1.dp, if (isBgSelected) Color.Transparent else GlassBorderColor),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("lyrics_bg_${bgMode.name}")
                                    ) {
                                        Text(
                                            text = bgMode.label,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isBgSelected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 11.dp, horizontal = 12.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        if (styleConfig.lyricsCardBackgroundMode == LyricsCardBackgroundMode.SONG_ART) {
                            val lyricsBlurInteraction = remember { MutableInteractionSource() }
                            val lyricsBlurDragged by lyricsBlurInteraction.collectIsDraggedAsState()
                            val lyricsBlurPressed by lyricsBlurInteraction.collectIsPressedAsState()
                            LaunchedEffect(lyricsBlurDragged, lyricsBlurPressed) {
                                if (lyricsBlurDragged || lyricsBlurPressed) {
                                    activeScrub = SheetLiveScrub.LYRICS_BLUR
                                } else if (activeScrub == SheetLiveScrub.LYRICS_BLUR) {
                                    activeScrub = SheetLiveScrub.NONE
                                }
                            }
                            Column(
                                modifier = Modifier.graphicsLayer { alpha = scrubVisibilityAlpha(SheetLiveScrub.LYRICS_BLUR) }
                            ) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Song Art Blur",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                    Surface(
                                        color = if (activeScrub == SheetLiveScrub.LYRICS_BLUR) Color(0xAA000000) else Color.Transparent,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = "${styleConfig.lyricsArtBlurRadius.roundToInt()}px",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Slider(
                                    value = styleConfig.lyricsArtBlurRadius.coerceIn(0f, 40f),
                                    onValueChange = { next ->
                                        val clamped = next.coerceIn(0f, 40f)
                                        latestLyricsBlur = clamped
                                        activeScrub = SheetLiveScrub.LYRICS_BLUR
                                        onLiveLyricsBlurPreview(clamped)
                                    },
                                    onValueChangeFinished = {
                                        isScrubTouchActive.set(false)
                                        activeScrub = SheetLiveScrub.NONE
                                        onStyleChange(styleConfig.copy(lyricsArtBlurRadius = latestLyricsBlur.coerceIn(0f, 40f)))
                                        onLiveLyricsBlurPreview(null)
                                    },
                                    valueRange = 0f..40f,
                                    interactionSource = lyricsBlurInteraction,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF2B44),
                                        activeTrackColor = Color(0xFFFF2B44),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                    ),
                                    modifier = Modifier
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                isScrubTouchActive.set(true)
                                                activeScrub = SheetLiveScrub.LYRICS_BLUR
                                                try {
                                                    do {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    } while (event.changes.any { it.pressed })
                                                } finally {
                                                    isScrubTouchActive.set(false)
                                                    if (activeScrub == SheetLiveScrub.LYRICS_BLUR) {
                                                        activeScrub = SheetLiveScrub.NONE
                                                    }
                                                }
                                            }
                                        }
                                        .testTag("sheet_lyrics_card_blur_slider")
                                )
                            }
                        }

                        // 16-PROVIDER AUTO LYRICS SEARCH + LINES / WORD-BY-WORD PICKER
                        Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = GlassCardFill,
                                border = BorderStroke(1.dp, GlassBorderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Auto-Search Lyrics (16+ Providers)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Genius (Primary), LRCLIB Synced, Lyrics.ovh, Musixmatch, NetEase, Kugou & 10+ more",
                                        fontSize = 10.5.sp,
                                        color = Color.White.copy(alpha = 0.58f),
                                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = lyricsSearchQuery,
                                            onValueChange = { lyricsSearchQuery = it },
                                            singleLine = true,
                                            placeholder = { Text("Song - Artist or Title", fontSize = 12.sp) },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFFFF2B44),
                                                unfocusedBorderColor = GlassBorderColor,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("lyrics_search_input")
                                        )
                                        Button(
                                            onClick = {
                                                MediaNotificationRepository.searchLyrics(lyricsSearchQuery)
                                            },
                                            enabled = !isSearchingLyrics && lyricsSearchQuery.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                            modifier = Modifier.testTag("search_lyrics_button")
                                        ) {
                                            if (isSearchingLyrics) {
                                                CircularProgressIndicator(
                                                    color = Color.White,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Text("Search", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isWordByWord = styleConfig.lyricsWordSelectionMode
                                        Surface(
                                            onClick = {
                                                onStyleChange(styleConfig.copy(lyricsWordSelectionMode = false))
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (!isWordByWord) Color(0xFFFF2B44) else GlassCardStrongFill,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("lyrics_mode_lines")
                                        ) {
                                            Text(
                                                text = "Select Lines",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (!isWordByWord) FontWeight.Bold else FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                            )
                                        }
                                        Surface(
                                            onClick = {
                                                onStyleChange(styleConfig.copy(lyricsWordSelectionMode = true))
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isWordByWord) Color(0xFFFF2B44) else GlassCardStrongFill,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("lyrics_mode_word_by_word")
                                        ) {
                                            Text(
                                                text = "Word by Word",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (isWordByWord) FontWeight.Bold else FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                            )
                                        }
                                    }

                                    lyricsSearchStatus?.let { lStatus ->
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = lStatus,
                                            color = Color.White.copy(alpha = 0.78f),
                                            fontSize = 11.sp,
                                            modifier = Modifier.testTag("lyrics_search_status_text")
                                        )
                                    }

                                    val manualLines = remember(styleConfig.customLyrics) {
                                        styleConfig.customLyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                    }
                                    val activeCand = lyricsCandidates.getOrNull(selectedProviderIndex)
                                    val pickerLines: List<String> = when {
                                        lyricsCandidates.isNotEmpty() -> activeCand?.lines ?: emptyList()
                                        styleConfig.lyricsWordSelectionMode && manualLines.isNotEmpty() -> manualLines
                                        else -> emptyList()
                                    }

                                    if (lyricsCandidates.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(lyricsCandidates.size) { idx ->
                                                val cand = lyricsCandidates[idx]
                                                val active = idx == selectedProviderIndex
                                                Surface(
                                                    onClick = { selectedProviderIndex = idx },
                                                    shape = RoundedCornerShape(50),
                                                    color = if (active) Color(0xFFFF2B44) else GlassCardStrongFill
                                                ) {
                                                    Text(
                                                        text = "${cand.provider} (${cand.lines.size})",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                        activeCand?.let { cand ->
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color.White.copy(alpha = 0.08f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("lyrics_active_match_badge")
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                ) {
                                                    Text(
                                                        text = buildString {
                                                            append(cand.title)
                                                            if (cand.artist.isNotBlank()) {
                                                                append(" — ")
                                                                append(cand.artist)
                                                            }
                                                        },
                                                        color = Color.White.copy(alpha = 0.90f),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    val tagLabel = when {
                                                        cand.provider == "Genius" -> "GENIUS"
                                                        cand.isSynced -> "SYNCED"
                                                        else -> "VERIFIED"
                                                    }
                                                    Text(
                                                        text = tagLabel,
                                                        color = if (cand.provider == "Genius") Color(0xFFFFEE58) else Color(0xFF4ADE80),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (pickerLines.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.Black.copy(alpha = 0.30f))
                                                .padding(8.dp)
                                        ) {
                                            if (!styleConfig.lyricsWordSelectionMode) {
                                                pickerLines.take(40).forEachIndexed { lineIdx, line ->
                                                    val isChecked = selectedLineIndices.contains(lineIdx)
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable {
                                                                selectedLineIndices = if (isChecked) {
                                                                    selectedLineIndices - lineIdx
                                                                } else {
                                                                    selectedLineIndices + lineIdx
                                                                }
                                                            }
                                                            .background(if (isChecked) Color(0x44FF2B44) else Color.Transparent)
                                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (isChecked) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.22f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = line,
                                                            color = if (isChecked) Color.White else Color.White.copy(alpha = 0.78f),
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    }
                                                }
                                            } else {
                                                val tokenizedLines = remember(pickerLines) {
                                                    pickerLines.take(36).map { line ->
                                                        line.split(Regex("\\s+")).filter { it.isNotBlank() }
                                                    }
                                                }
                                                tokenizedLines.forEachIndexed { lineIdx, words ->
                                                    if (words.isNotEmpty()) {
                                                        FlowRow(
                                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            words.forEachIndexed { wordIdx, word ->
                                                                val key = lineIdx to wordIdx
                                                                val isPicked = selectedWordCoords.contains(key)
                                                                Surface(
                                                                    onClick = {
                                                                        selectedWordCoords = if (isPicked) {
                                                                            selectedWordCoords - key
                                                                        } else {
                                                                            selectedWordCoords + key
                                                                        }
                                                                    },
                                                                    shape = RoundedCornerShape(8.dp),
                                                                    color = if (isPicked) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.09f),
                                                                    border = BorderStroke(
                                                                        width = 1.dp,
                                                                        color = if (isPicked) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f)
                                                                    ),
                                                                    modifier = Modifier.testTag("lyric_word_${lineIdx}_${wordIdx}")
                                                                ) {
                                                                    Text(
                                                                        text = word,
                                                                        color = Color.White,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Normal,
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (!styleConfig.lyricsWordSelectionMode) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Tap lines above (${selectedLineIndices.size} selected)",
                                                    color = Color.White.copy(alpha = 0.65f),
                                                    fontSize = 11.sp
                                                )
                                                Button(
                                                    onClick = {
                                                        val ordered = selectedLineIndices.sorted()
                                                            .mapNotNull { pickerLines.getOrNull(it) }
                                                        if (ordered.isNotEmpty()) {
                                                            MediaNotificationRepository.applySelectedLyricLines(ordered)
                                                            Toast.makeText(
                                                                context,
                                                                "Applied ${ordered.size} lyric lines",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    },
                                                    enabled = selectedLineIndices.isNotEmpty(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                    modifier = Modifier.testTag("apply_selected_lyrics_button")
                                                ) {
                                                    Text("Use Lines (${selectedLineIndices.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        } else {
                                            val tokenizedLines = remember(pickerLines) {
                                                pickerLines.take(36).map { line ->
                                                    line.split(Regex("\\s+")).filter { it.isNotBlank() }
                                                }
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Tap words (${selectedWordCoords.size} selected)",
                                                    color = Color.White.copy(alpha = 0.65f),
                                                    fontSize = 11.sp
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (selectedWordCoords.isNotEmpty()) {
                                                        Surface(
                                                            onClick = { selectedWordCoords = emptySet() },
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = GlassCardStrongFill
                                                        ) {
                                                            Text(
                                                                text = "Clear",
                                                                color = Color.White.copy(alpha = 0.85f),
                                                                fontSize = 11.sp,
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                                            )
                                                        }
                                                    }
                                                    Button(
                                                        onClick = {
                                                            val groupedLines = selectedWordCoords
                                                                .sortedWith(compareBy({ it.first }, { it.second }))
                                                                .groupBy { it.first }
                                                                .toSortedMap()
                                                                .values
                                                                .mapNotNull { coords ->
                                                                    val lineTokens = coords.mapNotNull { (li, wi) ->
                                                                        tokenizedLines.getOrNull(li)?.getOrNull(wi)
                                                                    }
                                                                    if (lineTokens.isEmpty()) null else lineTokens.joinToString(" ")
                                                                }
                                                            if (groupedLines.isNotEmpty()) {
                                                                MediaNotificationRepository.applySelectedLyricLines(groupedLines)
                                                                Toast.makeText(
                                                                    context,
                                                                    "Applied ${selectedWordCoords.size} words",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        },
                                                        enabled = selectedWordCoords.isNotEmpty(),
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                        modifier = Modifier.testTag("apply_selected_words_button")
                                                    ) {
                                                        Text("Use Words (${selectedWordCoords.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Manual Lyrics",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.92f)
                                    )
                                    Text(
                                        text = "Edit or paste up to 6 lines",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.58f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        onClick = {
                                            val sampleSnippet =
                                                "Turn the lights down low\n" +
                                                    "Feel the bassline echo through the room\n" +
                                                    "Every chord hits right on time"
                                            onStyleChange(styleConfig.copy(customLyrics = sampleSnippet))
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = GlassCardStrongFill,
                                        modifier = Modifier.testTag("sample_lyrics_button")
                                    ) {
                                        Text(
                                            text = "Sample",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                    if (styleConfig.customLyrics.isNotEmpty()) {
                                        Surface(
                                            onClick = { onStyleChange(styleConfig.copy(customLyrics = "")) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0x553A2429),
                                            modifier = Modifier.testTag("clear_lyrics_button")
                                        ) {
                                            Text(
                                                text = "Clear",
                                                color = Color(0xFFFF8A96),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = styleConfig.customLyrics,
                                onValueChange = { newLyrics ->
                                    onStyleChange(styleConfig.copy(customLyrics = newLyrics))
                                },
                                placeholder = {
                                    Text(
                                        text = "Enter song lyrics line by line...",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                },
                                minLines = 3,
                                maxLines = 6,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF2B44),
                                    unfocusedBorderColor = GlassBorderColor,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_lyrics_input")
                            )
                        }
                    }

                    // Sections 7-10 fade out during any Live Scrubbing
                    Column(modifier = Modifier.graphicsLayer { alpha = chromeAlphaState.value }) {

                        Spacer(modifier = Modifier.height(20.dp))

                    // 7. SHADOW & DEPTH INTENSITY, COLOR & INVERSE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (styleConfig.cardLayoutType) {
                                CardLayoutType.APP_MUSIC_CARD -> "Photo Shadow Depth"
                                CardLayoutType.FLOATING_ARTWORK -> "Square Shadow Depth"
                                else -> "Card & Square Shadow Depth"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        Text(
                            text = if (styleConfig.glowIntensity <= 0.01f) "Off (0%)" else "${(styleConfig.glowIntensity * 100).roundToInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (styleConfig.glowIntensity <= 0.01f) Color.White.copy(alpha = 0.55f) else Color(0xFFFF8A96)
                        )
                    }
                    Slider(
                        value = styleConfig.glowIntensity,
                        onValueChange = { onStyleChange(styleConfig.copy(glowIntensity = it.coerceIn(0f, 1f))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF2B44),
                            activeTrackColor = Color(0xFFFF2B44),
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier.testTag("glow_intensity_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(0f to "Off 0%", 0.35f to "Soft 35%", 0.75f to "Rich 75%", 1.0f to "Deep 100%")
                        presets.forEach { (presetVal, label) ->
                            val isActive = kotlin.math.abs(styleConfig.glowIntensity - presetVal) < 0.04f
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(glowIntensity = presetVal)) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isActive) Color(0xFFFF2B44) else GlassCardFill,
                                border = BorderStroke(1.dp, if (isActive) Color.Transparent else GlassBorderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("shadow_depth_preset_${(presetVal * 100).roundToInt()}")
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Inverse Shadow Toggle (upward outer rim + recessed inner square depth)
                    Surface(
                        onClick = { onStyleChange(styleConfig.copy(cardShadowInverse = !styleConfig.cardShadowInverse)) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (styleConfig.cardShadowInverse) Color(0x28FF2B44) else GlassCardFill,
                        border = BorderStroke(
                            1.dp,
                            if (styleConfig.cardShadowInverse) Color(0x88FF2B44) else GlassBorderColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shadow_inverse_toggle")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Inverse Shadow (Recessed Edge)",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Inverts shadow upward & carves soft depth inside photo, card, and Floating Square edges",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.cardShadowInverse,
                                onCheckedChange = { checked ->
                                    onStyleChange(styleConfig.copy(cardShadowInverse = checked))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF2B44),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.18f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Shadow Color Selector (never locked to black only)
                    val activeShadowColor = styleConfig.resolveShadowColor(palette)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shadow Color",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.90f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(activeShadowColor)
                                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                            )
                            Text(
                                text = styleConfig.cardShadowColorMode.label,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CardShadowColorMode.entries.forEach { mode ->
                            val isSelected = styleConfig.cardShadowColorMode == mode
                            val swatchColor = when (mode) {
                                CardShadowColorMode.ALBUM_DYNAMIC -> palette.vibrantColor
                                CardShadowColorMode.BLACK -> Color.Black
                                CardShadowColorMode.RED -> Color(0xFFFF2B44)
                                CardShadowColorMode.WHITE -> Color.White
                                CardShadowColorMode.PURPLE -> Color(0xFF9D4EDD)
                                CardShadowColorMode.CYAN -> Color(0xFF00D2FF)
                                CardShadowColorMode.CUSTOM -> styleConfig.customShadowColor
                            }
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(cardShadowColorMode = mode)) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFFFF2B44) else GlassCardFill,
                                border = BorderStroke(1.dp, if (isSelected) Color.Transparent else GlassBorderColor),
                                modifier = Modifier.testTag("shadow_color_${mode.name}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(11.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                    )
                                    Text(
                                        text = mode.label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    if (styleConfig.cardShadowColorMode == CardShadowColorMode.CUSTOM) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val customSwatches = listOf(
                                Color(0xFFFF2B44),
                                Color(0xFFFFB703),
                                Color(0xFF1DB954),
                                Color(0xFF00D2FF),
                                Color(0xFF9D4EDD),
                                Color(0xFFFF4D8D),
                                Color(0xFFFF6B4A)
                            )
                            customSwatches.forEachIndexed { idx, swatch ->
                                val selected = styleConfig.customShadowColor.toArgb() == swatch.toArgb()
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            onStyleChange(
                                                styleConfig.copy(
                                                    cardShadowColorMode = CardShadowColorMode.CUSTOM,
                                                    customShadowColor = swatch
                                                )
                                            )
                                        }
                                        .testTag("custom_shadow_swatch_$idx")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 8. SAMPLE TRACK SELECTOR
                    Text(
                        text = "Preset Demo Tracks",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val samples = MediaNotificationRepository.getSampleTracks()
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        samples.forEach { sample ->
                            val isSelected = currentTrack.title == sample.title
                            Surface(
                                onClick = { onTrackSelect(sample) },
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFFFF2B44) else GlassCardFill,
                                border = BorderStroke(1.dp, if (isSelected) Color.Transparent else GlassBorderColor),
                                modifier = Modifier.testTag("sample_track_${sample.title}")
                            ) {
                                Text(
                                    text = "${sample.title} • ${sample.artist}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 9. EDIT TRACK METADATA
                    Text(
                        text = "Edit Track Information",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = {
                            editTitle = it
                            MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                        },
                        label = { Text("Track Title") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF2B44),
                            unfocusedBorderColor = GlassBorderColor,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFFFF2B44),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_track_title_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editArtist,
                        onValueChange = {
                            editArtist = it
                            MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                        },
                        label = { Text("Artist Name / Album") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF2B44),
                            unfocusedBorderColor = GlassBorderColor,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFFFF2B44),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_artist_name_input")
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 10. RESET ADJUSTABLE VALUES
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GlassCardFill,
                        border = BorderStroke(1.dp, GlassBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Resets adjusted values (Card Size, Card Opacity, Card Blur, Background Blur, Shadow & Waveform) while keeping your Spotify login, layout preset & saved files.",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.64f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    onLiveBlurPreview(null)
                                    onLiveCardScalePreview(null)
                                    onLiveCardOpacityPreview(null)
                                    onLiveCardBlurPreview(null)
                                    onLiveLyricsBlurPreview(null)
                                    MediaNotificationRepository.resetStyleValues()
                                    Toast.makeText(
                                        context,
                                        "Reset slider & effect values to defaults",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFFF2B44).copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFFF8A96)
                                ),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reset_edit_values_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset values",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Reset Values",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 11. ECHOSNAP FOLDER BACKUP (EXPORT / IMPORT / RESET)
                    EchoSnapBackupSectionCard(
                        styleConfig = styleConfig,
                        onLivePreviewReset = {
                            onLiveBlurPreview(null)
                            onLiveCardScalePreview(null)
                            onLiveCardOpacityPreview(null)
                            onLiveCardBlurPreview(null)
                            onLiveLyricsBlurPreview(null)
                        }
                    )
                }
            }
        }
    }

    // Long-press dialog for saving online Cover Art in max resolution locally
    coverCandidateToSave?.let { candidate ->
        SaveMediaNameDialog(
            title = "Download Cover (Max Res)",
            subtitle = "${candidate.source} • Saves offline to EchoSnap Saved library",
            initialName = "${currentTrack.title} - ${candidate.source}".trim(),
            confirmLabel = "Download",
            accentColor = Color(0xFFFF2B44),
            onDismiss = { coverCandidateToSave = null },
            onConfirm = { chosenName ->
                coverCandidateToSave = null
                Toast.makeText(context, "Downloading max resolution cover...", Toast.LENGTH_SHORT).show()
                MediaNotificationRepository.saveOnlineCoverLocally(candidate, chosenName) { saved ->
                    val msg = if (saved != null) "Saved cover: ${saved.name} (${saved.resolutionLabel})" else "Download failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Long-press dialog for saving player's current Cover Art locally
    if (saveCurrentCoverPrompt) {
        SaveMediaNameDialog(
            title = "Save Player Cover Art",
            subtitle = "Stores full quality art locally for offline reuse",
            initialName = currentTrack.title,
            confirmLabel = "Save",
            accentColor = Color(0xFFFF2B44),
            onDismiss = { saveCurrentCoverPrompt = false },
            onConfirm = { chosenName ->
                saveCurrentCoverPrompt = false
                MediaNotificationRepository.saveCurrentCoverLocally(chosenName) { saved ->
                    val msg = if (saved != null) "Saved cover: ${saved.name}" else "Failed to save cover"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Long-press dialog for downloading Animated Canvas MP4 in max resolution locally
    canvasCandidateToSave?.let { candidate ->
        SaveMediaNameDialog(
            title = "Download Canvas Video",
            subtitle = "${candidate.source} • Saves MP4 locally in max resolution",
            initialName = "${candidate.title} - ${candidate.source}".trim(),
            confirmLabel = "Download",
            accentColor = Color(0xFF1DB954),
            onDismiss = { canvasCandidateToSave = null },
            onConfirm = { chosenName ->
                canvasCandidateToSave = null
                Toast.makeText(context, "Downloading animated Canvas MP4...", Toast.LENGTH_SHORT).show()
                MediaNotificationRepository.saveOnlineCanvasLocally(candidate, chosenName) { saved ->
                    val msg = if (saved != null) "Saved canvas: ${saved.name} (${saved.resolutionLabel})" else "Download failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSavedCoversDialog) {
        SavedCoversBrowserDialog(
            items = savedCovers,
            selectedCoverId = selectedCoverId,
            onDismiss = { showSavedCoversDialog = false },
            onAddFromDevice = {
                coverMediaPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onSelect = { item ->
                MediaNotificationRepository.selectSavedCover(item)
                showSavedCoversDialog = false
            },
            onUpdateCover = { item, newName, albumName, albumArtist, tracks ->
                SavedMediaLibrary.updateCoverMetadata(
                    context = context,
                    id = item.id,
                    newName = newName,
                    linkedAlbumName = albumName,
                    linkedAlbumArtist = albumArtist,
                    linkedTrackTitles = tracks
                )
                MediaNotificationRepository.recheckSavedAlbumCoverForCurrentTrack()
            },
            onDelete = { item ->
                SavedMediaLibrary.deleteCover(context, item.id)
            }
        )
    }

    if (showSavedCanvasesDialog) {
        SavedCanvasesBrowserDialog(
            items = savedCanvases,
            activeCanvasId = activeCanvasId,
            onDismiss = { showSavedCanvasesDialog = false },
            onAddFromDevice = {
                canvasVideoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            onSelect = { item ->
                MediaNotificationRepository.selectSavedCanvas(item)
                showSavedCanvasesDialog = false
            },
            onUpdateCanvas = { item, newName, albumName, albumArtist, tracks ->
                SavedMediaLibrary.updateCanvasMetadata(
                    context = context,
                    id = item.id,
                    newName = newName,
                    linkedAlbumName = albumName,
                    linkedAlbumArtist = albumArtist,
                    linkedTrackTitles = tracks
                )
                MediaNotificationRepository.recheckSavedAlbumCanvasForCurrentTrack()
            },
            onDelete = { item ->
                SavedMediaLibrary.deleteCanvas(context, item.id)
            }
        )
    }
}

@Composable
private fun ActionLibraryTile(
    title: String,
    subtitle: String,
    accentColor: Color,
    isAddIcon: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .width(92.dp)
            .height(118.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (isHighlighted) accentColor.copy(alpha = 0.22f) else GlassCardFill)
            .border(
                width = if (isHighlighted) 2.dp else 1.dp,
                color = if (isHighlighted) accentColor else GlassBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        Icon(
            imageVector = if (isAddIcon) Icons.Default.Add else Icons.Default.PlayArrow,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverArtChoiceTile(
    bitmap: Bitmap,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) Color(0x5544222D) else GlassCardFill)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF2B44) else GlassBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrandModeChip(
    brandMode: CardBrandMode,
    previewBadgeColor: Color,
    previewLogoColorMode: LogoColorMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) GlassCardStrongFill else GlassCardFill,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) previewBadgeColor else GlassBorderColor
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BitChordBadge(
                brandMode = brandMode,
                badgeColor = previewBadgeColor,
                logoColorMode = previewLogoColorMode,
                size = 22.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = brandMode.label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LogoColorOption(
    label: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag("logo_color_option_$label")
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color.White else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (color == Color.White) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimatedCanvasChoiceTile(
    candidate: AnimatedCanvasCandidate,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(candidate.thumbnailBitmap) {
        candidate.thumbnailBitmap?.asImageBitmap()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) Color(0x551F3B27) else GlassCardFill)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF1DB954) else GlassBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF121216)),
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (candidate.isLoopingVideo) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Animated video",
                        tint = Color(0xFF1DB954),
                        modifier = Modifier
                            .size(16.dp)
                            .padding(2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = candidate.source,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = candidate.resolutionLabel,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
