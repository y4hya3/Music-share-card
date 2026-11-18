package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import com.example.util.AudioBeatMonitor
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Full-screen blurred song cover / animated canvas / transparent preview background.
 * - Transparent mode: displays a clean dark editor checkerboard on-screen; exports as pure ARGB_8888 PNG.
 * - Android 12+: uses real-time RenderEffect Modifier.blur for zero-lag 60fps slider dragging.
 * - Android 7-11: precomputes a 3-stop mipmap pyramid once per artwork and crossfades with zero CPU
 *   work while dragging.
 * - Animated Canvas: passes live blurRadiusPx to EGL14 + GLES20 Gaussian blur shader.
 */
@Composable
fun BlurredAlbumBackground(
    track: MediaTrackInfo,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    modifier: Modifier = Modifier,
    activeCanvasVideoUrl: String? = null,
    isCanvasPaused: Boolean = false
) {
    val blurRadius = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f)
    val albumArt = track.albumArt

    val sharpOrGpuBgBitmap = remember(albumArt) {
        albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = 720) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (styleConfig.useTransparentBackground) {
            TransparentEditorCheckerboard(modifier = Modifier.fillMaxSize())
            return@Box
        }

        if (styleConfig.useBlurredAlbumBackground && sharpOrGpuBgBitmap != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bgImageBitmap = remember(sharpOrGpuBgBitmap) {
                    sharpOrGpuBgBitmap.asImageBitmap()
                }
                Image(
                    bitmap = bgImageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (blurRadius > 0.35f) {
                                Modifier.blur(
                                    radius = (blurRadius * 0.85f).dp,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle
                                )
                            } else {
                                Modifier
                            }
                        )
                )
            } else {
                // Zero-lag 60fps mip-pyramid crossfade for API 24-30 (no delay, no UI jank during drag)
                val mipPyramid by produceState<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(
                    initialValue = null,
                    key1 = sharpOrGpuBgBitmap
                ) {
                    value = withContext(Dispatchers.Default) {
                        BitmapBlurUtils.computeBlurMipPyramid(sharpOrGpuBgBitmap)?.let { (low, mid, high) ->
                            Triple(low.asImageBitmap(), mid.asImageBitmap(), high.asImageBitmap())
                        }
                    }
                }

                val baseImg = remember(sharpOrGpuBgBitmap) { sharpOrGpuBgBitmap.asImageBitmap() }
                Image(
                    bitmap = baseImg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )

                val pyramid = mipPyramid
                if (pyramid != null && blurRadius > 0.2f) {
                    val lowAlpha = (blurRadius / 12f).coerceIn(0f, 1f)
                    val midAlpha = ((blurRadius - 12f) / 12f).coerceIn(0f, 1f)
                    val highAlpha = ((blurRadius - 24f) / 16f).coerceIn(0f, 1f)

                    if (lowAlpha > 0.01f && midAlpha < 0.99f) {
                        Image(
                            bitmap = pyramid.first,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = lowAlpha }
                        )
                    }
                    if (midAlpha > 0.01f && highAlpha < 0.99f) {
                        Image(
                            bitmap = pyramid.second,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = midAlpha }
                        )
                    }
                    if (highAlpha > 0.01f) {
                        Image(
                            bitmap = pyramid.third,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = highAlpha }
                        )
                    }
                }
            }
        } else {
            val gradientStops = remember(palette, styleConfig.gradientStyle) {
                resolveGradientStops(palette, styleConfig)
            }
            val bgBrush = remember(gradientStops) {
                Brush.linearGradient(
                    colors = gradientStops,
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgBrush)
            )
        }

        if (!activeCanvasVideoUrl.isNullOrBlank()) {
            AnimatedCanvasVideoBackground(
                videoUrl = activeCanvasVideoUrl,
                blurRadiusPx = blurRadius,
                isPlaybackPaused = isCanvasPaused,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Subtle dark overlay rgba(0, 0, 0, 0.25) for contrast on bright/white album covers
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = styleConfig.darkOverlayAlpha))
        )
    }
}

