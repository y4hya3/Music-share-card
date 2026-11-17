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
     * Updates peak-normalized zero-latency beat envelopes given raw band intensities.
     * - Rising edges apply ZERO lag (instant jump).
     * - Adaptive rolling peak ensures dynamic punch across quiet and loud songs.
     */
    internal class BeatEnvelopeTracker {
        private val env = floatArrayOf(MIN_LEVEL, MIN_LEVEL, MIN_LEVEL, MIN_LEVEL)
        private val prevRaw = FloatArray(4)
        private var rollingPeak = 0.65f
        private var lastCallbackNanos = 0L
        @Volatile
        var lastSignalNanos: Long = 0L
            private set

        @Synchronized
        fun reset() {
            env.fill(MIN_LEVEL)
            prevRaw.fill(0f)
            rollingPeak = 0.65f
            lastCallbackNanos = 0L
            lastSignalNanos = 0L
        }

        @Synchronized
        fun ingestFft(fft: ByteArray, samplingRateMilliHz: Int, nowNanos: Long): FloatArray {
            val raw = computeBandsFromFft(fft, samplingRateMilliHz)
            val totalEnergy = raw[0] + raw[1] + raw[2] + raw[3]
            if (totalEnergy <= 0.015f) {
                decayToNow(nowNanos)
                return env.copyOf()
            }
            lastSignalNanos = nowNanos
            decayToNow(nowNanos)

            val frameMax = max(raw[0], max(raw[1], max(raw[2], raw[3])))
            rollingPeak = if (frameMax > rollingPeak) {
                rollingPeak * 0.35f + frameMax * 0.65f
            } else {
                max(0.28f, rollingPeak * 0.985f)
            }
            val normScale = 1.0f / rollingPeak.coerceAtLeast(0.28f)

            for (b in 0..3) {
                val flux = max(0f, raw[b] - prevRaw[b]) * 1.15f
                prevRaw[b] = raw[b]
                val target = (MIN_LEVEL + ((raw[b] * 0.85f + flux) * normScale) * 0.84f)
                    .coerceIn(MIN_LEVEL, 1.0f)
                // ZERO-LATENCY ATTACK: snap upward immediately on beat hit
                if (target >= env[b]) {
                    env[b] = target
                }
            }
            lastCallbackNanos = nowNanos
            return env.copyOf()
        }

        @Synchronized
        fun ingestWaveform(waveform: ByteArray, nowNanos: Long): FloatArray {
            val energy = computePcmTransientEnergy(waveform)
            if (energy <= 0.025f) {
                return env.copyOf()
            }
            lastSignalNanos = nowNanos
            decayToNow(nowNanos)

            rollingPeak = if (energy > rollingPeak) {
                rollingPeak * 0.40f + energy * 0.60f
            } else {
                max(0.28f, rollingPeak * 0.992f)
            }
            val norm = (energy / rollingPeak.coerceAtLeast(0.28f)).coerceIn(0f, 1.3f)
            // Distribute crisp time-domain transient boost skewed slightly toward low/mid punch
            val weights = floatArrayOf(1.0f, 0.92f, 0.82f, 0.72f)
            for (b in 0..3) {
                val target = (MIN_LEVEL + norm * weights[b] * 0.78f).coerceIn(MIN_LEVEL, 1.0f)
                if (target > env[b]) {
                    env[b] = target
                }
            }
            lastCallbackNanos = nowNanos
            return env.copyOf()
        }

        @Synchronized
        fun snapshotAt(nowNanos: Long): FloatArray? {
            if (lastSignalNanos == 0L) return null
            if (nowNanos - lastSignalNanos > SIGNAL_ACTIVE_WINDOW_NS) {
                // Signal ended/paused: let bars smoothly fall to idle floor
                decayToNow(nowNanos)
                if (env.all { it <= MIN_LEVEL + 0.015f }) return null
                return env.copyOf()
            }
            decayToNow(nowNanos)
            return env.copyOf()
        }

        fun hasRecentSignal(nowNanos: Long): Boolean {
            val sig = lastSignalNanos
            return sig > 0L && (nowNanos - sig) <= SIGNAL_ACTIVE_WINDOW_NS
        }

        private fun decayToNow(nowNanos: Long) {
            val prev = lastCallbackNanos
            if (prev <= 0L || nowNanos <= prev) {
                lastCallbackNanos = nowNanos
                return
            }
            val dtSec = ((nowNanos - prev) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
            if (dtSec <= 0f) return
            // Fast musical release (~8.5/s falloff -> natural spring drop between 120-160 BPM beats)
            val decayFactor = exp(-8.5f * dtSec)
            for (b in 0..3) {
                env[b] = (MIN_LEVEL + (env[b] - MIN_LEVEL) * decayFactor).coerceIn(MIN_LEVEL, 1.0f)
            }
            lastCallbackNanos = nowNanos
        }
    }

    /**
     * Reference-counted singleton Visualizer(0) controller so multiple visible composables share
     * one zero-latency hardware audio tap cleanly without resource contention.
     */
    private object SharedAudioVisualizer {
        private val lock = Any()
        private var visualizer: Visualizer? = null
        private var refCount = 0
        private var lastAttachAttemptMs = 0L
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

        private fun ensureStartedLocked(context: Context, forceRetry: Boolean): Boolean {
            if (visualizer != null) return true
            if (!hasRecordAudioPermission(context)) return false
            val nowMs = SystemClock.elapsedRealtime()
            if (!forceRetry && nowMs - lastAttachAttemptMs < 750L) {
                return false
            }
            lastAttachAttemptMs = nowMs

            return try {
                val capSizes = Visualizer.getCaptureSizeRange()
                val minCap = capSizes.getOrElse(0) { 128 }
                val maxCap = capSizes.getOrElse(1) { 1024 }
                // 512 samples (~10.6ms @ 48kHz) balances crisp kick-drum resolution with instant latency
                val targetCaptureSize = 512.coerceIn(minCap, maxCap)

                val vis = Visualizer(0).apply {
                    captureSize = targetCaptureSize
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        try {
                            scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
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
        }
    }

    /**
     * Deterministic, track-fingerprinted 4-bar waveform heights in [0.18f .. 1.0f].
     * - When live Visualizer FFT/PCM bands are actively streaming (`liveAudioBands != null` & [hasLiveSignal]),
     *   outputs live bands directly with 0ms delay.
     * - In Default Speed mode ([beatSyncMode] == false), scales smooth wave motion by [speedMultiplier].
     * - Always animates cleanly when [animateWhenPaused] is true so previews and MP4/GIF exports never freeze.
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
        hasLiveSignal: Boolean = liveAudioBands != null && liveAudioBands.any { it > 0.20f }
    ): FloatArray {
        if (beatSyncMode && liveAudioBands != null && hasLiveSignal) {
            return FloatArray(4) { i ->
                (liveAudioBands.getOrNull(i) ?: MIN_LEVEL).coerceIn(MIN_LEVEL, 1.0f)
            }
        }

        if (!isPlaying && !animateWhenPaused) {
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

            if (beatSyncMode) {
                val beatPhase = (timeSec * beatFreq + i * 0.12f) % 1f
                val kick = ((1f - beatPhase) * (1f - beatPhase))
                shaped = (0.20f + 0.52f * harmonic + 0.28f * kick).coerceIn(MIN_LEVEL, 1.0f)
            }
            out[i] = shaped.coerceIn(MIN_LEVEL, 1.0f)
        }
        return out
    }

    /**
     * Composable handle backed by [SharedAudioVisualizer] for 0-latency audio capture.
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

        fun currentBands(): FloatArray? {
            if (!acquired) return null
            return SharedAudioVisualizer.tracker.snapshotAt(SystemClock.elapsedRealtimeNanos())
        }

        fun stop() {
            if (acquired) {
                acquired = false
                SharedAudioVisualizer.release()
            }
        }
    }
}
