// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqConfigTest {

    @Test
    fun flatGraphicEqHasNoPreampAndIsIdentity() {
        val c = PeqConfig.fromGraphicEq(true, List(10) { 0f })
        assertEquals(0.0, c.preampDb, 0.0)
        assertTrue(c.isIdentity)
    }

    @Test
    fun cutsOnlyNeedNoPreamp() {
        val c = PeqConfig.fromGraphicEq(true, listOf(0f, -3f, -6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(0.0, c.preampDb, 0.0)
        assertFalse(c.isIdentity)
    }

    @Test
    fun autoPreampCancelsTheLargestBoost() {
        val levels = listOf(6f, 6f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f)
        val c = PeqConfig.fromGraphicEq(true, levels)
        // Adjacent +6 dB bands overlap, so the peak is a bit above 6 dB.
        assertTrue("preamp ${c.preampDb}", c.preampDb <= -6.0 && c.preampDb > -9.0)

        // With the preamp applied, nothing in the audio band is boosted.
        val fs = PeqConfig.REFERENCE_SAMPLE_RATE
        for (f in listOf(20.0, 31.0, 45.0, 62.0, 90.0, 1000.0, 5000.0, 15000.0)) {
            assertTrue("f=$f", c.responseDb(f, fs) + c.preampDb <= 0.05)
        }
    }

    @Test
    fun parseLevelsPadsAndTolerantOfGarbage() {
        assertEquals(List(10) { 0f }, LahoDspController.parseLevels(""))
        assertEquals(
            listOf(1.5f, -2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            LahoDspController.parseLevels("1.5, -2,abc"),
        )
    }
}
