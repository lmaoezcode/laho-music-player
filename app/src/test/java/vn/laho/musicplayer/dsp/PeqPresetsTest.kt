// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqPresetsTest {

    private val hd600 = PeqPreset(
        "HD 600",
        PeqConfig(
            enabled = true,
            preampDb = -6.2,
            bands = listOf(
                PeqBand(FilterType.LOW_SHELF, 105.0, 6.0, 0.7),
                PeqBand(FilterType.PEAKING, 2000.0, -3.1, 1.41),
                PeqBand(FilterType.HIGH_SHELF, 10_000.0, -2.0, 0.7, enabled = false),
                PeqBand(FilterType.HIGH_PASS, 15.0, 0.0, 0.71),
            ),
        ),
    )
    private val flat = PeqPreset("Flat", PeqConfig(true, 0.0, listOf(PeqBand(FilterType.PEAKING, 1000.0))))
    private val graphic = PeqConfig.fromGraphicEq(true, List(10) { if (it == 0) 3f else 0f })

    // --- source selection ---

    @Test
    fun defaultStateKeepsGraphicEq() {
        assertSame(graphic, PeqSourceSelector.select(PeqPresetState.DEFAULT, graphic))
    }

    @Test
    fun presetSourceUsesActivePreset() {
        val s = PeqPresetState(PeqSource.PRESET, "HD 600", listOf(flat, hd600))
        assertEquals(hd600.config, PeqSourceSelector.select(s, graphic))
    }

    @Test
    fun graphicSourceIgnoresPresets() {
        val s = PeqPresetState(PeqSource.GRAPHIC_EQ, "HD 600", listOf(hd600))
        assertSame(graphic, PeqSourceSelector.select(s, graphic))
    }

    @Test
    fun presetSourceWithoutActivePresetFallsBackToGraphic() {
        val s = PeqPresetState(PeqSource.PRESET, null, emptyList())
        assertSame(graphic, PeqSourceSelector.select(s, graphic))
    }

    @Test
    fun flatPresetIsIdentity() {
        val s = PeqPresetState(PeqSource.PRESET, "Flat", listOf(flat))
        assertTrue(PeqSourceSelector.select(s, graphic).isIdentity)
    }

    // --- state operations ---

    @Test
    fun removingActivePresetFallsBackAndLastRemovalRestoresGraphic() {
        var s = PeqPresetState(PeqSource.PRESET, "HD 600", listOf(flat, hd600))
        s = s.remove("HD 600")
        assertEquals("Flat", s.activeName)
        assertEquals(PeqSource.PRESET, s.source)
        s = s.remove("Flat")
        assertNull(s.activeName)
        assertEquals(PeqSource.GRAPHIC_EQ, s.source)
    }

    @Test
    fun renameRejectsDuplicatesAndFollowsActive() {
        val s = PeqPresetState(PeqSource.PRESET, "HD 600", listOf(flat, hd600))
        assertSame(s, s.rename("HD 600", "Flat"))
        assertSame(s, s.rename("HD 600", "  "))
        val r = s.rename("HD 600", "HD 600 v2")
        assertEquals("HD 600 v2", r.activeName)
        assertEquals(hd600.config, r.activePreset?.config)
    }

    @Test
    fun uniqueNameAddsSuffix() {
        val s = PeqPresetState(presets = listOf(flat, flat.copy(name = "Flat (2)")))
        assertEquals("Flat (3)", s.uniqueName("Flat"))
        assertEquals("New", s.uniqueName("New"))
    }

    @Test
    fun updateActiveOnlyTouchesActivePreset() {
        val s = PeqPresetState(PeqSource.PRESET, "Flat", listOf(flat, hd600))
            .updateActive { it.copy(preampDb = -3.0) }
        assertEquals(-3.0, s.activePreset!!.config.preampDb, 0.0)
        assertEquals(hd600, s.presets[1])
    }

    // --- serialization ---

    @Test
    fun codecRoundTrips() {
        val s = PeqPresetState(PeqSource.PRESET, "HD 600", listOf(flat, hd600))
        assertEquals(s, PeqPresetCodec.decode(PeqPresetCodec.encode(s)))
    }

    @Test
    fun codecToleratesGarbage() {
        assertEquals(PeqPresetState.DEFAULT, PeqPresetCodec.decode("not json"))
        assertEquals(PeqPresetState.DEFAULT, PeqPresetCodec.decode(""))
        val text = """
            {"source":"BOGUS","active":"missing","presets":[
              {"name":"A","bands":[{"type":"NOPE","frequencyHz":100},{"type":"PEAKING","frequencyHz":-1},
                                   {"type":"PEAKING","frequencyHz":500,"gainDb":2}]},
              {"name":""},
              {"name":"A"}
            ]}
        """.trimIndent()
        val s = PeqPresetCodec.decode(text)
        assertEquals(PeqSource.GRAPHIC_EQ, s.source)
        assertEquals("A", s.activeName)
        assertEquals(1, s.presets.size)
        assertEquals(listOf(PeqBand(FilterType.PEAKING, 500.0, 2.0, PeqBand.DEFAULT_Q)), s.presets[0].config.bands)
    }

    @Test
    fun storePersistsAcrossInstances() {
        val dir = Files.createTempDirectory("peq").toFile()
        try {
            val file = File(dir, "presets.json")
            val store = PeqPresetStore(file, saveDelayMs = 60_000)
            assertEquals(PeqPresetState.DEFAULT, store.state.value)
            store.update { it.withPreset(hd600).activate("HD 600").copy(source = PeqSource.PRESET) }
            store.saveNow()
            val reloaded = PeqPresetStore(file).state.value
            assertEquals(PeqSource.PRESET, reloaded.source)
            assertEquals(hd600, reloaded.activePreset)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- AutoEQ import naming ---

    @Test
    fun presetNameFromAutoEqFile() {
        assertEquals("Sennheiser HD 600", AutoEqParser.presetNameFromFile("Sennheiser HD 600 ParametricEQ.txt"))
        assertEquals("custom", AutoEqParser.presetNameFromFile("custom.txt"))
        assertEquals("AutoEQ", AutoEqParser.presetNameFromFile(null))
        assertEquals("AutoEQ", AutoEqParser.presetNameFromFile("ParametricEQ.txt"))
    }
}
