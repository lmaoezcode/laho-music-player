// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PeqResponseTest {

    @Test
    fun logFrequenciesSpanRangeEvenlyOnLogAxis() {
        val f = PeqResponse.logFrequencies(4, 10.0, 10_000.0)
        assertEquals(10.0, f[0], 1e-9)
        assertEquals(100.0, f[1], 1e-6)
        assertEquals(1000.0, f[2], 1e-6)
        assertEquals(10_000.0, f[3], 1e-6)
    }

    @Test
    fun fractionAndFrequencyAreInverse() {
        for (hz in listOf(20.0, 63.0, 1000.0, 12_345.0, 20_000.0)) {
            assertEquals(hz, PeqResponse.frequencyAt(PeqResponse.fractionOf(hz)), hz * 1e-9)
        }
        assertEquals(0.0, PeqResponse.fractionOf(5.0), 0.0)
        assertEquals(1.0, PeqResponse.fractionOf(30_000.0), 0.0)
    }

    @Test
    fun flatConfigIsZeroEverywhere() {
        val config = PeqConfig(true, 0.0, listOf(PeqBand(FilterType.PEAKING, 1000.0, 0.0, 1.0)))
        val curve = PeqResponse.curveDb(config, PeqResponse.logFrequencies(64))
        assertTrue(curve.all { it == 0.0 })
    }

    @Test
    fun peakingBandHitsItsGainAtCentreAndPreampShiftsCurve() {
        val config = PeqConfig(true, -6.0, listOf(PeqBand(FilterType.PEAKING, 1000.0, 6.0, 1.41)))
        val freqs = doubleArrayOf(20.0, 1000.0, 20_000.0)
        val bands = PeqResponse.curveDb(config, freqs, includePreamp = false)
        val total = PeqResponse.curveDb(config, freqs, includePreamp = true)
        assertEquals(6.0, bands[1], 0.01)
        assertTrue(abs(bands[0]) < 0.1)
        for (i in freqs.indices) assertEquals(bands[i] - 6.0, total[i], 1e-9)
    }

    @Test
    fun curveMatchesSumOfBiquadMagnitudes() {
        val config = PeqConfig(
            true, 0.0,
            listOf(
                PeqBand(FilterType.LOW_SHELF, 105.0, 5.5, 0.7),
                PeqBand(FilterType.PEAKING, 3000.0, -3.2, 2.0),
                PeqBand(FilterType.HIGH_SHELF, 10_000.0, -2.0, 0.7, enabled = false),
            ),
        )
        val freqs = PeqResponse.logFrequencies(32)
        val curve = PeqResponse.curveDb(config, freqs, sampleRate = 44_100)
        for (i in freqs.indices) {
            assertEquals(config.responseDb(freqs[i], 44_100), curve[i], 1e-9)
        }
    }

    @Test
    fun masterDisabledStillShowsShape() {
        val config = PeqConfig(false, 0.0, listOf(PeqBand(FilterType.PEAKING, 1000.0, 4.0, 1.0)))
        assertEquals(4.0, PeqResponse.curveDb(config, doubleArrayOf(1000.0))[0], 0.01)
    }
}
