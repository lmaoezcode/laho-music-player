// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rec(
    id: String,
    title: String,
    artist: String,
    lengthMs: Long? = 200_000,
    releases: List<MbRelease> = emptyList(),
) = MbRecording(id, title, lengthMs, listOf(MbCredit(artist, "", "a-$artist")), "2010-05-01", releases, emptyList(), 100)

private fun release(
    id: String,
    status: String? = "Official",
    type: String? = "Album",
    secondary: List<String> = emptyList(),
    date: String? = "2010-05-01",
) = MbRelease(id, "T-$id", status, type, secondary, date, emptyList(), "rg-$id", 1, 1)

class MatchScorerTest {
    private val q = LookupQuery("Hãy Trao Cho Anh", "Sơn Tùng M-TP", 240_000)

    @Test
    fun exactMatchWithinToleranceIsHighConfidence() {
        val c = MatchScorer.candidate(q, rec("1", "Hay trao cho anh", "Son Tung M-TP", 242_900, listOf(release("r"))))
        assertTrue(c.confidence >= 0.97)
    }

    @Test
    fun durationBeyondThreeSecondsCapsConfidence() {
        val within = MatchScorer.candidate(q, rec("1", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 243_000, listOf(release("r"))))
        val beyond = MatchScorer.candidate(q, rec("1", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 243_001, listOf(release("r"))))
        assertTrue(within.confidence > 0.95)
        assertTrue(beyond.confidence <= MatchScorer.CAP_DURATION_MISMATCH)
        assertEquals(1.0, MatchScorer.durationScore(3_000), 0.0)
        assertEquals(0.0, MatchScorer.durationScore(20_000), 0.0)
    }

    @Test
    fun unknownDurationIsNeutral() {
        val c = MatchScorer.candidate(q.copy(durationMs = null), rec("1", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 1, listOf(release("r"))))
        assertTrue(c.confidence > 0.95)
    }

    @Test
    fun differentTitleOrArtistIsLow() {
        assertTrue(MatchScorer.candidate(q, rec("1", "Chúng Ta Của Hiện Tại", "Sơn Tùng M-TP")).confidence < 0.5)
        assertTrue(MatchScorer.candidate(q, rec("2", "Hãy Trao Cho Anh", "Someone Else")).confidence < 0.5)
    }

    @Test
    fun unknownArtistMatchesOnTitleButStaysBelowFullConfidence() {
        val c = MatchScorer.candidate(q.copy(artist = "<unknown>"), rec("1", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 240_500, listOf(release("r"))))
        assertEquals(MatchScorer.CAP_UNKNOWN_ARTIST, c.confidence, 1e-9)
    }

    @Test
    fun featuringAndBracketNoiseIsIgnored() {
        assertTrue(MatchScorer.titleSimilarity("Hãy Trao Cho Anh (Official Video)", "Hãy trao cho anh") > 0.9)
        assertTrue(MatchScorer.titleSimilarity("Song feat. Someone", "Song") > 0.9)
        assertTrue(MatchScorer.artistSimilarity("Snoop Dogg, Sơn Tùng M-TP", "Sơn Tùng M-TP") >= 0.9)
        assertTrue(MatchScorer.artistSimilarity("Sơn Tùng", "Sơn Tùng M-TP") >= 0.9)
    }

    @Test
    fun officialStudioAlbumIsPreferred() {
        val best = MatchScorer.bestRelease(
            listOf(
                release("bootleg", status = "Bootleg"),
                release("comp", secondary = listOf("Compilation"), date = "2005"),
                release("single", type = "Single", date = "2009"),
                release("album", date = "2011"),
                release("album-earlier", date = "2010"),
            ),
        )!!
        assertEquals("album-earlier", best.id) // both are plain official albums; earliest wins
        assertNull(MatchScorer.bestRelease(emptyList()))
    }

    @Test
    fun rankSortsAndDeduplicates() {
        val ranked = MatchScorer.rank(
            q,
            listOf(
                rec("a", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 300_000, listOf(release("r1"))), // other version
                rec("b", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 240_100, listOf(release("r2"))),
                rec("c", "Hãy Trao Cho Anh", "Sơn Tùng M-TP", 240_200, listOf(release("r2"))), // same title/artist/album as b
                rec("d", "Another", "Nobody", 240_000),
            ),
        )
        assertEquals(listOf("b", "a", "d"), ranked.map { it.recordingMbid })
        assertTrue(ranked[0].confidence > ranked[1].confidence)
    }

    @Test
    fun ambiguityNeedsAUserPickWhenTopTwoAreCloseButDifferent() {
        fun c(id: String, conf: Double, album: String, year: Int) = MatchScorer.candidate(
            q, rec(id, "x", "y"),
        ).copy(confidence = conf, album = album, year = year, recordingMbid = id)
        assertFalse(MatchScorer.isAmbiguous(listOf(c("a", 0.97, "A", 2010), c("b", 0.80, "B", 2012))))
        assertTrue(MatchScorer.isAmbiguous(listOf(c("a", 0.97, "A", 2010), c("b", 0.95, "B", 2012))))
        assertFalse(MatchScorer.isAmbiguous(listOf(c("a", 0.97, "A", 2010), c("b", 0.95, "A", 2010))))
        assertTrue(MatchScorer.isAmbiguous(listOf(c("a", 0.7, "A", 2010), c("b", 0.5, "B", 2012))))
        assertFalse(MatchScorer.isAmbiguous(emptyList()))
    }
}

class TagTextTest {
    @Test
    fun recognisesPlaceholders() {
        assertTrue(TagText.isUnknown(null))
        assertTrue(TagText.isUnknown("  "))
        assertTrue(TagText.isUnknown("<unknown>"))
        assertTrue(TagText.isUnknown("Unknown Artist"))
        assertFalse(TagText.isUnknown("Đen Vâu"))
    }

    @Test
    fun cleansTitles() {
        assertEquals("Lạc Trôi", TagText.cleanTitle("01 - Lạc Trôi (Official Music Video).mp3"))
        assertEquals("1979", TagText.cleanTitle("1979"))
        assertEquals("My Song", TagText.cleanTitle("My_Song [Lyric Video]"))
    }

    @Test
    fun splitsArtistFromTitleWhenArtistUnknown() {
        val q = LookupQuery("Đen - Hai Triệu Năm (Official)", "<unknown>", 0).normalized()
        assertEquals("Đen", q.artist)
        assertEquals("Hai Triệu Năm", q.title)
        assertNull(q.durationMs)
        // A known artist is never overridden.
        val k = LookupQuery("A - B", "Real", 1000).normalized()
        assertEquals("Real", k.artist)
        assertEquals("A - B", k.title)
    }
}
