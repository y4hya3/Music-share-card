package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Provides real-time 4-bar equalizer amplitudes for the Floating Glass Card waveform.
 * - When Audio Beat Sync is enabled and RECORD_AUDIO permission is granted, attaches Android's
 *   global output-mix Visualizer(0) to sample live audio FFT bands (bass, low-mid, high-mid, treble).
 * - Otherwise computes a track-fingerprinted harmonic rhythm derived uniquely from the active track's
 *   title & artist so each song animates with its own distinct BPM/groove rather than a generic loop.
 */
object AudioBeatMonitor {

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Deterministic, track-fingerprinted 4-bar waveform heights in [0.18f .. 1.0f].
     * - When [liveAudioBands] is streaming from Visualizer(0), follows real live music beats.
     * - In Default Speed mode ([beatSyncMode] == false), scales wave motion cleanly by [speedMultiplier].
     * - Always animates smoothly when [animateWhenPaused] is true so previews and 8s MP4 exports never freeze.
     */
    fun computeBarLevels(
        trackTitle: String,
        trackArtist: String,
        isPlaying: Boolean,
        timeSec: Float,
        liveAudioBands: FloatArray? = null,
        speedMultiplier: Float = 1.0f,
        beatSyncMode: Boolean = false,
        animateWhenPaused: Boolean = true
    ): FloatArray {
        val hasLive = liveAudioBands != null && liveAudioBands.any { it > 0.20f }
        if (!isPlaying && !animateWhenPaused && !hasLive) {
            return floatArrayOf(0.26f, 0.34f, 0.28f, 0.22f)
        }

        val speed = speedMultiplier.coerceIn(0.4f, 2.5f)
        val effectiveTime = timeSec * speed
        val seed = ("$trackTitle|$trackArtist").hashCode()
        val absSeed = abs(seed)
        val bpm = 92f + (absSeed % 58) // 92..149 BPM unique per track
        val beatFreq = bpm / 60f
        val phaseShift = (absSeed % 360) * (PI.toFloat() / 180f)
        val swingFactor = 0.85f + ((absSeed shr 4) % 30) / 100f

        val out = FloatArray(4)
        for (i in 0..3) {
            val f1 = (beatFreq * (1f + i * 0.19f) * swingFactor)
            val f2 = (beatFreq * (2.15f - i * 0.22f))
            val phase = phaseShift + i * 1.15f
            val wave1 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f1 * effectiveTime + phase)
            val wave2 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f2 * effectiveTime + phase * 1.7f)
            val harmonic = (0.62f * wave1 + 0.38f * wave2)
            var shaped = 0.20f + 0.78f * (harmonic * harmonic * (3f - 2f * harmonic))

            if (beatSyncMode && !hasLive) {
                // Sharp beat-transient envelope synchronized with the track's tempo when offline/exporting
                val beatPhase = (timeSec * beatFreq + i * 0.12f) % 1f
                val kick = ((1f - beatPhase) * (1f - beatPhase))
                shaped = (0.20f + 0.52f * harmonic + 0.28f * kick).coerceIn(0.18f, 1.0f)
            }

            val live = liveAudioBands?.getOrNull(i)
            out[i] = if (live != null && live > 0.01f && isPlaying) {
                live.coerceIn(0.18f, 1.0f)
            } else {
                shaped.coerceIn(0.18f, 1.0f)
            }
        }
        return out
    }

    /**
     * Lightweight wrapper around Android's Visualizer(0) for real system audio FFT capture.
     */
    class LiveSessionSampler {
        private var visualizer: Visualizer? = null
        @Volatile
        private var latestBands: FloatArray? = null
        private val smoothed = floatArrayOf(0.25f, 0.30f, 0.25f, 0.20f)

        val isAttached: Boolean
            get() = visualizer != null

        fun startIfPermitted(context: Context): Boolean {
            if (visualizer != null) return true
            if (!hasRecordAudioPermission(context)) return false
            return try {
                val capSizes = Visualizer.getCaptureSizeRange()
                val targetCaptureSize = capSizes.getOrElse(0) { 128 }.coerceIn(128, 256)
                val vis = Visualizer(0).apply {
                    captureSize = targetCaptureSize
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                visualizer: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) = Unit

                            override fun onFftDataCapture(
                                visualizer: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (fft == null || fft.size < 16) return
                                val bandSums = FloatArray(4)
                                val counts = IntArray(4)
                                val bins = (fft.size / 2).coerceAtLeast(8)
                                for (k in 1 until bins) {
                                    val re = fft[k * 2].toFloat()
                                    val im = fft[k * 2 + 1].toFloat()
                                    val mag = hypot(re, im) / 90f
                                    val bandIdx = when {
                                        k < bins / 8 -> 0
                                        k < bins / 3 -> 1
                                        k < (bins * 2) / 3 -> 2
                                        else -> 3
                                    }
                                    bandSums[bandIdx] += mag
                                    counts[bandIdx]++
                                }
                                for (b in 0..3) {
                                    val avg = if (counts[b] > 0) bandSums[b] / counts[b] else 0f
                                    val target = (0.18f + avg * 1.45f).coerceIn(0.18f, 1.0f)
                                    smoothed[b] = smoothed[b] * 0.55f + target * 0.45f
                                }
                                latestBands = smoothed.copyOf()
                            }
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true
                    )
                    enabled = true
                }
                visualizer = vis
                true
            } catch (_: Throwable) {
                stop()
                false
            }
        }

        fun currentBands(): FloatArray? = latestBands

        fun stop() {
            latestBands = null
            try {
                visualizer?.enabled = false
            } catch (_: Exception) {
            }
            try {
                visualizer?.release()
            } catch (_: Exception) {
            }
            visualizer = null
        }
    }
}
