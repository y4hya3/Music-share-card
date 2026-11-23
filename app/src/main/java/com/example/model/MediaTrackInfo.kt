package com.example.model

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

/**
 * Represents media playback metadata extracted from active media notifications.
 */
@Immutable
data class MediaTrackInfo(
    val title: String,
    val artist: String,
    val album: String = "",
    val packageName: String = "",
    val appName: String = "BitChord",
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = true,
    val isSample: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val spotifyTrackId: String = ""
)

@Immutable
data class MediaPlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val lastUpdateElapsedMs: Long = 0L,
    val isPlaying: Boolean = false
) {
    fun currentPositionAt(elapsedNowMs: Long): Long {
        if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
        if (!isPlaying || lastUpdateElapsedMs <= 0L) {
            return positionMs.coerceIn(0L, durationMs)
        }
        val delta = ((elapsedNowMs - lastUpdateElapsedMs).coerceAtLeast(0L) * playbackSpeed.coerceIn(0.25f, 4f)).toLong()
        return (positionMs + delta).coerceIn(0L, durationMs)
    }

    fun fractionAt(elapsedNowMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (currentPositionAt(elapsedNowMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

