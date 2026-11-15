package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Hardware-accelerated 9:16 MP4 story video compositor for Animated Song Canvases.
 *
 * Decodes the active Canvas MP4 loop via MediaExtractor + MediaCodec -> SurfaceTexture (OES),
 * applies center-crop scaling + optional GPU blur + foreground transparent share-card overlay
 * (including dark contrast scrim, crisp album cover, title/artist/lyrics, and brand badge),
 * and encodes a smooth 720x1280 MP4 video ready for Gallery, Instagram/Snapchat Stories, WhatsApp, or Discord.
 */
object CanvasVideoExporter {

    private const val TAG = "CanvasVideoExporter"
    private const val OUT_WIDTH = 720
    private const val OUT_HEIGHT = 1280
    private const val TARGET_BITRATE = 4_000_000
    private const val TARGET_FPS = 30
    private const val MIN_LOOP_DURATION_US = 6_000_000L
    private const val MAX_EXPORT_DURATION_US = 10_000_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    suspend fun exportAnimatedStoryVideo(
        context: Context,
        canvasVideoUrl: String,
        overlayBitmap: Bitmap,
        blurRadiusPx: Float,
        outputFileName: String
    ): File? = withContext(Dispatchers.IO) {
        val sourceFile = downloadVideoToCache(context, canvasVideoUrl) ?: return@withContext null

        val outDir = File(context.cacheDir, "cards").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)
        if (outFile.exists()) outFile.delete()

        val success = transcodeWithOverlay(
            sourceFile = sourceFile,
            outFile = outFile,
            overlayBitmap = overlayBitmap,
            blurIntensity = (blurRadiusPx / 40f).coerceIn(0f, 1f)
        )

