// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {

    private val sampleRates = listOf(44_100, 48_000, 96_000, 192_000)

    @Test
    fun peakingFilterHitsTargetGainAtCentreFrequency() {
        for (fs in sampleRates) {
            for (gain in listOf(-12.0, -6.0, -1.5, 3.0, 6.0, 12.0)) {
                for (f0 in listOf(31.0, 1000.0, 8000.0, 16000.0)) {
                    val c = BiquadCoefficients.design(FilterType.PEAKING, f0, gain, 1.41, fs)
                    assertEquals("fs=$fs f0=$f0 gain=$gain", gain, c.magnitudeDb(f0, fs), 0.1)
                }
            }
        }
    }

    @Test
    fun peakingFilterIsTransparentFarFromCentre() {
        val c = BiquadCoefficients.design(FilterType.PEAKING, 1000.0, 6.0, 1.41, 48_000)
        assertEquals(0.0, c.magnitudeDb(20.0, 48_000), 0.1)
        assertEquals(0.0, c.magnitudeDb(20_000.0, 48_000), 0.2)
    }

    @Test
    fun shelvesReachGainOnTheirSide() {
        val fs = 96_000
        val low = BiquadCoefficients.design(FilterType.LOW_SHELF, 105.0, 6.0, PeqBand.DEFAULT_Q, fs)
        assertEquals(6.0, low.magnitudeDb(10.0, fs), 0.1)
        assertEquals(0.0, low.magnitudeDb(10_000.0, fs), 0.1)

        val high = BiquadCoefficients.design(FilterType.HIGH_SHELF, 10_000.0, -4.0, PeqBand.DEFAULT_Q, fs)
        assertEquals(-4.0, high.magnitudeDb(40_000.0, fs), 0.15)
        assertEquals(0.0, high.magnitudeDb(100.0, fs), 0.1)
    }

    @Test
    fun passFiltersAreMinus3dbAtCutoff() {
        val fs = 48_000
        val lp = BiquadCoefficients.design(FilterType.LOW_PASS, 5000.0, 0.0, PeqBand.DEFAULT_Q, fs)
        assertEquals(-3.01, lp.magnitudeDb(5000.0, fs), 0.05)
        assertEquals(0.0, lp.magnitudeDb(20.0, fs), 0.05)

        val hp = BiquadCoefficients.design(FilterType.HIGH_PASS, 80.0, 0.0, PeqBand.DEFAULT_Q, fs)
        assertEquals(-3.01, hp.magnitudeDb(80.0, fs), 0.05)
        assertEquals(0.0, hp.magnitudeDb(10_000.0, fs), 0.05)
    }

    @Test
    fun zeroGainAndUnrealisableBandsAreIdentity() {
        assertTrue(BiquadCoefficients.design(FilterType.PEAKING, 1000.0, 0.0, 1.0, 48_000).isIdentity)
        // 16 kHz cannot be realised at 22.05 kHz (Nyquist 11.025 kHz)
        assertTrue(BiquadCoefficients.design(FilterType.PEAKING, 16_000.0, 6.0, 1.0, 22_050).isIdentity)
    }
}
