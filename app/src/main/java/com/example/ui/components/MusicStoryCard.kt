package com.example.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
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
 * - Pre-scales the background texture to <=240px so GPU RenderEffect (API 31+) and
 *   CPU StackBlur (API 24-30) both run at smooth 60fps with minimal memory overhead.
 */
@Composable
fun BlurredAlbumBackground(
    track: MediaTrackInfo,
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    modifier: Modifier = Modifier
) {
    val blurRadius = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f)
    val quantizedRadius = blurRadius.roundToInt()
    val albumArt = track.albumArt

    // Lightweight pre-scaled bitmap for fast GPU/CPU background blurring
    val lightweightBgBitmap = remember(albumArt) {
        if (albumArt != null) {
            BitmapBlurUtils.toSafeSoftwareBitmap(albumArt, maxDimension = 240)
        } else {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (styleConfig.useBlurredAlbumBackground && lightweightBgBitmap != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bgImageBitmap = remember(lightweightBgBitmap) {
                    lightweightBgBitmap.asImageBitmap()
                }
                Image(
                    bitmap = bgImageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (blurRadius > 0.5f) {
                                // Scale radius proportionally to pre-scaled texture for smooth 60fps GPU blur
                                Modifier.blur(
                                    radius = (blurRadius * 0.7f).dp,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle
                                )
                            } else {
                                Modifier
                            }
                        )
                )
            } else {
                // CPU StackBlur fallback (API 24-30, e.g. Redmi 9C / Android 10-11)
                // Quantized + tiny coalesce delay prevents queuing 40 blurs during slider drag
                val blurredBitmap by produceState<Bitmap?>(
                    initialValue = lightweightBgBitmap,
                    key1 = lightweightBgBitmap,
                    key2 = quantizedRadius
                ) {
                    if (quantizedRadius <= 0) {
                        value = lightweightBgBitmap
                    } else {
                        delay(16)
                        value = withContext(Dispatchers.Default) {
                            BitmapBlurUtils.blurBitmap(
                                source = lightweightBgBitmap,
                                radiusPx = quantizedRadius.toFloat(),
                                maxDimension = 180
                            )
                        }
                    }
                }
                val activeBitmap = blurredBitmap ?: lightweightBgBitmap
                val bgImageBitmap = remember(activeBitmap) { activeBitmap.asImageBitmap() }
                Image(
                    bitmap = bgImageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            val (gradientStart, gradientEnd) = remember(palette, styleConfig.gradientStyle) {
                resolveGradientColors(palette, styleConfig)
            }
            val bgBrush = remember(gradientStart, gradientEnd) {
                Brush.linearGradient(
                    colors = listOf(gradientStart, gradientEnd),
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
    val badgeColor = remember(styleConfig.logoColorMode, styleConfig.customLogoColor, palette) {
        styleConfig.resolveLogoBadgeColor(palette)
    }
    val cardShape = remember(styleConfig.cornerRadiusDp) {
        RoundedCornerShape(styleConfig.cornerRadiusDp.dp)
    }
    val crispArtImageBitmap = remember(track.albumArt) {
        track.albumArt?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = 720)?.asImageBitmap() }
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
                maxCardWidth = maxCardWidth,
                maxArtSize = maxArtSize,
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
                maxCardWidth = maxCardWidth,
                maxArtSize = maxArtSize,
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
                maxCardWidth = maxCardWidth,
                maxArtSize = maxArtSize,
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
    modifier: Modifier = Modifier
) {
    val cardBackgroundBrush = remember(palette.dominantColor, palette.darkMutedColor) {
        val adaptiveTop = lerp(palette.dominantColor, Color(0xFF141418), 0.45f).copy(alpha = 0.86f)
        val adaptiveBottom = lerp(palette.darkMutedColor, Color(0xFF0E0E12), 0.45f).copy(alpha = 0.90f)
        Brush.verticalGradient(colors = listOf(adaptiveTop, adaptiveBottom))
    }

    val shadowElevation = (24 * styleConfig.glowIntensity.coerceAtLeast(0.25f)).dp
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + 32.dp)

    Box(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth(0.88f)
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
                .padding(16.dp)
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

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = track.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.testTag("track_title_text")
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("track_artist_text")
            )

            Spacer(modifier = Modifier.height(16.dp))

            BitChordBranding(
                brandMode = styleConfig.brandMode,
                badgeColor = badgeColor,
                logoColorMode = styleConfig.logoColorMode,
                badgeSize = 20.dp,
                textSize = 14.sp,
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
    modifier: Modifier = Modifier
) {
    val glowIntensity = styleConfig.glowIntensity
    val effectiveWidth = minOf(maxCardWidth, maxArtSize + 16.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = effectiveWidth)
            .fillMaxWidth(0.88f)
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

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = track.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_title_text")
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = track.artist,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_artist_text")
        )

        Spacer(modifier = Modifier.height(18.dp))

        BitChordBranding(
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = 22.dp,
            textSize = 15.sp,
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
    modifier: Modifier = Modifier
) {
    val effectiveCardWidth = minOf(maxCardWidth, maxArtSize + 28.dp)

    Column(
        modifier = modifier
            .widthIn(max = effectiveCardWidth)
            .fillMaxWidth(0.88f)
            .shadow(
                elevation = (20 * styleConfig.glowIntensity.coerceAtLeast(0.25f)).dp,
                shape = cardShape
            )
            .clip(cardShape)
            .background(Color(0xFF141418).copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), cardShape)
            .padding(14.dp)
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

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = track.title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_title_text")
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = track.artist,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("track_artist_text")
        )

        Spacer(modifier = Modifier.height(14.dp))

        BitChordBranding(
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = 18.dp,
            textSize = 13.sp,
            showWordmark = styleConfig.showLogoWordmark
        )
    }
}

fun resolveGradientColors(
    palette: CardPalette,
    styleConfig: CardStyleConfig
): Pair<Color, Color> {
    return when (styleConfig.gradientStyle) {
        GradientStyle.VIBRANT_TO_DARK_MUTED ->
            Pair(palette.vibrantColor, palette.darkMutedColor)
        GradientStyle.DOMINANT_TO_DARK_MUTED ->
            Pair(palette.dominantColor, palette.darkMutedColor)
        GradientStyle.LIGHT_VIBRANT_TO_DARK_VIBRANT ->
            Pair(palette.lightVibrantColor, palette.darkVibrantColor)
        GradientStyle.DEEP_NIGHT ->
            Pair(Color(0xFF191414), Color(0xFF08080A))
        GradientStyle.SUNSET_GLOW ->
            Pair(Color(0xFFFF5E36), Color(0xFF3E0A24))
    }
}
