package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.CardStyleConfig
import com.example.repository.MediaNotificationRepository
import com.example.repository.SavedMediaLibrary
import com.example.util.EchoSnapBackupManager
import com.example.util.ScannedBackupCanvas
import com.example.util.ScannedBackupCover
import com.example.util.ScannedEchoSnapBackup
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val BackupGlassFill = Color(0x16FFFFFF)
private val BackupGlassBorder = Color(0x28FFFFFF)

/**
 * Reusable Export / Import / Reset card for Echomusic folder backups
 * (covers, animated canvases, linked albums + song lists, and card styles).
 */
@Composable
fun EchoSnapBackupSectionCard(
    styleConfig: CardStyleConfig,
    modifier: Modifier = Modifier,
    onLivePreviewReset: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedCovers by SavedMediaLibrary.savedCovers.collectAsStateWithLifecycle()
    val savedCanvases by SavedMediaLibrary.savedCanvases.collectAsStateWithLifecycle()

    val totalSongs = remember(savedCovers, savedCanvases) {
        savedCovers.sumOf { it.linkedTrackTitles.size } +
            savedCanvases.sumOf { it.linkedTrackTitles.size }
    }

    var isBusy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("") }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var scannedBackupForPreview by remember { mutableStateOf<ScannedEchoSnapBackup?>(null) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var linkedFolderUri by remember {
        mutableStateOf(EchoSnapBackupManager.getPersistedBackupTreeUri(context))
    }
    var linkedFolderName by remember {
        mutableStateOf(EchoSnapBackupManager.getSavedFolderDisplayName(context))
    }

    fun runAutoExport(treeUri: Uri) {
        coroutineScope.launch {
            isBusy = true
            busyMessage = "Exporting to Echomusic folder..."
            val result = EchoSnapBackupManager.exportBackupToTreeUri(
                context = context,
                treeUri = treeUri,
                styleConfig = styleConfig
            )
            isBusy = false
            linkedFolderUri = EchoSnapBackupManager.getPersistedBackupTreeUri(context)
            linkedFolderName = EchoSnapBackupManager.getSavedFolderDisplayName(context)
            result.onSuccess { res ->
                val summary = "Exported ${res.coversCount} cover(s), ${res.canvasesCount} canvas(es) & ${res.songsCount} song(s) into ${res.folderName}/"
                lastStatusMessage = summary
                Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                val msg = "Export failed: ${err.message ?: "Unknown error"}"
                lastStatusMessage = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runAutoExport(treeUri)
    }

    val importFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isBusy = true
            busyMessage = "Scanning Echomusic folder..."
            EchoSnapBackupManager.savePersistedBackupTreeUri(context, treeUri)
            linkedFolderUri = EchoSnapBackupManager.getPersistedBackupTreeUri(context)
            linkedFolderName = EchoSnapBackupManager.getSavedFolderDisplayName(context)
            val scanned = EchoSnapBackupManager.scanBackupFromTreeUri(context, treeUri)
            isBusy = false
            scanned.onSuccess { backup ->
                if (backup.totalItemCount == 0 && backup.includedStyle == null) {
                    val emptyMsg = "No covers, canvases or manifest.json found inside \"${backup.folderDisplayName}\""
                    lastStatusMessage = emptyMsg
                    Toast.makeText(context, emptyMsg, Toast.LENGTH_LONG).show()
                } else {
                    scannedBackupForPreview = backup
                }
            }.onFailure { err ->
                val msg = "Could not read folder: ${err.message ?: "Unknown error"}"
                lastStatusMessage = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BackupGlassFill,
        border = BorderStroke(1.dp, BackupGlassBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("echosnap_backup_section")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Echomusic Auto-Backup",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Auto-imports & exports saved art, animated canvases & song lists in folder \"Echomusic\"",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp
                    )
                }
                if (isBusy) {
                    CircularProgressIndicator(
                        color = Color(0xFF1DB954),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Linked Echomusic SAF Folder status bar
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (linkedFolderUri != null) Color(0x1E1DB954) else Color(0x18FFFFFF),
                border = BorderStroke(
                    1.dp,
                    if (linkedFolderUri != null) Color(0x551DB954) else Color.White.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = "Linked Echomusic folder",
                            tint = if (linkedFolderUri != null) Color(0xFF9BE7B3) else Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (linkedFolderUri != null) {
                                val displayFolder = linkedFolderName ?: EchoSnapBackupManager.BACKUP_FOLDER_NAME
                                if (displayFolder.equals(EchoSnapBackupManager.BACKUP_FOLDER_NAME, ignoreCase = true)) {
                                    "Linked: $displayFolder/"
                                } else {
                                    "Linked: $displayFolder/${EchoSnapBackupManager.BACKUP_FOLDER_NAME}/"
                                }
                            } else {
                                "No folder linked yet — tap Export or Change"
                            },
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            exportFolderPicker.launch(EchoSnapBackupManager.suggestedInitialPickerUri())
                        },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.testTag("change_echosnap_folder_button")
                    ) {
                        Text(
                            text = if (linkedFolderUri != null) "Change" else "Choose",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Library data counters
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF12141D),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BackupCountPill(label = "Covers", count = savedCovers.size, accent = Color(0xFFFF8A96))
                    BackupCountPill(label = "Canvases", count = savedCanvases.size, accent = Color(0xFF9BE7B3))
                    BackupCountPill(label = "Songs", count = totalSongs, accent = Color(0xFF8BE2FF))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val persisted = EchoSnapBackupManager.getPersistedBackupTreeUri(context)
                        if (persisted != null) {
                            runAutoExport(persisted)
                        } else {
                            exportFolderPicker.launch(EchoSnapBackupManager.suggestedInitialPickerUri())
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor = Color(0xFF0A0D0B)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("export_echosnap_folder_button")
                ) {
                    Text(
                        text = "Export Echomusic",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = {
                        importFolderPicker.launch(EchoSnapBackupManager.suggestedInitialPickerUri())
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF2B44),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("import_echosnap_folder_button")
                ) {
                    Text(
                        text = "Import Folder",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFFF6B7A).copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A96)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_reset_data_dialog_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset data",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reset",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val displayStatus = if (isBusy && busyMessage.isNotBlank()) busyMessage else lastStatusMessage
            displayStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    color = Color(0xFFC5F7D5),
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("backup_status_text")
                )
            }
        }
    }

    scannedBackupForPreview?.let { scanned ->
        EchoSnapImportPreviewDialog(
            scanned = scanned,
            onRetour = { scannedBackupForPreview = null },
            onRequestReset = { showResetConfirmDialog = true },
            onConfirmImport = { selectedCovers, selectedCanvases, importStyle, replaceExisting ->
                coroutineScope.launch {
                    isBusy = true
                    busyMessage = "Importing from ${scanned.folderDisplayName}..."
                    val res = EchoSnapBackupManager.importScannedBackup(
                        context = context,
                        scanned = scanned,
                        selectedCoverIds = selectedCovers,
                        selectedCanvasIds = selectedCanvases,
                        importStyle = importStyle,
                        replaceExistingFirst = replaceExisting
                    )
                    isBusy = false
                    scannedBackupForPreview = null
                    res.onSuccess { outcome ->
                        val styleSuffix = if (outcome.styleApplied) " + Card Style" else ""
                        val msg = "Imported ${outcome.coversCount} cover(s), ${outcome.canvasesCount} canvas(es) & ${outcome.songsCount} song(s)$styleSuffix"
                        lastStatusMessage = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }.onFailure { err ->
                        val msg = "Import failed: ${err.message ?: "Unknown error"}"
                        lastStatusMessage = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showResetConfirmDialog) {
        EchoSnapResetConfirmDialog(
            hasCovers = savedCovers.isNotEmpty(),
            hasCanvases = savedCanvases.isNotEmpty(),
            onRetour = { showResetConfirmDialog = false },
            onConfirmReset = { resetLibrary, resetStyles ->
                showResetConfirmDialog = false
                if (resetStyles) {
                    onLivePreviewReset()
                }
                MediaNotificationRepository.resetBackupTargets(
                    context = context,
                    resetSavedLibrary = resetLibrary,
                    resetCardStyles = resetStyles
                )
                val msg = when {
                    resetLibrary && resetStyles -> "Reset saved covers, canvases, song lists & card styles"
                    resetLibrary -> "Cleared all saved covers, canvases & song lists"
                    resetStyles -> "Reset card visual settings to defaults"
                    else -> "Nothing changed"
                }
                lastStatusMessage = msg
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun BackupCountPill(
    label: String,
    count: Int,
    accent: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.64f),
            fontSize = 10.sp
        )
    }
}

/**
 * Full interactive preview showing EVERYTHING inside a chosen EchoSnap folder
 * (every Cover Art, Animated Canvas, Linked Album & complete Songs List),
 * with Retour, Reset, and Import controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EchoSnapImportPreviewDialog(
    scanned: ScannedEchoSnapBackup,
    onRetour: () -> Unit,
    onRequestReset: () -> Unit,
    onConfirmImport: (
        selectedCoverIds: Set<String>,
        selectedCanvasIds: Set<String>,
        importStyle: Boolean,
        replaceExisting: Boolean
    ) -> Unit
) {
    var selectedCoverIds by remember(scanned) {
        mutableStateOf(scanned.covers.map { it.id }.toSet())
    }
    var selectedCanvasIds by remember(scanned) {
        mutableStateOf(scanned.canvases.map { it.id }.toSet())
    }
    var importStyle by remember(scanned) { mutableStateOf(scanned.includedStyle != null) }
    var replaceExisting by remember(scanned) { mutableStateOf(false) }

    val selectedCount = selectedCoverIds.size + selectedCanvasIds.size
    val totalCount = scanned.totalItemCount
    val canImport = selectedCount > 0 || (importStyle && scanned.includedStyle != null)
    val glassShape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = onRetour,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 700.dp)
                .background(
                    brush = Brush.verticalGradient(listOf(Color(0xF01C1E2B), Color(0xF8101219))),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), glassShape)
                .testTag("echosnap_import_preview_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Header with Back/Retour icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onRetour,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("import_preview_top_retour_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Folder: ${scanned.folderDisplayName}",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val dateText = if (scanned.exportedAtMs > 0L) {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(scanned.exportedAtMs))
                        } else {
                            "Folder Media Scan"
                        }
                        Text(
                            text = "${scanned.covers.size} Covers • ${scanned.canvases.size} Canvases • ${scanned.totalSongsCount} Songs • $dateText",
                            color = Color(0xFF9BE7B3),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onRetour,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Select All / None + Options strip
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val allSelected = totalCount > 0 && selectedCount == totalCount
                    Surface(
                        onClick = {
                            if (allSelected) {
                                selectedCoverIds = emptySet()
                                selectedCanvasIds = emptySet()
                            } else {
                                selectedCoverIds = scanned.covers.map { it.id }.toSet()
                                selectedCanvasIds = scanned.canvases.map { it.id }.toSet()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier.testTag("import_toggle_all_chip")
                    ) {
                        Text(
                            text = if (allSelected) "Deselect All" else "Select All ($totalCount)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    if (scanned.includedStyle != null) {
                        Surface(
                            onClick = { importStyle = !importStyle },
                            shape = RoundedCornerShape(10.dp),
                            color = if (importStyle) Color(0x331DB954) else Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (importStyle) Color(0xFF1DB954) else Color.White.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.testTag("import_style_chip")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (importStyle) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = "Card Style Preset",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { replaceExisting = !replaceExisting },
                        shape = RoundedCornerShape(10.dp),
                        color = if (replaceExisting) Color(0x33FF2B44) else Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(
                            1.dp,
                            if (replaceExisting) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.14f)
                        ),
                        modifier = Modifier.testTag("import_replace_existing_chip")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            if (replaceExisting) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFFFF8A96),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = "Replace Existing",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Everything in EchoSnap folder list (Covers + Animated Canvases + Song Lists)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 120.dp, max = 420.dp)
                        .testTag("import_preview_items_list")
                ) {
                    if (scanned.canvases.isNotEmpty()) {
                        item(key = "header_canvases") {
                            Text(
                                text = "Saved Animated Canvases (${scanned.canvases.size})",
                                color = Color(0xFF9BE7B3),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        items(scanned.canvases, key = { "canvas_${it.id}" }) { canvasItem ->
                            val isChecked = canvasItem.id in selectedCanvasIds
                            ScannedCanvasPreviewRow(
                                item = canvasItem,
                                isChecked = isChecked,
                                onToggle = {
                                    selectedCanvasIds = if (isChecked) {
                                        selectedCanvasIds - canvasItem.id
                                    } else {
                                        selectedCanvasIds + canvasItem.id
                                    }
                                }
                            )
                        }
                    }

                    if (scanned.covers.isNotEmpty()) {
                        item(key = "header_covers") {
                            Text(
                                text = "Saved Album Covers (${scanned.covers.size})",
                                color = Color(0xFFFFB0B9),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = if (scanned.canvases.isNotEmpty()) 6.dp else 0.dp, bottom = 2.dp)
                            )
                        }
                        items(scanned.covers, key = { "cover_${it.id}" }) { coverItem ->
                            val isChecked = coverItem.id in selectedCoverIds
                            ScannedCoverPreviewRow(
                                item = coverItem,
                                isChecked = isChecked,
                                onToggle = {
                                    selectedCoverIds = if (isChecked) {
                                        selectedCoverIds - coverItem.id
                                    } else {
                                        selectedCoverIds + coverItem.id
                                    }
                                }
                            )
                        }
                    }

                    if (scanned.totalItemCount == 0 && scanned.includedStyle != null) {
                        item(key = "style_only_notice") {
                            Text(
                                text = "This Echomusic backup contains Card Visual Settings (${scanned.includedStyle.cardLayoutType}).",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Row: Retour | Reset | Import
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRetour,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("import_preview_retour_button")
                    ) {
                        Text("Retour", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onRequestReset,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF6B7A).copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A96)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("import_preview_reset_button")
                    ) {
                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            onConfirmImport(
                                selectedCoverIds,
                                selectedCanvasIds,
                                importStyle,
                                replaceExisting
                            )
                        },
                        enabled = canImport,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            contentColor = Color(0xFF0A0D0B)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("import_preview_confirm_button")
                    ) {
                        Text(
                            text = "Import ($selectedCount)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedCoverPreviewRow(
    item: ScannedBackupCover,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    ScannedMediaPreviewRow(
        title = item.name,
        subtitle = listOfNotNull(item.artist.takeIf { it.isNotBlank() }, item.resolutionLabel).joinToString(" • "),
        linkedAlbumName = item.linkedAlbumName,
        linkedAlbumArtist = item.linkedAlbumArtist,
        linkedTrackTitles = item.linkedTrackTitles,
        previewBitmap = item.previewBitmap,
        isVideo = false,
        accentColor = Color(0xFFFF2B44),
        badgeTextColor = Color(0xFFFFB0B9),
        isChecked = isChecked,
        onToggle = onToggle
    )
}

@Composable
private fun ScannedCanvasPreviewRow(
    item: ScannedBackupCanvas,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    ScannedMediaPreviewRow(
        title = item.name,
        subtitle = listOfNotNull(item.artist.takeIf { it.isNotBlank() }, item.resolutionLabel).joinToString(" • "),
        linkedAlbumName = item.linkedAlbumName,
        linkedAlbumArtist = item.linkedAlbumArtist,
        linkedTrackTitles = item.linkedTrackTitles,
        previewBitmap = item.previewBitmap,
        isVideo = true,
        accentColor = Color(0xFF1DB954),
        badgeTextColor = Color(0xFF9BE7B3),
        isChecked = isChecked,
        onToggle = onToggle
    )
}

@Composable
private fun ScannedMediaPreviewRow(
    title: String,
    subtitle: String,
    linkedAlbumName: String,
    linkedAlbumArtist: String,
    linkedTrackTitles: List<String>,
    previewBitmap: android.graphics.Bitmap?,
    isVideo: Boolean,
    accentColor: Color,
    badgeTextColor: Color,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    var songsExpanded by remember { mutableStateOf(linkedTrackTitles.size <= 4) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isChecked) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isChecked) accentColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null && !previewBitmap.isRecycled) {
                        val imgBmp = remember(previewBitmap) { previewBitmap.asImageBitmap() }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (isVideo) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Animated Canvas",
                            tint = Color(0xFF1DB954)
                        )
                    }
                    if (isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "MP4",
                                color = Color(0xFF9BE7B3),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (linkedAlbumName.isNotBlank() || linkedAlbumArtist.isNotBlank()) {
                        val albumSummary = listOfNotNull(
                            linkedAlbumName.takeIf { it.isNotBlank() },
                            linkedAlbumArtist.takeIf { it.isNotBlank() }
                        ).joinToString(" — ")
                        Text(
                            text = "Album: $albumSummary",
                            color = badgeTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = accentColor,
                        uncheckedColor = Color.White.copy(alpha = 0.55f),
                        checkmarkColor = if (accentColor == Color(0xFF1DB954)) Color.Black else Color.White
                    )
                )
            }

            if (linkedTrackTitles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10131C),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { songsExpanded = !songsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Linked Songs (${linkedTrackTitles.size})",
                                color = badgeTextColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (linkedTrackTitles.size > 4) {
                                Text(
                                    text = if (songsExpanded) "Hide" else "Show all",
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        val visibleSongs = if (songsExpanded) linkedTrackTitles else linkedTrackTitles.take(4)
                        visibleSongs.forEachIndexed { idx, songName ->
                            Text(
                                text = "${idx + 1}- $songName",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!songsExpanded && linkedTrackTitles.size > 4) {
                            Text(
                                text = "+${linkedTrackTitles.size - 4} more songs (tap to expand)",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Safe confirmation dialog for resetting Saved Media (Covers, Canvases, Song Lists)
 * and/or Card Visual Settings, with Retour & Reset buttons.
 */
@Composable
fun EchoSnapResetConfirmDialog(
    hasCovers: Boolean,
    hasCanvases: Boolean,
    onRetour: () -> Unit,
    onConfirmReset: (resetSavedLibrary: Boolean, resetCardStyles: Boolean) -> Unit
) {
    var resetSavedLibrary by remember { mutableStateOf(true) }
    var resetCardStyles by remember { mutableStateOf(false) }
    val glassShape = RoundedCornerShape(22.dp)

    Dialog(onDismissRequest = onRetour) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(listOf(Color(0xEE1D1E2B), Color(0xF612131A))),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), glassShape)
                .testTag("echosnap_reset_confirm_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Reset EchoSnap Data",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose what to clear or restore to defaults. Export to your Echomusic folder first if you want a backup.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.5.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    onClick = { resetSavedLibrary = !resetSavedLibrary },
                    shape = RoundedCornerShape(12.dp),
                    color = if (resetSavedLibrary) Color(0x28FF2B44) else Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(
                        1.dp,
                        if (resetSavedLibrary) Color(0x88FF2B44) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_option_library")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Checkbox(
                            checked = resetSavedLibrary,
                            onCheckedChange = { resetSavedLibrary = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFFF2B44),
                                uncheckedColor = Color.White.copy(alpha = 0.55f),
                                checkmarkColor = Color.White
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saved Covers, Canvases & Song Lists",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (!hasCovers && !hasCanvases) {
                                    "Saved library is currently empty"
                                } else {
                                    "Removes all downloaded art, looping canvas MP4s & album links"
                                },
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    onClick = { resetCardStyles = !resetCardStyles },
                    shape = RoundedCornerShape(12.dp),
                    color = if (resetCardStyles) Color(0x28FF2B44) else Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(
                        1.dp,
                        if (resetCardStyles) Color(0x88FF2B44) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_option_styles")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Checkbox(
                            checked = resetCardStyles,
                            onCheckedChange = { resetCardStyles = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFFF2B44),
                                uncheckedColor = Color.White.copy(alpha = 0.55f),
                                checkmarkColor = Color.White
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Card Sliders & Visual Defaults",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Restores Blur, Card Size, Opacity, Waveform & keeps Spotify login",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetour,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("reset_confirm_retour_button")
                    ) {
                        Text("Retour", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onConfirmReset(resetSavedLibrary, resetCardStyles) },
                        enabled = resetSavedLibrary || resetCardStyles,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF2B44),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("reset_confirm_execute_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
