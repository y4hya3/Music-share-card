package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class WaveformExportSpec(
    val rectIn720x1280: RectF,
    val waveformColorArgb: Int,
    val cardOpacity: Float,
    val trackTitle: String,
    val trackArtist: String,
    val isPlaying: Boolean,
    val speedMultiplier: Float = 1.0f,
    val beatSyncEnabled: Boolean = false,
    val forceMultiplier: Float = 1.35f
)

/**
 * Hardware-accelerated 9:16 MP4 story video compositor for Animated Song Canvases and
 * 8-second Dynamic Island Waveform motion videos over static backgrounds.
 *
 * Renders:
 * 1) Background layer: either looping OES video (with 13-tap GLSL Gaussian blur) OR crisp 720x1280 photo/gradient.
 * 2) Foreground share-card overlay (`ARGB_8888` PNG layer).
 * 3) Optional live-rendered 4-bar Dynamic Island waveform synced cleanly per frame timestamp.
 */
object CanvasVideoExporter {

    private const val TAG = "CanvasVideoExporter"
    const val OUT_WIDTH = 720
    const val OUT_HEIGHT = 1280
    private const val TARGET_BITRATE = 4_200_000
    private const val TARGET_FPS = 30
    private const val MIN_LOOP_DURATION_US = 6_000_000L
    const val WAVEFORM_LOOP_DURATION_US = 8_000_000L
    private const val MAX_EXPORT_DURATION_US = 10_000_000L
    private const val STATIC_WAVEFORM_DURATION_SEC = 8
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private val cacheDownloadLocks = ConcurrentHashMap<String, Any>()

    suspend fun exportAnimatedStoryVideo(
        context: Context,
        canvasVideoUrl: String,
        overlayBitmap: Bitmap,
        blurRadiusPx: Float,
        outputFileName: String,
        waveformSpec: WaveformExportSpec? = null,
        onProgress: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        onProgress?.invoke(3)
        val sourceFile = resolveVideoFile(context, canvasVideoUrl) ?: return@withContext null
        onProgress?.invoke(15)

        val outDir = prepareOutputDir(context)
        val outFile = File(outDir, outputFileName)
        if (outFile.exists()) outFile.delete()

        val success = transcodeWithOverlay(
            sourceFile = sourceFile,
            outFile = outFile,
            overlayBitmap = overlayBitmap,
            blurIntensity = (blurRadiusPx / 40f).coerceIn(0f, 1f),
            waveformSpec = waveformSpec,
            onProgress = onProgress
        )

        if (success && outFile.exists() && outFile.length() > 4096L) {
            onProgress?.invoke(100)
            outFile
        } else {
            outFile.delete()
            null
        }
    }

    /**
     * Generates an 8-second (240 frames @ 30fps) 720x1280 MP4 video combining a static
     * background image, floating glass card overlay, and animated 4-bar Dynamic Island waveform.
     */
    suspend fun exportStaticBackgroundWaveformVideo(
        context: Context,
        backgroundBitmap: Bitmap,
        overlayBitmap: Bitmap,
        waveformSpec: WaveformExportSpec,
        outputFileName: String,
        onProgress: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val outDir = prepareOutputDir(context)
        val outFile = File(outDir, outputFileName)
        if (outFile.exists()) outFile.delete()
        onProgress?.invoke(4)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var encoderInputSurface: Surface? = null
        var renderer: StoryVideoGlRenderer? = null

        val success = try {
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

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return@withContext null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return@withContext null

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
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) {
                return@withContext null
            }
            val eglConfig = configs[0] ?: return@withContext null
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return@withContext null

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInputSurface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return@withContext null
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return@withContext null

            renderer = StoryVideoGlRenderer(
                videoWidth = OUT_WIDTH,
                videoHeight = OUT_HEIGHT,
                outWidth = OUT_WIDTH,
                outHeight = OUT_HEIGHT,
                overlayBitmap = overlayBitmap,
                blurIntensity = 0f,
                staticBackgroundBitmap = backgroundBitmap,
                waveformSpec = waveformSpec
            )

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            val encBufInfo = MediaCodec.BufferInfo()

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) encoder.signalEndOfInputStream()
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
                            if (encodedData != null &&
                                (encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                encBufInfo.size != 0 &&
                                muxerStarted
                            ) {
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

            val totalFrames = STATIC_WAVEFORM_DURATION_SEC * TARGET_FPS
            val frameDurationNs = 1_000_000_000L / TARGET_FPS
            var lastReportedPct = 4
            for (frameIdx in 0 until totalFrames) {
                val timeSec = frameIdx.toFloat() / TARGET_FPS.toFloat()
                renderer.drawStaticFrame(timeSec = timeSec)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameIdx.toLong() * frameDurationNs)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                drainEncoder(endOfStream = false)
                val pct = (5 + (frameIdx * 93) / totalFrames.coerceAtLeast(1)).coerceIn(5, 98)
                if (pct - lastReportedPct >= 8) {
                    lastReportedPct = pct
                    onProgress?.invoke(pct)
                }
            }
            drainEncoder(endOfStream = true)
            if (muxerStarted) onProgress?.invoke(100)
            muxerStarted
        } catch (e: Exception) {
            Log.e(TAG, "Static background 8s waveform export failed", e)
            false
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
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
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    if (eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        renderer?.release()
                    }
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

        if (success && outFile.exists() && outFile.length() > 4096L) {
            outFile
        } else {
            outFile.delete()
            null
        }
    }

