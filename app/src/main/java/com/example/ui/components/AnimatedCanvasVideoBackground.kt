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
import kotlin.math.max

/**
 * Hardware-accelerated looping animated Song Canvas background rendered via
 * MediaPlayer -> OES SurfaceTexture -> EGL14 + GLES20 Gaussian Blur Shader -> TextureView.
 *
 * Works identically across Android 7–16 (API 24–36) without the black-screen or
 * zero-blur limitations of Modifier.blur on hardware TextureViews, supports real-time
 * 60fps blur dragging from 0px to 40px, plays both cached/remote HTTP(S) videos and
 * local file:// saved canvases, and cleanly releases EGL & MediaPlayer resources.
 */
@Composable
fun AnimatedCanvasVideoBackground(
    videoUrl: String,
    modifier: Modifier = Modifier,
    blurRadiusPx: Float = 0f,
    isPlaybackPaused: Boolean = false
) {
    val context = LocalContext.current
    val initialPlayUri = remember(videoUrl) {
        if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
            videoUrl
        } else {
            CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl)
                ?.let { Uri.fromFile(it).toString() }
                ?: videoUrl
        }
    }
    var activePlayUri by remember(videoUrl) { mutableStateOf(initialPlayUri) }
    var streamErrored by remember(videoUrl) { mutableStateOf(false) }

    var isVideoReady by remember(activePlayUri) { mutableStateOf(false) }
    var isPrepared by remember(activePlayUri) { mutableStateOf(false) }

    // Download remote Canvas MP4 to local cache automatically so playback works reliably on Home even if remote MediaPlayer streaming fails
    LaunchedEffect(videoUrl) {
        if (!videoUrl.startsWith("file://") && !videoUrl.startsWith("content://")) {
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
        animationSpec = tween(durationMillis = 240),
        label = "canvas_video_fade"
    )

    var mediaPlayerRef by remember(activePlayUri) { mutableStateOf<MediaPlayer?>(null) }
    var glRendererRef by remember(activePlayUri) { mutableStateOf<PreviewGlRendererController?>(null) }
    var isLifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Propagate blur slider changes immediately at 60fps (even while paused)
    LaunchedEffect(blurRadiusPx, glRendererRef) {
        glRendererRef?.updateBlurRadius(blurRadiusPx)
    }

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

    LaunchedEffect(mediaPlayerRef, isPrepared, isPlaybackPaused, isLifecycleResumed) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        try {
            if (isPlaybackPaused || !isLifecycleResumed) {
                if (mp.isPlaying) mp.pause()
            } else {
                if (!mp.isPlaying) mp.start()
            }
        } catch (_: Exception) {
        }
    }

    DisposableEffect(activePlayUri) {
        onDispose {
            isPrepared = false
            isVideoReady = false
            try {
                mediaPlayerRef?.stop()
            } catch (_: Exception) {
            }
            try {
                mediaPlayerRef?.release()
            } catch (_: Exception) {
            }
            mediaPlayerRef = null
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
                val useFastBlur = DevicePerformanceTier.get(viewCtx).isLegacyRenderTier
                TextureView(viewCtx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            try {
                                mediaPlayerRef?.release()
                            } catch (_: Exception) {
                            }
                            glRendererRef?.release()

                            val controller = PreviewGlRendererController(
                                outputSurfaceTexture = surfaceTexture,
                                initialWidth = max(1, width),
                                initialHeight = max(1, height),
                                initialBlurPx = blurRadiusPx,
                                useFastBlurShader = useFastBlur
                            )
                            glRendererRef = controller

                            controller.start { decoderSurface ->
                                if (decoderSurface == null) {
                                    isPrepared = false
                                    isVideoReady = false
                                    return@start
                                }
                                post {
                                    setupMediaPlayerForUrl(
                                        context = viewCtx,
                                        videoUrl = activePlayUri,
                                        decoderSurface = decoderSurface,
                                        isPlaybackPaused = { isPlaybackPaused || !isLifecycleResumed },
                                        onVideoSize = { vw, vh -> controller.updateVideoSize(vw, vh) },
                                        onPreparedReady = { mp ->
                                            mediaPlayerRef = mp
                                            isPrepared = true
                                            controller.updateVideoSize(mp.videoWidth, mp.videoHeight)
                                            controller.requestRender()
                                            isVideoReady = true
                                        },
                                        onError = {
                                            isPrepared = false
                                            isVideoReady = false
                                            streamErrored = true
                                        }
                                    )
                                }
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
                            try {
                                mediaPlayerRef?.stop()
                            } catch (_: Exception) {
                            }
                            try {
                                mediaPlayerRef?.release()
                            } catch (_: Exception) {
                            }
                            mediaPlayerRef = null
                            glRendererRef?.release()
                            glRendererRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            }
        )
    }
}

