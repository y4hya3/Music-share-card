package com.example.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.service.AnimatedCanvasCandidate
import com.example.service.CoverArtSearchService
import com.example.service.OnlineCoverCandidate
import com.example.service.SpotifyAuthManager
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class SavedCanvasItem(
    val id: String,
    val name: String,
    val artist: String,
    val localVideoPath: String,
    val thumbnailPath: String?,
    val resolutionLabel: String,
    val createdAtMs: Long
) {
    val fileUri: String
        get() = Uri.fromFile(File(localVideoPath)).toString()
}

data class SavedCoverItem(
    val id: String,
    val name: String,
    val artist: String,
    val localImagePath: String,
    val resolutionLabel: String,
    val createdAtMs: Long
)

/**
 * Local storage manager for user-saved Animated Canvases and High-Res Cover Arts inside
 * internal app files (`filesDir/saved_media/`), requiring zero broad storage permissions.
 */
object SavedMediaLibrary {

    private const val ROOT_DIR = "saved_media"
    private const val CANVASES_DIR = "canvases"
    private const val COVERS_DIR = "covers"
    private const val CANVAS_INDEX_FILE = "canvases_index.json"
    private const val COVER_INDEX_FILE = "covers_index.json"

    private val _savedCanvases = MutableStateFlow<List<SavedCanvasItem>>(emptyList())
    val savedCanvases: StateFlow<List<SavedCanvasItem>> = _savedCanvases.asStateFlow()

