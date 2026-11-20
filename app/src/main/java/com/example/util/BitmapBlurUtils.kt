package com.example.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-quality, low-overhead CPU & Memory Bitmap Utility (optimized for 2GB-3GB RAM devices
 * like Redmi 9C and Samsung Galaxy Tab A7 LTE).
 * - Converts HARDWARE bitmaps safely to software ARGB_8888.
 * - Uses progressive 2x halving downscaling to avoid single-step Skia pixel-skipping ("choppy" art).
 * - Safely trims symmetric black letterbox/pillarbox bars from video music thumbnails (e.g. YouTube).
 * - Uses zero-giant-lookup-table StackBlur on adaptive-resolution buffers for smooth 60fps UI updates.
 */
object BitmapBlurUtils {

    private val highQualityBitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    )

    private val mipPyramidCache = LruCache<String, Triple<Bitmap, Bitmap, Bitmap>>(2)

    fun toSafeSoftwareBitmap(source: Bitmap?, maxDimension: Int = 1024): Bitmap? {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
            return null
        }
        return try {
            val needsSoftwareCopy =
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    source.config == Bitmap.Config.HARDWARE) ||
                    source.config != Bitmap.Config.ARGB_8888

            val software = if (needsSoftwareCopy) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            } ?: return null

            val maxSide = max(software.width, software.height)
            if (maxSide > maxDimension && maxDimension > 0) {
                val scale = maxDimension.toFloat() / maxSide.toFloat()
                val targetW = (software.width * scale).roundToInt().coerceAtLeast(1)
                val targetH = (software.height * scale).roundToInt().coerceAtLeast(1)
                val resized = createProgressiveScaledBitmap(software, targetW, targetH)
                if (software !== source && software !== resized && !software.isRecycled) {
                    software.recycle()
                }
                resized
            } else {
                software
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Progressive 2x halving downscale followed by a filtered final step.
     * Prevents Skia's single-pass bilinear sampler from skipping pixels when shrinking > 2x,
     * keeping both crisp album covers and blurred backgrounds smooth instead of jagged/choppy.
     */
    fun createProgressiveScaledBitmap(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val dstW = targetW.coerceAtLeast(1)
        val dstH = targetH.coerceAtLeast(1)
        if (source.width == dstW && source.height == dstH) return source

        // Upscaling or small (<2x) downscale works cleanly in one filtered pass
        if (source.width <= dstW * 2 && source.height <= dstH * 2) {
            return drawScaledBitmap(source, dstW, dstH)
        }

        var current = source
        var curW = source.width
        var curH = source.height
        while (curW / 2 >= dstW && curH / 2 >= dstH && curW > 2 && curH > 2) {
            val nextW = (curW / 2).coerceAtLeast(dstW)
            val nextH = (curH / 2).coerceAtLeast(dstH)
            if (nextW == curW && nextH == curH) break
            val stepped = drawScaledBitmap(current, nextW, nextH)
            if (current !== source && current !== stepped && !current.isRecycled) {
                current.recycle()
            }
            current = stepped
            curW = nextW
            curH = nextH
        }

        if (curW != dstW || curH != dstH) {
            val finalBitmap = drawScaledBitmap(current, dstW, dstH)
            if (current !== source && current !== finalBitmap && !current.isRecycled) {
                current.recycle()
            }
            return finalBitmap
        }
        return current
    }

    private fun drawScaledBitmap(source: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(0, 0, dstW, dstH),
            highQualityBitmapPaint
        )
        return out
    }

    /**
     * Detects and trims symmetric near-pure-black letterbox or pillarbox bars commonly embedded in
     * YouTube / YouTube Music thumbnails (e.g., 480x360 hqdefault or 1280x720 16:9 covers).
     * Never alters genuine dark album covers because it requires symmetric pure-black outer bands
     * AND non-black inner borders.
     */
    fun trimLetterboxBarsIfPresent(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 64 || h < 64 || source.isRecycled) return source

        // Only inspect genuinely non-square thumbnails (e.g. 4:3, 16:9, 9:16 video thumbs);
        // never chop square or near-square album covers.
        val aspect = w.toFloat() / h.toFloat()
        if (aspect in 0.85f..1.18f) return source

        return try {
            fun isNearBlack(pixel: Int): Boolean {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                return r <= 12 && g <= 12 && b <= 12
            }

            fun hasVisibleNonBlackBorder(rowOrCol: IntArray, step: Int): Boolean {
                var hits = 0
                var samples = 0
                var i = 2
                while (i < rowOrCol.size - 2) {
                    samples++
                    val px = rowOrCol[i]
                    val maxCh = maxOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
                    if (maxCh > 24) hits++
                    i += step
                }
                return samples > 0 && hits * 4 >= samples
            }

            val rowBuffer = IntArray(w)
            val rowStep = max(1, w / 32)
            fun isRowBlack(y: Int): Boolean {
                source.getPixels(rowBuffer, 0, w, 0, y, w, 1)
                var x = 2
                while (x < w - 2) {
                    if (!isNearBlack(rowBuffer[x])) return false
                    x += rowStep
                }
                return true
            }

            val colBuffer = IntArray(h)
            val colStep = max(1, h / 32)
            fun isColBlack(x: Int): Boolean {
                source.getPixels(colBuffer, 0, 1, x, 0, 1, h)
                var y = 2
                while (y < h - 2) {
                    if (!isNearBlack(colBuffer[y])) return false
                    y += colStep
                }
                return true
            }

            var topBar = 0
            val maxVBar = (h * 0.28f).roundToInt()
            while (topBar < maxVBar && isRowBlack(topBar)) {
                topBar++
            }
            var bottomBar = 0
            while (bottomBar < maxVBar && isRowBlack(h - 1 - bottomBar)) {
                bottomBar++
            }

            var leftBar = 0
            val maxHBar = (w * 0.30f).roundToInt()
            while (leftBar < maxHBar && isColBlack(leftBar)) {
                leftBar++
            }
            var rightBar = 0
            while (rightBar < maxHBar && isColBlack(w - 1 - rightBar)) {
                rightBar++
            }

            val minVTrigger = (h * 0.06f).roundToInt()
            val minHTrigger = (w * 0.06f).roundToInt()

            var hasSymmetricVerticalBars =
                topBar >= minVTrigger &&
                    bottomBar >= minVTrigger &&
                    abs(topBar - bottomBar) <= max(6, (h * 0.05f).roundToInt())

            if (hasSymmetricVerticalBars) {
                val innerTopY = (topBar + 4).coerceAtMost(h - 1)
                val innerBotY = (h - 1 - bottomBar - 4).coerceAtLeast(0)
                source.getPixels(rowBuffer, 0, w, 0, innerTopY, w, 1)
                val topBright = hasVisibleNonBlackBorder(rowBuffer, rowStep)
                source.getPixels(rowBuffer, 0, w, 0, innerBotY, w, 1)
                val botBright = hasVisibleNonBlackBorder(rowBuffer, rowStep)
                if (!topBright || !botBright) {
                    hasSymmetricVerticalBars = false
                }
            }

            var hasSymmetricHorizontalBars =
                leftBar >= minHTrigger &&
                    rightBar >= minHTrigger &&
                    abs(leftBar - rightBar) <= max(6, (w * 0.05f).roundToInt())

            if (hasSymmetricHorizontalBars) {
                val innerLeftX = (leftBar + 4).coerceAtMost(w - 1)
                val innerRightX = (w - 1 - rightBar - 4).coerceAtLeast(0)
                source.getPixels(colBuffer, 0, 1, innerLeftX, 0, 1, h)
                val leftBright = hasVisibleNonBlackBorder(colBuffer, colStep)
                source.getPixels(colBuffer, 0, 1, innerRightX, 0, 1, h)
                val rightBright = hasVisibleNonBlackBorder(colBuffer, colStep)
                if (!leftBright || !rightBright) {
                    hasSymmetricHorizontalBars = false
                }
            }

            val cropTop = if (hasSymmetricVerticalBars) (topBar + 2).coerceAtMost(h / 3) else 0
            val cropBottom = if (hasSymmetricVerticalBars) (h - bottomBar - 2).coerceAtLeast(cropTop + 32) else h
            val cropLeft = if (hasSymmetricHorizontalBars) (leftBar + 2).coerceAtMost(w / 3) else 0
            val cropRight = if (hasSymmetricHorizontalBars) (w - rightBar - 2).coerceAtLeast(cropLeft + 32) else w

            val newW = cropRight - cropLeft
            val newH = cropBottom - cropTop

            if ((cropTop > 0 || cropBottom < h || cropLeft > 0 || cropRight < w) &&
                newW >= w * 0.42f &&
                newH >= h * 0.42f
            ) {
                Bitmap.createBitmap(source, cropLeft, cropTop, newW, newH)
            } else {
                source
            }
        } catch (_: Exception) {
            source
        }
    }

    fun decodeSampledResource(resources: Resources, resId: Int, maxDimension: Int = 1024): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, resId, bounds)
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeResource(resources, resId, opts)
            val safe = toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
            if (decoded != null && safe !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            safe
        } catch (_: Exception) {
            null
        }
    }

    fun decodeSampledFromUri(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            val safe = toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
            if (decoded != null && safe !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            safe
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves crisp high-resolution local/offline artwork from MediaStore content URIs or files.
     * Compares full stream decode, Android Q+ ContentResolver.loadThumbnail(maxDimension), and
     * ID3 embedded art so offline music players never display choppy 128px/256px thumbnails.
     */
    fun decodeHighResFromUri(context: Context, uri: Uri, maxDimension: Int = 1200): Bitmap? {
        var best: Bitmap? = decodeSampledFromUri(context, uri, maxDimension = maxDimension)
        var bestPixels = if (best != null && !best.isRecycled) best.width.toLong() * best.height.toLong() else 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thumb = context.contentResolver.loadThumbnail(uri, Size(maxDimension, maxDimension), null)
                val safeThumb = toSafeSoftwareBitmap(thumb, maxDimension = maxDimension)
                if (safeThumb !== thumb && !thumb.isRecycled) {
                    thumb.recycle()
                }
                if (safeThumb != null && !safeThumb.isRecycled) {
                    val thumbPixels = safeThumb.width.toLong() * safeThumb.height.toLong()
                    if (thumbPixels > bestPixels) {
                        if (best != null && best !== safeThumb && !best.isRecycled) best.recycle()
                        best = safeThumb
                        bestPixels = thumbPixels
                    } else if (safeThumb !== best) {
                        safeThumb.recycle()
                    }
                }
            } catch (_: Exception) {
            }
        }

        val maxSide = maxOf(best?.width ?: 0, best?.height ?: 0)
        if (maxSide < 720) {
            val embedded = decodeEmbeddedAudioArtFromUri(context, uri, maxDimension = maxDimension)
            if (embedded != null && !embedded.isRecycled) {
                val embPixels = embedded.width.toLong() * embedded.height.toLong()
                if (embPixels > bestPixels) {
                    if (best != null && best !== embedded && !best.isRecycled) best.recycle()
                    best = embedded
                } else if (embedded !== best) {
                    embedded.recycle()
                }
            }
        }
        return best
    }

    fun decodeEmbeddedAudioArtFromUri(context: Context, uri: Uri, maxDimension: Int = 1200): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val picBytes = retriever.embeddedPicture ?: return null
            if (picBytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, bounds)
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts) ?: return null
            val safe = toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
            if (safe !== decoded && !decoded.isRecycled) decoded.recycle()
            safe
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun decodeSampledFromStream(stream: InputStream, maxDimension: Int = 1024): Bitmap? {
        return try {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            val maxBytes = 8 * 1024 * 1024 // 8 MB safety cap for budget RAM devices
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                total += read
                if (total > maxBytes) break
                buffer.write(chunk, 0, read)
            }
            val bytes = buffer.toByteArray()
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val safe = toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
            if (decoded != null && safe !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            safe
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Smooth StackBlur with adaptive buffer sizing so low-blur (1..12px) backgrounds stay
     * sharp and block-free on large phone/tablet screens while high-blur stays fast on budget CPUs.
     */
    fun blurBitmap(source: Bitmap, radiusPx: Float, maxDimension: Int = 280): Bitmap {
        val safeSource = toSafeSoftwareBitmap(source, maxDimension = 1024) ?: return source
        val clampedRadius = radiusPx.coerceIn(0f, 40f)
        if (clampedRadius <= 0.5f) {
            return safeSource
        }

        // Preserve higher resolution at light blur radii to eliminate pixel blockiness
        val effectiveMaxDim = if (clampedRadius <= 10f) {
            max(maxDimension, 360)
        } else {
            maxDimension
        }

        val scale = min(
            1f,
            effectiveMaxDim.toFloat() / max(safeSource.width, safeSource.height).coerceAtLeast(1)
        )
        val targetW = (safeSource.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (safeSource.height * scale).roundToInt().coerceAtLeast(1)

        val scaled = createProgressiveScaledBitmap(safeSource, targetW, targetH)
        if (safeSource !== source && safeSource !== scaled && !safeSource.isRecycled) {
            safeSource.recycle()
        }

        val mutable = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled !== source && scaled !== copy && !scaled.isRecycled) {
                scaled.recycle()
            }
            copy
        }

        val actualMaxSide = max(mutable.width, mutable.height).coerceAtLeast(1)
        val maxEffective = max(10, (actualMaxSide * 0.105f).roundToInt())
        val effectiveRadius = ((clampedRadius / 40f) * maxEffective).roundToInt().coerceIn(1, maxEffective)
        stackBlurInPlace(mutable, effectiveRadius)
        return mutable
    }

    /**
     * Pre-computes and caches a 3-stop blur mip pyramid (12px, 24px, 40px) once per cover art change so
     * devices below Android 12 share one cached pyramid across background and glass card layers at 60fps.
     */
    fun computeBlurMipPyramid(
        source: Bitmap,
        lowDim: Int = 340,
        midDim: Int = 260,
        highDim: Int = 220
    ): Triple<Bitmap, Bitmap, Bitmap>? {
        if (source.isRecycled) return null
        val key = "${System.identityHashCode(source)}_${source.width}x${source.height}_${lowDim}_${midDim}_${highDim}"
        synchronized(mipPyramidCache) {
            mipPyramidCache.get(key)?.let { cached ->
                if (!cached.first.isRecycled && !cached.second.isRecycled && !cached.third.isRecycled) {
                    return cached
                } else {
                    mipPyramidCache.remove(key)
                }
            }
        }
        return try {
            val safe = toSafeSoftwareBitmap(source, maxDimension = 720) ?: return null
            val low = blurBitmap(safe, radiusPx = 12f, maxDimension = lowDim)
            val mid = blurBitmap(safe, radiusPx = 24f, maxDimension = midDim)
            val high = blurBitmap(safe, radiusPx = 40f, maxDimension = highDim)
            if (safe !== source && safe !== low && safe !== mid && safe !== high && !safe.isRecycled) {
                safe.recycle()
            }
            val result = Triple(low, mid, high)
            synchronized(mipPyramidCache) {
                mipPyramidCache.put(key, result)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum

        yi = 0
        yw = 0

        val stackR = IntArray(div)
        val stackG = IntArray(div)
        val stackB = IntArray(div)
        var stackpointer: Int
        var stackstart: Int
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                val stackIdx = i + radius
                val sr = (p and 0xff0000) shr 16
                val sg = (p and 0x00ff00) shr 8
                val sb = p and 0x0000ff
                stackR[stackIdx] = sr
                stackG[stackIdx] = sg
                stackB[stackIdx] = sb
                rbs = r1 - abs(i)
                rsum += sr * rbs
                gsum += sg * rbs
                bsum += sb * rbs
                if (i > 0) {
                    rinsum += sr
                    ginsum += sg
                    binsum += sb
                } else {
                    routsum += sr
                    goutsum += sg
                    boutsum += sb
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = rsum / divsum
                g[yi] = gsum / divsum
                b[yi] = bsum / divsum

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = (stackpointer - radius + div) % div
                routsum -= stackR[stackstart]
                goutsum -= stackG[stackstart]
                boutsum -= stackB[stackstart]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                val pr = (p and 0xff0000) shr 16
                val pg = (p and 0x00ff00) shr 8
                val pb = p and 0x0000ff
                stackR[stackstart] = pr
                stackG[stackstart] = pg
                stackB[stackstart] = pb

                rinsum += pr
                ginsum += pg
                binsum += pb

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                routsum += stackR[stackpointer]
                goutsum += stackG[stackpointer]
                boutsum += stackB[stackpointer]

                rinsum -= stackR[stackpointer]
                ginsum -= stackG[stackpointer]
                binsum -= stackB[stackpointer]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                val stackIdx = i + radius
                val sr = r[yi]
                val sg = g[yi]
                val sb = b[yi]
                stackR[stackIdx] = sr
                stackG[stackIdx] = sg
                stackB[stackIdx] = sb
                rbs = r1 - abs(i)
                rsum += sr * rbs
                gsum += sg * rbs
                bsum += sb * rbs
                if (i > 0) {
                    rinsum += sr
                    ginsum += sg
                    binsum += sb
                } else {
                    routsum += sr
                    goutsum += sg
                    boutsum += sb
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (-0x1000000 and pix[yi]) or
                    ((rsum / divsum) shl 16) or
                    ((gsum / divsum) shl 8) or
                    (bsum / divsum)

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = (stackpointer - radius + div) % div
                routsum -= stackR[stackstart]
                goutsum -= stackG[stackstart]
                boutsum -= stackB[stackstart]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                val pr = r[p]
                val pg = g[p]
                val pb = b[p]
                stackR[stackstart] = pr
                stackG[stackstart] = pg
                stackB[stackstart] = pb

                rinsum += pr
                ginsum += pg
                binsum += pb

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                routsum += stackR[stackpointer]
                goutsum += stackG[stackpointer]
                boutsum += stackB[stackpointer]

                rinsum -= stackR[stackpointer]
                ginsum -= stackG[stackpointer]
                binsum -= stackB[stackpointer]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
