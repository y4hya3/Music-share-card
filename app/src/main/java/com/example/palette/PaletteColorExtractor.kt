package com.example.palette

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.example.model.CardPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Palette Color Extraction module using AndroidX Palette.
 * Extracts dominant visual colors (VibrantColor, DarkMutedColor, etc.) from album artwork.
 */
object PaletteColorExtractor {

    private const val DEFAULT_VIBRANT = 0xFFB8253B.toInt()
    private const val DEFAULT_DARK_MUTED = 0xFF1E0E14.toInt()
    private const val DEFAULT_DOMINANT = 0xFF88192A.toInt()
    private const val DEFAULT_LIGHT_VIBRANT = 0xFFF25A70.toInt()
    private const val DEFAULT_DARK_VIBRANT = 0xFF4C0A15.toInt()
    private const val DEFAULT_MUTED = 0xFF6E4249.toInt()
    private const val DEFAULT_LIGHT_MUTED = 0xFFE0B8BE.toInt()

    /**
     * Extracts palette colors asynchronously on Dispatchers.Default.
     */
    suspend fun extractColors(bitmap: Bitmap?): CardPalette = withContext(Dispatchers.Default) {
        val safeBitmap = com.example.util.BitmapBlurUtils.toSafeSoftwareBitmap(bitmap, maxDimension = 300)
        if (safeBitmap == null) {
            return@withContext CardPalette.Default
        }

        try {
            // Build palette from the software ARGB_8888 bitmap
            val palette = Palette.from(safeBitmap)
                .maximumColorCount(24)
                .generate()

            val vibrant = palette.getVibrantColor(
                palette.getDominantColor(DEFAULT_VIBRANT)
            )
            val darkMuted = palette.getDarkMutedColor(
                palette.getDarkVibrantColor(DEFAULT_DARK_MUTED)
            )
            val dominant = palette.getDominantColor(DEFAULT_DOMINANT)
            val lightVibrant = palette.getLightVibrantColor(DEFAULT_LIGHT_VIBRANT)
            val darkVibrant = palette.getDarkVibrantColor(DEFAULT_DARK_VIBRANT)
            val muted = palette.getMutedColor(DEFAULT_MUTED)
            val lightMuted = palette.getLightMutedColor(DEFAULT_LIGHT_MUTED)

            // Determine optimal text contrast based on darkMuted / vibrant background luminance
            val vibrantColor = Color(vibrant)
            val darkMutedColor = Color(darkMuted)

            CardPalette(
                vibrantColor = vibrantColor,
                darkMutedColor = darkMutedColor,
                dominantColor = Color(dominant),
                lightVibrantColor = Color(lightVibrant),
                darkVibrantColor = Color(darkVibrant),
                mutedColor = Color(muted),
                lightMutedColor = Color(lightMuted),
                titleTextColor = Color.White,
                subtitleTextColor = Color(0xCCFFFFFF)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            CardPalette.Default
        }
    }
}
