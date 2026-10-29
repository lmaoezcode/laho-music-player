// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodProfile

internal val UTC = ZoneOffset.UTC

/** 2026-03-04 is a Wednesday; 2026-03-07 a Saturday. */
internal fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 3, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

internal fun song(id: String, artist: String = "Artist $id", v: Double? = null, a: Double? = null, vararg moods: Mood) =
    SuggestCandidate(id, "Song $id", artist, if (v == null && a == null) null else MoodProfile(id, v, a, moods.toSet()))

/** A full listen (fraction 1) or, with [fraction] small, a skip. */
internal fun listen(id: String, artist: String, whenMs: Long, fraction: Double = 1.0) =
    ListenEvent(id, artist, whenMs, (fraction * 200_000).toLong(), fraction)

class SuggestionEngineTest {
    private val engine = SuggestionEngine()

    private val calm = song("calm", v = 0.05, a = -0.7, moods = arrayOf(Mood.CALM))
    private val bright = song("bright", v = 0.6, a = 0.4, moods = arrayOf(Mood.HAPPY))
    private val intense = song("intense", v = -0.4, a = 0.9, moods = arrayOf(Mood.INTENSE))
    private val library = listOf(calm, bright, intense)

    private fun top(ctx: SuggestContext, now: Long, history: List<ListenEvent> = emptyList(), pool: List<SuggestCandidate> = library) =
        engine.rank(pool, history, ctx, now, 3, UTC).map { it.song.id }

    @Test
    fun coldStartFollowsTimeOfDay() {
        // No history at all: morning wants bright, late night wants calm.
        assertEquals("bright", top(SuggestContext.at(at(4, 8), UTC), at(4, 8)).first())
        assertEquals("calm", top(SuggestContext.at(at(4, 23), UTC), at(4, 23)).first())
        assertEquals("calm", top(SuggestContext.at(at(5, 2), UTC), at(5, 2)).first())
    }

    @Test
    fun coldStartKeepsUnanalysedSongsInTheList() {
        val unknown = song("unknown")
        val ranked = top(SuggestContext.at(at(4, 8), UTC), at(4, 8), pool = listOf(unknown, calm, bright))
        assertEquals("bright", ranked.first())
        assertEquals(setOf("unknown", "calm", "bright"), ranked.toSet())
    }

    @Test
    fun hourBucketsCoverTheWholeDay() {
        assertEquals(TimeBucket.MORNING, TimeBucket.ofHour(5))
        assertEquals(TimeBucket.MORNING, TimeBucket.ofHour(10))
        assertEquals(TimeBucket.AFTERNOON, TimeBucket.ofHour(11))
        assertEquals(TimeBucket.EVENING, TimeBucket.ofHour(17))
        assertEquals(TimeBucket.LATE_NIGHT, TimeBucket.ofHour(22))
        assertEquals(TimeBucket.LATE_NIGHT, TimeBucket.ofHour(0))
        assertEquals(TimeBucket.LATE_NIGHT, TimeBucket.ofHour(4))
    }

    @Test
    fun weekendShiftsTheTargetUp() {
        val weekday = SuggestContext(TimeBucket.EVENING, weekend = false).priorTarget()
        val weekend = SuggestContext(TimeBucket.EVENING, weekend = true).priorTarget()
        assertTrue(weekend.first > weekday.first && weekend.second > weekday.second)
    }

    @Test
    fun songHeardRecentlyDropsBelowAnEquallyGoodFreshOne() {
        val a = song("a", "Ann", 0.5, 0.3)
        val b = song("b", "Bob", 0.5, 0.3)
        val now = at(4, 9)
        val history = listOf(listen("a", "Ann", now - 30 * 60_000))
        assertEquals("b", top(SuggestContext.at(now, UTC), now, history, listOf(a, b)).first())
    }

    @Test
    fun recencyPenaltyFadesAfterADay() {
        val a = song("a", "Ann", 0.5, 0.3)
        val b = song("b", "Bob", 0.5, 0.3)
        val now = at(5, 9)
        val soon = engine.rank(listOf(a, b), listOf(listen("a", "Ann", now - 30 * 60_000)), SuggestContext.at(now, UTC), now, 2, UTC)
        val later = engine.rank(listOf(a, b), listOf(listen("a", "Ann", now - 30 * 3_600_000L)), SuggestContext.at(now, UTC), now, 2, UTC)
        assertTrue(soon.first { it.song.id == "a" }.parts.recency > 0.8)
        assertTrue(later.first { it.song.id == "a" }.parts.recency < 0.05)
        // a day later the favourite wins again
        assertEquals("a", later.first().song.id)
    }

    @Test
    fun skippedSongsAreDemotedAndLikedOnesPromoted() {
        val liked = song("liked", "Ann", 0.5, 0.3)
        val skipped = song("skipped", "Bob", 0.5, 0.3)
        val neutral = song("neutral", "Cid", 0.5, 0.3)
        val now = at(10, 9)
        val history = (1..3).flatMap { d ->
            listOf(listen("liked", "Ann", at(10 - d, 20)), listen("skipped", "Bob", at(10 - d, 20, 5), fraction = 0.05))
        }
        val ranked = top(SuggestContext.at(now, UTC), now, history, listOf(skipped, neutral, liked))
        assertEquals(listOf("liked", "neutral", "skipped"), ranked)
    }

