package com.example.palette

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.example.model.CardPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Enhanced Palette Color Extraction module using AndroidX Palette + HSV harmonization.
 * Works reliably on ALL album cover types:
 * - Monochrome / black-and-white / ultra-dark / ultra-bright covers (where default Palette
 *   filters reject swatches) via clearFilters() and weighted population fallback.
 * - Derives harmonious vibrant, dominant, dark-muted, light-vibrant, and dark-vibrant colors
 *   so white typography remains sharp and gradients never look muddy.
 */
object PaletteColorExtractor {

    /**
     * Extracts palette colors asynchronously on Dispatchers.Default.
     */
    suspend fun extractColors(bitmap: Bitmap?): CardPalette = withContext(Dispatchers.Default) {
        val safeBitmap = com.example.util.BitmapBlurUtils.toSafeSoftwareBitmap(bitmap, maxDimension = 300)
        if (safeBitmap == null) {
            return@withContext CardPalette.Default
        }

        try {
            // Clear default luminance filters so pure-dark, bright, and skin/red covers still yield real swatches
            val palette = Palette.from(safeBitmap)
                .maximumColorCount(24)
                .clearFilters()
                .generate()

            val swatchesByPop = palette.swatches.sortedByDescending { it.population }
            if (swatchesByPop.isEmpty()) {
                return@withContext CardPalette.Default
            }

            // Pick the most chromatic swatch among the top weighted swatches for vibrant fallback
            val bestChromaticSwatch = swatchesByPop.maxByOrNull { swatch ->
                val hsv = swatch.hsl
                val saturation = hsv[1]
                val lightness = hsv[2]
                val lightnessScore = 1f - kotlin.math.abs(lightness - 0.5f) * 1.4f
                saturation * 2.2f + lightnessScore.coerceAtLeast(0.1f) +
                    (swatch.population.toFloat() / max(1, swatchesByPop.first().population)) * 0.45f
            }

            val dominantArgb = palette.dominantSwatch?.rgb ?: swatchesByPop.first().rgb

            val rawVibrant = palette.vibrantSwatch?.rgb
                ?: bestChromaticSwatch?.takeIf { it.hsl[1] >= 0.14f }?.rgb
                ?: deriveVibrantFrom(dominantArgb)
            val vibrantArgb = ensureReadableAccent(rawVibrant, minValue = 0.34f, maxValue = 0.88f)

            val rawDarkMuted = palette.darkMutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: deriveDeepAnchorFrom(dominantArgb, vibrantArgb)
            val darkMutedArgb = ensureDeepAnchor(rawDarkMuted, maxValue = 0.22f)

            val dominantSafeArgb = harmonizeDominant(dominantArgb, darkMutedArgb)

            val lightVibrantArgb = palette.lightVibrantSwatch?.rgb
                ?: deriveLightAccent(vibrantArgb)

            val darkVibrantArgb = palette.darkVibrantSwatch?.rgb
                ?: deriveDarkAccent(vibrantArgb)

            val mutedArgb = palette.mutedSwatch?.rgb
                ?: deriveMutedFrom(dominantArgb, vibrantArgb)

            val lightMutedArgb = palette.lightMutedSwatch?.rgb
                ?: deriveLightAccent(mutedArgb)

            CardPalette(
                vibrantColor = Color(vibrantArgb),
                darkMutedColor = Color(darkMutedArgb),
                dominantColor = Color(dominantSafeArgb),
                lightVibrantColor = Color(lightVibrantArgb),
                darkVibrantColor = Color(darkVibrantArgb),
                mutedColor = Color(mutedArgb),
                lightMutedColor = Color(lightMutedArgb),
                titleTextColor = Color.White,
                subtitleTextColor = Color(0xCCFFFFFF)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            CardPalette.Default
        }
    }

    private fun deriveVibrantFrom(baseArgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(baseArgb, hsv)
        if (hsv[1] < 0.08f) {
            // Monochrome / black-and-white cover: craft a sleek slate-blue/silver accent instead of mismatched red
            return AndroidColor.rgb(122, 134, 154)
        }
        hsv[1] = max(hsv[1], 0.56f).coerceAtMost(0.92f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.84f)
        return AndroidColor.HSVToColor(hsv)
    }

    private fun deriveDeepAnchorFrom(dominantArgb: Int, vibrantArgb: Int): Int {
        val hsvDom = FloatArray(3)
        AndroidColor.colorToHSV(dominantArgb, hsvDom)
        val hsvVib = FloatArray(3)
        AndroidColor.colorToHSV(vibrantArgb, hsvVib)

        val useHue = if (hsvDom[1] >= 0.10f) hsvDom[0] else hsvVib[0]
        val useSat = if (hsvDom[1] >= 0.08f || hsvVib[1] >= 0.08f) {
            max(hsvDom[1], hsvVib[1] * 0.72f).coerceIn(0.18f, 0.65f)
        } else {
            0.08f
        }
        return AndroidColor.HSVToColor(floatArrayOf(useHue, useSat, 0.14f))
    }

    private fun ensureReadableAccent(argb: Int, minValue: Float, maxValue: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hsv[2] = hsv[2].coerceIn(minValue, maxValue)
        if (hsv[1] in 0.08f..0.38f) {
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(0.65f)
        }
        return AndroidColor.HSVToColor(hsv)
    }

    private fun ensureDeepAnchor(argb: Int, maxValue: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hsv[2] = min(hsv[2], maxValue).coerceAtLeast(0.06f)
        hsv[1] = hsv[1].coerceAtMost(0.70f)
        return AndroidColor.HSVToColor(hsv)
    }

    private fun harmonizeDominant(dominantArgb: Int, darkMutedArgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(dominantArgb, hsv)
        // Prevent pure-white album covers from making dominant gradient stops unreadable under white text
        if (hsv[2] > 0.72f) {
            hsv[2] = 0.42f
            if (hsv[1] < 0.10f) {
                hsv[1] = 0.08f
            }
            return AndroidColor.HSVToColor(hsv)
        }
        if (hsv[2] < 0.06f) {
            return darkMutedArgb
        }
        return dominantArgb
    }

    private fun deriveLightAccent(baseArgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(baseArgb, hsv)
        hsv[1] = (hsv[1] * 0.80f).coerceAtMost(0.85f)
        hsv[2] = max(hsv[2], 0.74f).coerceAtMost(0.92f)
        return AndroidColor.HSVToColor(hsv)
    }

    private fun deriveDarkAccent(baseArgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(baseArgb, hsv)
        hsv[1] = min(0.88f, max(hsv[1], 0.35f))
        hsv[2] = hsv[2].coerceIn(0.16f, 0.32f)
        return AndroidColor.HSVToColor(hsv)
    }

    private fun deriveMutedFrom(dominantArgb: Int, vibrantArgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(dominantArgb, hsv)
        if (hsv[1] < 0.08f) {
            AndroidColor.colorToHSV(vibrantArgb, hsv)
        }
        hsv[1] = hsv[1].coerceIn(0.15f, 0.45f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.55f)
        return AndroidColor.HSVToColor(hsv)
    }
}
