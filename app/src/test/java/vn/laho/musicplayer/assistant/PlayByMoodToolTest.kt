// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.mood.MoodProfile

class PlayByMoodToolTest {

    private val library = (1..12).map { LibrarySong("$it", "Song $it", "Artist $it", "Album") }

    /** 1-4 sad/calm, 5-8 happy/energetic, 9-10 intense, 11 unknown valence, 12 not indexed. */
    private val profiles = listOf(
        MoodProfile("1", -0.8, -0.5, setOf(Mood.SAD)),
        MoodProfile("2", -0.6, -0.3, setOf(Mood.SAD)),
        MoodProfile("3", -0.7, -0.6, emptySet()),
        MoodProfile("4", -0.5, -0.4, setOf(Mood.SAD, Mood.CALM)),
        MoodProfile("5", 0.7, 0.8, setOf(Mood.ENERGETIC)),
        MoodProfile("6", 0.6, 0.9, setOf(Mood.ENERGETIC, Mood.HAPPY)),
        MoodProfile("7", 0.8, 0.5, setOf(Mood.HAPPY)),
        MoodProfile("8", 0.5, 0.7, emptySet()),
        MoodProfile("9", -0.4, 0.9, setOf(Mood.INTENSE)),
        MoodProfile("10", -0.3, 0.8, setOf(Mood.INTENSE)),
        MoodProfile("11", null, 0.9, emptySet()),
        MoodProfile("99", -0.8, -0.5, setOf(Mood.SAD)), // not in library anymore
    )

    private fun tool(p: FakePlayer, idx: List<MoodProfile> = profiles) =
        PlayByMoodTool(p, MoodIndex { idx }, Random(7))

    @Test
    fun sadRequestQueuesTheSadSongs() = runBlocking {
        val p = FakePlayer(library)
        val r = tool(p).execute(mapOf("mood" to "buon", "limit" to 4))
        assertTrue(r.message, r.success)
        assertEquals(setOf("1", "2", "3", "4"), p.queue.map { it.id }.toSet())
    }

    @Test
    fun energeticRequestAvoidsSadSongs() = runBlocking {
        val p = FakePlayer(library)
        tool(p).execute(mapOf("mood" to "soi_dong", "limit" to 3))
        assertTrue(p.queue.map { it.id }.all { it in setOf("5", "6", "8", "7") })
    }

    @Test
    fun explicitCoordinatesWork() = runBlocking {
        val p = FakePlayer(library)
        // sad but energetic → the "intense" corner
        tool(p).execute(mapOf("valence" to -40, "arousal" to 90, "limit" to 2))
        assertEquals(setOf("9", "10"), p.queue.map { it.id }.toSet())
    }

    @Test
    fun songsMissingFromLibraryAreNeverQueued() = runBlocking {
        val p = FakePlayer(library)
        tool(p).execute(mapOf("mood" to "buon", "limit" to 50))
        assertFalse(p.queue.any { it.id == "99" })
    }

    @Test
    fun emptyIndexAndBadArgumentsFailClearly() = runBlocking {
        val p = FakePlayer(library)
        val none = tool(p, emptyList()).execute(mapOf("mood" to "vui"))
        assertFalse(none.success)
        assertTrue(none.message.contains("/index"))
        assertFalse(tool(p).execute(emptyMap()).success)
        assertFalse(tool(p).execute(mapOf("mood" to "angry")).success)
    }

    @Test
    fun registeredOnlyWithAnIndex() {
        assertFalse(ToolRegistry.default(FakePlayer()).tools.any { it.name == "play_by_mood" })
        assertTrue(ToolRegistry.default(FakePlayer(), MoodIndex { emptyList() }).tools.any { it.name == "play_by_mood" })
    }
}
