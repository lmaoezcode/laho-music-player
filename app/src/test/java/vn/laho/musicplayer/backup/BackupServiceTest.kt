// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqSource
import vn.laho.musicplayer.metadata.MetadataSuggestion
import vn.laho.musicplayer.mood.IndexableSong

class BackupServiceTest {
    private val drive = FakeDrive()
    private var clock = 1_700_000_000_000L

    private fun service(peq: FakePeq, moods: MemMoods, sug: MemSuggestions, library: List<IndexableSong>) =
        BackupService(drive, peq, moods, sug, { library }, "5.5", now = { clock }, dispatcher = Dispatchers.Unconfined)

    private val oldLibrary = listOf(song("10", "Lạc Trôi", "Sơn Tùng M-TP", 245_000), song("11", "Hello", "Adele", 295_000))

    private fun backupFromOldDevice() = runBlocking {
        service(
            FakePeq(samplePeq),
            MemMoods(mood("10", "Lạc Trôi", "Sơn Tùng M-TP"), mood("99", "Gone", "Nobody")),
            MemSuggestions(MetadataSuggestion("11", "Hello", "Adele", "found", year = 2015, updatedAt = 7)),
            oldLibrary,
        ).backupNow()
    }

    @Test fun backupStoresDurationsFromTheLibrary() {
        val info = backupFromOldDevice()
        val byTitle = info.document.moods.associateBy { it.key.title }
        assertEquals(245_000L, byTitle.getValue("Lạc Trôi").key.durationMs)
        assertEquals(null, byTitle.getValue("Gone").key.durationMs)
        assertTrue(info.file.name.startsWith("laho-backup-"))
    }

    @Test fun onlyTheLastThreeBackupsAreKept() = runBlocking {
        val s = service(FakePeq(), MemMoods(), MemSuggestions(), oldLibrary)
        repeat(5) { clock += 1000; s.backupNow() }
        assertEquals(3, drive.files.size)
        assertEquals(2, drive.deleted.size)
        val names = s.list().map { it.name }
        assertEquals(names.sortedDescending(), names)
    }

    @Test fun restoreOnNewDeviceRemapsIdsAndSkipsUnmatched() = runBlocking {
        backupFromOldDevice()
        val newLibrary = listOf(song("500", "lac troi", "Son Tung M-TP", 246_000), song("501", "Hello", "Adele", 295_500))
        val peq = FakePeq()
        val moods = MemMoods()
        val sug = MemSuggestions()
        val s = service(peq, moods, sug, newLibrary)
        val report = s.restore(s.latest()!!.document)

        assertEquals(1, report.moodsMerged)
        assertEquals(1, report.moodsUnmatched)
        assertNotNull(moods.rows["500"])
        assertEquals("lac troi", moods.rows["500"]!!.title)
        assertEquals(2015, sug.rows["501"]!!.year)
        assertEquals(2, peq.state.presets.size)
        assertEquals(PeqSource.PRESET, peq.state.source)
        assertEquals("HD 600", peq.state.activeName)
        assertTrue(report.activeRestored)
    }

    @Test fun restoreMergesAndNeverOverwritesNewerLocalData() = runBlocking {
        backupFromOldDevice()
        val localPeq = PeqPresetState(PeqSource.PRESET, "Mine", listOf(preset("Mine", gain = 1.0), preset("HD 600", gain = -4.0)))
        val peq = FakePeq(localPeq)
        val moods = MemMoods(mood("10", "Lạc Trôi", "Sơn Tùng M-TP", updatedAt = 999, valence = -0.9))
        val s = service(peq, moods, MemSuggestions(), oldLibrary)
        val report = s.restore(s.latest()!!.document)

        assertEquals(-0.9, moods.rows["10"]!!.valence!!, 0.0) // newer local row kept
        assertEquals(1, report.moodsKeptLocal)
        assertEquals("Mine", peq.state.activeName) // local active choice kept
        assertEquals(setOf("Mine", "HD 600", "HD 600 (2)", "Flat"), peq.state.presets.map { it.name }.toSet())
        assertEquals(-4.0, peq.state.presets.first { it.name == "HD 600" }.config.bands[0].gainDb, 0.0)
    }

    @Test fun restoringTwiceAddsNothingNew() = runBlocking {
        backupFromOldDevice()
        val peq = FakePeq()
        val s = service(peq, MemMoods(), MemSuggestions(), oldLibrary)
        s.restore(s.latest()!!.document)
        val again = s.restore(s.latest()!!.document)
        assertEquals(0, again.presetsAdded)
        assertEquals(2, peq.state.presets.size)
    }

    @Test fun newerSchemaOnDriveIsRefused() = runBlocking {
        drive.upload(BackupService.fileName(1), """{"format":"laho-backup","schemaVersion":99}""".toByteArray(), "application/json")
        val s = service(FakePeq(), MemMoods(), MemSuggestions(), oldLibrary)
        try {
            s.latest()
            fail()
        } catch (e: BackupTooNewException) {
        }
    }
}
