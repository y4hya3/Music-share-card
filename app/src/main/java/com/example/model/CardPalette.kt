package com.example.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Colors extracted from album artwork using AndroidX Palette.
 */
@Immutable
data class CardPalette(
    val vibrantColor: Color = Color(0xFFE50914),
    val darkMutedColor: Color = Color(0xFF191414),
    val dominantColor: Color = Color(0xFF800020),
    val lightVibrantColor: Color = Color(0xFFFF6F61),
    val darkVibrantColor: Color = Color(0xFF4A0E17),
    val mutedColor: Color = Color(0xFF553C3C),
    val lightMutedColor: Color = Color(0xFFC0A9A9),
    val titleTextColor: Color = Color.White,
    val subtitleTextColor: Color = Color(0xFFCCCCCC)
) {
    companion object {
        val Default = CardPalette(
            vibrantColor = Color(0xFFB8253B),
            darkMutedColor = Color(0xFF1E0E14),
            dominantColor = Color(0xFF88192A),
            lightVibrantColor = Color(0xFFF25A70),
            darkVibrantColor = Color(0xFF4C0A15),
            mutedColor = Color(0xFF6E4249),
            lightMutedColor = Color(0xFFE0B8BE),
            titleTextColor = Color.White,
            subtitleTextColor = Color(0xCCFFFFFF)
        )

        val NeutralDark = CardPalette(
            vibrantColor = Color(0xFF1F2333),
            darkMutedColor = Color(0xFF0E1018),
            dominantColor = Color(0xFF161926),
            lightVibrantColor = Color(0xFF3E4560),
            darkVibrantColor = Color(0xFF121520),
            mutedColor = Color(0xFF2B3042),
            lightMutedColor = Color(0xFFB4BCD0),
            titleTextColor = Color.White,
            subtitleTextColor = Color(0xCCFFFFFF)
        )
    }
}