    @Test
    fun skipsOfOneArtistLowerTheirOtherSongs() {
        val same = song("same", "Bob", 0.5, 0.3)
        val control = song("control", "Cid", 0.5, 0.3)
        val now = at(10, 9)
        val history = (1..4).map { listen("other", "Bob", at(9, 20, it), fraction = 0.02) }
        val ranked = top(SuggestContext.at(now, UTC), now, history, listOf(same, control))
        assertEquals("control", ranked.first())
    }

    @Test
    fun eventsWithoutLengthUseTheThirtySecondRule() {
        assertTrue(ListenEvent("x", "a", 0, 10_000, null).isSkip)
        assertTrue(!ListenEvent("x", "a", 0, 90_000, null).isSkip)
        assertTrue(ListenEvent("x", "a", 0, 100_000, 0.2).isSkip)
        assertTrue(!ListenEvent("x", "a", 0, 100_000, 0.8).isSkip)
    }

    @Test
    fun affinityInThisPartOfTheDayBeatsTheSameSongElsewhere() {
        val a = song("a", "Ann", 0.2, 0.0)
        val b = song("b", "Bob", 0.2, 0.0)
        // a is only ever played at night, b only in the morning (both well in the past)
        val history = (1..6).flatMap { d ->
            listOf(listen("a", "Ann", at(4 - 0, 23) - d * 86_400_000L), listen("b", "Bob", at(4, 8) - d * 86_400_000L))
        }
        val night = at(10, 23)
        val morning = at(10, 8)
        assertEquals("a", top(SuggestContext.at(night, UTC), night, history, listOf(a, b)).first())
        assertEquals("b", top(SuggestContext.at(morning, UTC), morning, history, listOf(a, b)).first())
    }

    @Test
    fun learnedMoodOfABucketMovesTheTarget() {
        // The user actually listens to intense music late at night, unlike the calm prior.
        val history = (1..8).map { listen("intense", "Ivy", at(4, 23) - it * 86_400_000L) }
        val now = at(12, 23)
        val cold = engine.rank(library, emptyList(), SuggestContext.at(now, UTC), now, 3, UTC).first { it.song.id == "intense" }
        val learned = engine.rank(library, history, SuggestContext.at(now, UTC), now, 3, UTC).first { it.song.id == "intense" }
        assertTrue(learned.parts.mood > cold.parts.mood)
    }

    @Test
    fun explicitMoodOverridesTheClockAndHistory() {
        val focus = song("focus", v = 0.05, a = -0.3, moods = arrayOf(Mood.FOCUS))
        val pool = listOf(focus, intense, bright)
        val now = at(4, 8)
        val history = (1..6).map { listen("intense", "Artist intense", now - it * 3_600_000L * 30) }
        val ctx = SuggestContext.parse("tập trung", now, UTC)
        assertEquals(Mood.FOCUS, ctx.mood)
        assertEquals("focus", top(ctx, now, history, pool).first())
    }

    @Test
    fun contextTextIsParsed() {
        val now = at(4, 15)
        assertEquals(TimeBucket.MORNING, SuggestContext.parse("buổi sáng", now, UTC).bucket)
        assertEquals(TimeBucket.LATE_NIGHT, SuggestContext.parse("tối khuya", now, UTC).bucket)
        assertEquals(TimeBucket.EVENING, SuggestContext.parse("buổi tối", now, UTC).bucket)
        assertEquals(Mood.SAD, SuggestContext.parse("buồn", now, UTC).mood)
        val blank = SuggestContext.parse("  ", now, UTC)
        assertEquals(TimeBucket.AFTERNOON, blank.bucket)
        assertTrue(!blank.explicit)
    }

    @Test
    fun noMoreThanThreePerArtistWhileThereAreAlternatives() {
        val same = (1..6).map { song("s$it", "Same", 0.6, 0.4) }
        val others = (1..3).map { song("o$it", "Other $it", 0.0, 0.0) }
        val ranked = engine.rank(same + others, emptyList(), SuggestContext.at(at(4, 8), UTC), at(4, 8), 6, UTC)
        assertEquals(3, ranked.count { it.song.artist == "Same" })
        assertEquals(6, ranked.size)
    }

    @Test
    fun futureEventsAreIgnored() {
        val a = song("a", "Ann", 0.5, 0.3)
        val b = song("b", "Bob", 0.5, 0.3)
        val now = at(4, 9)
        val peek = (1..5).map { listen("a", "Ann", now + it * 3_600_000L) }
        val withFuture = engine.rank(listOf(a, b), peek, SuggestContext.at(now, UTC), now, 2, UTC)
        val without = engine.rank(listOf(a, b), emptyList(), SuggestContext.at(now, UTC), now, 2, UTC)
        assertEquals(without.map { it.score }, withFuture.map { it.score })
    }

    @Test
    fun dailyMixIsStableWithinADayAndFavoursFavourites() {
        val pool = (1..30).map { song("s$it", "Artist $it", (it % 10) / 10.0, ((it * 3) % 10) / 10.0) }
        val history = (1..10).flatMap { d -> listOf(listen("s7", "Artist 7", at(4, 20) - d * 86_400_000L), listen("s9", "Artist 9", at(4, 21) - d * 86_400_000L)) }
        val a = engine.dailyMix(pool, history, at(10, 9), 10, UTC).map { it.song.id }
        val b = engine.dailyMix(pool, history, at(10, 10), 10, UTC).map { it.song.id }
        assertEquals(a, b) // same day and bucket, same seed
        assertTrue("s7" in a && "s9" in a)
    }
}
