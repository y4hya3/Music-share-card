package com.example.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Classifies runtime CPU, RAM, and GPU constraints so EchoSnap delivers fluid 60fps UI
 * on Android 9/10 low-to-mid devices (e.g. Samsung Galaxy A7 2018, Xiaomi Redmi 9C)
 * without compromising visual quality on flagship hardware.
 */
@Immutable
data class DeviceTierProfile(
    val isLowRam: Boolean,
    val isLegacyRenderTier: Boolean,
    val cardArtMaxDim: Int,
    val bgSourceMaxDim: Int,
    val pyramidLowDim: Int,
    val pyramidMidDim: Int,
    val pyramidHighDim: Int,
    val previewMaxWidth: Int,
    val previewMaxHeight: Int,
    val waveformFrameIntervalNs: Long
)

object DevicePerformanceTier {

    @Volatile
    private var cachedProfile: DeviceTierProfile? = null

    fun get(context: Context?): DeviceTierProfile {
        cachedProfile?.let { return it }
        val profile = computeProfile(context)
        cachedProfile = profile
        return profile
    }

    @Composable
    fun rememberDeviceTier(): DeviceTierProfile {
        val context = LocalContext.current
        return remember(context) { get(context) }
    }

    fun recommendedCoverMaxDim(context: Context?): Int = get(context).cardArtMaxDim

    private fun computeProfile(context: Context?): DeviceTierProfile {
        var totalRamBytes = 4L * 1024L * 1024L * 1024L
        var systemLowRam = false

        if (context != null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    systemLowRam = am.isLowRamDevice
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    if (memInfo.totalMem > 0L) {
                        totalRamBytes = memInfo.totalMem
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Redmi 9C ships with 2GB or 3GB RAM (~1.8GB - 2.9GB visible to ActivityManager)
        val isLowRamDevice = systemLowRam || totalRamBytes <= 3_300_000_000L
        // Galaxy A7 (2018) & Redmi 9C run Android 8-10 (below API 31 RenderEffect)
        val isLegacyRender = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isLowRamDevice

        return when {
            isLowRamDevice -> DeviceTierProfile(
                isLowRam = true,
                isLegacyRenderTier = true,
                cardArtMaxDim = 640,
                bgSourceMaxDim = 480,
                pyramidLowDim = 210,
                pyramidMidDim = 155,
                pyramidHighDim = 115,
                previewMaxWidth = 720,
                previewMaxHeight = 1280,
                waveformFrameIntervalNs = 31_000_000L // ~32fps smooth groove, saves 50% Helio G35 / PowerVR load
            )
            isLegacyRender -> DeviceTierProfile(
                isLowRam = false,
                isLegacyRenderTier = true,
                cardArtMaxDim = 840,
                bgSourceMaxDim = 600,
                pyramidLowDim = 280,
                pyramidMidDim = 210,
                pyramidHighDim = 165,
                previewMaxWidth = 900,
                previewMaxHeight = 1600,
                waveformFrameIntervalNs = 16_000_000L // 60fps smooth on Exynos 7885 / Mali-G71
            )
            else -> DeviceTierProfile(
                isLowRam = false,
                isLegacyRenderTier = false,
                cardArtMaxDim = 1024,
                bgSourceMaxDim = 720,
                pyramidLowDim = 340,
                pyramidMidDim = 260,
                pyramidHighDim = 220,
                previewMaxWidth = 1080,
                previewMaxHeight = 1920,
                waveformFrameIntervalNs = 0L // Full native display vsync (60/90/120Hz)
            )
        }
    }
}
