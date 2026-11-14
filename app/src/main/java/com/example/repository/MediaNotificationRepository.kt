package com.example.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.example.R
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.palette.PaletteColorExtractor
import com.example.service.MusicNotificationListenerService
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central repository managing current active track metadata, palette extraction,
 * and card styling from any media notification.
 */
object MediaNotificationRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentTrack = MutableStateFlow(
        MediaTrackInfo(
            title = "SICKO MODE",
            artist = "Travis Scott",
            album = "ASTROWORLD",
            appName = "BitChord",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true
        )
    )
    val currentTrack: StateFlow<MediaTrackInfo> = _currentTrack.asStateFlow()

    private val _extractedPalette = MutableStateFlow(CardPalette.Default)
    val extractedPalette: StateFlow<CardPalette> = _extractedPalette.asStateFlow()

    private val _styleConfig = MutableStateFlow(CardStyleConfig())
    val styleConfig: StateFlow<CardStyleConfig> = _styleConfig.asStateFlow()

    private val _isNotificationAccessEnabled = MutableStateFlow(false)
    val isNotificationAccessEnabled: StateFlow<Boolean> = _isNotificationAccessEnabled.asStateFlow()

    private var sampleTracksList: List<MediaTrackInfo> = emptyList()

    fun initialize(context: Context) {
        checkNotificationAccess(context)

        val travisArt = BitmapBlurUtils.decodeSampledResource(
            context.resources,
            R.drawable.sample_art_travis,
            maxDimension = 720
        ) ?: createFallbackArt("Travis Scott", 0xFF9E1B32.toInt(), 0xFF191414.toInt())

        val theWeekndArt = createFallbackArt("The Weeknd", 0xFFE50914.toInt(), 0xFF0D0D1A.toInt())
        val daftPunkArt = createFallbackArt("Daft Punk", 0xFFFFD700.toInt(), 0xFF1A1A2E.toInt())
        val billieArt = createFallbackArt("Billie Eilish", 0xFF00C9FF.toInt(), 0xFF0B192C.toInt())

        val initialTrack = MediaTrackInfo(
            title = "SICKO MODE",
            artist = "Travis Scott",
            album = "ASTROWORLD",
            appName = "BitChord",
            packageName = "",
            albumArt = travisArt,
            isPlaying = true,
            isSample = true
        )

        sampleTracksList = listOf(
            initialTrack,
            MediaTrackInfo(
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                appName = "BitChord",
                packageName = "",
                albumArt = theWeekndArt,
                isPlaying = true,
                isSample = true
            ),
            MediaTrackInfo(
                title = "Get Lucky (feat. Pharrell)",
                artist = "Daft Punk",
                album = "Random Access Memories",
                appName = "BitChord",
                packageName = "",
                albumArt = daftPunkArt,
                isPlaying = true,
                isSample = true
            ),
            MediaTrackInfo(
                title = "BIRDS OF A FEATHER",
                artist = "Billie Eilish",
                album = "HIT ME HARD AND SOFT",
                appName = "BitChord",
                packageName = "",
                albumArt = billieArt,
                isPlaying = true,
                isSample = true
            )
        )

        _currentTrack.value = initialTrack
        extractPaletteForTrack(initialTrack)
    }

    fun checkNotificationAccess(context: Context) {
        _isNotificationAccessEnabled.value =
            MusicNotificationListenerService.isNotificationAccessGranted(context)
    }

    fun updateTrack(trackInfo: MediaTrackInfo) {
        val current = _currentTrack.value
        val resolvedArt = if (
            trackInfo.albumArt == null &&
            !current.isSample &&
            current.packageName == trackInfo.packageName &&
            current.title == trackInfo.title &&
            current.artist == trackInfo.artist &&
            current.albumArt != null
        ) {
            current.albumArt
        } else {
            trackInfo.albumArt
        }

        val finalTrack = if (resolvedArt !== trackInfo.albumArt) {
            trackInfo.copy(albumArt = resolvedArt)
        } else {
            trackInfo
        }
        _currentTrack.value = finalTrack
        extractPaletteForTrack(finalTrack)
    }

    fun updateTrackArtworkIfMatching(
        packageName: String,
        title: String,
        artist: String,
        albumArt: Bitmap
    ) {
        val current = _currentTrack.value
        if (!current.isSample &&
            current.packageName == packageName &&
            current.title == title &&
            current.artist == artist
        ) {
            val updated = current.copy(albumArt = albumArt)
            _currentTrack.value = updated
            extractPaletteForTrack(updated)
        }
    }

    fun refreshFromActiveMedia(context: Context): Boolean {
        checkNotificationAccess(context)
        if (!_isNotificationAccessEnabled.value) {
            return false
        }
        return MusicNotificationListenerService.syncNow(context)
    }

    fun selectSampleTrack(track: MediaTrackInfo) {
        _currentTrack.value = track
        extractPaletteForTrack(track)
    }

    fun getSampleTracks(): List<MediaTrackInfo> = sampleTracksList

    fun updateStyleConfig(update: (CardStyleConfig) -> CardStyleConfig) {
        _styleConfig.value = update(_styleConfig.value)
    }

    fun updateCustomTrackDetails(title: String, artist: String, album: String) {
        val current = _currentTrack.value
        val updated = current.copy(
            title = title,
            artist = artist,
            album = album,
            isSample = true
        )
        _currentTrack.value = updated
    }

    private fun extractPaletteForTrack(track: MediaTrackInfo) {
        repositoryScope.launch {
            val palette = PaletteColorExtractor.extractColors(track.albumArt)
            _extractedPalette.value = palette
        }
    }

    private fun createFallbackArt(title: String, startColor: Int, endColor: Int): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                startColor, endColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        paint.shader = null
        paint.color = 0x22FFFFFF
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)
        paint.strokeWidth = 4f
        canvas.drawCircle(size / 2f, size / 2f, size * 0.22f, paint)

        return bitmap
    }
}
