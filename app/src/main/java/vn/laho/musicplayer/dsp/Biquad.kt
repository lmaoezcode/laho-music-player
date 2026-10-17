// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes supported by the parametric EQ (same set as AutoEQ / Equalizer APO). */
enum class FilterType {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
    LOW_PASS,
    HIGH_PASS,
}

/**
 * Normalised biquad coefficients (a0 == 1).
 *
 * Transfer function: H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    val isIdentity: Boolean
        get() = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0

    /** Magnitude response in dB at [frequencyHz] for the given [sampleRate]. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2 * w)
        val sin2 = sin(2 * w)
        // e^{-jw} = cos w - j sin w
        val numRe = b0 + b1 * cos1 + b2 * cos2
        val numIm = -(b1 * sin1 + b2 * sin2)
        val denRe = 1.0 + a1 * cos1 + a2 * cos2
        val denIm = -(a1 * sin1 + a2 * sin2)
        val mag = hypot(numRe, numIm) / hypot(denRe, denIm)
        return 20.0 * log10(mag)
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Designs a biquad using the formulas from Robert Bristow-Johnson's
         * "Audio EQ Cookbook".
         *
         * Bands at or above ~Nyquist cannot be realised and return [IDENTITY].
         */
        fun design(
            type: FilterType,
            frequencyHz: Double,
            gainDb: Double,
            q: Double,
            sampleRate: Int,
        ): BiquadCoefficients {
            require(sampleRate > 0) { "sampleRate must be > 0" }
            val nyquist = sampleRate / 2.0
            if (frequencyHz <= 0.0 || frequencyHz >= nyquist * 0.98 || q <= 0.0) return IDENTITY
            val needsGain = type == FilterType.PEAKING ||
                type == FilterType.LOW_SHELF ||
                type == FilterType.HIGH_SHELF
            if (needsGain && gainDb == 0.0) return IDENTITY

            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequencyHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)

            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (type) {
                FilterType.PEAKING -> {
                    b0 = 1 + alpha * a
                    b1 = -2 * cosW
                    b2 = 1 - alpha * a
                    a0 = 1 + alpha / a
                    a1 = -2 * cosW
                    a2 = 1 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val twoSqrtAAlpha = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                    b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
                    a1 = -2 * ((a - 1) + (a + 1) * cosW)
                    a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
                }
                FilterType.HIGH_SHELF -> {
                    val twoSqrtAAlpha = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                    b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
                    a1 = 2 * ((a - 1) - (a + 1) * cosW)
                    a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
                }
                FilterType.LOW_PASS -> {
                    b0 = (1 - cosW) / 2
                    b1 = 1 - cosW
                    b2 = (1 - cosW) / 2
                    a0 = 1 + alpha
                    a1 = -2 * cosW
                    a2 = 1 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1 + cosW) / 2
                    b1 = -(1 + cosW)
                    b2 = (1 + cosW) / 2
                    a0 = 1 + alpha
                    a1 = -2 * cosW
                    a2 = 1 - alpha
                }
            }
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