    private val _savedCovers = MutableStateFlow<List<SavedCoverItem>>(emptyList())
    val savedCovers: StateFlow<List<SavedCoverItem>> = _savedCovers.asStateFlow()

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        _savedCanvases.value = readCanvasIndex(ctx).filter { File(it.localVideoPath).exists() }
        _savedCovers.value = readCoverIndex(ctx).filter { File(it.localImagePath).exists() }
    }

    suspend fun saveCanvasFromCandidate(
        context: Context,
        candidate: AnimatedCanvasCandidate,
        customName: String
    ): SavedCanvasItem? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val videoUrl = candidate.videoUrl?.trim().orEmpty()
        if (videoUrl.isEmpty()) return@withContext null

        val id = "sc_${System.currentTimeMillis()}_${(videoUrl.hashCode() and 0xFFFF)}"
        val dir = File(File(ctx.filesDir, ROOT_DIR), CANVASES_DIR).apply { mkdirs() }
        val videoFile = File(dir, "$id.mp4")

        val downloaded = downloadFileFromUrlOrUri(ctx, videoUrl, videoFile)
        if (!downloaded || !videoFile.exists() || videoFile.length() < 4096L) {
            videoFile.delete()
            return@withContext null
        }

        val thumbBitmap = extractVideoFrameFromFile(videoFile) ?: candidate.thumbnailBitmap
        val thumbFile = File(dir, "${id}_thumb.png")
        val savedThumbPath = if (thumbBitmap != null && !thumbBitmap.isRecycled) {
            try {
                FileOutputStream(thumbFile).use { out ->
                    thumbBitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                }
                thumbFile.absolutePath
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val resLabel = readVideoDimensionsLabel(videoFile).ifBlank { candidate.resolutionLabel }
        val item = SavedCanvasItem(
            id = id,
            name = customName.trim().ifBlank { candidate.title.ifBlank { "Saved Canvas" } },
            artist = candidate.artist.trim(),
            localVideoPath = videoFile.absolutePath,
            thumbnailPath = savedThumbPath,
            resolutionLabel = resLabel,
            createdAtMs = System.currentTimeMillis()
        )
        val next = listOf(item) + readCanvasIndex(ctx).filter { File(it.localVideoPath).exists() }
        writeCanvasIndex(ctx, next)
        _savedCanvases.value = next
        item
    }

    suspend fun importCanvasFromUri(
        context: Context,
        uri: Uri,
        customName: String
    ): SavedCanvasItem? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val id = "sc_${System.currentTimeMillis()}"
        val dir = File(File(ctx.filesDir, ROOT_DIR), CANVASES_DIR).apply { mkdirs() }
        val videoFile = File(dir, "$id.mp4")

        val copied = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(videoFile).use { out ->
                    input.copyTo(out, bufferSize = 16_384)
                    out.flush()
                }
            }
            videoFile.exists() && videoFile.length() > 4096L
        } catch (_: Exception) {
            false
        }

        if (!copied) {
            videoFile.delete()
            return@withContext null
        }

        val thumbBitmap = extractVideoFrameFromFile(videoFile)
        val thumbFile = File(dir, "${id}_thumb.png")
        val savedThumbPath = if (thumbBitmap != null && !thumbBitmap.isRecycled) {
            try {
                FileOutputStream(thumbFile).use { out ->
                    thumbBitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                }
                thumbFile.absolutePath
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val resLabel = readVideoDimensionsLabel(videoFile).ifBlank { "Imported MP4" }
        val item = SavedCanvasItem(
            id = id,
            name = customName.trim().ifBlank { "Imported Canvas" },
            artist = "Device Media",
            localVideoPath = videoFile.absolutePath,
            thumbnailPath = savedThumbPath,
            resolutionLabel = resLabel,
            createdAtMs = System.currentTimeMillis()
        )
        val next = listOf(item) + readCanvasIndex(ctx).filter { File(it.localVideoPath).exists() }
        writeCanvasIndex(ctx, next)
        _savedCanvases.value = next
        item
    }

    fun renameCanvas(context: Context, id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        val ctx = context.applicationContext
        val next = readCanvasIndex(ctx).map { if (it.id == id) it.copy(name = clean) else it }
        writeCanvasIndex(ctx, next)
        _savedCanvases.value = next
    }

    fun deleteCanvas(context: Context, id: String) {
        val ctx = context.applicationContext
        val current = readCanvasIndex(ctx)
        current.firstOrNull { it.id == id }?.let { victim ->
            runCatching { File(victim.localVideoPath).delete() }
            victim.thumbnailPath?.let { p -> runCatching { File(p).delete() } }
        }
        val next = current.filterNot { it.id == id }
        writeCanvasIndex(ctx, next)
        _savedCanvases.value = next
    }

    suspend fun saveCoverFromCandidate(
        context: Context,
        candidate: OnlineCoverCandidate,
        customName: String
    ): SavedCoverItem? = withContext(Dispatchers.IO) {
        val maxCandidate = candidate.copy(
            fullResUrl = upgradeCoverUrlToMaxResolution(candidate.fullResUrl)
        )
        val bmp = CoverArtSearchService.fetchFullResolutionArt(maxCandidate, maxDimension = 2048)
            ?: CoverArtSearchService.fetchFullResolutionArt(candidate, maxDimension = 2048)
            ?: return@withContext null
        saveCoverFromBitmap(
            context = context,
            bitmap = bmp,
            customName = customName.trim().ifBlank { candidate.title.ifBlank { "Saved Cover" } },
            artist = candidate.artist
        )
    }

    suspend fun saveCoverFromBitmap(
        context: Context,
        bitmap: Bitmap,
        customName: String,
        artist: String = ""
    ): SavedCoverItem? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val safeBmp = BitmapBlurUtils.toSafeSoftwareBitmap(bitmap, maxDimension = 2048)
            ?: return@withContext null
        val id = "art_${System.currentTimeMillis()}_${(customName.hashCode() and 0xFFFF)}"
        val dir = File(File(ctx.filesDir, ROOT_DIR), COVERS_DIR).apply { mkdirs() }
        val file = File(dir, "$id.png")
        val ok = try {
            FileOutputStream(file).use { out ->
                safeBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            file.exists() && file.length() > 512L
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            file.delete()
            return@withContext null
        }

        val item = SavedCoverItem(
            id = id,
            name = customName.trim().ifBlank { "Saved Cover" },
            artist = artist.trim(),
            localImagePath = file.absolutePath,
            resolutionLabel = "${safeBmp.width}×${safeBmp.height}",
            createdAtMs = System.currentTimeMillis()
        )
        val next = listOf(item) + readCoverIndex(ctx).filter { File(it.localImagePath).exists() }
        writeCoverIndex(ctx, next)
        _savedCovers.value = next
        item
    }

    suspend fun importCoverFromUri(
        context: Context,
        uri: Uri,
        customName: String
    ): SavedCoverItem? = withContext(Dispatchers.IO) {
        val decoded = BitmapBlurUtils.decodeHighResFromUri(context.applicationContext, uri, maxDimension = 2048)
            ?: return@withContext null
        saveCoverFromBitmap(
            context = context,
            bitmap = decoded,
            customName = customName.trim().ifBlank { "Imported Cover" },
            artist = "Device Photo"
        )
    }

    fun renameCover(context: Context, id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        val ctx = context.applicationContext
        val next = readCoverIndex(ctx).map { if (it.id == id) it.copy(name = clean) else it }
        writeCoverIndex(ctx, next)
        _savedCovers.value = next
    }

    fun deleteCover(context: Context, id: String) {
        val ctx = context.applicationContext
        val current = readCoverIndex(ctx)
        current.firstOrNull { it.id == id }?.let { victim ->
            runCatching { File(victim.localImagePath).delete() }
        }
        val next = current.filterNot { it.id == id }
        writeCoverIndex(ctx, next)
        _savedCovers.value = next
    }

    fun loadBitmap(path: String?, maxDimension: Int = 1200): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var inSample = 1
            while (bounds.outWidth / (inSample * 2) >= maxDimension && bounds.outHeight / (inSample * 2) >= maxDimension) {
                inSample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            BitmapBlurUtils.toSafeSoftwareBitmap(raw, maxDimension = maxDimension)
        } catch (_: Exception) {
            null
        }
    }

    private fun upgradeCoverUrlToMaxResolution(url: String): String {
        return when {
            url.contains("1000x1000bb") -> url.replace("1000x1000bb", "3000x3000bb")
            url.contains("600x600bb") -> url.replace("600x600bb", "3000x3000bb")
            url.contains("100x100bb") -> url.replace("100x100bb", "3000x3000bb")
            else -> url
        }
    }

    private fun downloadFileFromUrlOrUri(context: Context, urlOrUri: String, dest: File): Boolean {
        if (urlOrUri.startsWith("file://")) {
            val src = Uri.parse(urlOrUri).path?.let { File(it) } ?: return false
            if (!src.exists()) return false
            src.copyTo(dest, overwrite = true)
            return true
        }
        if (urlOrUri.startsWith("content://")) {
            return try {
                context.contentResolver.openInputStream(Uri.parse(urlOrUri))?.use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out, bufferSize = 16_384)
                        out.flush()
                    }
                }
                dest.exists() && dest.length() > 4096L
            } catch (_: Exception) {
                false
            }
        }
        var currentUrl = urlOrUri
        var redirects = 0
        while (redirects < 4) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", SpotifyAuthManager.DESKTOP_BROWSER_UA)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) return false
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out, bufferSize = 16_384)
                        out.flush()
                    }
                }
                return dest.exists() && dest.length() > 4096L
            } catch (_: Exception) {
                return false
            } finally {
                conn.disconnect()
            }
        }
        return false
    }

    private fun extractVideoFrameFromFile(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            BitmapBlurUtils.toSafeSoftwareBitmap(frame, maxDimension = 360)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readVideoDimensionsLabel(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) "${w}×${h} MP4" else ""
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readCanvasIndex(context: Context): List<SavedCanvasItem> {
        val file = File(File(context.filesDir, ROOT_DIR), CANVAS_INDEX_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val path = o.optString("localVideoPath")
                    if (id.isNotBlank() && path.isNotBlank()) {
                        add(
                            SavedCanvasItem(
                                id = id,
                                name = o.optString("name", "Saved Canvas"),
                                artist = o.optString("artist", ""),
                                localVideoPath = path,
                                thumbnailPath = o.optString("thumbnailPath", "").ifBlank { null },
                                resolutionLabel = o.optString("resolutionLabel", "MP4"),
                                createdAtMs = o.optLong("createdAtMs", 0L)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCanvasIndex(context: Context, items: List<SavedCanvasItem>) {
        val dir = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
        val file = File(dir, CANVAS_INDEX_FILE)
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("artist", item.artist)
                    put("localVideoPath", item.localVideoPath)
                    put("thumbnailPath", item.thumbnailPath ?: "")
                    put("resolutionLabel", item.resolutionLabel)
                    put("createdAtMs", item.createdAtMs)
                }
            )
        }
        file.writeText(arr.toString())
    }

    private fun readCoverIndex(context: Context): List<SavedCoverItem> {
        val file = File(File(context.filesDir, ROOT_DIR), COVER_INDEX_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val path = o.optString("localImagePath")
                    if (id.isNotBlank() && path.isNotBlank()) {
                        add(
                            SavedCoverItem(
                                id = id,
                                name = o.optString("name", "Saved Cover"),
                                artist = o.optString("artist", ""),
                                localImagePath = path,
                                resolutionLabel = o.optString("resolutionLabel", "HD"),
                                createdAtMs = o.optLong("createdAtMs", 0L)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCoverIndex(context: Context, items: List<SavedCoverItem>) {
        val dir = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
        val file = File(dir, COVER_INDEX_FILE)
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("artist", item.artist)
                    put("localImagePath", item.localImagePath)
                    put("resolutionLabel", item.resolutionLabel)
                    put("createdAtMs", item.createdAtMs)
                }
            )
        }
        file.writeText(arr.toString())
    }
}
