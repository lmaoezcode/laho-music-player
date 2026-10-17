// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** One parametric EQ band. */
data class PeqBand(
    val type: FilterType,
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = DEFAULT_Q,
    val enabled: Boolean = true,
) {
    /** True when this band changes the signal at all. */
    val isEffective: Boolean
        get() = enabled && when (type) {
            FilterType.PEAKING, FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> gainDb != 0.0
            FilterType.LOW_PASS, FilterType.HIGH_PASS -> true
        }

    fun coefficients(sampleRate: Int): BiquadCoefficients =
        if (!enabled) BiquadCoefficients.IDENTITY
        else BiquadCoefficients.design(type, frequencyHz, gainDb, q, sampleRate)

    companion object {
        /** Butterworth Q, used by AutoEQ for shelves and pass filters. */
        const val DEFAULT_Q = 0.7071067811865476
    }
}

/**
 * Complete parametric EQ settings.
 *
 * @param preampDb gain applied before the filters, normally negative to leave headroom
 *   for boosted bands so the float output does not clip.
 */
data class PeqConfig(
    val enabled: Boolean,
    val preampDb: Double,
    val bands: List<PeqBand>,
) {
    /** True when processing would leave the signal bit-for-bit unchanged. */
    val isIdentity: Boolean
        get() = !enabled || (preampDb == 0.0 && bands.none { it.isEffective })

    /** Combined magnitude response of all bands (without preamp) in dB. */
    fun responseDb(frequencyHz: Double, sampleRate: Int): Double =
        bands.sumOf { it.coefficients(sampleRate).magnitudeDb(frequencyHz, sampleRate) }

    /**
     * Highest boost of the combined response between 20 Hz and 20 kHz, sampled on a
     * log-spaced grid. Used to choose a preamp that avoids clipping.
     */
    fun maxResponseDb(sampleRate: Int = REFERENCE_SAMPLE_RATE, points: Int = 256): Double {
        if (bands.none { it.isEffective }) return 0.0
        val upper = minOf(20_000.0, sampleRate / 2.0 * 0.97)
        val logLo = ln(20.0)
        val logHi = ln(upper)
        var maxDb = Double.NEGATIVE_INFINITY
        for (i in 0 until points) {
            val f = exp(logLo + (logHi - logLo) * i / (points - 1))
            maxDb = max(maxDb, responseDb(f, sampleRate))
        }
        return maxDb
    }

    /** Returns a copy whose preamp exactly cancels the largest boost (never adds gain). */
    fun withAutoPreamp(sampleRate: Int = REFERENCE_SAMPLE_RATE): PeqConfig =
        copy(preampDb = -max(0.0, maxResponseDb(sampleRate)))

    companion object {
        const val REFERENCE_SAMPLE_RATE = 48_000

        val FLAT = PeqConfig(enabled = false, preampDb = 0.0, bands = emptyList())

        /** Centre frequencies of the 10-band graphic EQ shown in the app UI. */
        val GRAPHIC_EQ_FREQUENCIES = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        /** One-octave spacing → Q = √2 / (2^(1) − 1) ≈ 1.41. */
        const val GRAPHIC_EQ_Q = 1.4142135623730951

        /**
         * Builds a PEQ that reproduces the app's graphic EQ: one peaking filter per slider,
         * with automatic preamp so boosts cannot clip.
         */
        fun fromGraphicEq(
            enabled: Boolean,
            levelsDb: List<Float>,
            frequencies: List<Double> = GRAPHIC_EQ_FREQUENCIES,
        ): PeqConfig {
            val bands = levelsDb.zip(frequencies).map { (gain, freq) ->
                PeqBand(FilterType.PEAKING, freq, gain.toDouble(), GRAPHIC_EQ_Q)
            }
            return PeqConfig(enabled, 0.0, bands).withAutoPreamp()
        }
    }
}
