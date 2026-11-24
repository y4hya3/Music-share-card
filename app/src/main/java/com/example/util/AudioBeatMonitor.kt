package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Provides real-time 4-bar equalizer amplitudes for the Floating Glass Card waveform.
 *
 * Beat Sync architecture (0ms attack latency):
 * - Uses a ref-counted shared Android `Visualizer(0)` session so multiple composables
 *   (home card + Settings + preview dialog) never conflict over global audio session 0.
 * - Captures BOTH full-rate PCM waveform transients AND 512-point FFT at `Visualizer.getMaxCaptureRate()`
 *   with `SCALING_MODE_AS_PLAYED` for authentic kick/snare/hi-hat punch.
 * - Instant upward attack (0ms smoothing on rising edges) combined with frame-accurate
 *   exponential decay evaluated at display refresh rate (`currentBands()`).
 */
object AudioBeatMonitor {

    private const val MIN_LEVEL = 0.18f
    private const val MIN_LEVEL_6 = 0.16f
    private const val SIGNAL_ACTIVE_WINDOW_NS = 1_500_000_000L

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Computes instantaneous 4-band energy `[sub-bass/kick, low-mid/bass, mid/vocals, treble/hi-hats]`
     * mapped by real Hertz from raw Visualizer FFT bytes.
     */
    internal fun computeBandsFromFft(
        fft: ByteArray,
        samplingRateMilliHz: Int = 44_100_000
    ): FloatArray {
        val bins = (fft.size / 2).coerceAtLeast(8)
        val sampleRateHz = if (samplingRateMilliHz > 100_000) {
            samplingRateMilliHz / 1000f
        } else {
            44_100f
        }
        val hzPerBin = (sampleRateHz / 2f) / bins.toFloat()

        val bandWeightedSum = FloatArray(4)
        val bandMax = FloatArray(4)
        val counts = IntArray(4)

        for (k in 1 until bins) {
            val re = fft[k * 2].toFloat()
            val im = fft[k * 2 + 1].toFloat()
            val mag = hypot(re, im)
            val freqHz = k * hzPerBin

            val bandIdx = when {
                k == 1 || freqHz < 220f -> 0
                freqHz < 620f -> 1
                freqHz < 2500f -> 2
                else -> 3
            }

            // Weight higher bands slightly upward to counteract 1/f music spectrum roll-off
            val weighted = when (bandIdx) {
                0 -> mag / 82f
                1 -> mag / 68f
                2 -> mag / 52f
                else -> mag / 38f
            }
            bandWeightedSum[bandIdx] += weighted
            if (weighted > bandMax[bandIdx]) bandMax[bandIdx] = weighted
            counts[bandIdx]++
        }

        val out = FloatArray(4)
        for (b in 0..3) {
            val avg = if (counts[b] > 0) bandWeightedSum[b] / counts[b] else 0f
            // Blend peak & mean energy so sharp drum hits punch immediately
            out[b] = (0.65f * bandMax[b] + 0.35f * avg).coerceAtLeast(0f)
        }
        return out
    }

    /**
     * Computes instantaneous 6-band logarithmic musical spectrum
     * `[sub-bass, punch-bass, low-mid, vocal-mid, presence/snare, treble/air]`
     * mapped by real Hertz from raw Visualizer FFT bytes.
     */
    internal fun computeBands6FromFft(
        fft: ByteArray,
        samplingRateMilliHz: Int = 44_100_000
    ): FloatArray {
        val bins = (fft.size / 2).coerceAtLeast(8)
        val sampleRateHz = if (samplingRateMilliHz > 100_000) {
            samplingRateMilliHz / 1000f
        } else {
            44_100f
        }
        val hzPerBin = (sampleRateHz / 2f) / bins.toFloat()

        val bandWeightedSum = FloatArray(6)
        val bandMax = FloatArray(6)
        val counts = IntArray(6)

        for (k in 1 until bins) {
            val re = fft[k * 2].toFloat()
            val im = fft[k * 2 + 1].toFloat()
            val mag = hypot(re, im)
            val freqHz = k * hzPerBin

            val bandIdx = when {
                k == 1 || freqHz < 130f -> 0
                freqHz < 320f -> 1
                freqHz < 800f -> 2
                freqHz < 2000f -> 3
                freqHz < 5200f -> 4
                else -> 5
            }

            // Psychoacoustic tilt compensation so hi-hats, vocals, and kicks balance cleanly
            val weighted = when (bandIdx) {
                0 -> mag / 86f
                1 -> mag / 74f
                2 -> mag / 60f
                3 -> mag / 50f
                4 -> mag / 42f
                else -> mag / 34f
            }
            bandWeightedSum[bandIdx] += weighted
            if (weighted > bandMax[bandIdx]) bandMax[bandIdx] = weighted
            counts[bandIdx]++
        }

        val out = FloatArray(6)
        for (b in 0..5) {
            val avg = if (counts[b] > 0) bandWeightedSum[b] / counts[b] else 0f
            out[b] = (0.68f * bandMax[b] + 0.32f * avg).coerceAtLeast(0f)
        }
        return out
    }

