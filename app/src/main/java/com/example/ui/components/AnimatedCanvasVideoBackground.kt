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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hardware-accelerated looping animated Song Canvas background rendered via TextureView + MediaPlayer.
 * Respects Compose z-order behind dialogs/sheets, performs exact center-crop aspect scaling
 * without stretching or black bars, and fades in seamlessly over the still background once prepared.
 */
@Composable
fun AnimatedCanvasVideoBackground(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    var isVideoReady by remember(videoUrl) { mutableStateOf(false) }
    var videoWidth by remember(videoUrl) { mutableIntStateOf(0) }
    var videoHeight by remember(videoUrl) { mutableIntStateOf(0) }
    val fadeAlpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvas_video_fade"
    )

    var mediaPlayerRef by remember(videoUrl) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(videoUrl) {
        onDispose {
            try {
                mediaPlayerRef?.stop()
            } catch (_: Exception) {
            }
            try {
                mediaPlayerRef?.release()
            } catch (_: Exception) {
            }
            mediaPlayerRef = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .alpha(fadeAlpha),
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        val surface = Surface(surfaceTexture)
                        try {
                            mediaPlayerRef?.release()
                        } catch (_: Exception) {
                        }
                        val mp = MediaPlayer()
                        mediaPlayerRef = mp
                        try {
                            mp.setSurface(surface)
                            mp.setDataSource(context, Uri.parse(videoUrl))
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                videoWidth = vw
                                videoHeight = vh
                                applyCenterCropMatrix(this@apply, vw, vh)
                            }
                            mp.setOnPreparedListener { preparedMp ->
                                try {
                                    preparedMp.isLooping = true
                                    preparedMp.setVolume(0f, 0f)
                                    applyCenterCropMatrix(
                                        this@apply,
                                        preparedMp.videoWidth,
                                        preparedMp.videoHeight
                                    )
                                    preparedMp.start()
                                    isVideoReady = true
                                } catch (_: Exception) {
                                }
                            }
                            mp.setOnErrorListener { _, _, _ ->
                                isVideoReady = false
                                true
                            }
                            mp.prepareAsync()
                        } catch (_: Exception) {
                            isVideoReady = false
                            try {
                                mp.release()
                            } catch (_: Exception) {
                            }
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
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                }
            }
        }
    )
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
