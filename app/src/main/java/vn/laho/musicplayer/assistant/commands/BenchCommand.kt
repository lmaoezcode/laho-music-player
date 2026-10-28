// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.util.Log
import kotlinx.coroutines.CancellationException
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.PlayerGateway
import vn.laho.musicplayer.assistant.online.AssistantBenchmark
import vn.laho.musicplayer.assistant.online.GeminiAssistant

/** "/bench" compares Gemini models on a fixed prompt set (dry run); "/bench lite" or "/bench flash" runs one. */
class BenchCommand(
    private val gateway: PlayerGateway,
    private val onlineAvailable: Boolean,
) : SlashCommand {
    override val prefixes = listOf("/bench")

    override suspend fun run(input: String, chat: ChatSink) {
        if (!onlineAvailable) {
            chat.post("Bản build này không có Firebase nên không chạy được benchmark.")
            return
        }
        val flash = GeminiAssistant.MODEL_FLASH
        val lite = GeminiAssistant.MODEL_FLASH_LITE
        val models = when (input.removePrefix("/bench").trim()) {
            "lite" -> listOf(lite)
            "flash" -> listOf(flash)
            else -> listOf(flash, lite)
        }
        chat.post("Đang chạy benchmark ${models.joinToString(" và ")} (10 câu mỗi model, dry-run, khoảng 3-5 phút)…")
        try {
            val summaries = AssistantBenchmark(gateway).run(models) { progress -> Log.i(TAG, progress) }
            val report = AssistantBenchmark.format(summaries)
            Log.i(TAG, report)
            chat.post(report, AssistantReply.Source.ONLINE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Benchmark failed", e)
            chat.post("Benchmark lỗi: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "LahoAssistant"
    }
}