    /**
     * Extracts instantaneous unsigned-PCM time-domain transient energy `[0..1+]` from
     * `onWaveFormDataCapture` bytes (centered at 128).
     */
    internal fun computePcmTransientEnergy(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var peakAbs = 0
        var sumSq = 0L
        val step = if (waveform.size > 256) 2 else 1
        var samples = 0
        var i = 0
        while (i < waveform.size) {
            val centered = (waveform[i].toInt() and 0xFF) - 128
            val a = abs(centered)
            if (a > peakAbs) peakAbs = a
            sumSq += centered * centered
            samples++
            i += step
        }
        if (samples == 0) return 0f
        val rms = kotlin.math.sqrt(sumSq.toFloat() / samples) / 64f
        val peak = peakAbs / 110f
        return (0.60f * peak + 0.40f * rms).coerceAtLeast(0f)
    }

    /**
     * Separates time-domain PCM audio into:
     * - `first`: low-frequency bass/kick envelope (smoothed sample magnitude)
     * - `second`: high-frequency percussive transient energy (sample-to-sample derivative)
     */
    internal fun computePcmLowAndTransientEnergy(waveform: ByteArray): Pair<Float, Float> {
        if (waveform.isEmpty()) return 0f to 0f
        var lowSum = 0L
        var peakAbs = 0
        var diffSum = 0L
        var diffPeak = 0
        var prevSample = (waveform[0].toInt() and 0xFF) - 128
        var samples = 0
        for (i in waveform.indices) {
            val s = (waveform[i].toInt() and 0xFF) - 128
            val lowSample = (s + prevSample) / 2
            val lowAbs = abs(lowSample)
            if (lowAbs > peakAbs) peakAbs = lowAbs
            lowSum += lowSample * lowSample

            val d = abs(s - prevSample)
            if (d > diffPeak) diffPeak = d
            diffSum += d * d
            prevSample = s
            samples++
        }
        if (samples == 0) return 0f to 0f
        val lowRms = kotlin.math.sqrt(lowSum.toFloat() / samples) / 64f
        val lowPeak = peakAbs / 110f
        val lowEnergy = (0.60f * lowPeak + 0.40f * lowRms).coerceAtLeast(0f)

        val hiRms = kotlin.math.sqrt(diffSum.toFloat() / samples) / 48f
        val hiPeak = diffPeak / 96f
        val hiEnergy = (0.62f * hiPeak + 0.38f * hiRms).coerceAtLeast(0f)
        return lowEnergy to hiEnergy
    }

    /**
     * Updates peak-normalized zero-latency beat envelopes given raw band intensities.
     * - Rising edges apply ZERO lag (instant 0ms jump).
     * - Maintains both 4-band and 6-band synchronized envelopes from the same hardware audio tap.
     */
    internal class BeatEnvelopeTracker {
        private val env = floatArrayOf(MIN_LEVEL, MIN_LEVEL, MIN_LEVEL, MIN_LEVEL)
        private val env6 = floatArrayOf(MIN_LEVEL_6, MIN_LEVEL_6, MIN_LEVEL_6, MIN_LEVEL_6, MIN_LEVEL_6, MIN_LEVEL_6)
        private val prevRaw = FloatArray(4)
        private val prevRaw6 = FloatArray(6)
        private var rollingPeak = 0.36f
        private var rollingPeak6 = 0.34f
        private var lastCallbackNanos = 0L
        private var lastCaptureCallbackNanos = 0L
        private var silentSinceNanos = 0L
        @Volatile
        var lastSignalNanos: Long = 0L
            private set