        if (success && outFile.exists() && outFile.length() > 4096L) {
            outFile
        } else {
            outFile.delete()
            null
        }
    }

    private fun downloadVideoToCache(context: Context, videoUrl: String): File? {
        val cacheDir = File(context.cacheDir, "canvas_video_cache").apply { mkdirs() }
        val cacheKey = videoUrl.hashCode().toUInt().toString(16)
        val cached = File(cacheDir, "canvas_$cacheKey.mp4")
        if (cached.exists() && cached.length() > 10_240L) {
            return cached
        }

        // Keep cache bounded on low-storage devices like Redmi 9C
        cacheDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(4)
            ?.forEach { it.delete() }

        val tempFile = File(cacheDir, "tmp_$cacheKey.mp4")
        var currentUrl = videoUrl
        var redirects = 0
        while (redirects < 4) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "EchoSnap/1.0.1 Android")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) return null

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 16_384)
                        output.flush()
                    }
                }
                if (tempFile.length() > 10_240L) {
                    if (cached.exists()) cached.delete()
                    tempFile.renameTo(cached)
                    return cached
                }
                tempFile.delete()
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Failed downloading canvas video: ${e.message}")
                tempFile.delete()
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun transcodeWithOverlay(
        sourceFile: File,
        outFile: File,
        overlayBitmap: Bitmap,
        blurIntensity: Float
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var encoderInputSurface: Surface? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var frameThread: HandlerThread? = null

        return try {
            extractor = MediaExtractor().apply {
                setDataSource(sourceFile.absolutePath)
            }
            var videoTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (videoTrackIndex < 0 || inputFormat == null) return false
            extractor.selectTrack(videoTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val rawW = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawH = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = readVideoRotation(sourceFile, inputFormat)
            val sourceDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val effectiveW = if (rotation == 90 || rotation == 270) rawH else rawW
            val effectiveH = if (rotation == 90 || rotation == 270) rawW else rawH

            // Configure H.264 encoder
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                OUT_WIDTH,
                OUT_HEIGHT
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // Set up EGL14 + GLES20 context bound to encoder's Surface
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
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                return false
            }
            val eglConfig = configs[0] ?: return false

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInputSurface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            val renderer = StoryVideoGlRenderer(
                videoWidth = max(1, effectiveW),
                videoHeight = max(1, effectiveH),
                outWidth = OUT_WIDTH,
                outHeight = OUT_HEIGHT,
                overlayBitmap = overlayBitmap,
                blurIntensity = blurIntensity
            )

            frameThread = HandlerThread("CanvasFrameCallback").apply { start() }
            val frameHandler = Handler(frameThread.looper)
            val frameSyncLock = Object()
            var frameAvailable = false

            surfaceTexture = SurfaceTexture(renderer.videoTextureId).apply {
                setOnFrameAvailableListener({
                    synchronized(frameSyncLock) {
                        frameAvailable = true
                        frameSyncLock.notifyAll()
                    }
                }, frameHandler)
            }
            decoderSurface = Surface(surfaceTexture)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1

            val decBufInfo = MediaCodec.BufferInfo()
            val encBufInfo = MediaCodec.BufferInfo()
            val stMatrix = FloatArray(16)

            val shouldLoopShortVideo = sourceDurationUs in 1L until MIN_LOOP_DURATION_US
            val targetDurationUs = if (shouldLoopShortVideo) MIN_LOOP_DURATION_US else MAX_EXPORT_DURATION_US

            var inputDone = false
            var outputDone = false
            var ptsBaseUs = 0L
            var lastEncodedPtsUs = -1L

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                while (true) {
                    val encIndex = encoder.dequeueOutputBuffer(encBufInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("Encoder format changed twice")
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(encIndex)
                            if (encodedData != null && (encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encBufInfo.size != 0 && muxerStarted) {
                                encodedData.position(encBufInfo.offset)
                                encodedData.limit(encBufInfo.offset + encBufInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encodedData, encBufInfo)
                            }
                            encoder.releaseOutputBuffer(encIndex, false)
                            if ((encBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                return
                            }
                        }
                    }
                }
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(decBufInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex >= 0 -> {
                        val isEos = (decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val candidatePtsUs = ptsBaseUs + max(0L, decBufInfo.presentationTimeUs)
                        val overMaxDuration = candidatePtsUs > targetDurationUs
                        val doRender = decBufInfo.size != 0 && !overMaxDuration

                        decoder.releaseOutputBuffer(outIndex, doRender)

                        if (doRender) {
                            val gotFrame = synchronized(frameSyncLock) {
                                var deadline = System.currentTimeMillis() + 500L
                                while (!frameAvailable) {
                                    val waitMs = deadline - System.currentTimeMillis()
                                    if (waitMs <= 0L) break
                                    frameSyncLock.wait(waitMs)
                                }
                                val ok = frameAvailable
                                frameAvailable = false
                                ok
                            }
                            if (gotFrame) {
                                surfaceTexture.updateTexImage()
                                surfaceTexture.getTransformMatrix(stMatrix)
                                renderer.drawFrame(stMatrix)
                                val safePtsUs = if (candidatePtsUs <= lastEncodedPtsUs) {
                                    lastEncodedPtsUs + 33_333L
                                } else {
                                    candidatePtsUs
                                }
                                lastEncodedPtsUs = safePtsUs
                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, safePtsUs * 1000L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                                drainEncoder(endOfStream = false)
                            }
                        }

                        if (overMaxDuration) {
                            drainEncoder(endOfStream = true)
                            outputDone = true
                        } else if (isEos) {
                            if (shouldLoopShortVideo && lastEncodedPtsUs < MIN_LOOP_DURATION_US) {
                                ptsBaseUs = max(0L, lastEncodedPtsUs + 33_333L)
                                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                decoder.flush()
                                inputDone = false
                            } else {
                                drainEncoder(endOfStream = true)
                                outputDone = true
                            }
                        }
                    }
                }
            }

            muxerStarted && lastEncodedPtsUs >= 0L
        } catch (e: Exception) {
            Log.e(TAG, "Animated video export failed", e)
            false
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                decoderSurface?.release()
            } catch (_: Exception) {
            }
            try {
                surfaceTexture?.release()
            } catch (_: Exception) {
            }
            try {
                frameThread?.quitSafely()
            } catch (_: Exception) {
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                } catch (_: Exception) {
                }
            }
            try {
                encoderInputSurface?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readVideoRotation(file: File, format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            return format.getInteger(MediaFormat.KEY_ROTATION)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private class StoryVideoGlRenderer(
        videoWidth: Int,
        videoHeight: Int,
        outWidth: Int,
        outHeight: Int,
        overlayBitmap: Bitmap,
        private val blurIntensity: Float
    ) {
        val videoTextureId: Int
        private val overlayTextureId: Int

        private val oesProgram: Int
        private val oesPosHandle: Int
        private val oesTexHandle: Int
        private val oesStMatrixHandle: Int
        private val oesCropScaleHandle: Int
        private val oesBlurHandle: Int

        private val overlayProgram: Int
        private val overlayPosHandle: Int
        private val overlayTexHandle: Int

        private val videoQuadBuffer: FloatBuffer
        private val overlayQuadBuffer: FloatBuffer
        private val cropScaleX: Float
        private val cropScaleY: Float

        init {
            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            val outAspect = outWidth.toFloat() / outHeight.toFloat()
            if (videoAspect > outAspect) {
                cropScaleX = outAspect / videoAspect
                cropScaleY = 1f
            } else {
                cropScaleX = 1f
                cropScaleY = videoAspect / outAspect
            }

            // Bottom-left (0,0) -> Top-right (1,1) for SurfaceTexture OES UV space
            val videoVertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            videoQuadBuffer = ByteBuffer.allocateDirect(videoVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(videoVertices)
            videoQuadBuffer.position(0)

            // Bitmap uploaded via GLUtils.texImage2D has Y=0 at top, so flip V (1 -> 0)
            val overlayVertices = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            overlayQuadBuffer = ByteBuffer.allocateDirect(overlayVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(overlayVertices)
            overlayQuadBuffer.position(0)

            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            videoTextureId = textures[0]
            overlayTextureId = textures[1]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)

            oesProgram = createProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
            oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
            oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
            oesStMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uStMatrix")
            oesCropScaleHandle = GLES20.glGetUniformLocation(oesProgram, "uCropScale")
            oesBlurHandle = GLES20.glGetUniformLocation(oesProgram, "uBlurRadius")

            overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
            overlayPosHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
            overlayTexHandle = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        }

        fun drawFrame(stMatrix: FloatArray) {
            GLES20.glViewport(0, 0, OUT_WIDTH, OUT_HEIGHT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 1. Background looping Canvas video frame (center-cropped + optional blur)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(oesProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
            GLES20.glUniform1f(oesBlurHandle, blurIntensity * 0.012f)

            videoQuadBuffer.position(0)
            GLES20.glVertexAttribPointer(oesPosHandle, 2, GLES20.GL_FLOAT, false, 16, videoQuadBuffer)
            GLES20.glEnableVertexAttribArray(oesPosHandle)
            videoQuadBuffer.position(2)
            GLES20.glVertexAttribPointer(oesTexHandle, 2, GLES20.GL_FLOAT, false, 16, videoQuadBuffer)
            GLES20.glEnableVertexAttribArray(oesTexHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 2. Foreground transparent Share Card overlay (premultiplied alpha)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(overlayProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)

            overlayQuadBuffer.position(0)
            GLES20.glVertexAttribPointer(overlayPosHandle, 2, GLES20.GL_FLOAT, false, 16, overlayQuadBuffer)
            GLES20.glEnableVertexAttribArray(overlayPosHandle)
            overlayQuadBuffer.position(2)
            GLES20.glVertexAttribPointer(overlayTexHandle, 2, GLES20.GL_FLOAT, false, 16, overlayQuadBuffer)
            GLES20.glEnableVertexAttribArray(overlayTexHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glFinish()
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
            private const val OES_VERTEX_SHADER = """
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

            private const val OES_FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                uniform float uBlurRadius;
                varying vec2 vTexCoord;
                void main() {
                    if (uBlurRadius <= 0.0005) {
                        gl_FragColor = texture2D(sTexture, vTexCoord);
                    } else {
                        vec4 sum = texture2D(sTexture, vTexCoord) * 0.20;
                        float r = uBlurRadius;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(r, 0.0), 0.0, 1.0)) * 0.12;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(-r, 0.0), 0.0, 1.0)) * 0.12;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, r), 0.0, 1.0)) * 0.12;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(0.0, -r), 0.0, 1.0)) * 0.12;
                        float d = r * 0.707;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(d, d), 0.0, 1.0)) * 0.08;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(-d, d), 0.0, 1.0)) * 0.08;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(d, -d), 0.0, 1.0)) * 0.08;
                        sum += texture2D(sTexture, clamp(vTexCoord + vec2(-d, -d), 0.0, 1.0)) * 0.08;
                        gl_FragColor = sum;
                    }
                }
            """

            private const val OVERLAY_VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            private const val OVERLAY_FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D sTexture;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }
}
