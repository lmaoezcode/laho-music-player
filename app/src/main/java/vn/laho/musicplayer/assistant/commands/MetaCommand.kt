// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import vn.laho.musicplayer.metadata.AndroidMetadata
import vn.laho.musicplayer.mood.metadataStore

/**
 * "/meta": looks up songs with missing genre/year/album (or unknown artist) on MusicBrainz and
 * stores suggestions in LaHo's database only (never the files). "/stop" cancels;
 * "/meta" again resumes; "/meta reset" forgets the stored suggestions.
 */
class MetaCommand(private val context: Context) : SlashCommand {
    override val prefixes = listOf("/meta")

    override suspend fun run(input: String, chat: ChatSink) {
        if (input.startsWith("/meta reset")) {
            metadataStore(context).clear()
            chat.post("Đã xoá các gợi ý metadata đã lưu.")
            return
        }
        val progressAt = chat.post(
            "Đang tra metadata trên MusicBrainz… (gõ /stop để dừng, chạy lại sẽ tiếp tục; không sửa file nhạc)",
        )
        try {
            val report = AndroidMetadata.batch(context).run { p ->
                if (p.current != null) chat.update(progressAt, "Tra metadata ${p.done + 1}/${p.total}: ${p.current}")
            }
            val summary = "Tra metadata xong: ${report.candidates} bài thiếu thông tin" +
                (if (report.skipped > 0) " (${report.skipped} bài đã tra trước đó)" else "") +
                ". Tìm thấy ${report.found}, không thấy ${report.notFound}" +
                (if (report.failed > 0) ", lỗi ${report.failed} (chạy lại /meta để thử lại)" else "") + "." +
                (report.lastError?.let { " Lỗi cuối: $it." } ?: "") +
                " Gợi ý chỉ lưu trong LaHo (dùng cho chỉ mục tâm trạng), file nhạc không bị sửa. " +
                "Dữ liệu: MusicBrainz. Chạy /index để chỉ mục dùng thể loại/năm mới."
            Log.i(TAG, summary)
            chat.post(summary)
        } catch (e: CancellationException) {
            chat.post("Đã dừng. Gõ /meta để tiếp tục từ chỗ dừng.")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Metadata batch failed", e)
            chat.post("Tra metadata lỗi: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "LahoAssistant"
    }
}