@Composable
fun TransparentEditorCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.testTag("transparent_checkerboard_bg")) {
        val tile = 14.dp.toPx().coerceAtLeast(10f)
        val cols = ceil(size.width / tile).toInt()
        val rows = ceil(size.height / tile).toInt()
        val darkA = Color(0xFF16161B)
        val darkB = Color(0xFF202028)
        drawRect(darkA)
        for (r in 0..rows) {
            for (c in 0..cols) {
                if ((r + c) % 2 == 1) {
                    drawRect(
                        color = darkB,
                        topLeft = Offset(c * tile, r * tile),
                        size = Size(tile, tile)
                    )
                }
            }
        }
    }
}

/**
 * Center Share Card Composable.
 * Supports adaptive sizing ([maxCardWidth], [maxArtSize]), live [cardOpacity],
 * and Dynamic Island 4-bar waveform.
 */
@Composable
fun MusicStoryCard(
    track: MediaTrackInfo,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    modifier: Modifier = Modifier,
    maxCardWidth: Dp = 330.dp,
    maxArtSize: Dp = 300.dp
) {
    val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
    val glassOpacity = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f)
    val scaledMaxCardWidth = (maxCardWidth * scale).coerceAtLeast(155.dp)
    val scaledMaxArtSize = (maxArtSize * scale).coerceAtLeast(125.dp)
    val scaledCornerDp = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(6)

    val badgeColor = remember(styleConfig.logoColorMode, styleConfig.customLogoColor, palette) {
        styleConfig.resolveLogoBadgeColor(palette)
    }
    val cardShape = remember(scaledCornerDp) {
        RoundedCornerShape(scaledCornerDp.dp)
    }
    val crispArtImageBitmap = remember(track.albumArt) {
        track.albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = 1024)?.asImageBitmap() }
    }

    when (styleConfig.cardLayoutType) {
        CardLayoutType.SPOTIFY_INNER_CARD -> {
            FloatingGlassShareCard(
                track = track,
                crispArtImageBitmap = crispArtImageBitmap,
                palette = palette,
                badgeColor = badgeColor,
                styleConfig = styleConfig,
                cardShape = cardShape,
                maxCardWidth = scaledMaxCardWidth,
                maxArtSize = scaledMaxArtSize,
                scale = scale,
                glassOpacity = glassOpacity,
                modifier = modifier
            )
        }
        CardLayoutType.FLOATING_ARTWORK -> {
            FloatingGlowArtwork(
                track = track,
                crispArtImageBitmap = crispArtImageBitmap,
                palette = palette,
                badgeColor = badgeColor,
                styleConfig = styleConfig,
                maxCardWidth = scaledMaxCardWidth,
                maxArtSize = scaledMaxArtSize,
                scale = scale,
                glassOpacity = glassOpacity,
                modifier = modifier
            )
        }
        CardLayoutType.MINIMAL_CLEAN -> {
            MinimalCleanStoryCard(
                track = track,
                crispArtImageBitmap = crispArtImageBitmap,
                palette = palette,
                badgeColor = badgeColor,
                styleConfig = styleConfig,
                cardShape = cardShape,
                maxCardWidth = scaledMaxCardWidth,
                maxArtSize = scaledMaxArtSize,
                scale = scale,
                glassOpacity = glassOpacity,
                modifier = modifier
            )
        }
        CardLayoutType.SPOTIFY_LYRICS_CARD -> {
            SpotifyLyricsShareCard(
                track = track,
                crispArtImageBitmap = crispArtImageBitmap,
                palette = palette,
                badgeColor = badgeColor,
                styleConfig = styleConfig,
                cardShape = cardShape,
                maxCardWidth = scaledMaxCardWidth,
                scale = scale,
                glassOpacity = glassOpacity,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun FloatingGlassShareCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    glassOpacity: Float,
    modifier: Modifier = Modifier
) {
    val glassBlur = styleConfig.cardBlurRadius.coerceIn(0f, 40f)
    val glassDownFactor = (glassBlur / 20f).coerceIn(0f, 1f)
    val glassUpFactor = ((glassBlur - 20f) / 20f).coerceIn(0f, 1f)

    val topAlpha = (if (glassBlur <= 20f) {
        0.20f + (0.67f * glassDownFactor)
    } else {
        0.87f - (0.31f * glassUpFactor)
    }) * glassOpacity
    val bottomAlpha = (if (glassBlur <= 20f) {
        0.30f + (0.62f * glassDownFactor)
    } else {
        0.92f - (0.28f * glassUpFactor)
    }) * glassOpacity
    val glassBorderAlpha = (if (glassBlur <= 20f) {
        0.30f - (0.16f * glassDownFactor)
    } else {
        0.14f + (0.24f * glassUpFactor)
    }) * glassOpacity
    val glassBorderWidth = (1.0f + (1.2f * glassUpFactor)).dp
    val sideFrostGlowColor = remember(palette.lightVibrantColor) {
        lerp(Color.White, palette.lightVibrantColor, 0.32f)
    }

    val cardBackgroundBrush = remember(
        palette.vibrantColor,
        palette.dominantColor,
        palette.darkMutedColor,
        topAlpha,
        bottomAlpha
    ) {
        val accentTint = lerp(palette.dominantColor, palette.vibrantColor, 0.18f)
        val adaptiveTop = lerp(accentTint, Color(0xFF16161C), 0.42f).copy(alpha = topAlpha.coerceIn(0f, 1f))
        val adaptiveBottom = lerp(palette.darkMutedColor, Color(0xFF0C0C10), 0.40f).copy(alpha = bottomAlpha.coerceIn(0f, 1f))
        Brush.verticalGradient(colors = listOf(adaptiveTop, adaptiveBottom))
    }

    val shadowElevation = (24 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale * glassOpacity).dp
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (32 * scale).dp)
    val innerPadding = (16 * scale).dp
    val cornerDpPx = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(6).dp

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.78f * scale).coerceIn(0.44f, 0.94f))
            .then(
                if (shadowElevation > 0.5.dp) {
                    Modifier.shadow(
                        elevation = shadowElevation,
                        shape = cardShape,
                        ambientColor = Color.Black.copy(alpha = 0.65f * glassOpacity),
                        spotColor = Color.Black.copy(alpha = 0.75f * glassOpacity)
                    )
                } else {
                    Modifier
                }
            )
            .drawBehind {
                if (glassUpFactor > 0.01f && glassOpacity > 0.01f) {
                    val cornerPx = cornerDpPx.toPx()
                    val haloBlurPx = ((10f + 24f * glassUpFactor) * density).coerceAtLeast(1f)
                    val spreadPx = (4f * glassUpFactor * density)
                    drawIntoCanvas { canvas ->
                        val haloPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                            style = AndroidPaint.Style.STROKE
                            strokeWidth = (5f + 9f * glassUpFactor) * density
                            color = sideFrostGlowColor.copy(alpha = 0.36f * glassUpFactor * glassOpacity).toArgb()
                            maskFilter = BlurMaskFilter(haloBlurPx, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawRoundRect(
                            -spreadPx,
                            -spreadPx,
                            size.width + spreadPx,
                            size.height + spreadPx,
                            cornerPx,
                            cornerPx,
                            haloPaint
                        )
                    }
                }
            }
            .clip(cardShape)
            .background(cardBackgroundBrush)
            .then(
                if (glassBorderAlpha > 0.01f) {
                    Modifier.border(
                        width = glassBorderWidth,
                        color = Color.White.copy(alpha = glassBorderAlpha.coerceIn(0f, 1f)),
                        shape = cardShape
                    )
                } else {
                    Modifier
                }
            )
            .testTag("music_story_card_container")
    ) {
        if (glassUpFactor > 0.01f && glassOpacity > 0.01f && track.albumArt != null) {
            BlurredCardCoverLayer(
                albumArt = track.albumArt,
                blurRadius = 26f + (14f * glassUpFactor),
                scrimTopAlpha = 0.62f,
                scrimMidAlpha = 0.70f,
                scrimBottomAlpha = 0.78f,
                layerAlpha = 0.56f * glassUpFactor * glassOpacity,
                modifier = Modifier.matchParentSize()
            )
        }
        if (glassUpFactor > 0.01f && glassOpacity > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to sideFrostGlowColor.copy(alpha = 0.28f * glassUpFactor * glassOpacity),
                            0.18f to sideFrostGlowColor.copy(alpha = 0.05f * glassUpFactor * glassOpacity),
                            0.82f to sideFrostGlowColor.copy(alpha = 0.05f * glassUpFactor * glassOpacity),
                            1.0f to sideFrostGlowColor.copy(alpha = 0.28f * glassUpFactor * glassOpacity)
                        )
                    )
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.White.copy(alpha = 0.20f * glassUpFactor * glassOpacity),
                            0.15f to Color.White.copy(alpha = 0.02f * glassUpFactor * glassOpacity),
                            0.85f to Color.White.copy(alpha = 0.02f * glassUpFactor * glassOpacity),
                            1.0f to Color.White.copy(alpha = 0.18f * glassUpFactor * glassOpacity)
                        )
                    )
                    .drawBehind {
                        val cornerPx = cornerDpPx.toPx()
                        val innerBlurPx = ((7f + 14f * glassUpFactor) * density).coerceAtLeast(1f)
                        drawIntoCanvas { canvas ->
                            val innerRimPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                style = AndroidPaint.Style.STROKE
                                strokeWidth = (7f + 11f * glassUpFactor) * density
                                color = Color.White.copy(alpha = 0.28f * glassUpFactor * glassOpacity).toArgb()
                                maskFilter = BlurMaskFilter(innerBlurPx, BlurMaskFilter.Blur.NORMAL)
                            }
                            canvas.nativeCanvas.drawRoundRect(
                                0f,
                                0f,
                                size.width,
                                size.height,
                                cornerPx,
                                cornerPx,
                                innerRimPaint
                            )
                        }
                    }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF18181E)),
                contentAlignment = Alignment.Center
            ) {
                if (crispArtImageBitmap != null) {
                    Image(
                        bitmap = crispArtImageBitmap,
                        contentDescription = "Album art for ${track.title}",
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.vibrantColor.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height((14 * scale).dp))

            Text(
                text = track.title,
                color = Color.White,
                fontSize = (18 * scale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.testTag("track_title_text")
            )

            Spacer(modifier = Modifier.height((4 * scale).dp))

            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = (14 * scale).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("track_artist_text")
            )

            Spacer(modifier = Modifier.height((16 * scale).dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BitChordBranding(
                    brandMode = styleConfig.brandMode,
                    badgeColor = badgeColor,
                    logoColorMode = styleConfig.logoColorMode,
                    badgeSize = (20 * scale).dp,
                    textSize = (14 * scale).sp,
                    textColor = Color.White.copy(alpha = 0.95f),
                    showWordmark = styleConfig.showLogoWordmark,
                    modifier = Modifier.weight(1f)
                )

                if (styleConfig.enableWaveform) {
                    Spacer(modifier = Modifier.width((8 * scale).dp))
                    DynamicIslandWaveformBars(
                        track = track,
                        color = styleConfig.waveformColor,
                        beatSyncEnabled = styleConfig.waveformBeatSync,
                        speedMultiplier = styleConfig.waveformSpeedMultiplier,
                        forceMultiplier = styleConfig.waveformForce,
                        animateAlways = true,
                        scale = scale
                    )
                }
            }
        }
    }
}