        @Synchronized
        fun reset() {
            env.fill(MIN_LEVEL)
            env6.fill(MIN_LEVEL_6)
            prevRaw.fill(0f)
            prevRaw6.fill(0f)
            rollingPeak = 0.36f
            rollingPeak6 = 0.34f
            lastCallbackNanos = 0L
            lastCaptureCallbackNanos = 0L
            silentSinceNanos = 0L
            lastSignalNanos = 0L
        }

        @Synchronized
        fun ingestFft(fft: ByteArray, samplingRateMilliHz: Int, nowNanos: Long): FloatArray {
            val raw = computeBandsFromFft(fft, samplingRateMilliHz)
            val raw6 = computeBands6FromFft(fft, samplingRateMilliHz)
            val totalEnergy = raw[0] + raw[1] + raw[2] + raw[3]
            decayToNow(nowNanos)
            lastCaptureCallbackNanos = nowNanos

            if (totalEnergy <= 0.014f) {
                if (silentSinceNanos == 0L) {
                    silentSinceNanos = nowNanos
                }
                // Hold AGC rollingPeak steady during volume-0 / silence so unmuting never over-amplifies
                rollingPeak = max(0.36f, rollingPeak)
                rollingPeak6 = max(0.34f, rollingPeak6)
                if (nowNanos - silentSinceNanos > 120_000_000L) {
                    prevRaw.fill(0f)
                    prevRaw6.fill(0f)
                }
                return env.copyOf()
            }

            val wasSilent = (silentSinceNanos > 0L && nowNanos - silentSinceNanos > 180_000_000L) ||
                (lastSignalNanos > 0L && nowNanos - lastSignalNanos > 250_000_000L)
            silentSinceNanos = 0L
            lastSignalNanos = nowNanos

            val frameMax = max(raw[0], max(raw[1], max(raw[2], raw[3]))).coerceAtMost(2.5f)
            val frameMax6 = raw6.maxOrNull()?.coerceAtMost(2.5f) ?: frameMax
            if (wasSilent) {
                rollingPeak = max(0.42f, max(rollingPeak, frameMax))
                rollingPeak6 = max(0.40f, max(rollingPeak6, frameMax6))
                for (b in 0..3) prevRaw[b] = raw[b]
                for (b in 0..5) prevRaw6[b] = raw6[b]
            } else {
                rollingPeak = if (frameMax > rollingPeak) {
                    rollingPeak * 0.45f + frameMax * 0.55f
                } else {
                    max(0.18f, rollingPeak * 0.968f)
                }
                rollingPeak6 = if (frameMax6 > rollingPeak6) {
                    rollingPeak6 * 0.42f + frameMax6 * 0.58f
                } else {
                    max(0.16f, rollingPeak6 * 0.965f)
                }
            }
            val normScale = 1.0f / rollingPeak.coerceAtLeast(0.18f)
            val normScale6 = 1.0f / rollingPeak6.coerceAtLeast(0.16f)

            for (b in 0..3) {
                val flux = max(0f, raw[b] - prevRaw[b]) * 1.45f
                prevRaw[b] = raw[b]
                val drive = ((raw[b] * 0.95f + flux) * normScale).coerceIn(0f, 1.35f)
                val shaped = kotlin.math.sqrt(drive.coerceIn(0f, 1f))
                val target = (MIN_LEVEL + shaped * (1.0f - MIN_LEVEL))
                    .coerceIn(MIN_LEVEL, 1.0f)
                // 0ms ATTACK: snap upward immediately on beat hit
                if (target >= env[b]) {
                    env[b] = target
                }
            }

            for (b in 0..5) {
                val flux6 = max(0f, raw6[b] - prevRaw6[b]) * 1.65f
                prevRaw6[b] = raw6[b]
                val drive6 = ((raw6[b] * 0.95f + flux6) * normScale6).coerceIn(0f, 1.35f)
                val shaped6 = kotlin.math.sqrt(drive6.coerceIn(0f, 1f))
                val target6 = (MIN_LEVEL_6 + shaped6 * (1.0f - MIN_LEVEL_6))
                    .coerceIn(MIN_LEVEL_6, 1.0f)
                if (target6 >= env6[b]) {
                    env6[b] = target6
                }
            }
            return env.copyOf()
        }

