package com.example.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen blurred album cover background (`ContentScale.Crop` / object-fit: cover).
 * Performance-tuned for both Redmi 9C (PowerVR GE8320 / Helio G35 / API 29-30) and
 * Samsung Galaxy Tab A7 LTE (Adreno 610 / Snapdragon 662 / API 29-31):
 * - Uses high-res crisp texture at 0px sharp blur and progressive mipmap scaling at active blur
 *   so backgrounds never look blocky or chopped on large screens.
 */
@Composable
fun BlurredAlbumBackground(
    track: MediaTrackInfo,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    modifier: Modifier = Modifier,
    activeCanvasVideoUrl: String? = null
) {
    val blurRadius = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f)
    val quantizedRadius = blurRadius.roundToInt()
    val albumArt = track.albumArt

    val sharpOrGpuBgBitmap = remember(albumArt) {
        if (albumArt != null) {
            val targetMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 540 else 720
            BitmapBlurUtils.toSafeSoftwareBitmap(albumArt, maxDimension = targetMax)
        } else {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                            if (blurRadius > 0.5f) {
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
                // CPU StackBlur fallback (API 24-30, e.g. Redmi 9C / Android 10-11)
                // Keeps crisp 720px when sharp (0px), 380px for light blur, 260px for deep blur
                val blurredBitmap by produceState<Bitmap?>(
                    initialValue = sharpOrGpuBgBitmap,
                    key1 = sharpOrGpuBgBitmap,
                    key2 = quantizedRadius
                ) {
                    if (quantizedRadius <= 0) {
                        value = sharpOrGpuBgBitmap
                    } else {
                        delay(16)
                        val blurMaxDim = if (quantizedRadius <= 10) 380 else 260
                        value = withContext(Dispatchers.Default) {
                            BitmapBlurUtils.blurBitmap(
                                source = sharpOrGpuBgBitmap,
                                radiusPx = quantizedRadius.toFloat(),
                                maxDimension = blurMaxDim
                            )
                        }
                    }
                }
                val activeBitmap = blurredBitmap ?: sharpOrGpuBgBitmap
                val bgImageBitmap = remember(activeBitmap) { activeBitmap.asImageBitmap() }
                Image(
                    bitmap = bgImageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
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
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 2f) {
                            Modifier.blur(
                                radius = (blurRadius * 0.45f).dp,
                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                            )
                        } else {
                            Modifier
                        }
                    )
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

/**
 * Center Share Card Composable.
 * Supports adaptive sizing ([maxCardWidth], [maxArtSize]) so it never clips on compact
 * phones (Redmi 9C) or tablet landscape viewports (Samsung Galaxy Tab A7 LTE).
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
                modifier = modifier
            )
        }
    }
}

@Composable
private fun FloatingGlassShareCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val cardBackgroundBrush = remember(palette.vibrantColor, palette.dominantColor, palette.darkMutedColor) {
        val accentTint = lerp(palette.dominantColor, palette.vibrantColor, 0.18f)
        val adaptiveTop = lerp(accentTint, Color(0xFF16161C), 0.42f).copy(alpha = 0.87f)
        val adaptiveBottom = lerp(palette.darkMutedColor, Color(0xFF0C0C10), 0.40f).copy(alpha = 0.92f)
        Brush.verticalGradient(colors = listOf(adaptiveTop, adaptiveBottom))
    }

    val shadowElevation = (24 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale).dp
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (32 * scale).dp)
    val innerPadding = (16 * scale).dp

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.84f * scale).coerceIn(0.49f, 0.98f))
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.65f),
                spotColor = Color.Black.copy(alpha = 0.75f)
            )
            .clip(cardShape)
            .background(cardBackgroundBrush)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = cardShape
            )
            .testTag("music_story_card_container")
    ) {
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

            BitChordBranding(
                brandMode = styleConfig.brandMode,
                badgeColor = badgeColor,
                logoColorMode = styleConfig.logoColorMode,
                badgeSize = (20 * scale).dp,
                textSize = (14 * scale).sp,
                textColor = Color.White.copy(alpha = 0.95f),
                showWordmark = styleConfig.showLogoWordmark
            )
        }
    }
}

@Composable
private fun FloatingGlowArtwork(
    track: MediaTrackInfo,
    crispArtImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val glowIntensity = styleConfig.glowIntensity
    val effectiveWidth = minOf(maxCardWidth, maxArtSize + (16 * scale).dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = effectiveWidth)
            .fillMaxWidth((0.84f * scale).coerceIn(0.49f, 0.98f))
            .testTag("music_story_card_container")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = (24 * glowIntensity.coerceAtLeast(0.25f)).dp,
                    shape = RoundedCornerShape(styleConfig.cornerRadiusDp.dp),
                    ambientColor = palette.vibrantColor.copy(alpha = 0.6f),
                    spotColor = palette.vibrantColor.copy(alpha = 0.75f)
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
    crispArtImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    maxArtSize: Dp,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + (28 * scale).dp)

    Column(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth((0.84f * scale).coerceIn(0.49f, 0.98f))
            .shadow(
                elevation = (20 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale).dp,
                shape = cardShape
            )
            .clip(cardShape)
            .background(Color(0xFF141418).copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), cardShape)
            .padding((14 * scale).dp)
            .testTag("music_story_card_container")
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

/**
 * Exact Spotify-style Lyrics Share Card:
 * - Solid vibrant album-matched color block with rounded corners & soft shadow
 * - Header: small square cover art thumbnail + bold track title & artist
 * - Center: large bold editorial lyric lines (manually editable in Edit)
 * - Bottom-left: clean brand badge & wordmark with automatic light/dark ink contrast
 */
@Composable
private fun SpotifyLyricsShareCard(
    track: MediaTrackInfo,
    crispArtImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    palette: CardPalette,
    badgeColor: Color,
    styleConfig: CardStyleConfig,
    cardShape: RoundedCornerShape,
    maxCardWidth: Dp,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val useSongArtBg = styleConfig.lyricsCardBackgroundMode == LyricsCardBackgroundMode.SONG_ART &&
        crispArtImageBitmap != null

    val (cardBgColor, paletteInkColor) = remember(palette.vibrantColor, palette.dominantColor, palette.darkVibrantColor) {
        resolveLyricsCardColors(palette)
    }
    val inkColor = if (useSongArtBg) Color.White else paletteInkColor

    val lyricsText = remember(styleConfig.customLyrics, track.title) {
        styleConfig.resolveLyricsDisplayLines(track)
    }
    val isPlaceholder = styleConfig.customLyrics.trim().isEmpty() && lyricsText.startsWith("Add your favorite lyric lines")

    val shadowElevation = (22 * styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale).dp
    val horizontalPad = (20 * scale).dp
    val verticalPad = (20 * scale).dp
    val thumbSize = (44 * scale).dp

    Box(
        modifier = modifier
            .widthIn(max = maxCardWidth)
            .fillMaxWidth((0.84f * scale).coerceIn(0.49f, 0.98f))
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.65f)
            )
            .clip(cardShape)
            .background(if (useSongArtBg) Color(0xFF141418) else cardBgColor)
            .then(
                if (useSongArtBg) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), cardShape)
                } else {
                    Modifier
                }
            )
            .testTag("music_story_card_container")
    ) {
        if (useSongArtBg && crispArtImageBitmap != null) {
            Image(
                bitmap = crispArtImageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.44f),
                                Color.Black.copy(alpha = 0.56f),
                                Color.Black.copy(alpha = 0.74f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad, vertical = verticalPad)
        ) {
            // Header Row: compact album cover + title & artist
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

            // Main Bold Spotify-style Lyrics Block
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

            // Footer Brand Mark + Wordmark
            val footerBadgeColor = if (styleConfig.logoColorMode == com.example.model.LogoColorMode.TEMPLATE_DYNAMIC) {
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
