// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.util.Log
import kotlinx.coroutines.CancellationException
import vn.laho.musicplayer.drive.DriveAuth

/** "/drive" (status), "/drive login" (connect Google Drive), "/drive logout" (forget the token). */
class DriveCommand(private val auth: DriveAuth) : SlashCommand {
    override val prefixes = listOf("/drive")

    override suspend fun run(input: String, chat: ChatSink) {
        try {
            when {
                input.startsWith("/drive logout") -> {
                    auth.signOut()
                    chat.post("Đã quên token Google Drive trong bộ nhớ. Quyền cấp cho app vẫn còn trong tài khoản Google.")
                }
                input.startsWith("/drive login") -> chat.post(
                    if (auth.authorize(interactive = true) != null) "Đã kết nối Google Drive. Thử: \"tìm <tên bài> trên drive\"."
                    else "Đang mở màn hình cấp quyền Google Drive…",
                )
                else -> {
                    val ok = auth.isAuthorized || auth.authorize(interactive = false) != null
                    chat.post(
                        if (ok) "Google Drive: đã kết nối (chỉ đọc). Thử: \"tìm <tên bài> trên drive\"."
                        else "Google Drive: chưa kết nối. Gõ /drive login để cấp quyền.",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the message: it may contain account details.
            Log.w("LahoAssistant", "Drive command failed: ${e.javaClass.simpleName}")
            chat.post(e.message ?: "Lỗi Google Drive.")
        }
    }
}
