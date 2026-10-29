// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import vn.laho.musicplayer.backup.BackupAuthGate
import vn.laho.musicplayer.backup.BackupDocument
import vn.laho.musicplayer.backup.BackupFormatException
import vn.laho.musicplayer.backup.BackupService
import vn.laho.musicplayer.backup.BackupTooNewException
import vn.laho.musicplayer.backup.RestoreReport
import vn.laho.musicplayer.drive.AppDataFile
import vn.laho.musicplayer.drive.DriveAuthRequiredException
import vn.laho.musicplayer.drive.DriveRules

/**
 * "/backup" (status), "/backup login" (explicit consent for the hidden Drive app data folder),
 * "/backup now", "/backup restore" (preview) and "/backup restore có" (merge the newest backup
 * into local data; nothing local is deleted).
 */
class BackupCommand(
    private val service: BackupService,
    private val auth: BackupAuthGate,
    private val formatTime: (Long) -> String = ::defaultTime,
) : SlashCommand {
    override val prefixes = listOf("/backup")

    override suspend fun run(input: String, chat: ChatSink) {
        val args = input.removePrefix("/backup").trim()
        try {
            when {
                args.startsWith("login") -> login(chat)
                !auth.isAuthorized() -> chat.post(CONSENT_TEXT)
                args.startsWith("now") -> now(chat)
                args.startsWith("restore") -> restore(args.removePrefix("restore").trim(), chat)
                else -> status(chat)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthRequiredException) {
            chat.post("Cần cấp quyền sao lưu Google Drive. Gõ /backup login.")
        } catch (e: BackupTooNewException) {
            chat.post(e.message ?: "Bản sao lưu mới hơn app.")
        } catch (e: BackupFormatException) {
            chat.post("Bản sao lưu không dùng được: ${e.message}. Dữ liệu trên máy không bị thay đổi.")
        } catch (e: IOException) {
            Log.w(TAG, "Backup failed: ${e.javaClass.simpleName}")
            chat.post(e.message ?: "Lỗi mạng khi nói chuyện với Google Drive.")
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed: ${e.javaClass.simpleName}")
            chat.post("Sao lưu lỗi: ${e.javaClass.simpleName}. Dữ liệu trên máy không bị thay đổi.")
        }
    }

    private suspend fun login(chat: ChatSink) {
        chat.post(CONSENT_TEXT)
        chat.post(
            if (auth.requestConsent()) "Đã có quyền sao lưu. Gõ /backup now để sao lưu."
            else "Đang mở màn hình cấp quyền Google (chỉ thư mục ẩn của LaHo)…",
        )
    }

    private suspend fun status(chat: ChatSink) {
        val files = service.list()
        if (files.isEmpty()) {
            chat.post("Sao lưu: đã cấp quyền, chưa có bản nào. Gõ /backup now để sao lưu lần đầu.")
            return
        }
        chat.post(
            "Sao lưu: ${files.size} bản trên Google Drive (giữ tối đa ${BackupService.KEEP_BACKUPS}). " +
                "Gần nhất: ${describe(files.first())}.\n/backup now · /backup restore",
        )
    }

    private suspend fun now(chat: ChatSink) {
        val at = chat.post("Đang sao lưu lên Google Drive…")
        val info = service.backupNow()
        val d = info.document
        chat.update(
            at,
            "Đã sao lưu lúc ${formatTime(d.createdAtMs)}: ${d.peq.presets.size} preset PEQ, ${d.moods.size} bài trong chỉ mục tâm trạng, " +
                "${d.suggestions.size} gợi ý metadata (${DriveRules.formatSize(info.file.sizeBytes)}). " +
                "Không gồm token, đường dẫn tệp hay âm thanh.",
        )
    }

    private suspend fun restore(rest: String, chat: ChatSink) {
        val latest = service.latest()
        if (latest == null) {
            chat.post("Chưa có bản sao lưu nào trên Google Drive.")
            return
        }
        if (!isYes(rest)) {
            val listing = service.list().take(BackupService.KEEP_BACKUPS).joinToString("\n") { "• ${describe(it)}" }
            chat.post(
                "Các bản sao lưu:\n$listing\n\nBản mới nhất: ${summary(latest.document)}.\n" +
                    "Khôi phục sẽ GỘP vào dữ liệu hiện có (không xoá gì trên máy; preset trùng tên khác nội dung được thêm với tên mới; " +
                    "bài chỉ khớp khi trùng tên, nghệ sĩ và độ dài).\nGõ \"/backup restore có\" để khôi phục.",
            )
            return
        }
        val at = chat.post("Đang khôi phục…")
        val report = service.restore(latest.document)
        chat.update(at, report(report))
    }

    private fun summary(d: BackupDocument) =
        "${d.peq.presets.size} preset PEQ, ${d.moods.size} bài tâm trạng, ${d.suggestions.size} gợi ý metadata, " +
            "tạo ${formatTime(d.createdAtMs)} (LaHo ${d.appVersion})"

    private fun report(r: RestoreReport): String = buildString {
        append("Đã khôi phục (gộp, không xoá dữ liệu trên máy). ")
        append("PEQ: thêm ${r.presetsAdded} preset, bỏ qua ${r.presetsSkipped} trùng")
        if (r.activeRestored) append(", đã bật lại preset đang dùng")
        append(". Tâm trạng: ${r.moodsMerged} cập nhật, ${r.moodsKeptLocal} giữ bản trên máy, ${r.moodsUnmatched} bài không có trên máy. ")
        append("Metadata: ${r.suggestionsMerged} cập nhật, ${r.suggestionsKeptLocal} giữ bản trên máy, ${r.suggestionsUnmatched} không khớp.")
    }

    private fun describe(f: AppDataFile) = "${f.name} (${DriveRules.formatSize(f.sizeBytes)})"

    private fun isYes(text: String) = text == "có" || text == "co" || text == "yes"

    companion object {
        private const val TAG = "LahoBackup"

        const val CONSENT_TEXT =
            "Sao lưu cần thêm quyền Google Drive \"drive.appdata\": LaHo chỉ đọc/ghi một thư mục ẩn riêng của app " +
                "(bạn không thấy nó trong Drive, và nó không đụng tới tệp khác). Bản sao lưu gồm preset PEQ, chỉ mục tâm trạng " +
                "và gợi ý metadata; không gồm token, đường dẫn tệp hay âm thanh. Gõ /backup login để cấp quyền."

        private fun defaultTime(ms: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
    }
}