        @Synchronized
        fun ingestWaveform(waveform: ByteArray, nowNanos: Long): FloatArray {
            val energy = computePcmTransientEnergy(waveform).coerceAtMost(2.0f)
            val (lowEnergy, hiEnergy) = computePcmLowAndTransientEnergy(waveform)
            decayToNow(nowNanos)
            lastCaptureCallbackNanos = nowNanos

            if (energy <= 0.020f) {
                if (silentSinceNanos == 0L) {
                    silentSinceNanos = nowNanos
                }
                rollingPeak = max(0.36f, rollingPeak)
                rollingPeak6 = max(0.34f, rollingPeak6)
                if (nowNanos - silentSinceNanos > 120_000_000L) {
                    prevRaw.fill(0f)
                    prevRaw6.fill(0f)
                }
                return env.copyOf()
            }

            val wasSilent = (silentSinceNanos > 0L && nowNanos - silentSinceNanos > 180_000_000L) ||
                (lastSignalNanos > 0L && nowNanos - lastSignalNanos > 250_000_000L)
            silentSinceNanos = 0L
            lastSignalNanos = nowNanos

            if (wasSilent) {
                rollingPeak = max(0.42f, max(rollingPeak, energy))
                rollingPeak6 = max(0.40f, max(rollingPeak6, max(lowEnergy, hiEnergy)))
            } else {
                rollingPeak = if (energy > rollingPeak) {
                    rollingPeak * 0.45f + energy * 0.55f
                } else {
                    max(0.18f, rollingPeak * 0.975f)
                }
                val combPeak = max(lowEnergy, hiEnergy).coerceAtMost(2.0f)
                rollingPeak6 = if (combPeak > rollingPeak6) {
                    rollingPeak6 * 0.42f + combPeak * 0.58f
                } else {
                    max(0.16f, rollingPeak6 * 0.972f)
                }
            }
            val norm = (energy / rollingPeak.coerceAtLeast(0.18f)).coerceIn(0f, 1.35f)
            val shapedNorm = kotlin.math.sqrt(norm.coerceIn(0f, 1f))
            val weights = floatArrayOf(1.0f, 0.95f, 0.86f, 0.78f)
            for (b in 0..3) {
                val target = (MIN_LEVEL + shapedNorm * weights[b] * (1.0f - MIN_LEVEL))
                    .coerceIn(MIN_LEVEL, 1.0f)
                if (target > env[b]) {
                    env[b] = target
                }
            }

            val normLow = kotlin.math.sqrt((lowEnergy / rollingPeak6.coerceAtLeast(0.16f)).coerceIn(0f, 1f))
            val normHi = kotlin.math.sqrt((hiEnergy / rollingPeak6.coerceAtLeast(0.16f)).coerceIn(0f, 1f))
            val lowMix = floatArrayOf(1.00f, 0.92f, 0.65f, 0.42f, 0.25f, 0.15f)
            val hiMix = floatArrayOf(0.18f, 0.34f, 0.68f, 0.88f, 0.98f, 0.95f)
            for (b in 0..5) {
                val combined = (normLow * lowMix[b] + normHi * hiMix[b]).coerceIn(0f, 1f)
                val target6 = (MIN_LEVEL_6 + combined * (1.0f - MIN_LEVEL_6))
                    .coerceIn(MIN_LEVEL_6, 1.0f)
                if (target6 > env6[b]) {
                    env6[b] = target6
                }
            }
            return env.copyOf()
        }

