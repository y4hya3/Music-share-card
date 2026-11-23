package com.example.ui.components

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.model.CardPalette
import com.example.model.CardShadowColorMode
import com.example.model.CardStyleConfig
import com.example.model.LiveWallpaperArtworkFillMode
import com.example.model.LiveWallpaperCornerStyle
import com.example.model.LiveWallpaperMode
import com.example.model.LiveWallpaperTransitionStyle
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.repository.SavedMediaLibrary
import com.example.service.EchoSnapLiveWallpaperService
import com.example.service.MusicNotificationListenerService
import com.example.ui.SpotifyLoginActivity
import com.example.util.AudioBeatMonitor
import com.example.util.BatteryAndSystemHelper
import com.example.util.BitmapBlurUtils
import com.example.util.ExportNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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
    onDismiss: () -> Unit,
    animateWaveformPreview: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val hasMicPerm = remember(styleConfig.waveformBeatSync) {
        AudioBeatMonitor.hasRecordAudioPermission(context)
    }
    var isSystemWallpaperActive by remember {
        mutableStateOf(EchoSnapLiveWallpaperService.isLiveWallpaperActive(context))
    }
    var batteryIgnoring by remember {
        mutableStateOf(BatteryAndSystemHelper.isIgnoringBatteryOptimizations(context))
    }
    var listenerBound by remember {
        mutableStateOf(MusicNotificationListenerService.hasActiveListenerBinding())
    }
    var hasExportNotifPerm by remember {
        mutableStateOf(ExportNotificationHelper.hasNotificationPermission(context))
    }
    val detectedMediaApps by MediaNotificationRepository.detectedMediaApps.collectAsStateWithLifecycle()

    val exportNotifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasExportNotifPerm = granted || ExportNotificationHelper.hasNotificationPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isSystemWallpaperActive = EchoSnapLiveWallpaperService.isLiveWallpaperActive(context)
                batteryIgnoring = BatteryAndSystemHelper.isIgnoringBatteryOptimizations(context)
                listenerBound = MusicNotificationListenerService.hasActiveListenerBinding()
                hasExportNotifPerm = ExportNotificationHelper.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var localSpeed by remember(styleConfig.waveformSpeedMultiplier) {
        mutableFloatStateOf(styleConfig.waveformSpeedMultiplier.coerceIn(0.4f, 2.5f))
    }
    var localForce by remember(styleConfig.waveformForce) {
        mutableFloatStateOf(styleConfig.waveformForce.coerceIn(0.5f, 2.2f))
    }
    var localWallpaperBlur by remember(styleConfig.liveWallpaperBlurRadius) {
        mutableFloatStateOf(styleConfig.liveWallpaperBlurRadius.coerceIn(0f, 40f))
    }
    var localEdgeBlurFade by remember(styleConfig.liveWallpaperEdgeBlurFade) {
        mutableFloatStateOf(styleConfig.liveWallpaperEdgeBlurFade.coerceIn(0f, 1f))
    }
    var localShadowDepth by remember(styleConfig.glowIntensity) {
        mutableFloatStateOf(styleConfig.glowIntensity.coerceIn(0f, 1f))
    }
    var localDimAmount by remember(styleConfig.liveWallpaperDimAmount) {
        mutableFloatStateOf(styleConfig.liveWallpaperDimAmount.coerceIn(0f, 0.70f))
    }
    var transitionPreviewTick by remember { mutableIntStateOf(0) }
    var idleDelaySecondsInput by remember(styleConfig.liveWallpaperIdleDelaySec) {
        mutableStateOf(styleConfig.liveWallpaperIdleDelaySec.coerceIn(1, 86_400).toString())
    }
    val idlePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri ->
        if (pickedUri != null) {
            coroutineScope.launch {
                val savedPath = SavedMediaLibrary.saveWallpaperIdlePhotoFromUri(context, pickedUri)
                if (savedPath != null) {
                    val latest = MediaNotificationRepository.styleConfig.value
                    onStyleChange(
                        latest.copy(
                            liveWallpaperIdlePhotoPath = savedPath,
                            liveWallpaperIdleRevertEnabled = true
                        )
                    )
                    MediaNotificationRepository.flushStylePreferences()
                } else {
                    Toast.makeText(context, "Could not load selected photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                                        animateAlways = animateWaveformPreview,
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

                // 2. LIVE WALLPAPER (CONTROLLED HERE IN SETTINGS GEAR)
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
                                    text = "Live Wallpaper",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when {
                                        !styleConfig.liveWallpaperEnabled ->
                                            "Off — turn on for dynamic music home & lock screen"
                                        isSystemWallpaperActive ->
                                            "Active on Home / Lock screen • Auto-matches albums & songs"
                                        else ->
                                            "Enabled — tap Apply below to set as system wallpaper"
                                    },
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.liveWallpaperEnabled,
                                onCheckedChange = { enabled ->
                                    onStyleChange(styleConfig.copy(liveWallpaperEnabled = enabled))
                                    MediaNotificationRepository.flushStylePreferences()
                                    if (enabled) {
                                        MediaNotificationRepository.triggerWallpaperMediaSync(
                                            context = context,
                                            forceCanvasRetry = true
                                        )
                                        if (!EchoSnapLiveWallpaperService.isLiveWallpaperActive(context)) {
                                            EchoSnapLiveWallpaperService.openLiveWallpaperSetter(context)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                ),
                                modifier = Modifier.testTag("settings_live_wallpaper_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            onClick = {
                                if (!styleConfig.liveWallpaperEnabled) {
                                    onStyleChange(styleConfig.copy(liveWallpaperEnabled = true))
                                    MediaNotificationRepository.flushStylePreferences()
                                    MediaNotificationRepository.triggerWallpaperMediaSync(
                                        context = context,
                                        forceCanvasRetry = true
                                    )
                                }
                                EchoSnapLiveWallpaperService.openLiveWallpaperSetter(context)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSystemWallpaperActive) Color(0x331DB954) else Color(0x33FF2B44),
                            border = BorderStroke(
                                1.dp,
                                if (isSystemWallpaperActive) Color(0x661DB954) else Color(0x77FF2B44)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_live_wallpaper_apply_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSystemWallpaperActive) Color(0xFF1DB954) else Color(0xFFFF2B44)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isSystemWallpaperActive) {
                                            "EchoSnap Wallpaper Active"
                                        } else {
                                            "Set as System Live Wallpaper"
                                        },
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = if (isSystemWallpaperActive) "Preview" else "Apply",
                                    color = if (isSystemWallpaperActive) Color(0xFF9BE7B3) else Color(0xFFFF8A96),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Wallpaper Source Mode",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isCanvasMode = styleConfig.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(liveWallpaperMode = LiveWallpaperMode.ANIMATED_CANVAS)
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                    MediaNotificationRepository.triggerWallpaperMediaSync(
                                        context = context,
                                        forceCanvasRetry = true
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCanvasMode) Color(0xFF1DB954) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isCanvasMode) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_mode_canvas")
                            ) {
                                Text(
                                    text = "Animated Canvas",
                                    color = if (isCanvasMode) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isCanvasMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(liveWallpaperMode = LiveWallpaperMode.ARTWORK_ONLY)
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!isCanvasMode) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (!isCanvasMode) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_mode_artwork")
                            ) {
                                Text(
                                    text = "Artwork Only",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isCanvasMode) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                        }

                        Text(
                            text = if (styleConfig.liveWallpaperMode == LiveWallpaperMode.ANIMATED_CANVAS) {
                                "Shows high-res artwork cleanly until an animated canvas matches (online or saved)."
                            } else {
                                "Always renders high-res saved album cover or notification artwork with 0% video overhead."
                            },
                            color = Color.White.copy(alpha = 0.56f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        AnimatedVisibility(visible = styleConfig.liveWallpaperMode == LiveWallpaperMode.ARTWORK_ONLY) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .testTag("settings_lw_transition_section")
                            ) {
                                Text(
                                    text = "Next-Song Transition",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Animates wallpaper artwork change when the next song plays",
                                    color = Color.White.copy(alpha = 0.56f),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 1.dp, bottom = 7.dp)
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    LiveWallpaperTransitionStyle.entries.forEach { transitionStyle ->
                                        val selected = styleConfig.liveWallpaperArtTransition == transitionStyle
                                        Surface(
                                            onClick = {
                                                onStyleChange(styleConfig.copy(liveWallpaperArtTransition = transitionStyle))
                                                MediaNotificationRepository.flushStylePreferences()
                                                transitionPreviewTick++
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (selected) Color.Transparent else SettingsGlassBorder
                                            ),
                                            modifier = Modifier.testTag("settings_lw_transition_${transitionStyle.name}")
                                        ) {
                                            Text(
                                                text = transitionStyle.label,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                            )
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = styleConfig.liveWallpaperArtTransition != LiveWallpaperTransitionStyle.NONE) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Speed",
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        listOf(
                                            220 to "Fast",
                                            360 to "Smooth",
                                            540 to "Slow"
                                        ).forEach { (ms, speedLabel) ->
                                            val speedSelected = kotlin.math.abs(styleConfig.liveWallpaperArtTransitionDurationMs - ms) < 60
                                            Surface(
                                                onClick = {
                                                    onStyleChange(styleConfig.copy(liveWallpaperArtTransitionDurationMs = ms))
                                                    MediaNotificationRepository.flushStylePreferences()
                                                    transitionPreviewTick++
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (speedSelected) Color(0xFF1DB954) else Color.White.copy(alpha = 0.08f),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (speedSelected) Color.Transparent else SettingsGlassBorder
                                                ),
                                                modifier = Modifier.testTag("settings_lw_transition_speed_$ms")
                                            ) {
                                                Text(
                                                    text = speedLabel,
                                                    color = if (speedSelected) Color.Black else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = if (speedSelected) FontWeight.Bold else FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Artwork Display Style",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val isFloatingSquare = styleConfig.liveWallpaperArtworkFillMode == LiveWallpaperArtworkFillMode.FLOATING_SQUARE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(
                                            liveWallpaperArtworkFillMode = LiveWallpaperArtworkFillMode.FLOATING_SQUARE
                                        )
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isFloatingSquare) Color(0xFF1DB954) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isFloatingSquare) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_fill_floating")
                            ) {
                                Text(
                                    text = "Floating Square",
                                    color = if (isFloatingSquare) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isFloatingSquare) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(
                                            liveWallpaperArtworkFillMode = LiveWallpaperArtworkFillMode.FULL_SCREEN
                                        )
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!isFloatingSquare) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (!isFloatingSquare) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_fill_fullscreen")
                            ) {
                                Text(
                                    text = "Full Screen",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isFloatingSquare) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = if (isFloatingSquare) {
                                "Square Edges"
                            } else {
                                "Square Edges (applies in Floating Square)"
                            },
                            color = Color.White.copy(alpha = if (isFloatingSquare) 0.85f else 0.55f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val isCurvedCorners = styleConfig.liveWallpaperCornerStyle == LiveWallpaperCornerStyle.CURVED
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = if (isFloatingSquare) 1f else 0.58f },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(liveWallpaperCornerStyle = LiveWallpaperCornerStyle.CURVED)
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurvedCorners) Color(0xFF1DB954) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isCurvedCorners) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_corners_curved")
                            ) {
                                Text(
                                    text = "Curved Edges",
                                    color = if (isCurvedCorners) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isCurvedCorners) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    onStyleChange(
                                        styleConfig.copy(liveWallpaperCornerStyle = LiveWallpaperCornerStyle.SHARP)
                                    )
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!isCurvedCorners) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (!isCurvedCorners) Color.Transparent else SettingsGlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_corners_sharp")
                            ) {
                                Text(
                                    text = "Sharp Edges",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isCurvedCorners) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                        }

                        if (isFloatingSquare) {
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Square Shadow Depth",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (localShadowDepth <= 0.01f) "Off" else "${(localShadowDepth * 100f).roundToInt()}%",
                                    color = Color(0xFFFF8A96),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("settings_lw_shadow_depth_value")
                                )
                            }
                            Slider(
                                value = localShadowDepth,
                                onValueChange = { next ->
                                    val clamped = next.coerceIn(0f, 1f)
                                    localShadowDepth = clamped
                                    onStyleChange(styleConfig.copy(glowIntensity = clamped))
                                },
                                onValueChangeFinished = {
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF2B44),
                                    activeTrackColor = Color(0xFFFF2B44),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                ),
                                modifier = Modifier.testTag("settings_lw_shadow_depth_slider")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Inverse Shadow",
                                        color = Color.White.copy(alpha = 0.90f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Upward glow + recessed inner border depth",
                                        color = Color.White.copy(alpha = 0.52f),
                                        fontSize = 10.sp
                                    )
                                }
                                Switch(
                                    checked = styleConfig.cardShadowInverse,
                                    onCheckedChange = { checked ->
                                        onStyleChange(styleConfig.copy(cardShadowInverse = checked))
                                        MediaNotificationRepository.flushStylePreferences()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFFF2B44),
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f)
                                    ),
                                    modifier = Modifier.testTag("settings_lw_shadow_inverse_toggle")
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Square Shadow Color",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CardShadowColorMode.entries.forEach { mode ->
                                    val selected = styleConfig.cardShadowColorMode == mode
                                    val swatchColor = when (mode) {
                                        CardShadowColorMode.ALBUM_DYNAMIC -> palette.vibrantColor
                                        CardShadowColorMode.BLACK -> Color.Black
                                        CardShadowColorMode.RED -> Color(0xFFFF2B44)
                                        CardShadowColorMode.WHITE -> Color.White
                                        CardShadowColorMode.PURPLE -> Color(0xFF9D4EDD)
                                        CardShadowColorMode.CYAN -> Color(0xFF00D2FF)
                                        CardShadowColorMode.CUSTOM -> styleConfig.customShadowColor
                                    }
                                    Surface(
                                        onClick = {
                                            onStyleChange(styleConfig.copy(cardShadowColorMode = mode))
                                            MediaNotificationRepository.flushStylePreferences()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) Color.Transparent else SettingsGlassBorder
                                        ),
                                        modifier = Modifier.testTag("settings_lw_shadow_color_${mode.name}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(swatchColor)
                                                    .border(0.8.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                            )
                                            Text(
                                                text = mode.label,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (styleConfig.liveWallpaperShowCard) {
                                "Card Overlay is ON — turn off 'Show Music Card on Wallpaper' below to show Floating Square / Full Screen alone."
                            } else {
                                "Applies in Artwork Only mode and whenever an Animated Canvas is not active."
                            },
                            color = if (styleConfig.liveWallpaperShowCard) {
                                Color(0xFFFFD166)
                            } else {
                                Color.White.copy(alpha = 0.52f)
                            },
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LiveWallpaperMiniPreview(
                            track = currentTrack,
                            palette = palette,
                            centerBlurPx = localWallpaperBlur,
                            edgeBlurFade = localEdgeBlurFade,
                            showCard = styleConfig.liveWallpaperShowCard,
                            artworkFillMode = styleConfig.liveWallpaperArtworkFillMode,
                            cornerStyle = styleConfig.liveWallpaperCornerStyle,
                            shadowDepth = localShadowDepth,
                            shadowColor = styleConfig.resolveShadowColor(palette),
                            shadowInverse = styleConfig.cardShadowInverse,
                            dimAmount = if (styleConfig.liveWallpaperDimEnabled) localDimAmount else 0f,
                            artTransitionStyle = if (styleConfig.liveWallpaperMode == LiveWallpaperMode.ARTWORK_ONLY) {
                                styleConfig.liveWallpaperArtTransition
                            } else {
                                LiveWallpaperTransitionStyle.CROSSFADE
                            },
                            artTransitionDurationMs = styleConfig.liveWallpaperArtTransitionDurationMs,
                            transitionPreviewTick = transitionPreviewTick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_live_wallpaper_mini_preview")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Wallpaper Blur",
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${localWallpaperBlur.roundToInt()}px",
                                color = Color(0xFFFF8A96),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("settings_live_wallpaper_blur_value")
                            )
                        }
                        Slider(
                            value = localWallpaperBlur,
                            onValueChange = { next ->
                                val clamped = next.coerceIn(0f, 40f)
                                localWallpaperBlur = clamped
                                onStyleChange(styleConfig.copy(liveWallpaperBlurRadius = clamped))
                            },
                            onValueChangeFinished = {
                                MediaNotificationRepository.flushStylePreferences()
                            },
                            valueRange = 0f..40f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFFFF2B44),
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier.testTag("settings_live_wallpaper_blur_slider")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Top & Bottom Edge Blur",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Frosts top clock & bottom dock while keeping middle clear",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = "${(localEdgeBlurFade * 100f).roundToInt()}%",
                                color = Color(0xFF9BE7B3),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("settings_live_wallpaper_edge_value")
                            )
                        }
                        Slider(
                            value = localEdgeBlurFade,
                            onValueChange = { next ->
                                val clamped = next.coerceIn(0f, 1f)
                                localEdgeBlurFade = clamped
                                onStyleChange(styleConfig.copy(liveWallpaperEdgeBlurFade = clamped))
                            },
                            onValueChangeFinished = {
                                MediaNotificationRepository.flushStylePreferences()
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF1DB954),
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier.testTag("settings_live_wallpaper_edge_slider")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Wallpaper Brightness Dimmer",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (styleConfig.liveWallpaperDimEnabled) {
                                        "Dims wallpaper by ${(localDimAmount * 100f).roundToInt()}% so app icons & clock stay legible"
                                    } else {
                                        "Off — full artwork brightness on home & lock screen"
                                    },
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.liveWallpaperDimEnabled,
                                onCheckedChange = { enabled ->
                                    onStyleChange(styleConfig.copy(liveWallpaperDimEnabled = enabled))
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                ),
                                modifier = Modifier.testTag("settings_lw_dim_switch")
                            )
                        }

                        AnimatedVisibility(visible = styleConfig.liveWallpaperDimEnabled) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Dim Strength",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${(localDimAmount * 100f).roundToInt()}%",
                                        color = Color(0xFF9BE7B3),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.testTag("settings_lw_dim_value")
                                    )
                                }
                                Slider(
                                    value = localDimAmount,
                                    onValueChange = { next ->
                                        val clamped = next.coerceIn(0f, 0.70f)
                                        localDimAmount = clamped
                                        onStyleChange(styleConfig.copy(liveWallpaperDimAmount = clamped))
                                    },
                                    onValueChangeFinished = {
                                        MediaNotificationRepository.flushStylePreferences()
                                    },
                                    valueRange = 0f..0.70f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color(0xFF1DB954),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                    ),
                                    modifier = Modifier.testTag("settings_lw_dim_slider")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Battery Saver Auto-Pause Canvas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Switches video canvas to crisp HD artwork in Battery Saver or <=15%",
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.liveWallpaperPowerSavePauseCanvas,
                                onCheckedChange = { pauseOnPowerSave ->
                                    onStyleChange(styleConfig.copy(liveWallpaperPowerSavePauseCanvas = pauseOnPowerSave))
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                ),
                                modifier = Modifier.testTag("settings_lw_power_save_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Show Music Card on Wallpaper",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Display your EchoSnap music card centered on wallpaper",
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.liveWallpaperShowCard,
                                onCheckedChange = { showCard ->
                                    onStyleChange(styleConfig.copy(liveWallpaperShowCard = showCard))
                                    MediaNotificationRepository.flushStylePreferences()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF2B44)
                                ),
                                modifier = Modifier.testTag("settings_live_wallpaper_show_card_switch")
                            )
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.10f),
                            modifier = Modifier.padding(vertical = 12.dp)
                        )

                        // When Song Stops -> Switch to Library Photo (or disable to keep last Canvas/Artwork)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Standby Photo When Song Stops",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (!styleConfig.liveWallpaperIdleRevertEnabled) {
                                        "Disabled — stays on last Animated Canvas or Artwork"
                                    } else if (styleConfig.liveWallpaperIdlePhotoPath.isBlank()) {
                                        "Pick a photo from your library below"
                                    } else {
                                        "Switches to your photo ${styleConfig.liveWallpaperIdleDelaySec}s after music stops"
                                    },
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = styleConfig.liveWallpaperIdleRevertEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && styleConfig.liveWallpaperIdlePhotoPath.isBlank()) {
                                        idlePhotoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    } else {
                                        onStyleChange(styleConfig.copy(liveWallpaperIdleRevertEnabled = enabled))
                                        MediaNotificationRepository.flushStylePreferences()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                ),
                                modifier = Modifier.testTag("settings_live_wallpaper_idle_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val hasIdlePhoto = styleConfig.liveWallpaperIdlePhotoPath.isNotBlank() &&
                            File(styleConfig.liveWallpaperIdlePhotoPath).exists()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (hasIdlePhoto) {
                                AsyncImage(
                                    model = File(styleConfig.liveWallpaperIdlePhotoPath),
                                    contentDescription = "Standby wallpaper photo preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, SettingsGlassBorder, RoundedCornerShape(10.dp))
                                )
                            }
                            Surface(
                                onClick = {
                                    idlePhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.11f),
                                border = BorderStroke(1.dp, SettingsGlassBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_idle_pick_photo")
                            ) {
                                Text(
                                    text = if (hasIdlePhoto) "Change Library Photo" else "Add Photo from Library",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
                                )
                            }
                            if (hasIdlePhoto) {
                                Surface(
                                    onClick = {
                                        SavedMediaLibrary.deleteWallpaperIdlePhoto(
                                            context,
                                            styleConfig.liveWallpaperIdlePhotoPath
                                        )
                                        onStyleChange(
                                            styleConfig.copy(
                                                liveWallpaperIdlePhotoPath = "",
                                                liveWallpaperIdleRevertEnabled = false
                                            )
                                        )
                                        MediaNotificationRepository.flushStylePreferences()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0x33FF2B44),
                                    border = BorderStroke(1.dp, Color(0x66FF2B44)),
                                    modifier = Modifier.testTag("settings_live_wallpaper_idle_remove_photo")
                                ) {
                                    Text(
                                        text = "Remove",
                                        color = Color(0xFFFF8A96),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Switch Delay (seconds, max 86400s)",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5, 60).forEach { sec ->
                                val selected = styleConfig.liveWallpaperIdleDelaySec == sec
                                Surface(
                                    onClick = {
                                        idleDelaySecondsInput = sec.toString()
                                        onStyleChange(styleConfig.copy(liveWallpaperIdleDelaySec = sec))
                                        MediaNotificationRepository.flushStylePreferences()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) Color(0xFF1DB954) else Color.White.copy(alpha = 0.09f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) Color.Transparent else SettingsGlassBorder
                                    ),
                                    modifier = Modifier.testTag("settings_live_wallpaper_idle_${sec}s")
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        color = if (selected) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = idleDelaySecondsInput,
                                onValueChange = { raw ->
                                    val digitsOnly = raw.filter { it.isDigit() }.take(5)
                                    idleDelaySecondsInput = digitsOnly
                                    val parsed = digitsOnly.toIntOrNull()
                                    if (parsed != null) {
                                        val clamped = parsed.coerceIn(1, 86_400)
                                        if (parsed > 86_400) {
                                            idleDelaySecondsInput = clamped.toString()
                                        }
                                        onStyleChange(styleConfig.copy(liveWallpaperIdleDelaySec = clamped))
                                    }
                                },
                                singleLine = true,
                                placeholder = {
                                    Text("Sec (1-86400)", color = Color.White.copy(alpha = 0.42f), fontSize = 12.sp)
                                },
                                suffix = {
                                    Text("sec", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF1DB954),
                                    unfocusedBorderColor = SettingsGlassBorder,
                                    cursorColor = Color(0xFF1DB954)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_wallpaper_idle_seconds_input")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. LYRICS PICKER MODE
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

                Spacer(modifier = Modifier.height(12.dp))

                // 4. BACKGROUND, BATTERY HEALTH & MEDIA APP FILTER
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SettingsGlassFill,
                    border = BorderStroke(1.dp, SettingsGlassBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_battery_health_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "Background & Battery Health",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Keep music sync reliable when Android sleeps in the background",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 11.sp
                            )
                        }

                        // Status pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isAccessGranted && listenerBound) Color(0x221DB954) else Color(0x28FF2B44),
                                border = BorderStroke(
                                    1.dp,
                                    if (isAccessGranted && listenerBound) Color(0x551DB954) else Color(0x66FF2B44)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isAccessGranted && listenerBound -> Color(0xFF1DB954)
                                                    isAccessGranted -> Color(0xFFFFD166)
                                                    else -> Color(0xFFFF2B44)
                                                }
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isAccessGranted && listenerBound -> "Listener: Bound"
                                            isAccessGranted -> "Listener: Stale"
                                            else -> "Listener: Off"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("settings_listener_status_text")
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (batteryIgnoring) Color(0x221DB954) else Color(0x22FFD166),
                                border = BorderStroke(
                                    1.dp,
                                    if (batteryIgnoring) Color(0x551DB954) else Color(0x55FFD166)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (batteryIgnoring) Color(0xFF1DB954) else Color(0xFFFFD166))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (batteryIgnoring) "Battery: Unrestricted" else "Battery: Optimized",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("settings_battery_status_text")
                                    )
                                }
                            }
                        }

                        // Action buttons row
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    if (!isAccessGranted) {
                                        MusicNotificationListenerService.openNotificationAccessSettings(context)
                                    } else {
                                        MusicNotificationListenerService.rebindIfPossible(context)
                                        MediaNotificationRepository.refreshFromActiveMedia(context, force = true)
                                        listenerBound = MusicNotificationListenerService.hasActiveListenerBinding()
                                        Toast.makeText(
                                            context,
                                            if (listenerBound) "Listener rebound & synced" else "Rebind requested — check active player",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.11f),
                                border = BorderStroke(1.dp, SettingsGlassBorder),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_rebind_listener_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Rebind media listener",
                                        tint = Color(0xFF9BE7B3),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Rebind Listener",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    if (!BatteryAndSystemHelper.openBatteryOptimizationSettings(context)) {
                                        Toast.makeText(
                                            context,
                                            "Open App Info > Battery > Unrestricted",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.11f),
                                border = BorderStroke(1.dp, SettingsGlassBorder),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_battery_settings_button")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Battery Settings",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    if (!BatteryAndSystemHelper.openOemBackgroundSettings(context)) {
                                        Toast.makeText(
                                            context,
                                            "Check device App Battery / Autostart settings",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.11f),
                                border = BorderStroke(1.dp, SettingsGlassBorder),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_oem_background_button")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "OEM Autostart",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (hasExportNotifPerm) {
                                        "Export Progress Notifications: On"
                                    } else {
                                        "Allow export progress alerts"
                                    },
                                    color = Color.White.copy(alpha = 0.80f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                if (!hasExportNotifPerm) {
                                    Surface(
                                        onClick = {
                                            exportNotifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0x331DB954),
                                        border = BorderStroke(1.dp, Color(0x661DB954)),
                                        modifier = Modifier.testTag("settings_export_notif_button")
                                    ) {
                                        Text(
                                            text = "Enable",
                                            color = Color(0xFF9BE7B3),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                        Column {
                            Text(
                                text = "Ignore Video & Social Apps",
                                color = Color.White.copy(alpha = 0.90f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Prevents videos or voice notes from replacing your music artwork",
                                color = Color.White.copy(alpha = 0.56f),
                                fontSize = 10.sp
                            )
                        }

                        val filterCandidates = remember(detectedMediaApps) {
                            linkedMapOf(
                                "com.google.android.youtube" to "YouTube",
                                "com.zhiliaoapp.musically" to "TikTok",
                                "com.instagram.android" to "Instagram",
                                "com.whatsapp" to "WhatsApp",
                                "com.android.chrome" to "Chrome"
                            ).apply {
                                detectedMediaApps.forEach { (pkg, label) ->
                                    if (!containsKey(pkg)) {
                                        put(pkg, label)
                                    }
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            filterCandidates.forEach { (pkg, label) ->
                                val isIgnored = pkg in styleConfig.ignoredMediaPackages
                                Surface(
                                    onClick = {
                                        MediaNotificationRepository.toggleIgnoredMediaPackage(pkg)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isIgnored) Color(0x44FF2B44) else Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isIgnored) Color(0xAAFF2B44) else SettingsGlassBorder
                                    ),
                                    modifier = Modifier.testTag("settings_ignore_pkg_$pkg")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        if (isIgnored) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = null,
                                                tint = Color(0xFFFF8A96),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = label,
                                            color = if (isIgnored) Color(0xFFFFB3BC) else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (isIgnored) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. ECHOSNAP FOLDER BACKUP (EXPORT / IMPORT / RESET)
                EchoSnapBackupSectionCard(styleConfig = styleConfig)
            }
        }
    }
}

@Composable
private fun LiveWallpaperMiniPreview(
    track: MediaTrackInfo,
    palette: CardPalette,
    centerBlurPx: Float,
    edgeBlurFade: Float,
    showCard: Boolean,
    artworkFillMode: LiveWallpaperArtworkFillMode = LiveWallpaperArtworkFillMode.FLOATING_SQUARE,
    cornerStyle: LiveWallpaperCornerStyle = LiveWallpaperCornerStyle.CURVED,
    shadowDepth: Float = 0.75f,
    shadowColor: Color = Color.Black,
    shadowInverse: Boolean = false,
    dimAmount: Float = 0f,
    artTransitionStyle: LiveWallpaperTransitionStyle = LiveWallpaperTransitionStyle.CROSSFADE,
    artTransitionDurationMs: Int = 360,
    transitionPreviewTick: Int = 0,
    modifier: Modifier = Modifier
) {
    val art = track.albumArt
    val centerNorm = (centerBlurPx / 40f).coerceIn(0f, 1f)
    val edgeFade = edgeBlurFade.coerceIn(0f, 1f)
    val transitionAnim = remember { Animatable(1f) }
    LaunchedEffect(transitionPreviewTick) {
        if (transitionPreviewTick > 0) {
            if (artTransitionStyle == LiveWallpaperTransitionStyle.NONE) {
                transitionAnim.snapTo(1f)
            } else {
                transitionAnim.snapTo(0f)
                transitionAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = artTransitionDurationMs.coerceIn(180, 700),
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }
    val animT = transitionAnim.value

    // Precompute compact preview mipmaps once per track artwork so scrubbing BOTH sliders runs at 60fps
    val mipImages by produceState<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(
        initialValue = null,
        key1 = art
    ) {
        value = if (art != null && !art.isRecycled) {
            withContext(Dispatchers.Default) {
                BitmapBlurUtils.computeBlurMipPyramid(
                    source = art,
                    lowDim = 128,
                    midDim = 76,
                    highDim = 42
                )?.let { (low, mid, high) ->
                    Triple(low.asImageBitmap(), mid.asImageBitmap(), high.asImageBitmap())
                }
            }
        } else {
            null
        }
    }
    val sharpPreviewBmp = remember(art) {
        art?.takeIf { !it.isRecycled }
            ?.let { BitmapBlurUtils.toSafeSoftwareBitmap(it, maxDimension = 180)?.asImageBitmap() }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Miniature 9:16 Phone Wallpaper Frame
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.vibrantColor.copy(alpha = 0.85f), Color(0xFF0D0F18))
                        )
                    )
            ) {
                // 1. Center Wallpaper Layer (uniform center blur 0..40px + Artwork transition demo)
                val previewExtraBlur = if (artTransitionStyle == LiveWallpaperTransitionStyle.BLUR_DISSOLVE && animT < 0.99f) {
                    (1f - animT) * 14f
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (animT < 0.999f) {
                                when (artTransitionStyle) {
                                    LiveWallpaperTransitionStyle.CROSSFADE -> alpha = animT
                                    LiveWallpaperTransitionStyle.SLIDE_LEFT -> translationX = (1f - animT) * size.width
                                    LiveWallpaperTransitionStyle.ZOOM_FADE -> {
                                        val s = 1f + (1f - animT) * 0.16f
                                        scaleX = s
                                        scaleY = s
                                        alpha = animT
                                    }
                                    LiveWallpaperTransitionStyle.BLUR_DISSOLVE -> alpha = animT
                                    LiveWallpaperTransitionStyle.NONE -> Unit
                                }
                            }
                        }
                ) {
                    if (sharpPreviewBmp != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Image(
                                bitmap = sharpPreviewBmp,
                                contentDescription = "Wallpaper center blur preview",
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Medium,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (centerBlurPx + previewExtraBlur > 0.4f) {
                                            Modifier.blur(
                                                radius = ((centerBlurPx + previewExtraBlur) * 0.36f).dp,
                                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                        } else {
                            Image(
                                bitmap = sharpPreviewBmp,
                                contentDescription = "Wallpaper center blur preview",
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Low,
                                modifier = Modifier.fillMaxSize()
                            )
                            val mips = mipImages
                            if (mips != null && centerBlurPx > 0.4f) {
                                val midAlpha = (centerNorm * 1.4f).coerceIn(0f, 1f)
                                val highAlpha = ((centerNorm - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                if (midAlpha > 0.01f) {
                                    Image(
                                        bitmap = mips.first,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        filterQuality = FilterQuality.Low,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = midAlpha }
                                    )
                                }
                                if (highAlpha > 0.01f) {
                                    Image(
                                        bitmap = mips.second,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        filterQuality = FilterQuality.Low,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = highAlpha }
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Top & Bottom Edge Frosted Layer (heavily blurred, masked to top 38% & bottom 38% ONLY)
                val edgeSourceBmp = mipImages?.third ?: sharpPreviewBmp
                if (edgeSourceBmp != null && edgeFade > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.White.copy(alpha = edgeFade),
                                        0.18f to Color.White.copy(alpha = edgeFade * 0.92f),
                                        0.38f to Color.Transparent,
                                        0.62f to Color.Transparent,
                                        0.82f to Color.White.copy(alpha = edgeFade * 0.92f),
                                        1.0f to Color.White.copy(alpha = edgeFade)
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    ) {
                        Image(
                            bitmap = edgeSourceBmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Low,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(
                                            radius = (11f + edgeFade * 9f).dp,
                                            edgeTreatment = BlurredEdgeTreatment.Rectangle
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = edgeFade * 0.22f))
                        )
                    }
                }

                // Wallpaper Brightness Dimmer scrim
                if (dimAmount > 0.005f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = dimAmount.coerceIn(0f, 0.70f)))
                    )
                }

                // 3. Simulated Lock/Home Clock in top band + Dock dots in bottom band
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "12:45",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (showCard) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xAA161822),
                            border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.30f)),
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.vibrantColor.copy(alpha = 0.8f))
                                ) {
                                    if (sharpPreviewBmp != null) {
                                        Image(
                                            bitmap = sharpPreviewBmp,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.9f)
                                            .height(3.5.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.85f))
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.6f)
                                            .height(2.5.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.50f))
                                    )
                                }
                            }
                        }
                    } else if (
                        artworkFillMode == LiveWallpaperArtworkFillMode.FLOATING_SQUARE &&
                        sharpPreviewBmp != null
                    ) {
                        val artCorner = if (cornerStyle == LiveWallpaperCornerStyle.CURVED) 7.dp else 0.dp
                        val depth = shadowDepth.coerceIn(0f, 1f)
                        val isBlackShadow = shadowColor.red < 0.08f && shadowColor.green < 0.08f && shadowColor.blue < 0.08f
                        val outerColor = if (shadowInverse && isBlackShadow) Color.White.copy(alpha = 0.7f) else shadowColor
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .graphicsLayer {
                                    if (animT < 0.999f) {
                                        when (artTransitionStyle) {
                                            LiveWallpaperTransitionStyle.CROSSFADE -> alpha = animT
                                            LiveWallpaperTransitionStyle.SLIDE_LEFT -> {
                                                translationX = (1f - animT) * 28.dp.toPx()
                                                alpha = animT
                                            }
                                            LiveWallpaperTransitionStyle.ZOOM_FADE -> {
                                                val s = 0.88f + animT * 0.12f
                                                scaleX = s
                                                scaleY = s
                                                alpha = animT
                                            }
                                            LiveWallpaperTransitionStyle.BLUR_DISSOLVE -> alpha = animT
                                            LiveWallpaperTransitionStyle.NONE -> Unit
                                        }
                                    }
                                }
                                .drawBehind {
                                    if (depth > 0.01f) {
                                        drawIntoCanvas { canvas ->
                                            val paint = Paint().asFrameworkPaint().apply {
                                                isAntiAlias = true
                                                color = outerColor.copy(alpha = (0.62f * depth).coerceIn(0f, 0.90f)).toArgb()
                                                maskFilter = android.graphics.BlurMaskFilter(
                                                    (5.dp.toPx() * (0.4f + depth * 0.9f)).coerceAtLeast(1f),
                                                    android.graphics.BlurMaskFilter.Blur.NORMAL
                                                )
                                            }
                                            val dy = if (shadowInverse) -2.dp.toPx() * depth else 2.dp.toPx() * depth
                                            val radiusPx = artCorner.toPx()
                                            canvas.nativeCanvas.drawRoundRect(
                                                0f,
                                                dy,
                                                size.width,
                                                size.height + dy,
                                                radiusPx,
                                                radiusPx,
                                                paint
                                            )
                                        }
                                    }
                                }
                                .clip(RoundedCornerShape(artCorner))
                        ) {
                            Image(
                                bitmap = sharpPreviewBmp,
                                contentDescription = "Un-chopped artwork preview",
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Medium,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        drawContent()
                                        if (shadowInverse && depth > 0.01f) {
                                            drawIntoCanvas { canvas ->
                                                val strokePx = (7.dp.toPx() * (0.45f + 0.55f * depth)).coerceAtLeast(1.5f)
                                                val paint = Paint().asFrameworkPaint().apply {
                                                    isAntiAlias = true
                                                    style = android.graphics.Paint.Style.STROKE
                                                    strokeWidth = strokePx
                                                    color = shadowColor.copy(alpha = (0.82f * depth).coerceIn(0f, 0.94f)).toArgb()
                                                    maskFilter = android.graphics.BlurMaskFilter(
                                                        (strokePx * 0.65f).coerceAtLeast(1f),
                                                        android.graphics.BlurMaskFilter.Blur.NORMAL
                                                    )
                                                }
                                                val rPx = artCorner.toPx()
                                                canvas.nativeCanvas.drawRoundRect(
                                                    0f, 0f, size.width, size.height, rPx, rPx, paint
                                                )
                                            }
                                        }
                                    }
                            )
                            if (dimAmount > 0.005f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = dimAmount.coerceIn(0f, 0.70f)))
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(42.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.72f))
                            )
                        }
                    }
                }
            }

            // Helpful Legend beside miniature phone frame
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Live Blur Preview",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Wallpaper Blur (${centerBlurPx.roundToInt()}px) blurs the entire backdrop & middle.",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 10.sp
                )
                Text(
                    text = "• Edge Blur (${(edgeFade * 100f).roundToInt()}%) heavily frosts the top clock & bottom dock while keeping the middle clear.",
                    color = Color(0xFFA6F4C5),
                    fontSize = 10.sp
                )
                if (dimAmount > 0.005f) {
                    Text(
                        text = "• Dimmer (${(dimAmount * 100f).roundToInt()}%) softens brightness behind your icons.",
                        color = Color(0xFFFFD166),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
