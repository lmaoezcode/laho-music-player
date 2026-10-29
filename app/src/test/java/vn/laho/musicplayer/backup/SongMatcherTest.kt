// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatcherTest {
    private val library = listOf(
        song("a1", "Lạc Trôi", "Sơn Tùng M-TP", 245_000),
        song("a2", "Hello", "Adele", 295_000),
        song("a3", "Hello", "Adele", 180_000), // live / edit version
        song("a4", "Solo", "Nobody", 100_000),
    )

    @Test fun newDeviceMatchesByTitleArtistDespiteDifferentIdAndCase() {
        assertEquals("a1", SongMatcher.match(SongKey("lac troi", "son tung m-tp", 246_500), library)?.id)
    }

    @Test fun durationSeparatesVersions() {
        assertEquals("a3", SongMatcher.match(SongKey("Hello", "Adele", 181_000), library)?.id)
        assertEquals("a2", SongMatcher.match(SongKey("Hello", "Adele", 294_000), library)?.id)
        assertNull(SongMatcher.match(SongKey("Hello", "Adele", 240_000), library))
    }

    @Test fun missingDurationOnlyMatchesWhenUnambiguous() {
        assertEquals("a4", SongMatcher.match(SongKey("Solo", "Nobody", null), library)?.id)
        assertNull(SongMatcher.match(SongKey("Hello", "Adele", null), library))
    }

    @Test fun unknownSongIsNotMatched() {
        assertNull(SongMatcher.match(SongKey("Other", "Adele", 295_000), library))
    }
}