        @Synchronized
        fun snapshotAt(nowNanos: Long): FloatArray? {
            if (lastCaptureCallbackNanos == 0L && lastSignalNanos == 0L) return null
            decayToNow(nowNanos)
            val captureAlive = lastCaptureCallbackNanos > 0L &&
                (nowNanos - lastCaptureCallbackNanos) <= SIGNAL_ACTIVE_WINDOW_NS
            val signalRecent = lastSignalNanos > 0L &&
                (nowNanos - lastSignalNanos) <= SIGNAL_ACTIVE_WINDOW_NS
            if (!captureAlive && !signalRecent && env.all { it <= MIN_LEVEL + 0.015f }) {
                return null
            }
            return env.copyOf()
        }

        @Synchronized
        fun snapshot6At(nowNanos: Long): FloatArray? {
            if (lastCaptureCallbackNanos == 0L && lastSignalNanos == 0L) return null
            decayToNow(nowNanos)
            val captureAlive = lastCaptureCallbackNanos > 0L &&
                (nowNanos - lastCaptureCallbackNanos) <= SIGNAL_ACTIVE_WINDOW_NS
            val signalRecent = lastSignalNanos > 0L &&
                (nowNanos - lastSignalNanos) <= SIGNAL_ACTIVE_WINDOW_NS
            if (!captureAlive && !signalRecent && env6.all { it <= MIN_LEVEL_6 + 0.015f }) {
                return null
            }
            return env6.copyOf()
        }

        fun hasRecentSignal(nowNanos: Long): Boolean {
            val sig = lastSignalNanos
            return sig > 0L && (nowNanos - sig) <= SIGNAL_ACTIVE_WINDOW_NS
        }

        fun hasActiveCaptureFeed(nowNanos: Long): Boolean {
            val cap = lastCaptureCallbackNanos
            return cap > 0L && (nowNanos - cap) <= SIGNAL_ACTIVE_WINDOW_NS
        }

        private fun decayToNow(nowNanos: Long) {
            val prev = lastCallbackNanos
            if (prev <= 0L || nowNanos <= prev) {
                lastCallbackNanos = nowNanos
                return
            }
            val dtSec = ((nowNanos - prev) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
            if (dtSec <= 0f) return
            val decayFactor4 = exp(-9.2f * dtSec)
            for (b in 0..3) {
                env[b] = (MIN_LEVEL + (env[b] - MIN_LEVEL) * decayFactor4).coerceIn(MIN_LEVEL, 1.0f)
            }
            // Tighter release (~12.8/s) on 6-bar Apple Music analyzer for sharp rhythmic articulation
            val decayFactor6 = exp(-12.8f * dtSec)
            for (b in 0..5) {
                env6[b] = (MIN_LEVEL_6 + (env6[b] - MIN_LEVEL_6) * decayFactor6).coerceIn(MIN_LEVEL_6, 1.0f)
            }
            lastCallbackNanos = nowNanos
        }
    }

    /**
     * Reference-counted singleton Visualizer(0) controller with direct frame-exact polling
     * (`getWaveForm` + `getFft`) so display refreshes get 0ms hardware audio instead of waiting
     * for 20Hz (~50ms) Android callback timers.
     */
    private object SharedAudioVisualizer {
        private val lock = Any()
        private var visualizer: Visualizer? = null
        private var refCount = 0
        private var lastAttachAttemptMs = 0L
        private var attachedAtNanos = 0L
        private var lastStaleRefreshMs = 0L
        private var lastHardwarePollNanos = 0L
        private var cachedSampleRateMilliHz = 48_000_000
        private var pollWaveBuf = ByteArray(512)
        private var pollFftBuf = ByteArray(512)
        val tracker = BeatEnvelopeTracker()

        fun acquire(context: Context): Boolean {
            synchronized(lock) {
                refCount++
                return ensureStartedLocked(context, forceRetry = true)
            }
        }

        fun ensureActive(context: Context): Boolean {
            synchronized(lock) {
                if (refCount <= 0) return false
                return ensureStartedLocked(context, forceRetry = false)
            }
        }

