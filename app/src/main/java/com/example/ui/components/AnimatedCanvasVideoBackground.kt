package com.example.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.SpotifyAuthManager
import com.example.util.CanvasVideoExporter

/**
 * Hardware-accelerated looping animated Song Canvas background rendered via TextureView + MediaPlayer.
 * Respects Compose z-order behind dialogs/sheets, plays from local MP4 cache when available
 * (while prefetching remote streams in the background for instant Preview switching),
 * performs exact center-crop aspect scaling without stretching or black bars, and releases
 * cleanly on dispose.
 */
@Composable
fun AnimatedCanvasVideoBackground(
    videoUrl: String,
    modifier: Modifier = Modifier,
    isPlaybackPaused: Boolean = false
) {
    val context = LocalContext.current
    var isVideoReady by remember(videoUrl) { mutableStateOf(false) }
    var isPrepared by remember(videoUrl) { mutableStateOf(false) }
    var videoWidth by remember(videoUrl) { mutableIntStateOf(0) }
    var videoHeight by remember(videoUrl) { mutableIntStateOf(0) }
    val fadeAlpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "canvas_video_fade"
    )

    var mediaPlayerRef by remember(videoUrl) { mutableStateOf<MediaPlayer?>(null) }
    var surfaceRef by remember(videoUrl) { mutableStateOf<Surface?>(null) }
    var isLifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Prefetch remote Canvas MP4 to cache in the background so Preview & Export start instantaneously
    LaunchedEffect(videoUrl) {
        if (CanvasVideoExporter.getCachedVideoFileIfReady(context, videoUrl) == null) {
            CanvasVideoExporter.prefetchVideoToCache(context, videoUrl)
        }
    }

    DisposableEffect(lifecycleOwner, videoUrl) {
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

    DisposableEffect(videoUrl) {
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
            try {
                surfaceRef?.release()
            } catch (_: Exception) {
            }
            surfaceRef = null
        }
    }

    key(videoUrl) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .alpha(fadeAlpha),
            factory = { viewCtx ->
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
                            try {
                                surfaceRef?.release()
                            } catch (_: Exception) {
                            }

                            val surface = Surface(surfaceTexture)
                            surfaceRef = surface
                            val mp = MediaPlayer()
                            mediaPlayerRef = mp
                            isPrepared = false
                            try {
                                mp.setSurface(surface)
                                val cachedFile = CanvasVideoExporter.getCachedVideoFileIfReady(viewCtx, videoUrl)
                                if (cachedFile != null) {
                                    mp.setDataSource(viewCtx, Uri.fromFile(cachedFile))
                                } else {
                                    val headers = mapOf(
                                        "User-Agent" to SpotifyAuthManager.DESKTOP_BROWSER_UA
                                    )
                                    mp.setDataSource(viewCtx, Uri.parse(videoUrl), headers)
                                }
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)
                                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                    videoWidth = vw
                                    videoHeight = vh
                                    applyCenterCropMatrix(this@apply, vw, vh)
                                }
                                mp.setOnInfoListener { _, what, _ ->
                                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                        isVideoReady = true
                                    }
                                    false
                                }
                                mp.setOnPreparedListener { preparedMp ->
                                    try {
                                        preparedMp.isLooping = true
                                        preparedMp.setVolume(0f, 0f)
                                        videoWidth = preparedMp.videoWidth
                                        videoHeight = preparedMp.videoHeight
                                        applyCenterCropMatrix(
                                            this@apply,
                                            preparedMp.videoWidth,
                                            preparedMp.videoHeight
                                        )
                                        isPrepared = true
                                        if (!isPlaybackPaused && isLifecycleResumed) {
                                            preparedMp.start()
                                        }
                                        isVideoReady = true
                                    } catch (_: Exception) {
                                    }
                                }
                                mp.setOnErrorListener { _, _, _ ->
                                    isPrepared = false
                                    isVideoReady = false
                                    true
                                }
                                mp.prepareAsync()
                            } catch (_: Exception) {
                                isPrepared = false
                                isVideoReady = false
                                try {
                                    mp.release()
                                } catch (_: Exception) {
                                }
                                mediaPlayerRef = null
                                try {
                                    surface.release()
                                } catch (_: Exception) {
                                }
                                if (surfaceRef === surface) surfaceRef = null
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            applyCenterCropMatrix(this@apply, videoWidth, videoHeight)
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
                            try {
                                surfaceRef?.release()
                            } catch (_: Exception) {
                            }
                            surfaceRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            }
        )
    }
}

private fun applyCenterCropMatrix(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int
) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || videoWidth <= 0 || videoHeight <= 0) return

    val vw = videoWidth.toFloat()
    val vh = videoHeight.toFloat()
    val scaleX: Float
    val scaleY: Float
    if (vw / vh > viewW / viewH) {
        scaleX = (vw / vh) / (viewW / viewH)
        scaleY = 1f
    } else {
        scaleX = 1f
        scaleY = (viewW / viewH) / (vw / vh)
    }

    val matrix = Matrix()
    matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
    textureView.setTransform(matrix)
}
