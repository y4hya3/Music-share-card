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
     * - Responsive rolling AGC keeps heavy kicks hitting 0.90..1.0f even at moderate phone volume.
     */
    internal class BeatEnvelopeTracker {
        private val env = floatArrayOf(MIN_LEVEL, MIN_LEVEL, MIN_LEVEL, MIN_LEVEL)
        private val prevRaw = FloatArray(4)
        private var rollingPeak = 0.36f
        private var lastCallbackNanos = 0L
        private var lastCaptureCallbackNanos = 0L
        private var silentSinceNanos = 0L
        @Volatile
        var lastSignalNanos: Long = 0L
            private set

        @Synchronized
        fun reset() {
            env.fill(MIN_LEVEL)
            prevRaw.fill(0f)
            rollingPeak = 0.36f
            lastCallbackNanos = 0L
            lastCaptureCallbackNanos = 0L
            silentSinceNanos = 0L
            lastSignalNanos = 0L
        }

        @Synchronized
        fun ingestFft(fft: ByteArray, samplingRateMilliHz: Int, nowNanos: Long): FloatArray {
            val raw = computeBandsFromFft(fft, samplingRateMilliHz)
            val totalEnergy = raw[0] + raw[1] + raw[2] + raw[3]
            decayToNow(nowNanos)
            lastCaptureCallbackNanos = nowNanos

            if (totalEnergy <= 0.014f) {
                if (silentSinceNanos == 0L) {
                    silentSinceNanos = nowNanos
                }
                // Hold AGC rollingPeak steady during volume-0 / silence so unmuting never over-amplifies
                rollingPeak = max(0.36f, rollingPeak)
                if (nowNanos - silentSinceNanos > 120_000_000L) {
                    prevRaw.fill(0f)
                }
                return env.copyOf()
            }

            val wasSilent = (silentSinceNanos > 0L && nowNanos - silentSinceNanos > 180_000_000L) ||
                (lastSignalNanos > 0L && nowNanos - lastSignalNanos > 250_000_000L)
            silentSinceNanos = 0L
            lastSignalNanos = nowNanos

            val frameMax = max(raw[0], max(raw[1], max(raw[2], raw[3]))).coerceAtMost(2.5f)
            if (wasSilent) {
                rollingPeak = max(0.42f, max(rollingPeak, frameMax))
                for (b in 0..3) {
                    prevRaw[b] = raw[b]
                }
            } else {
                rollingPeak = if (frameMax > rollingPeak) {
                    rollingPeak * 0.45f + frameMax * 0.55f
                } else {
                    max(0.18f, rollingPeak * 0.968f)
                }
            }
            val normScale = 1.0f / rollingPeak.coerceAtLeast(0.18f)

            for (b in 0..3) {
                val flux = max(0f, raw[b] - prevRaw[b]) * 1.45f
                prevRaw[b] = raw[b]
                val drive = ((raw[b] * 0.95f + flux) * normScale).coerceIn(0f, 1.35f)
                // Concave punch curve so real beats push confidently near 1.0 instead of hovering low
                val shaped = kotlin.math.sqrt(drive.coerceIn(0f, 1f))
                val target = (MIN_LEVEL + shaped * (1.0f - MIN_LEVEL))
                    .coerceIn(MIN_LEVEL, 1.0f)
                // ZERO-LATENCY ATTACK: snap upward immediately on beat hit
                if (target >= env[b]) {
                    env[b] = target
                }
            }
            return env.copyOf()
        }

        @Synchronized
        fun ingestWaveform(waveform: ByteArray, nowNanos: Long): FloatArray {
            val energy = computePcmTransientEnergy(waveform).coerceAtMost(2.0f)
            decayToNow(nowNanos)
            lastCaptureCallbackNanos = nowNanos

            if (energy <= 0.020f) {
                if (silentSinceNanos == 0L) {
                    silentSinceNanos = nowNanos
                }
                rollingPeak = max(0.36f, rollingPeak)
                if (nowNanos - silentSinceNanos > 120_000_000L) {
                    prevRaw.fill(0f)
                }
                return env.copyOf()
            }

            val wasSilent = (silentSinceNanos > 0L && nowNanos - silentSinceNanos > 180_000_000L) ||
                (lastSignalNanos > 0L && nowNanos - lastSignalNanos > 250_000_000L)
            silentSinceNanos = 0L
            lastSignalNanos = nowNanos

            if (wasSilent) {
                rollingPeak = max(0.42f, max(rollingPeak, energy))
            } else {
                rollingPeak = if (energy > rollingPeak) {
                    rollingPeak * 0.45f + energy * 0.55f
                } else {
                    max(0.18f, rollingPeak * 0.975f)
                }
            }
            val norm = (energy / rollingPeak.coerceAtLeast(0.18f)).coerceIn(0f, 1.35f)
            val shapedNorm = kotlin.math.sqrt(norm.coerceIn(0f, 1f))
            // Distribute crisp time-domain transient boost skewed slightly toward low/mid punch
            val weights = floatArrayOf(1.0f, 0.95f, 0.86f, 0.78f)
            for (b in 0..3) {
                val target = (MIN_LEVEL + shapedNorm * weights[b] * (1.0f - MIN_LEVEL))
                    .coerceIn(MIN_LEVEL, 1.0f)
                if (target > env[b]) {
                    env[b] = target
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
            // Crisp musical release (~9.2/s falloff -> tight bounce on fast drops)
            val decayFactor = exp(-9.2f * dtSec)
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
        private var attachedAtNanos = 0L
        private var lastStaleRefreshMs = 0L
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
                attachedAtNanos = SystemClock.elapsedRealtimeNanos()
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
     * - In Manual Speed + Force mode ([beatSyncMode] == false), scales speed by [speedMultiplier] and
     *   vertical bar punch/amplitude by [forceMultiplier] (`0.5f..2.2f`) so heavy drops reach full height.
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
        hasLiveSignal: Boolean = liveAudioBands != null && liveAudioBands.any { it > 0.20f },
        forceMultiplier: Float = 1.35f
    ): FloatArray {
        val force = forceMultiplier.coerceIn(0.5f, 2.2f)
        if (beatSyncMode && liveAudioBands != null && hasLiveSignal) {
            return FloatArray(4) { i ->
                val raw = (liveAudioBands.getOrNull(i) ?: MIN_LEVEL).coerceIn(MIN_LEVEL, 1.0f)
                val normalized = ((raw - MIN_LEVEL) / (1.0f - MIN_LEVEL)).coerceIn(0f, 1f)
                // Boost dynamic response with mild force headroom so beats visibly reach 1.0f
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
        val bpm = 96f + (absSeed % 54) // 96..149 BPM unique per track
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

            // Crisp beat kick pulse so peaks hit higher & drop tighter rather than hovering flat
            val beatPhase = ((if (beatSyncMode) timeSec else effectiveTime) * beatFreq + i * 0.14f) % 1f
            val kick = ((1f - beatPhase) * (1f - beatPhase) * (1f - beatPhase))
            val rawEnvelope = if (beatSyncMode) {
                (0.54f * harmonic + 0.46f * kick)
            } else {
                // Rescale smoothstep slightly above midpoint & blend punchy kick for full-range travel
                val smooth = (harmonic * harmonic * (3f - 2f * harmonic))
                (0.68f * smooth + 0.32f * kick)
            }.coerceIn(0f, 1f)

            // Apply selectable Wave Force: scales excursion above MIN_LEVEL and hits 1.0 cleanly at high force
            val scaledEnvelope = (rawEnvelope * (if (beatSyncMode) max(1.15f, force) else force))
                .coerceIn(0f, 1f)
            out[i] = (MIN_LEVEL + (1.0f - MIN_LEVEL) * scaledEnvelope).coerceIn(MIN_LEVEL, 1.0f)
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

        fun hasLiveFeed(): Boolean {
            val now = SystemClock.elapsedRealtimeNanos()
            return SharedAudioVisualizer.tracker.hasActiveCaptureFeed(now) ||
                SharedAudioVisualizer.tracker.hasRecentSignal(now)
        }

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
