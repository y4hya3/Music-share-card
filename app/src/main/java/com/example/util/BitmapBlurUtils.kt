package com.example.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
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

        // Only inspect non-square artwork (or slight aspect mismatches typical of video thumbnails)
        val aspect = w.toFloat() / h.toFloat()
        if (aspect in 0.96f..1.04f) return source

        return try {
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            fun isNearBlack(pixel: Int): Boolean {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                return r <= 12 && g <= 12 && b <= 12
            }

            fun isRowBlack(y: Int): Boolean {
                val rowOffset = y * w
                val step = max(1, w / 32)
                var x = 2
                while (x < w - 2) {
                    if (!isNearBlack(pixels[rowOffset + x])) return false
                    x += step
                }
                return true
            }

            fun isColBlack(x: Int): Boolean {
                val step = max(1, h / 32)
                var y = 2
                while (y < h - 2) {
                    if (!isNearBlack(pixels[y * w + x])) return false
                    y += step
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

            val hasSymmetricVerticalBars =
                topBar >= minVTrigger &&
                    bottomBar >= minVTrigger &&
                    abs(topBar - bottomBar) <= max(6, (h * 0.05f).roundToInt())

            val hasSymmetricHorizontalBars =
                leftBar >= minHTrigger &&
                    rightBar >= minHTrigger &&
                    abs(leftBar - rightBar) <= max(6, (w * 0.05f).roundToInt())

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
            toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
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
            toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
        } catch (_: Exception) {
            null
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
            toSafeSoftwareBitmap(decoded, maxDimension = maxDimension)
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
        val mutable = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled !== safeSource && scaled !== copy && !scaled.isRecycled) {
                scaled.recycle()
            }
            copy
        }

        val actualMaxSide = max(mutable.width, mutable.height).coerceAtLeast(1)
        val maxEffective = max(10, (actualMaxSide * 0.14f).roundToInt())
        val effectiveRadius = ((clampedRadius / 40f) * maxEffective).roundToInt().coerceIn(1, maxEffective)
        stackBlurInPlace(mutable, effectiveRadius)
        return mutable
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

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
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
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
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

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

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
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
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

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
