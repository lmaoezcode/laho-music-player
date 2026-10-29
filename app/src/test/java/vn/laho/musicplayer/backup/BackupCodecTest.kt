// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.laho.musicplayer.metadata.MetadataSuggestion

class BackupCodecTest {
    private fun doc(version: Int = BackupCodec.SCHEMA_VERSION) = BackupDocument(
        version, "5.5 test", 1_700_000_000_000, samplePeq,
        listOf(BackupMood(SongKey("Lạc Trôi", "Sơn Tùng M-TP", 245_000), mood("77", "Lạc Trôi", "Sơn Tùng M-TP"))),
        listOf(
            BackupSuggestion(
                SongKey("Hello", "Adele", null),
                MetadataSuggestion("9", "Hello", "Adele", "found", "MusicBrainz", 0.9, year = 2015, genre = "Pop", updatedAt = 5),
            ),
        ),
    )

    @Test fun roundTripKeepsEverything() {
        val back = BackupCodec.decode(BackupCodec.encode(doc()))
        assertEquals(doc(), back)
        assertNull(back.suggestions[0].key.durationMs)
    }

    @Test fun newerVersionIsRefusedWithClearMessage() {
        val bytes = BackupCodec.encode(doc(BackupCodec.SCHEMA_VERSION + 1))
        try {
            BackupCodec.decode(bytes)
            fail()
        } catch (e: BackupTooNewException) {
            assertTrue(e.message!!.contains("mới hơn"))
        }
    }

    @Test fun garbageAndForeignJsonAreRefused() {
        for (text in listOf("not json", "{}", """{"format":"other","schemaVersion":1}""", """{"format":"laho-backup"}""")) {
            try {
                BackupCodec.decode(text.toByteArray())
                fail(text)
            } catch (e: BackupFormatException) {
            }
        }
    }

    @Test fun tooManyRecordsAreRefused() {
        val rows = (0..BackupLimits.MAX_MOODS).joinToString(",") { """{"title":"t$it","artist":"a"}""" }
        val text = """{"format":"laho-backup","schemaVersion":1,"moods":[$rows]}"""
        try {
            BackupCodec.decode(text.toByteArray())
            fail()
        } catch (e: BackupFormatException) {
        }
    }

    @Test fun oversizedFileIsRefused() {
        try {
            BackupCodec.decode(ByteArray(BackupLimits.MAX_BYTES + 1))
            fail()
        } catch (e: BackupFormatException) {
        }
    }

    @Test fun badRowsAreSkippedAndTextIsClamped() {
        val long = "x".repeat(5000)
        val text = """{"format":"laho-backup","schemaVersion":1,"moods":[{"artist":"no title"},{"title":"$long","artist":"a","valence":"NaN"}]}"""
        val d = BackupCodec.decode(text.toByteArray())
        assertEquals(1, d.moods.size)
        assertEquals(BackupLimits.MAX_TEXT, d.moods[0].key.title.length)
    }

    @Test fun fileHasNoSecretsOrPaths() {
        val text = String(BackupCodec.encode(doc()))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("uri", ignoreCase = true))
    }
}
