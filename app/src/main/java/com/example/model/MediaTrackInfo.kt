package com.example.model

import android.graphics.Bitmap

/**
 * Represents media playback metadata extracted from active media notifications.
 */
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
