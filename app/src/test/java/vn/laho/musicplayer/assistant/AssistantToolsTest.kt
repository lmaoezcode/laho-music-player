// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePlayer(var library: List<LibrarySong> = emptyList()) : PlayerGateway {
    val log = mutableListOf<String>()
    var volume = 50
    var queue: List<LibrarySong> = emptyList()
    var playing = false

    override suspend fun play() { log += "play"; playing = true }
    override suspend fun pause() { log += "pause"; playing = false }
    override suspend fun next() { log += "next" }
    override suspend fun previous() { log += "previous" }
    override suspend fun volumePercent() = volume
    override suspend fun setVolumePercent(percent: Int) { volume = percent }
    override suspend fun librarySongs() = library
    override suspend fun playSongs(songs: List<LibrarySong>, startIndex: Int) {
        queue = songs; playing = true; log += "playSongs:${songs[startIndex].title}"
    }
    override suspend fun nowPlaying() = queue.firstOrNull()?.let { NowPlaying(it.title, it.artist, playing) }
}

val LIBRARY = listOf(
    LibrarySong("1", "Lạc Trôi", "Sơn Tùng M-TP", "m-tp M-TP"),
    LibrarySong("2", "Nơi Này Có Anh", "Sơn Tùng M-TP", "Nơi Này Có Anh"),
    LibrarySong("3", "Lạc Trôi (Remix)", "DJ X", "Remixes"),
    LibrarySong("4", "Em Đi", "Ngọt", "3"),
    LibrarySong("5", "Về Nhà", "Ngọt", "Gieo"),
    LibrarySong("6", "Đi Về Nhà", "Đen Vâu", "Đi Về Nhà"),
    LibrarySong("7", "Shape of You", "Ed Sheeran", "÷"),
)

class AssistantToolsTest {

    private fun registry(player: FakePlayer) = ToolRegistry.default(player)

    @Test
    fun playbackControl() = runBlocking {
        val p = FakePlayer()
        val r = registry(p)
        for (a in PlaybackControlTool.ACTIONS) assertTrue(r.execute(ToolCall("playback_control", mapOf("action" to a))).success)
        assertEquals(listOf("play", "pause", "next", "previous"), p.log)
        assertFalse(r.execute(ToolCall("playback_control", mapOf("action" to "dance"))).success)
    }

    @Test
    fun volumeIsClampedAndAcceptsJsonNumbers() = runBlocking {
        val p = FakePlayer().apply { volume = 95 }
        val r = registry(p)
        r.execute(ToolCall("set_volume", mapOf("delta" to 10)))
        assertEquals(100, p.volume)
        r.execute(ToolCall("set_volume", mapOf("level" to 30.0))) // LLM JSON numbers are doubles
        assertEquals(30, p.volume)
        r.execute(ToolCall("set_volume", mapOf("delta" to "-50")))
        assertEquals(0, p.volume)
        assertFalse(r.execute(ToolCall("set_volume")).success)
    }

    @Test
    fun exactTitleBeatsPartialMatch() {
        val result = LibrarySearch.search(LIBRARY, "lac troi")
        assertEquals(listOf("Lạc Trôi", "Lạc Trôi (Remix)"), result.map { it.title })
    }

    @Test
    fun searchByArtistOnly() {
        assertEquals(setOf("1", "2"), LibrarySearch.search(LIBRARY, null, "son tung").map { it.id }.toSet())
    }

    @Test
    fun allQueryWordsMustMatch() {
        assertTrue(LibrarySearch.search(LIBRARY, "Lạc Trôi Ngọt").isEmpty())
        assertEquals(listOf("6"), LibrarySearch.search(LIBRARY, "đi về nhà đen").map { it.id })
    }

    @Test
    fun searchAndPlayPlaysBestMatchFirst() = runBlocking {
        val p = FakePlayer(LIBRARY)
        val result = registry(p).execute(ToolCall("search_and_play", mapOf("query" to "Lạc Trôi")))
        assertTrue(result.success)
        assertEquals("Lạc Trôi", p.queue.first().title)
        assertEquals(2, p.queue.size)
    }

    @Test
    fun trailingFillerIsDroppedOnlyWhenNeeded() = runBlocking {
        val p = FakePlayer(LIBRARY)
        val r = registry(p)
        // "Em Đi" exists → keep "Đi".
        r.execute(ToolCall("search_and_play", mapOf("query" to "Em Đi")))
        assertEquals("Em Đi", p.queue.first().title)
        // "Nơi Này Có Anh đi" does not → retried as "Nơi Này Có Anh".
        val res = r.execute(ToolCall("search_and_play", mapOf("query" to "Nơi Này Có Anh đi")))
        assertTrue(res.message, res.success)
        assertEquals("Nơi Này Có Anh", p.queue.first().title)
    }

    @Test
    fun searchFailures() = runBlocking {
        assertFalse(registry(FakePlayer()).execute(ToolCall("search_and_play", mapOf("query" to "x"))).success)
        val r = registry(FakePlayer(LIBRARY))
        assertFalse(r.execute(ToolCall("search_and_play", mapOf("query" to "không có bài này"))).success)
        assertFalse(r.execute(ToolCall("search_and_play")).success)
    }

    @Test
    fun unknownToolAndExceptionsBecomeFailures() = runBlocking {
        val broken = object : PlayerGateway by FakePlayer() {
            override suspend fun next() = error("controller disconnected")
        }
        val r = ToolRegistry.default(broken)
        assertFalse(r.execute(ToolCall("fly")).success)
        val res = r.execute(ToolCall("playback_control", mapOf("action" to "next")))
        assertFalse(res.success)
        assertTrue(res.message.contains("controller disconnected"))
    }
}

class AssistantEngineTest {

    @Test
    fun endToEndOfflineCommands() = runBlocking {
        val p = FakePlayer(LIBRARY).apply { volume = 40 }
        val engine = AssistantEngine(ToolRegistry.default(p))

        engine.handle("Phát nhạc của Sơn Tùng M-TP nhé")
        assertEquals(setOf("Lạc Trôi", "Nơi Này Có Anh"), p.queue.map { it.title }.toSet())

        engine.handle("to lên")
        assertEquals(50, p.volume)

        engine.handle("bài tiếp")
        assertEquals("next", p.log.last())

        val now = engine.handle("bài gì đây?")
        assertEquals(AssistantReply.Source.OFFLINE, now.source)
        assertTrue(now.text, now.text.contains("Sơn Tùng M-TP"))
    }

    @Test
    fun unknownTextFallsBackToOnlineOrHelp() = runBlocking {
        val p = FakePlayer()
        val offlineOnly = AssistantEngine(ToolRegistry.default(p))
        assertEquals(AssistantReply.Source.NONE, offlineOnly.handle("tôi đang buồn").source)

        var asked: String? = null
        val withOnline = AssistantEngine(ToolRegistry.default(p), online = { text, _ ->
            asked = text
            AssistantReply("ok", AssistantReply.Source.ONLINE)
        })
        assertEquals(AssistantReply.Source.ONLINE, withOnline.handle("tôi đang buồn").source)
        assertEquals("tôi đang buồn", asked)
        // Fixed commands never hit the network.
        withOnline.handle("tạm dừng")
        assertEquals("tôi đang buồn", asked)
    }
}
