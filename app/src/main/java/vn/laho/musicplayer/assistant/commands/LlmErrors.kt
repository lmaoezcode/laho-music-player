// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

/** Turns a Gemini/Firebase error message into a short Vietnamese hint for the chat. */
object LlmErrors {
    fun hint(error: String?): String {
        if (error == null) return ""
        val lower = error.lowercase()
        return when {
            "app check" in lower || "appcheck" in lower || "attestation" in lower ->
                " Lý do: App Check từ chối. Hãy thêm debug token của máy này (logcat, tag DebugAppCheckProvider) " +
                    "vào Firebase Console → Security → App Check → ⋮ → Manage debug tokens."
            "unable to resolve host" in lower || "timeout" in lower || "network" in lower -> " Lý do: không có mạng."
            "quota" in lower || "429" in lower -> " Lý do: hết lượt miễn phí, thử lại sau."
            else -> " Lý do: $error"
        }
    }
}