private fun setupMediaPlayerForUrl(
    context: Context,
    videoUrl: String,
    decoderSurface: Surface,
    isPlaybackPaused: () -> Boolean,
    onVideoSize: (Int, Int) -> Unit,
    onPreparedReady: (MediaPlayer) -> Unit,
    onError: () -> Unit
) {
    val mp = MediaPlayer()
    try {
        mp.setSurface(decoderSurface)
        val cachedFile = CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl)
        if (cachedFile != null && cachedFile.exists()) {
            mp.setDataSource(context, Uri.fromFile(cachedFile))
        } else if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) {
            mp.setDataSource(context, Uri.parse(videoUrl))
        } else {
            val headers = mapOf("User-Agent" to SpotifyAuthManager.DESKTOP_BROWSER_UA)
            mp.setDataSource(context, Uri.parse(videoUrl), headers)
        }
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnVideoSizeChangedListener { _, vw, vh ->
            onVideoSize(vw, vh)
        }
        mp.setOnPreparedListener { preparedMp ->
            try {
                preparedMp.isLooping = true
                preparedMp.setVolume(0f, 0f)
                if (!isPlaybackPaused()) {
                    preparedMp.start()
                }
                onPreparedReady(preparedMp)
            } catch (_: Exception) {
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            onError()
            true
        }
        mp.prepareAsync()
    } catch (_: Exception) {
        try {
            mp.release()
        } catch (_: Exception) {
        }
        onError()
    }
}

/**
 * Dedicated GL thread rendering OES video frames onto a TextureView SurfaceTexture
 * with center-crop scaling and a smooth 13-tap 2-ring Gaussian blur shader.
 */
private class PreviewGlRendererController(
    private val outputSurfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
    initialBlurPx: Float,
    private val useFastBlurShader: Boolean
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

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTextureId: Int = 0
    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null

    private var program: Int = 0
    private var posHandle: Int = 0
    private var texHandle: Int = 0
    private var stMatrixHandle: Int = 0
    private var cropScaleHandle: Int = 0
    private var blurHandle: Int = 0
    private var fastModeHandle: Int = 0
    private var quadBuffer: FloatBuffer? = null
    private val stMatrix = FloatArray(16)

    fun start(onSurfaceReady: (Surface?) -> Unit) {
        glHandler.post {
            if (released) {
                onSurfaceReady(null)
                return@post
            }
            val ok = initGl()
            if (ok) {
                onSurfaceReady(decoderSurface)
            } else {
                onSurfaceReady(null)
            }
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
        glHandler.post {
            if (!released && hasReceivedFrame) {
                drawCurrentFrame(updateTexImage = false)
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

            program = createProgram(VERT_SHADER, FRAG_SHADER)
            posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            stMatrixHandle = GLES20.glGetUniformLocation(program, "uStMatrix")
            cropScaleHandle = GLES20.glGetUniformLocation(program, "uCropScale")
            blurHandle = GLES20.glGetUniformLocation(program, "uBlurRadius")
            fastModeHandle = GLES20.glGetUniformLocation(program, "uFastMode")

            decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener({
                    if (!released) {
                        drawCurrentFrame(updateTexImage = true)
                    }
                }, glHandler)
            }
            decoderSurface = Surface(decoderSurfaceTexture)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun drawCurrentFrame(updateTexImage: Boolean) {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        val dst = decoderSurfaceTexture ?: return
        val qb = quadBuffer ?: return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (updateTexImage) {
                dst.updateTexImage()
                dst.getTransformMatrix(stMatrix)
                hasReceivedFrame = true
            }
            val vw = max(1, viewWidth)
            val vh = max(1, viewHeight)
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

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
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glUniform2f(cropScaleHandle, cropScaleX, cropScaleY)
            GLES20.glUniform1f(blurHandle, (blurRadiusPx / 40f).coerceIn(0f, 1f) * 0.015f)
            GLES20.glUniform1f(fastModeHandle, if (useFastBlurShader) 1f else 0f)

            qb.position(0)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, qb)
            GLES20.glEnableVertexAttribArray(posHandle)
            qb.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, qb)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (_: Exception) {
        }
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
                    if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
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
        private const val VERT_SHADER = """
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

        private const val FRAG_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform float uBlurRadius;
            uniform float uFastMode;
            varying vec2 vTexCoord;
            void main() {
                if (uBlurRadius <= 0.0004) {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                } else if (uFastMode > 0.5) {
                    float r = uBlurRadius * 1.15;
                    vec4 sum = texture2D(sTexture, vTexCoord) * 0.24;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(r, r * 0.65), 0.0, 1.0)) * 0.19;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-r, r * 0.65), 0.0, 1.0)) * 0.19;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(r * 0.65, -r), 0.0, 1.0)) * 0.19;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-r * 0.65, -r), 0.0, 1.0)) * 0.19;
                    gl_FragColor = sum;
                } else {
                    float r = uBlurRadius;
                    vec4 sum = texture2D(sTexture, vTexCoord) * 0.16;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(r, 0.0), 0.0, 1.0)) * 0.10;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-r, 0.0), 0.0, 1.0)) * 0.10;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, r), 0.0, 1.0)) * 0.10;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, -r), 0.0, 1.0)) * 0.10;
                    float d = r * 0.707;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(d, d), 0.0, 1.0)) * 0.07;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-d, d), 0.0, 1.0)) * 0.07;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(d, -d), 0.0, 1.0)) * 0.07;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-d, -d), 0.0, 1.0)) * 0.07;
                    float r2 = r * 1.85;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(r2, 0.0), 0.0, 1.0)) * 0.04;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(-r2, 0.0), 0.0, 1.0)) * 0.04;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, r2), 0.0, 1.0)) * 0.04;
                    sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, -r2), 0.0, 1.0)) * 0.04;
                    gl_FragColor = sum;
                }
            }
        """
    }
}
