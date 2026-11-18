package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.repository.SavedCanvasItem
import com.example.repository.SavedCoverItem
import com.example.repository.SavedMediaLibrary
import com.example.service.AlbumTracklistCandidate
import com.example.service.AlbumTracklistSearchService
import kotlinx.coroutines.launch

@Composable
fun SaveMediaNameDialog(
    title: String,
    subtitle: String,
    initialName: String,
    confirmLabel: String,
    accentColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nameInput by remember(initialName) { mutableStateOf(initialName) }
    val glassShape = RoundedCornerShape(22.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xE81C1D28), Color(0xF212131A))
                    ),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), glassShape)
                .testTag("save_media_name_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("Change Name", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = accentColor,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_media_name_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.75f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(nameInput.trim().ifEmpty { initialName }) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = if (accentColor == Color(0xFF1DB954)) Color(0xFF0A0C0B) else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_save_media_button")
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun EditSavedCanvasDialog(
    item: SavedCanvasItem,
    onDismiss: () -> Unit,
    onConfirm: (
        newName: String,
        linkedAlbumName: String,
        linkedAlbumArtist: String,
        linkedTrackTitles: List<String>
    ) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var nameInput by remember(item.id) { mutableStateOf(item.name) }
    var albumQueryInput by remember(item.id) { mutableStateOf(item.linkedAlbumName) }
    var linkedAlbumName by remember(item.id) { mutableStateOf(item.linkedAlbumName) }
    var linkedAlbumArtist by remember(item.id) { mutableStateOf(item.linkedAlbumArtist) }
    var linkedTracks by remember(item.id) { mutableStateOf(item.linkedTrackTitles) }

    var albumResults by remember(item.id) { mutableStateOf<List<AlbumTracklistCandidate>>(emptyList()) }
    var isSearchingAlbum by remember(item.id) { mutableStateOf(false) }
    var albumSearchMessage by remember(item.id) { mutableStateOf<String?>(null) }

    val glassShape = RoundedCornerShape(22.dp)
    val accentColor = Color(0xFF1DB954)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xEC1B1D29), Color(0xF5111218))
                    ),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), glassShape)
                .testTag("edit_saved_canvas_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Edit Saved Canvas",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.resolutionLabel,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("Canvas Name", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = accentColor,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_saved_canvas_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Link Album (Auto-Apply Offline)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (linkedAlbumName.isNotBlank() || linkedTracks.isNotEmpty()) {
                                Surface(
                                    onClick = {
                                        linkedAlbumName = ""
                                        linkedAlbumArtist = ""
                                        linkedTracks = emptyList()
                                        albumQueryInput = ""
                                        albumResults = emptyList()
                                        albumSearchMessage = "Album link cleared"
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0x44FF5A6B),
                                    modifier = Modifier.testTag("clear_saved_canvas_album_button")
                                ) {
                                    Text(
                                        text = "Unlink",
                                        color = Color(0xFFFF8A96),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Put any album name to sketch its songs, confirm it's the right album, then Save. Plays this canvas whenever a song from the album plays — even offline.",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = albumQueryInput,
                                onValueChange = { albumQueryInput = it },
                                singleLine = true,
                                label = { Text("Album Name", fontSize = 12.sp) },
                                placeholder = { Text("e.g. ASTROWORLD, After Hours", fontSize = 11.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = accentColor,
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.65f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_saved_canvas_album_input")
                            )

                            Button(
                                onClick = {
                                    val q = albumQueryInput.trim()
                                    if (q.isEmpty()) return@Button
                                    coroutineScope.launch {
                                        isSearchingAlbum = true
                                        albumSearchMessage = null
                                        val found = AlbumTracklistSearchService.searchAlbumsWithSongs(
                                            albumQuery = q,
                                            artistHint = item.artist
                                        )
                                        isSearchingAlbum = false
                                        albumResults = found
                                        if (found.isNotEmpty()) {
                                            val top = found.first()
                                            linkedAlbumName = top.albumName
                                            linkedAlbumArtist = top.artistName
                                            linkedTracks = top.trackTitles
                                            albumQueryInput = top.albumName
                                            albumSearchMessage = "Found ${top.trackTitles.size} songs (${top.source}) — check below & Save"
                                        } else {
                                            linkedAlbumName = q
                                            linkedAlbumArtist = ""
                                            linkedTracks = emptyList()
                                            albumSearchMessage = "No online tracklist found — will still match album name \"$q\" offline"
                                        }
                                    }
                                },
                                enabled = !isSearchingAlbum && albumQueryInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color(0xFF0A0C0B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp),
                                modifier = Modifier.testTag("search_saved_canvas_album_button")
                            ) {
                                if (isSearchingAlbum) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF0A0C0B),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search album songs",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Songs", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (albumResults.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                albumResults.forEach { cand ->
                                    val active = cand.albumName == linkedAlbumName && cand.artistName == linkedAlbumArtist
                                    Surface(
                                        onClick = {
                                            linkedAlbumName = cand.albumName
                                            linkedAlbumArtist = cand.artistName
                                            linkedTracks = cand.trackTitles
                                            albumQueryInput = cand.albumName
                                            albumSearchMessage = "${cand.albumName} • ${cand.trackTitles.size} songs selected"
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (active) Color(0xFF1F3B27) else Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (active) accentColor else Color.White.copy(alpha = 0.14f)
                                        )
                                    ) {
                                        Text(
                                            text = "${cand.albumName} • ${cand.artistName.ifBlank { cand.source }} (${cand.trackTitles.size})",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        albumSearchMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = msg,
                                color = Color(0xFF9BE7B3),
                                fontSize = 11.sp
                            )
                        }

                        if (linkedAlbumName.isNotBlank() || linkedTracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF12151E),
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .heightIn(max = 155.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = buildString {
                                            append(linkedAlbumName.ifBlank { "Linked Album" })
                                            if (linkedAlbumArtist.isNotBlank()) append(" — $linkedAlbumArtist")
                                        },
                                        color = Color(0xFF9BE7B3),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (linkedTracks.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(5.dp))
                                        linkedTracks.forEachIndexed { idx, songTitle ->
                                            Text(
                                                text = "${idx + 1}. $songTitle",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.75f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val manualAlbumFallback = if (linkedAlbumName.isBlank() && albumQueryInput.isNotBlank()) {
                                albumQueryInput.trim()
                            } else {
                                linkedAlbumName.trim()
                            }
                            onConfirm(
                                nameInput.trim().ifEmpty { item.name },
                                manualAlbumFallback,
                                linkedAlbumArtist.trim(),
                                linkedTracks
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color(0xFF0A0C0B)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_edit_saved_canvas_button")
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SavedCanvasesBrowserDialog(
    items: List<SavedCanvasItem>,
    activeCanvasId: String,
    onDismiss: () -> Unit,
    onAddFromDevice: () -> Unit,
    onSelect: (SavedCanvasItem) -> Unit,
    onUpdateCanvas: (
        item: SavedCanvasItem,
        newName: String,
        linkedAlbumName: String,
        linkedAlbumArtist: String,
        linkedTrackTitles: List<String>
    ) -> Unit,
    onDelete: (SavedCanvasItem) -> Unit
) {
    var editingItem by remember { mutableStateOf<SavedCanvasItem?>(null) }
    val glassShape = RoundedCornerShape(24.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(listOf(Color(0xEB1B1C27), Color(0xF5111218))),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), glassShape)
                .testTag("saved_canvases_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Saved Canvases (${items.size})",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap Edit to link an album for offline auto-apply",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onAddFromDevice,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            contentColor = Color(0xFF0A0C0B)
                        ),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("saved_canvases_add_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.75f))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved canvases yet.\nLong-press any online canvas or tap + Add to save videos locally.",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            val selected = activeCanvasId == "saved_canvas_${item.id}"
                            val thumbBitmap = remember(item.thumbnailPath) {
                                SavedMediaLibrary.loadBitmap(item.thumbnailPath, maxDimension = 200)?.asImageBitmap()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onSelect(item) }
                                    .background(if (selected) Color(0xFF1E3626) else Color.White.copy(alpha = 0.06f))
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) Color(0xFF1DB954) else Color.White.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbBitmap != null) {
                                        Image(
                                            bitmap = thumbBitmap,
                                            contentDescription = item.name,
                                            contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.High,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color(0xFF1DB954)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            item.artist.takeIf { it.isNotBlank() },
                                            item.resolutionLabel
                                        ).joinToString(" • "),
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.linkedAlbumName.isNotBlank()) {
                                        val songCountSuffix = if (item.linkedTrackTitles.isNotEmpty()) {
                                            " (${item.linkedTrackTitles.size} songs)"
                                        } else {
                                            ""
                                        }
                                        Text(
                                            text = "Album: ${item.linkedAlbumName}$songCountSuffix",
                                            color = Color(0xFF9BE7B3),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { editingItem = item },
                                    modifier = Modifier.testTag("edit_saved_canvas_${item.id}")
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit canvas",
                                        tint = Color.White.copy(alpha = 0.72f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onDelete(item) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete canvas",
                                        tint = Color(0xFFFF7A85),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { target ->
        EditSavedCanvasDialog(
            item = target,
            onDismiss = { editingItem = null },
            onConfirm = { newName, albumName, albumArtist, tracks ->
                onUpdateCanvas(target, newName, albumName, albumArtist, tracks)
                editingItem = null
            }
        )
    }
}

@Composable
fun SavedCoversBrowserDialog(
    items: List<SavedCoverItem>,
    selectedCoverId: String,
    onDismiss: () -> Unit,
    onAddFromDevice: () -> Unit,
    onSelect: (SavedCoverItem) -> Unit,
    onRename: (SavedCoverItem, String) -> Unit,
    onDelete: (SavedCoverItem) -> Unit
) {
    var renamingItem by remember { mutableStateOf<SavedCoverItem?>(null) }
    val glassShape = RoundedCornerShape(24.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = glassShape,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(listOf(Color(0xEB1B1C27), Color(0xF5111218))),
                    shape = glassShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), glassShape)
                .testTag("saved_covers_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Saved Covers (${items.size})",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Max-resolution cover art saved locally",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onAddFromDevice,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("saved_covers_add_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.75f))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved cover arts yet.\nLong-press any cover tile or tap + Add to store covers locally.",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            val selected = selectedCoverId == "saved_cover_${item.id}"
                            val thumbBitmap = remember(item.localImagePath) {
                                SavedMediaLibrary.loadBitmap(item.localImagePath, maxDimension = 200)?.asImageBitmap()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onSelect(item) }
                                    .background(if (selected) Color(0xFF331E26) else Color.White.copy(alpha = 0.06f))
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbBitmap != null) {
                                        Image(
                                            bitmap = thumbBitmap,
                                            contentDescription = item.name,
                                            contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.High,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            item.artist.takeIf { it.isNotBlank() },
                                            item.resolutionLabel
                                        ).joinToString(" • "),
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = Color(0xFFFF2B44),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { renamingItem = item }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Rename cover",
                                        tint = Color.White.copy(alpha = 0.72f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onDelete(item) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete cover",
                                        tint = Color(0xFFFF7A85),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renamingItem?.let { target ->
        SaveMediaNameDialog(
            title = "Rename Saved Cover",
            subtitle = target.resolutionLabel,
            initialName = target.name,
            confirmLabel = "Save Name",
            accentColor = Color(0xFFFF2B44),
            onDismiss = { renamingItem = null },
            onConfirm = { newName ->
                onRename(target, newName)
                renamingItem = null
            }
        )
    }
}
