package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin GIF89a Transparent Looping Sticker Exporter.
 *
 * Used when "No Background (Transparent)" AND "Dynamic Island 4-Bar Waveform" are both active:
 * - Preserves clear transparent background (`transparentColorIndex = 0`) for CapCut, Instagram,
 *   Premiere, InShot, and messaging sticker workflows.
 * - Includes the `NETSCAPE2.0` Application Extension for infinite looping.
 * - Renders once-per-export base card overlay indices and re-quantizes only the waveform
 *   bounding box per frame for fast export (~0.4–0.9s on mobile devices).
 */
object AnimatedGifExporter {

    private const val TAG = "AnimatedGifExporter"
    const val GIF_WIDTH = 540
    const val GIF_HEIGHT = 960
    private const val DEFAULT_FPS = 14
    private const val DEFAULT_DURATION_SEC = 6.0f
    private const val ALPHA_TRANSPARENT_THRESHOLD = 90
    private const val TRANSPARENT_INDEX = 0

    suspend fun exportTransparentWaveformGif(
        overlayWithoutWaveform: Bitmap,
        waveformSpec: WaveformExportSpec,
        wfRectInOutput: RectF,
        outputFile: File,
        durationSec: Float = DEFAULT_DURATION_SEC,
        fps: Int = DEFAULT_FPS
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            BufferedOutputStream(FileOutputStream(outputFile), 65_536).use { out ->
                val ok = encodeTransparentWaveformGifToStream(
                    overlayWithoutWaveform = overlayWithoutWaveform,
                    waveformSpec = waveformSpec,
                    wfRectInOutput = wfRectInOutput,
                    out = out,
                    durationSec = durationSec,
                    fps = fps
                )
                out.flush()
                if (!ok) {
                    outputFile.delete()
                    return@withContext null
                }
            }

            if (outputFile.exists() && outputFile.length() > 1024L) {
                outputFile
            } else {
                outputFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transparent GIF export failed", e)
            outputFile.delete()
            null
        }
    }

    internal fun encodeTransparentWaveformGifToBytes(
        overlayWithoutWaveform: Bitmap,
        waveformSpec: WaveformExportSpec,
        wfRectInOutput: RectF,
        durationSec: Float = DEFAULT_DURATION_SEC,
        fps: Int = DEFAULT_FPS
    ): ByteArray {
        val baos = ByteArrayOutputStream(256 * 1024)
        encodeTransparentWaveformGifToStream(
            overlayWithoutWaveform = overlayWithoutWaveform,
            waveformSpec = waveformSpec,
            wfRectInOutput = wfRectInOutput,
            out = baos,
            durationSec = durationSec,
            fps = fps
        )
        return baos.toByteArray()
    }

    private fun encodeTransparentWaveformGifToStream(
        overlayWithoutWaveform: Bitmap,
        waveformSpec: WaveformExportSpec,
        wfRectInOutput: RectF,
        out: OutputStream,
        durationSec: Float,
        fps: Int
    ): Boolean {
        val w = overlayWithoutWaveform.width
        val h = overlayWithoutWaveform.height
        if (w <= 0 || h <= 0) return false

        val pixels = IntArray(w * h)
        overlayWithoutWaveform.getPixels(pixels, 0, w, 0, 0, w, h)

        val palette = buildGlobalPalette(pixels, w, h, waveformSpec.waveformColorArgb)
        val lut555 = buildRgb555Lookup(palette)

        val baseIndices = ByteArray(w * h)
        for (i in pixels.indices) {
            baseIndices[i] = quantizeArgbToPaletteIndex(pixels[i], lut555)
        }

        // Write GIF89a header + Logical Screen Descriptor + Global Color Table + NETSCAPE2.0 loop
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLE(out, w)
        writeShortLE(out, h)
        // GCT flag = 1, Color Res = 7 (8-bit), Sort = 0, GCT size = 7 (256 entries)
        out.write(0xF7)
        out.write(TRANSPARENT_INDEX)
        out.write(0x00) // pixel aspect ratio
        out.write(palette) // 256 * 3 bytes
        writeNetscapeLoopExtension(out)

        val clampedFps = fps.coerceIn(6, 20)
        val totalFrames = max(8, (durationSec * clampedFps).roundToInt())
        val delayCs = max(4, (100f / clampedFps).roundToInt())

        val wfLeft = wfRectInOutput.left.toInt().coerceIn(0, w - 1)
        val wfTop = wfRectInOutput.top.toInt().coerceIn(0, h - 1)
        val wfRight = kotlin.math.ceil(wfRectInOutput.right.toDouble()).toInt().coerceIn(wfLeft + 1, w)
        val wfBottom = kotlin.math.ceil(wfRectInOutput.bottom.toDouble()).toInt().coerceIn(wfTop + 1, h)
        val wfW = wfRight - wfLeft
        val wfH = wfBottom - wfTop

        // Copy the exact static base background behind the waveform box so transparent areas stay crisp
        val wfBasePixels = IntArray(wfW * wfH)
        for (row in 0 until wfH) {
            val srcOffset = (wfTop + row) * w + wfLeft
            System.arraycopy(pixels, srcOffset, wfBasePixels, row * wfW, wfW)
        }

        val wfBmp = Bitmap.createBitmap(wfW, wfH, Bitmap.Config.ARGB_8888)
        val wfCanvas = Canvas(wfBmp)
        val wfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = waveformSpec.waveformColorArgb
        }
        val wfLocalPixels = IntArray(wfW * wfH)
        val hasTransparentWfBase = wfBasePixels.any { ((it ushr 24) and 0xFF) < ALPHA_TRANSPARENT_THRESHOLD }
        val wfFrameIndices = if (!hasTransparentWfBase) ByteArray(wfW * wfH) else ByteArray(0)
        val frameIndices = if (hasTransparentWfBase) ByteArray(w * h) else ByteArray(0)
        val lzwEncoder = GifLzwEncoder()