        fun pollAndSnapshot(nowNanos: Long): FloatArray? {
            synchronized(lock) {
                pollHardwareFrameLocked(nowNanos)
            }
            return tracker.snapshotAt(nowNanos)
        }

        fun pollAndSnapshot6(nowNanos: Long): FloatArray? {
            synchronized(lock) {
                pollHardwareFrameLocked(nowNanos)
            }
            return tracker.snapshot6At(nowNanos)
        }

        private fun pollHardwareFrameLocked(nowNanos: Long) {
            val vis = visualizer ?: return
            // Read directly from AudioFlinger buffer at up to display vsync rate (~6ms throttle)
            if (lastHardwarePollNanos != 0L && (nowNanos - lastHardwarePollNanos) < 6_000_000L) {
                return
            }
            lastHardwarePollNanos = nowNanos
            try {
                if (!vis.enabled) return
                val waveRes = vis.getWaveForm(pollWaveBuf)
                if (waveRes == Visualizer.SUCCESS) {
                    tracker.ingestWaveform(pollWaveBuf, nowNanos)
                }
                val fftRes = vis.getFft(pollFftBuf)
                if (fftRes == Visualizer.SUCCESS) {
                    tracker.ingestFft(pollFftBuf, cachedSampleRateMilliHz, nowNanos)
                }
            } catch (_: Throwable) {
            }
        }

        private fun ensureStartedLocked(context: Context, forceRetry: Boolean): Boolean {
            if (!hasRecordAudioPermission(context)) return false
            val nowMs = SystemClock.elapsedRealtime()
            val nowNs = SystemClock.elapsedRealtimeNanos()
            if (visualizer != null) {
                val attachedForNs = nowNs - attachedAtNanos
                if (attachedForNs > 2_500_000_000L && !tracker.hasActiveCaptureFeed(nowNs)) {
                    if (forceRetry || nowMs - lastStaleRefreshMs >= 3_000L) {
                        lastStaleRefreshMs = nowMs
                        releaseVisualizerLocked()
                    } else {
                        return false
                    }
                } else {
                    return true
                }
            }
            if (!forceRetry && nowMs - lastAttachAttemptMs < 750L) {
                return false
            }
            lastAttachAttemptMs = nowMs

            return try {
                val capSizes = Visualizer.getCaptureSizeRange()
                val minCap = capSizes.getOrElse(0) { 128 }
                val maxCap = capSizes.getOrElse(1) { 1024 }
                // 512 samples balances sub-100Hz FFT bin accuracy with fast hardware capture
                val targetCaptureSize = 512.coerceIn(minCap, maxCap)
                if (pollWaveBuf.size != targetCaptureSize) pollWaveBuf = ByteArray(targetCaptureSize)
                if (pollFftBuf.size != targetCaptureSize) pollFftBuf = ByteArray(targetCaptureSize)

                val vis = Visualizer(0).apply {
                    captureSize = targetCaptureSize
                    cachedSampleRateMilliHz = runCatching { samplingRate }.getOrDefault(48_000_000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        try {
                            scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                        } catch (_: Throwable) {
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                        } catch (_: Throwable) {
                        }
                    }
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                visualizer: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (waveform != null && waveform.isNotEmpty()) {
                                    tracker.ingestWaveform(waveform, SystemClock.elapsedRealtimeNanos())
                                }
                            }

                            override fun onFftDataCapture(
                                visualizer: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (fft != null && fft.size >= 16) {
                                    cachedSampleRateMilliHz = samplingRate
                                    tracker.ingestFft(fft, samplingRate, SystemClock.elapsedRealtimeNanos())
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate(),
                        true,
                        true
                    )
                    enabled = true
                }
                visualizer = vis
                attachedAtNanos = SystemClock.elapsedRealtimeNanos()
                lastHardwarePollNanos = 0L
                true
            } catch (_: Throwable) {
                releaseVisualizerLocked()
                false
            }
        }

        fun release() {
            synchronized(lock) {
                refCount = max(0, refCount - 1)
                if (refCount == 0) {
                    releaseVisualizerLocked()
                    tracker.reset()
                }
            }
        }

