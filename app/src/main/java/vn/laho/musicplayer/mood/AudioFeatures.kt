// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Signal-level descriptors of a track excerpt, computed fully offline. */
data class AudioFeatures(
    /** Estimated tempo in beats per minute (60..180), 0 if no clear pulse. */
    val bpm: Double,
    /** Mean frame loudness in dBFS (RMS). */
    val rmsDb: Double,
    /** Loud-minus-quiet spread: 95th - 10th percentile of frame dB. */
    val dynamicRangeDb: Double,
    /** Mean spectral centroid ("brightness") in Hz. */
    val spectralCentroidHz: Double,
    /** Mean positive spectral flux on log magnitudes (how busy / percussive). */
    val spectralFlux: Double,
    /** Share of spectral energy below 150 Hz (bass weight). */
    val lowEnergyRatio: Double,
    /** Seconds of audio analysed. */
    val analysedSeconds: Double,
) {
    /**
     * Heuristic arousal (calm 0 … energetic 1) from loudness, tempo, brightness and
     * onset density. Valence (sad/happy) is not reliably measurable from these features;
     * it comes from the LLM classifier.
     */
    fun arousal(): Double {
        val energy = norm(rmsDb, -30.0, -8.0)
        val tempo = if (bpm <= 0) 0.4 else norm(bpm, 70.0, 150.0)
        val bright = norm(spectralCentroidHz, 800.0, 3500.0)
        val busy = norm(spectralFlux, 0.5, 4.0)
        return (0.35 * energy + 0.25 * tempo + 0.2 * bright + 0.2 * busy).coerceIn(0.0, 1.0)
    }

    private fun norm(x: Double, lo: Double, hi: Double) = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
}

/**
 * Short-time analysis of a mono PCM excerpt: Hann-windowed 2048-point FFT frames with
 * 50% overlap. Tempo is the dominant periodicity (60-180 BPM) of the onset envelope,
 * weighted towards ~120 BPM to reduce half/double-tempo errors.
 */
object AudioFeatureExtractor {

    const val FRAME = 2048
    const val HOP = 1024
    private const val SILENCE_DB = -70.0
    private const val PULSE_CLARITY = 3.0

    fun extract(mono: FloatArray, sampleRate: Int): AudioFeatures {
        require(sampleRate > 0)
        val frames = (mono.size - FRAME) / HOP + 1
        if (frames < 8) return AudioFeatures(0.0, SILENCE_DB, 0.0, 0.0, 0.0, 0.0, mono.size.toDouble() / sampleRate)

        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val bins = FRAME / 2
        val prevLog = DoubleArray(bins)
        val binHz = sampleRate.toDouble() / FRAME
        val lowBin = (150.0 / binHz).toInt().coerceAtLeast(1)

        val frameDb = DoubleArray(frames)
        val onset = DoubleArray(frames)
        var centroidSum = 0.0
        var centroidCount = 0
        var lowEnergy = 0.0
        var totalEnergy = 0.0

        for (f in 0 until frames) {
            val start = f * HOP
            var sumSq = 0.0
            for (i in 0 until FRAME) {
                val x = mono[start + i].toDouble()
                sumSq += x * x
                re[i] = x * window[i]
                im[i] = 0.0
            }
            val rms = sqrt(sumSq / FRAME)
            frameDb[f] = if (rms > 1e-7) 20 * log10(rms) else SILENCE_DB

            Fft.transform(re, im)
            var magSum = 0.0
            var weighted = 0.0
            var flux = 0.0
            for (k in 1 until bins) {
                val power = re[k] * re[k] + im[k] * im[k]
                val mag = sqrt(power)
                magSum += mag
                weighted += mag * k * binHz
                totalEnergy += power
                if (k < lowBin) lowEnergy += power
                val logMag = ln(1.0 + 100.0 * mag)
                if (f > 0) flux += max(0.0, logMag - prevLog[k])
                prevLog[k] = logMag
            }
            onset[f] = flux / bins
            if (magSum > 1e-9 && frameDb[f] > -50.0) {
                centroidSum += weighted / magSum
                centroidCount++
            }
        }

        val audible = frameDb.filter { it > SILENCE_DB + 1 }.sorted()
        val meanDb = if (audible.isEmpty()) SILENCE_DB else audible.average()
        val dynamicRange = if (audible.size < 4) 0.0 else percentile(audible, 0.95) - percentile(audible, 0.10)

        return AudioFeatures(
            bpm = estimateTempo(onset, sampleRate.toDouble() / HOP),
            rmsDb = meanDb,
            dynamicRangeDb = dynamicRange,
            spectralCentroidHz = if (centroidCount == 0) 0.0 else centroidSum / centroidCount,
            spectralFlux = onset.average() * 100.0,
            lowEnergyRatio = if (totalEnergy <= 0) 0.0 else lowEnergy / totalEnergy,
            analysedSeconds = mono.size.toDouble() / sampleRate,
        )
    }

    /** Dominant beat period of the onset envelope, in BPM (0 when there is no pulse). */
    internal fun estimateTempo(onset: DoubleArray, framesPerSecond: Double): Double {
        val n = onset.size
        // Pulse clarity gate: real beats make onset peaks stand far above the typical
        // frame. Steady tones only show tiny, periodic leakage changes (false "tempo").
        val sorted = onset.sorted()
        val median = sorted[n / 2]
        val peak = sorted[(n * 0.98).toInt().coerceAtMost(n - 1)]
        if (peak <= 0.0 || (median > 0.0 && peak / median < PULSE_CLARITY)) return 0.0
        val mean = onset.average()
        val env = DoubleArray(n) { onset[it] - mean }
        var energy = 0.0
        for (v in env) energy += v * v
        if (energy <= 1e-12) return 0.0

        val minLag = max(1, (framesPerSecond * 60.0 / 180.0).toInt())
        val maxLag = min(n - 1, (framesPerSecond * 60.0 / 60.0).toInt() + 1)
        var bestLag = -1
        var bestScore = 0.0
        val acf = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            if (lag >= n) break
            var s = 0.0
            for (i in 0 until n - lag) s += env[i] * env[i + lag]
            acf[lag] = s / energy
        }
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * framesPerSecond / lag
            // Log-normal preference centred on 120 BPM (octave errors are the classic failure).
            val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.45).let { it * it })
            // Parabolic peak interpolation would refine bpm; the coarse lag is enough here.
            val score = acf[lag] * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || acf[bestLag] < 0.05) return 0.0
        // Refine with parabolic interpolation around the peak.
        val y0 = acf[bestLag - 1].takeIf { bestLag - 1 >= minLag } ?: acf[bestLag]
        val y1 = acf[bestLag]
        val y2 = acf[bestLag + 1]
        val denom = y0 - 2 * y1 + y2
        val offset = if (denom == 0.0) 0.0 else (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
        return 60.0 * framesPerSecond / (bestLag + offset)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val idx = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lo = idx.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }
}

/** In-place iterative radix-2 complex FFT. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
