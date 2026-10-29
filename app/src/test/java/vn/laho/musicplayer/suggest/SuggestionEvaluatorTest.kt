// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEvaluatorTest {
    private val library = (0 until 40).map { song("s$it", "Artist ${it % 8}", (it % 10) / 10.0, ((it * 7) % 10) / 10.0) }

    /** Mornings: songs s0-s4; evenings: s20-s24; 30 days; every listen complete. */
    private val habit: List<ListenEvent> = (1..30).flatMap { d ->
        val morning = (0..2).map { listen("s${(d + it) % 5}", "Artist ${(d + it) % 5 % 8}", at(1, 8, it * 5) + d * 86_400_000L) }
        val evening = (0..2).map { listen("s${20 + (d + it) % 5}", "Artist ${(20 + (d + it) % 5) % 8}", at(1, 20, it * 5) + d * 86_400_000L) }
        morning + evening
    }

    @Test
    fun replayBeatsRandomAndPopularityOnAHabitualListener() {
        val rows = SuggestionEvaluator.replay(library, habit, UTC, ks = listOf(5), minHistory = 20)
        val s = SuggestionEvaluator.summarize(rows).associateBy { it.method }
        assertTrue("sessions replayed", s.getValue("engine").sessions > 20)
        assertTrue("engine ${s.getValue("engine")}", s.getValue("engine").hitRate > 0.9)
        assertTrue(s.getValue("engine").hitRate > s.getValue("random").hitRate)
        assertTrue(s.getValue("engine").recall > s.getValue("mood_time").recall)
    }

    @Test
    fun sessionsWithTooLittleHistoryAreSkippedAndSkipsAreNotTruth() {
        assertTrue(SuggestionEvaluator.replay(library, habit.take(10), UTC, minHistory = 20).isEmpty())
        val onlySkips = habit.map { it.copy(fraction = 0.02, listenedMs = 4_000) }
        assertTrue(SuggestionEvaluator.replay(library, onlySkips, UTC, minHistory = 5).isEmpty())
    }

    @Test
    fun replayNeverUsesTheSessionItself() {
        // Songs that were never played before the first evaluable session cannot be "predicted" from themselves.
        val novel = habit + (0..4).map { listen("s39", "Artist 7", at(1, 8) + 40 * 86_400_000L + it * 60_000) }
        val rows = SuggestionEvaluator.replay(library, novel, UTC, ks = listOf(1), minHistory = 20)
            .filter { it.method == "engine" && it.date == java.time.LocalDate.of(2026, 4, 10) }
        assertTrue(rows.isNotEmpty())
        assertEquals(0, rows.first().hits)
    }

    @Test
    fun csvHasHeaderAndEscapesCommas() {
        val csv = SuggestionEvaluator.suggestionsCsv(
            listOf(SuggestContext(TimeBucket.MORNING, false) to SuggestionEngine().rank(listOf(song("a", "Ann, Bob", 0.1, 0.1)), emptyList(), SuggestContext(TimeBucket.MORNING, false), at(4, 8), 1, UTC)),
        )
        val lines = csv.lines()
        assertEquals(SuggestionEvaluator.SUGGEST_HEADER, lines.first())
        assertTrue(lines[1].contains("\"Ann, Bob\""))
        assertEquals(SuggestionEvaluator.HISTORY_HEADER, SuggestionEvaluator.historyCsv(habit, UTC).lines().first())
        assertEquals(SuggestionEvaluator.REPLAY_HEADER, SuggestionEvaluator.replayCsv(emptyList()))
    }
}
