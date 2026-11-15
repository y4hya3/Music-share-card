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
import com.example.service.AnimatedCanvasCandidate
import com.example.service.AnimatedCanvasSearchService
import com.example.service.CoverArtSearchService
import com.example.service.MusicNotificationListenerService
import com.example.service.OnlineCoverCandidate
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central repository managing current active track metadata, palette extraction,
 * persistent CardStyleConfig edits, and online HD cover art search/selection.
 */
object MediaNotificationRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var appContext: Context? = null

    private var onlineSearchJob: Job? = null
    private var canvasSearchJob: Job? = null
    private var lastAutoSearchedTrackKey: String? = null
    private var lastAutoCanvasTrackKey: String? = null
    private var userManuallySelectedCoverTrackKey: String? = null
    private var userManuallySelectedCanvasTrackKey: String? = null

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

    private val _onlineCoverCandidates = MutableStateFlow<List<OnlineCoverCandidate>>(emptyList())
    val onlineCoverCandidates: StateFlow<List<OnlineCoverCandidate>> = _onlineCoverCandidates.asStateFlow()

    private val _isSearchingCoverArt = MutableStateFlow(false)
    val isSearchingCoverArt: StateFlow<Boolean> = _isSearchingCoverArt.asStateFlow()

    private val _originalPlayerArt = MutableStateFlow<Bitmap?>(null)
    val originalPlayerArt: StateFlow<Bitmap?> = _originalPlayerArt.asStateFlow()

    private val _selectedCoverId = MutableStateFlow("player")
    val selectedCoverId: StateFlow<String> = _selectedCoverId.asStateFlow()

    private val _animatedCanvasCandidates = MutableStateFlow<List<AnimatedCanvasCandidate>>(emptyList())
    val animatedCanvasCandidates: StateFlow<List<AnimatedCanvasCandidate>> = _animatedCanvasCandidates.asStateFlow()

    private val _isSearchingCanvas = MutableStateFlow(false)
    val isSearchingCanvas: StateFlow<Boolean> = _isSearchingCanvas.asStateFlow()

    private val _canvasSearchStatus = MutableStateFlow<String?>(null)
    val canvasSearchStatus: StateFlow<String?> = _canvasSearchStatus.asStateFlow()

    private val _activeCanvasUrl = MutableStateFlow<String?>(null)
    val activeCanvasUrl: StateFlow<String?> = _activeCanvasUrl.asStateFlow()

    private val _activeCanvasId = MutableStateFlow("none")
    val activeCanvasId: StateFlow<String> = _activeCanvasId.asStateFlow()

    private var sampleTracksList: List<MediaTrackInfo> = emptyList()

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        _styleConfig.value = CardStylePreferences.load(ctx)
        checkNotificationAccess(ctx)

        val travisArt = BitmapBlurUtils.decodeSampledResource(
            ctx.resources,
            R.drawable.sample_art_travis,
            maxDimension = 1024
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
        _originalPlayerArt.value = travisArt
        _selectedCoverId.value = "player"
        extractPaletteForTrack(initialTrack)
    }

    fun checkNotificationAccess(context: Context) {
        _isNotificationAccessEnabled.value =
            MusicNotificationListenerService.isNotificationAccessGranted(context)
    }

    fun updateTrack(trackInfo: MediaTrackInfo) {
        val current = _currentTrack.value
        val newKey = trackKey(trackInfo)
        val currentKey = trackKey(current)
        val isSameTrack = !current.isSample && currentKey == newKey

        if (!isSameTrack) {
            _originalPlayerArt.value = trackInfo.albumArt
            _selectedCoverId.value = "player"
            _onlineCoverCandidates.value = emptyList()
            _animatedCanvasCandidates.value = emptyList()
            _canvasSearchStatus.value = null
            _activeCanvasUrl.value = null
            _activeCanvasId.value = "none"
            userManuallySelectedCoverTrackKey = null
            userManuallySelectedCanvasTrackKey = null
        } else if (trackInfo.albumArt != null && !trackInfo.albumArt.isRecycled) {
            val prevOrig = _originalPlayerArt.value
            val prevPixels = if (prevOrig != null && !prevOrig.isRecycled) prevOrig.width.toLong() * prevOrig.height.toLong() else 0L
            val incPixels = trackInfo.albumArt.width.toLong() * trackInfo.albumArt.height.toLong()
            if (incPixels >= prevPixels) {
                _originalPlayerArt.value = trackInfo.albumArt
            }
        }

        val currentArt = current.albumArt
        val incomingArt = trackInfo.albumArt

        val resolvedArt = when {
            isSameTrack && userManuallySelectedCoverTrackKey == newKey && currentArt != null && !currentArt.isRecycled -> {
                // Respect user's manual cover art choice for the active song
                currentArt
            }
            isSameTrack && currentArt != null && !currentArt.isRecycled -> {
                if (incomingArt == null || incomingArt.isRecycled) {
                    currentArt
                } else {
                    val currentPixels = currentArt.width.toLong() * currentArt.height.toLong()
                    val incomingPixels = incomingArt.width.toLong() * incomingArt.height.toLong()
                    if (currentPixels > incomingPixels) currentArt else incomingArt
                }
            }
            else -> incomingArt
        }

        val finalTrack = if (resolvedArt !== trackInfo.albumArt) {
            trackInfo.copy(albumArt = resolvedArt)
        } else {
            trackInfo
        }
        _currentTrack.value = finalTrack
        extractPaletteForTrack(finalTrack)

        // If Auto-Search Online HD Cover is enabled, look up HD candidates automatically for new live tracks
        if (!finalTrack.isSample &&
            _styleConfig.value.autoSearchOnlineArt &&
            lastAutoSearchedTrackKey != newKey
        ) {
            lastAutoSearchedTrackKey = newKey
            searchOnlineCovers(autoApplyBest = true)
        }

        // If Auto-Search Animated Canvas is enabled, look up looping canvases automatically for new live tracks
        if (!finalTrack.isSample &&
            _styleConfig.value.autoSearchAnimatedCanvas &&
            lastAutoCanvasTrackKey != newKey
        ) {
            lastAutoCanvasTrackKey = newKey
            searchAnimatedCanvases(autoApplyBest = true)
        }
    }

    fun updateTrackArtworkIfMatching(
        packageName: String,
        title: String,
        artist: String,
        albumArt: Bitmap
    ) {
        val current = _currentTrack.value
        val key = "${packageName}|${title}|${artist}"
        if (!current.isSample &&
            current.packageName == packageName &&
            current.title == title &&
            current.artist == artist
        ) {
            _originalPlayerArt.value = albumArt
            if (userManuallySelectedCoverTrackKey == key) return

            val existing = current.albumArt
            val existingPixels = if (existing != null && !existing.isRecycled) {
                existing.width.toLong() * existing.height.toLong()
            } else {
                0L
            }
            val newPixels = albumArt.width.toLong() * albumArt.height.toLong()
            if (existingPixels == 0L || newPixels >= existingPixels) {
                val updated = current.copy(albumArt = albumArt)
                _currentTrack.value = updated
                extractPaletteForTrack(updated)
            }
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
        _originalPlayerArt.value = track.albumArt
        _selectedCoverId.value = "player"
        _onlineCoverCandidates.value = emptyList()
        _animatedCanvasCandidates.value = emptyList()
        _canvasSearchStatus.value = null
        _activeCanvasUrl.value = null
        _activeCanvasId.value = "none"
        userManuallySelectedCoverTrackKey = null
        userManuallySelectedCanvasTrackKey = null
        lastAutoSearchedTrackKey = null
        lastAutoCanvasTrackKey = null
        _currentTrack.value = track
        extractPaletteForTrack(track)
    }

    fun getSampleTracks(): List<MediaTrackInfo> = sampleTracksList

    fun updateStyleConfig(update: (CardStyleConfig) -> CardStyleConfig) {
        val updated = update(_styleConfig.value)
        _styleConfig.value = updated
        appContext?.let { CardStylePreferences.save(it, updated) }
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

    fun searchOnlineCovers(
        customQuery: String? = null,
        autoApplyBest: Boolean = false
    ) {
        val snapshot = _currentTrack.value
        val trackSnapshotKey = trackKey(snapshot)
        val searchTerm = (customQuery ?: buildSearchQuery(snapshot)).trim()
        if (searchTerm.isEmpty()) return

        onlineSearchJob?.cancel()
        onlineSearchJob = repositoryScope.launch {
            _isSearchingCoverArt.value = true
            try {
                val results = CoverArtSearchService.searchCoverArt(searchTerm, maxResults = 8)
                _onlineCoverCandidates.value = results
                if (autoApplyBest &&
                    results.isNotEmpty() &&
                    trackKey(_currentTrack.value) == trackSnapshotKey &&
                    userManuallySelectedCoverTrackKey != trackSnapshotKey
                ) {
                    applyOnlineCoverCandidate(results.first(), markAsManual = false)
                }
            } finally {
                _isSearchingCoverArt.value = false
            }
        }
    }

    fun selectOnlineCover(candidate: OnlineCoverCandidate) {
        repositoryScope.launch {
            _isSearchingCoverArt.value = true
            try {
                applyOnlineCoverCandidate(candidate, markAsManual = true)
            } finally {
                _isSearchingCoverArt.value = false
            }
        }
    }

    fun restoreOriginalPlayerCover() {
        val orig = _originalPlayerArt.value ?: return
        val current = _currentTrack.value
        userManuallySelectedCoverTrackKey = null
        _selectedCoverId.value = "player"
        val updated = current.copy(albumArt = orig)
        _currentTrack.value = updated
        extractPaletteForTrack(updated)
    }

    private suspend fun applyOnlineCoverCandidate(
        candidate: OnlineCoverCandidate,
        markAsManual: Boolean
    ) {
        val hdBitmap = CoverArtSearchService.fetchFullResolutionArt(candidate) ?: return
        val current = _currentTrack.value
        if (markAsManual) {
            userManuallySelectedCoverTrackKey = trackKey(current)
        }
        _selectedCoverId.value = candidate.id
        val updated = current.copy(albumArt = hdBitmap)
        _currentTrack.value = updated
        extractPaletteForTrack(updated)
    }

    fun searchAnimatedCanvases(
        customQuery: String? = null,
        autoApplyBest: Boolean = false
    ) {
        val snapshot = _currentTrack.value
        val trackSnapshotKey = trackKey(snapshot)
        val searchTerm = (customQuery ?: buildSearchQuery(snapshot)).trim()
        if (searchTerm.isEmpty()) return

        canvasSearchJob?.cancel()
        canvasSearchJob = repositoryScope.launch {
            _isSearchingCanvas.value = true
            _canvasSearchStatus.value = "Smart searching animated canvases..."
            try {
                val result = AnimatedCanvasSearchService.searchCanvases(
                    rawQuery = searchTerm,
                    spotifyToken = _styleConfig.value.spotifyAccessToken,
                    targetTitle = snapshot.title,
                    targetArtist = snapshot.artist,
                    maxResults = 8
                )
                _animatedCanvasCandidates.value = result.candidates
                _canvasSearchStatus.value = result.statusMessage

                if (autoApplyBest &&
                    result.candidates.isNotEmpty() &&
                    trackKey(_currentTrack.value) == trackSnapshotKey &&
                    userManuallySelectedCanvasTrackKey != trackSnapshotKey
                ) {
                    val best = result.candidates.firstOrNull { it.isLoopingVideo } ?: result.candidates.first()
                    applyAnimatedCanvasCandidate(best, markAsManual = false)
                }
            } finally {
                _isSearchingCanvas.value = false
            }
        }
    }

    fun selectAnimatedCanvas(candidate: AnimatedCanvasCandidate) {
        repositoryScope.launch {
            _isSearchingCanvas.value = true
            try {
                applyAnimatedCanvasCandidate(candidate, markAsManual = true)
            } finally {
                _isSearchingCanvas.value = false
            }
        }
    }

    fun clearAnimatedCanvas() {
        userManuallySelectedCanvasTrackKey = null
        _activeCanvasUrl.value = null
        _activeCanvasId.value = "none"
    }

    private suspend fun applyAnimatedCanvasCandidate(
        candidate: AnimatedCanvasCandidate,
        markAsManual: Boolean
    ) {
        val current = _currentTrack.value
        if (markAsManual) {
            userManuallySelectedCanvasTrackKey = trackKey(current)
        }
        _activeCanvasId.value = candidate.id
        _activeCanvasUrl.value = candidate.videoUrl

        // Also upgrade art / export frame to HD poster or extracted video frame so palette + 9:16 exports stay crisp
        val hdFrame = AnimatedCanvasSearchService.extractHighResPosterOrFrame(candidate)
        if (hdFrame != null) {
            val updated = current.copy(albumArt = hdFrame)
            _currentTrack.value = updated
            extractPaletteForTrack(updated)
        }
    }

    private fun buildSearchQuery(track: MediaTrackInfo): String {
        val cleanTitle = track.title
            .replace("\\s*\\((feat\\.|ft\\.|with).*?\\)".toRegex(RegexOption.IGNORE_CASE), "")
            .trim()
        val cleanArtist = track.artist.trim()
        return if (cleanArtist.isNotEmpty() && !cleanArtist.equals(track.appName, ignoreCase = true)) {
            "$cleanTitle $cleanArtist"
        } else {
            cleanTitle
        }
    }

    private fun trackKey(track: MediaTrackInfo): String =
        "${track.packageName}|${track.title}|${track.artist}"

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