/**
 * 4-bar iOS Dynamic Island style audio equalizer displayed in the Floating Glass Card footer
 * and Settings speed/beat/force preview.
 *
 * Uses continuous phase integration + in-place array updates so adjusting Wave Speed or Wave Force
 * never resets phase or causes frame jitter.
 */
@Composable
fun DynamicIslandWaveformBars(
    track: MediaTrackInfo,
    color: Color,
    beatSyncEnabled: Boolean,
    speedMultiplier: Float = 1.0f,
    forceMultiplier: Float = 1.35f,
    animateAlways: Boolean = true,
    scale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sampler = remember { AudioBeatMonitor.LiveSessionSampler() }
    val barLevels = remember { FloatArray(4) { 0.45f } }
    var frameCounter by remember { mutableStateOf(0L) }

    val latestTitle by androidx.compose.runtime.rememberUpdatedState(track.title)
    val latestArtist by androidx.compose.runtime.rememberUpdatedState(track.artist)
    val latestPlaying by androidx.compose.runtime.rememberUpdatedState(track.isPlaying)
    val latestBeatSync by androidx.compose.runtime.rememberUpdatedState(beatSyncEnabled)
    val latestSpeed by androidx.compose.runtime.rememberUpdatedState(speedMultiplier)
    val latestForce by androidx.compose.runtime.rememberUpdatedState(forceMultiplier)
    val latestAnimateAlways by androidx.compose.runtime.rememberUpdatedState(animateAlways)

    DisposableEffect(beatSyncEnabled) {
        if (beatSyncEnabled) {
            sampler.startIfPermitted(context)
        } else {
            sampler.stop()
        }
        onDispose {
            sampler.stop()
        }
    }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        var lastEnsureNanos = 0L
        var phaseTimeSec = 0f
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dtSec = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameNanos - lastFrameNanos).coerceIn(0L, 100_000_000L)) / 1_000_000_000f
            }
            lastFrameNanos = frameNanos

            val sync = latestBeatSync
            val speed = latestSpeed.coerceIn(0.4f, 2.5f)
            phaseTimeSec += dtSec * (if (sync) 1.0f else speed)

            if (sync && frameNanos - lastEnsureNanos >= 800_000_000L) {
                lastEnsureNanos = frameNanos
                sampler.ensureActive(context)
            }

            val live = if (sync) sampler.currentBands() else null
            val liveSignal = sync && sampler.hasLiveSignal()
            val shouldAnimate = latestAnimateAlways || latestPlaying || liveSignal

            if (shouldAnimate) {
                val computed = AudioBeatMonitor.computeBarLevels(
                    trackTitle = latestTitle,
                    trackArtist = latestArtist,
                    isPlaying = if (latestAnimateAlways) true else latestPlaying,
                    timeSec = phaseTimeSec,
                    liveAudioBands = live,
                    speedMultiplier = 1.0f, // speed already smoothly integrated into phaseTimeSec
                    beatSyncMode = sync,
                    animateWhenPaused = latestAnimateAlways,
                    hasLiveSignal = liveSignal,
                    forceMultiplier = latestForce
                )
                for (i in 0..3) {
                    val current = barLevels[i]
                    val target = computed[i]
                    // 0ms instant attack when rising so beats punch immediately; silky release smoothing on fall
                    barLevels[i] = if (target >= current) {
                        target
                    } else {
                        current + (target - current) * (dtSec * 14f).coerceIn(0.18f, 1.0f)
                    }
                }
                frameCounter++
            }
        }
    }

    val boxW = (26 * scale).dp
    val boxH = (18 * scale).dp

    Canvas(
        modifier = modifier
            .size(width = boxW, height = boxH)
            .testTag("floating_glass_waveform")
    ) {
        // Subscribe purely inside DrawScope so parent rows don't recompose 60-120 times per second
        frameCounter
        val padX = size.width * 0.06f
        val usableW = (size.width - padX * 2f).coerceAtLeast(6f)
        val gap = usableW * 0.14f
        val barWidth = ((usableW - gap * 3f) / 4f).coerceAtLeast(2.2f)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val maxBarH = size.height * 0.94f
        for (i in 0..3) {
            val lvl = barLevels[i].coerceIn(0.18f, 1.0f)
            val barH = (maxBarH * lvl).coerceAtLeast(barWidth * 1.15f)
            val x = padX + i * (barWidth + gap)
            val top = (size.height - barH) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, barH),
                cornerRadius = radius
            )
        }
    }
}

