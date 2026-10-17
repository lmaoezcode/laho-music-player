// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqProcessorTest {

    private fun noise(samples: Int, seed: Int = 42): FloatArray {
        val r = Random(seed)
        return FloatArray(samples) { r.nextFloat() * 1.6f - 0.8f }
    }

    private fun sine(freq: Double, fs: Int, frames: Int, channels: Int, amplitude: Double = 0.25): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * freq * i / fs)).toFloat()
            for (ch in 0 until channels) out[i * channels + ch] = v
        }
        return out
    }

    private fun rmsDb(samples: FloatArray, channel: Int, channels: Int, fromFrame: Int): Double {
        var sum = 0.0
        var n = 0
        var i = fromFrame * channels + channel
        while (i < samples.size) {
            sum += samples[i].toDouble() * samples[i]
            n++
            i += channels
        }
        return 20 * log10(sqrt(sum / n))
    }

    @Test
    fun nullTest_flatEqIsBitExact() {
        val flat = PeqConfig.fromGraphicEq(enabled = true, levelsDb = List(10) { 0f })
        assertTrue(flat.isIdentity)

        val p = PeqProcessor().apply { setConfig(flat); configure(96_000, 2) }
        val input = noise(96_000 * 2)
        val output = input.copyOf()
        p.process(output, output.size / 2)

        assertTrue(p.isBypassed)
        assertArrayEquals(input, output, 0f)
    }

    @Test
    fun nullTest_disabledEqIsBitExact() {
        val boosted = PeqConfig.fromGraphicEq(enabled = false, levelsDb = List(10) { 6f })
        val p = PeqProcessor().apply { setConfig(boosted); configure(44_100, 2) }
        val input = noise(4410 * 2)
        val output = input.copyOf()
        p.process(output, output.size / 2)
        assertArrayEquals(input, output, 0f)
    }

    @Test
    fun sineThroughPeakingBandGetsExpectedGain() {
        val fs = 48_000
        val config = PeqConfig(
            enabled = true,
            preampDb = 0.0,
            bands = listOf(PeqBand(FilterType.PEAKING, 1000.0, 6.0, 1.41)),
        )
        val p = PeqProcessor().apply { setConfig(config); configure(fs, 2) }
        val frames = fs // 1 s
        val dry = sine(1000.0, fs, frames, 2)
        val wet = dry.copyOf()
        p.process(wet, frames)

        val settle = fs / 10 // skip filter transient
        val gain = rmsDb(wet, 0, 2, settle) - rmsDb(dry, 0, 2, settle)
        assertEquals(6.0, gain, 0.1)
    }

    @Test
    fun preampIsApplied() {
        val config = PeqConfig(enabled = true, preampDb = -6.0206, bands = emptyList())
        val p = PeqProcessor().apply { setConfig(config); configure(48_000, 1) }
        val samples = floatArrayOf(0.5f, -0.5f, 1.0f)
        p.process(samples, 3)
        assertArrayEquals(floatArrayOf(0.25f, -0.25f, 0.5f), samples, 1e-4f)
    }

    @Test
    fun channelsAreProcessedIndependently() {
        val fs = 48_000
        val config = PeqConfig(true, 0.0, listOf(PeqBand(FilterType.PEAKING, 100.0, 12.0, 0.7)))
        val p = PeqProcessor().apply { setConfig(config); configure(fs, 2) }
        val frames = 4800
        val samples = sine(100.0, fs, frames, 2)
        for (i in 0 until frames) samples[i * 2 + 1] = 0f // right channel silent
        p.process(samples, frames)
        for (i in 0 until frames) assertEquals(0f, samples[i * 2 + 1], 0f)
    }

    @Test
    fun processingInBlocksMatchesOneShot() {
        val fs = 44_100
        val config = PeqConfig.fromGraphicEq(true, listOf(4f, 2f, 0f, -3f, 1f, 0f, 2.5f, -1f, 3f, -2f))
        val input = noise(8192 * 2)

        val oneShot = input.copyOf()
        PeqProcessor().apply { setConfig(config); configure(fs, 2) }.process(oneShot, 8192)

        val blocks = input.copyOf()
        val p = PeqProcessor().apply { setConfig(config); configure(fs, 2) }
        var frame = 0
        for (size in listOf(1, 7, 512, 1000, 4096, 2576)) {
            val chunk = blocks.copyOfRange(frame * 2, (frame + size) * 2)
            p.process(chunk, size)
            chunk.copyInto(blocks, frame * 2)
            frame += size
        }
        assertEquals(8192, frame)
        assertArrayEquals(oneShot, blocks, 0f)
    }

    @Test
    fun newConfigIsPickedUpOnNextBlock() {
        val p = PeqProcessor().apply { setConfig(PeqConfig.FLAT); configure(48_000, 1) }
        assertTrue(p.isBypassed)
        p.setConfig(PeqConfig(true, -6.0, emptyList()))
        val samples = floatArrayOf(1f)
        p.process(samples, 1)
        assertEquals(0.501f, samples[0], 0.001f)
    }
}
