package com.example.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.content.ContextCompat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.LiveWallpaperMode
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.repository.SavedMediaLibrary
import com.example.util.BitmapBlurUtils
import com.example.util.CanvasVideoExporter
import com.example.util.CardShareManager
import com.example.util.DevicePerformanceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Battery-safe, zero-lag EGL14/GLES20 Live Wallpaper engine for EchoSnap.
 *
 * Key architecture highlights (optimized for Samsung Tab A7 LTE, Galaxy A7 2018, Redmi 9C):
 * - Supports both Artwork Only and Animated Canvas modes with automatic Artwork fallback until
 *   an animated canvas is found (or stays clean crisp artwork when offline / none exists).
 * - Uses 2-pass downsampled FBO Ping-Pong Gaussian Blur + smooth Top & Bottom Edge Blur Fade
 *   (softly frosted top status/clock band & bottom dock band even when center blur is 0px).
 * - Optional centered EchoSnap Music Card overlay rendered off-thread once per song/style
 *   change and composited with exact aspect-fit scaling across portrait and landscape tablets.
 * - Zero continuous GPU loop when showing static artwork, when disabled, or when invisible.
 */
class EchoSnapLiveWallpaperService : WallpaperService() {

    override fun onCreate() {
        super.onCreate()
        MediaNotificationRepository.ensureInitialized(applicationContext)
    }

    override fun onCreateEngine(): Engine = EchoSnapWallpaperEngine()

