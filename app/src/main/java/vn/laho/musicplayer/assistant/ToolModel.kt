// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * A request to run one assistant tool. Produced either by the offline command parser or,
 * later, by an LLM function call, so both paths execute exactly the same code.
 */
data class ToolCall(val name: String, val args: Map<String, Any?> = emptyMap())

/** Outcome of a tool run. [message] is a short user-facing reply (Vietnamese). */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun ok(message: String, data: Map<String, Any?> = emptyMap()) = ToolResult(true, message, data)
        fun fail(message: String) = ToolResult(false, message)
    }
}

/** JSON-schema-like description of one tool argument (mirrors LLM function declarations). */
data class ToolParameter(
    val name: String,
    val type: Type,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
) {
    enum class Type { STRING, INTEGER }
}

/** One action the assistant can perform in the app. */
interface AssistantTool {
    /** snake_case identifier, also used as the LLM function name. */
    val name: String
    val description: String
    val parameters: List<ToolParameter>

    suspend fun execute(args: Map<String, Any?>): ToolResult

    /** Short Vietnamese label for multi-step progress ("Tìm trên Drive"). */
    fun progressLabel(args: Map<String, Any?>): String = DEFAULT_LABELS[name] ?: name
}

private val DEFAULT_LABELS = mapOf(
    "playback_control" to "Điều khiển phát nhạc",
    "set_volume" to "Chỉnh âm lượng",
    "search_and_play" to "Tìm và phát",
    "get_now_playing" to "Xem bài đang phát",
    "get_library_overview" to "Xem thư viện",
    "play_by_mood" to "Phát nhạc theo tâm trạng",
)

/** Reads an integer argument that may arrive as Int, Long, Double (JSON) or String. */
internal fun Map<String, Any?>.intArg(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

internal fun Map<String, Any?>.stringArg(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
