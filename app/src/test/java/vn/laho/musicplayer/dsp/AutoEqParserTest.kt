// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqParserTest {

    private val sample = """
        Preamp: -6.2 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
        Filter 2: ON PK Fc 2000 Hz Gain -3.1 dB Q 1.41
        Filter 3: OFF HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
        Filter 4: ON PK Fc 4500.5 Hz Gain +1.25 dB Q 3
    """.trimIndent()

    @Test
    fun parsesPreampAndFilters() {
        val (config, warnings) = AutoEqParser.parse(sample)
        assertTrue(warnings.isEmpty())
        assertEquals(-6.2, config.preampDb, 1e-9)
        assertEquals(4, config.bands.size)

        val ls = config.bands[0]
        assertEquals(FilterType.LOW_SHELF, ls.type)
        assertEquals(105.0, ls.frequencyHz, 1e-9)
        assertEquals(6.0, ls.gainDb, 1e-9)
        assertEquals(0.70, ls.q, 1e-9)

        assertEquals(FilterType.PEAKING, config.bands[1].type)
        assertFalse("OFF filter is kept but disabled", config.bands[2].enabled)
        assertEquals(4500.5, config.bands[3].frequencyHz, 1e-9)
        assertEquals(1.25, config.bands[3].gainDb, 1e-9)
        assertEquals(3.0, config.bands[3].q, 1e-9)
    }

    @Test
    fun missingQDefaultsToButterworth() {
        val (config, _) = AutoEqParser.parse("Filter: ON LS Fc 100 Hz Gain 3 dB")
        assertEquals(PeqBand.DEFAULT_Q, config.bands.single().q, 1e-12)
    }

    @Test
    fun junkLinesProduceWarningsNotFailures() {
        val (config, warnings) = AutoEqParser.parse(
            """
            # comment
            Device: Some IEM
            Filter 1: ON XYZ Fc 100 Hz Gain 3 dB Q 1
            Filter 2: ON PK Fc 1000 Hz Gain 2 dB Q 1
            """.trimIndent(),
        )
        assertEquals(1, config.bands.size)
        assertEquals(2, warnings.size)
    }

    @Test
    fun parsedPresetDrivesTheProcessor() {
        val (config, _) = AutoEqParser.parse(sample)
        val p = PeqProcessor().apply { setConfig(config); configure(96_000, 2) }
        val samples = FloatArray(2048) { if (it % 2 == 0) 0.1f else -0.1f }
        p.process(samples, 1024)
        assertFalse(p.isBypassed)
    }
}