@Composable
private fun FloatingGlowArtwork(
    track: MediaTrackInfo,
    crispArtImageBitmap: ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    glassOpacity: Float,
    modifier: Modifier = Modifier
) {
    val glowIntensity = styleConfig.glowIntensity * glassOpacity
    val effectiveWidth = minOf(maxCardWidth, maxArtSize + (16 * scale).dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = effectiveWidth)
            .fillMaxWidth((0.74f * scale).coerceIn(0.42f, 0.90f))
            .testTag("music_story_card_container")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (glowIntensity > 0.02f) {
                        Modifier.shadow(
                            elevation = (24 * glowIntensity).dp,
                            shape = RoundedCornerShape(styleConfig.cornerRadiusDp.dp),
                            ambientColor = palette.vibrantColor.copy(alpha = 0.6f * glassOpacity),
                            spotColor = palette.vibrantColor.copy(alpha = 0.75f * glassOpacity)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(RoundedCornerShape(styleConfig.cornerRadiusDp.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (crispArtImageBitmap != null) {
                Image(
                    bitmap = crispArtImageBitmap,
                    contentDescription = "Album art for ${track.title}",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height((18 * scale).dp))

        Text(
            text = track.title,
            color = Color.White,
            fontSize = (20 * scale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_title_text")
        )

        Spacer(modifier = Modifier.height((5 * scale).dp))

        Text(
            text = track.artist,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = (15 * scale).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_artist_text")
        )

        Spacer(modifier = Modifier.height((18 * scale).dp))

        BitChordBranding(
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = (22 * scale).dp,
            textSize = (15 * scale).sp,
            showWordmark = styleConfig.showLogoWordmark
        )
    }
}

@Composable
private fun MinimalCleanStoryCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    glassOpacity: Float,
    modifier: Modifier = Modifier
) {
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (28 * scale).dp)
    val shadowDp = (20 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale * glassOpacity).dp

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.78f * scale).coerceIn(0.44f, 0.94f))
            .then(
                if (shadowDp > 0.5.dp) {
                    Modifier.shadow(elevation = shadowDp, shape = cardShape)
                } else {
                    Modifier
                }
            )
            .clip(cardShape)
            .background(Color(0xFF141418).copy(alpha = 0.88f * glassOpacity))
            .then(
                if (glassOpacity > 0.02f) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.10f * glassOpacity), cardShape)
                } else {
                    Modifier
                }
            )
            .testTag("music_story_card_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding((14 * scale).dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (crispArtImageBitmap != null) {
                    Image(
                        bitmap = crispArtImageBitmap,
                        contentDescription = "Album art for ${track.title}",
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height((14 * scale).dp))

            Text(
                text = track.title,
                color = Color.White,
                fontSize = (18 * scale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("track_title_text")
            )

            Spacer(modifier = Modifier.height((3 * scale).dp))

            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = (13 * scale).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("track_artist_text")
            )

            Spacer(modifier = Modifier.height((14 * scale).dp))

            BitChordBranding(
                brandMode = styleConfig.brandMode,
                badgeColor = badgeColor,
                logoColorMode = styleConfig.logoColorMode,
                badgeSize = (18 * scale).dp,
                textSize = (13 * scale).sp,
                showWordmark = styleConfig.showLogoWordmark
            )
        }
    }
}

