package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.example.R
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Card Share & Storage Manager.
 * Renders the 9:16 Share Card to a clean, high-resolution 1080x1920 Bitmap
 * (excluding any external blur slider or edit UI):
 * - Full-screen blurred album cover background (0px..40px) + rgba(0, 0, 0, 0.25) overlay
 * - Centered floating 16dp glassmorphic card with soft drop shadow
 * - Crisp, unblurred album art + bold title + muted artist
 * - Selected branding (Bitchord or Spotify) logo + wordmark in the card footer
 */
object CardShareManager {

    suspend fun generateStoryBitmap(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Normalize album art to software ARGB_8888 so Canvas never throws on HARDWARE bitmaps
        val safeAlbumArt = BitmapBlurUtils.toSafeSoftwareBitmap(track.albumArt, maxDimension = 1024)

        // 1. Draw Background: Full-screen blurred album cover (`object-fit: cover`) OR palette gradient
        if (styleConfig.useBlurredAlbumBackground && safeAlbumArt != null) {
            val exportBlurMaxDim = if (styleConfig.backgroundBlurRadius <= 10f) 480 else 360
            val blurredBg = BitmapBlurUtils.blurBitmap(
                source = safeAlbumArt,
                radiusPx = styleConfig.backgroundBlurRadius,
                maxDimension = exportBlurMaxDim
            )
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = blurredBg,
                destRect = RectF(0f, 0f, width.toFloat(), height.toFloat()),
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
            if (blurredBg !== safeAlbumArt && !blurredBg.isRecycled) {
                blurredBg.recycle()
            }
        } else {
            val gradientStops = resolveGradientStopsArgb(palette, styleConfig)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    gradientStops,
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        // Subtle dark overlay rgba(0, 0, 0, 0.25) for readable contrast
        val overlayAlpha = (255f * styleConfig.darkOverlayAlpha.coerceIn(0f, 1f)).roundToInt()
        if (overlayAlpha > 0) {
            canvas.drawColor(AndroidColor.argb(overlayAlpha, 0, 0, 0))
        }

        val safeTrack = if (safeAlbumArt !== track.albumArt) track.copy(albumArt = safeAlbumArt) else track

        // 2. Draw Centered Floating Share Card (without external slider/edit buttons)
        when (styleConfig.cardLayoutType) {
            CardLayoutType.SPOTIFY_INNER_CARD -> {
                drawFloatingGlassCardLayout(context, canvas, width, height, safeTrack, palette, styleConfig)
            }
            CardLayoutType.FLOATING_ARTWORK -> {
                drawFloatingArtworkLayout(context, canvas, width, height, safeTrack, palette, styleConfig)
            }
            CardLayoutType.MINIMAL_CLEAN -> {
                drawMinimalLayout(context, canvas, width, height, safeTrack, palette, styleConfig)
            }
            CardLayoutType.SPOTIFY_LYRICS_CARD -> {
                drawSpotifyLyricsCardLayout(context, canvas, width, height, safeTrack, palette, styleConfig)
            }
        }

        bitmap
    }

    private fun resolveGradientStopsArgb(
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ): IntArray {
        val stops = com.example.ui.components.resolveGradientStops(palette, styleConfig)
        return intArrayOf(
            stops[0].toArgb(),
            stops[1].toArgb(),
            stops[2].toArgb()
        )
    }

    private fun drawFloatingGlassCardLayout(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) {
        val scale = styleConfig.cardScale.coerceIn(0.75f, 1.15f)
        val cardW = w * 0.78f * scale
        val innerPad = 48f * scale
        val artSize = cardW - innerPad * 2f
        val cornerRadius = styleConfig.cornerRadiusDp * 3f * scale

        val textMaxW = artSize.toInt().coerceAtLeast(100)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 54f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        val titleLayout = buildEllipsizedLayout(track.title, titlePaint, textMaxW, maxLines = 2)

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(185, 255, 255, 255)
            textSize = 40f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val artistLayout = buildEllipsizedLayout(track.artist, artistPaint, textMaxW, maxLines = 1)

        val badgeSize = 60f * scale
        val cardH = innerPad + artSize + (42f * scale) + titleLayout.height + (12f * scale) + artistLayout.height + (54f * scale) + badgeSize + innerPad

        val cardLeft = (w - cardW) / 2f
        val cardTop = (h - cardH) / 2f
        val cardRight = cardLeft + cardW
        val cardBottom = cardTop + cardH
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        val shadowStrength = styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb((145 * styleConfig.glowIntensity.coerceAtLeast(0.25f)).roundToInt().coerceIn(0, 255), 0, 0, 0)
            maskFilter = BlurMaskFilter(64f * shadowStrength, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(cardLeft, cardTop + (22f * scale), cardRight, cardBottom + (22f * scale)),
            cornerRadius,
            cornerRadius,
            shadowPaint
        )

        val accentTint = lerpArgb(palette.dominantColor.toArgb(), palette.vibrantColor.toArgb(), 0.18f)
        val topCardColor = withAlpha(lerpArgb(accentTint, 0xFF16161C.toInt(), 0.42f), 0.87f)
        val bottomCardColor = withAlpha(lerpArgb(palette.darkMutedColor.toArgb(), 0xFF0C0C10.toInt(), 0.40f), 0.92f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardLeft, cardTop, cardLeft, cardBottom,
                topCardColor, bottomCardColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = AndroidColor.argb(38, 255, 255, 255)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        val artLeft = cardLeft + innerPad
        val artTop = cardTop + innerPad
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val artCorner = 30f * scale

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        val albumArt = track.albumArt
        if (albumArt != null) {
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = albumArt,
                destRect = artRect,
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(palette.vibrantColor.toArgb(), 0.45f)
            }
            canvas.drawRect(artRect, placeholderPaint)
        }
        canvas.restore()

        var cursorY = artTop + artSize + (42f * scale)
        canvas.save()
        canvas.translate(artLeft, cursorY)
        titleLayout.draw(canvas)
        canvas.restore()

        cursorY += titleLayout.height + (12f * scale)
        canvas.save()
        canvas.translate(artLeft, cursorY)
        artistLayout.draw(canvas)
        canvas.restore()

        val footerTop = cursorY + artistLayout.height + (54f * scale)
        val badgeColor = styleConfig.resolveLogoBadgeColor(palette).toArgb()
        drawBrandFooter(
            context = context,
            canvas = canvas,
            leftX = artLeft,
            topY = footerTop,
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = badgeSize,
            textSize = 42f * scale,
            showWordmark = styleConfig.showLogoWordmark
        )
    }

    private fun drawFloatingArtworkLayout(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) {
        val scale = styleConfig.cardScale.coerceIn(0.75f, 1.15f)
        val artSize = (w * 0.72f * scale)
        val artLeft = (w - artSize) / 2f
        val artTop = (h - artSize) * 0.36f
        val artRight = artLeft + artSize
        val artBottom = artTop + artSize
        val artRadius = styleConfig.cornerRadiusDp * 3f * scale

        if (styleConfig.glowIntensity > 0.05f) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.vibrantColor.toArgb()
                alpha = (180 * styleConfig.glowIntensity).toInt().coerceIn(0, 255)
                maskFilter = BlurMaskFilter(80f * styleConfig.glowIntensity * scale, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(
                RectF(artLeft - 20f, artTop - 20f, artRight + 20f, artBottom + 20f),
                artRadius + 12f, artRadius + 12f, glowPaint
            )
        }

        val artRect = RectF(artLeft, artTop, artRight, artBottom)
        canvas.save()
        val clipPath = Path()
        clipPath.addRoundRect(artRect, artRadius, artRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        val albumArt = track.albumArt
        if (albumArt != null) {
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = albumArt,
                destRect = artRect,
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.vibrantColor.toArgb() }
            canvas.drawRect(artRect, p)
        }
        canvas.restore()

        var textY = artBottom + (96f * scale)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 58f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(track.title.take(30), w / 2f, textY, titlePaint)

        textY += 60f * scale
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(200, 255, 255, 255)
            textSize = 42f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(track.artist.take(36), w / 2f, textY, artistPaint)

        val badgeColor = styleConfig.resolveLogoBadgeColor(palette).toArgb()
        drawBrandFooter(
            context = context,
            canvas = canvas,
            leftX = (w / 2f) - (135f * scale),
            topY = textY + (72f * scale),
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = 62f * scale,
            textSize = 44f * scale,
            showWordmark = styleConfig.showLogoWordmark
        )
    }

    private fun drawMinimalLayout(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) {
        val scale = styleConfig.cardScale.coerceIn(0.75f, 1.15f)
        val cardW = w * 0.78f * scale
        val innerPad = 42f * scale
        val artSize = cardW - innerPad * 2f
        val cornerRadius = styleConfig.cornerRadiusDp * 3f * scale
        val cardH = innerPad + artSize + (290f * scale)

        val cardLeft = (w - cardW) / 2f
        val cardTop = (h - cardH) / 2f
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(224, 20, 20, 24)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

        val artLeft = cardLeft + innerPad
        val artTop = cardTop + innerPad
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        canvas.save()
        val clipPath = Path()
        clipPath.addRoundRect(artRect, 28f * scale, 28f * scale, Path.Direction.CW)
        canvas.clipPath(clipPath)
        val albumArt = track.albumArt
        if (albumArt != null) {
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = albumArt,
                destRect = artRect,
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.vibrantColor.toArgb() }
            canvas.drawRect(artRect, p)
        }
        canvas.restore()

        var textY = artTop + artSize + (72f * scale)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 52f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(track.title.take(30), artLeft, textY, titlePaint)

        textY += 52f * scale
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(185, 255, 255, 255)
            textSize = 38f * scale
        }
        canvas.drawText(track.artist.take(36), artLeft, textY, artistPaint)

