package com.example.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.util.AudioOutputRouteItem
import com.example.util.MediaPlaybackControlHelper
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val FIXED_APP_MUSIC_GLASS_BLUR_PX = 34f

/**
 * Interactive Frosted-Glass Apple Music Control Center style player card.
 * - Fixed card glass blur (cannot be altered by cardBlurRadius; background blur stays adjustable).
 * - Square album photo supports adjustable Photo Shadow Depth, Inverse Shadow, and Shadow Color.
 * - Functional Star favorite, Previous, Play/Pause, Next, draggable Timeline, draggable Volume, and AirPlay route sheet.
 */
@Composable
fun AppMusicPlayerCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: ImageBitmap?,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    glassOpacity: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playbackProgress by MediaNotificationRepository.playbackProgress.collectAsState()
    val favoriteTrackKeys by MediaNotificationRepository.favoriteTrackKeys.collectAsState()
    val isFavorited = remember(track.title, track.artist, favoriteTrackKeys) {
        MediaNotificationRepository.isTrackFavorited(track, favoriteTrackKeys)
    }

    var systemVolumeFraction by remember {
        mutableFloatStateOf(MediaPlaybackControlHelper.readMusicVolumeFraction(context))
    }
    var isDraggingVolume by remember { mutableStateOf(false) }
    var dragVolumeFraction by remember { mutableFloatStateOf(systemVolumeFraction) }

    DisposableEffect(context) {
        systemVolumeFraction = MediaPlaybackControlHelper.readMusicVolumeFraction(context)
        val unregister = MediaPlaybackControlHelper.registerMusicVolumeObserver(context) { updated ->
            if (!isDraggingVolume) {
                systemVolumeFraction = updated
            }
        }
        onDispose { unregister() }
    }

    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekFraction by remember { mutableFloatStateOf(0f) }
    var tickElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(playbackProgress.isPlaying, playbackProgress.lastUpdateElapsedMs, playbackProgress.positionMs) {
        tickElapsedMs = SystemClock.elapsedRealtime()
        while (playbackProgress.isPlaying && playbackProgress.durationMs > 0L) {
            delay(350L)
            tickElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    val effectiveDurationMs = playbackProgress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(138_000L)
    val livePositionMs = playbackProgress.currentPositionAt(tickElapsedMs)
    val displayPositionMs = if (isDraggingSeek) {
        (dragSeekFraction.coerceIn(0f, 1f) * effectiveDurationMs.toFloat()).toLong()
    } else {
        livePositionMs.coerceIn(0L, effectiveDurationMs)
    }
    val timelineFraction = if (effectiveDurationMs > 0L) {
        (displayPositionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownVolumeFraction = if (isDraggingVolume) dragVolumeFraction else systemVolumeFraction

    var showAirPlayDialog by remember { mutableStateOf(false) }
    var outputRoutes by remember { mutableStateOf<List<AudioOutputRouteItem>>(emptyList()) }

    // Smooth dark frosted palette tint (card glass blur is always fixed, independent of cardBlurRadius)
    val animDarkMuted by animateColorAsState(
        targetValue = palette.darkMutedColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "app_music_dark_muted"
    )
    val animDominant by animateColorAsState(
        targetValue = palette.dominantColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "app_music_dominant"
    )
    val cardTopTint = remember(animDominant, glassOpacity) {
        lerp(animDominant, Color(0xFF28262B), 0.62f).copy(alpha = (0.70f * glassOpacity).coerceIn(0f, 1f))
    }
    val cardBottomTint = remember(animDarkMuted, glassOpacity) {
        lerp(animDarkMuted, Color(0xFF141317), 0.66f).copy(alpha = (0.78f * glassOpacity).coerceIn(0f, 1f))
    }

    val cardCornerDp = (28 * scale).roundToInt().coerceAtLeast(14).dp
    val cardShape = remember(cardCornerDp) { RoundedCornerShape(cardCornerDp) }
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (28 * scale).dp)
    val horizPad = (16 * scale).dp
    val topPad = (18 * scale).dp
    val bottomPad = (14 * scale).dp

    // Album photo shadow (controlled by glowIntensity, cardShadowInverse, resolveShadowColor)
    val photoShadowStrength = styleConfig.glowIntensity.coerceIn(0f, 1f)
    val isInversePhotoShadow = styleConfig.cardShadowInverse
    val resolvedShadowColor = remember(styleConfig.cardShadowColorMode, styleConfig.customShadowColor, palette) {
        styleConfig.resolveShadowColor(palette)
    }
    val outerPhotoShadowTint = if (isInversePhotoShadow && resolvedShadowColor == Color.Black) {
        lerp(Color.White, palette.lightVibrantColor, 0.35f)
    } else {
        resolvedShadowColor
    }
    val artCornerDp = (12 * scale).roundToInt().coerceAtLeast(4).dp
    val artShape = remember(artCornerDp) { RoundedCornerShape(artCornerDp) }
    val photoGlowPath = remember { AndroidPath() }
    val photoGlowPaint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val photoGlowBlurState = remember {
        object {
            var lastKey = -1
            var lastFilter: BlurMaskFilter? = null
        }
    }

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.80f * scale).coerceIn(0.46f, 0.94f))
            .shadow(
                elevation = (18 * glassOpacity * scale).dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.55f * glassOpacity),
                spotColor = Color.Black.copy(alpha = 0.70f * glassOpacity)
            )
            .clip(cardShape)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(cardTopTint, cardBottomTint)
                    )
                )
            }
            .then(
                if (glassOpacity > 0.02f) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.24f * glassOpacity),
                        shape = cardShape
                    )
                } else {
                    Modifier
                }
            )
            .testTag("music_story_card_container")
    ) {
        // Fixed frosted backdrop blur inside the card (NOT controlled by cardBlurRadius)
        if (glassOpacity > 0.01f && (crispArtImageBitmap != null || track.albumArt != null)) {
            BlurredCardCoverLayer(
                albumArt = track.albumArt,
                predecodedImageBitmap = crispArtImageBitmap,
                blurRadius = FIXED_APP_MUSIC_GLASS_BLUR_PX,
                scrimStrength = 0.88f,
                layerAlpha = 0.56f * glassOpacity,
                modifier = Modifier.matchParentSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizPad, end = horizPad, top = topPad, bottom = bottomPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. LARGE SQUARE ALBUM PHOTO WITH CONFIGURABLE PHOTO SHADOW
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .drawBehind {
                        if (photoShadowStrength > 0.01f) {
                            val cornerPx = artCornerDp.toPx()
                            val blurPx = ((9f + 22f * photoShadowStrength) * scale * density).coerceAtLeast(1f)
                            val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                            if (blurKey != photoGlowBlurState.lastKey || photoGlowBlurState.lastFilter == null) {
                                photoGlowBlurState.lastKey = blurKey
                                photoGlowBlurState.lastFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                            }
                            val spreadPx = (if (isInversePhotoShadow) 2.5f else 5f) * photoShadowStrength * scale * density
                            val offsetY = (if (isInversePhotoShadow) -7f else 7f) * photoShadowStrength * scale * density
                            photoGlowPaint.color = outerPhotoShadowTint.copy(
                                alpha = (if (isInversePhotoShadow) 0.52f else 0.78f) * photoShadowStrength
                            ).toArgb()
                            photoGlowPaint.maskFilter = photoGlowBlurState.lastFilter
                            photoGlowPath.reset()
                            photoGlowPath.addRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, AndroidPath.Direction.CW)
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val saved = native.save()
                                    native.clipOutPath(photoGlowPath)
                                    native.drawRoundRect(
                                        -spreadPx,
                                        -spreadPx + offsetY,
                                        size.width + spreadPx,
                                        size.height + spreadPx + offsetY,
                                        cornerPx + spreadPx * 0.35f,
                                        cornerPx + spreadPx * 0.35f,
                                        photoGlowPaint
                                    )
                                    native.restoreToCount(saved)
                                } else {
                                    native.drawRoundRect(
                                        -spreadPx,
                                        -spreadPx + offsetY,
                                        size.width + spreadPx,
                                        size.height + spreadPx + offsetY,
                                        cornerPx + spreadPx * 0.35f,
                                        cornerPx + spreadPx * 0.35f,
                                        photoGlowPaint
                                    )
                                }
                            }
                        }
                    }
                    .then(
                        if (photoShadowStrength > 0.02f) {
                            Modifier.shadow(
                                elevation = (16 * photoShadowStrength * scale).dp,
                                shape = artShape,
                                ambientColor = outerPhotoShadowTint.copy(alpha = 0.65f * photoShadowStrength),
                                spotColor = outerPhotoShadowTint.copy(alpha = 0.85f * photoShadowStrength)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(artShape)
                    .background(Color(0xFF141418))
                    .testTag("app_music_card_artwork_box"),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = crispArtImageBitmap,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "app_music_art_crossfade",
                    modifier = Modifier.fillMaxSize()
                ) { targetImg ->
                    if (targetImg != null) {
                        Image(
                            bitmap = targetImg,
                            contentDescription = "Album art for ${track.title}",
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.vibrantColor.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_music_note),
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size((48 * scale).dp)
                            )
                        }
                    }
                }

                if (isInversePhotoShadow && photoShadowStrength > 0.01f) {
                    InnerInverseShadowOverlay(
                        shadowColor = resolvedShadowColor,
                        strength = photoShadowStrength,
                        cornerDp = artCornerDp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height((13 * scale).dp))

            // 2. SONG TITLE + ARTIST + RIGHT 6-BAR AUDIO ICON
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontSize = (15.5 * scale).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.25).sp,
                        modifier = Modifier.testTag("track_title_text")
                    )
                    Spacer(modifier = Modifier.height((1.5 * scale).dp))
                    Text(
                        text = track.artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = (13.5 * scale).sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("track_artist_text")
                    )
                }
                Spacer(modifier = Modifier.width((10 * scale).dp))
                AppMusicMiniWaveform(
                    isPlaying = track.isPlaying,
                    scale = scale,
                    modifier = Modifier.testTag("app_music_card_waveform")
                )
            }

            Spacer(modifier = Modifier.height((4 * scale).dp))

            // 3. INTERACTIVE TIMELINE SCRUBBER + 0:29 / -1:49 LABELS
            AppMusicTimelineBar(
                fraction = timelineFraction,
                isDragging = isDraggingSeek,
                elapsedText = MediaPlaybackControlHelper.formatDurationMs(displayPositionMs),
                remainingText = MediaPlaybackControlHelper.formatRemainingMs(displayPositionMs, effectiveDurationMs),
                scale = scale,
                onDragStart = { startFrac ->
                    isDraggingSeek = true
                    dragSeekFraction = startFrac
                },
                onDragFraction = { frac ->
                    dragSeekFraction = frac
                },
                onDragEnd = { commitFrac ->
                    isDraggingSeek = false
                    val seekMs = (commitFrac.coerceIn(0f, 1f) * effectiveDurationMs.toFloat()).toLong()
                    MediaNotificationRepository.seekCurrentTrackTo(context, seekMs)
                    tickElapsedMs = SystemClock.elapsedRealtime()
                }
            )

            // 4. FUNCTIONAL TRANSPORT + FAVORITE ROW (STAR, PREV, PLAY/PAUSE, NEXT)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .size((48 * scale).coerceAtLeast(48f).dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, color = Color.White),
                            role = Role.Button,
                            onClickLabel = if (isFavorited) "Remove from favorites" else "Add to favorites"
                        ) {
                            MediaNotificationRepository.toggleFavoriteCurrentTrack(context)
                        }
                        .semantics { contentDescription = "Favorite track" }
                        .testTag("app_music_card_star_button"),
                    contentAlignment = Alignment.Center
                ) {
                    AppleMusicStarGlyph(
                        filled = isFavorited,
                        modifier = Modifier.size((21 * scale).dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .size((48 * scale).coerceAtLeast(48f).dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, color = Color.White),
                            role = Role.Button
                        ) {
                            MediaNotificationRepository.skipPreviousCurrentTrack(context)
                        }
                        .semantics { contentDescription = "Previous track" }
                        .testTag("app_music_card_prev_button"),
                    contentAlignment = Alignment.Center
                ) {
                    DoubleTriangleSkipGlyph(
                        pointLeft = true,
                        modifier = Modifier.size(width = (26 * scale).dp, height = (19 * scale).dp)
                    )
                }

                Spacer(modifier = Modifier.width((4 * scale).dp))

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .size((48 * scale).coerceAtLeast(48f).dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, color = Color.White),
                            role = Role.Button
                        ) {
                            MediaNotificationRepository.togglePlayPauseCurrentTrack(context)
                        }
                        .semantics { contentDescription = if (track.isPlaying) "Pause" else "Play" }
                        .testTag("app_music_card_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    PlayPauseAppleGlyph(
                        isPlaying = track.isPlaying,
                        modifier = Modifier.size((26 * scale).dp)
                    )
                }

                Spacer(modifier = Modifier.width((4 * scale).dp))

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .size((48 * scale).coerceAtLeast(48f).dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, color = Color.White),
                            role = Role.Button
                        ) {
                            MediaNotificationRepository.skipNextCurrentTrack(context)
                        }
                        .semantics { contentDescription = "Next track" }
                        .testTag("app_music_card_next_button"),
                    contentAlignment = Alignment.Center
                ) {
                    DoubleTriangleSkipGlyph(
                        pointLeft = false,
                        modifier = Modifier.size(width = (26 * scale).dp, height = (19 * scale).dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Balancing right spacer so Play/Pause sits dead center directly above AirPlay
                Spacer(modifier = Modifier.size((48 * scale).coerceAtLeast(48f).dp))
            }

            // 5. FUNCTIONAL DRAGGABLE VOLUME ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeakerGlyph(
                    highVolume = false,
                    tint = Color.White.copy(alpha = 0.52f),
                    modifier = Modifier.size((15 * scale).dp)
                )
                Spacer(modifier = Modifier.width((9 * scale).dp))
                AppMusicPillDragTrack(
                    fraction = shownVolumeFraction,
                    isDragging = isDraggingVolume,
                    contentDescription = "Music Volume",
                    testTagName = "app_music_card_volume_slider",
                    scale = scale,
                    onDragStart = { frac ->
                        isDraggingVolume = true
                        dragVolumeFraction = frac
                        MediaPlaybackControlHelper.setMusicVolumeFraction(context, frac)
                    },
                    onDragFraction = { frac ->
                        dragVolumeFraction = frac
                        MediaPlaybackControlHelper.setMusicVolumeFraction(context, frac)
                    },
                    onDragEnd = { frac ->
                        val actual = MediaPlaybackControlHelper.setMusicVolumeFraction(context, frac)
                        systemVolumeFraction = actual
                        dragVolumeFraction = actual
                        isDraggingVolume = false
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width((9 * scale).dp))
                SpeakerGlyph(
                    highVolume = true,
                    tint = Color.White.copy(alpha = 0.52f),
                    modifier = Modifier.size((18 * scale).dp)
                )
            }

            // 6. FUNCTIONAL AIRPLAY OUTPUT PILL (48dp touch box wrapping crisp pill visuals)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = (104 * scale).dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = Color.White),
                        role = Role.Button
                    ) {
                        val launchedSystem = MediaPlaybackControlHelper.tryLaunchSystemMediaOutputDialog(
                            context = context,
                            packageName = track.packageName
                        )
                        if (!launchedSystem) {
                            outputRoutes = MediaPlaybackControlHelper.getAvailableAudioOutputs(context)
                            showAirPlayDialog = true
                        }
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "AirPlay audio output"
                    }
                    .testTag("app_music_card_airplay_button"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.defaultMinSize(minWidth = (92 * scale).dp, minHeight = (32 * scale).dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = (16 * scale).dp, vertical = (7 * scale).dp)
                    ) {
                        AirPlayGlyph(
                            tint = Color.White,
                            modifier = Modifier.size((14 * scale).dp)
                        )
                        Spacer(modifier = Modifier.width((6 * scale).dp))
                        Text(
                            text = "AirPlay",
                            color = Color.White,
                            fontSize = (12.5 * scale).sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    if (showAirPlayDialog) {
        AudioOutputPickerDialog(
            routes = outputRoutes,
            currentVolumeFraction = systemVolumeFraction,
            onVolumeChange = { newFrac ->
                val actual = MediaPlaybackControlHelper.setMusicVolumeFraction(context, newFrac)
                systemVolumeFraction = actual
                dragVolumeFraction = actual
            },
            onOpenBluetoothSettings = {
                showAirPlayDialog = false
                if (!MediaPlaybackControlHelper.openBluetoothSettings(context)) {
                    Toast.makeText(context, "Bluetooth settings unavailable", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenVolumePanel = {
                showAirPlayDialog = false
                if (!MediaPlaybackControlHelper.openSystemSoundOrVolumePanel(context)) {
                    Toast.makeText(context, "Audio panel unavailable", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showAirPlayDialog = false }
        )
    }
}

@Composable
private fun AppMusicTimelineBar(
    fraction: Float,
    isDragging: Boolean,
    elapsedText: String,
    remainingText: String,
    scale: Float,
    onDragStart: (Float) -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppMusicPillDragTrack(
            fraction = fraction,
            isDragging = isDragging,
            contentDescription = "Playback timeline",
            testTagName = "app_music_card_timeline_slider",
            scale = scale,
            onDragStart = onDragStart,
            onDragFraction = onDragFraction,
            onDragEnd = onDragEnd,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = elapsedText,
                color = Color.White.copy(alpha = 0.56f),
                fontSize = (11 * scale).sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.testTag("app_music_card_elapsed_text")
            )
            Text(
                text = remainingText,
                color = Color.White.copy(alpha = 0.56f),
                fontSize = (11 * scale).sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.testTag("app_music_card_remaining_text")
            )
        }
    }
}

@Composable
private fun AppMusicPillDragTrack(
    fraction: Float,
    isDragging: Boolean,
    contentDescription: String,
    testTagName: String,
    scale: Float,
    onDragStart: (Float) -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) (9 * scale).dp else (6.5 * scale).dp,
        animationSpec = tween(durationMillis = 120),
        label = "pill_track_height"
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(clamped, 0f..1f)
                setProgress { target ->
                    val next = target.coerceIn(0f, 1f)
                    onDragEnd(next)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    var currentFrac = (down.position.x / widthPx).coerceIn(0f, 1f)
                    onDragStart(currentFrac)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            change?.consume()
                            onDragEnd(currentFrac)
                            break
                        }
                        change.consume()
                        currentFrac = (change.position.x / widthPx).coerceIn(0f, 1f)
                        onDragFraction(currentFrac)
                    }
                }
            }
            .testTag(testTagName),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.24f),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = radius
            )
            val fillWidth = (size.width * clamped).coerceAtLeast(if (clamped > 0.005f) size.height else 0f)
            if (fillWidth > 0.5f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.96f),
                    topLeft = Offset.Zero,
                    size = Size(fillWidth.coerceAtMost(size.width), size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}

