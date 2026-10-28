// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {

    private class Fake(override val prefixes: List<String>) : SlashCommand {
        override suspend fun run(input: String, chat: ChatSink) = Unit
    }

    private val mood = Fake(listOf("/index", "/mood"))
    private val meta = Fake(listOf("/meta"))
    private val drive = Fake(listOf("/drive"))
    private val router = CommandRouter(listOf(mood, meta, drive))

    @Test fun routesByFirstWordCaseInsensitive() {
        val (cmd, input) = router.route("  /Mood Export ")!!
        assertSame(mood, cmd)
        assertEquals("/mood export", input)
        assertSame(mood, router.route("/index")!!.first)
        assertSame(drive, router.route("/DRIVE login")!!.first)
    }

    @Test fun moodAndMetaDoNotShadowEachOther() {
        assertSame(meta, router.route("/meta reset")!!.first)
        assertSame(mood, router.route("/mood reset")!!.first)
    }

    @Test fun prefixMustBeAWholeWord() {
        assertNull(router.route("/indexes"))
        assertNull(router.route("/metadata"))
    }

    @Test fun normalRequestsAndUnknownCommandsGoToTheAssistant() {
        assertNull(router.route("bật nhạc buồn"))
        assertNull(router.route("/unknown"))
        assertNull(router.route(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePrefixesAreRejected() {
        CommandRouter(listOf(Fake(listOf("/a")), Fake(listOf("/a"))))
    }

    @Test fun llmErrorHints() {
        assertEquals("", LlmErrors.hint(null))
        assertTrue(LlmErrors.hint("App Check token is invalid").contains("App Check"))
        assertTrue(LlmErrors.hint("Unable to resolve host x").contains("không có mạng"))
        assertTrue(LlmErrors.hint("HTTP 429").contains("hết lượt"))
        assertEquals(" Lý do: boom", LlmErrors.hint("boom"))
    }
}
