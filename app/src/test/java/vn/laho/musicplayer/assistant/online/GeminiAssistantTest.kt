// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.TextPart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.FakePlayer
import vn.laho.musicplayer.assistant.LIBRARY
import vn.laho.musicplayer.assistant.ToolRegistry

/** Scripted model: returns the queued turns in order and records every request. */
private class ScriptedBackend(vararg turns: Content) : ModelBackend {
    private val queue = ArrayDeque(turns.toList())
    val requests = mutableListOf<Pair<String, List<Content>>>()
    var failNext: Exception? = null

    override suspend fun generate(systemInstruction: String, tools: ToolRegistry, history: List<Content>): Content {
        requests += systemInstruction to history
        failNext?.let { failNext = null; throw it }
        return queue.removeFirst()
    }

    fun enqueue(vararg turns: Content) = queue.addAll(turns)
}

private fun call(name: String, id: String, vararg args: Pair<String, JsonElement>) =
    Content("model", listOf(FunctionCallPart(name, mapOf(*args), id)))

private fun say(text: String) = Content("model", listOf(TextPart(text)))

private val GENRE_LIBRARY = LIBRARY.mapIndexed { i, s -> s.copy(genre = if (i % 2 == 0) "Ballad" else "V-Pop") }

/** Every function call must be answered by the next turn, with matching ids. */
private fun assertValidHistory(history: List<Content>) {
    assertEquals("must start with user text", "user", history.first().role)
    history.forEachIndexed { i, c ->
        val calls = c.parts.filterIsInstance<FunctionCallPart>()
        if (calls.isNotEmpty()) {
            val next = history.getOrNull(i + 1)
            requireNotNull(next) { "function call at $i has no response" }
            val responses = next.parts.filterIsInstance<FunctionResponsePart>()
            assertEquals(calls.map { it.id }, responses.map { it.id })
        }
    }
}

class GeminiAssistantTest {

    @Test
    fun moodRequestFinishesInOneModelRoundWithLibrarySummary() = runBlocking {
        val player = FakePlayer(GENRE_LIBRARY)
        val backend = ScriptedBackend(call("search_and_play", "c1", "genre" to JsonPrimitive("Ballad")))
        val assistant = GeminiAssistant(backend)

        val reply = assistant.handle("tôi đang buồn", ToolRegistry.default(player))

        assertEquals(AssistantReply.Source.ONLINE, reply.source)
        assertEquals(1, assistant.lastModelRounds)
        assertEquals(1, backend.requests.size)
        assertTrue(reply.text, reply.text.startsWith("Đang phát"))
        assertTrue(player.queue.all { it.genre == "Ballad" })
        val system = backend.requests.single().first
        assertTrue(system, "Thể loại: Ballad (4), V-Pop (3)" in system)
        assertTrue(system, "7 bài" in system)
    }

    @Test
    fun failedActionGoesBackToTheModel() = runBlocking {
        val player = FakePlayer(GENRE_LIBRARY)
        val backend = ScriptedBackend(
            call("search_and_play", "c1", "genre" to JsonPrimitive("Jazz")), // not in library
            call("search_and_play", "c2", "genre" to JsonPrimitive("Ballad")),
        )
        val assistant = GeminiAssistant(backend)
        val reply = assistant.handle("nhạc thư giãn", ToolRegistry.default(player))

        assertEquals(2, assistant.lastModelRounds)
        assertTrue(reply.result!!.success)
        assertValidHistory(backend.requests.last().second)
    }

    @Test
    fun informationalToolNeedsAnotherRound() = runBlocking {
        val backend = ScriptedBackend(
            call("get_library_overview", "c1"),
            say("Thư viện của bạn chủ yếu là Ballad."),
        )
        val assistant = GeminiAssistant(backend)
        val reply = assistant.handle("thư viện của tôi có gì", ToolRegistry.default(FakePlayer(GENRE_LIBRARY)))
        assertEquals(2, assistant.lastModelRounds)
        assertEquals("Thư viện của bạn chủ yếu là Ballad.", reply.text)
    }

    @Test
    fun unsupportedRequestIsAPlainAnswer() = runBlocking {
        val backend = ScriptedBackend(say("Tính năng tải từ Google Drive chưa được hỗ trợ."))
        val assistant = GeminiAssistant(backend)
        val reply = assistant.handle("tải nhạc từ drive", ToolRegistry.default(FakePlayer(LIBRARY)))
        assertNull(reply.toolCall)
        assertEquals(1, assistant.lastModelRounds)
    }