@Composable
private fun SpotifyLyricsShareCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    scale: Float,
    glassOpacity: Float,
    modifier: Modifier = Modifier
) {
    val useSongArtBg = styleConfig.lyricsCardBackgroundMode == LyricsCardBackgroundMode.SONG_ART &&
        crispArtImageBitmap != null

    val (cardBgColor, paletteInkColor) = remember(palette.vibrantColor, palette.dominantColor, palette.darkVibrantColor) {
        resolveLyricsCardColors(palette)
    }
    val inkColor = if (useSongArtBg || glassOpacity < 0.45f) Color.White else paletteInkColor

    val lyricsText = remember(styleConfig.customLyrics, track.title) {
        styleConfig.resolveLyricsDisplayLines(track)
    }
    val isPlaceholder = styleConfig.customLyrics.trim().isEmpty() && lyricsText.startsWith("Add or auto-search")

    val shadowElevation = (22 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale * glassOpacity).dp
    val horizontalPad = (20 * scale).dp
    val verticalPad = (20 * scale).dp
    val thumbSize = (44 * scale).dp

    Box(
        modifier = modifier
            .widthIn(max = maxCardWidth)
            .fillMaxWidth((0.80f * scale).coerceIn(0.46f, 0.94f))
            .then(
                if (shadowElevation > 0.5.dp) {
                    Modifier.shadow(
                        elevation = shadowElevation,
                        shape = cardShape,
                        ambientColor = Color.Black.copy(alpha = 0.55f * glassOpacity),
                        spotColor = Color.Black.copy(alpha = 0.65f * glassOpacity)
                    )
                } else {
                    Modifier
                }
            )
            .clip(cardShape)
            .background(
                if (useSongArtBg) {
                    Color(0xFF141418).copy(alpha = glassOpacity)
                } else {
                    cardBgColor.copy(alpha = glassOpacity)
                }
            )
            .then(
                if (useSongArtBg && glassOpacity > 0.02f) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.16f * glassOpacity), cardShape)
                } else {
                    Modifier
                }
            )
            .testTag("music_story_card_container")
    ) {
        if (useSongArtBg && crispArtImageBitmap != null && glassOpacity > 0.01f) {
            BlurredCardCoverLayer(
                albumArt = track.albumArt,
                blurRadius = styleConfig.lyricsArtBlurRadius,
                layerAlpha = glassOpacity,
                modifier = Modifier.matchParentSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad, vertical = verticalPad)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .clip(RoundedCornerShape((5 * scale).dp))
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (crispArtImageBitmap != null) {
                        Image(
                            bitmap = crispArtImageBitmap,
                            contentDescription = "Album art for ${track.title}",
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = inkColor.copy(alpha = 0.7f),
                            modifier = Modifier.size((22 * scale).dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width((12 * scale).dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = inkColor,
                        fontSize = (14 * scale).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("track_title_text")
                    )
                    Spacer(modifier = Modifier.height((2 * scale).dp))
                    Text(
                        text = track.artist,
                        color = inkColor.copy(alpha = 0.78f),
                        fontSize = (12 * scale).sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("track_artist_text")
                    )
                }
            }

            Spacer(modifier = Modifier.height((22 * scale).dp))

            Text(
                text = lyricsText,
                color = inkColor.copy(alpha = if (isPlaceholder) 0.72f else 1f),
                fontSize = (20 * scale).sp,
                lineHeight = (26 * scale).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.45).sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lyrics_card_body_text")
            )

            Spacer(modifier = Modifier.height((24 * scale).dp))

            val footerBadgeColor = if (styleConfig.logoColorMode == LogoColorMode.TEMPLATE_DYNAMIC) {
                inkColor
            } else {
                badgeColor
            }
            BitChordBranding(
                brandMode = styleConfig.brandMode,
                badgeColor = footerBadgeColor,
                logoColorMode = styleConfig.logoColorMode,
                badgeSize = (19 * scale).dp,
                textSize = (13.5 * scale).sp,
                textColor = inkColor.copy(alpha = 0.95f),
                showWordmark = styleConfig.showLogoWordmark
            )
        }
    }
}