@Composable
private fun AppMusicMiniWaveform(
    isPlaying: Boolean,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "app_music_wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "app_music_wave_phase"
    )
    val baseProfile = remember { floatArrayOf(0.32f, 0.72f, 0.44f, 0.96f, 0.56f, 0.30f) }

    Canvas(
        modifier = modifier.size(width = (22 * scale).dp, height = (15 * scale).dp)
    ) {
        val count = baseProfile.size
        val barW = (size.width * 0.088f).coerceAtLeast(1.8f)
        val gap = (size.width - barW * count) / (count - 1).coerceAtLeast(1)
        val corner = CornerRadius(barW / 2f, barW / 2f)
        for (i in 0 until count) {
            val animMod = if (isPlaying) {
                0.78f + 0.28f * sin(phase + i * 1.15f)
            } else {
                1f
            }
            val frac = (baseProfile[i] * animMod).coerceIn(0.22f, 1f)
            val h = (size.height * frac).coerceAtLeast(barW * 1.2f)
            val x = i * (barW + gap)
            val top = (size.height - h) / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.52f),
                topLeft = Offset(x, top),
                size = Size(barW, h),
                cornerRadius = corner
            )
        }
    }
}

@Composable
private fun AppleMusicStarGlyph(
    filled: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = minOf(size.width, size.height) * 0.46f
        val innerR = outerR * 0.44f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = (-PI / 2.0) + i * (PI / 5.0)
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) {
            drawPath(
                path = path,
                color = Color(0xFFFFD60A),
                style = Fill
            )
        }
        drawPath(
            path = path,
            color = if (filled) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.72f),
            style = Stroke(
                width = minOf(size.width, size.height) * 0.085f,
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.cornerPathEffect(minOf(size.width, size.height) * 0.06f)
            )
        )
    }
}

