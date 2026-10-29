// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.privacy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.OnlineAssistant
import vn.laho.musicplayer.assistant.ToolRegistry

/** Minimal persistence seam so the setting is unit-testable without Android. */
interface FlagStore {
    /** Null when the user never chose. */
    fun read(): Boolean?
    fun write(value: Boolean)
}

/**
 * "Allow online AI (Gemini)" switch. Default (never chosen): ON in debug builds, OFF in
 * others until the user turns it on (the assistant screen and settings point to the
 * explanation page). Kept out of Rhythm's AppSettings on purpose.
 */
class AiPrivacySettings(private val store: FlagStore, private val isDebugBuild: Boolean) {
    private val state = MutableStateFlow(resolve(store.read(), isDebugBuild))
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun isOnlineEnabled(): Boolean = state.value

    fun setOnlineEnabled(value: Boolean) {
        store.write(value)
        state.value = value
    }

    companion object {
        fun resolve(stored: Boolean?, isDebugBuild: Boolean): Boolean = stored ?: isDebugBuild

        @Volatile private var instance: AiPrivacySettings? = null

        fun get(context: Context): AiPrivacySettings = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences("laho_privacy", Context.MODE_PRIVATE)
                val debug = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                AiPrivacySettings(SharedPrefsFlag(prefs), debug).also { instance = it }
            }
        }
    }
}

private class SharedPrefsFlag(private val prefs: android.content.SharedPreferences) : FlagStore {
    override fun read(): Boolean? = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    override fun write(value: Boolean) = prefs.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val KEY = "online_ai_enabled"
    }
}

/**
 * Wraps the online assistant behind the switch, re-checked on every request: turning it off
 * takes effect immediately, drops the conversation held by the model wrapper, and nothing is
 * sent. [create] builds the real backend lazily (null when the build has no Firebase).
 */
class GatedOnlineAssistant(
    private val enabled: () -> Boolean,
    private val create: () -> OnlineAssistant?,
) : OnlineAssistant {
    private var inner: OnlineAssistant? = null

    /** The live backend, or null when the switch is off or the build has none. */
    fun current(): OnlineAssistant? {
        if (!enabled()) {
            inner = null
            return null
        }
        return inner ?: create()?.also { inner = it }
    }

    override suspend fun handle(text: String, tools: ToolRegistry): AssistantReply {
        val backend = current() ?: return AssistantReply(
            if (enabled()) NO_BACKEND else OFF_MESSAGE,
            AssistantReply.Source.NONE,
        )
        return backend.handle(text, tools)
    }

    companion object {
        const val OFF_MESSAGE =
            "AI online (Gemini) đang tắt nên mình chỉ hiểu lệnh offline: \"bài tiếp\", \"tạm dừng\", " +
                "\"to lên\", \"phát bài …\". Bật lại trong Cài đặt → Khả năng ngoài."
        const val NO_BACKEND = "Bản build này không có cấu hình Firebase nên trợ lý chỉ chạy offline."
    }
}
