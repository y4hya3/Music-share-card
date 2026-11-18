package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.toArgb
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.service.MusicNotificationListenerService
import com.example.ui.SpotifyLoginActivity
import com.example.util.AudioBeatMonitor
import java.util.Locale

private val SettingsGlassFill = Color(0x16FFFFFF)
private val SettingsGlassBorder = Color(0x28FFFFFF)

/**
 * Frosted-glass EchoSnap Settings dialog.
 * Allows configuring Waveform Motion (Default Speed 0.4x..2.5x vs Live Music Beats),
 * Lyrics Selection Mode (Lines vs Word by Word), auto-search toggles, and system access status.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    styleConfig: CardStyleConfig,
    currentTrack: MediaTrackInfo,
    palette: CardPalette = CardPalette.Default,
    isAccessGranted: Boolean,
    onStyleChange: (CardStyleConfig) -> Unit,
    onRequestAudioBeatSync: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hasMicPerm = remember(styleConfig.waveformBeatSync) {
        AudioBeatMonitor.hasRecordAudioPermission(context)
    }
    var localSpeed by remember(styleConfig.waveformSpeedMultiplier) {
        mutableFloatStateOf(styleConfig.waveformSpeedMultiplier.coerceIn(0.4f, 2.5f))
    }
    var localForce by remember(styleConfig.waveformForce) {
        mutableFloatStateOf(styleConfig.waveformForce.coerceIn(0.5f, 2.2f))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val cardShape = RoundedCornerShape(26.dp)
        Box(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 680.dp)
                .clip(cardShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEB222434),
                            Color(0xF513141E)
                        )
                    ),
                    shape = cardShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), cardShape)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Settings",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Waveform speed, beat reaction, lyrics mode & access",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_settings_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close settings",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 1. WAVEFORM MOTION & SPEED
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SettingsGlassFill,
                    border = BorderStroke(1.dp, SettingsGlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Waveform Motion",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (styleConfig.waveformBeatSync) {
                                        "Reacts to your playing music beats"
                                    } else {
                                        "Speed ${String.format(Locale.US, "%.1fx", localSpeed)} • Force ${String.format(Locale.US, "%.1fx", localForce)}"
                                    },
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    DynamicIslandWaveformBars(
                                        track = currentTrack,
                                        color = styleConfig.waveformColor,
                                        beatSyncEnabled = styleConfig.waveformBeatSync,
                                        speedMultiplier = localSpeed,
                                        forceMultiplier = localForce,
                                        animateAlways = true,
                                        scale = 1.15f
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Show Waveform on Card",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (styleConfig.enableWaveform) {
                                        "Visible on Floating Glass card (exports MP4 / transparent GIF)"
                                    } else {
                                        "Hidden — configure speed, beats & color without turning it on"
                                    },
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.enableWaveform,
                                onCheckedChange = { enabled ->
                                    onStyleChange(styleConfig.copy(enableWaveform = enabled))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF2B44)
                                ),
                                modifier = Modifier.testTag("settings_enable_waveform_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isSpeedMode = !styleConfig.waveformBeatSync
                            Surface(
                                onClick = { onRequestAudioBeatSync(false) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSpeedMode) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, if (isSpeedMode) Color.Transparent else SettingsGlassBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_waveform_mode_speed")
                            ) {
                                Text(
                                    text = "Default Speed",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSpeedMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = { onRequestAudioBeatSync(true) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!isSpeedMode) Color(0xFF1DB954) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, if (!isSpeedMode) Color.Transparent else SettingsGlassBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_waveform_mode_beats")
                            ) {
                                Text(
                                    text = "Music Beats",
                                    color = if (!isSpeedMode) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isSpeedMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }

                        if (!styleConfig.waveformBeatSync) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Wave Speed",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", localSpeed),
                                    color = Color(0xFFFF8A96),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("settings_waveform_speed_value")
                                )
                            }
                            Slider(
                                value = localSpeed,
                                onValueChange = { next ->
                                    localSpeed = ((next * 10f).toInt() / 10f).coerceIn(0.4f, 2.5f)
                                },
                                onValueChangeFinished = {
                                    onStyleChange(styleConfig.copy(waveformSpeedMultiplier = localSpeed))
                                },
                                valueRange = 0.4f..2.5f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF2B44),
                                    activeTrackColor = Color(0xFFFF2B44),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                ),
                                modifier = Modifier.testTag("settings_waveform_speed_slider")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    0.6f to "Slow (0.6x)",
                                    1.0f to "Normal (1.0x)",
                                    1.6f to "Fast (1.6x)",
                                    2.2f to "Hyper (2.2x)"
                                )
                                presets.forEach { (presetSpeed, label) ->
                                    val active = kotlin.math.abs(localSpeed - presetSpeed) < 0.08f
                                    Surface(
                                        onClick = {
                                            localSpeed = presetSpeed
                                            onStyleChange(styleConfig.copy(waveformSpeedMultiplier = presetSpeed))
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = if (active) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.10f),
                                        modifier = Modifier.testTag("settings_speed_preset_$presetSpeed")
                                    ) {
                                        Text(
                                            text = label,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Wave Force (Height)",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", localForce),
                                    color = Color(0xFF9BE7B3),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("settings_waveform_force_value")
                                )
                            }
                            Slider(
                                value = localForce,
                                onValueChange = { next ->
                                    localForce = ((next * 10f).toInt() / 10f).coerceIn(0.5f, 2.2f)
                                },
                                onValueChangeFinished = {
                                    onStyleChange(styleConfig.copy(waveformForce = localForce))
                                },
                                valueRange = 0.5f..2.2f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF1DB954),
                                    activeTrackColor = Color(0xFF1DB954),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                ),
                                modifier = Modifier.testTag("settings_waveform_force_slider")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val forcePresets = listOf(
                                    0.7f to "Soft (0.7x)",
                                    1.1f to "Balanced (1.1x)",
                                    1.5f to "Punchy (1.5x)",
                                    2.0f to "Max (2.0x)"
                                )
                                forcePresets.forEach { (presetForce, label) ->
                                    val active = kotlin.math.abs(localForce - presetForce) < 0.09f
                                    Surface(
                                        onClick = {
                                            localForce = presetForce
                                            onStyleChange(styleConfig.copy(waveformForce = presetForce))
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = if (active) Color(0xFF1DB954) else Color.White.copy(alpha = 0.10f),
                                        modifier = Modifier.testTag("settings_force_preset_$presetForce")
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (active) Color.Black else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x281DB954),
                                border = BorderStroke(1.dp, Color(0x551DB954)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (hasMicPerm) {
                                        "Zero-latency Music Beats active — 4-band spectrum + PCM kick detection reacts instantly to playing audio."
                                    } else {
                                        "Tempo Beat Sync active — grant microphone permission for real-time audio spectrum capture."
                                    },
                                    color = Color(0xFFC5F7D5),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Waveform Bar Color",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val swatchOptions = listOf(
                            "White" to Color.White,
                            "Green" to Color(0xFF1DB954),
                            "Red" to Color(0xFFFF2B44),
                            "Cyan" to Color(0xFF38E8FF),
                            "Amber" to Color(0xFFFFB830),
                            "Violet" to Color(0xFFA855F7),
                            "Pink" to Color(0xFFFF4D9E),
                            "Accent" to palette.vibrantColor
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            swatchOptions.forEach { (label, color) ->
                                val isPicked = styleConfig.waveformColor.toArgb() == color.toArgb()
                                val isLightSwatch = color == Color.White || color == Color(0xFF38E8FF) || color == Color(0xFFFFB830)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isPicked) 2.5.dp else 1.dp,
                                            color = if (isPicked) Color.White else SettingsGlassBorder,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            onStyleChange(
                                                styleConfig.copy(waveformColor = color)
                                            )
                                        }
                                        .testTag("settings_waveform_color_$label"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isPicked) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = label,
                                            tint = if (isLightSwatch) Color.Black else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. LYRICS PICKER MODE
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SettingsGlassFill,
                    border = BorderStroke(1.dp, SettingsGlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Lyrics Selection Mode",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Choose full lines or tap individual words word-by-word in Lyrics Card",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isWordMode = styleConfig.lyricsWordSelectionMode
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(lyricsWordSelectionMode = false)) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!isWordMode) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_lyrics_lines")
                            ) {
                                Text(
                                    text = "Full Lines",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isWordMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(lyricsWordSelectionMode = true)) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isWordMode) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_lyrics_word_by_word")
                            ) {
                                Text(
                                    text = "Word by Word",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isWordMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. ONLINE DISCOVERY & CONNECTIONS
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SettingsGlassFill,
                    border = BorderStroke(1.dp, SettingsGlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Auto-search HD cover online",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Fetch crisp artwork for offline & third-party players",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.autoSearchOnlineArt,
                                onCheckedChange = { onStyleChange(styleConfig.copy(autoSearchOnlineArt = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF2B44)
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Auto-scratch animated canvas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Fetch Spotify Canvas & Apple Motion loops",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.autoSearchAnimatedCanvas,
                                onCheckedChange = { onStyleChange(styleConfig.copy(autoSearchAnimatedCanvas = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }

                        // Access & Spotify quick links
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    MusicNotificationListenerService.openNotificationAccessSettings(context)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isAccessGranted) Color(0x331DB954) else Color(0x44FF2B44),
                                border = BorderStroke(
                                    1.dp,
                                    if (isAccessGranted) Color(0x661DB954) else Color(0x88FF2B44)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isAccessGranted) Color(0xFF1DB954) else Color(0xFFFF2B44))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAccessGranted) "Media Access On" else "Enable Access",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            val spotifyConnected = styleConfig.spotifyAccessToken.isNotBlank() ||
                                styleConfig.spotifySpDcCookie.isNotBlank()
                            Surface(
                                onClick = {
                                    context.startActivity(SpotifyLoginActivity.createIntent(context))
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (spotifyConnected) Color(0x331DB954) else Color.White.copy(alpha = 0.09f),
                                border = BorderStroke(
                                    1.dp,
                                    if (spotifyConnected) Color(0x661DB954) else SettingsGlassBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (spotifyConnected) Color(0xFF1DB954) else Color.White.copy(alpha = 0.45f))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (spotifyConnected) "Spotify Linked" else "Link Spotify",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