@Composable
private fun DoubleTriangleSkipGlyph(
    pointLeft: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val triW = w * 0.47f
        val overlap = w * 0.05f
        val cornerR = minOf(w, h) * 0.14f

        fun buildTriangle(startX: Float, tipX: Float): Path {
            return Path().apply {
                moveTo(tipX, h / 2f)
                lineTo(startX, h * 0.08f)
                lineTo(startX, h * 0.92f)
                close()
            }
        }

        val paths = if (pointLeft) {
            listOf(
                buildTriangle(startX = triW, tipX = 0f),
                buildTriangle(startX = w, tipX = triW - overlap)
            )
        } else {
            listOf(
                buildTriangle(startX = 0f, tipX = triW),
                buildTriangle(startX = triW - overlap, tipX = w)
            )
        }

        val strokeStyle = Stroke(
            width = minOf(w, h) * 0.12f,
            join = StrokeJoin.Round,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.cornerPathEffect(cornerR)
        )
        paths.forEach { p ->
            drawPath(path = p, color = Color.White, style = Fill)
            drawPath(path = p, color = Color.White, style = strokeStyle)
        }
    }
}

@Composable
private fun PlayPauseAppleGlyph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (isPlaying) {
            val barW = w * 0.25f
            val barH = h * 0.80f
            val top = (h - barH) / 2f
            val gap = w * 0.18f
            val left1 = (w - (barW * 2f + gap)) / 2f
            val left2 = left1 + barW + gap
            val r = CornerRadius(barW * 0.34f, barW * 0.34f)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left1, top),
                size = Size(barW, barH),
                cornerRadius = r
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left2, top),
                size = Size(barW, barH),
                cornerRadius = r
            )
        } else {
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.12f)
                lineTo(w * 0.86f, h * 0.50f)
                lineTo(w * 0.22f, h * 0.88f)
                close()
            }
            drawPath(path = path, color = Color.White, style = Fill)
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(
                    width = w * 0.09f,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.cornerPathEffect(w * 0.10f)
                )
            )
        }
    }
}

