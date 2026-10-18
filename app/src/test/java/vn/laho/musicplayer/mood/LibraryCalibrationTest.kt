// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCalibrationTest {

    /**
     * Raw features measured on the Xiaomi 11 Ultra test library (18 Oct 2026,
     * /mood export): id, bpm, rms dBFS, centroid Hz, flux.
     */
    private val measured = listOf(
        listOf("kho_bau", 172.24, -9.64, 3090.5, 38.08),
        listOf("day_di", 141.57, -16.87, 1748.2, 23.54),
        listOf("pho_hoa", 120.26, -13.73, 2932.6, 34.19),
        listOf("xuong_rong", 132.50, -18.84, 3125.3, 33.14),
        listOf("1001", 113.95, -19.31, 2377.9, 29.79),
        listOf("vai_cau_noi", 172.29, -15.90, 1769.5, 27.56),
        listOf("toi_khong_con", 134.20, -12.10, 1618.2, 28.37),
        listOf("senorita", 117.10, -10.93, 2889.7, 34.00),
        listOf("mua_thang_sau", 142.52, -11.62, 2143.9, 31.82),
        listOf("banh_mi_khong", 93.05, -18.71, 3049.9, 31.97),
    ).map { (id, bpm, rms, centroid, flux) ->
        MoodRecord(
            songId = id as String, title = id, artist = "x",
            bpm = bpm as Double, rmsDb = rms as Double, centroidHz = centroid as Double, flux = flux as Double,
            dynamicRangeDb = 10.0, lowEnergyRatio = 0.3, featureVersion = MoodRecord.FEATURE_VERSION,
        )
    }

    @Test
    fun midRankPercentiles() {
        val sorted = doubleArrayOf(1.0, 2.0, 2.0, 3.0)
        assertEquals(0.125, LibraryCalibration.rank(sorted, 1.0), 1e-12)
        assertEquals(0.5, LibraryCalibration.rank(sorted, 2.0), 1e-12)
        assertEquals(0.875, LibraryCalibration.rank(sorted, 3.0), 1e-12)
        assertEquals(0.0, LibraryCalibration.rank(sorted, 0.0), 1e-12)
        assertEquals(1.0, LibraryCalibration.rank(sorted, 9.0), 1e-12)
        assertEquals(0.5, LibraryCalibration.rank(DoubleArray(0), 5.0), 1e-12)
    }

    @Test
    fun calibrationSpreadsTheSaturatedRealLibrary() {
        val cal = assertNotNull(LibraryCalibration.from(measured)).let { LibraryCalibration.from(measured)!! }
        val arousal = measured.associate { it.songId to cal.arousal(it)!! }
        val spread = arousal.values.max() - arousal.values.min()
        assertTrue("spread=$spread $arousal", spread >= 0.5)
        assertEquals("kho_bau", arousal.maxByOrNull { it.value }!!.key)
        val calmest = arousal.entries.sortedBy { it.value }.take(3).map { it.key }
        assertTrue("calmest=$calmest", "1001" in calmest)
        arousal.values.forEach { assertTrue(it in 0.0..1.0) }
    }

    @Test
    fun recalibratedAbsoluteScaleNoLongerSaturates() {
        val abs = measured.map { it.features()!!.arousal() }
        assertTrue("abs=$abs", abs.max() - abs.min() > 0.3)
        assertTrue(abs.count { it > 0.9 } <= 1)
    }

    @Test
    fun smallLibrariesFallBackToTheAbsoluteScale() {
        assertNull(LibraryCalibration.from(measured.take(LibraryCalibration.MIN_SONGS - 1)))
        val r = measured.first()
        assertEquals(r.features()!!.arousal(), r.audioArousal(null)!!, 1e-12)
    }

    @Test
    fun indexUsesTheCalibration() = runBlocking {
        val store = InMemoryMoodStore().apply { upsert(measured) }
        val profiles = store.asIndex().profiles().associateBy { it.songId }
        val cal = LibraryCalibration.from(measured)!!
        val expected = cal.arousal(measured.first { it.songId == "1001" })!! * 2 - 1
        assertEquals(expected, profiles.getValue("1001").arousal!!, 1e-12)
    }
}
