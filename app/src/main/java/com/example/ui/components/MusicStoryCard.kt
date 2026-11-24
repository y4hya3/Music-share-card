package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.util.DevicePerformanceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
private fun rememberPreparedCrispBitmap(
    source: Bitmap?,
    minTargetDim: Int,
    maxDimension: Int,
    sharpenStrength: Float = -1f
): Bitmap? {
    val initialSync = remember(source, minTargetDim, maxDimension, sharpenStrength) {
        if (source == null || source.isRecycled) {
            null
        } else {
            BitmapBlurUtils.getCachedCrispArtworkIfReady(
                source = source,
                minTargetDim = minTargetDim,
                maxDimension = maxDimension,
                sharpenStrength = sharpenStrength
            ) ?: if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O || source.config != Bitmap.Config.HARDWARE) &&
                source.config == Bitmap.Config.ARGB_8888 &&
                maxOf(source.width, source.height) <= 2048
            ) {
                source
            } else {
                null
            }
        }
    }

    val prepared by produceState(
        initialValue = initialSync,
        key1 = source,
        key2 = minTargetDim,
        key3 = maxDimension
    ) {
        if (source == null || source.isRecycled) {
            value = null
            return@produceState
        }
        val cached = BitmapBlurUtils.getCachedCrispArtworkIfReady(
            source = source,
            minTargetDim = minTargetDim,
            maxDimension = maxDimension,
            sharpenStrength = sharpenStrength
        )
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            BitmapBlurUtils.prepareCrispArtwork(
                source = source,
                minTargetDim = minTargetDim,
                maxDimension = maxDimension,
                sharpenStrength = sharpenStrength
            ) ?: initialSync
        }
    }
    return prepared?.takeIf { !it.isRecycled }
}

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
    val tier = DevicePerformanceTier.rememberDeviceTier()
    val crispFilter = FilterQuality.High
    val isUltraLowBlur = blurRadius <= 1f
    val isUnblurred = blurRadius <= 0.35f

    val bgMinTarget = if (isUltraLowBlur) {
        if (tier.isLowRam) 768 else 1024
    } else {
        0
    }
    val bgMaxDim = if (isUnblurred) tier.cardArtMaxDim else tier.bgSourceMaxDim
    val bgSharpen = if (isUnblurred) -1f else 0f

    val sharpOrGpuBgBitmap = rememberPreparedCrispBitmap(
        source = albumArt,
        minTargetDim = bgMinTarget,
        maxDimension = bgMaxDim,
        sharpenStrength = bgSharpen
    )

    var isCanvasOpaque by remember(activeCanvasVideoUrl) { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val stageWidthRatio = (maxWidth.value / 360f).coerceIn(0.65f, 1.45f)
        if (styleConfig.useTransparentBackground) {
            TransparentEditorCheckerboard(modifier = Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        val hideStaticUnderlay = !activeCanvasVideoUrl.isNullOrBlank() && isCanvasOpaque
        if (hideStaticUnderlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0E))
            )
        } else if (styleConfig.useBlurredAlbumBackground && sharpOrGpuBgBitmap != null) {
            val bgImageBitmap = remember(sharpOrGpuBgBitmap) {
                sharpOrGpuBgBitmap.asImageBitmap()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Crossfade(
                    targetState = bgImageBitmap,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "bg_artwork_crossfade",
                    modifier = Modifier.fillMaxSize()
                ) { targetBgImg ->
                    Image(
                        bitmap = targetBgImg,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = crispFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (blurRadius > 0.35f) {
                                    Modifier.blur(
                                        radius = (blurRadius * 0.92f * stageWidthRatio).dp,
                                        edgeTreatment = BlurredEdgeTreatment.Rectangle
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            } else {
                // Zero-lag shared mip-pyramid crossfade for API 24-30 with zero occluded overdraw
                val mipPyramid by produceState<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(
                    initialValue = null,
                    key1 = albumArt,
                    key2 = tier.pyramidLowDim
                ) {
                    val src = albumArt ?: sharpOrGpuBgBitmap
                    value = withContext(Dispatchers.Default) {
                        BitmapBlurUtils.computeBlurMipPyramid(
                            source = src,
                            lowDim = tier.pyramidLowDim,
                            midDim = tier.pyramidMidDim,
                            highDim = tier.pyramidHighDim
                        )?.let { (low, mid, high) ->
                            Triple(low.asImageBitmap(), mid.asImageBitmap(), high.asImageBitmap())
                        }
                    }
                }

                val pyramid = mipPyramid
                if (pyramid == null || blurRadius < 12f) {
                    Crossfade(
                        targetState = bgImageBitmap,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        label = "legacy_bg_artwork_crossfade",
                        modifier = Modifier.fillMaxSize()
                    ) { targetBgImg ->
                        Image(
                            bitmap = targetBgImg,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = crispFilter,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (pyramid != null && blurRadius > 0.2f) {
                    val lowAlpha = (blurRadius / 12f).coerceIn(0f, 1f)
                    val midAlpha = ((blurRadius - 12f) / 12f).coerceIn(0f, 1f)
                    val highAlpha = ((blurRadius - 24f) / 16f).coerceIn(0f, 1f)

                    if (lowAlpha > 0.01f && midAlpha < 0.99f) {
                        Image(
                            bitmap = pyramid.first,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Low,
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
                            filterQuality = FilterQuality.Low,
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
                            filterQuality = FilterQuality.Low,
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
                onVideoReadyChanged = { isCanvasOpaque = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Subtle dark overlay rgba(0, 0, 0, 0.25) drawn allocation-free for contrast on bright covers
        val darkOverlay = styleConfig.darkOverlayAlpha.coerceIn(0f, 1f)
        if (darkOverlay > 0.004f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(color = Color.Black, alpha = darkOverlay)
                    }
            )
        }
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
    val tier = DevicePerformanceTier.rememberDeviceTier()
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
    val cardArtMinTarget = if (tier.isLowRam) 768 else 1024
    val preparedCardBmp = rememberPreparedCrispBitmap(
        source = track.albumArt,
        minTargetDim = cardArtMinTarget,
        maxDimension = tier.cardArtMaxDim
    )
    val crispArtImageBitmap = remember(preparedCardBmp) {
        preparedCardBmp?.asImageBitmap()
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
        CardLayoutType.APP_MUSIC_CARD -> {
            AppMusicPlayerCard(
                track = track,
                crispArtImageBitmap = crispArtImageBitmap,
                palette = palette,
                styleConfig = styleConfig,
                maxCardWidth = scaledMaxCardWidth,
                maxArtSize = scaledMaxArtSize,
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
    val blurFrac = (glassBlur / 40f).coerceIn(0f, 1f)

    // Smoothly transition palette colors between tracks without delaying slider drag frames
    val animDominant by animateColorAsState(
        targetValue = palette.dominantColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "glass_dominant"
    )
    val animVibrant by animateColorAsState(
        targetValue = palette.vibrantColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "glass_vibrant"
    )
    val animDarkMuted by animateColorAsState(
        targetValue = palette.darkMutedColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "glass_dark_muted"
    )
    val animLightVibrant by animateColorAsState(
        targetValue = palette.lightVibrantColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "glass_light_vibrant"
    )

    // Classic clear-glass min at 0px (0.20->0.30 tint, 0.30 rim, rich drop shadow) scaling uniformly to deep frost at 40px
    val topAlpha = (0.20f + 0.50f * blurFrac) * glassOpacity
    val bottomAlpha = (0.30f + 0.46f * blurFrac) * glassOpacity
    val glassBorderAlpha = (0.30f + 0.08f * blurFrac) * glassOpacity
    val glassBorderWidth = (1.1f + 0.8f * blurFrac).dp
    val sideFrostGlowColor = remember(animLightVibrant) {
        lerp(Color.White, animLightVibrant, 0.28f)
    }

    val adaptiveTopOpaque = remember(animDominant, animVibrant) {
        val accentTint = lerp(animDominant, animVibrant, 0.18f)
        lerp(accentTint, Color(0xFF16161C), 0.44f)
    }
    val adaptiveBottomOpaque = remember(animDarkMuted) {
        lerp(animDarkMuted, Color(0xFF0C0C10), 0.42f)
    }

    val shadowColor = remember(styleConfig.cardShadowColorMode, styleConfig.customShadowColor, palette) {
        styleConfig.resolveShadowColor(palette)
    }
    val glowStrength = styleConfig.glowIntensity.coerceIn(0f, 1f)
    val isInverseShadow = styleConfig.cardShadowInverse
    val shadowAlpha = (0.56f * glowStrength * glassOpacity).coerceIn(0f, 0.72f)
    val shadowBlurDp = (8f + 14f * glowStrength + 6f * blurFrac) * glowStrength * scale
    val shadowOffsetYDp = (if (isInverseShadow) -7f else 7f).dp * scale
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (32 * scale).dp)
    val innerPadding = (16 * scale).dp
    val cornerDpPx = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(6).dp

    // Pre-allocated native drawing primitives for zero-GC-allocation 60-120fps slider scrubbing
    val cardPath = remember { AndroidPath() }
    val shadowPaint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val haloPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.STROKE }
    }
    val clearPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }
    val blurFilterState = remember {
        object {
            var lastShadowBlurKey = -1
            var lastShadowFilter: BlurMaskFilter? = null
            var lastHaloBlurKey = -1
            var lastHaloFilter: BlurMaskFilter? = null
        }
    }
    val sheenBrush = remember(sideFrostGlowColor) {
        Brush.linearGradient(
            0.0f to sideFrostGlowColor.copy(alpha = 0.14f),
            0.5f to Color.White.copy(alpha = 0.04f),
            1.0f to sideFrostGlowColor.copy(alpha = 0.10f)
        )
    }

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.78f * scale).coerceIn(0.44f, 0.94f))
            .drawBehind {
                val cornerPx = cornerDpPx.toPx()
                cardPath.reset()
                cardPath.addRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, AndroidPath.Direction.CW)

                // Soft outer drop shadow clipped strictly outside the translucent card so sides never stain dark
                if (glowStrength > 0.01f && shadowAlpha > 0.01f && glassOpacity > 0.01f) {
                    val blurPx = (shadowBlurDp * density).coerceAtLeast(1f)
                    val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                    if (blurKey != blurFilterState.lastShadowBlurKey || blurFilterState.lastShadowFilter == null) {
                        blurFilterState.lastShadowBlurKey = blurKey
                        blurFilterState.lastShadowFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                    }
                    val outerTint = if (isInverseShadow && shadowColor == Color.Black) {
                        lerp(Color.White, palette.lightVibrantColor, 0.35f)
                    } else {
                        shadowColor
                    }
                    shadowPaint.color = outerTint.copy(alpha = shadowAlpha).toArgb()
                    shadowPaint.maskFilter = blurFilterState.lastShadowFilter
                    val offsetY = shadowOffsetYDp.toPx()
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val saved = native.save()
                            native.clipOutPath(cardPath)
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                shadowPaint
                            )
                            native.restoreToCount(saved)
                        } else {
                            val pad = blurPx * 2f + offsetY
                            @Suppress("DEPRECATION")
                            val savedLayer = native.saveLayer(
                                -pad,
                                -pad,
                                size.width + pad,
                                size.height + pad,
                                null,
                                android.graphics.Canvas.ALL_SAVE_FLAG
                            )
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                shadowPaint
                            )
                            native.drawPath(cardPath, clearPaint)
                            native.restoreToCount(savedLayer)
                        }
                    }
                }

                // Soft frosted outer ambient glow scaling smoothly to 0 when blur decreases
                if (blurFrac > 0.10f && glassOpacity > 0.01f) {
                    val haloBlurPx = ((6f + 20f * blurFrac) * density).coerceAtLeast(1f)
                    val haloKey = (haloBlurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                    if (haloKey != blurFilterState.lastHaloBlurKey || blurFilterState.lastHaloFilter == null) {
                        blurFilterState.lastHaloBlurKey = haloKey
                        blurFilterState.lastHaloFilter = BlurMaskFilter(haloKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                    }
                    val spreadPx = 3f * blurFrac * density
                    haloPaint.strokeWidth = (3f + 7f * blurFrac) * density
                    haloPaint.color = sideFrostGlowColor.copy(alpha = 0.20f * blurFrac * blurFrac * glassOpacity).toArgb()
                    haloPaint.maskFilter = blurFilterState.lastHaloFilter
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val saved = native.save()
                            native.clipOutPath(cardPath)
                            native.drawRoundRect(
                                -spreadPx,
                                -spreadPx,
                                size.width + spreadPx,
                                size.height + spreadPx,
                                cornerPx,
                                cornerPx,
                                haloPaint
                            )
                            native.restoreToCount(saved)
                        } else {
                            native.drawRoundRect(
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
            }
            .clip(cardShape)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            adaptiveTopOpaque.copy(alpha = topAlpha.coerceIn(0f, 1f)),
                            adaptiveBottomOpaque.copy(alpha = bottomAlpha.coerceIn(0f, 1f))
                        )
                    )
                )
            }
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
        if (glassBlur > 0.5f && glassOpacity > 0.01f && (crispArtImageBitmap != null || track.albumArt != null)) {
            BlurredCardCoverLayer(
                albumArt = track.albumArt,
                predecodedImageBitmap = crispArtImageBitmap,
                blurRadius = glassBlur,
                scrimStrength = blurFrac,
                layerAlpha = 0.60f * blurFrac * glassOpacity,
                modifier = Modifier.matchParentSize()
            )
        }
        if (blurFrac > 0.02f && glassOpacity > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = (blurFrac * glassOpacity).coerceIn(0f, 1f) }
                    .background(sheenBrush)
            )
        }
        if (isInverseShadow && glowStrength > 0.01f && glassOpacity > 0.01f) {
            InnerInverseShadowOverlay(
                shadowColor = shadowColor,
                strength = glowStrength * glassOpacity,
                cornerDp = cornerDpPx,
                modifier = Modifier.matchParentSize()
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
                Crossfade(
                    targetState = crispArtImageBitmap,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "floating_card_art_crossfade",
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
                        scale = scale,
                        modifier = Modifier.graphicsLayer()
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
 * Uses continuous phase integration + GPU layer isolation + device-tier frame pacing so low/mid
 * SoCs (Helio G35, Exynos 7885) stay smooth without re-drawing the glass backdrop every tick.
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
    val tier = DevicePerformanceTier.rememberDeviceTier()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleActive = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isLifecycleActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
    val latestActive by androidx.compose.runtime.rememberUpdatedState(isLifecycleActive)

    DisposableEffect(beatSyncEnabled, isLifecycleActive) {
        if (beatSyncEnabled && isLifecycleActive) {
            sampler.startIfPermitted(context)
        } else {
            sampler.stop()
        }
        onDispose {
            sampler.stop()
        }
    }

    val shouldRunFrameLoop = isLifecycleActive && (animateAlways || track.isPlaying || beatSyncEnabled)
    LaunchedEffect(shouldRunFrameLoop, tier.waveformFrameIntervalNs) {
        if (!shouldRunFrameLoop) return@LaunchedEffect
        var lastFrameNanos = 0L
        var lastEmitNanos = 0L
        var lastEnsureNanos = 0L
        var phaseTimeSec = 0.0
        var prevWasUsingLiveFeed = false
        var modeBlendRemainingSec = 0f
        val minIntervalNs = tier.waveformFrameIntervalNs

        while (true) {
            val frameNanos = withFrameNanos { it }
            if (!latestActive) {
                lastFrameNanos = frameNanos
                lastEmitNanos = frameNanos
                continue
            }
            if (minIntervalNs > 0L && lastEmitNanos != 0L && (frameNanos - lastEmitNanos) < minIntervalNs) {
                continue
            }
            val dtSec = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameNanos - lastFrameNanos).coerceIn(0L, 66_000_000L)) / 1_000_000_000f
            }
            lastFrameNanos = frameNanos
            lastEmitNanos = frameNanos

            val sync = latestBeatSync
            val speed = latestSpeed.coerceIn(0.4f, 2.5f)
            phaseTimeSec += dtSec * (if (sync) 1.0 else speed.toDouble())

            if (sync && frameNanos - lastEnsureNanos >= 800_000_000L) {
                lastEnsureNanos = frameNanos
                sampler.ensureActive(context)
            }

            val live = if (sync) sampler.currentBands() else null
            val liveFeed = sync && live != null && sampler.hasLiveFeed()
            if (liveFeed != prevWasUsingLiveFeed) {
                prevWasUsingLiveFeed = liveFeed
                modeBlendRemainingSec = 0.14f
            }
            val shouldAnimate = latestAnimateAlways || latestPlaying || liveFeed

            if (shouldAnimate) {
                val computed = AudioBeatMonitor.computeBarLevels(
                    trackTitle = latestTitle,
                    trackArtist = latestArtist,
                    isPlaying = if (latestAnimateAlways) true else latestPlaying,
                    timeSec = phaseTimeSec.toFloat(),
                    liveAudioBands = live,
                    speedMultiplier = 1.0f, // speed already smoothly integrated into phaseTimeSec
                    beatSyncMode = sync,
                    animateWhenPaused = latestAnimateAlways,
                    hasLiveSignal = liveFeed,
                    forceMultiplier = latestForce
                )
                val fallAlpha = (1f - kotlin.math.exp(-13f * dtSec)).coerceIn(0.04f, 1f)
                val isTransitioning = modeBlendRemainingSec > 0f
                if (isTransitioning) {
                    modeBlendRemainingSec = (modeBlendRemainingSec - dtSec).coerceAtLeast(0f)
                }
                val attackAlpha = if (isTransitioning) {
                    (1f - kotlin.math.exp(-22f * dtSec)).coerceIn(0.18f, 1f)
                } else {
                    1f
                }
                for (i in 0..3) {
                    val current = barLevels[i]
                    val target = computed[i]
                    barLevels[i] = if (target >= current) {
                        if (attackAlpha >= 0.99f) target else current + (target - current) * attackAlpha
                    } else {
                        current + (target - current) * fallAlpha
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
            .graphicsLayer()
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
    val rawGlow = styleConfig.glowIntensity.coerceIn(0f, 1f)
    val glowIntensity = rawGlow * glassOpacity
    val isInverseShadow = styleConfig.cardShadowInverse
    val shadowColor = remember(styleConfig.cardShadowColorMode, styleConfig.customShadowColor, palette) {
        styleConfig.resolveShadowColor(palette)
    }
    val effectiveWidth = minOf(maxCardWidth, maxArtSize + (16 * scale).dp)
    val artCornerDp = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(0).dp
    val artShape = remember(artCornerDp) { RoundedCornerShape(artCornerDp) }

    val glowPath = remember { AndroidPath() }
    val glowPaint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val glowBlurState = remember {
        object {
            var lastKey = -1
            var lastFilter: BlurMaskFilter? = null
        }
    }

    val artAspect = remember(crispArtImageBitmap) {
        crispArtImageBitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1).toFloat() } ?: 1f
    }
    val frameAspect = if (artAspect in 0.90f..1.12f) 1f else artAspect.coerceIn(0.72f, 1.48f)

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
                .aspectRatio(frameAspect)
                .drawBehind {
                    if (glowIntensity > 0.01f) {
                        val cornerPx = artCornerDp.toPx()
                        val blurPx = ((10f + 26f * glowIntensity) * scale * density).coerceAtLeast(1f)
                        val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                        if (blurKey != glowBlurState.lastKey || glowBlurState.lastFilter == null) {
                            glowBlurState.lastKey = blurKey
                            glowBlurState.lastFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                        }
                        val outerTint = if (isInverseShadow && shadowColor == Color.Black) {
                            lerp(Color.White, palette.lightVibrantColor, 0.35f)
                        } else {
                            shadowColor
                        }
                        val spreadPx = (if (isInverseShadow) 3f else 6f) * glowIntensity * scale * density
                        val offsetY = (if (isInverseShadow) -8f else 8f) * glowIntensity * scale * density
                        glowPaint.color = outerTint.copy(
                            alpha = (if (isInverseShadow) 0.50f else 0.72f) * glowIntensity
                        ).toArgb()
                        glowPaint.maskFilter = glowBlurState.lastFilter
                        glowPath.reset()
                        glowPath.addRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, AndroidPath.Direction.CW)
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val saved = native.save()
                                native.clipOutPath(glowPath)
                                native.drawRoundRect(
                                    -spreadPx,
                                    -spreadPx + offsetY,
                                    size.width + spreadPx,
                                    size.height + spreadPx + offsetY,
                                    cornerPx + spreadPx * 0.4f,
                                    cornerPx + spreadPx * 0.4f,
                                    glowPaint
                                )
                                native.restoreToCount(saved)
                            } else {
                                native.drawRoundRect(
                                    -spreadPx,
                                    -spreadPx + offsetY,
                                    size.width + spreadPx,
                                    size.height + spreadPx + offsetY,
                                    cornerPx + spreadPx * 0.4f,
                                    cornerPx + spreadPx * 0.4f,
                                    glowPaint
                                )
                            }
                        }
                    }
                }
                .then(
                    if (glowIntensity > 0.02f) {
                        val elevTint = if (isInverseShadow && shadowColor == Color.Black) {
                            lerp(Color.White, palette.lightVibrantColor, 0.35f)
                        } else {
                            shadowColor
                        }
                        Modifier.shadow(
                            elevation = (24 * glowIntensity).dp,
                            shape = artShape,
                            ambientColor = elevTint.copy(alpha = 0.65f * glowIntensity),
                            spotColor = elevTint.copy(alpha = 0.80f * glowIntensity)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(artShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = crispArtImageBitmap,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "floating_glow_art_crossfade",
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
            }

            if (isInverseShadow && glowIntensity > 0.01f) {
                InnerInverseShadowOverlay(
                    shadowColor = shadowColor,
                    strength = glowIntensity,
                    cornerDp = artCornerDp,
                    modifier = Modifier.fillMaxSize()
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
    val glowStrength = styleConfig.glowIntensity.coerceIn(0f, 1f) * glassOpacity
    val shadowDp = (22 * glowStrength * scale).dp
    val shadowColor = remember(styleConfig.cardShadowColorMode, styleConfig.customShadowColor, palette) {
        styleConfig.resolveShadowColor(palette)
    }
    val isInverseShadow = styleConfig.cardShadowInverse
    val shadowTint = if (isInverseShadow && shadowColor == Color.Black) {
        lerp(Color.White, palette.lightVibrantColor, 0.35f)
    } else {
        shadowColor
    }
    val cornerDp = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(6).dp
    val minCardPath = remember { AndroidPath() }
    val minShadowPaint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val minBlurState = remember {
        object {
            var lastKey = -1
            var lastFilter: BlurMaskFilter? = null
        }
    }

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.78f * scale).coerceIn(0.44f, 0.94f))
            .drawBehind {
                if (glowStrength > 0.01f && glassOpacity > 0.01f) {
                    val cornerPx = cornerDp.toPx()
                    val blurPx = ((10f + 22f * glowStrength) * scale * density).coerceAtLeast(1f)
                    val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                    if (blurKey != minBlurState.lastKey || minBlurState.lastFilter == null) {
                        minBlurState.lastKey = blurKey
                        minBlurState.lastFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                    }
                    minShadowPaint.color = shadowTint.copy(
                        alpha = ((if (isInverseShadow) 0.45f else 0.60f) * glowStrength).coerceIn(0f, 0.78f)
                    ).toArgb()
                    minShadowPaint.maskFilter = minBlurState.lastFilter
                    val offsetY = (if (isInverseShadow) -6f else 6f) * glowStrength * scale * density
                    minCardPath.reset()
                    minCardPath.addRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, AndroidPath.Direction.CW)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val saved = native.save()
                            native.clipOutPath(minCardPath)
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                minShadowPaint
                            )
                            native.restoreToCount(saved)
                        } else {
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                minShadowPaint
                            )
                        }
                    }
                }
            }
            .then(
                if (shadowDp > 0.4.dp) {
                    Modifier.shadow(
                        elevation = shadowDp,
                        shape = cardShape,
                        ambientColor = shadowTint.copy(alpha = 0.65f * glowStrength),
                        spotColor = shadowTint.copy(alpha = 0.80f * glowStrength)
                    )
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
        if (isInverseShadow && glowStrength > 0.01f) {
            InnerInverseShadowOverlay(
                shadowColor = shadowColor,
                strength = glowStrength,
                cornerDp = cornerDp,
                modifier = Modifier.matchParentSize()
            )
        }
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
                Crossfade(
                    targetState = crispArtImageBitmap,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "minimal_card_art_crossfade",
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
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_music_note),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
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

    val glowStrength = styleConfig.glowIntensity.coerceIn(0f, 1f) * glassOpacity
    val shadowElevation = (24 * glowStrength * scale).dp
    val shadowColor = remember(styleConfig.cardShadowColorMode, styleConfig.customShadowColor, palette) {
        styleConfig.resolveShadowColor(palette)
    }
    val isInverseShadow = styleConfig.cardShadowInverse
    val shadowTint = if (isInverseShadow && shadowColor == Color.Black) {
        lerp(Color.White, palette.lightVibrantColor, 0.35f)
    } else {
        shadowColor
    }
    val cornerDp = (styleConfig.cornerRadiusDp * scale).roundToInt().coerceAtLeast(6).dp
    val lyricsCardPath = remember { AndroidPath() }
    val lyricsShadowPaint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val lyricsBlurState = remember {
        object {
            var lastKey = -1
            var lastFilter: BlurMaskFilter? = null
        }
    }
    val horizontalPad = (20 * scale).dp
    val verticalPad = (20 * scale).dp
    val thumbSize = (44 * scale).dp

    Box(
        modifier = modifier
            .widthIn(max = maxCardWidth)
            .fillMaxWidth((0.80f * scale).coerceIn(0.46f, 0.94f))
            .drawBehind {
                if (glowStrength > 0.01f && glassOpacity > 0.01f) {
                    val cornerPx = cornerDp.toPx()
                    val blurPx = ((10f + 24f * glowStrength) * scale * density).coerceAtLeast(1f)
                    val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
                    if (blurKey != lyricsBlurState.lastKey || lyricsBlurState.lastFilter == null) {
                        lyricsBlurState.lastKey = blurKey
                        lyricsBlurState.lastFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
                    }
                    lyricsShadowPaint.color = shadowTint.copy(
                        alpha = ((if (isInverseShadow) 0.48f else 0.62f) * glowStrength).coerceIn(0f, 0.78f)
                    ).toArgb()
                    lyricsShadowPaint.maskFilter = lyricsBlurState.lastFilter
                    val offsetY = (if (isInverseShadow) -7f else 7f) * glowStrength * scale * density
                    lyricsCardPath.reset()
                    lyricsCardPath.addRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, AndroidPath.Direction.CW)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val saved = native.save()
                            native.clipOutPath(lyricsCardPath)
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                lyricsShadowPaint
                            )
                            native.restoreToCount(saved)
                        } else {
                            native.drawRoundRect(
                                0f,
                                offsetY,
                                size.width,
                                size.height + offsetY,
                                cornerPx,
                                cornerPx,
                                lyricsShadowPaint
                            )
                        }
                    }
                }
            }
            .then(
                if (shadowElevation > 0.4.dp) {
                    Modifier.shadow(
                        elevation = shadowElevation,
                        shape = cardShape,
                        ambientColor = shadowTint.copy(alpha = 0.65f * glowStrength),
                        spotColor = shadowTint.copy(alpha = 0.80f * glowStrength)
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
        if (useSongArtBg && glassOpacity > 0.01f) {
            BlurredCardCoverLayer(
                albumArt = track.albumArt,
                predecodedImageBitmap = crispArtImageBitmap,
                blurRadius = styleConfig.lyricsArtBlurRadius,
                layerAlpha = glassOpacity,
                modifier = Modifier.matchParentSize()
            )
        }
        if (isInverseShadow && glowStrength > 0.01f) {
            InnerInverseShadowOverlay(
                shadowColor = shadowColor,
                strength = glowStrength,
                cornerDp = cornerDp,
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
                    Crossfade(
                        targetState = crispArtImageBitmap,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        label = "lyrics_thumb_art_crossfade",
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
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_music_note),
                                    contentDescription = null,
                                    tint = inkColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size((22 * scale).dp)
                                )
                            }
                        }
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
internal fun BlurredCardCoverLayer(
    albumArt: Bitmap?,
    blurRadius: Float,
    modifier: Modifier = Modifier,
    predecodedImageBitmap: ImageBitmap? = null,
    scrimStrength: Float = 1f,
    layerAlpha: Float = 1f
) {
    val clampedRadius = blurRadius.coerceIn(0f, 40f)
    val tier = DevicePerformanceTier.rememberDeviceTier()
    val crispFilter = FilterQuality.High
    val baseScrimBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.34f),
                Color.Black.copy(alpha = 0.42f),
                Color.Black.copy(alpha = 0.50f)
            )
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val imageBitmap = predecodedImageBitmap ?: remember(albumArt, tier.bgSourceMaxDim) {
            albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = tier.bgSourceMaxDim)?.asImageBitmap() }
        } ?: return

        Box(modifier = modifier) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = crispFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = layerAlpha.coerceIn(0f, 1f) }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (scrimStrength * layerAlpha).coerceIn(0f, 1f) }
                    .background(baseScrimBrush)
            )
        }
        return
    }

    val fallbackImg = predecodedImageBitmap ?: remember(albumArt, tier.bgSourceMaxDim) {
        albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = tier.bgSourceMaxDim)?.asImageBitmap() }
    }
    if (albumArt == null && fallbackImg == null) return

    Box(modifier = modifier) {
        val mipPyramid by produceState<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(
            initialValue = null,
            key1 = albumArt,
            key2 = tier.pyramidLowDim
        ) {
            val src = albumArt ?: return@produceState
            value = withContext(Dispatchers.Default) {
                BitmapBlurUtils.computeBlurMipPyramid(
                    source = src,
                    lowDim = tier.pyramidLowDim,
                    midDim = tier.pyramidMidDim,
                    highDim = tier.pyramidHighDim
                )?.let { (low, mid, high) ->
                    Triple(low.asImageBitmap(), mid.asImageBitmap(), high.asImageBitmap())
                }
            }
        }
        val pyramid = mipPyramid
        if ((pyramid == null || clampedRadius < 12f) && fallbackImg != null) {
            Image(
                bitmap = fallbackImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = crispFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = layerAlpha.coerceIn(0f, 1f) }
            )
        }
        if (pyramid != null && clampedRadius > 0.2f) {
            val lowAlpha = (clampedRadius / 12f).coerceIn(0f, 1f) * layerAlpha
            val midAlpha = ((clampedRadius - 12f) / 12f).coerceIn(0f, 1f) * layerAlpha
            val highAlpha = ((clampedRadius - 24f) / 16f).coerceIn(0f, 1f) * layerAlpha

            if (lowAlpha > 0.01f && midAlpha < 0.99f) {
                Image(
                    bitmap = pyramid.first,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lowAlpha.coerceIn(0f, 1f) }
                )
            }
            if (midAlpha > 0.01f && highAlpha < 0.99f) {
                Image(
                    bitmap = pyramid.second,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = midAlpha.coerceIn(0f, 1f) }
                )
            }
            if (highAlpha > 0.01f) {
                Image(
                    bitmap = pyramid.third,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = highAlpha.coerceIn(0f, 1f) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (scrimStrength * layerAlpha).coerceIn(0f, 1f) }
                .background(baseScrimBrush)
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

@Composable
internal fun InnerInverseShadowOverlay(
    shadowColor: Color,
    strength: Float,
    cornerDp: Dp,
    modifier: Modifier = Modifier
) {
    val s = strength.coerceIn(0f, 1f)
    if (s <= 0.01f) return
    val insetPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
        }
    }
    val blurState = remember {
        object {
            var lastKey = -1
            var lastFilter: BlurMaskFilter? = null
        }
    }
    Box(
        modifier = modifier.drawBehind {
            val cornerPx = cornerDp.toPx()
            val strokePx = (14f + 22f * s) * density
            val blurPx = (10f + 16f * s) * density
            val blurKey = (blurPx / 2f).roundToInt().coerceAtLeast(1) * 2
            if (blurKey != blurState.lastKey || blurState.lastFilter == null) {
                blurState.lastKey = blurKey
                blurState.lastFilter = BlurMaskFilter(blurKey.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            insetPaint.strokeWidth = strokePx
            insetPaint.maskFilter = blurState.lastFilter
            insetPaint.color = shadowColor.copy(alpha = (0.72f * s).coerceIn(0f, 0.85f)).toArgb()
            val pad = strokePx * 0.25f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(
                    -pad,
                    -pad,
                    size.width + pad,
                    size.height + pad,
                    cornerPx,
                    cornerPx,
                    insetPaint
                )
            }
        }
    )
}

