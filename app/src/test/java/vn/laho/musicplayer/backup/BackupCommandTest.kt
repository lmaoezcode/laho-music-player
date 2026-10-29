// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.commands.BackupCommand
import vn.laho.musicplayer.assistant.commands.ChatSink
import vn.laho.musicplayer.dsp.PeqPresetState

class BackupCommandTest {
    private class Chat : ChatSink {
        val lines = mutableListOf<String>()
        override fun post(text: String, source: AssistantReply.Source?): Int { lines += text; return lines.lastIndex }
        override fun update(index: Int, text: String) { lines[index] = text }
    }

    private class Gate(var authorized: Boolean) : BackupAuthGate {
        var consentRequests = 0
        override suspend fun isAuthorized() = authorized
        override suspend fun requestConsent(): Boolean { consentRequests++; return authorized }
    }

    private val drive = FakeDrive()
    private val peq = FakePeq(samplePeq)
    private val moods = MemMoods(mood("1", "A", "B"))
    private val service = BackupService(
        drive, peq, moods, MemSuggestions(), { listOf(song("1", "A", "B", 1000)) }, "5.5",
        now = { 1_700_000_000_000 }, dispatcher = Dispatchers.Unconfined,
    )

    private fun run(gate: BackupAuthGate, input: String): Chat {
        val chat = Chat()
        runBlocking { BackupCommand(service, gate, formatTime = { "T$it" }).run(input, chat) }
        return chat
    }

    @Test fun withoutConsentEverythingExplainsAndNothingIsUploaded() {
        val gate = Gate(false)
        for (cmd in listOf("/backup", "/backup now", "/backup restore")) {
            assertTrue(run(gate, cmd).lines.single().contains("drive.appdata"))
        }
        assertEquals(0, gate.consentRequests)
        assertTrue(drive.files.isEmpty())
    }

    @Test fun loginExplainsWhyBeforeAskingForConsent() {
        val gate = Gate(false)
        val chat = run(gate, "/backup login")
        assertEquals(1, gate.consentRequests)
        assertTrue(chat.lines.first().contains("thư mục ẩn"))
    }

    @Test fun statusThenNowThenStatus() {
        val gate = Gate(true)
        assertTrue(run(gate, "/backup").lines.single().contains("chưa có bản nào"))
        assertTrue(run(gate, "/backup now").lines.single().contains("Đã sao lưu"))
        assertEquals(1, drive.files.size)
        assertTrue(run(gate, "/backup").lines.single().contains("1 bản"))
    }

    @Test fun restoreNeedsConfirmationThenMerges() {
        val gate = Gate(true)
        run(gate, "/backup now")
        moods.rows.clear()
        peq.state = PeqPresetState.DEFAULT

        val preview = run(gate, "/backup restore")
        assertTrue(preview.lines.single().contains("/backup restore có"))
        assertTrue(moods.rows.isEmpty())
        assertTrue(peq.state.presets.isEmpty())

        val done = run(gate, "/backup restore có")
        assertTrue(done.lines.single().startsWith("Đã khôi phục"))
        assertEquals(1, moods.rows.size)
        assertEquals(2, peq.state.presets.size)
    }

    @Test fun restoreWithNoBackupAndWithTooNewBackup() {
        val gate = Gate(true)
        assertTrue(run(gate, "/backup restore").lines.single().contains("Chưa có bản sao lưu"))
        runBlocking {
            drive.upload(BackupService.fileName(5), """{"format":"laho-backup","schemaVersion":42}""".toByteArray(), "application/json")
        }
        val chat = run(gate, "/backup restore có")
        assertTrue(chat.lines.single().contains("mới hơn"))
        assertFalse(peq.state.presets.isEmpty()) // untouched
    }
}
