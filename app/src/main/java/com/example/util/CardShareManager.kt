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
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val canvas = Canvas(bitmap)

        // Normalize album art to software ARGB_8888 so Canvas never throws on HARDWARE bitmaps
        val safeAlbumArt = BitmapBlurUtils.toSafeSoftwareBitmap(track.albumArt, maxDimension = 1200)

        try {
            // 1. Draw Background unless "No Background (Transparent)" is selected
            if (!styleConfig.useTransparentBackground) {
                drawBackgroundOnly(
                    canvas = canvas,
                    targetWidth = width,
                    targetHeight = height,
                    albumArt = safeAlbumArt,
                    palette = palette,
                    styleConfig = styleConfig
                )
            }

            // Subtle dark overlay rgba(0, 0, 0, 0.25) (omitted when exporting pure transparent card)
            val safeTrack = if (safeAlbumArt !== track.albumArt) track.copy(albumArt = safeAlbumArt) else track
            drawOverlayAndCardOnCanvas(
                context = context,
                canvas = canvas,
                targetWidth = width,
                targetHeight = height,
                track = safeTrack,
                palette = palette,
                styleConfig = styleConfig,
                skipScrim = styleConfig.useTransparentBackground,
                includeWaveform = true
            )
        } finally {
            if (safeAlbumArt != null && safeAlbumArt !== track.albumArt && !safeAlbumArt.isRecycled) {
                safeAlbumArt.recycle()
            }
        }

        bitmap
    }

    suspend fun generateStaticBackgroundBitmap(
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        targetWidth: Int = CanvasVideoExporter.OUT_WIDTH,
        targetHeight: Int = CanvasVideoExporter.OUT_HEIGHT
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val safeAlbumArt = BitmapBlurUtils.toSafeSoftwareBitmap(track.albumArt, maxDimension = 1024)
        try {
            drawBackgroundOnly(
                canvas = canvas,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                albumArt = safeAlbumArt,
                palette = palette,
                styleConfig = styleConfig
            )
        } finally {
            if (safeAlbumArt != null && safeAlbumArt !== track.albumArt && !safeAlbumArt.isRecycled) {
                safeAlbumArt.recycle()
            }
        }
        bitmap
    }

    private fun drawBackgroundOnly(
        canvas: Canvas,
        targetWidth: Int,
        targetHeight: Int,
        albumArt: Bitmap?,
        palette: CardPalette,
        styleConfig: CardStyleConfig
    ) {
        if (styleConfig.useBlurredAlbumBackground && albumArt != null) {
            val exportBlurMaxDim = if (styleConfig.backgroundBlurRadius <= 10f) 480 else 360
            val blurredBg = BitmapBlurUtils.blurBitmap(
                source = albumArt,
                radiusPx = styleConfig.backgroundBlurRadius,
                maxDimension = exportBlurMaxDim
            )
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = blurredBg,
                destRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
            if (blurredBg !== albumArt && !blurredBg.isRecycled) {
                blurredBg.recycle()
            }
        } else {
            val gradientStops = resolveGradientStopsArgb(palette, styleConfig)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(),
                    gradientStops,
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), bgPaint)
        }
    }

    /**
     * Renders ONLY the contrast scrim and centered floating Share Card onto a transparent bitmap.
     * Used both by the animated 9:16 live Preview dialog and by CanvasVideoExporter as the MP4 overlay layer.
     */
    suspend fun generateTransparentCardOverlayBitmap(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        includeWaveform: Boolean = true
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val safeAlbumArt = BitmapBlurUtils.toSafeSoftwareBitmap(track.albumArt, maxDimension = 1024)
        try {
            val safeTrack = if (safeAlbumArt !== track.albumArt) track.copy(albumArt = safeAlbumArt) else track

            drawOverlayAndCardOnCanvas(
                context = context,
                canvas = canvas,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                track = safeTrack,
                palette = palette,
                styleConfig = styleConfig,
                skipScrim = styleConfig.useTransparentBackground,
                includeWaveform = includeWaveform
            )
        } finally {
            if (safeAlbumArt != null && safeAlbumArt !== track.albumArt && !safeAlbumArt.isRecycled) {
                safeAlbumArt.recycle()
            }
        }
        bitmap
    }

    private fun drawOverlayAndCardOnCanvas(
        context: Context,
        canvas: Canvas,
        targetWidth: Int,
        targetHeight: Int,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        skipScrim: Boolean = false,
        includeWaveform: Boolean = true
    ) {
        val width = 1080
        val height = 1920
        val saved = canvas.save()
        if (targetWidth != width || targetHeight != height) {
            canvas.scale(targetWidth / width.toFloat(), targetHeight / height.toFloat())
        }

        if (!skipScrim) {
            val overlayAlpha = (255f * styleConfig.darkOverlayAlpha.coerceIn(0f, 1f)).roundToInt()
            if (overlayAlpha > 0) {
                canvas.drawColor(AndroidColor.argb(overlayAlpha, 0, 0, 0))
            }
        }

        when (styleConfig.cardLayoutType) {
            CardLayoutType.SPOTIFY_INNER_CARD -> {
                drawFloatingGlassCardLayout(
                    context = context,
                    canvas = canvas,
                    w = width,
                    h = height,
                    track = track,
                    palette = palette,
                    styleConfig = styleConfig,
                    includeWaveform = includeWaveform
                )
            }
            CardLayoutType.FLOATING_ARTWORK -> {
                drawFloatingArtworkLayout(context, canvas, width, height, track, palette, styleConfig)
            }
            CardLayoutType.MINIMAL_CLEAN -> {
                drawMinimalLayout(context, canvas, width, height, track, palette, styleConfig)
            }
            CardLayoutType.SPOTIFY_LYRICS_CARD -> {
                drawSpotifyLyricsCardLayout(context, canvas, width, height, track, palette, styleConfig)
            }
        }
        canvas.restoreToCount(saved)
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
        styleConfig: CardStyleConfig,
        includeWaveform: Boolean = true
    ) {
        val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
        val cardW = (w * 0.78f * scale).coerceIn(w * 0.44f, w * 0.94f)
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

        val glassOpacity = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f)
        val shadowStrength = styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale
        val shadowAlpha = (145f * styleConfig.glowIntensity.coerceAtLeast(0.25f) * glassOpacity)
            .roundToInt()
            .coerceIn(0, 255)
        if (shadowAlpha > 2) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(shadowAlpha, 0, 0, 0)
                maskFilter = BlurMaskFilter(64f * shadowStrength, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(
                RectF(cardLeft, cardTop + (22f * scale), cardRight, cardBottom + (22f * scale)),
                cornerRadius,
                cornerRadius,
                shadowPaint
            )
        }

        val albumArt = track.albumArt
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
        val borderAlphaInt = ((if (glassBlur <= 20f) {
            0.30f - 0.16f * glassDownFactor
        } else {
            0.14f + 0.24f * glassUpFactor
        }) * glassOpacity * 255f).roundToInt().coerceIn(0, 255)
        val sideFrostColor = lerpArgb(AndroidColor.WHITE, palette.lightVibrantColor.toArgb(), 0.32f)

        if (glassUpFactor > 0.01f && glassOpacity > 0.01f) {
            val spreadPx = 12f * glassUpFactor
            val outerHaloRect = RectF(
                cardLeft - spreadPx,
                cardTop - spreadPx,
                cardRight + spreadPx,
                cardBottom + spreadPx
            )
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (15f + 27f * glassUpFactor) * scale
                color = withAlpha(sideFrostColor, 0.36f * glassUpFactor * glassOpacity)
                maskFilter = BlurMaskFilter((30f + 72f * glassUpFactor) * scale, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(outerHaloRect, cornerRadius, cornerRadius, haloPaint)
        }

        val accentTint = lerpArgb(palette.dominantColor.toArgb(), palette.vibrantColor.toArgb(), 0.18f)
        val topCardColor = withAlpha(lerpArgb(accentTint, 0xFF16161C.toInt(), 0.42f), topAlpha)
        val bottomCardColor = withAlpha(lerpArgb(palette.darkMutedColor.toArgb(), 0xFF0C0C10.toInt(), 0.40f), bottomAlpha)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardLeft, cardTop, cardLeft, cardBottom,
                topCardColor, bottomCardColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

        if (glassUpFactor > 0.01f && glassOpacity > 0.01f) {
            drawFrostedGlassOverlay(
                canvas = canvas,
                cardRect = cardRect,
                cornerRadius = cornerRadius,
                albumArt = albumArt,
                blurRadius = 26f + (14f * glassUpFactor),
                intensity = glassUpFactor * glassOpacity,
                sideFrostColor = sideFrostColor
            )
        }

        if (borderAlphaInt > 0) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f + (3.6f * glassUpFactor)
                color = AndroidColor.argb(borderAlphaInt, 255, 255, 255)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)
        }

        val artLeft = cardLeft + innerPad
        val artTop = cardTop + innerPad
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val artCorner = 30f * scale

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
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

        if (includeWaveform && styleConfig.enableWaveform) {
            val wfRect = computeFloatingGlassWaveformRectIn1080x1920(track, styleConfig)
            drawDynamicIslandWaveform(
                canvas = canvas,
                rect = wfRect,
                colorArgb = styleConfig.waveformColor.toArgb(),
                track = track,
                timeSec = 1.4f,
                speedMultiplier = styleConfig.waveformSpeedMultiplier,
                beatSyncMode = styleConfig.waveformBeatSync
            )
        }
    }

    private fun computeFloatingGlassWaveformRectIn1080x1920(
        track: MediaTrackInfo,
        styleConfig: CardStyleConfig
    ): RectF {
        val w = 1080
        val h = 1920
        val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
        val cardW = (w * 0.78f * scale).coerceIn(w * 0.44f, w * 0.94f)
        val innerPad = 48f * scale
        val artSize = cardW - innerPad * 2f
        val textMaxW = artSize.toInt().coerceAtLeast(100)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 54f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        val titleLayout = buildEllipsizedLayout(track.title, titlePaint, textMaxW, maxLines = 2)
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val artistLayout = buildEllipsizedLayout(track.artist, artistPaint, textMaxW, maxLines = 1)
        val badgeSize = 60f * scale
        val cardH = innerPad + artSize + (42f * scale) + titleLayout.height + (12f * scale) + artistLayout.height + (54f * scale) + badgeSize + innerPad

        val cardLeft = (w - cardW) / 2f
        val cardRight = cardLeft + cardW
        val cardTop = (h - cardH) / 2f
        val footerTop = cardTop + innerPad + artSize + (42f * scale) + titleLayout.height + (12f * scale) + artistLayout.height + (54f * scale)

        val wfWidth = 78f * scale
        val wfHeight = 56f * scale
        val wfRight = cardRight - innerPad
        val wfTop = footerTop + (badgeSize - wfHeight) / 2f
        return RectF(wfRight - wfWidth, wfTop, wfRight, wfTop + wfHeight)
    }

    fun computeFloatingGlassWaveformRect(
        targetWidth: Int,
        targetHeight: Int,
        track: MediaTrackInfo,
        styleConfig: CardStyleConfig
    ): RectF {
        val base = computeFloatingGlassWaveformRectIn1080x1920(track, styleConfig)
        val sx = targetWidth / 1080f
        val sy = targetHeight / 1920f
        return RectF(base.left * sx, base.top * sy, base.right * sx, base.bottom * sy)
    }

    private fun drawDynamicIslandWaveform(
        canvas: Canvas,
        rect: RectF,
        colorArgb: Int,
        track: MediaTrackInfo,
        timeSec: Float,
        speedMultiplier: Float = 1.0f,
        beatSyncMode: Boolean = false
    ) {
        val levels = AudioBeatMonitor.computeBarLevels(
            trackTitle = track.title,
            trackArtist = track.artist,
            isPlaying = true,
            timeSec = timeSec,
            speedMultiplier = speedMultiplier,
            beatSyncMode = beatSyncMode,
            animateWhenPaused = true
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            style = Paint.Style.FILL
        }
        val padX = rect.width() * 0.08f
        val usableW = (rect.width() - padX * 2f).coerceAtLeast(8f)
        val gap = usableW * 0.14f
        val barW = ((usableW - gap * 3f) / 4f).coerceAtLeast(2f)
        val corner = barW / 2f
        val maxBarH = rect.height() * 0.90f
        val cy = rect.centerY()

        for (i in 0 until 4) {
            val frac = levels[i].coerceIn(0.18f, 1f)
            val barH = max(barW, maxBarH * frac)
            val left = rect.left + padX + i * (barW + gap)
            val top = cy - barH / 2f
            canvas.drawRoundRect(
                RectF(left, top, left + barW, top + barH),
                corner,
                corner,
                paint
            )
        }
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
        val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
        val artSize = (w * 0.72f * scale).coerceIn(w * 0.42f, w * 0.90f)
        val artLeft = (w - artSize) / 2f
        val artTop = (h - artSize) * 0.36f
        val artRight = artLeft + artSize
        val artBottom = artTop + artSize
        val artRadius = styleConfig.cornerRadiusDp * 3f * scale

        val glassOpacity = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f)
        if (styleConfig.glowIntensity > 0.05f && glassOpacity > 0.02f) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.vibrantColor.toArgb()
                alpha = (180 * styleConfig.glowIntensity * glassOpacity).toInt().coerceIn(0, 255)
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
        val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
        val cardW = (w * 0.78f * scale).coerceIn(w * 0.44f, w * 0.94f)
        val innerPad = 42f * scale
        val artSize = cardW - innerPad * 2f
        val cornerRadius = styleConfig.cornerRadiusDp * 3f * scale
        val cardH = innerPad + artSize + (290f * scale)

        val cardLeft = (w - cardW) / 2f
        val cardTop = (h - cardH) / 2f
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)

        val glassOpacity = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f)
        val bgAlpha = (224f * glassOpacity).roundToInt().coerceIn(0, 255)
        if (bgAlpha > 0) {
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(bgAlpha, 20, 20, 24)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)
        }

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
        val scale = styleConfig.cardScale.coerceIn(0.60f, 1.20f)
        val useSongArtBg = styleConfig.lyricsCardBackgroundMode == LyricsCardBackgroundMode.SONG_ART &&
            track.albumArt != null
        val (cardColor, paletteInkColor) = com.example.ui.components.resolveLyricsCardColors(palette)
        val cardArgb = cardColor.toArgb()
        val inkArgb = if (useSongArtBg) AndroidColor.WHITE else paletteInkColor.toArgb()
        val lyricsText = styleConfig.resolveLyricsDisplayLines(track)
        val isPlaceholder = styleConfig.customLyrics.trim().isEmpty() &&
            lyricsText.startsWith("Add your favorite lyric lines")

        val cardW = (w * 0.80f * scale).coerceIn(w * 0.46f, w * 0.94f)
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

        val glassOpacity = styleConfig.cardOpacity.coerceIn(0.0f, 1.0f)
        val shadowStrength = styleConfig.glowIntensity.coerceAtLeast(0.25f) * scale
        val shadowAlpha = (135f * styleConfig.glowIntensity.coerceAtLeast(0.25f) * glassOpacity)
            .roundToInt()
            .coerceIn(0, 255)
        if (shadowAlpha > 2) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(shadowAlpha, 0, 0, 0)
                maskFilter = BlurMaskFilter(58f * shadowStrength, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(
                RectF(cardLeft, cardTop + (20f * scale), cardRight, cardBottom + (20f * scale)),
                cornerRadius,
                cornerRadius,
                shadowPaint
            )
        }

        val albumArt = track.albumArt
        if (useSongArtBg && albumArt != null) {
            if (glassOpacity > 0.01f) {
                drawBlurredCardCoverBackground(
                    canvas = canvas,
                    cardRect = cardRect,
                    cornerRadius = cornerRadius,
                    albumArt = albumArt,
                    blurRadius = styleConfig.lyricsArtBlurRadius,
                    opacity = glassOpacity
                )
            }

            val borderAlpha = (42f * glassOpacity).roundToInt().coerceIn(0, 255)
            if (borderAlpha > 0) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = AndroidColor.argb(borderAlpha, 255, 255, 255)
                }
                canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)
            }
        } else {
            val fillAlpha = (255f * glassOpacity).roundToInt().coerceIn(0, 255)
            if (fillAlpha > 0) {
                val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(cardArgb, glassOpacity)
                }
                canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)
            }
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

    private fun drawBlurredCardCoverBackground(
        canvas: Canvas,
        cardRect: RectF,
        cornerRadius: Float,
        albumArt: Bitmap,
        blurRadius: Float,
        opacity: Float = 1.0f
    ) {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        if (clampedOpacity <= 0.01f) return
        val clamped = blurRadius.coerceIn(0f, 40f)
        val drawBmp = if (clamped > 0.5f) {
            val maxDim = if (clamped <= 10f) 420 else 300
            BitmapBlurUtils.blurBitmap(
                source = albumArt,
                radiusPx = clamped,
                maxDimension = maxDim
            )
        } else {
            albumArt
        }

        canvas.save()
        val cardClip = Path().apply {
            addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(cardClip)
        val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = (255f * clampedOpacity).roundToInt().coerceIn(0, 255)
        }
        drawCenterCroppedBitmap(
            canvas = canvas,
            bitmap = drawBmp,
            destRect = cardRect,
            paint = artPaint
        )
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
                intArrayOf(
                    AndroidColor.argb((112f * clampedOpacity).roundToInt().coerceIn(0, 255), 0, 0, 0),
                    AndroidColor.argb((143f * clampedOpacity).roundToInt().coerceIn(0, 255), 0, 0, 0),
                    AndroidColor.argb((188f * clampedOpacity).roundToInt().coerceIn(0, 255), 0, 0, 0)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cardRect, scrimPaint)
        canvas.restore()

        if (drawBmp !== albumArt && !drawBmp.isRecycled) {
            drawBmp.recycle()
        }
    }

    private fun drawFrostedGlassOverlay(
        canvas: Canvas,
        cardRect: RectF,
        cornerRadius: Float,
        albumArt: Bitmap?,
        blurRadius: Float,
        intensity: Float,
        sideFrostColor: Int
    ) {
        val clamped = blurRadius.coerceIn(1f, 40f)
        val drawBmp = albumArt?.let {
            BitmapBlurUtils.blurBitmap(
                source = it,
                radiusPx = clamped,
                maxDimension = 220
            )
        }
        canvas.save()
        val cardClip = Path().apply {
            addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(cardClip)
        if (drawBmp != null) {
            val frostedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (255f * 0.56f * intensity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
            }
            drawCenterCroppedBitmap(
                canvas = canvas,
                bitmap = drawBmp,
                destRect = cardRect,
                paint = frostedPaint
            )
        }
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
                intArrayOf(
                    AndroidColor.argb((158f * intensity).roundToInt().coerceIn(0, 255), 14, 14, 18),
                    AndroidColor.argb((178f * intensity).roundToInt().coerceIn(0, 255), 12, 12, 16),
                    AndroidColor.argb((198f * intensity).roundToInt().coerceIn(0, 255), 10, 10, 14)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cardRect, scrimPaint)

        // Left & right card side frosted gradient
        val sideH = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.right, cardRect.top,
                intArrayOf(
                    withAlpha(sideFrostColor, 0.28f * intensity),
                    withAlpha(sideFrostColor, 0.05f * intensity),
                    withAlpha(sideFrostColor, 0.05f * intensity),
                    withAlpha(sideFrostColor, 0.28f * intensity)
                ),
                floatArrayOf(0f, 0.18f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cardRect, sideH)

        // Top & bottom edge frosted gradient
        val sideV = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
                intArrayOf(
                    withAlpha(AndroidColor.WHITE, 0.20f * intensity),
                    withAlpha(AndroidColor.WHITE, 0.02f * intensity),
                    withAlpha(AndroidColor.WHITE, 0.02f * intensity),
                    withAlpha(AndroidColor.WHITE, 0.18f * intensity)
                ),
                floatArrayOf(0f, 0.15f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cardRect, sideV)

        // Soft blurred inner perimeter glow along all 4 card sides
        val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 21f + 33f * intensity
            color = withAlpha(AndroidColor.WHITE, 0.28f * intensity)
            maskFilter = BlurMaskFilter(21f + 42f * intensity, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, innerRimPaint)
        canvas.restore()

        if (drawBmp != null && drawBmp !== albumArt && !drawBmp.isRecycled) {
            drawBmp.recycle()
        }
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
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val videoFile = exportAnimatedVideoIfActive(
            context = context,
            track = track,
            palette = palette,
            styleConfig = styleConfig,
            activeCanvasVideoUrl = activeCanvasVideoUrl,
            prefix = "${styleConfig.brandMode.wordmark}_motion"
        )
        if (videoFile != null) {
            val savedVideo = saveVideoFileToGallery(context, track, styleConfig, videoFile)
            videoFile.delete()
            if (savedVideo) return@withContext true
        }

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
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EchoSnap")
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
                val echoSnapDir = File(picturesDir, "EchoSnap").apply { mkdirs() }
                val file = File(echoSnapDir, fileName)
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
                val msg = if (styleConfig.useTransparentBackground) {
                    "Saved Transparent PNG (Pictures/EchoSnap)!"
                } else {
                    "Saved to Gallery (Pictures/EchoSnap)!"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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

    private suspend fun saveVideoFileToGallery(
        context: Context,
        track: MediaTrackInfo,
        styleConfig: CardStyleConfig,
        videoFile: File
    ): Boolean {
        return try {
            val safeTitle = track.title.replace("[^A-Za-z0-9_-]".toRegex(), "_").take(32)
            val fileName = "${styleConfig.brandMode.wordmark}_${safeTitle}_${System.currentTimeMillis()}.mp4"
            val contentResolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EchoSnap")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (videoUri != null) {
                    contentResolver.openOutputStream(videoUri).use { out ->
                        if (out != null) {
                            videoFile.inputStream().use { input ->
                                input.copyTo(out)
                                out.flush()
                            }
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(videoUri, values, null, null)
                }
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val echoDir = File(moviesDir, "EchoSnap").apply { mkdirs() }
                val dest = File(echoDir, fileName)
                videoFile.copyTo(dest, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, dest.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Saved Animated Video (Movies/EchoSnap)!",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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

    private suspend fun prepareSharePayload(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String?,
        filePrefix: String
    ): Pair<Uri, String> {
        val videoFile = exportAnimatedVideoIfActive(
            context = context,
            track = track,
            palette = palette,
            styleConfig = styleConfig,
            activeCanvasVideoUrl = activeCanvasVideoUrl,
            prefix = filePrefix
        )
        if (videoFile != null) {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, videoFile)
            return Pair(uri, "video/mp4")
        }
        val bitmap = generateStoryBitmap(context, track, palette, styleConfig)
        val uri = saveBitmapToCache(context, bitmap, "${filePrefix}_${System.currentTimeMillis()}.png")
        return Pair(uri, "image/png")
    }

    private suspend fun exportAnimatedVideoIfActive(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String?,
        prefix: String
    ): File? {
        // Transparent "No Background" exports must preserve full ARGB alpha PNG (MP4 lacks alpha channel)
        if (styleConfig.useTransparentBackground) return null

        val hasWaveformMotion = styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_INNER_CARD &&
            styleConfig.enableWaveform
        val hasCanvasVideo = !activeCanvasVideoUrl.isNullOrBlank()
        if (!hasCanvasVideo && !hasWaveformMotion) return null

        val waveformSpec = if (hasWaveformMotion) {
            WaveformExportSpec(
                rectIn720x1280 = computeFloatingGlassWaveformRect(
                    targetWidth = CanvasVideoExporter.OUT_WIDTH,
                    targetHeight = CanvasVideoExporter.OUT_HEIGHT,
                    track = track,
                    styleConfig = styleConfig
                ),
                waveformColorArgb = styleConfig.waveformColor.toArgb(),
                cardOpacity = styleConfig.cardOpacity,
                trackTitle = track.title,
                trackArtist = track.artist,
                isPlaying = true,
                speedMultiplier = styleConfig.waveformSpeedMultiplier,
                beatSyncEnabled = styleConfig.waveformBeatSync
            )
        } else {
            null
        }

        val overlay = generateTransparentCardOverlayBitmap(
            context = context,
            track = track,
            palette = palette,
            styleConfig = styleConfig,
            targetWidth = CanvasVideoExporter.OUT_WIDTH,
            targetHeight = CanvasVideoExporter.OUT_HEIGHT,
            includeWaveform = false
        )

        return if (hasCanvasVideo) {
            try {
                CanvasVideoExporter.exportAnimatedStoryVideo(
                    context = context,
                    canvasVideoUrl = activeCanvasVideoUrl!!,
                    overlayBitmap = overlay,
                    blurRadiusPx = styleConfig.backgroundBlurRadius,
                    outputFileName = "${prefix}_${System.currentTimeMillis()}.mp4",
                    waveformSpec = waveformSpec
                )
            } finally {
                if (!overlay.isRecycled) overlay.recycle()
            }
        } else {
            val staticBg = generateStaticBackgroundBitmap(
                track = track,
                palette = palette,
                styleConfig = styleConfig,
                targetWidth = CanvasVideoExporter.OUT_WIDTH,
                targetHeight = CanvasVideoExporter.OUT_HEIGHT
            )
            try {
                CanvasVideoExporter.exportStaticBackgroundWaveformVideo(
                    context = context,
                    backgroundBitmap = staticBg,
                    overlayBitmap = overlay,
                    waveformSpec = waveformSpec!!,
                    outputFileName = "${prefix}_wf8s_${System.currentTimeMillis()}.mp4"
                )
            } finally {
                if (!staticBg.isRecycled) staticBg.recycle()
                if (!overlay.isRecycled) overlay.recycle()
            }
        }
    }

    suspend fun shareToWhatsApp(
        context: Context,
        track: MediaTrackInfo,
        palette: CardPalette,
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val (uri, mimeType) = prepareSharePayload(
                context, track, palette, styleConfig, activeCanvasVideoUrl, "echosnap_wa"
            )
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Listening to ${track.title} by ${track.artist} on $brand")
                    clipData = ClipData.newRawUri("Share Card", uri)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.grantUriPermission("com.whatsapp", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
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
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val (uri, mimeType) = prepareSharePayload(
                context, track, palette, styleConfig, activeCanvasVideoUrl, "echosnap_ig_story"
            )

            withContext(Dispatchers.Main) {
                val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                    setDataAndType(uri, mimeType)
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
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val (uri, mimeType) = prepareSharePayload(
                context, track, palette, styleConfig, activeCanvasVideoUrl, "echosnap_snap_story"
            )
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val snapIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
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
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val (uri, mimeType) = prepareSharePayload(
                context, track, palette, styleConfig, activeCanvasVideoUrl, "echosnap_discord"
            )
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val discordIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
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
                        type = mimeType
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
        styleConfig: CardStyleConfig,
        activeCanvasVideoUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val (uri, mimeType) = prepareSharePayload(
                context, track, palette, styleConfig, activeCanvasVideoUrl, "echosnap_story"
            )
            val brand = styleConfig.brandMode.wordmark

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
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
        } else {
            cacheDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(4)
                ?.forEach { it.delete() }
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