    private const val CANVAS_CACHE_DIR_NAME = "canvas_video_cache"
    private const val PREFS_TRACK_CANVAS_INDEX = "echosnap_canvas_disk_index"
    private const val KEY_TRACK_INDEX_JSON = "track_index_entries"
    private const val MAX_TRACK_INDEX_ENTRIES = 64

    private fun getDurableCacheDir(context: Context): File =
        File(context.applicationContext.filesDir, CANVAS_CACHE_DIR_NAME).apply { mkdirs() }

    private fun getLegacyCacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, CANVAS_CACHE_DIR_NAME)

    fun normalizeTrackCacheKey(title: String, artist: String): String {
        val normTitle = com.example.service.AnimatedCanvasSearchService.normalizeQueryText(title)
            .ifBlank { title.trim() }
            .lowercase()
        val normArtist = com.example.service.AnimatedCanvasSearchService.normalizeArtistText(artist)
            .lowercase()
        return "$normTitle|$normArtist"
    }

    fun getCachedVideoFileIfReady(context: Context, videoUrl: String): File? {
        if (videoUrl.startsWith("file://")) {
            val path = Uri.parse(videoUrl).path ?: return null
            val f = File(path)
            return if (isLikelyValidVideoFile(f)) f else null
        }
        val shaKey = cacheKeyForUrl(videoUrl)
        val legacyKey = legacyCacheKeyForUrl(videoUrl)
        val durableDir = getDurableCacheDir(context)
        val legacyDir = getLegacyCacheDir(context)

        val candidates = listOf(
            File(durableDir, "canvas_$shaKey.mp4"),
            File(durableDir, "canvas_$legacyKey.mp4"),
            File(legacyDir, "canvas_$shaKey.mp4"),
            File(legacyDir, "canvas_$legacyKey.mp4")
        )
        for (candidate in candidates) {
            if (isLikelyValidVideoFile(candidate)) {
                candidate.setLastModified(System.currentTimeMillis())
                return candidate
            }
            if (candidate.exists()) runCatching { candidate.delete() }
        }
        return null
    }

    fun getCachedVideoFileForTrack(context: Context, title: String, artist: String): Pair<File, String>? {
        val trackKey = normalizeTrackCacheKey(title, artist)
        if (trackKey == "|") return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS_TRACK_CANVAS_INDEX, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TRACK_INDEX_JSON, null) ?: return null
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        val entry = json.optJSONObject(trackKey) ?: return null
        val filePath = entry.optString("path").trim()
        val sourceLabel = entry.optString("source").ifBlank { "Cached" }
        if (filePath.isEmpty()) return null
        val file = File(filePath)
        return if (isLikelyValidVideoFile(file)) {
            file.setLastModified(System.currentTimeMillis())
            entry.put("lastUsedMs", System.currentTimeMillis())
            prefs.edit().putString(KEY_TRACK_INDEX_JSON, json.toString()).apply()
            file to sourceLabel
        } else {
            json.remove(trackKey)
            prefs.edit().putString(KEY_TRACK_INDEX_JSON, json.toString()).apply()
            null
        }
    }

    fun recordTrackCanvasCache(
        context: Context,
        title: String,
        artist: String,
        source: String,
        cachedFile: File
    ) {
        if (!isLikelyValidVideoFile(cachedFile)) return
        val trackKey = normalizeTrackCacheKey(title, artist)
        if (trackKey == "|") return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_TRACK_CANVAS_INDEX, Context.MODE_PRIVATE)
        synchronized(cacheDownloadLocks) {
            val json = runCatching {
                prefs.getString(KEY_TRACK_INDEX_JSON, null)?.let { org.json.JSONObject(it) }
            }.getOrNull() ?: org.json.JSONObject()

            val entry = org.json.JSONObject().apply {
                put("path", cachedFile.absolutePath)
                put("source", source.ifBlank { "Cached" })
                put("lastUsedMs", System.currentTimeMillis())
            }
            json.put(trackKey, entry)

            // Prune missing files & keep index bounded to MAX_TRACK_INDEX_ENTRIES
            val keys = json.keys().asSequence().toList()
            val validEntries = keys.mapNotNull { k ->
                val obj = json.optJSONObject(k) ?: return@mapNotNull null
                val path = obj.optString("path")
                if (path.isBlank() || !File(path).exists()) {
                    json.remove(k)
                    null
                } else {
                    k to obj.optLong("lastUsedMs", 0L)
                }
            }.sortedByDescending { it.second }
            if (validEntries.size > MAX_TRACK_INDEX_ENTRIES) {
                validEntries.drop(MAX_TRACK_INDEX_ENTRIES).forEach { (k, _) ->
                    json.remove(k)
                }
            }
            prefs.edit().putString(KEY_TRACK_INDEX_JSON, json.toString()).apply()
        }
    }

    fun getCachedVideoCountAndSizeMb(context: Context): Pair<Int, Float> {
        val dirs = listOf(getDurableCacheDir(context), getLegacyCacheDir(context))
        var count = 0
        var totalBytes = 0L
        dirs.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.startsWith("canvas_") && it.name.endsWith(".mp4") }
                ?.forEach {
                    count++
                    totalBytes += it.length()
                }
        }
        return count to (totalBytes / (1024f * 1024f))
    }

    fun invalidateCachedVideoFile(context: Context, videoUrl: String) {
        if (videoUrl.startsWith("file://") || videoUrl.startsWith("content://")) return
        val shaKey = cacheKeyForUrl(videoUrl)
        val legacyKey = legacyCacheKeyForUrl(videoUrl)
        val dirs = listOf(getDurableCacheDir(context), getLegacyCacheDir(context))
        dirs.forEach { dir ->
            listOf(
                File(dir, "canvas_$shaKey.mp4"),
                File(dir, "canvas_$legacyKey.mp4")
            ).forEach { file ->
                if (file.exists()) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    suspend fun prefetchVideoToCache(context: Context, videoUrl: String): File? = withContext(Dispatchers.IO) {
        resolveVideoFile(context, videoUrl)
    }

    suspend fun resolveOrCacheVideoUri(context: Context, videoUrl: String): String? = withContext(Dispatchers.IO) {
        val file = resolveVideoFile(context, videoUrl) ?: return@withContext null
        Uri.fromFile(file).toString()
    }

    private fun cacheKeyForUrl(videoUrl: String): String {
        val normalized = normalizeDownloadUrl(videoUrl)
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            buildString(24) {
                for (i in 0 until 12) {
                    val v = digest[i].toInt() and 0xFF
                    if (v < 16) append('0')
                    append(v.toString(16))
                }
            }
        } catch (_: Exception) {
            legacyCacheKeyForUrl(normalized)
        }
    }

    private fun legacyCacheKeyForUrl(videoUrl: String): String =
        normalizeDownloadUrl(videoUrl).hashCode().toUInt().toString(16)

    private fun normalizeDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            val host = runCatching { URL(trimmed).host.lowercase() }.getOrDefault("")
            if (host.isNotEmpty() && host != "127.0.0.1" && host != "localhost") {
                return "https://" + trimmed.substring(7)
            }
        }
        return trimmed
    }

    private fun isLikelyValidVideoFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 4096L) return false
        return try {
            val header = ByteArray(32)
            val read = RandomAccessFile(file, "r").use { raf ->
                raf.read(header)
            }
            if (read < 12) return false
            // ISOBMFF MP4 / M4V / MOV / 3GP box types at offset 4
            val boxType = String(header, 4, 4, Charsets.US_ASCII)
            if (boxType == "ftyp" || boxType == "moov" || boxType == "mdat" ||
                boxType == "free" || boxType == "skip" || boxType == "wide"
            ) {
                return true
            }
            // EBML (WebM / MKV) header magic 0x1A 0x45 0xDF 0xA3
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareOutputDir(context: Context): File =
        File(context.cacheDir, "cards").apply {
            mkdirs()
            listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(4)
                ?.forEach { it.delete() }
        }

    private fun resolveVideoFile(context: Context, videoUrl: String): File? {
        if (videoUrl.startsWith("file://")) {
            val path = Uri.parse(videoUrl).path ?: return null
            val localFile = File(path)
            return if (localFile.exists() && localFile.length() > 4096L) localFile else null
        }
        if (videoUrl.startsWith("content://")) {
            val cacheDir = File(context.cacheDir, "canvas_video_cache").apply { mkdirs() }
            val dst = File(cacheDir, "canvas_content_${videoUrl.hashCode().toUInt().toString(16)}.mp4")
            return try {
                context.contentResolver.openInputStream(Uri.parse(videoUrl))?.use { input ->
                    FileOutputStream(dst).use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                if (dst.exists() && dst.length() > 4096L) dst else null
            } catch (_: Exception) {
                dst.delete()
                null
            }
        }
        return downloadVideoToCache(context, videoUrl)
    }

    private fun downloadVideoToCache(context: Context, videoUrl: String): File? {
        getCachedVideoFileIfReady(context, videoUrl)?.let { return it }
        val cacheDir = getDurableCacheDir(context)
        val normalizedUrl = normalizeDownloadUrl(videoUrl)
        val cacheKey = cacheKeyForUrl(normalizedUrl)
        val cached = File(cacheDir, "canvas_$cacheKey.mp4")
        val lock = cacheDownloadLocks.computeIfAbsent(cacheKey) { Any() }

        synchronized(lock) {
            if (isLikelyValidVideoFile(cached)) {
                cached.setLastModified(System.currentTimeMillis())
                return cached
            }
            if (cached.exists()) {
                runCatching { cached.delete() }
            }

            val tempFile = File.createTempFile("tmp_${cacheKey}_", ".mp4", cacheDir)
            var currentUrl = normalizedUrl
            var redirects = 0
            try {
                while (redirects < 6) {
                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 9000
                        readTimeout = 20000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", com.example.service.SpotifyAuthManager.DESKTOP_BROWSER_UA)
                        setRequestProperty("Accept", "video/mp4,video/*,*/*;q=0.8")
                        val host = runCatching { URL(currentUrl).host.lowercase() }.getOrDefault("")
                        if (host.contains("apple.com") || host.contains("mzstatic.com")) {
                            setRequestProperty("Referer", "https://music.apple.com/")
                        }
                    }
                    try {
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location") ?: return null
                            currentUrl = normalizeDownloadUrl(URL(URL(currentUrl), loc).toString())
                            redirects++
                            continue
                        }
                        if (code !in 200..299) return null

                        val contentType = conn.contentType?.lowercase().orEmpty()
                        if (contentType.startsWith("text/") ||
                            contentType.contains("json") ||
                            contentType.contains("xml") ||
                            contentType.startsWith("image/")
                        ) {
                            return null
                        }

                        val expectedLength = conn.contentLengthLong
                        var totalWritten = 0L
                        conn.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buf = ByteArray(16_384)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    totalWritten += n
                                }
                                output.flush()
                            }
                        }
                        if (expectedLength > 0L && totalWritten < expectedLength) {
                            tempFile.delete()
                            return null
                        }
                        if (isLikelyValidVideoFile(tempFile)) {
                            if (cached.exists()) cached.delete()
                            val moved = if (tempFile.renameTo(cached)) {
                                true
                            } else {
                                val staging = File.createTempFile("stg_${cacheKey}_", ".mp4", cacheDir)
                                tempFile.copyTo(staging, overwrite = true)
                                tempFile.delete()
                                staging.renameTo(cached) || run {
                                    staging.copyTo(cached, overwrite = true)
                                    staging.delete()
                                    true
                                }
                            }
                            if (moved && isLikelyValidVideoFile(cached)) {
                                cached.setLastModified(System.currentTimeMillis())
                                enforceDiskCacheBounds(context, protectedFile = cached)
                                return cached
                            }
                        }
                        tempFile.delete()
                        return null
                    } finally {
                        conn.disconnect()
                    }
                }
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Failed downloading canvas video: ${e.message}")
                tempFile.delete()
                return null
            } finally {
                if (tempFile.exists()) {
                    runCatching { tempFile.delete() }
                }
            }
        }
    }

    private fun enforceDiskCacheBounds(context: Context, protectedFile: File?) {
        val durableDir = getDurableCacheDir(context)
        val lowSpace = durableDir.usableSpace in 1L..(500L * 1024L * 1024L)
        val maxFiles = if (lowSpace) 18 else 45
        val maxBytes = if (lowSpace) 72L * 1024L * 1024L else 180L * 1024L * 1024L

        val files = durableDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("canvas_") && it.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        var keptCount = 0
        var keptBytes = 0L
        files.forEach { file ->
            if (protectedFile != null && file.absolutePath == protectedFile.absolutePath) {
                keptCount++
                keptBytes += file.length()
            } else if (keptCount < maxFiles && keptBytes + file.length() <= maxBytes) {
                keptCount++
                keptBytes += file.length()
            } else {
                runCatching { file.delete() }
            }
        }
    }

    private fun transcodeWithOverlay(
        sourceFile: File,
        outFile: File,
        overlayBitmap: Bitmap,
        blurIntensity: Float,
        waveformSpec: WaveformExportSpec?,
        onProgress: ((Int) -> Unit)? = null
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
        var renderer: StoryVideoGlRenderer? = null

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

            renderer = StoryVideoGlRenderer(
                videoWidth = max(1, effectiveW),
                videoHeight = max(1, effectiveH),
                outWidth = OUT_WIDTH,
                outHeight = OUT_HEIGHT,
                overlayBitmap = overlayBitmap,
                blurIntensity = blurIntensity,
                staticBackgroundBitmap = null,
                waveformSpec = waveformSpec
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

            val minDurUs = if (waveformSpec != null) WAVEFORM_LOOP_DURATION_US else MIN_LOOP_DURATION_US
            val shouldLoopShortVideo = sourceDurationUs in 1L until minDurUs
            val targetDurationUs = when {
                waveformSpec != null -> {
                    if (sourceDurationUs > 0L) {
                        max(WAVEFORM_LOOP_DURATION_US, sourceDurationUs.coerceAtMost(MAX_EXPORT_DURATION_US))
                    } else {
                        WAVEFORM_LOOP_DURATION_US
                    }
                }
                shouldLoopShortVideo -> MIN_LOOP_DURATION_US
                else -> MAX_EXPORT_DURATION_US
            }

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

            val transcodeDeadlineMs = System.currentTimeMillis() + 45_000L
            var lastTranscodePct = 15
            while (!outputDone) {
                if (System.currentTimeMillis() > transcodeDeadlineMs) {
                    Log.w(TAG, "Video export timed out after 45s; aborting cleanly")
                    break
                }
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
                                val deadline = System.currentTimeMillis() + 500L
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
                                val safePtsUs = if (candidatePtsUs <= lastEncodedPtsUs) {
                                    lastEncodedPtsUs + 33_333L
                                } else {
                                    candidatePtsUs
                                }
                                lastEncodedPtsUs = safePtsUs
                                renderer.drawOesFrame(stMatrix, timeSec = safePtsUs / 1_000_000f)
                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, safePtsUs * 1000L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                                drainEncoder(endOfStream = false)
                                val pct = (18 + ((safePtsUs * 80L) / targetDurationUs.coerceAtLeast(1L)).toInt()).coerceIn(18, 98)
                                if (pct - lastTranscodePct >= 8) {
                                    lastTranscodePct = pct
                                    onProgress?.invoke(pct)
                                }
                            }
                        }

                        if (overMaxDuration) {
                            drainEncoder(endOfStream = true)
                            outputDone = true
                        } else if (isEos) {
                            if (shouldLoopShortVideo && lastEncodedPtsUs < minDurUs) {
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
                    if (eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        renderer?.release()
                    }
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
        private val blurIntensity: Float,
        staticBackgroundBitmap: Bitmap? = null,
        private val waveformSpec: WaveformExportSpec? = null
    ) {
        val videoTextureId: Int
        private val staticBgTextureId: Int
        private val overlayTextureId: Int
        private val waveformTextureId: Int

        private val oesProgram: Int
        private val oesPosHandle: Int
        private val oesTexHandle: Int
        private val oesStMatrixHandle: Int
        private val oesCropScaleHandle: Int
        private val oesBlurHandle: Int

        private val quadProgram: Int
        private val quadPosHandle: Int
        private val quadTexHandle: Int

        private val videoQuadBuffer: FloatBuffer
        private val overlayQuadBuffer: FloatBuffer
        private val waveformQuadBuffer: FloatBuffer?
        private val waveformBitmap: Bitmap?
        private val waveformCanvas: Canvas?
        private val waveformBarPaint: Paint?
        private val barRect = RectF()
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

            // SurfaceTexture OES bottom-left (0,0) -> top-right (1,1)
            val videoVertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            videoQuadBuffer = allocateFloatBuffer(videoVertices)

            // Bitmap uploaded via GLUtils.texImage2D has Y=0 at top -> flip V (1 -> 0)
            val overlayVertices = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            overlayQuadBuffer = allocateFloatBuffer(overlayVertices)

            val textures = IntArray(4)
            GLES20.glGenTextures(4, textures, 0)
            videoTextureId = textures[0]
            staticBgTextureId = textures[1]
            overlayTextureId = textures[2]
            waveformTextureId = textures[3]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            if (staticBackgroundBitmap != null) {
                configure2dTexture(staticBgTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, staticBackgroundBitmap, 0)
            }

            configure2dTexture(overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)

            if (waveformSpec != null) {
                val r = waveformSpec.rectIn720x1280
                val leftNdc = (r.left / outWidth.toFloat()) * 2f - 1f
                val rightNdc = (r.right / outWidth.toFloat()) * 2f - 1f
                val topNdc = 1f - (r.top / outHeight.toFloat()) * 2f
                val bottomNdc = 1f - (r.bottom / outHeight.toFloat()) * 2f
                val wfVerts = floatArrayOf(
                    leftNdc, bottomNdc, 0f, 1f,
                    rightNdc, bottomNdc, 1f, 1f,
                    leftNdc, topNdc, 0f, 0f,
                    rightNdc, topNdc, 1f, 0f
                )
                waveformQuadBuffer = allocateFloatBuffer(wfVerts)
                val wfW = r.width().roundToInt().coerceIn(48, 256)
                val wfH = r.height().roundToInt().coerceIn(32, 160)
                val bmp = Bitmap.createBitmap(wfW, wfH, Bitmap.Config.ARGB_8888)
                waveformBitmap = bmp
                waveformCanvas = Canvas(bmp)
                waveformBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                }
                configure2dTexture(waveformTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            } else {
                waveformQuadBuffer = null
                waveformBitmap = null
                waveformCanvas = null
                waveformBarPaint = null
            }

            oesProgram = createProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
            oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
            oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
            oesStMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uStMatrix")
            oesCropScaleHandle = GLES20.glGetUniformLocation(oesProgram, "uCropScale")
            oesBlurHandle = GLES20.glGetUniformLocation(oesProgram, "uBlurRadius")

            quadProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
            quadPosHandle = GLES20.glGetAttribLocation(quadProgram, "aPosition")
            quadTexHandle = GLES20.glGetAttribLocation(quadProgram, "aTexCoord")
        }

        fun drawOesFrame(stMatrix: FloatArray, timeSec: Float) {
            GLES20.glViewport(0, 0, OUT_WIDTH, OUT_HEIGHT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(oesProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES20.glUniformMatrix4fv(oesStMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glUniform2f(oesCropScaleHandle, cropScaleX, cropScaleY)
            GLES20.glUniform1f(oesBlurHandle, blurIntensity * 0.036f)
            drawQuad(oesPosHandle, oesTexHandle, videoQuadBuffer)

            drawForegroundAndWaveform(timeSec)
        }

        fun drawStaticFrame(timeSec: Float) {
            GLES20.glViewport(0, 0, OUT_WIDTH, OUT_HEIGHT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(quadProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticBgTextureId)
            drawQuad(quadPosHandle, quadTexHandle, overlayQuadBuffer)

            drawForegroundAndWaveform(timeSec)
        }

        private fun drawForegroundAndWaveform(timeSec: Float) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(quadProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            drawQuad(quadPosHandle, quadTexHandle, overlayQuadBuffer)

            val wfSpec = waveformSpec
            val wfQuad = waveformQuadBuffer
            val wfBmp = waveformBitmap
            val wfCanvas = waveformCanvas
            val wfPaint = waveformBarPaint
            if (wfSpec != null && wfQuad != null && wfBmp != null && wfCanvas != null && wfPaint != null) {
                renderWaveformFrameBitmap(
                    bmp = wfBmp,
                    canvas = wfCanvas,
                    paint = wfPaint,
                    spec = wfSpec,
                    timeSec = timeSec
                )
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waveformTextureId)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, wfBmp)
                drawQuad(quadPosHandle, quadTexHandle, wfQuad)
            }
        }

        private fun renderWaveformFrameBitmap(
            bmp: Bitmap,
            canvas: Canvas,
            paint: Paint,
            spec: WaveformExportSpec,
            timeSec: Float
        ) {
            bmp.eraseColor(AndroidColor.TRANSPARENT)
            val levels = AudioBeatMonitor.computeBarLevels(
                trackTitle = spec.trackTitle,
                trackArtist = spec.trackArtist,
                isPlaying = true,
                timeSec = timeSec,
                speedMultiplier = spec.speedMultiplier,
                beatSyncMode = spec.beatSyncEnabled,
                animateWhenPaused = true,
                forceMultiplier = spec.forceMultiplier
            )
            // Keep waveform bars 100% opaque regardless of frosted glass container opacity
            paint.color = spec.waveformColorArgb
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val padX = w * 0.08f
            val usableW = (w - padX * 2f).coerceAtLeast(8f)
            val gap = usableW * 0.14f
            val barW = ((usableW - gap * 3f) / 4f).coerceAtLeast(2f)
            val corner = barW / 2f
            val maxBarH = h * 0.90f
            val cy = h / 2f

            for (i in 0 until 4) {
                val frac = levels[i].coerceIn(0.18f, 1f)
                val barH = max(barW, maxBarH * frac)
                val left = padX + i * (barW + gap)
                val top = cy - barH / 2f
                barRect.set(left, top, left + barW, top + barH)
                canvas.drawRoundRect(
                    barRect,
                    corner,
                    corner,
                    paint
                )
            }
        }

        private fun drawQuad(posHandle: Int, texHandle: Int, buffer: FloatBuffer) {
            buffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(posHandle)
            buffer.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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

        fun release() {
            try {
                waveformBitmap?.let { if (!it.isRecycled) it.recycle() }
                GLES20.glDeleteTextures(4, intArrayOf(videoTextureId, staticBgTextureId, overlayTextureId, waveformTextureId), 0)
                GLES20.glDeleteProgram(oesProgram)
                GLES20.glDeleteProgram(quadProgram)
            } catch (_: Exception) {
            }
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
                    if (uBlurRadius <= 0.0004) {
                        gl_FragColor = texture2D(sTexture, vTexCoord);
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
