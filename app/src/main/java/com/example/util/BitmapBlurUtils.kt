package com.example.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Low-overhead CPU & Memory Bitmap Utility (optimized for 2GB-3GB RAM devices like Redmi 9C
 * and Samsung Galaxy Tab A7 LTE).
 * - Converts HARDWARE bitmaps safely to software ARGB_8888.
 * - Uses inJustDecodeBounds + inSampleSize so large cover art never spikes RAM.
 * - Uses zero-giant-lookup-table StackBlur on pre-downscaled buffers for fast 60fps UI updates.
 */
object BitmapBlurUtils {

    fun toSafeSoftwareBitmap(source: Bitmap?, maxDimension: Int = 720): Bitmap? {
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
                val resized = Bitmap.createScaledBitmap(software, targetW, targetH, true)
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

    fun decodeSampledResource(resources: Resources, resId: Int, maxDimension: Int = 720): Bitmap? {
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

    fun decodeSampledFromUri(context: Context, uri: Uri, maxDimension: Int = 720): Bitmap? {
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

    fun decodeSampledFromStream(stream: InputStream, maxDimension: Int = 720): Bitmap? {
        return try {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            val maxBytes = 6 * 1024 * 1024 // 6 MB safety cap for budget RAM devices
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
     * Fast StackBlur on a pre-downscaled buffer (default maxDimension = 180 for live UI, 260 for export).
     * Eliminates large lookup-table heap allocations so budget CPUs (Helio G35, Snapdragon 662)
     * do not suffer GC pauses during live slider dragging.
     */
    fun blurBitmap(source: Bitmap, radiusPx: Float, maxDimension: Int = 180): Bitmap {
        val safeSource = toSafeSoftwareBitmap(source, maxDimension = 720) ?: return source
        val clampedRadius = radiusPx.coerceIn(0f, 40f)
        if (clampedRadius <= 0.5f) {
            return safeSource
        }

        val scale = min(
            1f,
            maxDimension.toFloat() / max(safeSource.width, safeSource.height).coerceAtLeast(1)
        )
        val targetW = (safeSource.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (safeSource.height * scale).roundToInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(safeSource, targetW, targetH, true)
        val mutable = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled !== safeSource && scaled !== copy && !scaled.isRecycled) {
                scaled.recycle()
            }
            copy
        }

        val maxEffective = max(8, (maxDimension * 0.14f).roundToInt())
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