        val localRect = RectF(
            wfRectInOutput.left - wfLeft,
            wfRectInOutput.top - wfTop,
            wfRectInOutput.right - wfLeft,
            wfRectInOutput.bottom - wfTop
        )
        val barRect = RectF()

        try {
            if (!hasTransparentWfBase) {
                // Frame 0: emit full canvas once, keeping contents on screen (disposal = 1)
                val firstFrameIndices = baseIndices.copyOf()
                wfBmp.setPixels(wfBasePixels, 0, wfW, 0, 0, wfW, wfH)
                drawWaveformBars(
                    canvas = wfCanvas,
                    rect = localRect,
                    barRect = barRect,
                    paint = wfPaint,
                    spec = waveformSpec,
                    timeSec = 0f
                )
                wfBmp.getPixels(wfLocalPixels, 0, wfW, 0, 0, wfW, wfH)
                for (row in 0 until wfH) {
                    val dstRowOffset = (wfTop + row) * w + wfLeft
                    val srcRowOffset = row * wfW
                    for (col in 0 until wfW) {
                        firstFrameIndices[dstRowOffset + col] =
                            quantizeArgbToPaletteIndex(wfLocalPixels[srcRowOffset + col], lut555)
                    }
                }
                writeGraphicControlExtension(
                    out = out,
                    delayCs = delayCs,
                    transparentIndex = TRANSPARENT_INDEX,
                    disposalMethod = 1,
                    hasTransparent = true
                )
                out.write(0x2C)
                writeShortLE(out, 0)
                writeShortLE(out, 0)
                writeShortLE(out, w)
                writeShortLE(out, h)
                out.write(0x00)
                lzwEncoder.encode(firstFrameIndices, out)

                // Frames 1..N: encode only the opaque wfW x wfH dirty rectangle (overwrites previous bars cleanly)
                for (frameIdx in 1 until totalFrames) {
                    val timeSec = frameIdx.toFloat() / clampedFps.toFloat()
                    wfBmp.setPixels(wfBasePixels, 0, wfW, 0, 0, wfW, wfH)
                    drawWaveformBars(
                        canvas = wfCanvas,
                        rect = localRect,
                        barRect = barRect,
                        paint = wfPaint,
                        spec = waveformSpec,
                        timeSec = timeSec
                    )
                    wfBmp.getPixels(wfLocalPixels, 0, wfW, 0, 0, wfW, wfH)
                    for (i in wfLocalPixels.indices) {
                        wfFrameIndices[i] = quantizeArgbToPaletteIndex(wfLocalPixels[i], lut555)
                    }
                    writeGraphicControlExtension(
                        out = out,
                        delayCs = delayCs,
                        transparentIndex = TRANSPARENT_INDEX,
                        disposalMethod = 1,
                        hasTransparent = false
                    )
                    out.write(0x2C)
                    writeShortLE(out, wfLeft)
                    writeShortLE(out, wfTop)
                    writeShortLE(out, wfW)
                    writeShortLE(out, wfH)
                    out.write(0x00)
                    lzwEncoder.encode(wfFrameIndices, out)
                }
            } else {
                for (frameIdx in 0 until totalFrames) {
                    val timeSec = frameIdx.toFloat() / clampedFps.toFloat()
                    System.arraycopy(baseIndices, 0, frameIndices, 0, baseIndices.size)

                    wfBmp.setPixels(wfBasePixels, 0, wfW, 0, 0, wfW, wfH)
                    drawWaveformBars(
                        canvas = wfCanvas,
                        rect = localRect,
                        barRect = barRect,
                        paint = wfPaint,
                        spec = waveformSpec,
                        timeSec = timeSec
                    )
                    wfBmp.getPixels(wfLocalPixels, 0, wfW, 0, 0, wfW, wfH)

                    for (row in 0 until wfH) {
                        val dstRowOffset = (wfTop + row) * w + wfLeft
                        val srcRowOffset = row * wfW
                        for (col in 0 until wfW) {
                            frameIndices[dstRowOffset + col] =
                                quantizeArgbToPaletteIndex(wfLocalPixels[srcRowOffset + col], lut555)
                        }
                    }

                    writeGraphicControlExtension(
                        out = out,
                        delayCs = delayCs,
                        transparentIndex = TRANSPARENT_INDEX,
                        disposalMethod = 2,
                        hasTransparent = true
                    )
                    out.write(0x2C)
                    writeShortLE(out, 0)
                    writeShortLE(out, 0)
                    writeShortLE(out, w)
                    writeShortLE(out, h)
                    out.write(0x00)
                    lzwEncoder.encode(frameIndices, out)
                }
            }
        } finally {
            if (!wfBmp.isRecycled) wfBmp.recycle()
        }

