package com.example.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardShadowColorMode
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LiveWallpaperArtworkFillMode
import com.example.model.LiveWallpaperCornerStyle
import com.example.model.LiveWallpaperTransitionStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.repository.MediaNotificationRepository
import com.example.repository.SavedCanvasItem
import com.example.repository.SavedMediaLibrary
import com.example.repository.SavedCoverItem
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Non-sensitive card style settings stored in Echomusic backup manifests (never stores Spotify tokens/cookies).
 */
data class EchoSnapBackupStyle(
    val brandMode: String,
    val logoColorMode: String,
    val gradientStyle: String,
    val cardLayoutType: String,
    val lyricsCardBackgroundMode: String,
    val useBlurredAlbumBackground: Boolean,
    val useTransparentBackground: Boolean,
    val backgroundBlurRadius: Float,
    val cardBlurRadius: Float,
    val cardOpacity: Float,
    val lyricsArtBlurRadius: Float,
    val darkOverlayAlpha: Float,
    val glowIntensity: Float,
    val cornerRadiusDp: Int,
    val cardScale: Float,
    val enableWaveform: Boolean,
    val waveformBeatSync: Boolean,
    val waveformSpeedMultiplier: Float,
    val waveformForce: Float,
    val waveformColorArgb: Int,
    val lyricsWordSelectionMode: Boolean,
    val showLogoWordmark: Boolean,
    val liveWallpaperArtworkFillMode: String = LiveWallpaperArtworkFillMode.FLOATING_SQUARE.name,
    val liveWallpaperCornerStyle: String = LiveWallpaperCornerStyle.CURVED.name,
    val liveWallpaperArtTransition: String = LiveWallpaperTransitionStyle.CROSSFADE.name,
    val liveWallpaperArtTransitionDurationMs: Int = 360,
    val liveWallpaperDimEnabled: Boolean = false,
    val liveWallpaperDimAmount: Float = 0.28f,
    val liveWallpaperPowerSavePauseCanvas: Boolean = true,
    val liveWallpaperSmoothCanvasToCanvas: Boolean = true,
    val ignoredMediaPackages: List<String> = emptyList(),
    val backupAutoSyncEnabled: Boolean = false,
    val cardShadowColorMode: String = CardShadowColorMode.ALBUM_DYNAMIC.name,
    val customShadowColorArgb: Int = 0xFFFF2B44.toInt(),
    val cardShadowInverse: Boolean = false
) {
    fun applyTo(current: CardStyleConfig): CardStyleConfig = current.copy(
        brandMode = enumOrDefault(brandMode, current.brandMode),
        logoColorMode = enumOrDefault(logoColorMode, current.logoColorMode),
        gradientStyle = enumOrDefault(gradientStyle, current.gradientStyle),
        cardLayoutType = enumOrDefault(cardLayoutType, current.cardLayoutType),
        lyricsCardBackgroundMode = enumOrDefault(lyricsCardBackgroundMode, current.lyricsCardBackgroundMode),
        useBlurredAlbumBackground = useBlurredAlbumBackground,
        useTransparentBackground = useTransparentBackground,
        backgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 40f),
        cardBlurRadius = cardBlurRadius.coerceIn(0f, 40f),
        cardOpacity = cardOpacity.coerceIn(0f, 1f),
        lyricsArtBlurRadius = lyricsArtBlurRadius.coerceIn(0f, 40f),
        darkOverlayAlpha = darkOverlayAlpha.coerceIn(0f, 0.7f),
        glowIntensity = glowIntensity.coerceIn(0f, 1f),
        cardShadowColorMode = enumOrDefault(cardShadowColorMode, current.cardShadowColorMode),
        customShadowColor = Color(customShadowColorArgb),
        cardShadowInverse = cardShadowInverse,
        cornerRadiusDp = cornerRadiusDp.coerceIn(0, 32),
        cardScale = cardScale.coerceIn(0.60f, 1.20f),
        enableWaveform = enableWaveform,
        waveformBeatSync = waveformBeatSync,
        waveformSpeedMultiplier = waveformSpeedMultiplier.coerceIn(0.4f, 2.5f),
        waveformForce = waveformForce.coerceIn(0.5f, 2.2f),
        waveformColor = Color(waveformColorArgb),
        lyricsWordSelectionMode = lyricsWordSelectionMode,
        showLogoWordmark = showLogoWordmark,
        liveWallpaperArtworkFillMode = enumOrDefault(liveWallpaperArtworkFillMode, current.liveWallpaperArtworkFillMode),
        liveWallpaperCornerStyle = enumOrDefault(liveWallpaperCornerStyle, current.liveWallpaperCornerStyle),
        liveWallpaperArtTransition = enumOrDefault(liveWallpaperArtTransition, current.liveWallpaperArtTransition),
        liveWallpaperArtTransitionDurationMs = liveWallpaperArtTransitionDurationMs.coerceIn(160, 800),
        liveWallpaperDimEnabled = liveWallpaperDimEnabled,
        liveWallpaperDimAmount = liveWallpaperDimAmount.coerceIn(0f, 0.70f),
        liveWallpaperPowerSavePauseCanvas = liveWallpaperPowerSavePauseCanvas,
        liveWallpaperSmoothCanvasToCanvas = liveWallpaperSmoothCanvasToCanvas,
        ignoredMediaPackages = ignoredMediaPackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        backupAutoSyncEnabled = backupAutoSyncEnabled
    )

    companion object {
        fun fromConfig(cfg: CardStyleConfig): EchoSnapBackupStyle = EchoSnapBackupStyle(
            brandMode = cfg.brandMode.name,
            logoColorMode = cfg.logoColorMode.name,
            gradientStyle = cfg.gradientStyle.name,
            cardLayoutType = cfg.cardLayoutType.name,
            lyricsCardBackgroundMode = cfg.lyricsCardBackgroundMode.name,
            useBlurredAlbumBackground = cfg.useBlurredAlbumBackground,
            useTransparentBackground = cfg.useTransparentBackground,
            backgroundBlurRadius = cfg.backgroundBlurRadius,
            cardBlurRadius = cfg.cardBlurRadius,
            cardOpacity = cfg.cardOpacity,
            lyricsArtBlurRadius = cfg.lyricsArtBlurRadius,
            darkOverlayAlpha = cfg.darkOverlayAlpha,
            glowIntensity = cfg.glowIntensity,
            cornerRadiusDp = cfg.cornerRadiusDp,
            cardScale = cfg.cardScale,
            enableWaveform = cfg.enableWaveform,
            waveformBeatSync = cfg.waveformBeatSync,
            waveformSpeedMultiplier = cfg.waveformSpeedMultiplier,
            waveformForce = cfg.waveformForce,
            waveformColorArgb = cfg.waveformColor.toArgb(),
            lyricsWordSelectionMode = cfg.lyricsWordSelectionMode,
            showLogoWordmark = cfg.showLogoWordmark,
            liveWallpaperArtworkFillMode = cfg.liveWallpaperArtworkFillMode.name,
            liveWallpaperCornerStyle = cfg.liveWallpaperCornerStyle.name,
            liveWallpaperArtTransition = cfg.liveWallpaperArtTransition.name,
            liveWallpaperArtTransitionDurationMs = cfg.liveWallpaperArtTransitionDurationMs,
            liveWallpaperDimEnabled = cfg.liveWallpaperDimEnabled,
            liveWallpaperDimAmount = cfg.liveWallpaperDimAmount,
            liveWallpaperPowerSavePauseCanvas = cfg.liveWallpaperPowerSavePauseCanvas,
            liveWallpaperSmoothCanvasToCanvas = cfg.liveWallpaperSmoothCanvasToCanvas,
            ignoredMediaPackages = cfg.ignoredMediaPackages.sorted(),
            backupAutoSyncEnabled = cfg.backupAutoSyncEnabled,
            cardShadowColorMode = cfg.cardShadowColorMode.name,
            customShadowColorArgb = cfg.customShadowColor.toArgb(),
            cardShadowInverse = cfg.cardShadowInverse
        )

        private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
            try {
                enumValueOf<T>(raw)
            } catch (_: Exception) {
                fallback
            }
    }
}

