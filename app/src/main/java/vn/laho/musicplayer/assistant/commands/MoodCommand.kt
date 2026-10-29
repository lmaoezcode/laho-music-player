// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import vn.laho.musicplayer.mood.AndroidMoodIndexing
import vn.laho.musicplayer.mood.IndexProgress
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodStore

/**
 * Mood index commands: "/index" (analyse new songs; "/stop" cancels), "/mood" (status),
 * "/mood export" (CSV for evaluation), "/mood reset" (forget everything).
 */
class MoodCommand(private val context: Context, private val store: MoodStore) : SlashCommand {
    override val prefixes = listOf("/index", "/mood")

    override suspend fun run(input: String, chat: ChatSink) {
        when {
            input.startsWith("/index") -> index(chat)
            input.startsWith("/mood export") -> {
                val f = AndroidMoodIndexing.exportCsv(context, store)
                chat.post("Đã xuất ${f.absolutePath}")
            }
            input.startsWith("/mood reset") -> {
                store.clear()
                chat.post("Đã xoá chỉ mục tâm trạng.")
            }
            else -> status(chat)
        }
    }

    private suspend fun index(chat: ChatSink) {
        val llmAllowed = vn.laho.musicplayer.privacy.AiPrivacySettings.get(context).isOnlineEnabled()
        val progressAt = chat.post(
            "Đang lập chỉ mục tâm trạng… (gõ /stop để dừng, chạy lại sẽ tiếp tục)" +
                if (llmAllowed) "" else " AI online đang tắt: chỉ phân tích âm thanh trên máy, bỏ qua bước Gemini.",
        )
        val started = System.currentTimeMillis()
        try {
            val report = AndroidMoodIndexing.indexer(context, store, allowLlm = llmAllowed).run { p ->
                val text = when (p.stage) {
                    IndexProgress.Stage.AUDIO -> "Phân tích âm thanh ${p.done + 1}/${p.total}: ${p.current.orEmpty()}"
                    IndexProgress.Stage.LLM -> "Gemini phân loại ${p.done}/${p.total} bài…"
                    IndexProgress.Stage.DONE -> "Hoàn tất."
                }
                chat.update(progressAt, text)
            }
            val secs = (System.currentTimeMillis() - started) / 1000
            val summary = "Chỉ mục tâm trạng xong sau ${secs}s: ${report.indexedWithMood}/${report.songs} bài có tâm trạng. " +
                "Âm thanh: ${report.analysed} bài mới phân tích, ${report.decodeFailures} bài không giải mã được. " +
                (if (llmAllowed) "Gemini: ${report.classified} bài phân loại" else "Gemini: bỏ qua (AI online đang tắt)") +
                (if (report.llmFailures > 0) ", ${report.llmFailures} bài lỗi (chạy lại /index để thử lại)." else ".") +
                LlmErrors.hint(report.lastLlmError) +
                " Thử: \"bật nhạc buồn\", \"nhạc tập gym\"."
            Log.i(TAG, summary)
            chat.post(summary)
        } catch (e: CancellationException) {
            chat.post("Đã dừng. Gõ /index để tiếp tục từ chỗ dừng.")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Mood indexing failed", e)
            chat.post("Lập chỉ mục lỗi: ${e.message}")
        }
    }

    private suspend fun status(chat: ChatSink) {
        val records = store.all()
        val profiles = records.mapNotNull { it.profile() }
        val byMood = Mood.values().joinToString(", ") { m -> "${m.label} ${profiles.count { m in it.moods }}" }
        chat.post(
            "Chỉ mục: ${records.size} bài (${records.count { it.hasFeatures && it.featureError == null }} có đặc trưng âm thanh, " +
                "${records.count { it.hasVerdict }} đã qua Gemini). Theo nhãn: $byMood.",
        )
    }

    private companion object {
        const val TAG = "LahoAssistant"
    }
}
