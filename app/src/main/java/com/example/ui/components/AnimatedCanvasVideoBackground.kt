package com.example.ui.components

import android.content.Context
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.SpotifyAuthManager
import com.example.util.CanvasVideoExporter
import com.example.util.DevicePerformanceTier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardware-accelerated looping animated Song Canvas background rendered via
 * MediaPlayer -> OES SurfaceTexture -> 2-Pass Downsampled FBO Ping-Pong Gaussian Blur
 * -> TextureView.
 *
 * Optimizations for mid/entry GPUs (Samsung Galaxy Tab A7 LTE / Galaxy A7 2018 / Redmi 9C):
 * 1. All MediaPlayer lifecycle IPC (setDataSource, prepareAsync, start, pause, stop, release)
 *    runs on a dedicated background HandlerThread — zero UI thread stalls when opening Preview,
 *    scrolling, or switching tracks.
 * 2. When blurRadiusPx > 0.45f, samplerExternalOES (heavy YUV->RGB fetch) is sampled ONCE
 *    per texel into a compact downsampled RGBA FBO, then blurred via two separable 9-tap
 *    2D Gaussian ping-pong passes (81 effective taps) with coalesced render scheduling.
 * 3. When blurRadiusPx <= 0.45f, a single crisp OES pass renders directly with 0 FBO overhead.
 */