        val badgeColor = styleConfig.resolveLogoBadgeColor(palette).toArgb()
        drawBrandFooter(
            context = context,
            canvas = canvas,
            leftX = artLeft,
            topY = textY + (42f * scale),
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = 54f * scale,
            textSize = 38f * scale,
            showWordmark = styleConfig.showLogoWordmark
        )
    }

    private fun drawSpotifyLyricsCardLayout(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) {
        val scale = styleConfig.cardScale.coerceIn(0.75f, 1.15f)
        val useSongArtBg = styleConfig.lyricsCardBackgroundMode == LyricsCardBackgroundMode.SONG_ART &&
            track.albumArt != null
        val (cardColor, paletteInkColor) = com.example.ui.components.resolveLyricsCardColors(palette)
        val cardArgb = cardColor.toArgb()
        val inkArgb = if (useSongArtBg) AndroidColor.WHITE else paletteInkColor.toArgb()
        val lyricsText = styleConfig.resolveLyricsDisplayLines(track)
        val isPlaceholder = styleConfig.customLyrics.trim().isEmpty() &&
            lyricsText.startsWith("Add your favorite lyric lines")

        val cardW = w * 0.80f * scale
        val padH = 56f * scale
        val padV = 56f * scale
        val thumbSize = 132f * scale
        val thumbCorner = 14f * scale
        val cornerRadius = styleConfig.cornerRadiusDp * 3f * scale

        val headerTextLeft = padH + thumbSize + (34f * scale)
        val headerTextMaxW = (cardW - headerTextLeft - padH).toInt().coerceAtLeast(100)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkArgb
            textSize = 42f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        val titleLayout = buildEllipsizedLayout(track.title, titlePaint, headerTextMaxW, maxLines = 1)

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(inkArgb, 0.78f)
            textSize = 35f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val artistLayout = buildEllipsizedLayout(track.artist, artistPaint, headerTextMaxW, maxLines = 1)

        val headerHeight = max(thumbSize, (titleLayout.height + 6f * scale + artistLayout.height))

        val lyricsMaxW = (cardW - padH * 2f).toInt().coerceAtLeast(140)
        val lyricsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isPlaceholder) withAlpha(inkArgb, 0.72f) else inkArgb
            textSize = 60f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.025f
        }
        val lyricsLayout = buildEllipsizedLayout(
            text = lyricsText,
            paint = lyricsPaint,
            width = lyricsMaxW,
            maxLines = 8,
            spacingMult = 1.22f
        )

        val badgeSize = 56f * scale
        val gapHeaderLyrics = 62f * scale
        val gapLyricsFooter = 66f * scale
        val cardH = padV + headerHeight + gapHeaderLyrics + lyricsLayout.height + gapLyricsFooter + badgeSize + padV

        val cardLeft = (w - cardW) / 2f
        val cardTop = (h - cardH) / 2f
        val cardRight = cardLeft + cardW
        val cardBottom = cardTop + cardH
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        val shadowStrength = styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb((135 * styleConfig.glowIntensity.coerceAtLeast(0.25f)).roundToInt().coerceIn(0, 255), 0, 0, 0)
            maskFilter = BlurMaskFilter(58f * shadowStrength, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(cardLeft, cardTop + (20f * scale), cardRight, cardBottom + (20f * scale)),
            cornerRadius,
            cornerRadius,
            shadowPaint
        )

        val albumArt = track.albumArt
        if (useSongArtBg && albumArt != null) {
            canvas.save()
            val cardClip = Path().apply {
                addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.clipPath(cardClip)
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = albumArt,
                destRect = cardRect,
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    cardLeft, cardTop, cardLeft, cardBottom,
                    intArrayOf(
                        AndroidColor.argb(112, 0, 0, 0),
                        AndroidColor.argb(143, 0, 0, 0),
                        AndroidColor.argb(188, 0, 0, 0)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(cardRect, scrimPaint)
            canvas.restore()

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = AndroidColor.argb(42, 255, 255, 255)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)
        } else {
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cardArgb
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)
        }

        // Header cover thumbnail
        val thumbLeft = cardLeft + padH
        val thumbTop = cardTop + padV
        val thumbRect = RectF(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize)
        canvas.save()
        val thumbPath = Path().apply {
            addRoundRect(thumbRect, thumbCorner, thumbCorner, Path.Direction.CW)
        }
        canvas.clipPath(thumbPath)
        if (albumArt != null) {
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = albumArt,
                destRect = thumbRect,
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(inkArgb, 0.22f)
            }
            canvas.drawRect(thumbRect, placeholderPaint)
        }
        canvas.restore()

        // Header title & artist vertically centered next to cover thumbnail
        val metaBlockH = titleLayout.height + 6f * scale + artistLayout.height
        val metaTop = thumbTop + ((thumbSize - metaBlockH) / 2f).coerceAtLeast(0f)
        val metaLeft = cardLeft + headerTextLeft
        canvas.save()
        canvas.translate(metaLeft, metaTop)
        titleLayout.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(metaLeft, metaTop + titleLayout.height + 6f * scale)
        artistLayout.draw(canvas)
        canvas.restore()

        // Main bold editorial lyrics block
        val lyricsTop = thumbTop + headerHeight + gapHeaderLyrics
        canvas.save()
        canvas.translate(thumbLeft, lyricsTop)
        lyricsLayout.draw(canvas)
        canvas.restore()

        // Footer brand badge + wordmark
        val footerTop = lyricsTop + lyricsLayout.height + gapLyricsFooter
        val badgeColor = if (styleConfig.logoColorMode == LogoColorMode.TEMPLATE_DYNAMIC) {
            inkArgb
        } else {
            styleConfig.resolveLogoBadgeColor(palette).toArgb()
        }
        drawBrandFooter(
            context = context,
            canvas = canvas,
            leftX = thumbLeft,
            topY = footerTop,
            brandMode = styleConfig.brandMode,
            badgeColor = badgeColor,
            logoColorMode = styleConfig.logoColorMode,
            badgeSize = badgeSize,
            textSize = 40f * scale,
            showWordmark = styleConfig.showLogoWordmark,
            textColor = withAlpha(inkArgb, 0.95f)
        )
    }

    private fun drawBrandFooter(
        context: Context,
        canvas: Canvas,
        leftX: Float,
        topY: Float,
        brandMode: CardBrandMode,
        badgeColor: Int,
        logoColorMode: LogoColorMode,
        badgeSize: Float,
        textSize: Float,
        showWordmark: Boolean,
        textColor: Int = AndroidColor.WHITE
    ) {
        val badgeRect = RectF(leftX, topY, leftX + badgeSize, topY + badgeSize)

        when (brandMode) {
            CardBrandMode.BITCHORD -> {
                if (logoColorMode == LogoColorMode.RED) {
                    val badgeBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_bitchord_badge)
                    if (badgeBitmap != null) {
                        canvas.drawBitmap(badgeBitmap, null, badgeRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        if (!badgeBitmap.isRecycled) badgeBitmap.recycle()
                    }
                } else {
                    val squircleBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_bitchord_squircle)
                    val wavesBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_bitchord_waves)
                    if (squircleBitmap != null) {
                        val squirclePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = PorterDuffColorFilter(badgeColor, PorterDuff.Mode.SRC_IN)
                        }
                        canvas.drawBitmap(squircleBitmap, null, badgeRect, squirclePaint)
                        if (!squircleBitmap.isRecycled) squircleBitmap.recycle()
                    }
                    if (wavesBitmap != null) {
                        val waveTint = if (colorLuminance(badgeColor) > 0.72f) 0xFF141418.toInt() else AndroidColor.WHITE
                        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = PorterDuffColorFilter(waveTint, PorterDuff.Mode.SRC_IN)
                        }
                        canvas.drawBitmap(wavesBitmap, null, badgeRect, wavePaint)
                        if (!wavesBitmap.isRecycled) wavesBitmap.recycle()
                    }
                }
            }
            CardBrandMode.SPOTIFY -> {
                val radius = badgeSize / 2f
                val cx = badgeRect.centerX()
                val cy = badgeRect.centerY()
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = badgeColor
                }
                canvas.drawCircle(cx, cy, radius, circlePaint)

                val waveColor = if (colorLuminance(badgeColor) > 0.35f) 0xFF121216.toInt() else AndroidColor.WHITE
                canvas.save()
                canvas.rotate(7f, cx, cy)

                val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = waveColor
                    style = Paint.Style.STROKE
                    strokeWidth = badgeSize * 0.105f
                    strokeCap = Paint.Cap.ROUND
                }
                val topPath = Path().apply {
                    moveTo(cx - radius * 0.56f, cy - radius * 0.22f)
                    quadTo(cx + radius * 0.02f, cy - radius * 0.44f, cx + radius * 0.56f, cy - radius * 0.16f)
                }
                canvas.drawPath(topPath, topPaint)

                val midPaint = Paint(topPaint).apply { strokeWidth = badgeSize * 0.088f }
                val midPath = Path().apply {
                    moveTo(cx - radius * 0.48f, cy + radius * 0.04f)
                    quadTo(cx + radius * 0.01f, cy - radius * 0.14f, cx + radius * 0.46f, cy + radius * 0.09f)
                }
                canvas.drawPath(midPath, midPaint)

                val botPaint = Paint(topPaint).apply { strokeWidth = badgeSize * 0.075f }
                val botPath = Path().apply {
                    moveTo(cx - radius * 0.39f, cy + radius * 0.28f)
                    quadTo(cx, cy + radius * 0.14f, cx + radius * 0.36f, cy + radius * 0.32f)
                }
                canvas.drawPath(botPath, botPaint)

                canvas.restore()
            }
            CardBrandMode.APPLE_MUSIC -> {
                val corner = badgeSize * 0.24f
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = badgeColor
                }
                canvas.drawRoundRect(badgeRect, corner, corner, bgPaint)

                val contrast = if (colorLuminance(badgeColor) > 0.72f) 0xFF141418.toInt() else AndroidColor.WHITE
                val left = badgeRect.left
                val top = badgeRect.top
                val stemStroke = badgeSize * 0.09f
                val leftStemX = left + badgeSize * 0.39f
                val rightStemX = left + badgeSize * 0.69f
                val topYLeft = top + badgeSize * 0.29f
                val topYRight = top + badgeSize * 0.23f
                val botYLeft = top + badgeSize * 0.67f
                val botYRight = top + badgeSize * 0.61f

                val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = contrast
                    style = Paint.Style.FILL
                }
                val beamPath = Path().apply {
                    moveTo(leftStemX - stemStroke * 0.45f, topYLeft)
                    lineTo(rightStemX + stemStroke * 0.45f, topYRight)
                    lineTo(rightStemX + stemStroke * 0.45f, topYRight + badgeSize * 0.12f)
                    lineTo(leftStemX - stemStroke * 0.45f, topYLeft + badgeSize * 0.12f)
                    close()
                }
                canvas.drawPath(beamPath, notePaint)

                val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = contrast
                    style = Paint.Style.STROKE
                    strokeWidth = stemStroke
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(leftStemX, topYLeft + badgeSize * 0.04f, leftStemX, botYLeft, stemPaint)
                canvas.drawLine(rightStemX, topYRight + badgeSize * 0.04f, rightStemX, botYRight, stemPaint)

                val headRx = badgeSize * 0.115f
                val headRy = badgeSize * 0.085f
                canvas.drawOval(
                    RectF(
                        leftStemX - headRx * 1.55f,
                        botYLeft - headRy * 0.9f,
                        leftStemX + headRx * 0.45f,
                        botYLeft + headRy * 1.1f
                    ),
                    notePaint
                )
                canvas.drawOval(
                    RectF(
                        rightStemX - headRx * 1.55f,
                        botYRight - headRy * 0.9f,
                        rightStemX + headRx * 0.45f,
                        botYRight + headRy * 1.1f
                    ),
                    notePaint
                )
            }
        }

        if (showWordmark) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                this.textSize = textSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = -0.02f
            }
            val fm = textPaint.fontMetrics
            val baselineY = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(brandMode.wordmark, badgeRect.right + 22f, baselineY, textPaint)
        }
    }

    private fun colorLuminance(color: Int): Float {
        return (0.299f * AndroidColor.red(color) +
            0.587f * AndroidColor.green(color) +
            0.114f * AndroidColor.blue(color)) / 255f
    }

    private fun drawCenterCroppedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        destRect: RectF,
        paint: Paint
    ) {
        val srcW = bitmap.width.toFloat().coerceAtLeast(1f)
        val srcH = bitmap.height.toFloat().coerceAtLeast(1f)
        val dstW = destRect.width().coerceAtLeast(1f)
        val dstH = destRect.height().coerceAtLeast(1f)

        val scale = max(dstW / srcW, dstH / srcH)
        val cropW = dstW / scale
        val cropH = dstH / scale
        val left = ((srcW - cropW) / 2f).roundToInt().coerceAtLeast(0)
        val top = ((srcH - cropH) / 2f).roundToInt().coerceAtLeast(0)
        val right = (left + cropW.roundToInt()).coerceAtMost(bitmap.width)
        val bottom = (top + cropH.roundToInt()).coerceAtMost(bitmap.height)

        val srcRect = Rect(left, top, right, bottom)
        canvas.drawBitmap(bitmap, srcRect, destRect, paint)
    }

    @Suppress("DEPRECATION")
    private fun buildEllipsizedLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        spacingMult: Float = 1.0f
    ): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacingMult)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, spacingMult, 0.0f, false)
        }
    }

    private fun lerpArgb(colorA: Int, colorB: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (AndroidColor.alpha(colorA) + (AndroidColor.alpha(colorB) - AndroidColor.alpha(colorA)) * f).roundToInt()
        val r = (AndroidColor.red(colorA) + (AndroidColor.red(colorB) - AndroidColor.red(colorA)) * f).roundToInt()
        val g = (AndroidColor.green(colorA) + (AndroidColor.green(colorB) - AndroidColor.green(colorA)) * f).roundToInt()
        val b = (AndroidColor.blue(colorA) + (AndroidColor.blue(colorB) - AndroidColor.blue(colorA)) * f).roundToInt()
        return AndroidColor.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (255f * alphaFraction.coerceIn(0f, 1f)).roundToInt()
        return AndroidColor.argb(
            alpha,
            AndroidColor.red(color),
            AndroidColor.green(color),
            AndroidColor.blue(color)
        )
    }

    suspend fun saveCardToGallery(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ): Boolean = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val safeTitle = track.title.replace("[^A-Za-z0-9_-]".toRegex(), "_").take(32)
            val fileName = "${styleConfig.brandMode.wordmark}_${safeTitle}_${System.currentTimeMillis()}.png"

            val contentResolver = context.contentResolver
            val imageUri: Uri?

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BitChord")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    contentResolver.openOutputStream(imageUri).use { outStream ->
                        if (outStream != null) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(imageUri, contentValues, null, null)
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val bitChordDir = File(picturesDir, "BitChord").apply { mkdirs() }
                val file = File(bitChordDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Gallery (Pictures/BitChord)!", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        } finally {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun copyTrackLink(
        context: Context,
        track: MediaTrackInfo,
        brandMode: CardBrandMode = CardBrandMode.BITCHORD
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val shareText = "🎵 ${track.title} - ${track.artist}\nShared with ${brandMode.wordmark}"
        val clip = ClipData.newPlainText("${brandMode.wordmark} Track", shareText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Track copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    suspend fun shareToWhatsApp(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) = withContext(Dispatchers.IO) {
        try {
            val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val uri = saveBitmapToCache(context, bitmap, "bitchord_story_${System.currentTimeMillis()}.png")
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                    clipData = ClipData.newRawUri("Share Card", uri)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                        clipData = ClipData.newRawUri("Share Card", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(fallback, "Share to WhatsApp via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Unable to share: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun shareToInstagramStory(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) = withContext(Dispatchers.IO) {
        try {
            val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val uri = saveBitmapToCache(context, bitmap, "bitchord_ig_story_${System.currentTimeMillis()}.png")

            withContext(Dispatchers.Main) {
                val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                    setDataAndType(uri, "image/png")
                    putExtra("interactive_asset_uri", uri)
                    putExtra("top_background_color", String.format("#%06X", (0xFFFFFF and palette.vibrantColor.toArgb())))
                    putExtra("bottom_background_color", String.format("#%06X", (0xFFFFFF and palette.darkMutedColor.toArgb())))
                    clipData = ClipData.newRawUri("Instagram Story", uri)
                    setPackage("com.instagram.android")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(storyIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Instagram app not found for Stories", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Unable to share Instagram Story: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun shareToSnapchatStory(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) = withContext(Dispatchers.IO) {
        try {
            val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val uri = saveBitmapToCache(context, bitmap, "bitchord_snap_story_${System.currentTimeMillis()}.png")
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val snapIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                    clipData = ClipData.newRawUri("Snapchat Story", uri)
                    setPackage("com.snapchat.android")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.grantUriPermission("com.snapchat.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(snapIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Snapchat app not found for Stories", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Unable to share Snapchat Story: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun shareToDiscord(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) = withContext(Dispatchers.IO) {
        try {
            val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val uri = saveBitmapToCache(context, bitmap, "bitchord_discord_${System.currentTimeMillis()}.png")
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val discordIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                    clipData = ClipData.newRawUri("Discord Share Card", uri)
                    setPackage("com.discord")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.grantUriPermission("com.discord", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(discordIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Discord app not found — opening share menu", Toast.LENGTH_SHORT).show()
                    val fallback = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                        clipData = ClipData.newRawUri("Share Card", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(fallback, "Share to Discord via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Unable to share to Discord: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun shareStoryCard(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) = withContext(Dispatchers.IO) {
        try {
            val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
            val uri = saveBitmapToCache(context, bitmap, "bitchord_story_${System.currentTimeMillis()}.png")
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Music Story: ${track.title} by ${track.artist}")
                    putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                    clipData = ClipData.newRawUri("Share Card", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Music Story via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error sharing story card: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val cacheDir = File(context.cacheDir, "cards")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val file = File(cacheDir, fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
