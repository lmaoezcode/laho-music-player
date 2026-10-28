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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.AgentRig
import vn.laho.musicplayer.assistant.AssistantEngine
import vn.laho.musicplayer.assistant.ToolRegistry

/** Scripted model: returns the queued turns in order and records every request. */
private class Script(vararg turns: Content) : ModelBackend {
    private val queue = ArrayDeque(turns.toList())
    val requests = mutableListOf<List<Content>>()
    override suspend fun generate(systemInstruction: String, tools: ToolRegistry, history: List<Content>): Content {
        requests += history
        return queue.removeFirst()
    }
}

private class Call(val name: String, val id: String, val args: Map<String, JsonElement>)

private fun c(name: String, id: String, vararg args: Pair<String, JsonElement>) = Call(name, id, mapOf(*args))

private fun calls(vararg c: Call) = Content("model", c.map { FunctionCallPart(it.name, it.args, it.id) })

private fun say(text: String) = Content("model", listOf(TextPart(text)))
private fun s(v: String) = JsonPrimitive(v)

private fun assertValid(history: List<Content>) {
    history.forEachIndexed { i, content ->
        val fc = content.parts.filterIsInstance<FunctionCallPart>()
        if (fc.isNotEmpty()) {
            val next = history.getOrNull(i + 1) ?: error("call at $i unanswered")
            assertEquals(fc.map { it.id }, next.parts.filterIsInstance<FunctionResponsePart>().map { it.id })
        }
    }
}

class GeminiAgentTest {

    @Test fun chainedActionsRunInOrderAcrossRounds() = runBlocking {
        val rig = AgentRig()
        val backend = Script(
            calls(
                c("plan_steps", "p", "steps" to s("Tạo playlist|Phát ngẫu nhiên")),
                c("create_playlist", "a", "mood" to s("buon"), "limit" to JsonPrimitive(2)),
            ),
            calls(c("play_playlist", "b", "shuffle" to s("true"))),
        )
        val g = GeminiAssistant(backend)
        val reply = g.handle("nhạc buồn thành playlist rồi mở lên", rig.registry, rig.listener)

        assertEquals(2, g.lastModelRounds)
        assertEquals(1, rig.playlists.stored.size)
        assertTrue(rig.player.log.single().startsWith("playSongs"))
        assertEquals(2, reply.steps)
        assertEquals(
            listOf("1/2 Tạo playlist…", "✓ 1/2 Tạo playlist", "2/2 Phát ngẫu nhiên playlist…", "✓ 2/2 Phát ngẫu nhiên playlist"),
            rig.progress,
        )
        assertTrue(reply.text.contains("Đang phát"))
    }

    @Test fun hintOfAChainStopsTheEarlyReturnEvenWithoutPlan() = runBlocking {
        val rig = AgentRig()
        val backend = Script(
            calls(c("set_volume", "a", "level" to JsonPrimitive(20))),
            calls(c("playback_control", "b", "action" to s("pause"))),
        )
        val g = GeminiAssistant(backend)
        g.handle("nhỏ tiếng rồi dừng nhạc", rig.registry)
        assertEquals(2, g.lastModelRounds)
        assertEquals(20, rig.player.volume)
        assertEquals(listOf("pause"), rig.player.log)
    }

    @Test fun stepCapStopsAtSix() = runBlocking {
        val rig = AgentRig()
        val eight = (1..8).map { c("playback_control", "n$it", "action" to s("next")) }
        val backend = Script(calls(c("plan_steps", "p", "steps" to s("a|b|c|d|e|f|g|h")), *eight.toTypedArray()))
        val reply = GeminiAssistant(backend).handle("bấm next rồi next rồi next", rig.registry)

        assertEquals(6, rig.player.log.count { it == "next" })
        assertTrue(reply.text, "tối đa 6 bước" in reply.text)
    }

    @Test fun downloadPausesAndModelCannotSelfConfirm() = runBlocking {
        val rig = AgentRig(drive = true)
        val backend = Script(
            calls(c("plan_steps", "p", "steps" to s("Tìm|Tải|Phát")), c("drive_search", "a", "query" to s("Lạc Trôi"))),
            // The model (or an injected file name) tries to pre-confirm: must be ignored.
            calls(c("drive_download", "b", "file_id" to s("1"), "confirmed" to s("true"))),
            calls(c("play_downloaded", "c")),
        )
        val g = GeminiAssistant(backend)

        val paused = g.handle("tìm Lạc Trôi trên drive rồi tải về rồi phát nó", rig.registry, rig.listener)
        assertNotNull(paused.pending)
        assertTrue(paused.text, paused.text.startsWith("Bước 2/3:") && paused.text.endsWith("(có/không)"))
        assertTrue(rig.driveFiles.downloads.isEmpty())

        val done = paused.pending!!.resume(true, rig.listener)
        assertEquals(listOf("aaaaaaaaaa1"), rig.driveFiles.downloads)
        assertEquals(1, rig.queue.played.size)
        assertEquals(3, done.steps)
        assertValid(backend.requests.last())
    }

    @Test fun onlineResumeAfterYesAndNo() = runBlocking {
        for (answer in listOf("có", "không")) {
            val rig = AgentRig(drive = true)
            val backend = Script(
                calls(c("drive_search", "a", "query" to s("Lạc Trôi"))),
                calls(c("drive_download", "b", "file_id" to s("1"), "confirmed" to s("true"))),
                say("Xong rồi."),
            )
            val g = GeminiAssistant(backend)
            // Engine with no offline match: the sentence is not a command.
            val e = AssistantEngine(rig.registry, online = g)
            val paused = e.handle("bạn ơi giúp mình lấy bài Lạc Trôi trong kho đám mây về máy", rig.listener)
            assertNotNull(paused.pending)
            assertTrue(rig.driveFiles.downloads.isEmpty())

            val reply = e.handle(answer, rig.listener)
            if (answer == "có") {
                assertEquals(listOf("aaaaaaaaaa1"), rig.driveFiles.downloads)
            } else {
                assertTrue(rig.driveFiles.downloads.isEmpty())
                assertTrue(reply.text, "huỷ" in reply.text)
            }
            // History stays valid for the next request.
            val next = Script(say("chào"))
            val g2 = GeminiAssistant(next)
            assertEquals("chào", g2.handle("chào", rig.registry).text)
            assertValid(backend.requests.last())
        }
    }
}
