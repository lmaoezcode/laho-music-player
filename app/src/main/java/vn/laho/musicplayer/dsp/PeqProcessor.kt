// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import kotlin.math.abs
import kotlin.math.pow

/**
 * Real-time parametric EQ working on interleaved 32-bit float samples.
 *
 * Filters run in transposed direct form II with double-precision coefficients and
 * state, so cascading many bands does not add audible rounding noise.
 *
 * Threading: [setConfig] may be called from any thread; the new settings are picked up
 * at the start of the next [process] call on the audio thread. [configure], [process]
 * and [reset] must be called from the audio thread.
 */
class PeqProcessor {

    @Volatile
    private var pendingConfig: PeqConfig? = null
    private var config: PeqConfig = PeqConfig.FLAT

    private var sampleRate = 0
    private var channelCount = 0

    private var coefficients: Array<BiquadCoefficients> = emptyArray()
    private var preampGain = 1.0
    private var bypass = true

    /** State per channel per band: [z1, z2]. Index = ((channel * bands) + band) * 2. */
    private var state = DoubleArray(0)

    /** True when the last applied config leaves audio untouched. */
    val isBypassed: Boolean get() = bypass

    fun setConfig(newConfig: PeqConfig) {
        pendingConfig = newConfig
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0 && channelCount > 0) { "Invalid format $sampleRate Hz / $channelCount ch" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        pendingConfig?.let { config = it; pendingConfig = null }
        rebuild(resetState = true)
    }

    /** Clears filter memory, e.g. after a seek. */
    fun reset() {
        state.fill(0.0)
    }

    /**
     * Processes [frameCount] interleaved frames in place.
     * The array must hold at least `frameCount * channelCount` samples.
     */
    fun process(samples: FloatArray, frameCount: Int) {
        check(sampleRate > 0) { "configure() must be called before process()" }
        pendingConfig?.let { newConfig ->
            pendingConfig = null
            val sameShape = newConfig.bands.size == config.bands.size
            config = newConfig
            rebuild(resetState = !sameShape)
        }
        if (bypass || frameCount <= 0) return

        val channels = channelCount
        val bandCount = coefficients.size
        val gain = preampGain

        for (ch in 0 until channels) {
            if (gain != 1.0) {
                var i = ch
                repeat(frameCount) {
                    samples[i] = (samples[i] * gain).toFloat()
                    i += channels
                }
            }
            for (band in 0 until bandCount) {
                val c = coefficients[band]
                if (c.isIdentity) continue
                val b0 = c.b0
                val b1 = c.b1
                val b2 = c.b2
                val a1 = c.a1
                val a2 = c.a2
                val s = ((ch * bandCount) + band) * 2
                var z1 = state[s]
                var z2 = state[s + 1]
                var i = ch
                repeat(frameCount) {
                    val x = samples[i].toDouble()
                    val y = b0 * x + z1
                    z1 = b1 * x - a1 * y + z2
                    z2 = b2 * x - a2 * y
                    samples[i] = y.toFloat()
                    i += channels
                }
                // Flush denormals once per block to keep the CPU cost stable in silence.
                state[s] = if (abs(z1) < DENORMAL_THRESHOLD) 0.0 else z1
                state[s + 1] = if (abs(z2) < DENORMAL_THRESHOLD) 0.0 else z2
            }
        }
    }

    private fun rebuild(resetState: Boolean) {
        if (sampleRate == 0) return
        val active = config.enabled
        preampGain = if (active) 10.0.pow(config.preampDb / 20.0) else 1.0
        coefficients = if (active) {
            Array(config.bands.size) { config.bands[it].coefficients(sampleRate) }
        } else {
            emptyArray()
        }
        bypass = config.isIdentity || (preampGain == 1.0 && coefficients.all { it.isIdentity })
        val needed = channelCount * coefficients.size * 2
        if (resetState || state.size != needed) state = DoubleArray(needed)
    }

    private companion object {
        const val DENORMAL_THRESHOLD = 1e-25
    }
}