data class ScannedBackupCover(
    val id: String,
    val name: String,
    val artist: String,
    val resolutionLabel: String,
    val createdAtMs: Long,
    val linkedAlbumName: String,
    val linkedAlbumArtist: String,
    val linkedTrackTitles: List<String>,
    val imageFileName: String,
    val localFile: File? = null,
    val contentUri: Uri? = null,
    val previewBitmap: Bitmap? = null
)

data class ScannedBackupCanvas(
    val id: String,
    val name: String,
    val artist: String,
    val resolutionLabel: String,
    val createdAtMs: Long,
    val linkedAlbumName: String,
    val linkedAlbumArtist: String,
    val linkedTrackTitles: List<String>,
    val videoFileName: String,
    val thumbnailFileName: String?,
    val localVideoFile: File? = null,
    val localThumbFile: File? = null,
    val videoContentUri: Uri? = null,
    val thumbContentUri: Uri? = null,
    val previewBitmap: Bitmap? = null
)

data class ScannedEchoSnapBackup(
    val folderDisplayName: String,
    val covers: List<ScannedBackupCover>,
    val canvases: List<ScannedBackupCanvas>,
    val includedStyle: EchoSnapBackupStyle?,
    val exportedAtMs: Long,
    val hasManifest: Boolean
) {
    val totalItemCount: Int get() = covers.size + canvases.size
    val totalSongsCount: Int
        get() = covers.sumOf { it.linkedTrackTitles.size } + canvases.sumOf { it.linkedTrackTitles.size }
}

data class EchoSnapBackupResult(
    val coversCount: Int,
    val canvasesCount: Int,
    val songsCount: Int,
    val folderName: String,
    val styleApplied: Boolean = false
)

/**
 * Zero-permission Storage Access Framework (SAF) + File backup engine for Echomusic.
 * Exports & imports Saved Album Cover Arts, Saved Animated Canvases, Linked Album Song Lists,
 * and Card Visual Styles inside a structured `Echomusic/` folder (scanning legacy `EchoSnap/` too).
 */
object EchoSnapBackupManager {

    const val BACKUP_FOLDER_NAME = "Echomusic"
    const val LEGACY_BACKUP_FOLDER_NAME = "EchoSnap"
    const val MANIFEST_FILE_NAME = "manifest.json"
    const val COVERS_SUBDIR = "covers"
    const val CANVASES_SUBDIR = "canvases"
    private const val MANIFEST_FORMAT = "echosnap_backup"
    private const val MANIFEST_VERSION = 1

    private const val PREFS_NAME = "echosnap_backup_prefs"
    private const val KEY_PERSISTED_TREE_URI = "persisted_backup_tree_uri"
    private const val KEY_HAS_PROMPTED_FOLDER_ACCESS = "has_prompted_folder_access"
    private const val KEY_LAST_AUTO_IMPORT_FINGERPRINT = "last_auto_import_fingerprint"
    private const val KEY_SAVED_FOLDER_DISPLAY_NAME = "saved_folder_display_name"

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoSyncJob: Job? = null

    fun scheduleAutoBackupIfEnabled(context: Context) {
        val ctx = context.applicationContext
        val cfg = MediaNotificationRepository.styleConfig.value
        if (!cfg.backupAutoSyncEnabled) return
        val persistedUri = getPersistedBackupTreeUri(ctx) ?: return
        autoSyncJob?.cancel()
        autoSyncJob = backupScope.launch {
            delay(1800L)
            val latestCfg = MediaNotificationRepository.styleConfig.value
            if (latestCfg.backupAutoSyncEnabled) {
                exportBackupToTreeUri(ctx, persistedUri, latestCfg)
            }
        }
    }