        // GIF Trailer
        out.write(0x3B)
        return true
    }

    private fun drawWaveformBars(
        canvas: Canvas,
        rect: RectF,
        barRect: RectF,
        paint: Paint,
        spec: WaveformExportSpec,
        timeSec: Float
    ) {
        val levels = AudioBeatMonitor.computeBarLevels(
            trackTitle = spec.trackTitle,
            trackArtist = spec.trackArtist,
            isPlaying = true,
            timeSec = timeSec,
            speedMultiplier = spec.speedMultiplier,
            beatSyncMode = spec.beatSyncEnabled,
            animateWhenPaused = true,
            forceMultiplier = spec.forceMultiplier
        )
        val padX = rect.width() * 0.08f
        val usableW = (rect.width() - padX * 2f).coerceAtLeast(6f)
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
            barRect.set(left, top, left + barW, top + barH)
            canvas.drawRoundRect(
                barRect,
                corner,
                corner,
                paint
            )
        }
    }

    private fun quantizeArgbToPaletteIndex(argb: Int, lut555: ByteArray): Byte {
        val a = (argb ushr 24) and 0xFF
        if (a < ALPHA_TRANSPARENT_THRESHOLD) {
            return TRANSPARENT_INDEX.toByte()
        }
        val r: Int
        val g: Int
        val b: Int
        if (a >= 250) {
            r = (argb ushr 16) and 0xFF
            g = (argb ushr 8) and 0xFF
            b = argb and 0xFF
        } else {
            // Composite semi-transparent frosted glass pixels against dark glass tone (18, 18, 24)
            val inv = 255 - a
            r = (((argb ushr 16) and 0xFF) * a + 18 * inv) / 255
            g = (((argb ushr 8) and 0xFF) * a + 18 * inv) / 255
            b = ((argb and 0xFF) * a + 24 * inv) / 255
        }
        val key = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
        return lut555[key]
    }

    /**
     * Builds a 256-color Global Color Table:
     * - Index 0: transparent placeholder (0, 0, 0)
     * - Indices 1..11: exact waveform color + anti-aliased edge shades + pure white/black
     * - Indices 12..255: median-cut quantized colors sampled from the artwork & card
     */
    private fun buildGlobalPalette(
        pixels: IntArray,
        w: Int,
        h: Int,
        waveformColorArgb: Int
    ): ByteArray {
        val table = ByteArray(256 * 3)
        val paletteInts = ArrayList<Int>(256)
        paletteInts.add(0x000000) // Index 0 reserved for transparency

        val wfR = (waveformColorArgb ushr 16) and 0xFF
        val wfG = (waveformColorArgb ushr 8) and 0xFF
        val wfB = waveformColorArgb and 0xFF
        val wfRgb = (wfR shl 16) or (wfG shl 8) or wfB

        val reserved = ArrayList<Int>(12)
        reserved.add(wfRgb)
        reserved.add(0xFFFFFF)
        reserved.add(0x000000)
        // Soft AA transition steps between dark frosted card surface and waveform bar color
        for (step in intArrayOf(25, 50, 75)) {
            val f = step / 100f
            val r = (18 + (wfR - 18) * f).roundToInt().coerceIn(0, 255)
            val g = (18 + (wfG - 18) * f).roundToInt().coerceIn(0, 255)
            val b = (24 + (wfB - 24) * f).roundToInt().coerceIn(0, 255)
            reserved.add((r shl 16) or (g shl 8) or b)
        }
        for (c in reserved) {
            if (!paletteInts.contains(c)) {
                paletteInts.add(c)
            }
        }

        // Sample up to ~12,000 opaque pixels evenly for Median-Cut quantization
        val stride = max(1, (w * h) / 12_000)
        val histogram = HashMap<Int, Int>(4096)
        var idx = 0
        while (idx < pixels.size) {
            val argb = pixels[idx]
            val a = (argb ushr 24) and 0xFF
            if (a >= ALPHA_TRANSPARENT_THRESHOLD) {
                val r = if (a >= 250) (argb ushr 16) and 0xFF else (((argb ushr 16) and 0xFF) * a + 18 * (255 - a)) / 255
                val g = if (a >= 250) (argb ushr 8) and 0xFF else (((argb ushr 8) and 0xFF) * a + 18 * (255 - a)) / 255
                val b = if (a >= 250) argb and 0xFF else ((argb and 0xFF) * a + 24 * (255 - a)) / 255
                // Group cleanly in 6-bit RGB bins (262k possible keys) for fast median-cut
                val qKey = ((r and 0xFC) shl 16) or ((g and 0xFC) shl 8) or (b and 0xFC)
                histogram[qKey] = (histogram[qKey] ?: 0) + 1
            }
            idx += stride
        }

        val maxQuantized = 256 - paletteInts.size
        val medianColors = medianCut(histogram, maxQuantized)
        for (c in medianColors) {
            if (paletteInts.size >= 256) break
            if (!paletteInts.contains(c)) {
                paletteInts.add(c)
            }
        }

        // Fill any remaining slots safely
        var pad = 0
        while (paletteInts.size < 256) {
            val v = (pad * 17) and 0xFF
            paletteInts.add((v shl 16) or (v shl 8) or v)
            pad++
        }

        for (i in 0 until 256) {
            val rgb = paletteInts[i]
            table[i * 3] = ((rgb ushr 16) and 0xFF).toByte()
            table[i * 3 + 1] = ((rgb ushr 8) and 0xFF).toByte()
            table[i * 3 + 2] = (rgb and 0xFF).toByte()
        }
        return table
    }

    private fun medianCut(histogram: Map<Int, Int>, maxColors: Int): List<Int> {
        if (histogram.isEmpty() || maxColors <= 0) return emptyList()
        val entries = histogram.entries.map { ColorBin(it.key, it.value) }.toMutableList()
        if (entries.size <= maxColors) {
            return entries.map { it.rgb }
        }

        val boxes = ArrayList<ColorBox>(maxColors)
        boxes.add(ColorBox(entries, 0, entries.size))

        while (boxes.size < maxColors) {
            var bestIdx = -1
            var bestScore = -1L
            for (i in boxes.indices) {
                val box = boxes[i]
                if (box.count > 1) {
                    val score = box.rangeScore
                    if (score > bestScore) {
                        bestScore = score
                        bestIdx = i
                    }
                }
            }
            if (bestIdx < 0 || bestScore <= 0L) break

            val splitBox = boxes.removeAt(bestIdx)
            val (left, right) = splitBox.split(entries)
            boxes.add(left)
            if (right.count > 0) boxes.add(right)
        }

        return boxes.map { it.averageColor(entries) }
    }

    private class ColorBin(val rgb: Int, val weight: Int) {
        val r: Int get() = (rgb ushr 16) and 0xFF
        val g: Int get() = (rgb ushr 8) and 0xFF
        val b: Int get() = rgb and 0xFF
    }

    private class ColorBox(
        bins: List<ColorBin>,
        val start: Int,
        val end: Int
    ) {
        val count: Int = end - start
        private val minR: Int
        private val maxR: Int
        private val minG: Int
        private val maxG: Int
        private val minB: Int
        private val maxB: Int
        private val dominantChannel: Int
        val rangeScore: Long

        init {
            var r0 = 255
            var r1 = 0
            var g0 = 255
            var g1 = 0
            var b0 = 255
            var b1 = 0
            var totalW = 0L
            for (i in start until end) {
                val c = bins[i]
                if (c.r < r0) r0 = c.r
                if (c.r > r1) r1 = c.r
                if (c.g < g0) g0 = c.g
                if (c.g > g1) g1 = c.g
                if (c.b < b0) b0 = c.b
                if (c.b > b1) b1 = c.b
                totalW += c.weight
            }
            minR = r0; maxR = r1
            minG = g0; maxG = g1
            minB = b0; maxB = b1
            val dr = maxR - minR
            val dg = maxG - minG
            val db = maxB - minB
            dominantChannel = when {
                dr >= dg && dr >= db -> 0
                dg >= dr && dg >= db -> 1
                else -> 2
            }
            val maxSpan = max(dr, max(dg, db)).toLong()
            rangeScore = maxSpan * maxSpan * max(1L, totalW)
        }

        fun split(bins: MutableList<ColorBin>): Pair<ColorBox, ColorBox> {
            val sub = bins.subList(start, end)
            when (dominantChannel) {
                0 -> sub.sortBy { it.r }
                1 -> sub.sortBy { it.g }
                else -> sub.sortBy { it.b }
            }
            var totalW = 0L
            for (i in start until end) totalW += bins[i].weight
            val halfW = totalW / 2L
            var acc = 0L
            var mid = start + 1
            for (i in start until end - 1) {
                acc += bins[i].weight
                mid = i + 1
                if (acc >= halfW) break
            }
            return Pair(ColorBox(bins, start, mid), ColorBox(bins, mid, end))
        }

        fun averageColor(bins: List<ColorBin>): Int {
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var sumW = 0L
            for (i in start until end) {
                val c = bins[i]
                val w = c.weight.toLong()
                sumR += c.r * w
                sumG += c.g * w
                sumB += c.b * w
                sumW += w
            }
            if (sumW <= 0L) return 0
            val r = (sumR / sumW).toInt().coerceIn(0, 255)
            val g = (sumG / sumW).toInt().coerceIn(0, 255)
            val b = (sumB / sumW).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Precomputes a 32,768-entry 5-5-5 RGB lookup table mapping any RGB color to palette index [1..255].
     */
    private fun buildRgb555Lookup(palette: ByteArray): ByteArray {
        val lut = ByteArray(32_768)
        val pr = IntArray(256)
        val pg = IntArray(256)
        val pb = IntArray(256)
        for (i in 1 until 256) {
            pr[i] = palette[i * 3].toInt() and 0xFF
            pg[i] = palette[i * 3 + 1].toInt() and 0xFF
            pb[i] = palette[i * 3 + 2].toInt() and 0xFF
        }

        for (r5 in 0 until 32) {
            val r = (r5 shl 3) or (r5 ushr 2)
            for (g5 in 0 until 32) {
                val g = (g5 shl 3) or (g5 ushr 2)
                for (b5 in 0 until 32) {
                    val b = (b5 shl 3) or (b5 ushr 2)
                    var bestIdx = 1
                    var bestDist = Int.MAX_VALUE
                    for (i in 1 until 256) {
                        val dr = r - pr[i]
                        val dg = g - pg[i]
                        val db = b - pb[i]
                        // Slight perceptual green weight for cleaner anti-aliased edges
                        val dist = dr * dr * 3 + dg * dg * 4 + db * db * 2
                        if (dist < bestDist) {
                            bestDist = dist
                            bestIdx = i
                            if (dist == 0) break
                        }
                    }
                    lut[(r5 shl 10) or (g5 shl 5) or b5] = bestIdx.toByte()
                }
            }
        }
        return lut
    }

    private fun writeShortLE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeNetscapeLoopExtension(out: OutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03)
        out.write(0x01)
        writeShortLE(out, 0) // 0 = infinite loop
        out.write(0x00)
    }

    private fun writeGraphicControlExtension(
        out: OutputStream,
        delayCs: Int,
        transparentIndex: Int,
        disposalMethod: Int = 2,
        hasTransparent: Boolean = true
    ) {
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        val packed = ((disposalMethod and 0x07) shl 2) or (if (hasTransparent) 0x01 else 0x00)
        out.write(packed)
        writeShortLE(out, delayCs)
        out.write(transparentIndex and 0xFF)
        out.write(0x00)
    }

    /**
     * Compact, allocation-free array-hashed GIF LZW encoder (minimum code size = 8).
     */
    private class GifLzwEncoder {
        private companion object {
            const val MIN_CODE_SIZE = 8
            const val CLEAR_CODE = 1 shl MIN_CODE_SIZE // 256
            const val EOI_CODE = CLEAR_CODE + 1 // 257
            const val MAX_CODE = 4095
            const val HASH_SIZE = 5003
        }

        private val hTab = IntArray(HASH_SIZE)
        private val codeTab = IntArray(HASH_SIZE)
        private val packet = ByteArray(256)
        private var packetLen = 0

        fun encode(indices: ByteArray, out: OutputStream) {
            out.write(MIN_CODE_SIZE)
            packetLen = 0

            var bitAccum = 0
            var bitCount = 0
            var codeBits = MIN_CODE_SIZE + 1
            var nextCode = EOI_CODE + 1
            var maxCodeForBits = (1 shl codeBits) - 1

            fun flushPacket() {
                if (packetLen > 0) {
                    out.write(packetLen)
                    out.write(packet, 0, packetLen)
                    packetLen = 0
                }
            }

            fun writeCode(code: Int) {
                bitAccum = bitAccum or (code shl bitCount)
                bitCount += codeBits
                while (bitCount >= 8) {
                    packet[packetLen++] = (bitAccum and 0xFF).toByte()
                    if (packetLen == 254) {
                        flushPacket()
                    }
                    bitAccum = bitAccum ushr 8
                    bitCount -= 8
                }
            }

            fun clearTable() {
                hTab.fill(-1)
                nextCode = EOI_CODE + 1
                codeBits = MIN_CODE_SIZE + 1
                maxCodeForBits = (1 shl codeBits) - 1
            }

            clearTable()
            writeCode(CLEAR_CODE)

            if (indices.isEmpty()) {
                writeCode(EOI_CODE)
                if (bitCount > 0) {
                    packet[packetLen++] = (bitAccum and 0xFF).toByte()
                }
                flushPacket()
                out.write(0x00)
                return
            }

            var prefix = indices[0].toInt() and 0xFF
            for (pos in 1 until indices.size) {
                val c = indices[pos].toInt() and 0xFF
                val fCode = (c shl 12) or prefix
                var i = (c shl 4) xor prefix
                if (i >= HASH_SIZE) i %= HASH_SIZE

                var matched = false
                if (hTab[i] == fCode) {
                    prefix = codeTab[i]
                    matched = true
                } else if (hTab[i] >= 0) {
                    var disp = HASH_SIZE - i
                    if (i == 0) disp = 1
                    while (true) {
                        i -= disp
                        if (i < 0) i += HASH_SIZE
                        if (hTab[i] == fCode) {
                            prefix = codeTab[i]
                            matched = true
                            break
                        }
                        if (hTab[i] < 0) break
                    }
                }

                if (!matched) {
                    writeCode(prefix)
                    if (nextCode <= MAX_CODE) {
                        hTab[i] = fCode
                        codeTab[i] = nextCode++
                        // Increase code width right after emitting code that reaches bit boundary
                        if (nextCode - 1 > maxCodeForBits && codeBits < 12) {
                            codeBits++
                            maxCodeForBits = (1 shl codeBits) - 1
                        }
                    } else {
                        writeCode(CLEAR_CODE)
                        clearTable()
                    }
                    prefix = c
                }
            }

            writeCode(prefix)
            writeCode(EOI_CODE)
            if (bitCount > 0) {
                packet[packetLen++] = (bitAccum and 0xFF).toByte()
            }
            flushPacket()
            out.write(0x00)
        }
    }
}
