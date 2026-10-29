// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.mood.MoodProfile
import vn.laho.musicplayer.suggest.ListenEvent
import vn.laho.musicplayer.suggest.ListeningHistory

class SuggestToolsTest {
    private fun ms(day: Int, hour: Int) = LocalDateTime.of(2026, 3, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val library = listOf(
        LibrarySong("calm", "Đêm Yên", "Ann", "A"),
        LibrarySong("bright", "Nắng Mới", "Bob", "B"),
        LibrarySong("intense", "Bão", "Cid", "C"),
        LibrarySong("plain", "Chưa Phân Tích", "Dan", "D"),
    )
    private val index = MoodIndex {
        listOf(
            MoodProfile("calm", 0.05, -0.7, setOf(Mood.CALM)),
            MoodProfile("bright", 0.6, 0.4, setOf(Mood.HAPPY)),
            MoodProfile("intense", -0.4, 0.9, setOf(Mood.INTENSE)),
        )
    }

    private fun history(vararg e: ListenEvent) = ListeningHistory { e.toList() }

    private fun tools(p: FakePlayer, now: Long, h: ListeningHistory? = null, idx: MoodIndex? = index) =
        suggestTools(p, idx, h, { now }, ZoneOffset.UTC).associateBy { it.name }

    @Test
    fun lateNightSuggestsCalmFirstAndPlaysIt() = runBlocking {
        val p = FakePlayer(library)
        val r = tools(p, ms(4, 23)).getValue("suggest_for_now").execute(mapOf("count" to 3))
        assertTrue(r.message, r.success)
        assertEquals("calm", p.queue.first().id)
        assertEquals(3, p.queue.size)
        assertEquals(3, r.data["count"])
        assertTrue(r.message.contains("đêm khuya"))
    }

    @Test
    fun morningSuggestsBrightFirst() = runBlocking {
        val p = FakePlayer(library)
        tools(p, ms(4, 8)).getValue("suggest_for_now").execute(mapOf("count" to 2))
        assertEquals("bright", p.queue.first().id)
    }

    @Test
    fun contextArgumentOverridesTheClock() = runBlocking {
        val p = FakePlayer(library)
        tools(p, ms(4, 8)).getValue("suggest_for_now").execute(mapOf("context" to "tối khuya"))
        assertEquals("calm", p.queue.first().id)
        tools(p, ms(4, 8)).getValue("suggest_for_now").execute(mapOf("context" to "buồn", "count" to 4))
        assertTrue(p.queue.isNotEmpty())
    }

    @Test
    fun historyChangesTheOrderAndRecentSongsAreDemoted() = runBlocking {
        val p = FakePlayer(library)
        val now = ms(10, 8)
        // bright just played 20 minutes ago: it should not lead again.
        val h = history(ListenEvent("bright", "Bob", now - 20 * 60_000, 200_000, 1.0))
        tools(p, now, h).getValue("suggest_for_now").execute(mapOf("count" to 4))
        assertFalse(p.queue.first().id == "bright")
    }

    @Test
    fun worksWithoutMoodIndexOrHistory() = runBlocking {
        val p = FakePlayer(library)
        val r = tools(p, ms(4, 8), h = null, idx = null).getValue("suggest_for_now").execute(emptyMap())
        assertTrue(r.message, r.success)
        assertEquals(library.size, p.queue.size)
    }

    @Test
    fun emptyLibraryFailsClearly() = runBlocking {
        val p = FakePlayer(emptyList())
        val r = tools(p, ms(4, 8)).getValue("daily_mix").execute(emptyMap())
        assertFalse(r.success)
        assertTrue(p.queue.isEmpty())
    }

    @Test
    fun dailyMixPlaysFavouritesAndIsStableForTheDay() = runBlocking {
        val many = (1..30).map { LibrarySong("s$it", "Bài $it", "Nghệ sĩ $it", "Album") }
        val h = history(*(1..8).map { ListenEvent("s5", "Nghệ sĩ 5", ms(3, 20) - it * 86_400_000L, 200_000, 1.0) }.toTypedArray())
        val p1 = FakePlayer(many)
        tools(p1, ms(10, 9), h).getValue("daily_mix").execute(mapOf("count" to 10))
        val p2 = FakePlayer(many)
        tools(p2, ms(10, 10), h).getValue("daily_mix").execute(mapOf("count" to 10))
        assertEquals(10, p1.queue.size)
        assertTrue(p1.queue.any { it.id == "s5" })
        assertEquals(p1.queue.map { it.id }, p2.queue.map { it.id })
    }

    @Test
    fun registryExposesAndRunsBothTools() = runBlocking {
        val p = FakePlayer(library)
        val registry = ToolRegistry.default(p, index, null, history = history())
        assertNotNull(registry["suggest_for_now"])
        assertNotNull(registry["daily_mix"])
        assertTrue(registry.execute(ToolCall("suggest_for_now", mapOf("count" to 2))).success)
        assertEquals(2, p.queue.size)
    }

    @Test
    fun offlinePhrasesMapToTheTools() {
        val parser = OfflineCommandParser()
        fun name(s: String) = parser.parse(s)?.name
        assertEquals("suggest_for_now", name("nhạc cho lúc này"))
        assertEquals("suggest_for_now", name("Gợi ý nhạc cho tôi"))
        assertEquals("suggest_for_now", name("nghe gì bây giờ"))
        assertEquals("daily_mix", name("daily mix"))
        assertEquals("daily_mix", name("mix hôm nay đi"))
        assertEquals("daily_mix", name("phát playlist hôm nay"))
        assertEquals(ToolCall("suggest_for_now", mapOf("context" to "buoi sang")), parser.parse("nhạc buổi sáng"))
        assertEquals("toi khuya", parser.parse("bật nhạc tối khuya")?.args?.get("context"))
        assertEquals(10, parser.parse("gợi ý 10 bài")?.args?.get("count"))
        // existing behaviour is untouched
        assertEquals("play_by_mood", name("bật nhạc buồn"))
        assertEquals("search_and_play", name("phát bài Lạc Trôi"))
    }
}