    fun getPersistedBackupTreeUri(context: Context): Uri? {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PERSISTED_TREE_URI, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (!isRobolectric && parsed.scheme == "content") {
            val hasGrant = ctx.contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == parsed && perm.isReadPermission && perm.isWritePermission
            }
            if (!hasGrant) {
                prefs.edit()
                    .remove(KEY_PERSISTED_TREE_URI)
                    .remove(KEY_SAVED_FOLDER_DISPLAY_NAME)
                    .apply()
                return null
            }
        }
        return parsed
    }

    fun savePersistedBackupTreeUri(context: Context, treeUri: Uri, displayName: String? = null) {
        val ctx = context.applicationContext
        takePersistableTreePermission(ctx, treeUri)
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            queryDocument(ctx.contentResolver, treeUri, rootDocId)?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: BACKUP_FOLDER_NAME
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERSISTED_TREE_URI, treeUri.toString())
            .putString(KEY_SAVED_FOLDER_DISPLAY_NAME, resolvedName)
            .putBoolean(KEY_HAS_PROMPTED_FOLDER_ACCESS, true)
            .apply()
    }

    fun clearPersistedBackupTreeUri(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PERSISTED_TREE_URI)
            .remove(KEY_SAVED_FOLDER_DISPLAY_NAME)
            .remove(KEY_LAST_AUTO_IMPORT_FINGERPRINT)
            .apply()
    }

    fun getSavedFolderDisplayName(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAVED_FOLDER_DISPLAY_NAME, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun hasPromptedInitialFolderAccess(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_PROMPTED_FOLDER_ACCESS, false)
    }

    fun setPromptedInitialFolderAccess(context: Context, prompted: Boolean = true) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_PROMPTED_FOLDER_ACCESS, prompted)
            .apply()
    }

    fun getLastAutoImportedFingerprint(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_AUTO_IMPORT_FINGERPRINT, null)
    }

    fun setLastAutoImportedFingerprint(context: Context, fingerprint: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_AUTO_IMPORT_FINGERPRINT, fingerprint)
            .apply()
    }

    fun suggestedInitialPickerUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download"
            )
        }.getOrNull()
    }

    suspend fun autoSearchAndImportFromTreeUri(
        context: Context,
        treeUri: Uri,
        forceReimport: Boolean = false
    ): Result<EchoSnapBackupResult?> = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        savePersistedBackupTreeUri(ctx, treeUri)
        val scanned = scanBackupFromTreeUri(ctx, treeUri).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        if (scanned.totalItemCount == 0 && scanned.includedStyle == null) {
            return@withContext Result.success(null)
        }
        val fingerprint = "${scanned.folderDisplayName}|${scanned.exportedAtMs}|${scanned.covers.size}|${scanned.canvases.size}|${scanned.totalSongsCount}"
        val hasExistingLibrary = SavedMediaLibrary.savedCovers.value.isNotEmpty() ||
            SavedMediaLibrary.savedCanvases.value.isNotEmpty()
        if (!forceReimport && hasExistingLibrary && fingerprint == getLastAutoImportedFingerprint(ctx)) {
            return@withContext Result.success(null)
        }
        val outcome = importScannedBackup(
            context = ctx,
            scanned = scanned,
            selectedCoverIds = scanned.covers.map { it.id }.toSet(),
            selectedCanvasIds = scanned.canvases.map { it.id }.toSet(),
            importStyle = scanned.includedStyle != null,
            replaceExistingFirst = false
        ).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        setLastAutoImportedFingerprint(ctx, fingerprint)
        Result.success(outcome)
    }

    private data class SafDocEntry(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val uri: Uri
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    // -------------------------------------------------------------------------
    // PUBLIC SAF API (Android UI)
    // -------------------------------------------------------------------------

    suspend fun exportBackupToTreeUri(
        context: Context,
        treeUri: Uri,
        styleConfig: CardStyleConfig
    ): Result<EchoSnapBackupResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = context.applicationContext
            savePersistedBackupTreeUri(ctx, treeUri)
            val resolver = ctx.contentResolver
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDoc = queryDocument(resolver, treeUri, rootDocId)
                ?: SafDocEntry(
                    documentId = rootDocId,
                    displayName = BACKUP_FOLDER_NAME,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                )

            // Export directly into picked folder if already named Echomusic (case-insensitive),
            // otherwise create/reuse an Echomusic subfolder inside it.
            val echoSnapDir = if (rootDoc.displayName.equals(BACKUP_FOLDER_NAME, ignoreCase = true)) {
                rootDoc
            } else {
                findOrCreateChildDir(resolver, treeUri, rootDoc, BACKUP_FOLDER_NAME)
            }

            val coversDir = findOrCreateChildDir(resolver, treeUri, echoSnapDir, COVERS_SUBDIR)
            val canvasesDir = findOrCreateChildDir(resolver, treeUri, echoSnapDir, CANVASES_SUBDIR)

            val savedCovers = SavedMediaLibrary.savedCovers.value.filter { File(it.localImagePath).exists() }
            val savedCanvases = SavedMediaLibrary.savedCanvases.value.filter { File(it.localVideoPath).exists() }

            val coverExportEntries = savedCovers.mapNotNull { item ->
                val srcFile = File(item.localImagePath)
                val ext = srcFile.extension.ifBlank { "png" }
                val outName = "${sanitizeFileStem(item.id)}.$ext"
                val mime = if (ext.equals("jpg", true) || ext.equals("jpeg", true)) "image/jpeg" else "image/png"
                val copied = writeStreamToSafChild(resolver, treeUri, coversDir, outName, mime) { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out, bufferSize = 16_384) }
                }
                if (copied) item to outName else null
            }

            val canvasExportEntries = savedCanvases.mapNotNull { item ->
                val videoFile = File(item.localVideoPath)
                val videoExt = videoFile.extension.ifBlank { "mp4" }
                val outVideoName = "${sanitizeFileStem(item.id)}.$videoExt"
                val videoCopied = writeStreamToSafChild(resolver, treeUri, canvasesDir, outVideoName, "video/mp4") { out ->
                    videoFile.inputStream().use { input -> input.copyTo(out, bufferSize = 16_384) }
                }
                if (!videoCopied) return@mapNotNull null

                var outThumbName: String? = null
                val thumbFile = item.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
                if (thumbFile != null) {
                    val candidateThumbName = "${sanitizeFileStem(item.id)}_thumb.png"
                    val thumbCopied = writeStreamToSafChild(resolver, treeUri, canvasesDir, candidateThumbName, "image/png") { out ->
                        thumbFile.inputStream().use { input -> input.copyTo(out, bufferSize = 16_384) }
                    }
                    if (thumbCopied) {
                        outThumbName = candidateThumbName
                    }
                }
                Triple(item, outVideoName, outThumbName)
            }

            val manifestJson = buildManifestJson(
                covers = coverExportEntries,
                canvases = canvasExportEntries,
                style = EchoSnapBackupStyle.fromConfig(styleConfig),
                exportedAtMs = System.currentTimeMillis()
            ).toString(2)

            val manifestOk = writeStreamToSafChild(
                resolver = resolver,
                treeUri = treeUri,
                parentDir = echoSnapDir,
                fileName = MANIFEST_FILE_NAME,
                mimeType = "application/json"
            ) { out ->
                out.write(manifestJson.toByteArray(Charsets.UTF_8))
            }
            if (!manifestOk) {
                error("Could not write $MANIFEST_FILE_NAME to $BACKUP_FOLDER_NAME folder")
            }

            val totalSongs = coverExportEntries.sumOf { it.first.linkedTrackTitles.size } +
                canvasExportEntries.sumOf { it.first.linkedTrackTitles.size }
            EchoSnapBackupResult(
                coversCount = coverExportEntries.size,
                canvasesCount = canvasExportEntries.size,
                songsCount = totalSongs,
                folderName = echoSnapDir.displayName
            )
        }
    }

    suspend fun scanBackupFromTreeUri(
        context: Context,
        treeUri: Uri
    ): Result<ScannedEchoSnapBackup> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = context.applicationContext
            takePersistableTreePermission(ctx, treeUri)
            val resolver = ctx.contentResolver
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDoc = queryDocument(resolver, treeUri, rootDocId)
                ?: SafDocEntry(
                    documentId = rootDocId,
                    displayName = BACKUP_FOLDER_NAME,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                )

            val rootChildren = listChildren(resolver, treeUri, rootDoc.documentId)
            val directManifest = rootChildren.firstOrNull { !it.isDirectory && it.displayName.equals(MANIFEST_FILE_NAME, true) }
            val hasDirectSubdirs = rootChildren.any {
                it.isDirectory && (it.displayName.equals(COVERS_SUBDIR, true) || it.displayName.equals(CANVASES_SUBDIR, true))
            }
            val nestedEchomusicDir = rootChildren.firstOrNull { it.isDirectory && it.displayName.equals(BACKUP_FOLDER_NAME, true) }
            val nestedLegacyDir = rootChildren.firstOrNull { it.isDirectory && it.displayName.equals(LEGACY_BACKUP_FOLDER_NAME, true) }
            var nestedTargetDir = nestedEchomusicDir ?: nestedLegacyDir

            if (directManifest == null && nestedTargetDir == null) {
                val priorityOrder = listOf("download", "downloads", "music", "documents", "media")
                val depth1Dirs = rootChildren
                    .filter {
                        it.isDirectory &&
                            !it.displayName.startsWith(".") &&
                            !it.displayName.equals("Android", ignoreCase = true)
                    }
                    .sortedBy { dir ->
                        val idx = priorityOrder.indexOf(dir.displayName.lowercase())
                        if (idx >= 0) idx else 100
                    }
                    .take(24)

                var depth2Legacy: SafDocEntry? = null
                for (sub in depth1Dirs) {
                    val subChildren = listChildren(resolver, treeUri, sub.documentId)
                    val echoHit = subChildren.firstOrNull {
                        it.isDirectory && it.displayName.equals(BACKUP_FOLDER_NAME, ignoreCase = true)
                    }
                    if (echoHit != null) {
                        nestedTargetDir = echoHit
                        break
                    }
                    if (depth2Legacy == null) {
                        depth2Legacy = subChildren.firstOrNull {
                            it.isDirectory && it.displayName.equals(LEGACY_BACKUP_FOLDER_NAME, ignoreCase = true)
                        }
                    }
                }
                if (nestedTargetDir == null) {
                    nestedTargetDir = depth2Legacy
                }
            }

            val targetDir: SafDocEntry
            val targetChildren: List<SafDocEntry>
            val manifestDoc: SafDocEntry?

            if (directManifest != null || (hasDirectSubdirs && nestedTargetDir == null)) {
                targetDir = rootDoc
                targetChildren = rootChildren
                manifestDoc = directManifest
            } else if (nestedTargetDir != null) {
                targetDir = nestedTargetDir
                targetChildren = listChildren(resolver, treeUri, nestedTargetDir.documentId)
                manifestDoc = targetChildren.firstOrNull { !it.isDirectory && it.displayName.equals(MANIFEST_FILE_NAME, true) }
            } else {
                targetDir = rootDoc
                targetChildren = rootChildren
                manifestDoc = null
            }

            val coversDir = targetChildren.firstOrNull { it.isDirectory && it.displayName.equals(COVERS_SUBDIR, true) }
            val canvasesDir = targetChildren.firstOrNull { it.isDirectory && it.displayName.equals(CANVASES_SUBDIR, true) }
            val coverFiles = if (coversDir != null) listChildren(resolver, treeUri, coversDir.documentId).filter { !it.isDirectory } else emptyList()
            val canvasFiles = if (canvasesDir != null) listChildren(resolver, treeUri, canvasesDir.documentId).filter { !it.isDirectory } else emptyList()

            if (manifestDoc != null) {
                val manifestText = resolver.openInputStream(manifestDoc.uri)?.use { it.bufferedReader().readText() }
                    ?: error("Failed to read $MANIFEST_FILE_NAME")
                val parsed = parseManifestJson(manifestText)

                val coverByName = coverFiles.associateBy { it.displayName.lowercase() }
                val canvasByName = canvasFiles.associateBy { it.displayName.lowercase() }

                val scannedCovers = parsed.covers.mapNotNull { entry ->
                    val match = coverByName[entry.fileName.lowercase()] ?: return@mapNotNull null
                    val previewBmp = decodeSampledBitmap(maxDimension = 160) {
                        resolver.openInputStream(match.uri)
                    }
                    ScannedBackupCover(
                        id = entry.id,
                        name = entry.name,
                        artist = entry.artist,
                        resolutionLabel = entry.resolutionLabel,
                        createdAtMs = entry.createdAtMs,
                        linkedAlbumName = entry.linkedAlbumName,
                        linkedAlbumArtist = entry.linkedAlbumArtist,
                        linkedTrackTitles = entry.linkedTrackTitles,
                        imageFileName = match.displayName,
                        contentUri = match.uri,
                        previewBitmap = previewBmp
                    )
                }

                val scannedCanvases = parsed.canvases.mapNotNull { entry ->
                    val videoDoc = canvasByName[entry.videoFileName.lowercase()] ?: return@mapNotNull null
                    val thumbDoc = entry.thumbnailFileName?.let { canvasByName[it.lowercase()] }
                    val previewBmp = thumbDoc?.let { td ->
                        decodeSampledBitmap(maxDimension = 160) {
                            resolver.openInputStream(td.uri)
                        }
                    }
                    ScannedBackupCanvas(
                        id = entry.id,
                        name = entry.name,
                        artist = entry.artist,
                        resolutionLabel = entry.resolutionLabel,
                        createdAtMs = entry.createdAtMs,
                        linkedAlbumName = entry.linkedAlbumName,
                        linkedAlbumArtist = entry.linkedAlbumArtist,
                        linkedTrackTitles = entry.linkedTrackTitles,
                        videoFileName = videoDoc.displayName,
                        thumbnailFileName = thumbDoc?.displayName,
                        videoContentUri = videoDoc.uri,
                        thumbContentUri = thumbDoc?.uri,
                        previewBitmap = previewBmp
                    )
                }

                ScannedEchoSnapBackup(
                    folderDisplayName = targetDir.displayName,
                    covers = scannedCovers,
                    canvases = scannedCanvases,
                    includedStyle = parsed.style,
                    exportedAtMs = parsed.exportedAtMs,
                    hasManifest = true
                )
            } else {
                // Fallback when user points at an EchoSnap folder without manifest.json
                val allImgDocs = (coverFiles + targetChildren.filter { !it.isDirectory }).filter { doc ->
                    val lower = doc.displayName.lowercase()
                    (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) &&
                        !lower.endsWith("_thumb.png")
                }
                val allVidDocs = (canvasFiles + targetChildren.filter { !it.isDirectory }).filter { doc ->
                    val lower = doc.displayName.lowercase()
                    lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                }

                val scannedCovers = allImgDocs.map { doc ->
                    val previewBmp = decodeSampledBitmap(maxDimension = 160) {
                        resolver.openInputStream(doc.uri)
                    }
                    val stem = doc.displayName.substringBeforeLast('.')
                    ScannedBackupCover(
                        id = "imp_cov_${sanitizeFileStem(stem)}",
                        name = stem,
                        artist = "",
                        resolutionLabel = "Backup Image",
                        createdAtMs = System.currentTimeMillis(),
                        linkedAlbumName = "",
                        linkedAlbumArtist = "",
                        linkedTrackTitles = emptyList(),
                        imageFileName = doc.displayName,
                        contentUri = doc.uri,
                        previewBitmap = previewBmp
                    )
                }

                val thumbLookup = canvasFiles.associateBy { it.displayName.lowercase() }
                val scannedCanvases = allVidDocs.map { doc ->
                    val stem = doc.displayName.substringBeforeLast('.')
                    val thumbDoc = thumbLookup["${stem.lowercase()}_thumb.png"]
                    val previewBmp = thumbDoc?.let { td ->
                        decodeSampledBitmap(maxDimension = 160) {
                            resolver.openInputStream(td.uri)
                        }
                    }
                    ScannedBackupCanvas(
                        id = "imp_can_${sanitizeFileStem(stem)}",
                        name = stem,
                        artist = "",
                        resolutionLabel = "Backup MP4",
                        createdAtMs = System.currentTimeMillis(),
                        linkedAlbumName = "",
                        linkedAlbumArtist = "",
                        linkedTrackTitles = emptyList(),
                        videoFileName = doc.displayName,
                        thumbnailFileName = thumbDoc?.displayName,
                        videoContentUri = doc.uri,
                        thumbContentUri = thumbDoc?.uri,
                        previewBitmap = previewBmp
                    )
                }

                ScannedEchoSnapBackup(
                    folderDisplayName = targetDir.displayName,
                    covers = scannedCovers,
                    canvases = scannedCanvases,
                    includedStyle = null,
                    exportedAtMs = 0L,
                    hasManifest = false
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // IMPORT ENGINE (shared for SAF URIs and File directories)
    // -------------------------------------------------------------------------

    suspend fun importScannedBackup(
        context: Context,
        scanned: ScannedEchoSnapBackup,
        selectedCoverIds: Set<String>,
        selectedCanvasIds: Set<String>,
        importStyle: Boolean,
        replaceExistingFirst: Boolean
    ): Result<EchoSnapBackupResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = context.applicationContext
            val resolver = ctx.contentResolver
            val coversDir = SavedMediaLibrary.getCoversDir(ctx)
            val canvasesDir = SavedMediaLibrary.getCanvasesDir(ctx)

            if (replaceExistingFirst) {
                SavedMediaLibrary.resetAllSavedMedia(ctx)
            }

            val importedCovers = mutableListOf<SavedCoverItem>()
            for (c in scanned.covers) {
                if (c.id !in selectedCoverIds) continue
                val ext = c.imageFileName.substringAfterLast('.', "png").ifBlank { "png" }
                val targetFile = File(coversDir, "${sanitizeFileStem(c.id)}.$ext")
                val copied = copyScannedSourceToFile(resolver, c.localFile, c.contentUri, targetFile)
                if (copied && targetFile.exists() && targetFile.length() > 128L) {
                    importedCovers.add(
                        SavedCoverItem(
                            id = c.id,
                            name = c.name,
                            artist = c.artist,
                            localImagePath = targetFile.absolutePath,
                            resolutionLabel = c.resolutionLabel,
                            createdAtMs = c.createdAtMs,
                            linkedAlbumName = c.linkedAlbumName,
                            linkedAlbumArtist = c.linkedAlbumArtist,
                            linkedTrackTitles = c.linkedTrackTitles
                        )
                    )
                } else {
                    targetFile.delete()
                }
            }

            val importedCanvases = mutableListOf<SavedCanvasItem>()
            for (v in scanned.canvases) {
                if (v.id !in selectedCanvasIds) continue
                val vidExt = v.videoFileName.substringAfterLast('.', "mp4").ifBlank { "mp4" }
                val targetVidFile = File(canvasesDir, "${sanitizeFileStem(v.id)}.$vidExt")
                val copiedVid = copyScannedSourceToFile(resolver, v.localVideoFile, v.videoContentUri, targetVidFile)
                if (!copiedVid || !targetVidFile.exists() || targetVidFile.length() < 256L) {
                    targetVidFile.delete()
                    continue
                }

                var savedThumbPath: String? = null
                if (v.localThumbFile != null || v.thumbContentUri != null) {
                    val targetThumbFile = File(canvasesDir, "${sanitizeFileStem(v.id)}_thumb.png")
                    val copiedThumb = copyScannedSourceToFile(resolver, v.localThumbFile, v.thumbContentUri, targetThumbFile)
                    if (copiedThumb && targetThumbFile.exists() && targetThumbFile.length() > 64L) {
                        savedThumbPath = targetThumbFile.absolutePath
                    } else {
                        targetThumbFile.delete()
                    }
                }

                importedCanvases.add(
                    SavedCanvasItem(
                        id = v.id,
                        name = v.name,
                        artist = v.artist,
                        localVideoPath = targetVidFile.absolutePath,
                        thumbnailPath = savedThumbPath,
                        resolutionLabel = v.resolutionLabel,
                        createdAtMs = v.createdAtMs,
                        linkedAlbumName = v.linkedAlbumName,
                        linkedAlbumArtist = v.linkedAlbumArtist,
                        linkedTrackTitles = v.linkedTrackTitles
                    )
                )
            }

            SavedMediaLibrary.upsertImportedMedia(
                context = ctx,
                importedCovers = importedCovers,
                importedCanvases = importedCanvases,
                replaceExisting = replaceExistingFirst
            )

            var styleApplied = false
            if (importStyle && scanned.includedStyle != null) {
                val stylePatch = scanned.includedStyle
                MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
                    stylePatch.applyTo(current)
                }
                styleApplied = true
            }

            MediaNotificationRepository.onSavedMediaLibraryImported()

            val totalSongs = importedCovers.sumOf { it.linkedTrackTitles.size } +
                importedCanvases.sumOf { it.linkedTrackTitles.size }

            EchoSnapBackupResult(
                coversCount = importedCovers.size,
                canvasesCount = importedCanvases.size,
                songsCount = totalSongs,
                folderName = scanned.folderDisplayName,
                styleApplied = styleApplied
            )
        }
    }

    // -------------------------------------------------------------------------
    // PURE FILE DIRECTORY API (for direct directories & Robolectric tests)
    // -------------------------------------------------------------------------

    fun exportBackupToDirectory(
        parentOrEchoSnapDir: File,
        styleConfig: CardStyleConfig
    ): Result<EchoSnapBackupResult> = runCatching {
        val echoSnapDir = if (parentOrEchoSnapDir.name.equals(BACKUP_FOLDER_NAME, ignoreCase = true)) {
            parentOrEchoSnapDir
        } else {
            File(parentOrEchoSnapDir, BACKUP_FOLDER_NAME)
        }.apply { mkdirs() }

        val coversOutDir = File(echoSnapDir, COVERS_SUBDIR).apply { mkdirs() }
        val canvasesOutDir = File(echoSnapDir, CANVASES_SUBDIR).apply { mkdirs() }

        val savedCovers = SavedMediaLibrary.savedCovers.value.filter { File(it.localImagePath).exists() }
        val savedCanvases = SavedMediaLibrary.savedCanvases.value.filter { File(it.localVideoPath).exists() }

        val coverEntries = savedCovers.mapNotNull { item ->
            val src = File(item.localImagePath)
            val ext = src.extension.ifBlank { "png" }
            val outName = "${sanitizeFileStem(item.id)}.$ext"
            val dst = File(coversOutDir, outName)
            src.copyTo(dst, overwrite = true)
            item to outName
        }

        val canvasEntries = savedCanvases.mapNotNull { item ->
            val srcVid = File(item.localVideoPath)
            val vidExt = srcVid.extension.ifBlank { "mp4" }
            val outVidName = "${sanitizeFileStem(item.id)}.$vidExt"
            srcVid.copyTo(File(canvasesOutDir, outVidName), overwrite = true)

            var outThumbName: String? = null
            val srcThumb = item.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
            if (srcThumb != null) {
                val name = "${sanitizeFileStem(item.id)}_thumb.png"
                srcThumb.copyTo(File(canvasesOutDir, name), overwrite = true)
                outThumbName = name
            }
            Triple(item, outVidName, outThumbName)
        }

        val jsonText = buildManifestJson(
            covers = coverEntries,
            canvases = canvasEntries,
            style = EchoSnapBackupStyle.fromConfig(styleConfig),
            exportedAtMs = System.currentTimeMillis()
        ).toString(2)
        File(echoSnapDir, MANIFEST_FILE_NAME).writeText(jsonText, Charsets.UTF_8)

        val totalSongs = coverEntries.sumOf { it.first.linkedTrackTitles.size } +
            canvasEntries.sumOf { it.first.linkedTrackTitles.size }

        EchoSnapBackupResult(
            coversCount = coverEntries.size,
            canvasesCount = canvasEntries.size,
            songsCount = totalSongs,
            folderName = echoSnapDir.name
        )
    }

    fun scanBackupFromDirectory(parentOrEchoSnapDir: File): Result<ScannedEchoSnapBackup> = runCatching {
        val directManifest = File(parentOrEchoSnapDir, MANIFEST_FILE_NAME)
        val hasDirectSubdirs = File(parentOrEchoSnapDir, COVERS_SUBDIR).isDirectory ||
            File(parentOrEchoSnapDir, CANVASES_SUBDIR).isDirectory
        val children = parentOrEchoSnapDir.listFiles().orEmpty()
        var nestedDir = children.firstOrNull {
            it.isDirectory && it.name.equals(BACKUP_FOLDER_NAME, ignoreCase = true)
        } ?: children.firstOrNull {
            it.isDirectory && it.name.equals(LEGACY_BACKUP_FOLDER_NAME, ignoreCase = true)
        }

        if (!directManifest.exists() && nestedDir == null) {
            val depth1Dirs = children.filter {
                it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", ignoreCase = true)
            }.take(24)
            var depth2Legacy: File? = null
            for (sub in depth1Dirs) {
                val subChildren = sub.listFiles().orEmpty()
                val echoHit = subChildren.firstOrNull {
                    it.isDirectory && it.name.equals(BACKUP_FOLDER_NAME, ignoreCase = true)
                }
                if (echoHit != null) {
                    nestedDir = echoHit
                    break
                }
                if (depth2Legacy == null) {
                    depth2Legacy = subChildren.firstOrNull {
                        it.isDirectory && it.name.equals(LEGACY_BACKUP_FOLDER_NAME, ignoreCase = true)
                    }
                }
            }
            if (nestedDir == null) {
                nestedDir = depth2Legacy
            }
        }

        val targetDir = when {
            directManifest.exists() -> parentOrEchoSnapDir
            hasDirectSubdirs && nestedDir == null -> parentOrEchoSnapDir
            nestedDir != null -> nestedDir
            else -> parentOrEchoSnapDir
        }

        val manifestFile = File(targetDir, MANIFEST_FILE_NAME)
        val coversDir = File(targetDir, COVERS_SUBDIR)
        val canvasesDir = File(targetDir, CANVASES_SUBDIR)

        if (manifestFile.exists()) {
            val parsed = parseManifestJson(manifestFile.readText(Charsets.UTF_8))
            val scannedCovers = parsed.covers.mapNotNull { entry ->
                val f = File(coversDir, entry.fileName).takeIf { it.exists() } ?: return@mapNotNull null
                val preview = decodeSampledBitmap(160) { f.inputStream() }
                ScannedBackupCover(
                    id = entry.id,
                    name = entry.name,
                    artist = entry.artist,
                    resolutionLabel = entry.resolutionLabel,
                    createdAtMs = entry.createdAtMs,
                    linkedAlbumName = entry.linkedAlbumName,
                    linkedAlbumArtist = entry.linkedAlbumArtist,
                    linkedTrackTitles = entry.linkedTrackTitles,
                    imageFileName = entry.fileName,
                    localFile = f,
                    previewBitmap = preview
                )
            }
            val scannedCanvases = parsed.canvases.mapNotNull { entry ->
                val vid = File(canvasesDir, entry.videoFileName).takeIf { it.exists() } ?: return@mapNotNull null
                val thumb = entry.thumbnailFileName?.let { File(canvasesDir, it) }?.takeIf { it.exists() }
                val preview = thumb?.let { decodeSampledBitmap(160) { it.inputStream() } }
                ScannedBackupCanvas(
                    id = entry.id,
                    name = entry.name,
                    artist = entry.artist,
                    resolutionLabel = entry.resolutionLabel,
                    createdAtMs = entry.createdAtMs,
                    linkedAlbumName = entry.linkedAlbumName,
                    linkedAlbumArtist = entry.linkedAlbumArtist,
                    linkedTrackTitles = entry.linkedTrackTitles,
                    videoFileName = entry.videoFileName,
                    thumbnailFileName = thumb?.name,
                    localVideoFile = vid,
                    localThumbFile = thumb,
                    previewBitmap = preview
                )
            }
            ScannedEchoSnapBackup(
                folderDisplayName = targetDir.name,
                covers = scannedCovers,
                canvases = scannedCanvases,
                includedStyle = parsed.style,
                exportedAtMs = parsed.exportedAtMs,
                hasManifest = true
            )
        } else {
            val imgFiles = (coversDir.listFiles()?.toList().orEmpty() + targetDir.listFiles()?.toList().orEmpty())
                .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") && !it.name.endsWith("_thumb.png", true) }
            val vidFiles = (canvasesDir.listFiles()?.toList().orEmpty() + targetDir.listFiles()?.toList().orEmpty())
                .filter { it.isFile && it.extension.lowercase() in setOf("mp4", "mkv", "webm") }

            ScannedEchoSnapBackup(
                folderDisplayName = targetDir.name,
                covers = imgFiles.map { f ->
                    ScannedBackupCover(
                        id = "imp_cov_${sanitizeFileStem(f.nameWithoutExtension)}",
                        name = f.nameWithoutExtension,
                        artist = "",
                        resolutionLabel = "Backup Image",
                        createdAtMs = f.lastModified(),
                        linkedAlbumName = "",
                        linkedAlbumArtist = "",
                        linkedTrackTitles = emptyList(),
                        imageFileName = f.name,
                        localFile = f,
                        previewBitmap = decodeSampledBitmap(160) { f.inputStream() }
                    )
                },
                canvases = vidFiles.map { f ->
                    val thumb = File(f.parentFile, "${f.nameWithoutExtension}_thumb.png").takeIf { it.exists() }
                    ScannedBackupCanvas(
                        id = "imp_can_${sanitizeFileStem(f.nameWithoutExtension)}",
                        name = f.nameWithoutExtension,
                        artist = "",
                        resolutionLabel = "Backup MP4",
                        createdAtMs = f.lastModified(),
                        linkedAlbumName = "",
                        linkedAlbumArtist = "",
                        linkedTrackTitles = emptyList(),
                        videoFileName = f.name,
                        thumbnailFileName = thumb?.name,
                        localVideoFile = f,
                        localThumbFile = thumb,
                        previewBitmap = thumb?.let { decodeSampledBitmap(160) { it.inputStream() } }
                    )
                },
                includedStyle = null,
                exportedAtMs = 0L,
                hasManifest = false
            )
        }
    }

    // -------------------------------------------------------------------------
    // JSON MANIFEST SERIALIZATION
    // -------------------------------------------------------------------------

    private data class ParsedCoverEntry(
        val id: String,
        val name: String,
        val artist: String,
        val resolutionLabel: String,
        val createdAtMs: Long,
        val linkedAlbumName: String,
        val linkedAlbumArtist: String,
        val linkedTrackTitles: List<String>,
        val fileName: String
    )

    private data class ParsedCanvasEntry(
        val id: String,
        val name: String,
        val artist: String,
        val resolutionLabel: String,
        val createdAtMs: Long,
        val linkedAlbumName: String,
        val linkedAlbumArtist: String,
        val linkedTrackTitles: List<String>,
        val videoFileName: String,
        val thumbnailFileName: String?
    )

    private data class ParsedManifest(
        val exportedAtMs: Long,
        val covers: List<ParsedCoverEntry>,
        val canvases: List<ParsedCanvasEntry>,
        val style: EchoSnapBackupStyle?
    )

    private fun buildManifestJson(
        covers: List<Pair<SavedCoverItem, String>>,
        canvases: List<Triple<SavedCanvasItem, String, String?>>,
        style: EchoSnapBackupStyle?,
        exportedAtMs: Long
    ): JSONObject {
        val coversJson = JSONArray()
        covers.forEach { (item, fileName) ->
            coversJson.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("artist", item.artist)
                    put("resolutionLabel", item.resolutionLabel)
                    put("createdAtMs", item.createdAtMs)
                    put("linkedAlbumName", item.linkedAlbumName)
                    put("linkedAlbumArtist", item.linkedAlbumArtist)
                    put("linkedTrackTitles", JSONArray(item.linkedTrackTitles))
                    put("imageFileName", fileName)
                }
            )
        }

        val canvasesJson = JSONArray()
        canvases.forEach { (item, vidName, thumbName) ->
            canvasesJson.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("artist", item.artist)
                    put("resolutionLabel", item.resolutionLabel)
                    put("createdAtMs", item.createdAtMs)
                    put("linkedAlbumName", item.linkedAlbumName)
                    put("linkedAlbumArtist", item.linkedAlbumArtist)
                    put("linkedTrackTitles", JSONArray(item.linkedTrackTitles))
                    put("videoFileName", vidName)
                    put("thumbnailFileName", thumbName ?: "")
                }
            )
        }

        return JSONObject().apply {
            put("format", MANIFEST_FORMAT)
            put("version", MANIFEST_VERSION)
            put("exportedAtMs", exportedAtMs)
            put("covers", coversJson)
            put("canvases", canvasesJson)
            if (style != null) {
                put("style", styleToJson(style))
            }
        }
    }

    private fun parseManifestJson(rawJson: String): ParsedManifest {
        val root = JSONObject(rawJson)
        val exportedAtMs = root.optLong("exportedAtMs", 0L)

        val coversArr = root.optJSONArray("covers") ?: JSONArray()
        val covers = buildList {
            for (i in 0 until coversArr.length()) {
                val o = coversArr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val fileName = o.optString("imageFileName").trim()
                if (id.isEmpty() || fileName.isEmpty()) continue
                add(
                    ParsedCoverEntry(
                        id = id,
                        name = o.optString("name", "Saved Cover"),
                        artist = o.optString("artist", ""),
                        resolutionLabel = o.optString("resolutionLabel", "HD"),
                        createdAtMs = o.optLong("createdAtMs", 0L),
                        linkedAlbumName = o.optString("linkedAlbumName", ""),
                        linkedAlbumArtist = o.optString("linkedAlbumArtist", ""),
                        linkedTrackTitles = parseSongsArray(o.optJSONArray("linkedTrackTitles")),
                        fileName = fileName
                    )
                )
            }
        }

        val canvasesArr = root.optJSONArray("canvases") ?: JSONArray()
        val canvases = buildList {
            for (i in 0 until canvasesArr.length()) {
                val o = canvasesArr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val videoFileName = o.optString("videoFileName").trim()
                if (id.isEmpty() || videoFileName.isEmpty()) continue
                add(
                    ParsedCanvasEntry(
                        id = id,
                        name = o.optString("name", "Saved Canvas"),
                        artist = o.optString("artist", ""),
                        resolutionLabel = o.optString("resolutionLabel", "MP4"),
                        createdAtMs = o.optLong("createdAtMs", 0L),
                        linkedAlbumName = o.optString("linkedAlbumName", ""),
                        linkedAlbumArtist = o.optString("linkedAlbumArtist", ""),
                        linkedTrackTitles = parseSongsArray(o.optJSONArray("linkedTrackTitles")),
                        videoFileName = videoFileName,
                        thumbnailFileName = o.optString("thumbnailFileName", "").ifBlank { null }
                    )
                )
            }
        }

        val styleObj = root.optJSONObject("style")
        val style = styleObj?.let { jsonToStyle(it) }

        return ParsedManifest(
            exportedAtMs = exportedAtMs,
            covers = covers,
            canvases = canvases,
            style = style
        )
    }

    private fun parseSongsArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val title = arr.optString(i).trim()
                if (title.isNotEmpty()) add(title)
            }
        }
    }

    private fun styleToJson(s: EchoSnapBackupStyle): JSONObject = JSONObject().apply {
        put("brandMode", s.brandMode)
        put("logoColorMode", s.logoColorMode)
        put("gradientStyle", s.gradientStyle)
        put("cardLayoutType", s.cardLayoutType)
        put("lyricsCardBackgroundMode", s.lyricsCardBackgroundMode)
        put("useBlurredAlbumBackground", s.useBlurredAlbumBackground)
        put("useTransparentBackground", s.useTransparentBackground)
        put("backgroundBlurRadius", s.backgroundBlurRadius.toDouble())
        put("cardBlurRadius", s.cardBlurRadius.toDouble())
        put("cardOpacity", s.cardOpacity.toDouble())
        put("lyricsArtBlurRadius", s.lyricsArtBlurRadius.toDouble())
        put("darkOverlayAlpha", s.darkOverlayAlpha.toDouble())
        put("glowIntensity", s.glowIntensity.toDouble())
        put("cornerRadiusDp", s.cornerRadiusDp)
        put("cardScale", s.cardScale.toDouble())
        put("enableWaveform", s.enableWaveform)
        put("waveformBeatSync", s.waveformBeatSync)
        put("waveformSpeedMultiplier", s.waveformSpeedMultiplier.toDouble())
        put("waveformForce", s.waveformForce.toDouble())
        put("waveformColorArgb", s.waveformColorArgb)
        put("lyricsWordSelectionMode", s.lyricsWordSelectionMode)
        put("showLogoWordmark", s.showLogoWordmark)
        put("liveWallpaperArtworkFillMode", s.liveWallpaperArtworkFillMode)
        put("liveWallpaperCornerStyle", s.liveWallpaperCornerStyle)
        put("liveWallpaperArtTransition", s.liveWallpaperArtTransition)
        put("liveWallpaperArtTransitionDurationMs", s.liveWallpaperArtTransitionDurationMs)
        put("liveWallpaperDimEnabled", s.liveWallpaperDimEnabled)
        put("liveWallpaperDimAmount", s.liveWallpaperDimAmount.toDouble())
        put("liveWallpaperPowerSavePauseCanvas", s.liveWallpaperPowerSavePauseCanvas)
        put("liveWallpaperSmoothCanvasToCanvas", s.liveWallpaperSmoothCanvasToCanvas)
        put("ignoredMediaPackages", JSONArray(s.ignoredMediaPackages))
        put("backupAutoSyncEnabled", s.backupAutoSyncEnabled)
        put("cardShadowColorMode", s.cardShadowColorMode)
        put("customShadowColorArgb", s.customShadowColorArgb)
        put("cardShadowInverse", s.cardShadowInverse)
    }

    private fun jsonToStyle(o: JSONObject): EchoSnapBackupStyle {
        val d = CardStyleConfig()
        return EchoSnapBackupStyle(
            brandMode = o.optString("brandMode", d.brandMode.name),
            logoColorMode = o.optString("logoColorMode", d.logoColorMode.name),
            gradientStyle = o.optString("gradientStyle", d.gradientStyle.name),
            cardLayoutType = o.optString("cardLayoutType", d.cardLayoutType.name),
            lyricsCardBackgroundMode = o.optString("lyricsCardBackgroundMode", d.lyricsCardBackgroundMode.name),
            useBlurredAlbumBackground = o.optBoolean("useBlurredAlbumBackground", d.useBlurredAlbumBackground),
            useTransparentBackground = o.optBoolean("useTransparentBackground", d.useTransparentBackground),
            backgroundBlurRadius = o.optDouble("backgroundBlurRadius", d.backgroundBlurRadius.toDouble()).toFloat(),
            cardBlurRadius = o.optDouble("cardBlurRadius", d.cardBlurRadius.toDouble()).toFloat(),
            cardOpacity = o.optDouble("cardOpacity", d.cardOpacity.toDouble()).toFloat(),
            lyricsArtBlurRadius = o.optDouble("lyricsArtBlurRadius", d.lyricsArtBlurRadius.toDouble()).toFloat(),
            darkOverlayAlpha = o.optDouble("darkOverlayAlpha", d.darkOverlayAlpha.toDouble()).toFloat(),
            glowIntensity = o.optDouble("glowIntensity", d.glowIntensity.toDouble()).toFloat(),
            cornerRadiusDp = o.optInt("cornerRadiusDp", d.cornerRadiusDp),
            cardScale = o.optDouble("cardScale", d.cardScale.toDouble()).toFloat(),
            enableWaveform = o.optBoolean("enableWaveform", d.enableWaveform),
            waveformBeatSync = o.optBoolean("waveformBeatSync", d.waveformBeatSync),
            waveformSpeedMultiplier = o.optDouble("waveformSpeedMultiplier", d.waveformSpeedMultiplier.toDouble()).toFloat(),
            waveformForce = o.optDouble("waveformForce", d.waveformForce.toDouble()).toFloat(),
            waveformColorArgb = o.optInt("waveformColorArgb", d.waveformColor.toArgb()),
            lyricsWordSelectionMode = o.optBoolean("lyricsWordSelectionMode", d.lyricsWordSelectionMode),
            showLogoWordmark = o.optBoolean("showLogoWordmark", d.showLogoWordmark),
            liveWallpaperArtworkFillMode = o.optString("liveWallpaperArtworkFillMode", d.liveWallpaperArtworkFillMode.name),
            liveWallpaperCornerStyle = o.optString("liveWallpaperCornerStyle", d.liveWallpaperCornerStyle.name),
            liveWallpaperArtTransition = o.optString("liveWallpaperArtTransition", d.liveWallpaperArtTransition.name),
            liveWallpaperArtTransitionDurationMs = o.optInt("liveWallpaperArtTransitionDurationMs", d.liveWallpaperArtTransitionDurationMs),
            liveWallpaperDimEnabled = o.optBoolean("liveWallpaperDimEnabled", d.liveWallpaperDimEnabled),
            liveWallpaperDimAmount = o.optDouble("liveWallpaperDimAmount", d.liveWallpaperDimAmount.toDouble()).toFloat(),
            liveWallpaperPowerSavePauseCanvas = o.optBoolean(
                "liveWallpaperPowerSavePauseCanvas",
                d.liveWallpaperPowerSavePauseCanvas
            ),
            liveWallpaperSmoothCanvasToCanvas = o.optBoolean(
                "liveWallpaperSmoothCanvasToCanvas",
                d.liveWallpaperSmoothCanvasToCanvas
            ),
            ignoredMediaPackages = parseSongsArray(o.optJSONArray("ignoredMediaPackages")),
            backupAutoSyncEnabled = o.optBoolean("backupAutoSyncEnabled", d.backupAutoSyncEnabled),
            cardShadowColorMode = o.optString("cardShadowColorMode", d.cardShadowColorMode.name),
            customShadowColorArgb = o.optInt("customShadowColorArgb", d.customShadowColor.toArgb()),
            cardShadowInverse = o.optBoolean("cardShadowInverse", d.cardShadowInverse)
        )
    }

    // -------------------------------------------------------------------------
    // INTERNAL SAF & BITMAP HELPERS
    // -------------------------------------------------------------------------

    private fun takePersistableTreePermission(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    private fun queryDocument(resolver: ContentResolver, treeUri: Uri, documentId: String): SafDocEntry? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return runCatching {
            resolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getString(0) ?: return@use null
                val name = cursor.getString(1) ?: ""
                val mime = cursor.getString(2) ?: ""
                SafDocEntry(
                    documentId = id,
                    displayName = name,
                    mimeType = mime,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                )
            }
        }.getOrNull()
    }

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<SafDocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = mutableListOf<SafDocEntry>()
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    out.add(
                        SafDocEntry(
                            documentId = id,
                            displayName = name,
                            mimeType = mime,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        )
                    )
                }
            }
        }
        return out
    }

    private fun findOrCreateChildDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parent: SafDocEntry,
        dirName: String
    ): SafDocEntry {
        val existing = listChildren(resolver, treeUri, parent.documentId)
            .firstOrNull { it.isDirectory && it.displayName.equals(dirName, ignoreCase = true) }
        if (existing != null) return existing

        val createdUri = DocumentsContract.createDocument(
            resolver,
            parent.uri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        ) ?: error("Unable to create folder $dirName")
        val createdId = DocumentsContract.getDocumentId(createdUri)
        return SafDocEntry(
            documentId = createdId,
            displayName = dirName,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            uri = createdUri
        )
    }

    private fun writeStreamToSafChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDir: SafDocEntry,
        fileName: String,
        mimeType: String,
        writer: (java.io.OutputStream) -> Unit
    ): Boolean {
        return try {
            val existing = listChildren(resolver, treeUri, parentDir.documentId)
                .firstOrNull { !it.isDirectory && it.displayName.equals(fileName, ignoreCase = true) }
            if (existing != null) {
                runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }
            }
            val createdUri = DocumentsContract.createDocument(
                resolver,
                parentDir.uri,
                mimeType,
                fileName
            ) ?: return false

            resolver.openOutputStream(createdUri, "w")?.use { out ->
                writer(out)
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyScannedSourceToFile(
        resolver: ContentResolver,
        localFile: File?,
        contentUri: Uri?,
        destFile: File
    ): Boolean {
        return try {
            when {
                localFile != null && localFile.exists() -> {
                    localFile.copyTo(destFile, overwrite = true)
                    true
                }
                contentUri != null -> {
                    resolver.openInputStream(contentUri)?.use { input ->
                        FileOutputStream(destFile).use { out ->
                            input.copyTo(out, bufferSize = 16_384)
                            out.flush()
                        }
                    } ?: return false
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeSampledBitmap(
        maxDimension: Int,
        streamProvider: () -> InputStream?
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            streamProvider()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSample = 1
            while (bounds.outWidth / (inSample * 2) >= maxDimension &&
                bounds.outHeight / (inSample * 2) >= maxDimension
            ) {
                inSample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = streamProvider()?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            BitmapBlurUtils.toSafeSoftwareBitmap(raw, maxDimension = maxDimension)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileStem(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "item_${System.currentTimeMillis()}" }
}