        private fun releaseVisualizerLocked() {
            try {
                visualizer?.enabled = false
            } catch (_: Exception) {
            }
            try {
                visualizer?.release()
            } catch (_: Exception) {
            }
            visualizer = null
            lastHardwarePollNanos = 0L
        }
    }

    /**
     * Deterministic, track-fingerprinted 4-bar waveform heights in [0.18f .. 1.0f].
     */
    fun computeBarLevels(
        trackTitle: String,
        trackArtist: String,
        isPlaying: Boolean,
        timeSec: Float,
        liveAudioBands: FloatArray? = null,
        speedMultiplier: Float = 1.0f,
        beatSyncMode: Boolean = false,
        animateWhenPaused: Boolean = true,
        hasLiveSignal: Boolean = liveAudioBands != null && liveAudioBands.any { it > 0.20f },
        forceMultiplier: Float = 1.35f
    ): FloatArray {
        val force = forceMultiplier.coerceIn(0.5f, 2.2f)
        if (beatSyncMode && liveAudioBands != null && hasLiveSignal) {
            return FloatArray(4) { i ->
                val raw = (liveAudioBands.getOrNull(i) ?: MIN_LEVEL).coerceIn(MIN_LEVEL, 1.0f)
                val normalized = ((raw - MIN_LEVEL) / (1.0f - MIN_LEVEL)).coerceIn(0f, 1f)
                val boosted = (normalized * (0.95f + (force - 1.0f) * 0.35f).coerceIn(0.65f, 1.5f))
                    .coerceIn(0f, 1f)
                (MIN_LEVEL + boosted * (1.0f - MIN_LEVEL)).coerceIn(MIN_LEVEL, 1.0f)
            }
        }

        if (!isPlaying && !animateWhenPaused) {
            return floatArrayOf(0.26f, 0.34f, 0.28f, 0.22f)
        }

        val speed = speedMultiplier.coerceIn(0.4f, 2.5f)
        val effectiveTime = timeSec * speed
        val seed = ("$trackTitle|$trackArtist").hashCode()
        val absSeed = abs(seed)
        val bpm = 96f + (absSeed % 54)
        val beatFreq = bpm / 60f
        val phaseShift = (absSeed % 360) * (PI.toFloat() / 180f)
        val swingFactor = 0.85f + ((absSeed shr 4) % 30) / 100f

        val out = FloatArray(4)
        for (i in 0..3) {
            val f1 = (beatFreq * (1f + i * 0.19f) * swingFactor)
            val f2 = (beatFreq * (2.10f - i * 0.20f))
            val phase = phaseShift + i * 1.15f
            val wave1 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f1 * effectiveTime + phase)
            val wave2 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f2 * effectiveTime + phase * 1.7f)
            val harmonic = (0.60f * wave1 + 0.40f * wave2).coerceIn(0f, 1f)

            val beatPhase = ((if (beatSyncMode) timeSec else effectiveTime) * beatFreq + i * 0.14f) % 1f
            val kick = ((1f - beatPhase) * (1f - beatPhase) * (1f - beatPhase))
            val rawEnvelope = if (beatSyncMode) {
                (0.54f * harmonic + 0.46f * kick)
            } else {
                val smooth = (harmonic * harmonic * (3f - 2f * harmonic))
                (0.68f * smooth + 0.32f * kick)
            }.coerceIn(0f, 1f)

