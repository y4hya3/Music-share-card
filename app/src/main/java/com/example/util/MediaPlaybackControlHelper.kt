package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import kotlin.math.roundToInt

data class AudioOutputRouteItem(
    val id: Int,
    val label: String,
    val detail: String,
    val type: Int,
    val isWireless: Boolean
)

/**
 * Helpers for controlling device STREAM_MUSIC volume, listening to hardware volume changes,
 * formatting media timestamps, and inspecting/opening Android Audio Output routes.
 */
object MediaPlaybackControlHelper {

    private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val ACTION_LAUNCH_MEDIA_OUTPUT = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"

    fun formatDurationMs(ms: Long): String {
        val safeSec = (ms.coerceAtLeast(0L) / 1000L)
        val hours = safeSec / 3600L
        val mins = (safeSec % 3600L) / 60L
        val secs = safeSec % 60L
        return if (hours > 0L) {
            "$hours:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
        } else {
            "$mins:${secs.toString().padStart(2, '0')}"
        }
    }

    fun formatRemainingMs(positionMs: Long, durationMs: Long): String {
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        return "-${formatDurationMs(remaining)}"
    }

    fun readMusicVolumeFraction(context: Context): Float {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0.72f
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
            val span = (maxVol - minVol).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            ((cur - minVol).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        } catch (_: Exception) {
            0.72f
        }
    }

    fun setMusicVolumeFraction(context: Context, fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return clamped
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
            val span = (maxVol - minVol).coerceAtLeast(1)
            val targetLevel = (minVol + (clamped * span).roundToInt()).coerceIn(minVol, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetLevel, 0)
            readMusicVolumeFraction(context)
        } catch (_: Exception) {
            clamped
        }
    }

    fun registerMusicVolumeObserver(
        context: Context,
        onVolumeChanged: (Float) -> Unit
    ): () -> Unit {
        val appCtx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val streamType = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                if (streamType == null || streamType == AudioManager.STREAM_MUSIC) {
                    onVolumeChanged(readMusicVolumeFraction(appCtx))
                }
            }
        }
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
        }
        return {
            try {
                appCtx.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    fun getAvailableAudioOutputs(context: Context): List<AudioOutputRouteItem> {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.mapNotNull { device ->
                val type = device.type
                val productName = device.productName?.toString()?.trim().orEmpty()
                val labelAndCategory = when (type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                        (productName.ifBlank { "Bluetooth Audio" }) to ("Wireless Media" to true)
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST ->
                        (productName.ifBlank { "LE Audio Device" }) to ("Bluetooth LE Audio" to true)
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                        (productName.ifBlank { "Bluetooth Headset" }) to ("Bluetooth Voice/Audio" to true)
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                        (productName.ifBlank { "Wired Headphones" }) to ("3.5mm Audio Jack" to false)
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE ->
                        (productName.ifBlank { "USB Audio Device" }) to ("USB-C Audio" to false)
                    AudioDeviceInfo.TYPE_HEARING_AID ->
                        (productName.ifBlank { "Hearing Aid" }) to ("Assistive Audio" to true)
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                        "Phone Speaker" to ("Built-in Speaker" to false)
                    else -> null
                } ?: return@mapNotNull null

                AudioOutputRouteItem(
                    id = device.id,
                    label = labelAndCategory.first,
                    detail = labelAndCategory.second.first,
                    type = type,
                    isWireless = labelAndCategory.second.second
                )
            }.distinctBy { "${it.type}:${it.label}" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun tryLaunchSystemMediaOutputDialog(context: Context, packageName: String): Boolean {
        val pkg = packageName.trim().ifBlank { context.packageName }
        val systemIntent = Intent(ACTION_LAUNCH_MEDIA_OUTPUT).apply {
            setPackage("com.android.systemui")
            putExtra("package_name", pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startBroadcastOrActivitySafe(systemIntent)
        } catch (_: Exception) {
            false
        }
    }

    fun openBluetoothSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openSystemSoundOrVolumePanel(context: Context): Boolean {
        val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent("android.settings.panel.action.VOLUME")
        } else {
            Intent(Settings.ACTION_SOUND_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(panelIntent)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun Context.startBroadcastOrActivitySafe(intent: Intent): Boolean {
        return try {
            val resolved = packageManager.resolveActivity(intent, 0)
            if (resolved != null) {
                startActivity(intent)
                true
            } else {
                sendBroadcast(intent)
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