@Composable
private fun BlurredCardCoverLayer(
    albumArt: Bitmap?,
    blurRadius: Float,
    modifier: Modifier = Modifier,
    scrimTopAlpha: Float = 0.44f,
    scrimMidAlpha: Float = 0.56f,
    scrimBottomAlpha: Float = 0.74f,
    layerAlpha: Float = 1f
) {
    val clampedRadius = blurRadius.coerceIn(0f, 40f)
    val safeBitmap = remember(albumArt) {
        albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = 720) }
    } ?: return

    Box(modifier = modifier) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val imageBitmap = remember(safeBitmap) { safeBitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                alpha = layerAlpha.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (clampedRadius > 0.35f) {
                            Modifier.blur(
                                radius = (clampedRadius * 0.72f).dp,
                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        } else {
            val mipPyramid by produceState<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(
                initialValue = null,
                key1 = safeBitmap
            ) {
                value = withContext(Dispatchers.Default) {
                    BitmapBlurUtils.computeBlurMipPyramid(safeBitmap)?.let { (low, mid, high) ->
                        Triple(low.asImageBitmap(), mid.asImageBitmap(), high.asImageBitmap())
                    }
                }
            }
            val baseImg = remember(safeBitmap) { safeBitmap.asImageBitmap() }
            Image(
                bitmap = baseImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                alpha = layerAlpha.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize()
            )
            val pyramid = mipPyramid
            if (pyramid != null && clampedRadius > 0.2f) {
                val lowAlpha = (clampedRadius / 12f).coerceIn(0f, 1f) * layerAlpha
                val midAlpha = ((clampedRadius - 12f) / 12f).coerceIn(0f, 1f) * layerAlpha
                val highAlpha = ((clampedRadius - 24f) / 16f).coerceIn(0f, 1f) * layerAlpha

                if (lowAlpha > 0.01f && midAlpha < 0.99f) {
                    Image(
                        bitmap = pyramid.first,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        alpha = lowAlpha.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (midAlpha > 0.01f && highAlpha < 0.99f) {
                    Image(
                        bitmap = pyramid.second,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        alpha = midAlpha.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (highAlpha > 0.01f) {
                    Image(
                        bitmap = pyramid.third,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        alpha = highAlpha.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = (scrimTopAlpha * layerAlpha).coerceIn(0f, 1f)),
                            Color.Black.copy(alpha = (scrimMidAlpha * layerAlpha).coerceIn(0f, 1f)),
                            Color.Black.copy(alpha = (scrimBottomAlpha * layerAlpha).coerceIn(0f, 1f))
                        )
                    )
                )
        )
    }
}

fun resolveLyricsCardColors(palette: CardPalette): Pair<Color, Color> {
    val blended = lerp(palette.vibrantColor, palette.dominantColor, 0.25f)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(blended.toArgb(), hsv)
    if (hsv[1] >= 0.10f) {
        hsv[1] = hsv[1].coerceIn(0.45f, 0.88f)
        hsv[2] = hsv[2].coerceIn(0.36f, 0.78f)
    } else {
        hsv[2] = hsv[2].coerceIn(0.24f, 0.76f)
    }
    val cardColor = Color(android.graphics.Color.HSVToColor(hsv))
    val inkColor = if (cardColor.luminance() > 0.46f) Color(0xFF111114) else Color.White
    return Pair(cardColor, inkColor)
}

fun resolveGradientStops(
    palette: CardPalette,
    styleConfig: CardStyleConfig
): List<Color> {
    return when (styleConfig.gradientStyle) {
        GradientStyle.ADAPTIVE_HARMONY -> listOf(
            lerp(palette.vibrantColor, palette.dominantColor, 0.28f),
            lerp(palette.dominantColor, palette.darkMutedColor, 0.48f),
            palette.darkMutedColor
        )
        GradientStyle.VIBRANT_TO_DARK_MUTED -> listOf(
            palette.vibrantColor,
            lerp(palette.vibrantColor, palette.darkMutedColor, 0.55f),
            palette.darkMutedColor
        )
        GradientStyle.DOMINANT_TO_DARK_MUTED -> listOf(
            palette.dominantColor,
            lerp(palette.dominantColor, palette.darkMutedColor, 0.52f),
            palette.darkMutedColor
        )
        GradientStyle.LIGHT_VIBRANT_TO_DARK_VIBRANT -> listOf(
            palette.lightVibrantColor,
            palette.vibrantColor,
            palette.darkVibrantColor
        )
        GradientStyle.CINEMATIC_AURORA -> listOf(
            palette.vibrantColor,
            palette.mutedColor,
            palette.darkVibrantColor
        )
        GradientStyle.DEEP_NIGHT -> listOf(
            Color(0xFF1F1D24),
            Color(0xFF121216),
            Color(0xFF070709)
        )
        GradientStyle.SUNSET_GLOW -> listOf(
            Color(0xFFFF5E36),
            Color(0xFF9E1B44),
            Color(0xFF280618)
        )
    }
}

fun resolveGradientColors(
    palette: CardPalette,
    styleConfig: CardStyleConfig
): Pair<Color, Color> {
    val stops = resolveGradientStops(palette, styleConfig)
    return Pair(stops.first(), stops.last())
}