    private inner class EchoSnapWallpaperEngine : Engine() {

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val appCtx = applicationContext
        private val tier = DevicePerformanceTier.get(appCtx)

        private var glThread: HandlerThread? = null
        private var glHandler: Handler? = null

        private var mediaThread: HandlerThread? = null
        private var mediaHandler: Handler? = null

        private var observeJob: Job? = null
        private var cardOverlayJob: Job? = null
        private var idlePhotoLoadJob: Job? = null
        private var idlePlaceholderBitmap: Bitmap? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val idleTimeoutRunnable = Runnable { evaluateIdleTimeoutState() }
        private val videoWatchdogRunnable = Runnable { checkAndRecoverVideoPlayback() }
        private var screenStateReceiver: BroadcastReceiver? = null

        @Volatile
        private var hiddenSinceRealtimeMs: Long = 0L

        @Volatile
        private var lastVideoFrameRealtimeMs: Long = 0L

        @Volatile
        private var videoResumeAttemptTimeMs: Long = 0L

        private var videoRecoveryAttempts = 0

        private var currentConfig = MediaNotificationRepository.styleConfig.value
        private var currentTrack = MediaNotificationRepository.currentTrack.value
        private var currentPalette = MediaNotificationRepository.extractedPalette.value
        private var currentCanvasUrl: String? = MediaNotificationRepository.activeCanvasUrl.value
        private var stoppedAtElapsedMs: Long = MediaNotificationRepository.lastPlaybackStoppedElapsedMs.value
        private var loadedIdlePhotoPath: String = ""
        private var loadedIdlePhotoBitmap: Bitmap? = null

        @Volatile
        private var isShowingIdlePhoto = false

        @Volatile
        private var isSurfaceValid = false

        @Volatile
        private var isEngineVisible = false

        @Volatile
        private var viewWidth = 1

        @Volatile
        private var viewHeight = 1

        @Volatile
        private var blurRadiusPx = currentConfig.liveWallpaperBlurRadius.coerceIn(0f, 40f)

        @Volatile
        private var edgeBlurFade = currentConfig.liveWallpaperEdgeBlurFade.coerceIn(0f, 1f)

        @Volatile
        private var showCardOverlay = currentConfig.liveWallpaperShowCard

        @Volatile
        private var isWallpaperEnabled = currentConfig.liveWallpaperEnabled

        // Track what is currently active in GL / MediaPlayer
        private var activeVideoUrl: String? = null
        private var hasReceivedVideoFrame = false
        private var videoWidth = 9
        private var videoHeight = 16
        private var artWidth = 1
        private var artHeight = 1
        private var hasArtTexture = false
        private var hasCardTexture = false
        private var lastUploadedArtBitmap: Bitmap? = null
        private var lastCardRenderSignature: String? = null

        private val renderPending = AtomicBoolean(false)
        private val texImagePending = AtomicBoolean(false)

        // EGL / GLES20 state (exclusively touched on glThread)
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglConfig: EGLConfig? = null
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var oesTextureId = 0
        private var artTextureId = 0
        private var cardTextureId = 0
        private var decoderSurfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null

        private var oesProgram = 0
        private var oesPosHandle = 0
        private var oesTexHandle = 0
        private var oesStMatrixHandle = 0
        private var oesCropScaleHandle = 0

        private var tex2dProgram = 0
        private var tex2dPosHandle = 0
        private var tex2dTexHandle = 0
        private var tex2dCropScaleHandle = 0
        private var tex2dFlipYHandle = 0
        private var tex2dEdgeFadeHandle = 0
        private var tex2dAlphaScaleHandle = 0

        private var blurProgram = 0
        private var blurPosHandle = 0
        private var blurTexHandle = 0
        private var blurTexelStepHandle = 0

        private var quadBuffer: FloatBuffer? = null
        private var cardQuadBuffer: FloatBuffer? = null
        private var fitArtQuadBuffer: FloatBuffer? = null
        private val stMatrix = FloatArray(16)

        private val fboIds = IntArray(4)
        private val fboTexIds = IntArray(4)
        private var currentFboW = 0
        private var currentFboH = 0
        private var currentHalfFboW = 0
        private var currentHalfFboH = 0

        // Off-thread MediaPlayer state (exclusively touched on mediaThread)
        private var mediaPlayer: MediaPlayer? = null
        private var preparedVideoUrl: String? = null
        private var isMediaPrepared = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            MediaNotificationRepository.ensureInitialized(appCtx)

            glThread = HandlerThread("EchoSnapWallpaperGl").apply { start() }
            glHandler = Handler(glThread!!.looper)
            mediaThread = HandlerThread("EchoSnapWallpaperMedia").apply { start() }
            mediaHandler = Handler(mediaThread!!.looper)

            registerScreenStateReceiver()
            startObservingRepository()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            val surface = holder?.surface ?: return
            val frame = holder.surfaceFrame
            viewWidth = max(1, frame.width())
            viewHeight = max(1, frame.height())
            isSurfaceValid = true
            MediaNotificationRepository.triggerWallpaperMediaSync(appCtx, forceCanvasRetry = true)

            glHandler?.post {
                if (initGl(surface)) {
                    pushArtworkFromState(force = true)
                    scheduleCardOverlayGeneration(force = true)
                    syncVideoSourceWithState()
                    requestStaticRender()
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            viewWidth = max(1, width)
            viewHeight = max(1, height)
            updateCardQuadBuffer(viewWidth, viewHeight)
            updateFitArtQuadBuffer(viewWidth, viewHeight, artWidth, artHeight)
            requestStaticRender()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isEngineVisible = visible
            if (visible) {
                val hiddenDurationMs = if (hiddenSinceRealtimeMs > 0L) {
                    (SystemClock.elapsedRealtime() - hiddenSinceRealtimeMs).coerceAtLeast(0L)
                } else {
                    0L
                }
                hiddenSinceRealtimeMs = 0L
                MediaNotificationRepository.triggerWallpaperMediaSync(appCtx, forceCanvasRetry = true)
                evaluateIdleTimeoutState()
                syncVideoSourceWithState()
                videoResumeAttemptTimeMs = SystemClock.elapsedRealtime()
                if (hiddenDurationMs >= 3000L && shouldPlayVideo()) {
                    mainHandler.removeCallbacks(videoWatchdogRunnable)
                    mainHandler.postDelayed(videoWatchdogRunnable, 420L)
                } else if (shouldPlayVideo()) {
                    mainHandler.removeCallbacks(videoWatchdogRunnable)
                    mainHandler.postDelayed(videoWatchdogRunnable, 750L)
                }
                requestStaticRender()
            } else {
                hiddenSinceRealtimeMs = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(idleTimeoutRunnable)
                mainHandler.removeCallbacks(videoWatchdogRunnable)
                updateMediaPlayerPlaybackState()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            isSurfaceValid = false
            isEngineVisible = false
            activeVideoUrl = null
            preparedVideoUrl = null
            isMediaPrepared = false
            hasReceivedVideoFrame = false
            lastVideoFrameRealtimeMs = 0L
            mainHandler.removeCallbacks(idleTimeoutRunnable)
            mainHandler.removeCallbacks(videoWatchdogRunnable)
            releaseMediaPlayerAsync(quitThread = false)
            glHandler?.post {
                releaseGlWindowAndTextures()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            isSurfaceValid = false
            isEngineVisible = false
            activeVideoUrl = null
            preparedVideoUrl = null
            isMediaPrepared = false
            hasReceivedVideoFrame = false
            unregisterScreenStateReceiver()
            mainHandler.removeCallbacks(idleTimeoutRunnable)
            mainHandler.removeCallbacks(videoWatchdogRunnable)
            observeJob?.cancel()
            cardOverlayJob?.cancel()
            idlePhotoLoadJob?.cancel()
            engineScope.cancel()
            releaseMediaPlayerAsync(quitThread = true)

            val gt = glThread
            val gh = glHandler
            glThread = null
            glHandler = null
            gh?.post {
                releaseGlAll()
                gt?.quitSafely()
            }
            idlePlaceholderBitmap?.let { if (!it.isRecycled) it.recycle() }
            idlePlaceholderBitmap = null
            loadedIdlePhotoBitmap?.let { if (!it.isRecycled) it.recycle() }
            loadedIdlePhotoBitmap = null
            super.onDestroy()
        }

        private fun registerScreenStateReceiver() {
            if (screenStateReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            hiddenSinceRealtimeMs = SystemClock.elapsedRealtime()
                            mainHandler.removeCallbacks(videoWatchdogRunnable)
                            updateMediaPlayerPlaybackState()
                        }
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT -> {
                            MediaNotificationRepository.triggerWallpaperMediaSync(appCtx, forceCanvasRetry = true)
                            evaluateIdleTimeoutState()
                            syncVideoSourceWithState()
                            if (shouldPlayVideo()) {
                                videoResumeAttemptTimeMs = SystemClock.elapsedRealtime()
                                updateMediaPlayerPlaybackState()
                                mainHandler.removeCallbacks(videoWatchdogRunnable)
                                mainHandler.postDelayed(videoWatchdogRunnable, 480L)
                            }
                            requestStaticRender()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            try {
                ContextCompat.registerReceiver(appCtx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                screenStateReceiver = receiver
            } catch (_: Exception) {
            }
        }

        private fun unregisterScreenStateReceiver() {
            val receiver = screenStateReceiver ?: return
            screenStateReceiver = null
            try {
                appCtx.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }

        private fun startObservingRepository() {
            observeJob?.cancel()
            observeJob = engineScope.launch {
                combine(
                    MediaNotificationRepository.styleConfig,
                    MediaNotificationRepository.currentTrack,
                    MediaNotificationRepository.extractedPalette,
                    MediaNotificationRepository.activeCanvasUrl,
                    MediaNotificationRepository.lastPlaybackStoppedElapsedMs
                ) { config, track, palette, canvasUrl, stoppedElapsed ->
                    WallpaperStateSnapshot(config, track, palette, canvasUrl, stoppedElapsed)
                }.collectLatest { state ->
                    val prevConfig = currentConfig
                    val prevTrack = currentTrack
                    val prevCanvasUrl = currentCanvasUrl
                    val prevPalette = currentPalette
                    val prevStoppedAt = stoppedAtElapsedMs

                    currentConfig = state.config
                    currentTrack = state.track
                    currentPalette = state.palette
                    currentCanvasUrl = state.canvasUrl
                    stoppedAtElapsedMs = state.stoppedAtElapsedMs

                    isWallpaperEnabled = state.config.liveWallpaperEnabled
                    blurRadiusPx = state.config.liveWallpaperBlurRadius.coerceIn(0f, 40f)
                    edgeBlurFade = state.config.liveWallpaperEdgeBlurFade.coerceIn(0f, 1f)
                    showCardOverlay = state.config.liveWallpaperShowCard

                    // Fast zero-allocation path when user is dragging Wallpaper Blur / Edge Blur sliders
                    val idleSettingsOrPlaybackChanged = prevStoppedAt != state.stoppedAtElapsedMs ||
                        prevTrack.isPlaying != state.track.isPlaying ||
                        prevTrack.isSample != state.track.isSample ||
                        prevConfig.liveWallpaperEnabled != state.config.liveWallpaperEnabled ||
                        prevConfig.liveWallpaperIdleRevertEnabled != state.config.liveWallpaperIdleRevertEnabled ||
                        prevConfig.liveWallpaperIdleDelaySec != state.config.liveWallpaperIdleDelaySec ||
                        prevConfig.liveWallpaperIdlePhotoPath != state.config.liveWallpaperIdlePhotoPath

                    val idleChanged = if (idleSettingsOrPlaybackChanged) {
                        ensureIdlePhotoLoadedIfNeeded(state.config.liveWallpaperIdlePhotoPath)
                        evaluateIdleTimeoutState(fromStateUpdate = true)
                    } else {
                        false
                    }

                    val artChanged = idleChanged ||
                        prevTrack.albumArt !== state.track.albumArt ||
                        prevTrack.title != state.track.title ||
                        prevTrack.artist != state.track.artist ||
                        prevConfig.liveWallpaperEnabled != state.config.liveWallpaperEnabled ||
                        prevConfig.liveWallpaperIdlePhotoPath != state.config.liveWallpaperIdlePhotoPath ||
                        prevPalette != state.palette

                    if (artChanged) {
                        pushArtworkFromState(force = false)
                    }

                    val canvasSettingsChanged = idleChanged ||
                        prevConfig.liveWallpaperEnabled != state.config.liveWallpaperEnabled ||
                        prevConfig.liveWallpaperMode != state.config.liveWallpaperMode ||
                        prevCanvasUrl != state.canvasUrl

                    if (canvasSettingsChanged) {
                        syncVideoSourceWithState()
                    } else if (prevConfig.liveWallpaperEnabled != state.config.liveWallpaperEnabled) {
                        updateMediaPlayerPlaybackState()
                    }

                    val cardMayHaveChanged = idleChanged ||
                        artChanged ||
                        prevConfig.liveWallpaperShowCard != state.config.liveWallpaperShowCard ||
                        prevConfig.brandMode != state.config.brandMode ||
                        prevConfig.logoColorMode != state.config.logoColorMode ||
                        prevConfig.customLogoColor != state.config.customLogoColor ||
                        prevConfig.cardLayoutType != state.config.cardLayoutType ||
                        prevConfig.cardBlurRadius != state.config.cardBlurRadius ||
                        prevConfig.cardOpacity != state.config.cardOpacity ||
                        prevConfig.glowIntensity != state.config.glowIntensity ||
                        prevConfig.cornerRadiusDp != state.config.cornerRadiusDp ||
                        prevConfig.cardScale != state.config.cardScale ||
                        prevConfig.showLogoWordmark != state.config.showLogoWordmark ||
                        prevConfig.lyricsCardBackgroundMode != state.config.lyricsCardBackgroundMode ||
                        prevConfig.lyricsArtBlurRadius != state.config.lyricsArtBlurRadius ||
                        prevConfig.customLyrics != state.config.customLyrics

                    if (cardMayHaveChanged) {
                        scheduleCardOverlayGeneration(force = false)
                    }
                    requestStaticRender()
                }
            }
        }

        private fun ensureIdlePhotoLoadedIfNeeded(path: String) {
            val cleanPath = path.trim()
            if (cleanPath.isEmpty()) {
                idlePhotoLoadJob?.cancel()
                loadedIdlePhotoPath = ""
                val old = loadedIdlePhotoBitmap
                loadedIdlePhotoBitmap = null
                if (old != null && !old.isRecycled && old !== lastUploadedArtBitmap) {
                    old.recycle()
                }
                return
            }
            if (cleanPath == loadedIdlePhotoPath && loadedIdlePhotoBitmap != null && !loadedIdlePhotoBitmap!!.isRecycled) {
                return
            }
            loadedIdlePhotoPath = cleanPath
            idlePhotoLoadJob?.cancel()
            idlePhotoLoadJob = engineScope.launch {
                val maxDim = if (tier.isLowRam) 1080 else 1600
                val bmp = withContext(Dispatchers.IO) {
                    if (File(cleanPath).exists()) {
                        SavedMediaLibrary.loadBitmap(cleanPath, maxDimension = maxDim)
                    } else {
                        null
                    }
                }
                if (loadedIdlePhotoPath != cleanPath) {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                    return@launch
                }
                loadedIdlePhotoBitmap = bmp
                if (isShowingIdlePhoto) {
                    pushArtworkFromState(force = true)
                } else {
                    evaluateIdleTimeoutState()
                }
            }
        }

        private fun evaluateIdleTimeoutState(fromStateUpdate: Boolean = false): Boolean {
            mainHandler.removeCallbacks(idleTimeoutRunnable)
            val cfg = currentConfig
            val hasCustomPhoto = cfg.liveWallpaperIdlePhotoPath.isNotBlank() &&
                loadedIdlePhotoBitmap?.takeIf { !it.isRecycled } != null
            val stoppedMs = stoppedAtElapsedMs
            val isPlaybackStopped = (!currentTrack.isPlaying || currentTrack.isSample) && stoppedMs > 0L

            val shouldRevertFeatureActive = isWallpaperEnabled &&
                cfg.liveWallpaperIdleRevertEnabled &&
                cfg.liveWallpaperIdlePhotoPath.isNotBlank() &&
                isPlaybackStopped

            if (!shouldRevertFeatureActive) {
                val changed = isShowingIdlePhoto
                isShowingIdlePhoto = false
                if (changed && !fromStateUpdate) {
                    pushArtworkFromState(force = true)
                    updateMediaPlayerPlaybackState()
                    scheduleCardOverlayGeneration(force = false)
                    requestStaticRender()
                }
                return changed
            }

            val delayMs = cfg.liveWallpaperIdleDelaySec.coerceIn(1, 86_400) * 1000L
            val elapsedMs = (SystemClock.elapsedRealtime() - stoppedMs).coerceAtLeast(0L)
            return if (elapsedMs >= delayMs) {
                val changed = !isShowingIdlePhoto
                if (hasCustomPhoto || loadedIdlePhotoPath == cfg.liveWallpaperIdlePhotoPath) {
                    isShowingIdlePhoto = loadedIdlePhotoBitmap?.takeIf { !it.isRecycled } != null
                }
                if ((changed || isShowingIdlePhoto) && !fromStateUpdate) {
                    pushArtworkFromState(force = true)
                    updateMediaPlayerPlaybackState()
                    scheduleCardOverlayGeneration(force = false)
                    requestStaticRender()
                }
                changed
            } else {
                val remainingMs = delayMs - elapsedMs
                val changed = isShowingIdlePhoto
                isShowingIdlePhoto = false
                // Schedule precise wakeup only when engine is visible or within a short window;
                // long timers (up to 1 day = 86,400s) evaluate instantaneously in onVisibilityChanged(true) with 0 battery wakeups.
                if (isEngineVisible || remainingMs <= 65_000L) {
                    mainHandler.postDelayed(idleTimeoutRunnable, remainingMs.coerceAtLeast(100L))
                }
                if (changed && !fromStateUpdate) {
                    pushArtworkFromState(force = true)
                    updateMediaPlayerPlaybackState()
                    scheduleCardOverlayGeneration(force = false)
                    requestStaticRender()
                }
                changed
            }
        }

        private fun pushArtworkFromState(force: Boolean) {
            val customIdleBmp = loadedIdlePhotoBitmap?.takeIf { !it.isRecycled }
            val rawArt = when {
                !isWallpaperEnabled -> getOrCreateDisabledBackdropBitmap()
                isShowingIdlePhoto && customIdleBmp != null -> customIdleBmp
                else -> currentTrack.albumArt ?: getOrCreateFallbackGradientBitmap(currentPalette)
            }
            if (!force && rawArt === lastUploadedArtBitmap && hasArtTexture) return
            lastUploadedArtBitmap = rawArt

            // Upload software-safe high-res bitmap copy to GL_TEXTURE_2D
            val maxDim = if (tier.isLowRam) 1200 else 1600
            val uploadCopy = BitmapBlurUtils.toSafeSoftwareBitmap(rawArt, maxDimension = maxDim) ?: return
            glHandler?.post {
                if (!isSurfaceValid || artTextureId == 0 || eglContext == EGL14.EGL_NO_CONTEXT) {
                    if (uploadCopy !== rawArt && !uploadCopy.isRecycled) uploadCopy.recycle()
                    return@post
                }
                try {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, artTextureId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadCopy, 0)
                    artWidth = max(1, uploadCopy.width)
                    artHeight = max(1, uploadCopy.height)
                    hasArtTexture = true
                    updateFitArtQuadBuffer(viewWidth, viewHeight, artWidth, artHeight)
                } catch (_: Exception) {
                } finally {
                    if (uploadCopy !== rawArt && !uploadCopy.isRecycled) {
                        uploadCopy.recycle()
                    }
                }
                drawFrameInternal(updateTexImage = false)
            }
        }

        private fun scheduleCardOverlayGeneration(force: Boolean) {
            if (!isWallpaperEnabled || !showCardOverlay || isShowingIdlePhoto) {
                cardOverlayJob?.cancel()
                lastCardRenderSignature = null
                if (hasCardTexture) {
                    hasCardTexture = false
                    requestStaticRender()
                }
                return
            }

            val cfg = currentConfig
            val track = currentTrack
            val palette = currentPalette
            val sig = buildString {
                append(track.title).append('|').append(track.artist).append('|').append(track.album).append('|')
                append(track.albumArt?.hashCode() ?: 0).append('|')
                append(cfg.brandMode.name).append('|').append(cfg.logoColorMode.name).append('|')
                append(cfg.customLogoColor.value).append('|').append(cfg.cardLayoutType.name).append('|')
                append(cfg.cardBlurRadius).append('|').append(cfg.cardOpacity).append('|')
                append(cfg.glowIntensity).append('|').append(cfg.cornerRadiusDp).append('|')
                append(cfg.cardScale).append('|').append(cfg.showLogoWordmark).append('|')
                append(cfg.lyricsCardBackgroundMode.name).append('|').append(cfg.lyricsArtBlurRadius).append('|')
                append(cfg.customLyrics.hashCode()).append('|').append(palette.hashCode())
            }
            if (!force && sig == lastCardRenderSignature && hasCardTexture) return
            lastCardRenderSignature = sig

            val targetW = if (tier.isLowRam) 540 else 720
            val targetH = if (tier.isLowRam) 960 else 1280
            val overlayStyle = cfg.copy(
                useTransparentBackground = true,
                useBlurredAlbumBackground = false
            )

            cardOverlayJob?.cancel()
            cardOverlayJob = engineScope.launch(Dispatchers.Default) {
                val cardBitmap = try {
                    CardShareManager.generateTransparentCardOverlayBitmap(
                        context = appCtx,
                        track = track,
                        palette = palette,
                        styleConfig = overlayStyle,
                        targetWidth = targetW,
                        targetHeight = targetH,
                        includeWaveform = false
                    )
                } catch (_: Exception) {
                    null
                } ?: return@launch

                glHandler?.post {
                    if (!isSurfaceValid || cardTextureId == 0 || eglContext == EGL14.EGL_NO_CONTEXT) {
                        if (!cardBitmap.isRecycled) cardBitmap.recycle()
                        return@post
                    }
                    try {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cardTextureId)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cardBitmap, 0)
                        hasCardTexture = true
                    } catch (_: Exception) {
                    } finally {
                        if (!cardBitmap.isRecycled) cardBitmap.recycle()
                    }
                    drawFrameInternal(updateTexImage = false)
                }
            }
        }

        private fun syncVideoSourceWithState() {
            val targetUrl = if (
                isWallpaperEnabled &&
                !isShowingIdlePhoto &&
                currentConfig.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS
            ) {
                currentCanvasUrl?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            if (targetUrl == null) {
                activeVideoUrl = null
                hasReceivedVideoFrame = false
                videoRecoveryAttempts = 0
                mainHandler.removeCallbacks(videoWatchdogRunnable)
                releaseMediaPlayerAsync(quitThread = false)
                requestStaticRender()
                return
            }

            if (targetUrl == activeVideoUrl && preparedVideoUrl == targetUrl) {
                updateMediaPlayerPlaybackState()
                return
            }

            val existingActive = activeVideoUrl
            val isUpgradeToSameCachedFile = !existingActive.isNullOrBlank() &&
                !existingActive.startsWith("file://") &&
                !existingActive.startsWith("content://") &&
                targetUrl.startsWith("file://") &&
                CanvasVideoExporter.getCachedVideoFileIfReady(appCtx, existingActive)
                    ?.let { Uri.fromFile(it).toString() } == targetUrl
            if (isUpgradeToSameCachedFile && isMediaPrepared) {
                activeVideoUrl = targetUrl
                preparedVideoUrl = targetUrl
                updateMediaPlayerPlaybackState()
                return
            }

            activeVideoUrl = targetUrl
            hasReceivedVideoFrame = false
            videoRecoveryAttempts = 0
            requestStaticRender() // Shows high-res artwork cleanly while video prepares

            glHandler?.post {
                val decSurface = decoderSurface
                if (!isSurfaceValid || decSurface == null) return@post
                prepareMediaPlayerAsync(targetUrl, decSurface)
            }
        }

        private fun checkAndRecoverVideoPlayback() {
            if (!isSurfaceValid || !shouldPlayVideo()) return
            val url = activeVideoUrl ?: return
            val elapsedSinceFrame = SystemClock.elapsedRealtime() - lastVideoFrameRealtimeMs
            val frameArrivedAfterResume = lastVideoFrameRealtimeMs >= videoResumeAttemptTimeMs &&
                elapsedSinceFrame < 1200L
            if (frameArrivedAfterResume && isMediaPrepared && hasReceivedVideoFrame) {
                videoRecoveryAttempts = 0
                return
            }
            if (videoRecoveryAttempts >= 3) return
            videoRecoveryAttempts++
            val forceRebuildSurface = videoRecoveryAttempts >= 2 || decoderSurface == null

            glHandler?.post {
                if (!isSurfaceValid || activeVideoUrl != url) return@post
                if (forceRebuildSurface) {
                    recreateDecoderSurfaceLocked()
                }
                val decSurface = decoderSurface ?: return@post
                hasReceivedVideoFrame = false
                videoResumeAttemptTimeMs = SystemClock.elapsedRealtime()
                prepareMediaPlayerAsync(url, decSurface)
                mainHandler.postDelayed(videoWatchdogRunnable, 900L)
            }
        }

        private fun prepareMediaPlayerAsync(
            videoUrl: String,
            decSurface: Surface,
            allowCacheRetry: Boolean = true
        ) {
            mediaHandler?.post {
                if (!isSurfaceValid) return@post
                releaseMediaPlayerInternal()
                preparedVideoUrl = videoUrl
                isMediaPrepared = false

                val mp = MediaPlayer()
                mediaPlayer = mp
                try {
                    mp.setSurface(decSurface)
                    val cached = CanvasVideoExporter.getCachedVideoFileIfReady(appCtx, videoUrl)
                    if (cached != null && cached.exists()) {
                        mp.setDataSource(appCtx, Uri.fromFile(cached))
                    } else if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
                        mp.setDataSource(appCtx, Uri.parse(videoUrl))
                    } else {
                        val headers = mapOf("User-Agent" to SpotifyAuthManager.DESKTOP_BROWSER_UA)
                        mp.setDataSource(appCtx, Uri.parse(videoUrl), headers)
                    }
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                        if (vw > 0 && vh > 0) {
                            videoWidth = vw
                            videoHeight = vh
                        }
                    }
                    mp.setOnPreparedListener { preparedMp ->
                        if (preparedVideoUrl != videoUrl || !isSurfaceValid) {
                            releaseMediaPlayerInternal()
                            return@setOnPreparedListener
                        }
                        try {
                            isMediaPrepared = true
                            preparedMp.isLooping = true
                            preparedMp.setVolume(0f, 0f)
                            if (preparedMp.videoWidth > 0 && preparedMp.videoHeight > 0) {
                                videoWidth = preparedMp.videoWidth
                                videoHeight = preparedMp.videoHeight
                            }
                            if (shouldPlayVideo()) {
                                videoResumeAttemptTimeMs = SystemClock.elapsedRealtime()
                                preparedMp.start()
                            }
                        } catch (_: Exception) {
                        }
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        hasReceivedVideoFrame = false
                        isMediaPrepared = false
                        releaseMediaPlayerInternal()
                        if (allowCacheRetry && !videoUrl.startsWith("file://") && !videoUrl.startsWith("content://")) {
                            engineScope.launch {
                                val file = CanvasVideoExporter.prefetchVideoToCache(appCtx, videoUrl)
                                if (file != null && file.exists() && isSurfaceValid && activeVideoUrl == videoUrl) {
                                    glHandler?.post {
                                        val s = decoderSurface
                                        if (isSurfaceValid && s != null && activeVideoUrl == videoUrl) {
                                            prepareMediaPlayerAsync(Uri.fromFile(file).toString(), s, allowCacheRetry = false)
                                        }
                                    }
                                } else {
                                    requestStaticRender()
                                }
                            }
                        } else {
                            requestStaticRender()
                        }
                        true
                    }
                    mp.prepareAsync()
                } catch (_: Exception) {
                    hasReceivedVideoFrame = false
                    releaseMediaPlayerInternal()
                    requestStaticRender()
                }
            }
        }

        private fun shouldPlayVideo(): Boolean =
            isSurfaceValid &&
                isEngineVisible &&
                isWallpaperEnabled &&
                !isShowingIdlePhoto &&
                currentConfig.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS &&
                !activeVideoUrl.isNullOrBlank()

        private fun updateMediaPlayerPlaybackState() {
            val play = shouldPlayVideo()
            val expectedUrl = activeVideoUrl
            mediaHandler?.post {
                val mp = mediaPlayer
                if (mp == null) {
                    if (play && !expectedUrl.isNullOrBlank() && preparedVideoUrl == null) {
                        glHandler?.post {
                            val s = decoderSurface
                            if (isSurfaceValid && s != null && activeVideoUrl == expectedUrl) {
                                prepareMediaPlayerAsync(expectedUrl, s)
                            }
                        }
                    }
                    return@post
                }
                if (!isMediaPrepared) return@post
                try {
                    if (play) {
                        if (!mp.isPlaying) {
                            videoResumeAttemptTimeMs = SystemClock.elapsedRealtime()
                            mp.start()
                        }
                    } else {
                        if (mp.isPlaying) mp.pause()
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun releaseMediaPlayerAsync(quitThread: Boolean) {
            val mh = mediaHandler
            val mt = mediaThread
            if (quitThread) {
                mediaHandler = null
                mediaThread = null
            }
            mh?.post {
                releaseMediaPlayerInternal()
                if (quitThread) {
                    mt?.quitSafely()
                }
            }
        }

        private fun releaseMediaPlayerInternal() {
            val mp = mediaPlayer ?: return
            mediaPlayer = null
            preparedVideoUrl = null
            isMediaPrepared = false
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }

        private fun requestStaticRender() {
            if (!isSurfaceValid) return
            if (!renderPending.compareAndSet(false, true)) return
            glHandler?.post {
                renderPending.set(false)
                if (isSurfaceValid) {
                    val updateOes = texImagePending.getAndSet(false)
                    drawFrameInternal(updateTexImage = updateOes)
                }
            }
        }

        private fun initGl(surface: Surface): Boolean {
            return try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
                val version = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

                val configAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                    numConfigs[0] <= 0
                ) {
                    return false
                }
                eglConfig = configs[0] ?: return false
                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                if (eglContext == EGL14.EGL_NO_CONTEXT) return false

                eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
                if (eglSurface == EGL14.EGL_NO_SURFACE) return false
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

                val vertices = floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
                quadBuffer = allocateFloatBuffer(vertices)
                updateCardQuadBuffer(viewWidth, viewHeight)

                val oesIds = IntArray(1)
                GLES20.glGenTextures(1, oesIds, 0)
                oesTextureId = oesIds[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                val tex2ds = IntArray(2)
                GLES20.glGenTextures(2, tex2ds, 0)
                artTextureId = tex2ds[0]
                cardTextureId = tex2ds[1]
                configure2dTexture(artTextureId)
                configure2dTexture(cardTextureId)

                oesProgram = createProgram(OES_VERT_SHADER, OES_FRAG_SHADER)
                oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
                oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
                oesStMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uStMatrix")
                oesCropScaleHandle = GLES20.glGetUniformLocation(oesProgram, "uCropScale")

                tex2dProgram = createProgram(TEX2D_VERT_SHADER, TEX2D_FRAG_SHADER)
                tex2dPosHandle = GLES20.glGetAttribLocation(tex2dProgram, "aPosition")
                tex2dTexHandle = GLES20.glGetAttribLocation(tex2dProgram, "aTexCoord")
                tex2dCropScaleHandle = GLES20.glGetUniformLocation(tex2dProgram, "uCropScale")
                tex2dFlipYHandle = GLES20.glGetUniformLocation(tex2dProgram, "uFlipY")
                tex2dEdgeFadeHandle = GLES20.glGetUniformLocation(tex2dProgram, "uEdgeBlurFade")
                tex2dAlphaScaleHandle = GLES20.glGetUniformLocation(tex2dProgram, "uEdgeOverlayAlpha")

                blurProgram = createProgram(PASSTHROUGH_VERT_SHADER, GAUSSIAN_9TAP_FRAG_SHADER)
                blurPosHandle = GLES20.glGetAttribLocation(blurProgram, "aPosition")
                blurTexHandle = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
                blurTexelStepHandle = GLES20.glGetUniformLocation(blurProgram, "uTexelStep")

                val gh = glHandler
                if (gh != null) {
                    decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
                        setOnFrameAvailableListener({
                            lastVideoFrameRealtimeMs = SystemClock.elapsedRealtime()
                            videoRecoveryAttempts = 0
                            texImagePending.set(true)
                            if (renderPending.compareAndSet(false, true)) {
                                gh.post {
                                    renderPending.set(false)
                                    val doTex = texImagePending.getAndSet(false)
                                    if (isSurfaceValid && shouldPlayVideo()) {
                                        drawFrameInternal(updateTexImage = doTex)
                                    } else if (doTex) {
                                        drainPendingTexImageSafely()
                                    }
                                }
                            }
                        }, gh)
                    }
                    decoderSurface = Surface(decoderSurfaceTexture)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun drainPendingTexImageSafely() {
            val dst = decoderSurfaceTexture ?: return
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT) {
                return
            }
            try {
                if (EGL14.eglGetCurrentContext() != eglContext ||
                    EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface
                ) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                dst.updateTexImage()
                dst.getTransformMatrix(stMatrix)
                hasReceivedVideoFrame = true
                lastVideoFrameRealtimeMs = SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
        }

        private fun recreateDecoderSurfaceLocked() {
            if (oesTextureId == 0 || glHandler == null) return
            try {
                decoderSurface?.release()
            } catch (_: Exception) {
            }
            decoderSurface = null
            try {
                decoderSurfaceTexture?.release()
            } catch (_: Exception) {
            }
            decoderSurfaceTexture = null

            val gh = glHandler ?: return
            decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener({
                    lastVideoFrameRealtimeMs = SystemClock.elapsedRealtime()
                    videoRecoveryAttempts = 0
                    texImagePending.set(true)
                    if (renderPending.compareAndSet(false, true)) {
                        gh.post {
                            renderPending.set(false)
                            val doTex = texImagePending.getAndSet(false)
                            if (isSurfaceValid && shouldPlayVideo()) {
                                drawFrameInternal(updateTexImage = doTex)
                            } else if (doTex) {
                                drainPendingTexImageSafely()
                            }
                        }
                    }
                }, gh)
            }
            decoderSurface = Surface(decoderSurfaceTexture)
        }

        private fun updateCardQuadBuffer(vw: Int, vh: Int) {
            val viewAspect = vw.toFloat() / vh.coerceAtLeast(1).toFloat()
            val cardAspect = 9f / 16f
            val scaleX: Float
            val scaleY: Float
            if (viewAspect > 0.72f) {
                // Tablet / Landscape (e.g. Samsung Tab A7 LTE rotated 2000x1200)
                scaleY = 0.88f
                scaleX = (scaleY * (cardAspect / viewAspect)).coerceIn(0.25f, 0.96f)
            } else {
                // Phone / Portrait Tablet
                scaleX = (cardAspect / viewAspect).coerceIn(0.55f, 0.94f)
                scaleY = (scaleX * (viewAspect / cardAspect)).coerceIn(0.45f, 0.92f)
            }
            val verts = floatArrayOf(
                -scaleX, -scaleY, 0f, 0f,
                scaleX, -scaleY, 1f, 0f,
                -scaleX, scaleY, 0f, 1f,
                scaleX, scaleY, 1f, 1f
            )
            cardQuadBuffer = allocateFloatBuffer(verts)
        }

        private fun updateFitArtQuadBuffer(vw: Int, vh: Int, aw: Int, ah: Int) {
            val viewAspect = vw.toFloat() / vh.coerceAtLeast(1).toFloat()
            val artAspect = aw.toFloat() / ah.coerceAtLeast(1).toFloat()
            val maxW = 0.84f
            val maxH = 0.64f
            var scaleX = maxW
            var scaleY = scaleX * (viewAspect / artAspect)
            if (scaleY > maxH) {
                scaleY = maxH
                scaleX = scaleY * (artAspect / viewAspect)
            }
            val verts = floatArrayOf(
                -scaleX, -scaleY, 0f, 0f,
                scaleX, -scaleY, 1f, 0f,
                -scaleX, scaleY, 0f, 1f,
                scaleX, scaleY, 1f, 1f
            )
            fitArtQuadBuffer = allocateFloatBuffer(verts)
        }

        private fun ensureBlurFbos(baseW: Int, baseH: Int) {
            val halfW = (baseW / 2).coerceAtLeast(24)
            val halfH = (baseH / 2).coerceAtLeast(24)
            if (currentFboW == baseW &&
                currentFboH == baseH &&
                currentHalfFboW == halfW &&
                currentHalfFboH == halfH &&
                fboIds[0] != 0 &&
                fboIds[1] != 0 &&
                fboIds[2] != 0 &&
                fboIds[3] != 0
            ) {
                return
            }
            deleteBlurFbos()
            GLES20.glGenFramebuffers(4, fboIds, 0)
            GLES20.glGenTextures(4, fboTexIds, 0)
            for (i in 0..3) {
                val w = if (i < 2) baseW else halfW
                val h = if (i < 2) baseH else halfH
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    w,
                    h,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    fboTexIds[i],
                    0
                )
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            currentFboW = baseW
            currentFboH = baseH
            currentHalfFboW = halfW
            currentHalfFboH = halfH
        }

        private fun deleteBlurFbos() {
            if (fboIds.any { it != 0 }) {
                GLES20.glDeleteFramebuffers(4, fboIds, 0)
                for (i in 0..3) fboIds[i] = 0
            }
            if (fboTexIds.any { it != 0 }) {
                GLES20.glDeleteTextures(4, fboTexIds, 0)
                for (i in 0..3) fboTexIds[i] = 0
            }
            currentFboW = 0
            currentFboH = 0
            currentHalfFboW = 0
            currentHalfFboH = 0
        }

        private fun runPingPongGaussianIteration(
            srcTexId: Int,
            midFboId: Int,
            midTexId: Int,
            dstFboId: Int,
            fboW: Int,
            fboH: Int,
            stepMul: Float,
            qb: FloatBuffer
        ) {
            val invW = 1f / fboW.toFloat()
            val invH = 1f / fboH.toFloat()
            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            // Horizontal pass -> midFboId
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, midFboId)
            GLES20.glViewport(0, 0, fboW, fboH)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
            GLES20.glUniform2f(blurTexelStepHandle, stepMul * invW, 0f)
            drawQuad(blurPosHandle, blurTexHandle, qb)

            // Vertical pass -> dstFboId
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFboId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, midTexId)
            GLES20.glUniform2f(blurTexelStepHandle, 0f, stepMul * invH)
            drawQuad(blurPosHandle, blurTexHandle, qb)
        }

        private fun drawFrameInternal(updateTexImage: Boolean) {
            if (!isSurfaceValid || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            val qb = quadBuffer ?: return
            try {
                if (EGL14.eglGetCurrentContext() != eglContext ||
                    EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface
                ) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                if (updateTexImage) {
                    decoderSurfaceTexture?.let {
                        it.updateTexImage()
                        it.getTransformMatrix(stMatrix)
                        hasReceivedVideoFrame = true
                        lastVideoFrameRealtimeMs = SystemClock.elapsedRealtime()
                    }
                }

                val vw = max(1, viewWidth)
                val vh = max(1, viewHeight)
                val viewAspect = vw.toFloat() / vh.toFloat()

                val showingStandbyPhoto = isWallpaperEnabled && isShowingIdlePhoto &&
                    loadedIdlePhotoBitmap?.takeIf { !it.isRecycled } != null
                val useVideo = isWallpaperEnabled &&
                    !showingStandbyPhoto &&
                    currentConfig.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS &&
                    !activeVideoUrl.isNullOrBlank() &&
                    hasReceivedVideoFrame

                if (!useVideo && !hasArtTexture) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, vw, vh)
                    GLES20.glClearColor(0.04f, 0.04f, 0.07f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    return
                }

                val srcAspect = if (useVideo) {
                    videoWidth.toFloat() / videoHeight.coerceAtLeast(1).toFloat()
                } else {
                    artWidth.toFloat() / artHeight.coerceAtLeast(1).toFloat()
                }
                val cropScaleX: Float
                val cropScaleY: Float
                if (srcAspect > viewAspect) {
                    cropScaleX = viewAspect / srcAspect
                    cropScaleY = 1f
                } else {
                    cropScaleX = 1f
                    cropScaleY = srcAspect / viewAspect
                }

                val rawCenterBlur = if (isWallpaperEnabled && !showingStandbyPhoto) blurRadiusPx else 0f
                val edgeBlur = if (isWallpaperEnabled && !showingStandbyPhoto) edgeBlurFade else 0f

                // When showing plain artwork with card hidden, frame the un-chopped crisp cover centered
                // over a soft ambient-blurred halo of itself unless the user pushes blur high (> 14px).
                val showUncroppedCenterArt = isWallpaperEnabled &&
                    !showingStandbyPhoto &&
                    !useVideo &&
                    !showCardOverlay &&
                    hasArtTexture &&
                    fitArtQuadBuffer != null &&
                    rawCenterBlur < 24f

                val centerBlur = if (showUncroppedCenterArt) {
                    max(rawCenterBlur, 12f)
                } else {
                    rawCenterBlur
                }

                GLES20.glDisable(GLES20.GL_BLEND)

                if (centerBlur <= 0.35f) {
                    // Stage 1 (Sharp Center Layer): Direct full-res pass to screen
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, vw, vh)
                    if (useVideo) {
                        GLES20.glUseProgram(oesProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                        GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
                        GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
                        drawQuad(oesPosHandle, oesTexHandle, qb)
                    } else {
                        GLES20.glUseProgram(tex2dProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, artTextureId)
                        GLES20.glUniform2f(tex2dCropScaleHandle, cropScaleX, cropScaleY)
                        GLES20.glUniform1f(tex2dFlipYHandle, 1f)
                        GLES20.glUniform1f(tex2dEdgeFadeHandle, 0f)
                        GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                        drawQuad(tex2dPosHandle, tex2dTexHandle, qb)
                    }

                    // Stage 2 (Top & Bottom Edge Blur): Pre-allocated 2-level Gaussian pyramid frost
                    if (edgeBlur > 0.01f) {
                        val baseMaxLong = if (tier.isLegacyRenderTier) 180 else 240
                        val (fboW, fboH) = computeFboDims(baseMaxLong, vw, vh, viewAspect)
                        ensureBlurFbos(fboW, fboH)
                        val halfW = currentHalfFboW
                        val halfH = currentHalfFboH

                        renderInputToFbo0(useVideo, fboW, fboH, cropScaleX, cropScaleY, qb)
                        runPingPongGaussianIteration(fboTexIds[0], fboIds[1], fboTexIds[1], fboIds[0], fboW, fboH, 1.05f, qb)
                        runPingPongGaussianIteration(
                            fboTexIds[0], fboIds[3], fboTexIds[3], fboIds[2],
                            halfW, halfH, 0.72f + edgeBlur * 0.35f, qb
                        )

                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, vw, vh)
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        GLES20.glUseProgram(tex2dProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[2])
                        GLES20.glUniform2f(tex2dCropScaleHandle, 1f, 1f)
                        GLES20.glUniform1f(tex2dFlipYHandle, 0f)
                        GLES20.glUniform1f(tex2dEdgeFadeHandle, edgeBlur)
                        GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                        drawQuad(tex2dPosHandle, tex2dTexHandle, qb)
                    }
                } else {
                    // Pre-allocated 2-level Gaussian pyramid: zero reallocations during slider drags, step <= 1.10
                    val baseMaxLong = if (tier.isLegacyRenderTier) 180 else 240
                    val (fboW, fboH) = computeFboDims(baseMaxLong, vw, vh, viewAspect)
                    ensureBlurFbos(fboW, fboH)
                    val halfW = currentHalfFboW
                    val halfH = currentHalfFboH

                    renderInputToFbo0(useVideo, fboW, fboH, cropScaleX, cropScaleY, qb)

                    val centerResultTexId: Int
                    if (centerBlur <= 10f) {
                        val step1 = 0.25f + (centerBlur / 10f) * 0.80f
                        runPingPongGaussianIteration(fboTexIds[0], fboIds[1], fboTexIds[1], fboIds[0], fboW, fboH, step1, qb)
                        centerResultTexId = fboTexIds[0]
                    } else {
                        val t = ((centerBlur - 10f) / 30f).coerceIn(0f, 1f)
                        runPingPongGaussianIteration(fboTexIds[0], fboIds[1], fboTexIds[1], fboIds[0], fboW, fboH, 1.05f, qb)
                        val step2 = 0.45f + t * 0.65f
                        runPingPongGaussianIteration(fboTexIds[0], fboIds[3], fboTexIds[3], fboIds[2], halfW, halfH, step2, qb)
                        if (t > 0.35f) {
                            val step3 = ((t - 0.35f) / 0.65f) * 1.05f
                            runPingPongGaussianIteration(fboTexIds[2], fboIds[3], fboTexIds[3], fboIds[2], halfW, halfH, step3, qb)
                        }
                        centerResultTexId = fboTexIds[2]
                    }

                    // Draw Center Blur Layer cleanly to Screen
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, vw, vh)
                    GLES20.glUseProgram(tex2dProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, centerResultTexId)
                    GLES20.glUniform2f(tex2dCropScaleHandle, 1f, 1f)
                    GLES20.glUniform1f(tex2dFlipYHandle, 0f)
                    GLES20.glUniform1f(tex2dEdgeFadeHandle, 0f)
                    GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                    drawQuad(tex2dPosHandle, tex2dTexHandle, qb)

                    // Stage 2 (Top & Bottom Edge Blur): Smooth half-res extra frost on top & bottom bands
                    if (edgeBlur > 0.01f) {
                        val edgeStep = 0.80f + edgeBlur * 0.30f
                        runPingPongGaussianIteration(centerResultTexId, fboIds[3], fboTexIds[3], fboIds[2], halfW, halfH, edgeStep, qb)

                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, vw, vh)
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        GLES20.glUseProgram(tex2dProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[2])
                        GLES20.glUniform2f(tex2dCropScaleHandle, 1f, 1f)
                        GLES20.glUniform1f(tex2dFlipYHandle, 0f)
                        GLES20.glUniform1f(tex2dEdgeFadeHandle, edgeBlur)
                        GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                        drawQuad(tex2dPosHandle, tex2dTexHandle, qb)
                    }
                }

                // 2.5 Un-chopped centered full-res artwork when playing non-card artwork mode
                val aqb = fitArtQuadBuffer
                if (showUncroppedCenterArt && aqb != null) {
                    val coverAlpha = if (rawCenterBlur <= 14f) {
                        1f
                    } else {
                        ((24f - rawCenterBlur) / 10f).coerceIn(0f, 1f)
                    }
                    if (coverAlpha > 0.01f) {
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        GLES20.glUseProgram(tex2dProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, artTextureId)
                        GLES20.glUniform2f(tex2dCropScaleHandle, 1f, 1f)
                        GLES20.glUniform1f(tex2dFlipYHandle, 1f)
                        GLES20.glUniform1f(tex2dEdgeFadeHandle, 0f)
                        GLES20.glUniform1f(tex2dAlphaScaleHandle, coverAlpha)
                        drawQuad(tex2dPosHandle, tex2dTexHandle, aqb)
                    }
                }

                // 3. Optional centered EchoSnap Music Card overlay (premultiplied alpha bitmap texture)
                val cqb = cardQuadBuffer
                if (isWallpaperEnabled && !showingStandbyPhoto && showCardOverlay && hasCardTexture && cqb != null) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glUseProgram(tex2dProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cardTextureId)
                    GLES20.glUniform2f(tex2dCropScaleHandle, 1f, 1f)
                    GLES20.glUniform1f(tex2dFlipYHandle, 1f)
                    GLES20.glUniform1f(tex2dEdgeFadeHandle, 0f)
                    GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                    drawQuad(tex2dPosHandle, tex2dTexHandle, cqb)
                }

                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (_: Exception) {
            }
        }

        private fun renderInputToFbo0(
            useVideo: Boolean,
            fboW: Int,
            fboH: Int,
            cropScaleX: Float,
            cropScaleY: Float,
            qb: FloatBuffer
        ) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
            GLES20.glViewport(0, 0, fboW, fboH)
            if (useVideo) {
                GLES20.glUseProgram(oesProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
                GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
                drawQuad(oesPosHandle, oesTexHandle, qb)
            } else {
                GLES20.glUseProgram(tex2dProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, artTextureId)
                GLES20.glUniform2f(tex2dCropScaleHandle, cropScaleX, cropScaleY)
                GLES20.glUniform1f(tex2dFlipYHandle, 1f)
                GLES20.glUniform1f(tex2dEdgeFadeHandle, 0f)
                GLES20.glUniform1f(tex2dAlphaScaleHandle, 1f)
                drawQuad(tex2dPosHandle, tex2dTexHandle, qb)
            }
        }

        private fun computeFboDims(maxLong: Int, vw: Int, vh: Int, viewAspect: Float): Pair<Int, Int> {
            return if (vw >= vh) {
                maxLong to (maxLong / viewAspect).roundToInt().coerceAtLeast(32)
            } else {
                (maxLong * viewAspect).roundToInt().coerceAtLeast(32) to maxLong
            }
        }

        private fun drawQuad(posHandle: Int, texHandle: Int, qb: FloatBuffer) {
            qb.position(0)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, qb)
            GLES20.glEnableVertexAttribArray(posHandle)
            qb.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, qb)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun releaseGlWindowAndTextures() {
            try {
                decoderSurface?.release()
            } catch (_: Exception) {
            }
            decoderSurface = null
            try {
                decoderSurfaceTexture?.release()
            } catch (_: Exception) {
            }
            decoderSurfaceTexture = null

            if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglSurface != EGL14.EGL_NO_SURFACE &&
                eglContext != EGL14.EGL_NO_CONTEXT
            ) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    deleteBlurFbos()
                    val texIds = intArrayOf(oesTextureId, artTextureId, cardTextureId).filter { it != 0 }.toIntArray()
                    if (texIds.isNotEmpty()) GLES20.glDeleteTextures(texIds.size, texIds, 0)
                    oesTextureId = 0
                    artTextureId = 0
                    cardTextureId = 0
                    hasArtTexture = false
                    hasCardTexture = false
                    if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
                    if (tex2dProgram != 0) GLES20.glDeleteProgram(tex2dProgram)
                    if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
                    oesProgram = 0
                    tex2dProgram = 0
                    blurProgram = 0
                } catch (_: Exception) {
                }
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                } catch (_: Exception) {
                }
            }
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun releaseGlAll() {
            releaseGlWindowAndTextures()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                } catch (_: Exception) {
                }
            }
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        private fun getOrCreateFallbackGradientBitmap(palette: CardPalette): Bitmap {
            val existing = idlePlaceholderBitmap
            if (existing != null && !existing.isRecycled) return existing
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 128f, 128f,
                    intArrayOf(
                        android.graphics.Color.rgb(
                            (palette.vibrantColor.red * 255).toInt(),
                            (palette.vibrantColor.green * 255).toInt(),
                            (palette.vibrantColor.blue * 255).toInt()
                        ),
                        0xFF101119.toInt()
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, 128f, 128f, p)
            idlePlaceholderBitmap = bmp
            return bmp
        }

        private fun getOrCreateDisabledBackdropBitmap(): Bitmap {
            val existing = idlePlaceholderBitmap
            if (existing != null && !existing.isRecycled) return existing
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(0xFF0A0B10.toInt())
            idlePlaceholderBitmap = bmp
            return bmp
        }

        private fun configure2dTexture(texId: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun allocateFloatBuffer(arr: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(arr.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(arr)
                .apply { position(0) }

        private fun createProgram(vertSrc: String, fragSrc: String): Int {
            val v = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            return GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, v)
                GLES20.glAttachShader(prog, f)
                GLES20.glLinkProgram(prog)
            }
        }

        private fun compileShader(type: Int, src: String): Int =
            GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, src)
                GLES20.glCompileShader(shader)
            }
    }

    private data class WallpaperStateSnapshot(
        val config: CardStyleConfig,
        val track: MediaTrackInfo,
        val palette: CardPalette,
        val canvasUrl: String?,
        val stoppedAtElapsedMs: Long
    )

    companion object {
        fun isLiveWallpaperActive(context: Context): Boolean {
            return try {
                val wm = WallpaperManager.getInstance(context.applicationContext)
                val info = wm.wallpaperInfo
                info != null &&
                    info.packageName == context.packageName &&
                    info.serviceName == EchoSnapLiveWallpaperService::class.java.name
            } catch (_: Exception) {
                false
            }
        }

        fun openLiveWallpaperSetter(context: Context): Boolean {
            val component = ComponentName(context, EchoSnapLiveWallpaperService::class.java)
            val directIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            try {
                context.startActivity(directIntent)
                return true
            } catch (_: Exception) {
            }

            // Fallback for older custom Android skins (e.g. MIUI / OneUI variants)
            val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            return try {
                context.startActivity(chooserIntent)
                true
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Open system Wallpaper settings and choose EchoSnap",
                    Toast.LENGTH_LONG
                ).show()
                false
            }
        }

        private const val OES_VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uStMatrix;
            uniform vec2 uCropScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 centered = (aTexCoord - vec2(0.5)) * uCropScale + vec2(0.5);
                vTexCoord = (uStMatrix * vec4(centered, 0.0, 1.0)).xy;
            }
        """

        private const val OES_FRAG_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, clamp(vTexCoord, 0.0, 1.0));
            }
        """

        private const val TEX2D_VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uCropScale;
            uniform float uFlipY;
            varying vec2 vTexCoord;
            varying vec2 vScreenUv;
            void main() {
                gl_Position = aPosition;
                vScreenUv = aTexCoord;
                vec2 centered = (aTexCoord - vec2(0.5)) * uCropScale + vec2(0.5);
                if (uFlipY > 0.5) {
                    centered.y = 1.0 - centered.y;
                }
                vTexCoord = centered;
            }
        """

        private const val TEX2D_FRAG_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform float uEdgeBlurFade;
            uniform float uEdgeOverlayAlpha;
            varying vec2 vTexCoord;
            varying vec2 vScreenUv;
            void main() {
                vec4 col = texture2D(sTexture, clamp(vTexCoord, 0.0, 1.0));
                if (uEdgeBlurFade > 0.005) {
                    float topBand = smoothstep(0.62, 0.90, vScreenUv.y);
                    float bottomBand = smoothstep(0.38, 0.10, vScreenUv.y);
                    float edge = clamp(topBand + bottomBand, 0.0, 1.0) * uEdgeBlurFade;
                    col.rgb *= (1.0 - edge * 0.18);
                    gl_FragColor = vec4(col.rgb, edge);
                } else {
                    gl_FragColor = vec4(col.rgb, col.a * uEdgeOverlayAlpha);
                }
            }
        """

        private const val PASSTHROUGH_VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val GAUSSIAN_9TAP_FRAG_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform vec2 uTexelStep;
            varying vec2 vTexCoord;
            void main() {
                vec4 sum = texture2D(sTexture, vTexCoord) * 0.227027;
                sum += texture2D(sTexture, clamp(vTexCoord + uTexelStep * 1.384615, 0.0, 1.0)) * 0.316216;
                sum += texture2D(sTexture, clamp(vTexCoord - uTexelStep * 1.384615, 0.0, 1.0)) * 0.316216;
                sum += texture2D(sTexture, clamp(vTexCoord + uTexelStep * 3.230769, 0.0, 1.0)) * 0.070270;
                sum += texture2D(sTexture, clamp(vTexCoord - uTexelStep * 3.230769, 0.0, 1.0)) * 0.070270;
                gl_FragColor = sum;
            }
        """
    }
}
