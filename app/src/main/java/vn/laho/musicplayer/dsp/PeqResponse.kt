// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import kotlin.math.exp
import kotlin.math.ln

/**
 * Frequency-response helpers for the PEQ editor graph. Pure Kotlin so it can be unit-tested
 * and reused for exporting a response curve.
 */
object PeqResponse {

    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    /** [points] frequencies spaced evenly on a log axis between [lo] and [hi] (inclusive). */
    fun logFrequencies(points: Int, lo: Double = MIN_HZ, hi: Double = MAX_HZ): DoubleArray {
        require(points >= 2) { "points must be >= 2" }
        require(lo > 0.0 && hi > lo) { "invalid range $lo..$hi" }
        val logLo = ln(lo)
        val logHi = ln(hi)
        return DoubleArray(points) { i -> exp(logLo + (logHi - logLo) * i / (points - 1)) }
    }

    /** Position of [frequencyHz] on a log axis from [lo] to [hi], as 0..1 (clamped). */
    fun fractionOf(frequencyHz: Double, lo: Double = MIN_HZ, hi: Double = MAX_HZ): Double =
        (ln(frequencyHz.coerceIn(lo, hi) / lo) / ln(hi / lo))

    /** Inverse of [fractionOf]. */
    fun frequencyAt(fraction: Double, lo: Double = MIN_HZ, hi: Double = MAX_HZ): Double =
        lo * exp(ln(hi / lo) * fraction.coerceIn(0.0, 1.0))

    /**
     * Combined magnitude response in dB at each of [frequencies], computed from the actual
     * [Biquad] coefficients the DSP would use at [sampleRate].
     *
     * The config's master `enabled` flag is ignored (the editor shows the shape even while
     * the EQ is off); disabled bands contribute nothing.
     */
    fun curveDb(
        config: PeqConfig,
        frequencies: DoubleArray,
        sampleRate: Int = PeqConfig.REFERENCE_SAMPLE_RATE,
        includePreamp: Boolean = true,
    ): DoubleArray {
        val coefficients = config.bands.map { it.coefficients(sampleRate) }.filterNot { it.isIdentity }
        val offset = if (includePreamp) config.preampDb else 0.0
        return DoubleArray(frequencies.size) { i ->
            var db = offset
            for (c in coefficients) db += c.magnitudeDb(frequencies[i], sampleRate)
            db
        }
    }
}
