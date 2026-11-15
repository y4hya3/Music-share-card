package com.example.ui.components

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import kotlin.math.roundToInt

/**
 * Edit Card Bottom Sheet.
 * All edits persist automatically via CardStylePreferences across track changes,
 * player switches, and app restarts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditCardBottomSheet(
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    currentTrack: MediaTrackInfo,
    onDismiss: () -> Unit,
    onStyleChange: (CardStyleConfig) -> Unit,
    onTrackSelect: (MediaTrackInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    val onlineCovers by MediaNotificationRepository.onlineCoverCandidates.collectAsState()
    val isSearchingCovers by MediaNotificationRepository.isSearchingCoverArt.collectAsState()
    val originalPlayerArt by MediaNotificationRepository.originalPlayerArt.collectAsState()
    val selectedCoverId by MediaNotificationRepository.selectedCoverId.collectAsState()

    var editTitle by remember(currentTrack.title, currentTrack.packageName) {
        mutableStateOf(currentTrack.title)
    }
    var editArtist by remember(currentTrack.artist, currentTrack.packageName) {
        mutableStateOf(currentTrack.artist)
    }
    var coverSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E24),
        contentColor = Color.White,
        modifier = modifier.testTag("edit_card_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Customize Share Card",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Changes stay saved across songs & app restarts",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_edit_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. CARD BRAND (LOGO & NAME)
            Text(
                text = "Card Logo & Name",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Choose whether the share card displays Bitchord, Spotify, or Apple Music branding.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrandModeChip(
                    brandMode = CardBrandMode.BITCHORD,
                    previewBadgeColor = Color(0xFFFF2B44),
                    previewLogoColorMode = LogoColorMode.RED,
                    isSelected = styleConfig.brandMode == CardBrandMode.BITCHORD,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                            LogoColorMode.RED
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.BITCHORD,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_bitchord")
                )

                BrandModeChip(
                    brandMode = CardBrandMode.SPOTIFY,
                    previewBadgeColor = Color(0xFF1DB954),
                    previewLogoColorMode = LogoColorMode.GREEN,
                    isSelected = styleConfig.brandMode == CardBrandMode.SPOTIFY,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.RED) {
                            LogoColorMode.GREEN
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.SPOTIFY,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_spotify")
                )

                BrandModeChip(
                    brandMode = CardBrandMode.APPLE_MUSIC,
                    previewBadgeColor = Color(0xFFFA243C),
                    previewLogoColorMode = LogoColorMode.RED,
                    isSelected = styleConfig.brandMode == CardBrandMode.APPLE_MUSIC,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                            LogoColorMode.RED
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.APPLE_MUSIC,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_apple_music")
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. LOGO BADGE COLOR SECTION
            Text(
                text = "Logo Badge Color",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Changes the icon badge color while keeping the '${styleConfig.brandMode.wordmark}' text crisp white.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LogoColorOption(
                    label = "Red",
                    color = Color(0xFFFF2B44),
                    isSelected = styleConfig.logoColorMode == LogoColorMode.RED,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.RED)) }
                )
                LogoColorOption(
                    label = "Template",
                    color = palette.vibrantColor,
                    isSelected = styleConfig.logoColorMode == LogoColorMode.TEMPLATE_DYNAMIC,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.TEMPLATE_DYNAMIC)) }
                )
                LogoColorOption(
                    label = "Green",
                    color = Color(0xFF1DB954),
                    isSelected = styleConfig.logoColorMode == LogoColorMode.GREEN,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.GREEN)) }
                )
                LogoColorOption(
                    label = "White",
                    color = Color.White,
                    isSelected = styleConfig.logoColorMode == LogoColorMode.WHITE,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.WHITE)) }
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3. ONLINE HD COVER ART SEARCH & CHOOSER
            Text(
                text = "High-Quality Cover Art (Online)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Auto-search online HD covers or pick between multiple 1000×1000 results.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF262630),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Auto-search HD cover online",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically looks up 1000px covers when songs play",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = styleConfig.autoSearchOnlineArt,
                        onCheckedChange = { enabled ->
                            onStyleChange(styleConfig.copy(autoSearchOnlineArt = enabled))
                            if (enabled && onlineCovers.isEmpty()) {
                                MediaNotificationRepository.searchOnlineCovers(
                                    customQuery = coverSearchQuery,
                                    autoApplyBest = !currentTrack.isSample
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFFF2B44)
                        ),
                        modifier = Modifier.testTag("auto_search_online_art_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = coverSearchQuery,
                    onValueChange = { coverSearchQuery = it },
                    singleLine = true,
                    placeholder = { Text("Song & artist", fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF2B44),
                        unfocusedBorderColor = Color(0xFF444450),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("online_cover_search_input")
                )

                Button(
                    onClick = {
                        MediaNotificationRepository.searchOnlineCovers(
                            customQuery = coverSearchQuery,
                            autoApplyBest = false
                        )
                    },
                    enabled = !isSearchingCovers && coverSearchQuery.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    modifier = Modifier.testTag("search_online_cover_button")
                ) {
                    if (isSearchingCovers) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search HD covers",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Find", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (originalPlayerArt != null || onlineCovers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    originalPlayerArt?.let { orig ->
                        item(key = "player_art") {
                            CoverArtChoiceTile(
                                bitmap = orig,
                                title = "Player Art",
                                subtitle = "${orig.width}×${orig.height}",
                                isSelected = selectedCoverId == "player",
                                onClick = { MediaNotificationRepository.restoreOriginalPlayerCover() },
                                modifier = Modifier.testTag("cover_choice_player")
                            )
                        }
                    }

                    items(onlineCovers, key = { it.id }) { candidate ->
                        candidate.thumbnailBitmap?.let { thumb ->
                            CoverArtChoiceTile(
                                bitmap = thumb,
                                title = candidate.source,
                                subtitle = candidate.resolutionLabel,
                                isSelected = selectedCoverId == candidate.id,
                                onClick = { MediaNotificationRepository.selectOnlineCover(candidate) },
                                modifier = Modifier.testTag("cover_choice_${candidate.id}")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. SINGLE CARD SIZE CONTROL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Card Size",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = "${(styleConfig.cardScale * 100f).roundToInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
            Slider(
                value = styleConfig.cardScale.coerceIn(0.75f, 1.15f),
                onValueChange = { newScale ->
                    onStyleChange(styleConfig.copy(cardScale = newScale.coerceIn(0.75f, 1.15f)))
                },
                valueRange = 0.75f..1.15f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF2B44),
                    activeTrackColor = Color(0xFFFF2B44),
                    inactiveTrackColor = Color(0xFF40404C)
                ),
                modifier = Modifier.testTag("card_size_slider")
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 5. BACKGROUND STYLE & PALETTE GRADIENTS
            Text(
                text = "Background Style & Gradient",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { onStyleChange(styleConfig.copy(useBlurredAlbumBackground = true)) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (styleConfig.useBlurredAlbumBackground) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bg_mode_blurred_album")
                ) {
                    Text(
                        text = "Blurred Album Cover",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (styleConfig.useBlurredAlbumBackground) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }

                Surface(
                    onClick = { onStyleChange(styleConfig.copy(useBlurredAlbumBackground = false)) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (!styleConfig.useBlurredAlbumBackground) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bg_mode_palette_gradient")
                ) {
                    Text(
                        text = "Palette Gradient",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (!styleConfig.useBlurredAlbumBackground) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }

            if (styleConfig.useBlurredAlbumBackground) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Background Blur Intensity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = "${styleConfig.backgroundBlurRadius.roundToInt()}px",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
                Slider(
                    value = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f),
                    onValueChange = {
                        onStyleChange(
                            styleConfig.copy(
                                useBlurredAlbumBackground = true,
                                backgroundBlurRadius = it
                            )
                        )
                    },
                    valueRange = 0f..40f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF2B44),
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color(0xFF40404C)
                    ),
                    modifier = Modifier.testTag("sheet_background_blur_slider")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Adaptive Palette Gradients",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GradientStyle.values().forEach { style ->
                    val isSelected = !styleConfig.useBlurredAlbumBackground && styleConfig.gradientStyle == style
                    Surface(
                        onClick = {
                            onStyleChange(
                                styleConfig.copy(
                                    useBlurredAlbumBackground = false,
                                    gradientStyle = style
                                )
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier.testTag("gradient_preset_${style.name}")
                    ) {
                        Text(
                            text = style.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. CARD LAYOUT STYLE
            Text(
                text = "Card Layout Style",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CardLayoutType.values().forEach { layout ->
                    val isSelected = styleConfig.cardLayoutType == layout
                    Surface(
                        onClick = { onStyleChange(styleConfig.copy(cardLayoutType = layout)) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_type_${layout.name}")
                    ) {
                        Text(
                            text = layout.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 7. SHADOW & DEPTH INTENSITY
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Card Shadow Depth",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = "${(styleConfig.glowIntensity * 100).toInt()}%",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            Slider(
                value = styleConfig.glowIntensity,
                onValueChange = { onStyleChange(styleConfig.copy(glowIntensity = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF2B44),
                    activeTrackColor = Color(0xFFFF2B44),
                    inactiveTrackColor = Color(0xFF40404C)
                ),
                modifier = Modifier.testTag("glow_intensity_slider")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 8. SAMPLE TRACK SELECTOR
            Text(
                text = "Preset Demo Tracks",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Test instant palette extraction and background blur with sample songs:",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            val samples = MediaNotificationRepository.getSampleTracks()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                samples.forEach { sample ->
                    val isSelected = currentTrack.title == sample.title
                    Surface(
                        onClick = { onTrackSelect(sample) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier.testTag("sample_track_${sample.title}")
                    ) {
                        Text(
                            text = "${sample.title} • ${sample.artist}",
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 9. EDIT TRACK METADATA
            Text(
                text = "Edit Track Information",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = editTitle,
                onValueChange = {
                    editTitle = it
                    MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                },
                label = { Text("Track Title") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF2B44),
                    unfocusedBorderColor = Color(0xFF444450),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFFFF2B44),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_track_title_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = editArtist,
                onValueChange = {
                    editArtist = it
                    MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                },
                label = { Text("Artist Name / Album") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF2B44),
                    unfocusedBorderColor = Color(0xFF444450),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFFFF2B44),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_artist_name_input")
            )
        }
    }
}

@Composable
private fun CoverArtChoiceTile(
    bitmap: Bitmap,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) Color(0xFF2E222A) else Color(0xFF26262F))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrandModeChip(
    brandMode: CardBrandMode,
    previewBadgeColor: Color,
    previewLogoColorMode: LogoColorMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Color(0xFF2D2D38) else Color(0xFF24242C),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) previewBadgeColor else Color.White.copy(alpha = 0.10f)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BitChordBadge(
                brandMode = brandMode,
                badgeColor = previewBadgeColor,
                logoColorMode = previewLogoColorMode,
                size = 22.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = brandMode.label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LogoColorOption(
    label: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag("logo_color_option_$label")
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color.White else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (color == Color.White) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