@Composable
private fun SpeakerGlyph(
    highVolume: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val coneRight = if (highVolume) w * 0.44f else w * 0.68f
        val coneLeft = w * 0.10f
        val bodyPath = Path().apply {
            moveTo(coneLeft, h * 0.36f)
            lineTo(coneLeft + (coneRight - coneLeft) * 0.40f, h * 0.36f)
            lineTo(coneRight, h * 0.16f)
            lineTo(coneRight, h * 0.84f)
            lineTo(coneLeft + (coneRight - coneLeft) * 0.40f, h * 0.64f)
            lineTo(coneLeft, h * 0.64f)
            close()
        }
        drawPath(path = bodyPath, color = tint, style = Fill)
        drawPath(
            path = bodyPath,
            color = tint,
            style = Stroke(
                width = minOf(w, h) * 0.06f,
                join = StrokeJoin.Round
            )
        )

        if (highVolume) {
            val cx = coneRight + w * 0.04f
            val cy = h * 0.50f
            val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
            listOf(w * 0.16f, w * 0.30f, w * 0.44f).forEach { r ->
                drawArc(
                    color = tint,
                    startAngle = -42f,
                    sweepAngle = 84f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke
                )
            }
        }
    }
}

@Composable
private fun AirPlayGlyph(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.52f
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        listOf(w * 0.42f, w * 0.27f, w * 0.13f).forEach { r ->
            drawArc(
                color = tint,
                startAngle = 148f,
                sweepAngle = 244f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = stroke
            )
        }
        val triPath = Path().apply {
            moveTo(cx, h * 0.64f)
            lineTo(cx + w * 0.20f, h * 0.92f)
            lineTo(cx - w * 0.20f, h * 0.92f)
            close()
        }
        drawPath(path = triPath, color = tint, style = Fill)
    }
}

