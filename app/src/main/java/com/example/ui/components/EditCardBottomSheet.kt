package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
 * Enables live customization of:
 * 1. Card Brand (Logo & Name): Bitchord or Spotify.
 * 2. Logo Badge Color: Signature Red, Template Dynamic Color, Green, White.
 * 3. Background Mode: Full-Screen Blurred Album Cover (0px..40px) vs Palette Gradient.
 * 4. Card Layout Type & Shadow Depth.
 * 5. Preset Demo Tracks & Track Metadata editor.
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

    var editTitle by remember(currentTrack) { mutableStateOf(currentTrack.title) }
    var editArtist by remember(currentTrack) { mutableStateOf(currentTrack.artist) }

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
                Text(
                    text = "Customize Share Card",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
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

            // 3. BACKGROUND STYLE & BLUR
            Text(
                text = "Background Style",
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

            Spacer(modifier = Modifier.height(16.dp))

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

            if (!styleConfig.useBlurredAlbumBackground) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Gradient Palette",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GradientStyle.values().forEach { style ->
                        val isSelected = styleConfig.gradientStyle == style
                        Surface(
                            onClick = { onStyleChange(styleConfig.copy(gradientStyle = style)) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                            modifier = Modifier.testTag("gradient_preset_${style.name}")
                        ) {
                            Text(
                                text = style.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. CARD LAYOUT STYLE
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

            Spacer(modifier = Modifier.height(22.dp))

            // 5. SHADOW & DEPTH INTENSITY
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

            Spacer(modifier = Modifier.height(18.dp))

            // 6. SAMPLE TRACK SELECTOR
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

            // 7. EDIT TRACK METADATA
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
