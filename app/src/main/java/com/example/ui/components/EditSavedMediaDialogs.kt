package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
fun SavedCanvasesBrowserDialog(
    items: List<SavedCanvasItem>,
    activeCanvasId: String,
    onDismiss: () -> Unit,
    onAddFromDevice: () -> Unit,
    onSelect: (SavedCanvasItem) -> Unit,
    onRename: (SavedCanvasItem, String) -> Unit,
    onDelete: (SavedCanvasItem) -> Unit
) {
    var renamingItem by remember { mutableStateOf<SavedCanvasItem?>(null) }
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
                            text = "Saved offline loops in max resolution",
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
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { renamingItem = item }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Rename canvas",
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

    renamingItem?.let { target ->
        SaveMediaNameDialog(
            title = "Rename Saved Canvas",
            subtitle = target.resolutionLabel,
            initialName = target.name,
            confirmLabel = "Save Name",
            accentColor = Color(0xFF1DB954),
            onDismiss = { renamingItem = null },
            onConfirm = { newName ->
                onRename(target, newName)
                renamingItem = null
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