@Composable
private fun AudioOutputPickerDialog(
    routes: List<AudioOutputRouteItem>,
    currentVolumeFraction: Float,
    onVolumeChange: (Float) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenVolumePanel: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = shape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(listOf(Color(0xF5222430), Color(0xFA14151D))),
                    shape = shape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                .testTag("airplay_output_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AirPlayGlyph(tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Audio Output Routes",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Active outputs & Bluetooth audio switcher",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (routes.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Phone Speaker active • Connect Bluetooth headphones or tap System Output below.",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        routes.take(5).forEach { route ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (route.isWireless) Color(0x281DB954) else Color.White.copy(alpha = 0.07f),
                                border = BorderStroke(
                                    1.dp,
                                    if (route.isWireless) Color(0x661DB954) else Color.White.copy(alpha = 0.10f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = route.label,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = route.detail,
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (route.isWireless) Color(0x441DB954) else Color.White.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = if (route.isWireless) "Connected" else "Ready",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Media Volume (${(currentVolumeFraction * 100f).roundToInt()}%)",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                AppMusicPillDragTrack(
                    fraction = currentVolumeFraction,
                    isDragging = false,
                    contentDescription = "Output volume",
                    testTagName = "airplay_dialog_volume_slider",
                    scale = 1.0f,
                    onDragStart = onVolumeChange,
                    onDragFraction = onVolumeChange,
                    onDragEnd = onVolumeChange,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 11.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("airplay_bluetooth_settings_button")
                    ) {
                        Text("Bluetooth", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onOpenVolumePanel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 11.dp),
                        modifier = Modifier
                            .weight(1.25f)
                            .heightIn(min = 48.dp)
                            .testTag("airplay_system_output_button")
                    ) {
                        Text("System Switcher", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
