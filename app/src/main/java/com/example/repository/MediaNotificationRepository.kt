package com.example.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import com.example.R
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.LiveWallpaperMode
import com.example.model.MediaPlaybackProgress
import com.example.model.MediaTrackInfo
import com.example.palette.PaletteColorExtractor
import com.example.service.AnimatedCanvasCandidate
import com.example.service.AnimatedCanvasSearchService
import com.example.service.CoverArtSearchService
import com.example.service.LyricsCandidate
import com.example.service.LyricsSearchService
import com.example.service.MusicNotificationListenerService
import com.example.service.OnlineCoverCandidate
import com.example.service.SpotifyAuthManager
import com.example.util.BitmapBlurUtils
import com.example.util.CanvasVideoExporter
import com.example.util.DevicePerformanceTier
import com.example.util.EchoSnapBackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central repository managing current active track metadata, palette extraction,
 * persistent CardStyleConfig edits, and online HD cover art search/selection.
 */
object MediaNotificationRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    private var onlineSearchJob: Job? = null
    private var lowResCoverUpgradeJob: Job? = null
    private var canvasSearchJob: Job? = null
    private var applyCanvasJob: Job? = null
    private var coverLoadJob: Job? = null
    private var lyricsSearchJob: Job? = null
    private var paletteJob: Job? = null
    private var prefsSaveJob: Job? = null
    private var proactiveSessionRefreshJob: Job? = null
    @Volatile
    private var lastMediaRefreshMs: Long = 0L
    private var lastAutoSearchedTrackKey: String? = null
    private var lastAutoUpgradedLowResTrackKey: String? = null
    private var lastAutoCanvasTrackKey: String? = null
    private var userManuallySelectedCoverTrackKey: String? = null
    private var userManuallySelectedCanvasTrackKey: String? = null
    private var autoAppliedSavedCanvasTrackKey: String? = null
    private var autoAppliedSavedCoverTrackKey: String? = null

    private const val PREFS_FAVORITES = "echosnap_favorites"
    private const val KEY_FAVORITE_KEYS = "favorite_track_keys"

    private val _currentTrack = MutableStateFlow(
        MediaTrackInfo(
            title = "SICKO MODE",
            artist = "Travis Scott",
            album = "ASTROWORLD",
            appName = "EchoSnap",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true,
            durationMs = 138_000L,
            positionMs = 29_000L
        )
    )
    val currentTrack: StateFlow<MediaTrackInfo> = _currentTrack.asStateFlow()

    private val _playbackProgress = MutableStateFlow(
        MediaPlaybackProgress(
            positionMs = 29_000L,
            durationMs = 138_000L,
            playbackSpeed = 1f,
            lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
            isPlaying = true
        )
    )
    val playbackProgress: StateFlow<MediaPlaybackProgress> = _playbackProgress.asStateFlow()

    private val _favoriteTrackKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackKeys: StateFlow<Set<String>> = _favoriteTrackKeys.asStateFlow()

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

    private val _spotifyReloginRequired = MutableStateFlow(false)
    val spotifyReloginRequired: StateFlow<Boolean> = _spotifyReloginRequired.asStateFlow()

    private val _activeCanvasUrl = MutableStateFlow<String?>(null)
    val activeCanvasUrl: StateFlow<String?> = _activeCanvasUrl.asStateFlow()

    private val _activeCanvasId = MutableStateFlow("none")
    val activeCanvasId: StateFlow<String> = _activeCanvasId.asStateFlow()

    private val _lyricsCandidates = MutableStateFlow<List<LyricsCandidate>>(emptyList())
    val lyricsCandidates: StateFlow<List<LyricsCandidate>> = _lyricsCandidates.asStateFlow()

    private val _isSearchingLyrics = MutableStateFlow(false)
    val isSearchingLyrics: StateFlow<Boolean> = _isSearchingLyrics.asStateFlow()

    private val _lyricsSearchStatus = MutableStateFlow<String?>(null)
    val lyricsSearchStatus: StateFlow<String?> = _lyricsSearchStatus.asStateFlow()

    private val _lastPlaybackStoppedElapsedMs = MutableStateFlow(0L)
    val lastPlaybackStoppedElapsedMs: StateFlow<Long> = _lastPlaybackStoppedElapsedMs.asStateFlow()

    private val _detectedMediaApps = MutableStateFlow<Map<String, String>>(emptyMap())
    val detectedMediaApps: StateFlow<Map<String, String>> = _detectedMediaApps.asStateFlow()

    private val recentTrackHdArtCache = object : LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 20
    }

    private val savedCoverBitmapCache = object : LinkedHashMap<String, Bitmap>(14, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 10
    }

    private val savedCanvasThumbCache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 8
    }

    private var prewarmJob: Job? = null
    private var sampleTracksList: List<MediaTrackInfo> = emptyList()

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized && appContext != null) {
            return
        }
        initialize(context)
    }

    @Synchronized
    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        initialized = true
        _styleConfig.value = CardStylePreferences.load(ctx)
        _favoriteTrackKeys.value = ctx
            .getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITE_KEYS, emptySet())
            .orEmpty()
            .toSet()
        SavedMediaLibrary.initialize(ctx)
        prewarmSavedMediaArtworkCache(ctx)
        checkNotificationAccess(ctx)
        if (_lastPlaybackStoppedElapsedMs.value == 0L && _currentTrack.value.isSample) {
            _lastPlaybackStoppedElapsedMs.value = SystemClock.elapsedRealtime()
        }

        val initialTrack = MediaTrackInfo(
            title = "SICKO MODE",
            artist = "Travis Scott",
            album = "ASTROWORLD",
            appName = "EchoSnap",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true,
            durationMs = 138_000L,
            positionMs = 29_000L
        )

        val weekndStub = MediaTrackInfo(
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            appName = "EchoSnap",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true,
            durationMs = 200_000L,
            positionMs = 42_000L
        )
        val daftStub = MediaTrackInfo(
            title = "Get Lucky (feat. Pharrell)",
            artist = "Daft Punk",
            album = "Random Access Memories",
            appName = "EchoSnap",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true,
            durationMs = 248_000L,
            positionMs = 54_000L
        )
        val billieStub = MediaTrackInfo(
            title = "BIRDS OF A FEATHER",
            artist = "Billie Eilish",
            album = "HIT ME HARD AND SOFT",
            appName = "EchoSnap",
            packageName = "",
            albumArt = null,
            isPlaying = true,
            isSample = true,
            durationMs = 210_000L,
            positionMs = 31_000L
        )

        sampleTracksList = listOf(initialTrack, weekndStub, daftStub, billieStub)
        _currentTrack.value = initialTrack
        _playbackProgress.value = MediaPlaybackProgress(
            positionMs = initialTrack.positionMs,
            durationMs = initialTrack.durationMs,
            playbackSpeed = 1f,
            lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
            isPlaying = initialTrack.isPlaying
        )
        _selectedCoverId.value = "player"

        // Decode bundled sample artwork & build fallback cards off the main thread so cold launch stays instant
        val maxCoverDim = DevicePerformanceTier.recommendedCoverMaxDim(ctx)
        repositoryScope.launch(Dispatchers.Default) {
            val travisArt = BitmapBlurUtils.decodeSampledResource(
                ctx.resources,
                R.drawable.sample_art_travis,
                maxDimension = maxCoverDim
            ) ?: createFallbackArt("Travis Scott", 0xFF9E1B32.toInt(), 0xFF191414.toInt())

            val theWeekndArt = createFallbackArt("The Weeknd", 0xFFE50914.toInt(), 0xFF0D0D1A.toInt())
            val daftPunkArt = createFallbackArt("Daft Punk", 0xFFFFD700.toInt(), 0xFF1A1A2E.toInt())
            val billieArt = createFallbackArt("Billie Eilish", 0xFF00C9FF.toInt(), 0xFF0B192C.toInt())

            val hydratedInitial = initialTrack.copy(albumArt = travisArt)
            val hydratedSamples = listOf(
                hydratedInitial,
                weekndStub.copy(albumArt = theWeekndArt),
                daftStub.copy(albumArt = daftPunkArt),
                billieStub.copy(albumArt = billieArt)
            )
            val palette = PaletteColorExtractor.extractColors(travisArt)

            withContext(Dispatchers.Main) {
                sampleTracksList = hydratedSamples
                val active = _currentTrack.value
                if (active.isSample && active.albumArt == null) {
                    val matchedSample = hydratedSamples.firstOrNull { it.title == active.title } ?: hydratedInitial
                    _currentTrack.value = matchedSample
                    _originalPlayerArt.value = matchedSample.albumArt
                    _extractedPalette.value = palette
                }
            }
        }
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

        val savedCanvasMatch = if (!isSameTrack || (_activeCanvasId.value == "none" && userManuallySelectedCanvasTrackKey != newKey)) {
            SavedMediaLibrary.findMatchingSavedCanvasForTrack(
                title = trackInfo.title,
                album = trackInfo.album,
                artist = trackInfo.artist
            )
        } else {
            null
        }
        val savedCoverMatch = if (!isSameTrack || (_selectedCoverId.value == "player" && userManuallySelectedCoverTrackKey != newKey)) {
            SavedMediaLibrary.findMatchingSavedCoverForTrack(
                title = trackInfo.title,
                album = trackInfo.album,
                artist = trackInfo.artist
            )
        } else {
            null
        }

        if (!isSameTrack) {
            canvasSearchJob?.cancel()
            applyCanvasJob?.cancel()
            onlineSearchJob?.cancel()
            lowResCoverUpgradeJob?.cancel()
            _originalPlayerArt.value = trackInfo.albumArt
            if (trackInfo.durationMs <= 0L && trackInfo.positionMs <= 0L) {
                _playbackProgress.value = MediaPlaybackProgress(
                    positionMs = 0L,
                    durationMs = 0L,
                    playbackSpeed = if (trackInfo.isPlaying) 1f else 0f,
                    lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
                    isPlaying = trackInfo.isPlaying
                )
            }
            _selectedCoverId.value = "player"
            _onlineCoverCandidates.value = emptyList()
            _animatedCanvasCandidates.value = emptyList()
            _canvasSearchStatus.value = null
            _lyricsCandidates.value = emptyList()
            _lyricsSearchStatus.value = null
            userManuallySelectedCoverTrackKey = null
            userManuallySelectedCanvasTrackKey = null
            autoAppliedSavedCoverTrackKey = null
            lastAutoSearchedTrackKey = null
            lastAutoUpgradedLowResTrackKey = null
            lastAutoCanvasTrackKey = null
            coverLoadJob?.cancel()

            if (savedCanvasMatch != null) {
                autoAppliedSavedCanvasTrackKey = newKey
                _activeCanvasId.value = "saved_canvas_${savedCanvasMatch.id}"
                _activeCanvasUrl.value = savedCanvasMatch.fileUri
                _canvasSearchStatus.value = if (savedCanvasMatch.linkedAlbumName.isNotBlank()) {
                    "Offline album canvas: ${savedCanvasMatch.linkedAlbumName}"
                } else {
                    "Saved offline canvas: ${savedCanvasMatch.name}"
                }
            } else {
                autoAppliedSavedCanvasTrackKey = null
                _activeCanvasId.value = "none"
                _activeCanvasUrl.value = null
            }
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
        val cachedHdArt = synchronized(recentTrackHdArtCache) {
            recentTrackHdArtCache[normalizedArtCacheKey(trackInfo.title, trackInfo.artist)]
        }?.takeIf { !it.isRecycled }

        if (incomingArt != null && !incomingArt.isRecycled && isSquareHighResArt(incomingArt)) {
            cacheHdArtForTrack(trackInfo.title, trackInfo.artist, incomingArt)
        }

        val cachedSavedCoverBmp = savedCoverMatch?.let { match ->
            synchronized(savedCoverBitmapCache) { savedCoverBitmapCache[match.id] }
        }?.takeIf { !it.isRecycled }
        val cachedSavedCanvasThumb = savedCanvasMatch?.let { match ->
            synchronized(savedCanvasThumbCache) { savedCanvasThumbCache[match.id] }
        }?.takeIf { !it.isRecycled }

        val resolvedArt = when {
            isSameTrack && (userManuallySelectedCoverTrackKey == newKey || autoAppliedSavedCoverTrackKey == newKey) &&
                currentArt != null && !currentArt.isRecycled -> {
                // Respect user's manual or linked offline cover art choice for the active song
                currentArt
            }
            cachedSavedCoverBmp != null -> {
                // Instant 0ms offline Saved Cover Art (prevents choppy player art or black flash)
                autoAppliedSavedCoverTrackKey = newKey
                _selectedCoverId.value = "saved_cover_${savedCoverMatch!!.id}"
                cachedSavedCoverBmp
            }
            isSameTrack && currentArt != null && !currentArt.isRecycled -> {
                if (incomingArt == null || incomingArt.isRecycled) {
                    currentArt
                } else {
                    val curPixels = currentArt.width.toLong() * currentArt.height.toLong()
                    val incPixels = incomingArt.width.toLong() * incomingArt.height.toLong()
                    val curSquare = isNearSquareArt(currentArt)
                    val incSquare = isNearSquareArt(incomingArt)
                    when {
                        incSquare && !curSquare -> incomingArt
                        curSquare && !incSquare && maxOf(currentArt.width, currentArt.height) >= 300 -> currentArt
                        curPixels > incPixels -> currentArt
                        else -> incomingArt
                    }
                }
            }
            incomingArt != null && !incomingArt.isRecycled -> {
                val incMax = maxOf(incomingArt.width, incomingArt.height)
                if (cachedHdArt != null && (!isNearSquareArt(incomingArt) || incMax < 600)) {
                    cachedHdArt
                } else {
                    incomingArt
                }
            }
            else -> cachedHdArt ?: cachedSavedCanvasThumb
        }

        val finalTrack = if (resolvedArt !== trackInfo.albumArt) {
            trackInfo.copy(albumArt = resolvedArt)
        } else {
            trackInfo
        }
        val artChanged = finalTrack.albumArt !== current.albumArt
        _currentTrack.value = finalTrack
        if (!finalTrack.isSample) {
            if (finalTrack.isPlaying) {
                _lastPlaybackStoppedElapsedMs.value = 0L
            } else if (_lastPlaybackStoppedElapsedMs.value == 0L || current.isPlaying) {
                _lastPlaybackStoppedElapsedMs.value = android.os.SystemClock.elapsedRealtime()
            }
        }
        if (!isSameTrack || artChanged) {
            extractPaletteForTrack(finalTrack)
        }

        // Offline Saved Album / Song Canvas auto-apply if same-track had none previously
        if (isSameTrack && savedCanvasMatch != null) {
            autoAppliedSavedCanvasTrackKey = newKey
            _activeCanvasId.value = "saved_canvas_${savedCanvasMatch.id}"
            _activeCanvasUrl.value = savedCanvasMatch.fileUri
            _canvasSearchStatus.value = if (savedCanvasMatch.linkedAlbumName.isNotBlank()) {
                "Offline album canvas: ${savedCanvasMatch.linkedAlbumName}"
            } else {
                "Saved offline canvas: ${savedCanvasMatch.name}"
            }
        }

        // Offline Saved Album / Song Cover Art auto-apply (highest priority & works 100% offline)
        if (savedCoverMatch != null && cachedSavedCoverBmp == null) {
            autoAppliedSavedCoverTrackKey = newKey
            applySavedCoverForTrackAsync(finalTrack, savedCoverMatch, isAutoApply = true)
        }

        // If Auto-Search Online HD Cover is enabled, look up HD candidates automatically for new live tracks
        // (unless a linked offline Saved Album cover already matched for this track)
        if (!finalTrack.isSample &&
            _styleConfig.value.autoSearchOnlineArt &&
            lastAutoSearchedTrackKey != newKey
        ) {
            lastAutoSearchedTrackKey = newKey
            searchOnlineCovers(autoApplyBest = autoAppliedSavedCoverTrackKey != newKey)
        } else if (!finalTrack.isSample &&
            autoAppliedSavedCoverTrackKey != newKey &&
            userManuallySelectedCoverTrackKey != newKey &&
            lastAutoUpgradedLowResTrackKey != newKey
        ) {
            val artMaxSide = finalTrack.albumArt?.takeIf { !it.isRecycled }
                ?.let { maxOf(it.width, it.height) } ?: 0
            val artIsSquare = finalTrack.albumArt?.let { isNearSquareArt(it) } == true
            if (artMaxSide < 800 || !artIsSquare) {
                lastAutoUpgradedLowResTrackKey = newKey
                upgradeLowResArtworkSilently(finalTrack)
            }
        }

        // If Auto-Search Animated Canvas OR Live Wallpaper Animated Canvas mode is enabled,
        // look up looping canvases automatically when no offline saved album/song canvas matched
        val cfgSnapshot = _styleConfig.value
        val needsWallpaperCanvas = cfgSnapshot.liveWallpaperEnabled &&
            cfgSnapshot.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS
        if (!finalTrack.isSample &&
            (cfgSnapshot.autoSearchAnimatedCanvas || needsWallpaperCanvas) &&
            autoAppliedSavedCanvasTrackKey != newKey &&
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
            if (userManuallySelectedCoverTrackKey == key || autoAppliedSavedCoverTrackKey == key) return

            val existing = current.albumArt
            val existingPixels = if (existing != null && !existing.isRecycled) {
                existing.width.toLong() * existing.height.toLong()
            } else {
                0L
            }
            val newPixels = albumArt.width.toLong() * albumArt.height.toLong()
            val existingIsSquare = existing != null && !existing.isRecycled && isNearSquareArt(existing)
            val newIsSquare = isNearSquareArt(albumArt)
            if (existingPixels == 0L ||
                (newIsSquare && !existingIsSquare) ||
                (newPixels >= existingPixels && (newIsSquare || !existingIsSquare))
            ) {
                cacheHdArtForTrack(title, artist, albumArt)
                val updated = current.copy(albumArt = albumArt)
                _currentTrack.value = updated
                extractPaletteForTrack(updated)
            }
        }
    }

    fun refreshFromActiveMedia(context: Context, force: Boolean = false): Boolean {
        checkNotificationAccess(context)
        ensureFreshSpotifySessionSilently()
        if (!_isNotificationAccessEnabled.value) {
            return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastMediaRefreshMs < 350L && !_currentTrack.value.isSample) {
            return true
        }
        lastMediaRefreshMs = now
        return MusicNotificationListenerService.syncNow(context)
    }

    fun markCurrentTrackStopped() {
        val cur = _currentTrack.value
        val curProg = _playbackProgress.value
        val frozenPos = curProg.currentPositionAt(SystemClock.elapsedRealtime())
        _playbackProgress.value = curProg.copy(
            positionMs = frozenPos,
            lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
            isPlaying = false
        )
        if (!cur.isSample && cur.isPlaying) {
            _currentTrack.value = cur.copy(isPlaying = false, positionMs = frozenPos)
        }
        if (_lastPlaybackStoppedElapsedMs.value == 0L || cur.isPlaying) {
            _lastPlaybackStoppedElapsedMs.value = SystemClock.elapsedRealtime()
        }
    }

    fun updatePlaybackProgress(
        positionMs: Long,
        durationMs: Long,
        playbackSpeed: Float,
        lastUpdateElapsedMs: Long,
        isPlaying: Boolean
    ) {
        val safeDur = durationMs.coerceAtLeast(0L)
        val safePos = if (safeDur > 0L) positionMs.coerceIn(0L, safeDur) else positionMs.coerceAtLeast(0L)
        _playbackProgress.value = MediaPlaybackProgress(
            positionMs = safePos,
            durationMs = safeDur,
            playbackSpeed = if (playbackSpeed <= 0f) 1f else playbackSpeed,
            lastUpdateElapsedMs = if (lastUpdateElapsedMs > 0L) lastUpdateElapsedMs else SystemClock.elapsedRealtime(),
            isPlaying = isPlaying
        )
    }

    fun favoriteKeyFor(track: MediaTrackInfo): String =
        "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"

    fun isTrackFavorited(
        track: MediaTrackInfo = _currentTrack.value,
        keys: Set<String> = _favoriteTrackKeys.value
    ): Boolean = favoriteKeyFor(track) in keys

    fun toggleFavoriteCurrentTrack(context: Context): Boolean {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        val key = favoriteKeyFor(_currentTrack.value)
        val current = _favoriteTrackKeys.value
        val nowFavorited = key !in current
        val next = if (nowFavorited) current + key else current - key
        _favoriteTrackKeys.value = next
        ctx.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITE_KEYS, next)
            .apply()
        if (!_currentTrack.value.isSample) {
            MusicNotificationListenerService.sendHeartRatingIfSupported(ctx, nowFavorited)
        }
        return nowFavorited
    }

    fun togglePlayPauseCurrentTrack(context: Context) {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        val cur = _currentTrack.value
        val now = SystemClock.elapsedRealtime()
        val curProg = _playbackProgress.value
        val snapshotPos = curProg.currentPositionAt(now)
        val dispatched = if (!cur.isSample) {
            MusicNotificationListenerService.togglePlayPause(ctx)
        } else {
            false
        }
        val nextPlaying = !cur.isPlaying
        _playbackProgress.value = curProg.copy(
            positionMs = snapshotPos,
            lastUpdateElapsedMs = now,
            isPlaying = nextPlaying
        )
        _currentTrack.value = cur.copy(isPlaying = nextPlaying, positionMs = snapshotPos)
        if (!cur.isSample) {
            if (nextPlaying) {
                _lastPlaybackStoppedElapsedMs.value = 0L
            } else {
                _lastPlaybackStoppedElapsedMs.value = now
            }
            if (dispatched) {
                repositoryScope.launch {
                    delay(180)
                    MusicNotificationListenerService.syncNow(ctx)
                }
            }
        }
    }

    fun skipNextCurrentTrack(context: Context) {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        val cur = _currentTrack.value
        if (!cur.isSample) {
            if (MusicNotificationListenerService.skipToNext(ctx)) {
                repositoryScope.launch {
                    delay(220)
                    MusicNotificationListenerService.syncNow(ctx)
                }
            }
            return
        }
        val list = sampleTracksList
        if (list.isNotEmpty()) {
            val idx = list.indexOfFirst { it.title == cur.title && it.artist == cur.artist }
            val nextTrack = list[if (idx < 0) 0 else (idx + 1) % list.size]
            selectSampleTrack(nextTrack)
        }
    }

    fun skipPreviousCurrentTrack(context: Context) {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        val cur = _currentTrack.value
        if (!cur.isSample) {
            if (MusicNotificationListenerService.skipToPrevious(ctx)) {
                repositoryScope.launch {
                    delay(220)
                    MusicNotificationListenerService.syncNow(ctx)
                }
            }
            return
        }
        val list = sampleTracksList
        if (list.isNotEmpty()) {
            val idx = list.indexOfFirst { it.title == cur.title && it.artist == cur.artist }
            val prevTrack = list[if (idx <= 0) list.lastIndex else (idx - 1)]
            selectSampleTrack(prevTrack)
        }
    }

    fun seekCurrentTrackTo(context: Context, targetPositionMs: Long) {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        val curProg = _playbackProgress.value
        val cur = _currentTrack.value
        val maxDur = curProg.durationMs.coerceAtLeast(cur.durationMs).coerceAtLeast(0L)
        val clamped = if (maxDur > 0L) targetPositionMs.coerceIn(0L, maxDur) else targetPositionMs.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        _playbackProgress.value = curProg.copy(
            positionMs = clamped,
            durationMs = maxDur,
            lastUpdateElapsedMs = now
        )
        _currentTrack.value = cur.copy(positionMs = clamped, durationMs = maxDur)
        if (!cur.isSample) {
            MusicNotificationListenerService.seekTo(ctx, clamped)
        }
    }

    fun triggerWallpaperMediaSync(
        context: Context,
        forceCanvasRetry: Boolean = false
    ) {
        val ctx = context.applicationContext
        ensureInitialized(ctx)
        SavedMediaLibrary.initialize(ctx)
        checkNotificationAccess(ctx)
        if (_isNotificationAccessEnabled.value && !MusicNotificationListenerService.hasActiveListenerBinding()) {
            MusicNotificationListenerService.rebindIfPossible(ctx)
        }
        refreshFromActiveMedia(ctx, force = true)
        recheckSavedAlbumCanvasForCurrentTrack()
        recheckSavedAlbumCoverForCurrentTrack()

        val cfg = _styleConfig.value
        val track = _currentTrack.value
        val key = trackKey(track)
        if (forceCanvasRetry && _activeCanvasUrl.value.isNullOrBlank()) {
            lastAutoCanvasTrackKey = null
        }
        if (cfg.liveWallpaperEnabled &&
            cfg.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS &&
            !track.isSample &&
            _activeCanvasId.value == "none" &&
            autoAppliedSavedCanvasTrackKey != key &&
            lastAutoCanvasTrackKey != key
        ) {
            lastAutoCanvasTrackKey = key
            searchAnimatedCanvases(autoApplyBest = true)
        }
    }

    fun selectSampleTrack(track: MediaTrackInfo) {
        coverLoadJob?.cancel()
        canvasSearchJob?.cancel()
        applyCanvasJob?.cancel()
        onlineSearchJob?.cancel()
        lowResCoverUpgradeJob?.cancel()
        _originalPlayerArt.value = track.albumArt
        _selectedCoverId.value = "player"
        _onlineCoverCandidates.value = emptyList()
        _animatedCanvasCandidates.value = emptyList()
        _canvasSearchStatus.value = null
        _activeCanvasUrl.value = null
        _activeCanvasId.value = "none"
        userManuallySelectedCoverTrackKey = null
        userManuallySelectedCanvasTrackKey = null
        autoAppliedSavedCanvasTrackKey = null
        autoAppliedSavedCoverTrackKey = null
        lastAutoSearchedTrackKey = null
        lastAutoUpgradedLowResTrackKey = null
        lastAutoCanvasTrackKey = null

        val key = trackKey(track)
        val savedMatch = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
            title = track.title,
            album = track.album,
            artist = track.artist
        )
        if (savedMatch != null) {
            autoAppliedSavedCanvasTrackKey = key
            _activeCanvasId.value = "saved_canvas_${savedMatch.id}"
            _activeCanvasUrl.value = savedMatch.fileUri
        }

        _currentTrack.value = track
        _playbackProgress.value = MediaPlaybackProgress(
            positionMs = track.positionMs.coerceAtLeast(0L),
            durationMs = track.durationMs.coerceAtLeast(138_000L),
            playbackSpeed = 1f,
            lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
            isPlaying = track.isPlaying
        )
        extractPaletteForTrack(track)

        val savedCoverMatch = SavedMediaLibrary.findMatchingSavedCoverForTrack(
            title = track.title,
            album = track.album,
            artist = track.artist
        )
        if (savedCoverMatch != null) {
            autoAppliedSavedCoverTrackKey = key
            applySavedCoverForTrackAsync(track, savedCoverMatch, isAutoApply = true)
        }

        val cfg = _styleConfig.value
        val needsWallpaperCanvas = cfg.liveWallpaperEnabled &&
            cfg.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS
        if ((cfg.autoSearchAnimatedCanvas || needsWallpaperCanvas) &&
            autoAppliedSavedCanvasTrackKey != key
        ) {
            lastAutoCanvasTrackKey = key
            searchAnimatedCanvases(autoApplyBest = true)
        }
    }

    fun recheckSavedAlbumCanvasForCurrentTrack() {
        val track = _currentTrack.value
        val key = trackKey(track)
        val savedMatch = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
            title = track.title,
            album = track.album,
            artist = track.artist
        )
        if (savedMatch != null) {
            autoAppliedSavedCanvasTrackKey = key
            _activeCanvasId.value = "saved_canvas_${savedMatch.id}"
            _activeCanvasUrl.value = savedMatch.fileUri
        } else if (autoAppliedSavedCanvasTrackKey == key && _activeCanvasId.value.startsWith("saved_canvas_")) {
            autoAppliedSavedCanvasTrackKey = null
            _activeCanvasId.value = "none"
            _activeCanvasUrl.value = null
        }
    }

    fun recheckSavedAlbumCoverForCurrentTrack() {
        val track = _currentTrack.value
        val key = trackKey(track)
        val savedMatch = SavedMediaLibrary.findMatchingSavedCoverForTrack(
            title = track.title,
            album = track.album,
            artist = track.artist
        )
        if (savedMatch != null) {
            autoAppliedSavedCoverTrackKey = key
            applySavedCoverForTrackAsync(track, savedMatch, isAutoApply = true)
        } else if (autoAppliedSavedCoverTrackKey == key && _selectedCoverId.value.startsWith("saved_cover_")) {
            autoAppliedSavedCoverTrackKey = null
            restoreOriginalPlayerCover()
        }
    }

    fun prewarmSavedMediaArtworkCache(context: Context? = appContext) {
        val ctx = context?.applicationContext ?: return
        val coversSnapshot = SavedMediaLibrary.savedCovers.value
        val canvasesSnapshot = SavedMediaLibrary.savedCanvases.value
        if (coversSnapshot.isEmpty() && canvasesSnapshot.isEmpty()) return

        prewarmJob?.cancel()
        prewarmJob = repositoryScope.launch(Dispatchers.IO) {
            val tier = DevicePerformanceTier.get(ctx)
            val maxItems = if (tier.isLowRam) 4 else 8
            val maxCoverDim = maxOf(tier.cardArtMaxDim, 1200)

            coversSnapshot.take(maxItems).forEach { item ->
                val cached = synchronized(savedCoverBitmapCache) { savedCoverBitmapCache[item.id] }
                if (cached == null || cached.isRecycled) {
                    val decoded = SavedMediaLibrary.loadBitmap(item.localImagePath, maxDimension = maxCoverDim)
                    if (decoded != null && !decoded.isRecycled) {
                        synchronized(savedCoverBitmapCache) {
                            savedCoverBitmapCache[item.id] = decoded
                        }
                    }
                }
            }

            canvasesSnapshot.take(maxItems).forEach { item ->
                val thumbPath = item.thumbnailPath ?: return@forEach
                val cached = synchronized(savedCanvasThumbCache) { savedCanvasThumbCache[item.id] }
                if (cached == null || cached.isRecycled) {
                    val decoded = SavedMediaLibrary.loadBitmap(thumbPath, maxDimension = maxCoverDim)
                    if (decoded != null && !decoded.isRecycled) {
                        synchronized(savedCanvasThumbCache) {
                            savedCanvasThumbCache[item.id] = decoded
                        }
                    }
                }
            }
        }
    }

    private fun applySavedCoverForTrackAsync(
        trackSnapshot: MediaTrackInfo,
        matchedCover: SavedCoverItem,
        isAutoApply: Boolean
    ) {
        val expectedKey = trackKey(trackSnapshot)
        coverLoadJob?.cancel()
        coverLoadJob = repositoryScope.launch {
            val cached = synchronized(savedCoverBitmapCache) { savedCoverBitmapCache[matchedCover.id] }
                ?.takeIf { !it.isRecycled }
            val maxCoverDim = maxOf(DevicePerformanceTier.recommendedCoverMaxDim(appContext), 1200)
            val bmp = cached ?: withContext(Dispatchers.IO) {
                SavedMediaLibrary.loadBitmap(matchedCover.localImagePath, maxDimension = maxCoverDim)
            } ?: return@launch
            synchronized(savedCoverBitmapCache) {
                savedCoverBitmapCache[matchedCover.id] = bmp
            }
            cacheHdArtForTrack(trackSnapshot.title, trackSnapshot.artist, bmp)
            val current = _currentTrack.value
            if (trackKey(current) != expectedKey) return@launch
            if (isAutoApply && userManuallySelectedCoverTrackKey == expectedKey) return@launch
            _selectedCoverId.value = "saved_cover_${matchedCover.id}"
            val updated = current.copy(albumArt = bmp)
            _currentTrack.value = updated
            extractPaletteForTrack(updated)
        }
    }

    fun ensureFreshSpotifySessionSilently() {
        val cfg = _styleConfig.value
        val spDc = SpotifyAuthManager.extractSpDcValue(cfg.spotifySpDcCookie)
            .ifBlank { SpotifyAuthManager.readLiveSpDcFromCookieManager() }
        val hasRefreshCred = spDc.length >= 20 ||
            (cfg.spotifyRefreshToken.isNotBlank() && cfg.spotifyClientId.isNotBlank())
        if (!hasRefreshCred) return

        val needsRefresh = AnimatedCanvasSearchService.shouldRefreshSpotifyToken(
            hasActiveToken = cfg.spotifyAccessToken.isNotBlank(),
            tokenExpiryMs = cfg.spotifyTokenExpiryMs,
            lastRefreshedAtMs = cfg.spotifyTokenLastRefreshedMs,
            hasRefreshCredential = true
        )
        if (!needsRefresh || proactiveSessionRefreshJob?.isActive == true) return

        proactiveSessionRefreshJob = repositoryScope.launch(Dispatchers.IO) {
            var renewed: com.example.service.SpotifyTokenResult? = null
            var loginRedirect = false
            if (spDc.length >= 20) {
                renewed = SpotifyAuthManager.exchangeSpDcForAccessToken(spDc)
                if (renewed == null) {
                    val ctx = appContext
                    if (ctx != null) {
                        val detailed = SpotifyAuthManager.exchangeSpDcViaWebViewDetailed(ctx, spDc)
                        renewed = detailed.tokenResult
                        loginRedirect = detailed.loginRedirectDetected
                    }
                }
            }
            if (renewed == null && cfg.spotifyRefreshToken.isNotBlank() && cfg.spotifyClientId.isNotBlank()) {
                renewed = SpotifyAuthManager.refreshPkceToken(cfg.spotifyClientId, cfg.spotifyRefreshToken)
            }
            withContext(Dispatchers.Main) {
                if (renewed != null) {
                    val nowMs = System.currentTimeMillis()
                    updateStyleConfig(saveImmediately = true) { current ->
                        current.copy(
                            spotifyAccessToken = renewed.accessToken,
                            spotifySpDcCookie = renewed.spDcCookie.ifBlank { current.spotifySpDcCookie },
                            spotifyRefreshToken = renewed.refreshToken.ifBlank { current.spotifyRefreshToken },
                            spotifyTokenExpiryMs = renewed.expiresAtMs,
                            spotifyTokenLastRefreshedMs = nowMs
                        )
                    }
                    _spotifyReloginRequired.value = false
                } else if (loginRedirect) {
                    _spotifyReloginRequired.value = true
                }
            }
        }
    }

    fun getSampleTracks(): List<MediaTrackInfo> = sampleTracksList

    fun updateStyleConfig(
        saveImmediately: Boolean = false,
        update: (CardStyleConfig) -> CardStyleConfig
    ) {
        val previous = _styleConfig.value
        val updated = update(previous)
        if (updated == previous) return
        _styleConfig.value = updated

        val autoCanvasJustEnabled = !previous.autoSearchAnimatedCanvas && updated.autoSearchAnimatedCanvas
        val wallpaperCanvasJustEnabled = updated.liveWallpaperEnabled &&
            updated.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS &&
            (!previous.liveWallpaperEnabled || previous.liveWallpaperMode != LiveWallpaperMode.ANIMATED_CANVAS)
        if (autoCanvasJustEnabled || wallpaperCanvasJustEnabled) {
            triggerAutoCanvasForCurrentTrackIfNeeded()
        }

        val ctx = appContext ?: return
        prefsSaveJob?.cancel()
        prefsSaveJob = repositoryScope.launch(Dispatchers.IO) {
            if (!saveImmediately) {
                delay(200)
            }
            CardStylePreferences.save(ctx, _styleConfig.value)
        }
        val onlySpotifyTokenChanged = previous.spotifyAccessToken != updated.spotifyAccessToken &&
            previous.copy(
                spotifyAccessToken = updated.spotifyAccessToken,
                spotifySpDcCookie = updated.spotifySpDcCookie,
                spotifyRefreshToken = updated.spotifyRefreshToken,
                spotifyTokenExpiryMs = updated.spotifyTokenExpiryMs,
                spotifyTokenLastRefreshedMs = updated.spotifyTokenLastRefreshedMs,
                spotifyAccountLabel = updated.spotifyAccountLabel
            ) == updated
        if (!onlySpotifyTokenChanged && updated.backupAutoSyncEnabled) {
            EchoSnapBackupManager.scheduleAutoBackupIfEnabled(ctx)
        }
    }

    fun recordDetectedMediaApp(packageName: String, appLabel: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val label = appLabel.trim().ifBlank { pkg }
        val existing = _detectedMediaApps.value
        if (existing[pkg] == label) return
        _detectedMediaApps.value = existing + (pkg to label)
    }

    fun toggleIgnoredMediaPackage(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        updateStyleConfig(saveImmediately = true) { current ->
            val nextSet = if (pkg in current.ignoredMediaPackages) {
                current.ignoredMediaPackages - pkg
            } else {
                current.ignoredMediaPackages + pkg
            }
            current.copy(ignoredMediaPackages = nextSet)
        }
        val active = _currentTrack.value
        if (!active.isSample && active.packageName == pkg && pkg in _styleConfig.value.ignoredMediaPackages) {
            markCurrentTrackStopped()
            val ctx = appContext
            if (ctx != null) {
                val resynced = MusicNotificationListenerService.syncNow(ctx)
                if (!resynced) {
                    sampleTracksList.firstOrNull()?.let { selectSampleTrack(it) }
                }
            }
        }
    }

    fun prewarmQueueTrackArtwork(title: String, artist: String, album: String = "") {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val ctx = appContext ?: return
        val matchedCover = SavedMediaLibrary.findMatchingSavedCoverForTrack(
            title = cleanTitle,
            album = album,
            artist = artist
        )
        val matchedCanvas = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
            title = cleanTitle,
            album = album,
            artist = artist
        )
        if (matchedCover == null && matchedCanvas == null) return

        repositoryScope.launch(Dispatchers.IO) {
            val maxDim = maxOf(DevicePerformanceTier.recommendedCoverMaxDim(ctx), 1200)
            if (matchedCover != null) {
                val cached = synchronized(savedCoverBitmapCache) { savedCoverBitmapCache[matchedCover.id] }
                if (cached == null || cached.isRecycled) {
                    val bmp = SavedMediaLibrary.loadBitmap(matchedCover.localImagePath, maxDimension = maxDim)
                    if (bmp != null && !bmp.isRecycled) {
                        synchronized(savedCoverBitmapCache) {
                            savedCoverBitmapCache[matchedCover.id] = bmp
                        }
                        cacheHdArtForTrack(cleanTitle, artist, bmp)
                    }
                }
            }
            val thumbPath = matchedCanvas?.thumbnailPath
            if (matchedCanvas != null && !thumbPath.isNullOrBlank()) {
                val cached = synchronized(savedCanvasThumbCache) { savedCanvasThumbCache[matchedCanvas.id] }
                if (cached == null || cached.isRecycled) {
                    val bmp = SavedMediaLibrary.loadBitmap(thumbPath, maxDimension = maxDim)
                    if (bmp != null && !bmp.isRecycled) {
                        synchronized(savedCanvasThumbCache) {
                            savedCanvasThumbCache[matchedCanvas.id] = bmp
                        }
                    }
                }
            }
        }
    }

    fun triggerAutoCanvasForCurrentTrackIfNeeded(customQuery: String? = null) {
        recheckSavedAlbumCanvasForCurrentTrack()
        val track = _currentTrack.value
        val key = trackKey(track)
        if (autoAppliedSavedCanvasTrackKey == key && !_activeCanvasUrl.value.isNullOrBlank()) {
            return
        }
        val existingCandidates = _animatedCanvasCandidates.value
        val hasCustomOverride = !customQuery.isNullOrBlank() &&
            !customQuery.trim().equals("${track.title} ${track.artist}".trim(), ignoreCase = true)
        if (!hasCustomOverride && existingCandidates.isNotEmpty() && _activeCanvasId.value == "none") {
            val ordered = existingCandidates.filter { it.isLoopingVideo && !it.videoUrl.isNullOrBlank() }
                .ifEmpty { existingCandidates }
            applyCanvasJob?.cancel()
            applyCanvasJob = repositoryScope.launch {
                applyAnimatedCanvasCandidate(
                    candidate = ordered.first(),
                    markAsManual = false,
                    fallbackCandidates = ordered.drop(1)
                )
            }
            return
        }
        if (_activeCanvasId.value == "none" || _activeCanvasUrl.value.isNullOrBlank()) {
            lastAutoCanvasTrackKey = key
            userManuallySelectedCanvasTrackKey = null
            searchAnimatedCanvases(customQuery = customQuery, autoApplyBest = true)
        }
    }

    fun flushStylePreferences() {
        val ctx = appContext ?: return
        prefsSaveJob?.cancel()
        val snapshot = _styleConfig.value
        repositoryScope.launch(Dispatchers.IO) {
            CardStylePreferences.save(ctx, snapshot)
        }
    }

    /**
     * Resets only adjusted visual slider & effect controls (size, blur, opacity, glow, waveform)
     * while preserving user's saved assets, Spotify connection, and chosen card layout preset.
     */
    fun resetStyleValues() {
        val defaults = CardStyleConfig()
        updateStyleConfig(saveImmediately = true) { current ->
            current.copy(
                backgroundBlurRadius = defaults.backgroundBlurRadius,
                cardBlurRadius = defaults.cardBlurRadius,
                lyricsArtBlurRadius = defaults.lyricsArtBlurRadius,
                cardScale = defaults.cardScale,
                cardOpacity = defaults.cardOpacity,
                glowIntensity = defaults.glowIntensity,
                cardShadowColorMode = defaults.cardShadowColorMode,
                customShadowColor = defaults.customShadowColor,
                cardShadowInverse = defaults.cardShadowInverse,
                darkOverlayAlpha = defaults.darkOverlayAlpha,
                cornerRadiusDp = defaults.cornerRadiusDp,
                useTransparentBackground = defaults.useTransparentBackground,
                enableWaveform = defaults.enableWaveform,
                waveformBeatSync = defaults.waveformBeatSync,
                waveformSpeedMultiplier = defaults.waveformSpeedMultiplier,
                waveformForce = defaults.waveformForce,
                waveformColor = defaults.waveformColor
            )
        }
    }

    fun onSavedMediaLibraryCleared() {
        if (_activeCanvasId.value.startsWith("saved_canvas_")) {
            autoAppliedSavedCanvasTrackKey = null
            _activeCanvasId.value = "none"
            _activeCanvasUrl.value = null
        }
        if (_selectedCoverId.value.startsWith("saved_cover_")) {
            autoAppliedSavedCoverTrackKey = null
            restoreOriginalPlayerCover()
        }
    }

    fun onSavedMediaLibraryImported() {
        prewarmSavedMediaArtworkCache()
        recheckSavedAlbumCanvasForCurrentTrack()
        recheckSavedAlbumCoverForCurrentTrack()
    }

    fun resetBackupTargets(
        context: Context,
        resetSavedLibrary: Boolean,
        resetCardStyles: Boolean
    ) {
        if (resetSavedLibrary) {
            SavedMediaLibrary.resetAllSavedMedia(context)
            onSavedMediaLibraryCleared()
        }
        if (resetCardStyles) {
            resetStyleValues()
        }
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
        coverLoadJob?.cancel()
        autoAppliedSavedCoverTrackKey = null
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
        val maxDim = maxOf(DevicePerformanceTier.recommendedCoverMaxDim(appContext), 1200)
        val hdBitmap = CoverArtSearchService.fetchFullResolutionArt(
            candidate = candidate,
            maxDimension = maxDim
        ) ?: return
        val current = _currentTrack.value
        if (markAsManual) {
            userManuallySelectedCoverTrackKey = trackKey(current)
            autoAppliedSavedCoverTrackKey = null
        }
        _selectedCoverId.value = candidate.id
        val updated = current.copy(albumArt = hdBitmap)
        _currentTrack.value = updated
        extractPaletteForTrack(updated)
    }

    private fun upgradeLowResArtworkSilently(snapshot: MediaTrackInfo) {
        val expectedKey = trackKey(snapshot)
        val searchTerm = buildSearchQuery(snapshot).trim()
        if (searchTerm.isEmpty()) return

        lowResCoverUpgradeJob?.cancel()
        lowResCoverUpgradeJob = repositoryScope.launch {
            val candidates = CoverArtSearchService.searchCoverArt(searchTerm, maxResults = 6)
            val bestMatch = CoverArtSearchService.pickBestMatchingCoverCandidate(
                candidates = candidates,
                targetTitle = snapshot.title,
                targetArtist = snapshot.artist,
                targetAlbum = snapshot.album
            ) ?: return@launch

            val hdBitmap = CoverArtSearchService.fetchFullResolutionArt(
                candidate = bestMatch,
                maxDimension = maxOf(DevicePerformanceTier.recommendedCoverMaxDim(appContext), 1200)
            ) ?: return@launch

            val current = _currentTrack.value
            if (trackKey(current) != expectedKey ||
                userManuallySelectedCoverTrackKey == expectedKey ||
                autoAppliedSavedCoverTrackKey == expectedKey
            ) {
                return@launch
            }
            val curArt = current.albumArt
            val curPixels = if (curArt != null && !curArt.isRecycled) curArt.width.toLong() * curArt.height.toLong() else 0L
            val curSquare = curArt != null && !curArt.isRecycled && isNearSquareArt(curArt)
            val hdPixels = hdBitmap.width.toLong() * hdBitmap.height.toLong()
            val hdMax = maxOf(hdBitmap.width, hdBitmap.height)
            val hdSquare = isNearSquareArt(hdBitmap)
            if (hdMax >= 600 && (hdPixels > curPixels || (hdSquare && !curSquare))) {
                cacheHdArtForTrack(current.title, current.artist, hdBitmap)
                _selectedCoverId.value = bestMatch.id
                val updated = current.copy(albumArt = hdBitmap)
                _currentTrack.value = updated
                extractPaletteForTrack(updated)
            }
        }
    }

    fun searchAnimatedCanvases(
        customQuery: String? = null,
        autoApplyBest: Boolean = false
    ) {
        val snapshot = _currentTrack.value
        val trackSnapshotKey = trackKey(snapshot)
        val trimmedCustom = customQuery?.trim().orEmpty()
        val searchTerm = (trimmedCustom.ifEmpty { buildSearchQuery(snapshot) }).trim()
        if (searchTerm.isEmpty()) return

        // If the user typed a completely different search query manually, match against what they typed
        val defaultQuery = "${snapshot.title} ${snapshot.artist}".trim()
        val cleanBuiltQuery = buildSearchQuery(snapshot)
        val isCustomOverride = trimmedCustom.isNotEmpty() &&
            !trimmedCustom.equals(defaultQuery, ignoreCase = true) &&
            !trimmedCustom.equals(cleanBuiltQuery, ignoreCase = true)

        canvasSearchJob?.cancel()
        canvasSearchJob = repositoryScope.launch {
            _isSearchingCanvas.value = true
            _canvasSearchStatus.value = "Smart searching animated canvases..."
            try {
                val cfg = _styleConfig.value
                val ctx = appContext
                val result = AnimatedCanvasSearchService.searchCanvases(
                    rawQuery = searchTerm,
                    spotifyToken = cfg.spotifyAccessToken,
                    spotifySpDcCookie = cfg.spotifySpDcCookie,
                    spotifyTokenExpiryMs = cfg.spotifyTokenExpiryMs,
                    spotifyTokenLastRefreshedMs = cfg.spotifyTokenLastRefreshedMs,
                    spotifyRefreshToken = cfg.spotifyRefreshToken,
                    spotifyClientId = cfg.spotifyClientId,
                    targetTitle = if (isCustomOverride) searchTerm else snapshot.title,
                    targetArtist = if (isCustomOverride) "" else snapshot.artist,
                    playerAppName = snapshot.appName,
                    spotifyTrackId = if (isCustomOverride) "" else snapshot.spotifyTrackId,
                    spDcWebFallback = if (ctx != null) {
                        { spDc -> com.example.service.SpotifyAuthManager.exchangeSpDcViaWebView(ctx, spDc) }
                    } else {
                        null
                    },
                    spDcWebDetailedFallback = if (ctx != null) {
                        { spDc -> com.example.service.SpotifyAuthManager.exchangeSpDcViaWebViewDetailed(ctx, spDc) }
                    } else {
                        null
                    },
                    webViewSearchFallback = if (ctx != null) {
                        { query ->
                            com.example.service.SpotifyAuthManager.searchTrackIdsViaWebView(
                                context = ctx,
                                query = query,
                                rawSpDc = _styleConfig.value.spotifySpDcCookie
                            )
                        }
                    } else {
                        null
                    },
                    maxResults = 8
                )
                result.refreshedToken?.let { renewed ->
                    val refreshedAt = System.currentTimeMillis()
                    updateStyleConfig(saveImmediately = true) { current ->
                        current.copy(
                            spotifyAccessToken = renewed.accessToken,
                            spotifySpDcCookie = renewed.spDcCookie.ifBlank { current.spotifySpDcCookie },
                            spotifyRefreshToken = renewed.refreshToken.ifBlank { current.spotifyRefreshToken },
                            spotifyTokenExpiryMs = renewed.expiresAtMs,
                            spotifyTokenLastRefreshedMs = refreshedAt
                        )
                    }
                }
                _spotifyReloginRequired.value = result.requiresInteractiveRelogin
                _animatedCanvasCandidates.value = result.candidates
                _canvasSearchStatus.value = result.statusMessage

                if (autoApplyBest &&
                    result.candidates.isNotEmpty() &&
                    trackKey(_currentTrack.value) == trackSnapshotKey &&
                    userManuallySelectedCanvasTrackKey != trackSnapshotKey &&
                    autoAppliedSavedCanvasTrackKey != trackSnapshotKey
                ) {
                    val ordered = result.candidates.filter { it.isLoopingVideo && !it.videoUrl.isNullOrBlank() }
                        .ifEmpty { result.candidates }
                    applyAnimatedCanvasCandidate(
                        candidate = ordered.first(),
                        markAsManual = false,
                        fallbackCandidates = ordered.drop(1)
                    )
                }
            } finally {
                _isSearchingCanvas.value = false
            }
        }
    }

    fun applySpotifyLoginResult(result: com.example.service.SpotifyTokenResult) {
        val nowMs = System.currentTimeMillis()
        _spotifyReloginRequired.value = false
        updateStyleConfig(saveImmediately = true) { current ->
            current.copy(
                spotifyAccessToken = result.accessToken,
                spotifySpDcCookie = result.spDcCookie.ifBlank { current.spotifySpDcCookie },
                spotifyRefreshToken = result.refreshToken.ifBlank { current.spotifyRefreshToken },
                spotifyTokenExpiryMs = result.expiresAtMs,
                spotifyTokenLastRefreshedMs = nowMs,
                spotifyAccountLabel = result.accountLabel.ifBlank { "Spotify Account" }
            )
        }
        // Automatically scratch animated canvases for the currently active song once logged in
        searchAnimatedCanvases(autoApplyBest = false)
    }

    fun connectWithSpDcCookie(
        rawSpDc: String,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val spDc = com.example.service.SpotifyAuthManager.extractSpDcValue(rawSpDc)
        if (spDc.length < 20) {
            onComplete(false, "Paste a valid Spotify sp_dc cookie value")
            return
        }
        repositoryScope.launch {
            _isSearchingCanvas.value = true
            _canvasSearchStatus.value = "Verifying Spotify sp_dc cookie..."
            try {
                var tokenResult = com.example.service.SpotifyAuthManager.exchangeSpDcForAccessToken(spDc)
                if (tokenResult == null) {
                    val ctx = appContext
                    if (ctx != null) {
                        tokenResult = com.example.service.SpotifyAuthManager.exchangeSpDcViaWebView(ctx, spDc)
                    }
                }
                if (tokenResult != null) {
                    val label = com.example.service.SpotifyAuthManager
                        .fetchSpotifyProfileLabel(tokenResult.accessToken)
                        .ifBlank { "Spotify (sp_dc)" }
                    applySpotifyLoginResult(
                        tokenResult.copy(
                            accountLabel = label,
                            spDcCookie = spDc
                        )
                    )
                    onComplete(true, "Connected ($label)")
                } else {
                    updateStyleConfig(saveImmediately = true) { current ->
                        current.copy(
                            spotifySpDcCookie = spDc,
                            spotifyAccountLabel = current.spotifyAccountLabel.ifBlank { "Spotify (sp_dc)" }
                        )
                    }
                    _canvasSearchStatus.value = "sp_dc invalid or expired — sign in with Login to Spotify"
                    onComplete(false, "Could not verify sp_dc cookie — check value or tap Login to Spotify")
                }
            } finally {
                _isSearchingCanvas.value = false
            }
        }
    }

    fun disconnectSpotifyAccount() {
        com.example.service.SpotifyAuthManager.clearSpotifyWebCookies()
        _spotifyReloginRequired.value = false
        updateStyleConfig(saveImmediately = true) { current ->
            current.copy(
                spotifyAccessToken = "",
                spotifySpDcCookie = "",
                spotifyRefreshToken = "",
                spotifyTokenExpiryMs = 0L,
                spotifyTokenLastRefreshedMs = 0L,
                spotifyClientId = current.spotifyClientId,
                spotifyAccountLabel = ""
            )
        }
        _canvasSearchStatus.value = "Spotify account disconnected"
    }

    fun selectAnimatedCanvas(candidate: AnimatedCanvasCandidate) {
        applyCanvasJob?.cancel()
        val applyTrackKey = trackKey(_currentTrack.value)
        userManuallySelectedCanvasTrackKey = applyTrackKey
        autoAppliedSavedCanvasTrackKey = null
        _activeCanvasId.value = candidate.id

        applyCanvasJob = repositoryScope.launch {
            applyAnimatedCanvasCandidate(candidate, markAsManual = true)
        }
    }

    fun clearAnimatedCanvas() {
        applyCanvasJob?.cancel()
        userManuallySelectedCanvasTrackKey = trackKey(_currentTrack.value)
        autoAppliedSavedCanvasTrackKey = null
        _activeCanvasUrl.value = null
        _activeCanvasId.value = "none"
    }

    private suspend fun applyAnimatedCanvasCandidate(
        candidate: AnimatedCanvasCandidate,
        markAsManual: Boolean,
        fallbackCandidates: List<AnimatedCanvasCandidate> = emptyList()
    ) {
        val current = _currentTrack.value
        val applyTrackKey = trackKey(current)
        if (markAsManual) {
            userManuallySelectedCanvasTrackKey = applyTrackKey
            autoAppliedSavedCanvasTrackKey = null
        }
        _activeCanvasId.value = candidate.id

        val rawUrl = candidate.videoUrl?.trim().orEmpty()
        val ctx = appContext
        val cachedReadyUri = if (rawUrl.isNotEmpty() && ctx != null && !rawUrl.startsWith("file://") && !rawUrl.startsWith("content://")) {
            CanvasVideoExporter.getCachedVideoFileIfReady(ctx, rawUrl)
                ?.let { Uri.fromFile(it).toString() }
        } else {
            null
        }

        if (rawUrl.isEmpty()) {
            if (trackKey(_currentTrack.value) == applyTrackKey && _activeCanvasId.value == candidate.id) {
                _activeCanvasUrl.value = null
            }
        } else if (cachedReadyUri != null || rawUrl.startsWith("file://") || rawUrl.startsWith("content://")) {
            val immediateUri = cachedReadyUri ?: rawUrl
            if (trackKey(_currentTrack.value) == applyTrackKey && _activeCanvasId.value == candidate.id) {
                _activeCanvasUrl.value = immediateUri
                _canvasSearchStatus.value = "Playing ${candidate.source} canvas"
            }
        } else {
            // Emit raw HTTPS URL immediately so playback begins right away instead of waiting for full download
            if (trackKey(_currentTrack.value) == applyTrackKey && _activeCanvasId.value == candidate.id) {
                _activeCanvasUrl.value = rawUrl
                _canvasSearchStatus.value = "Applying ${candidate.source} canvas..."
            }
            val cachedUri = if (ctx != null) {
                CanvasVideoExporter.resolveOrCacheVideoUri(ctx, rawUrl)
            } else {
                null
            }
            if (trackKey(_currentTrack.value) == applyTrackKey && _activeCanvasId.value == candidate.id) {
                if (cachedUri != null) {
                    _activeCanvasUrl.value = cachedUri
                    _canvasSearchStatus.value = "Playing ${candidate.source} canvas"
                } else if (!markAsManual && fallbackCandidates.isNotEmpty()) {
                    applyAnimatedCanvasCandidate(
                        candidate = fallbackCandidates.first(),
                        markAsManual = false,
                        fallbackCandidates = fallbackCandidates.drop(1)
                    )
                    return
                } else {
                    _canvasSearchStatus.value = "Streaming ${candidate.source} canvas"
                }
            }
        }

        // Like Spotify, keep the song's real album cover inside the card while the Canvas video
        // animates in the background (only populate artwork if none exists or candidate is still art).
        if (candidate.videoUrl.isNullOrBlank() || current.albumArt == null) {
            val hdFrame = AnimatedCanvasSearchService.extractHighResPosterOrFrame(candidate)
            if (hdFrame != null && trackKey(_currentTrack.value) == applyTrackKey) {
                val latest = _currentTrack.value
                val updated = latest.copy(albumArt = hdFrame)
                _currentTrack.value = updated
                extractPaletteForTrack(updated)
            }
        }
    }

    fun selectSavedCover(item: SavedCoverItem) {
        coverLoadJob?.cancel()
        repositoryScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                SavedMediaLibrary.loadBitmap(item.localImagePath, maxDimension = 1600)
            } ?: return@launch
            val current = _currentTrack.value
            userManuallySelectedCoverTrackKey = trackKey(current)
            autoAppliedSavedCoverTrackKey = null
            _selectedCoverId.value = "saved_cover_${item.id}"
            val updated = current.copy(albumArt = bmp)
            _currentTrack.value = updated
            extractPaletteForTrack(updated)
        }
    }

    fun selectSavedCanvas(item: SavedCanvasItem) {
        val current = _currentTrack.value
        userManuallySelectedCanvasTrackKey = trackKey(current)
        autoAppliedSavedCanvasTrackKey = null
        _activeCanvasId.value = "saved_canvas_${item.id}"
        _activeCanvasUrl.value = item.fileUri
    }

    fun saveOnlineCoverLocally(
        candidate: OnlineCoverCandidate,
        customName: String? = null,
        onComplete: (SavedCoverItem?) -> Unit = {}
    ) {
        val ctx = appContext ?: return onComplete(null)
        val resolvedName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: candidate.title.ifBlank { "Saved Cover" }
        repositoryScope.launch {
            val saved = SavedMediaLibrary.saveCoverFromCandidate(ctx, candidate, resolvedName)
            onComplete(saved)
        }
    }

    fun saveCurrentCoverLocally(
        customName: String? = null,
        onComplete: (SavedCoverItem?) -> Unit = {}
    ) {
        val ctx = appContext ?: return onComplete(null)
        val track = _currentTrack.value
        val bmp = track.albumArt ?: return onComplete(null)
        val name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: track.title
        repositoryScope.launch {
            val saved = SavedMediaLibrary.saveCoverFromBitmap(ctx, bmp, name, track.artist)
            onComplete(saved)
        }
    }

    fun saveOnlineCanvasLocally(
        candidate: AnimatedCanvasCandidate,
        customName: String? = null,
        onComplete: (SavedCanvasItem?) -> Unit = {}
    ) {
        val ctx = appContext ?: return onComplete(null)
        val resolvedName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: candidate.title.ifBlank { "Saved Canvas" }
        repositoryScope.launch {
            val saved = SavedMediaLibrary.saveCanvasFromCandidate(ctx, candidate, resolvedName)
            onComplete(saved)
        }
    }

    fun importLocalCoverUri(
        uri: Uri,
        customName: String? = null,
        onComplete: (SavedCoverItem?) -> Unit = {}
    ) {
        val ctx = appContext ?: return onComplete(null)
        val track = _currentTrack.value
        val fallbackName = customName?.trim()?.takeIf { it.isNotEmpty() } ?: "${track.title} Cover"
        repositoryScope.launch {
            val saved = SavedMediaLibrary.importCoverFromUri(ctx, uri, fallbackName)
            if (saved != null) {
                selectSavedCover(saved)
            }
            onComplete(saved)
        }
    }

    fun importLocalCanvasUri(
        uri: Uri,
        customName: String? = null,
        onComplete: (SavedCanvasItem?) -> Unit = {}
    ) {
        val ctx = appContext ?: return onComplete(null)
        val track = _currentTrack.value
        val fallbackName = customName?.trim()?.takeIf { it.isNotEmpty() } ?: "${track.title} Canvas"
        repositoryScope.launch {
            val saved = SavedMediaLibrary.importCanvasFromUri(ctx, uri, fallbackName)
            if (saved != null) {
                selectSavedCanvas(saved)
            }
            onComplete(saved)
        }
    }

    fun searchLyrics(customQuery: String? = null) {
        val snapshot = _currentTrack.value
        val cleanTitle = AnimatedCanvasSearchService.normalizeQueryText(snapshot.title)
            .ifBlank { snapshot.title.trim() }
        val cleanArtist = AnimatedCanvasSearchService.normalizeArtistText(snapshot.artist)
            .takeIf { !it.equals(snapshot.appName, ignoreCase = true) }
            .orEmpty()
        val rawOverride = customQuery?.trim().orEmpty()

        lyricsSearchJob?.cancel()
        lyricsSearchJob = repositoryScope.launch {
            _isSearchingLyrics.value = true
            _lyricsSearchStatus.value = "Searching Genius & ${LyricsSearchService.PROVIDERS.size - 1} lyrics providers..."
            try {
                val outcome = if (rawOverride.isNotEmpty() &&
                    !rawOverride.equals("$cleanTitle $cleanArtist".trim(), ignoreCase = true) &&
                    !rawOverride.equals(cleanTitle, ignoreCase = true)
                ) {
                    LyricsSearchService.searchAcrossProviders(
                        title = rawOverride,
                        artist = ""
                    )
                } else {
                    LyricsSearchService.searchAcrossProviders(
                        title = cleanTitle,
                        artist = cleanArtist
                    )
                }
                _lyricsCandidates.value = outcome.candidates
                _lyricsSearchStatus.value = outcome.statusMessage
            } finally {
                _isSearchingLyrics.value = false
            }
        }
    }

    fun applySelectedLyricLines(lines: List<String>) {
        val joined = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (joined.isNotEmpty()) {
            updateStyleConfig(saveImmediately = true) { it.copy(customLyrics = joined) }
        }
    }

    private fun buildSearchQuery(track: MediaTrackInfo): String {
        val cleanTitle = AnimatedCanvasSearchService.normalizeQueryText(track.title)
            .ifBlank { track.title.trim() }
        val cleanArtist = AnimatedCanvasSearchService.normalizeArtistText(track.artist)
        return if (cleanArtist.isNotEmpty() && !cleanArtist.equals(track.appName, ignoreCase = true)) {
            "$cleanTitle $cleanArtist"
        } else {
            cleanTitle
        }
    }

    private fun normalizedArtCacheKey(title: String, artist: String): String =
        "${AnimatedCanvasSearchService.normalizeQueryText(title).ifBlank { title.trim() }.lowercase()}|${AnimatedCanvasSearchService.normalizeArtistText(artist).lowercase()}"

    private fun isNearSquareArt(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return false
        val aspect = bmp.width.toFloat() / bmp.height.toFloat()
        return aspect in 0.85f..1.18f
    }

    private fun isSquareHighResArt(bmp: Bitmap): Boolean =
        isNearSquareArt(bmp) && maxOf(bmp.width, bmp.height) >= 480

    private fun cacheHdArtForTrack(title: String, artist: String, bmp: Bitmap) {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
        if (maxOf(bmp.width, bmp.height) < 400) return
        val key = normalizedArtCacheKey(title, artist)
        if (key == "|") return
        synchronized(recentTrackHdArtCache) {
            recentTrackHdArtCache[key] = bmp
        }
    }

    private fun trackKey(track: MediaTrackInfo): String =
        "${track.packageName}|${track.title}|${track.artist}"

    private fun extractPaletteForTrack(track: MediaTrackInfo) {
        val art = track.albumArt
        if (art == null) {
            paletteJob?.cancel()
            _extractedPalette.value = if (track.isSample) CardPalette.Default else CardPalette.NeutralDark
            return
        }
        paletteJob?.cancel()
        paletteJob = repositoryScope.launch {
            val palette = PaletteColorExtractor.extractColors(art)
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
