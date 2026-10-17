// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/** One assistant answer, with the tool call behind it (if any) for logging/evaluation. */
data class AssistantReply(
    val text: String,
    val source: Source,
    val toolCall: ToolCall? = null,
    val result: ToolResult? = null,
) {
    enum class Source { OFFLINE, ONLINE, NONE }
}

/**
 * Online fallback, e.g. Gemini with function calling. It receives the same [ToolRegistry]
 * so the LLM can only act through the declared tools. Implemented in a later step.
 */
fun interface OnlineAssistant {
    suspend fun handle(text: String, tools: ToolRegistry): AssistantReply
}

/**
 * Routes a user utterance: the offline parser handles fixed commands instantly and without
 * network; anything else goes to [online] when available.
 */
class AssistantEngine(
    private val tools: ToolRegistry,
    private val parser: OfflineCommandParser = OfflineCommandParser(),
    private val online: OnlineAssistant? = null,
) {

    suspend fun handle(text: String): AssistantReply {
        val call = parser.parse(text)
        if (call != null) {
            val result = tools.execute(call)
            return AssistantReply(result.message, AssistantReply.Source.OFFLINE, call, result)
        }
        online?.let { return it.handle(text, tools) }
        return AssistantReply(
            "Mình chưa hiểu lệnh này khi không có trợ lý online. Thử: \"bài tiếp\", " +
                "\"tạm dừng\", \"to lên\", \"âm lượng 50\", \"phát bài <tên bài>\".",
            AssistantReply.Source.NONE,
        )
    }
}