@Composable
fun AnimatedCanvasVideoBackground(
    videoUrl: String,
    modifier: Modifier = Modifier,
    blurRadiusPx: Float = 0f,
    isPlaybackPaused: Boolean = false,
    onVideoReadyChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var currentLogicalUrl by remember { mutableStateOf(videoUrl) }
    var activePlayUri by remember {
        mutableStateOf(
            if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
                videoUrl
            } else {
                CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl)
                    ?.let { Uri.fromFile(it).toString() }
                    ?: videoUrl
            }
        )
    }
    var streamErrored by remember { mutableStateOf(false) }
    var retriedCorruptCache by remember { mutableStateOf(false) }

    var isVideoReady by remember(activePlayUri) { mutableStateOf(false) }
    var isPrepared by remember(activePlayUri) { mutableStateOf(false) }

    LaunchedEffect(videoUrl) {
        val isUpgradeToCachedOfSameRemote = !currentLogicalUrl.startsWith("file://") &&
            !currentLogicalUrl.startsWith("content://") &&
            videoUrl.startsWith("file://") &&
            CanvasVideoExporter.getCachedVideoFileIfReady(context, currentLogicalUrl)
                ?.let { Uri.fromFile(it).toString() } == videoUrl

        if (isUpgradeToCachedOfSameRemote && (activePlayUri == videoUrl || (isVideoReady && !streamErrored))) {
            currentLogicalUrl = videoUrl
            return@LaunchedEffect
        }

        currentLogicalUrl = videoUrl
        retriedCorruptCache = false
        val targetPlayUri = if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
            videoUrl
        } else {
            CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl)
                ?.let { Uri.fromFile(it).toString() }
                ?: videoUrl
        }
        if (activePlayUri != targetPlayUri || streamErrored) {
            streamErrored = false
            activePlayUri = targetPlayUri
        }
    }

    LaunchedEffect(videoUrl, streamErrored) {
        if (!videoUrl.startsWith("file://") && !videoUrl.startsWith("content://")) {
            if (streamErrored && activePlayUri.startsWith("file://") && !retriedCorruptCache) {
                retriedCorruptCache = true
                CanvasVideoExporter.invalidateCachedVideoFile(context, videoUrl)
            }
            val cached = CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl)
                ?: CanvasVideoExporter.prefetchVideoToCache(context, videoUrl)
            if (cached != null) {
                val localUri = Uri.fromFile(cached).toString()
                if (activePlayUri != localUri && (streamErrored || !isVideoReady)) {
                    streamErrored = false
                    activePlayUri = localUri
                }
            }
        }
    }
    val fadeAlphaState = animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "canvas_video_fade"
    )
    val isFullyOpaque = isVideoReady && fadeAlphaState.value >= 0.98f

    LaunchedEffect(isFullyOpaque, activePlayUri) {
        onVideoReadyChanged(isFullyOpaque)
    }

    var playerControllerRef by remember(activePlayUri) { mutableStateOf<AsyncCanvasMediaPlayerController?>(null) }
    var glRendererRef by remember(activePlayUri) { mutableStateOf<PreviewGlRendererController?>(null) }
    var isLifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, activePlayUri) {
        isLifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isLifecycleResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(playerControllerRef, isPrepared, isPlaybackPaused, isLifecycleResumed) {
        val pc = playerControllerRef ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        pc.setPaused(isPlaybackPaused || !isLifecycleResumed)
    }

    DisposableEffect(activePlayUri) {
        onDispose {
            isPrepared = false
            isVideoReady = false
            onVideoReadyChanged(false)
            playerControllerRef?.releaseAsync()
            playerControllerRef = null
            glRendererRef?.release()
            glRendererRef = null
        }
    }

    key(activePlayUri) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fadeAlphaState.value },
            factory = { viewCtx ->
                val isLegacyTier = DevicePerformanceTier.get(viewCtx).isLegacyRenderTier
                TextureView(viewCtx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            playerControllerRef?.releaseAsync()
                            glRendererRef?.release()

                            val controller = PreviewGlRendererController(
                                outputSurfaceTexture = surfaceTexture,
                                initialWidth = max(1, width),
                                initialHeight = max(1, height),
                                initialBlurPx = blurRadiusPx,
                                isLegacyTier = isLegacyTier
                            )
                            glRendererRef = controller

                            val asyncPlayer = AsyncCanvasMediaPlayerController(viewCtx.applicationContext)
                            playerControllerRef = asyncPlayer

                            controller.start { decoderSurface ->
                                if (decoderSurface == null) {
                                    isPrepared = false
                                    isVideoReady = false
                                    return@start
                                }
                                asyncPlayer.prepareAsync(
                                    videoUrl = activePlayUri,
                                    decoderSurface = decoderSurface,
                                    shouldBePaused = { isPlaybackPaused || !isLifecycleResumed },
                                    onVideoSize = { vw, vh -> controller.updateVideoSize(vw, vh) },
                                    onPreparedReady = { vw, vh ->
                                        isPrepared = true
                                        controller.updateVideoSize(vw, vh)
                                        controller.updateBlurRadius(blurRadiusPx)
                                        controller.requestRender()
                                    },
                                    onError = {
                                        isPrepared = false
                                        isVideoReady = false
                                        streamErrored = true
                                    }
                                )
                            }
                            controller.setOnFirstFrameRendered {
                                isVideoReady = true
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            glRendererRef?.updateViewportSize(max(1, width), max(1, height))
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            isPrepared = false
                            isVideoReady = false
                            playerControllerRef?.releaseAsync()
                            playerControllerRef = null
                            glRendererRef?.release()
                            glRendererRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
            update = { textureView ->
                if (textureView.isOpaque != isFullyOpaque) {
                    textureView.isOpaque = isFullyOpaque
                }
                glRendererRef?.updateBlurRadius(blurRadiusPx)
            }
        )
    }
}

/**
 * Off-main-thread MediaPlayer owner so native binder IPC (`prepareAsync`, `start`,
 * `pause`, `stop`, `release`) never stalls Jetpack Compose animations or slider gestures.
 */