            val scaledEnvelope = (rawEnvelope * (if (beatSyncMode) max(1.15f, force) else force))
                .coerceIn(0f, 1f)
            out[i] = (MIN_LEVEL + (1.0f - MIN_LEVEL) * scaledEnvelope).coerceIn(MIN_LEVEL, 1.0f)
        }
        return out
    }

    /**
     * 6-bar Apple Music waveform levels `[0.16f .. 1.0f]`:
     * - When live Visualizer hardware FFT + PCM bands are streaming (`liveAudioBands6 != null` & [hasLiveSignal]),
     *   outputs true 0ms frequency-accurate levels directly.
     * - When offline or without mic permission, derives a tight beat-locked 6-band groove from
     *   song identity & playback time.
     */
    fun computeBarLevels6(
        trackTitle: String,
        trackArtist: String,
        isPlaying: Boolean,
        timeSec: Float,
        liveAudioBands6: FloatArray? = null,
        hasLiveSignal: Boolean = liveAudioBands6 != null && liveAudioBands6.any { it > 0.18f },
        animateWhenPaused: Boolean = false
    ): FloatArray {
        if (liveAudioBands6 != null && hasLiveSignal) {
            return FloatArray(6) { i ->
                (liveAudioBands6.getOrNull(i) ?: MIN_LEVEL_6).coerceIn(MIN_LEVEL_6, 1.0f)
            }
        }

        if (!isPlaying && !animateWhenPaused) {
            return floatArrayOf(0.24f, 0.40f, 0.30f, 0.46f, 0.32f, 0.22f)
        }

        val seed = ("$trackTitle|$trackArtist").hashCode()
        val absSeed = abs(seed)
        val bpm = 96f + (absSeed % 54)
        val beatFreq = bpm / 60f
        val phaseShift = (absSeed % 360) * (PI.toFloat() / 180f)
        val bandBase = floatArrayOf(0.84f, 1.00f, 0.88f, 0.96f, 0.82f, 0.72f)
        val bandRateMul = floatArrayOf(0.50f, 1.00f, 1.25f, 1.50f, 2.00f, 2.40f)

        return FloatArray(6) { i ->
            val phase = phaseShift + i * 0.92f
            val f1 = beatFreq * bandRateMul[i]
            val f2 = beatFreq * (1.85f + i * 0.22f)
            val w1 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f1 * timeSec + phase)
            val w2 = 0.5f + 0.5f * sin(2f * PI.toFloat() * f2 * timeSec + phase * 1.4f)
            val beatPhase = (timeSec * beatFreq + i * 0.10f) % 1f
            val kick = (1f - beatPhase) * (1f - beatPhase) * (1f - beatPhase)
            val groove = ((0.55f * w1 + 0.45f * kick * (if (i <= 2) 1.0f else 0.72f)) * 0.72f + 0.28f * w2)
                .coerceIn(0f, 1f)
            val shaped = (groove * bandBase[i] * 1.18f).coerceIn(0f, 1f)
            (MIN_LEVEL_6 + shaped * (1.0f - MIN_LEVEL_6)).coerceIn(MIN_LEVEL_6, 1.0f)
        }
    }

    /**
     * Composable handle backed by [SharedAudioVisualizer] for 0ms hardware audio capture.
     */
    class LiveSessionSampler {
        private var acquired = false

        val isAttached: Boolean
            get() = acquired

        fun startIfPermitted(context: Context): Boolean {
            if (!hasRecordAudioPermission(context)) return false
            if (!acquired) {
                acquired = true
                return SharedAudioVisualizer.acquire(context)
            }
            return SharedAudioVisualizer.ensureActive(context)
        }

        fun ensureActive(context: Context) {
            if (acquired) {
                SharedAudioVisualizer.ensureActive(context)
            } else {
                startIfPermitted(context)
            }
        }

        fun hasLiveSignal(): Boolean =
            SharedAudioVisualizer.tracker.hasRecentSignal(SystemClock.elapsedRealtimeNanos())

        fun hasLiveFeed(): Boolean {
            val now = SystemClock.elapsedRealtimeNanos()
            return SharedAudioVisualizer.tracker.hasActiveCaptureFeed(now) ||
                SharedAudioVisualizer.tracker.hasRecentSignal(now)
        }

        fun currentBands(): FloatArray? {
            if (!acquired) return null
            return SharedAudioVisualizer.pollAndSnapshot(SystemClock.elapsedRealtimeNanos())
        }

        fun currentBands6(): FloatArray? {
            if (!acquired) return null
            return SharedAudioVisualizer.pollAndSnapshot6(SystemClock.elapsedRealtimeNanos())
        }

        fun stop() {
            if (acquired) {
                acquired = false
                SharedAudioVisualizer.release()
            }
        }
    }
}