    @Test
    fun historyStaysValidAcrossEarlyReturnsAndSummaryIsCached() = runBlocking {
        var overviewCalls = 0
        val player = object : vn.laho.musicplayer.assistant.PlayerGateway by FakePlayer(GENRE_LIBRARY) {
            override suspend fun librarySongs() = GENRE_LIBRARY.also { overviewCalls++ }
        }
        val tools = ToolRegistry.default(player)
        val backend = ScriptedBackend(call("set_volume", "v1", "level" to JsonPrimitive(30)))
        val assistant = GeminiAssistant(backend)

        assistant.handle("nhỏ tiếng xuống", tools)
        backend.enqueue(call("playback_control", "p1", "action" to JsonPrimitive("pause")))
        assistant.handle("dừng lại một lát", tools)

        val secondHistory = backend.requests.last().second
        assertValidHistory(secondHistory)
        // user, model(call), user(response), model(synthetic text), user
        assertEquals(listOf("user", "model", "user", "model", "user"), secondHistory.map { it.role })
        assertTrue((secondHistory[3].parts.single() as TextPart).text.contains("30%"))
        assertEquals("library summary computed once", 1, overviewCalls)
    }

    @Test
    fun failureRollsBackTheUnfinishedTurn() = runBlocking {
        val backend = ScriptedBackend()
        backend.failNext = java.io.IOException("Unable to resolve host")
        val assistant = GeminiAssistant(backend)
        val tools = ToolRegistry.default(FakePlayer(LIBRARY))

        val failed = assistant.handle("bật nhạc vui", tools)
        assertEquals(AssistantReply.Source.NONE, failed.source)
        assertTrue(failed.text.contains("mạng"))

        backend.enqueue(say("ok"))
        assistant.handle("thử lại", tools)
        val history = backend.requests.last().second
        assertEquals("only the new message", 1, history.size)
        assertEquals("thử lại", (history.single().parts.single() as TextPart).text)
    }

    @Test
    fun markdownIsStrippedFromAnswers() = runBlocking {
        assertEquals(
            "Thư viện chỉ có nhạc Orchestral Pop (bài Over the Horizon của Samsung).",
            GeminiAssistant.plainText("Thư viện chỉ có nhạc *Orchestral Pop* (bài **Over the Horizon** của Samsung)."),
        )
        assertEquals("Gợi ý:\nBallad\nLo-fi", GeminiAssistant.plainText("## Gợi ý:\n- Ballad\n* Lo-fi"))
        assertEquals("Xem LaHo", GeminiAssistant.plainText("Xem [LaHo](https://example.com)"))
        assertEquals("DJ_Snake giữ nguyên", GeminiAssistant.plainText("DJ_Snake giữ nguyên"))

        val backend = ScriptedBackend(say("Chưa có nhạc *sôi động*, bạn muốn nghe `Ballad` không?"))
        val reply = GeminiAssistant(backend).handle("nhạc tiệc", ToolRegistry.default(FakePlayer(LIBRARY)))
        assertEquals("Chưa có nhạc sôi động, bạn muốn nghe Ballad không?", reply.text)
    }

    @Test
    fun defaultsToFlashLite() {
        assertEquals(GeminiAssistant.MODEL_FLASH_LITE, GeminiAssistant.DEFAULT_MODEL)
    }

    @Test
    fun moodRefusalCountsAsCorrectInBenchmark() = runBlocking {
        val bench = AssistantBenchmark(
            realGateway = FakePlayer(LIBRARY),
            backendFor = { ModelBackend { _, _, _ -> say("Thư viện chưa có nhạc sôi động, bạn muốn nghe Ballad không?") } },
            pauseBetweenMs = 0,
        )
        val party = AssistantBenchmark.DEFAULT_CASES.first { "buổi tiệc" in it.prompt }
        val (summary) = bench.run(listOf("m"), listOf(party))
        assertEquals(1, summary.correct)
    }

    @Test
    fun benchmarkScoresToolChoiceWithoutTouchingPlayback() = runBlocking {
        val real = FakePlayer(GENRE_LIBRARY)
        var t = 0L
        val bench = AssistantBenchmark(
            realGateway = real,
            backendFor = { model ->
                ModelBackend { _, _, history ->
                    t += if (model == "fast") 500_000_000L else 2_000_000_000L
                    val prompt = (history.last().parts.single() as TextPart).text
                    if ("Drive" in prompt) say("Chưa hỗ trợ.")
                    else call("search_and_play", "x", "genre" to JsonPrimitive("Ballad"))
                }
            },
            pauseBetweenMs = 0,
            clock = { t },
        )
        val cases = listOf(
            AssistantBenchmark.Case("tôi đang buồn", "search_and_play"),
            AssistantBenchmark.Case("giảm âm lượng", "set_volume"),
            AssistantBenchmark.Case("tải nhạc từ Google Drive", null),
        )
        val (fast, slow) = bench.run(listOf("fast", "slow"), cases)

        assertEquals(2, fast.correct) // volume case answered with the wrong tool
        assertEquals(500L, fast.medianMs)
        assertEquals(2000L, slow.medianMs)
        assertTrue(real.queue.isEmpty() && real.log.isEmpty()) // dry run
        assertFalse(AssistantBenchmark.format(listOf(fast, slow)).isBlank())
    }
}