private class AsyncCanvasMediaPlayerController(
    private val appContext: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("CanvasMediaPlayerCtrl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var released = false

    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var desiredPaused = false

    fun prepareAsync(
        videoUrl: String,
        decoderSurface: Surface,
        shouldBePaused: () -> Boolean,
        onVideoSize: (Int, Int) -> Unit,
        onPreparedReady: (Int, Int) -> Unit,
        onError: () -> Unit
    ) {
        handler.post {
            if (released) return@post
            releasePlayerInternal()
            prepared = false
            desiredPaused = shouldBePaused()

            val mp = MediaPlayer()
            mediaPlayer = mp
            try {
                mp.setSurface(decoderSurface)
                val cachedFile = CanvasVideoExporter.getCachedVideoFileIfReady(appContext, videoUrl)
                if (cachedFile != null && cachedFile.exists()) {
                    mp.setDataSource(appContext, Uri.fromFile(cachedFile))
                } else if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
                    mp.setDataSource(appContext, Uri.parse(videoUrl))
                } else {
                    val headers = mapOf("User-Agent" to SpotifyAuthManager.DESKTOP_BROWSER_UA)
                    mp.setDataSource(appContext, Uri.parse(videoUrl), headers)
                }
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                    mainHandler.post { if (!released) onVideoSize(vw, vh) }
                }
                mp.setOnPreparedListener { preparedMp ->
                    if (released) {
                        releasePlayerInternal()
                        return@setOnPreparedListener
                    }
                    try {
                        prepared = true
                        preparedMp.isLooping = true
                        preparedMp.setVolume(0f, 0f)
                        if (!desiredPaused && !shouldBePaused()) {
                            preparedMp.start()
                        }
                        val vw = preparedMp.videoWidth
                        val vh = preparedMp.videoHeight
                        mainHandler.post { if (!released) onPreparedReady(vw, vh) }
                    } catch (_: Exception) {
                    }
                }
                mp.setOnErrorListener { _, _, _ ->
                    if (!released) {
                        mainHandler.post { if (!released) onError() }
                    }
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                releasePlayerInternal()
                if (!released) {
                    mainHandler.post { if (!released) onError() }
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        desiredPaused = paused
        if (released) return
        handler.post {
            if (released || !prepared) return@post
            val mp = mediaPlayer ?: return@post
            try {
                if (paused) {
                    if (mp.isPlaying) mp.pause()
                } else {
                    if (!mp.isPlaying) mp.start()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun releaseAsync() {
        if (released) return
        released = true
        handler.post {
            releasePlayerInternal()
            thread.quitSafely()
        }
    }

    private fun releasePlayerInternal() {
        val mp = mediaPlayer ?: return
        mediaPlayer = null
        prepared = false
        try {
            mp.stop()
        } catch (_: Exception) {
        }
        try {
            mp.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * Dedicated GL thread rendering OES video frames onto a TextureView SurfaceTexture.
 * Uses 2-pass downsampled FBO ping-pong separable Gaussian blur when blur > 0.45px so
 * even 40px lush blur stays silky smooth at 60fps on PowerVR GE8320, Mali-G71, and Adreno 610.
 */
private class PreviewGlRendererController(
    private val outputSurfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
    initialBlurPx: Float,
    private val isLegacyTier: Boolean
) {
    private val glThread = HandlerThread("CanvasPreviewGl").apply { start() }
    private val glHandler = Handler(glThread.looper)

    @Volatile
    private var released = false

    @Volatile
    private var viewWidth: Int = initialWidth

    @Volatile
    private var viewHeight: Int = initialHeight

    @Volatile
    private var videoWidth: Int = 9

    @Volatile
    private var videoHeight: Int = 16

    @Volatile
    private var blurRadiusPx: Float = initialBlurPx.coerceIn(0f, 40f)

    @Volatile
    private var hasReceivedFrame = false

    @Volatile
    private var notifiedFirstFrame = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var onFirstFrameRenderedCallback: (() -> Unit)? = null

    private val renderPending = AtomicBoolean(false)
    private val texImagePending = AtomicBoolean(false)

    fun setOnFirstFrameRendered(callback: () -> Unit) {
        onFirstFrameRenderedCallback = callback
        if (notifiedFirstFrame && !released) {
            mainHandler.post(callback)
        }
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTextureId: Int = 0
    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null

    // Program 1: OES video -> Direct Screen OR FBO1 (handles stMatrix + center-crop)
    private var oesProgram: Int = 0
    private var oesPosHandle: Int = 0
    private var oesTexHandle: Int = 0
    private var oesStMatrixHandle: Int = 0
    private var oesCropScaleHandle: Int = 0

    // Program 2: Separable 9-tap 2D Gaussian blur (FBO1 -> FBO2 -> Screen)
    private var blurProgram: Int = 0
    private var blurPosHandle: Int = 0
    private var blurTexHandle: Int = 0
    private var blurTexelStepHandle: Int = 0

    private var quadBuffer: FloatBuffer? = null
    private val stMatrix = FloatArray(16)

    // Pre-allocated 2-level pyramid FBOs ([0,1] = compact base res, [2,3] = half-res) -> zero reallocations during slider drag
    private val fboIds = IntArray(4)
    private val fboTexIds = IntArray(4)
    private var currentFboW = 0
    private var currentFboH = 0
    private var currentHalfFboW = 0
    private var currentHalfFboH = 0

    fun start(onSurfaceReady: (Surface?) -> Unit) {
        glHandler.post {
            if (released) {
                onSurfaceReady(null)
                return@post
            }
            val ok = initGl()
            onSurfaceReady(if (ok) decoderSurface else null)
        }
    }

    fun updateBlurRadius(newBlurPx: Float) {
        val clamped = newBlurPx.coerceIn(0f, 40f)
        if (clamped == blurRadiusPx) return
        blurRadiusPx = clamped
        requestRender()
    }

    fun updateVideoSize(vw: Int, vh: Int) {
        if (vw > 0 && vh > 0) {
            videoWidth = vw
            videoHeight = vh
            requestRender()
        }
    }

    fun updateViewportSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            viewWidth = w
            viewHeight = h
            requestRender()
        }
    }

    fun requestRender() {
        if (released || !hasReceivedFrame) return
        if (!renderPending.compareAndSet(false, true)) return
        glHandler.post {
            renderPending.set(false)
            if (!released && hasReceivedFrame) {
                val shouldUpdateOes = texImagePending.getAndSet(false)
                drawCurrentFrame(updateTexImage = shouldUpdateOes)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        glHandler.post {
            releaseGlInternal()
            glThread.quitSafely()
        }
    }

    private fun initGl(): Boolean {
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
            val eglConfig = configs[0] ?: return false
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurfaceTexture, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            val vertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            quadBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices).apply { position(0) }

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            oesProgram = createProgram(OES_VERT_SHADER, OES_FRAG_SHADER)
            oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
            oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
            oesStMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uStMatrix")
            oesCropScaleHandle = GLES20.glGetUniformLocation(oesProgram, "uCropScale")

            blurProgram = createProgram(PASSTHROUGH_VERT_SHADER, GAUSSIAN_9TAP_FRAG_SHADER)
            blurPosHandle = GLES20.glGetAttribLocation(blurProgram, "aPosition")
            blurTexHandle = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
            blurTexelStepHandle = GLES20.glGetUniformLocation(blurProgram, "uTexelStep")

            decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener({
                    if (!released) {
                        texImagePending.set(true)
                        if (renderPending.compareAndSet(false, true)) {
                            glHandler.post {
                                renderPending.set(false)
                                if (!released) {
                                    val doTex = texImagePending.getAndSet(false)
                                    drawCurrentFrame(updateTexImage = doTex)
                                }
                            }
                        }
                    }
                }, glHandler)
            }
            decoderSurface = Surface(decoderSurfaceTexture)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureBlurFbos(baseW: Int, baseH: Int): Boolean {
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
            return true
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
        return true
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

    private fun drawCurrentFrame(updateTexImage: Boolean) {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        val dst = decoderSurfaceTexture ?: return
        val qb = quadBuffer ?: return
        try {
            if (EGL14.eglGetCurrentContext() != eglContext ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface
            ) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            if (updateTexImage) {
                dst.updateTexImage()
                dst.getTransformMatrix(stMatrix)
                hasReceivedFrame = true
            }
            if (!hasReceivedFrame) return

            val vw = max(1, viewWidth)
            val vh = max(1, viewHeight)
            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat().coerceAtLeast(1f)
            val viewAspect = vw.toFloat() / vh.toFloat()
            val cropScaleX: Float
            val cropScaleY: Float
            if (videoAspect > viewAspect) {
                cropScaleX = viewAspect / videoAspect
                cropScaleY = 1f
            } else {
                cropScaleX = 1f
                cropScaleY = videoAspect / viewAspect
            }

            GLES20.glDisable(GLES20.GL_BLEND)
            val blurPx = blurRadiusPx

            if (blurPx <= 0.35f) {
                // Fast zero-blur path: direct 1-tap OES render to screen
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, vw, vh)
                GLES20.glUseProgram(oesProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
                GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
                drawQuad(oesPosHandle, oesTexHandle, qb)
            } else {
                // Fixed pre-allocated 2-level Gaussian pyramid: zero texture reallocations on drag & step <= 1.10 (zero ringing)
                val maxLongEdge = if (isLegacyTier) 180 else 240
                val fboW: Int
                val fboH: Int
                if (vw >= vh) {
                    fboW = maxLongEdge
                    fboH = (maxLongEdge / viewAspect).roundToInt().coerceAtLeast(32)
                } else {
                    fboH = maxLongEdge
                    fboW = (maxLongEdge * viewAspect).roundToInt().coerceAtLeast(32)
                }
                ensureBlurFbos(fboW, fboH)
                val halfW = currentHalfFboW
                val halfH = currentHalfFboH

                // Pass 1: Sample OES video ONCE per compact FBO texel into FBO[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
                GLES20.glViewport(0, 0, fboW, fboH)
                GLES20.glUseProgram(oesProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
                GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
                drawQuad(oesPosHandle, oesTexHandle, qb)

                val invW = 1f / fboW.toFloat()
                val invH = 1f / fboH.toFloat()
                GLES20.glUseProgram(blurProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

                if (blurPx <= 10f) {
                    val step1 = 0.25f + (blurPx / 10f) * 0.80f
                    // H: FBO[0] -> FBO[1]
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
                    GLES20.glViewport(0, 0, fboW, fboH)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
                    GLES20.glUniform2f(blurTexelStepHandle, step1 * invW, 0f)
                    drawQuad(blurPosHandle, blurTexHandle, qb)

                    // V + bilinear upscale: FBO[1] -> Screen
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, vw, vh)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[1])
                    GLES20.glUniform2f(blurTexelStepHandle, 0f, step1 * invH)
                    drawQuad(blurPosHandle, blurTexHandle, qb)
                } else {
                    val t = ((blurPx - 10f) / 30f).coerceIn(0f, 1f)
                    val invHalfW = 1f / halfW.toFloat()
                    val invHalfH = 1f / halfH.toFloat()

                    // Level 1 pre-blur (step = 1.05): eliminates aliasing before 2x downsample
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
                    GLES20.glViewport(0, 0, fboW, fboH)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
                    GLES20.glUniform2f(blurTexelStepHandle, 1.05f * invW, 0f)
                    drawQuad(blurPosHandle, blurTexHandle, qb)

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[1])
                    GLES20.glUniform2f(blurTexelStepHandle, 0f, 1.05f * invH)
                    drawQuad(blurPosHandle, blurTexHandle, qb)

                    // Level 2 half-res blur (step = 0.45..1.10): ultra-smooth velvety bokeh with zero ringing
                    val step2 = 0.45f + t * 0.65f
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[2])
                    GLES20.glViewport(0, 0, halfW, halfH)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
                    GLES20.glUniform2f(blurTexelStepHandle, step2 * invW, 0f)
                    drawQuad(blurPosHandle, blurTexHandle, qb)

                    if (t > 0.35f) {
                        val step3 = ((t - 0.35f) / 0.65f) * 1.05f
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[3])
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[2])
                        GLES20.glUniform2f(blurTexelStepHandle, 0f, step2 * invHalfH)
                        drawQuad(blurPosHandle, blurTexHandle, qb)

                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[2])
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[3])
                        GLES20.glUniform2f(blurTexelStepHandle, step3 * invHalfW, 0f)
                        drawQuad(blurPosHandle, blurTexHandle, qb)

                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, vw, vh)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[2])
                        GLES20.glUniform2f(blurTexelStepHandle, 0f, step3 * invHalfH)
                        drawQuad(blurPosHandle, blurTexHandle, qb)
                    } else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, vw, vh)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[2])
                        GLES20.glUniform2f(blurTexelStepHandle, 0f, step2 * invHalfH)
                        drawQuad(blurPosHandle, blurTexHandle, qb)
                    }
                }
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (!notifiedFirstFrame) {
                notifiedFirstFrame = true
                onFirstFrameRenderedCallback?.let { cb ->
                    mainHandler.post { if (!released) cb() }
                }
            }
        } catch (_: Exception) {
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

    private fun releaseGlInternal() {
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

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                if (eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    deleteBlurFbos()
                    if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
                    if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
                }
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Exception) {
            }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

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

    companion object {
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
