// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.FakePlayer
import vn.laho.musicplayer.assistant.LIBRARY
import vn.laho.musicplayer.assistant.ToolCall
import vn.laho.musicplayer.assistant.ToolRegistry
import vn.laho.musicplayer.assistant.ToolResult

class JsonArgsTest {

    @Test
    fun functionCallArgsBecomePlainValues() {
        val args = mapOf(
            "action" to JsonPrimitive("next"),
            "level" to JsonPrimitive(30),
            "ratio" to JsonPrimitive(0.5),
            "flag" to JsonPrimitive(true),
            "none" to JsonNull,
            "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            "numberAsString" to JsonPrimitive("42"),
        )
        val out = JsonArgs.toKotlin(args)
        assertEquals("next", out["action"])
        assertEquals(30L, out["level"])
        assertEquals(0.5, out["ratio"])
        assertEquals(true, out["flag"])
        assertNull(out["none"])
        assertEquals(listOf("a", "b"), out["tags"])
        assertEquals("42", out["numberAsString"]) // quoted JSON stays a string
    }

    @Test
    fun toolsAcceptConvertedLlmArguments() = runBlocking {
        val p = FakePlayer().apply { volume = 20 }
        val args = JsonArgs.toKotlin(mapOf("delta" to JsonPrimitive(15)))
        ToolRegistry.default(p).execute(ToolCall("set_volume", args))
        assertEquals(35, p.volume)
    }

    @Test
    fun resultIncludesDataForTheModel() {
        val json = JsonArgs.resultToJson(
            ToolResult.ok("ok", mapOf("topGenres" to listOf("Ballad (3)"), "songCount" to 7)),
        )
        assertEquals("true", json["success"]!!.jsonPrimitive.content)
        assertEquals("Ballad (3)", json["topGenres"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(7, json["songCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun libraryOverviewAndGenreSearchSupportMoodRequests() = runBlocking {
        val library = LIBRARY.mapIndexed { i, s -> s.copy(genre = if (i % 2 == 0) "Ballad" else "V-Pop") }
        val p = FakePlayer(library)
        val r = ToolRegistry.default(p)

        val overview = r.execute(ToolCall("get_library_overview"))
        assertTrue(overview.success)
        @Suppress("UNCHECKED_CAST")
        val genres = overview.data["topGenres"] as List<String>
        assertEquals(listOf("Ballad (4)", "V-Pop (3)"), genres)

        val played = r.execute(ToolCall("search_and_play", mapOf("genre" to "ballad")))
        assertTrue(played.message, played.success)
        assertTrue(p.queue.all { it.genre == "Ballad" })
        assertEquals(4, p.queue.size)
    }

    @Test
    fun everyToolHasAValidDeclarationShape() {
        val r = ToolRegistry.default(FakePlayer())
        for (tool in r.tools) {
            assertTrue(tool.name.matches(Regex("[a-z_]+")))
            assertTrue(tool.description.isNotBlank())
            tool.parameters.forEach { assertTrue(it.description.isNotBlank()) }
        }
        assertEquals(
            setOf("playback_control", "set_volume", "search_and_play", "get_now_playing", "get_library_overview", "plan_steps"),
            r.tools.map { it.name }.toSet(),
        )
        // JsonObject sanity: map keys survive
        assertEquals(setOf("a"), JsonObject(mapOf("a" to JsonNull)).keys)
    }
}
