// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * One assistant answer, with the (last) tool call behind it for logging/evaluation.
 * [pending] is set when a chain paused to ask "có/không"; [steps] counts the planned steps.
 */
data class AssistantReply(
    val text: String,
    val source: Source,
    val toolCall: ToolCall? = null,
    val result: ToolResult? = null,
    val pending: PendingConfirmation? = null,
    val steps: Int = if (toolCall != null) 1 else 0,
) {
    enum class Source { OFFLINE, ONLINE, NONE }
}

/**
 * Online fallback, e.g. Gemini with function calling. It receives the same [ToolRegistry]
 * so the LLM can only act through the declared tools.
 */
fun interface OnlineAssistant {
    suspend fun handle(text: String, tools: ToolRegistry, listener: AgentListener): AssistantReply
}

/**
 * Routes a user utterance:
 * 1. if a chain is waiting for confirmation, "có"/"không" resumes or cancels it;
 * 2. the offline parser handles fixed commands and simple "… rồi …" chains instantly and
 *    without network ([PlanRunner]);
 * 3. anything else goes to [online] when available.
 */
class AssistantEngine(
    private val tools: ToolRegistry,
    private val parser: OfflineCommandParser = OfflineCommandParser(),
    private val online: OnlineAssistant? = null,
    private val runner: PlanRunner = PlanRunner(tools),
) {
    private var pending: PendingConfirmation? = null

    /** True while a step waits for "có"/"không". */
    val awaitingConfirmation: Boolean get() = pending != null

    suspend fun handle(text: String, listener: AgentListener = AgentListener.NONE): AssistantReply {
        pending?.let { paused ->
            pending = null
            val answer = ConfirmWords.classify(text)
            if (answer != null) return remember(paused.resume(answer, listener))
            paused.abandon()
        }
        val plan = parser.parsePlan(text)
        if (plan != null) return remember(runner.run(plan, listener))
        online?.let { return remember(it.handle(text, tools, listener)) }
        return AssistantReply(
            "Mình chưa hiểu lệnh này khi không có trợ lý online. Thử: \"bài tiếp\", " +
                "\"tạm dừng\", \"to lên\", \"âm lượng 50\", \"phát bài <tên bài>\", " +
                "\"thêm 5 bài của <nghệ sĩ> vào hàng đợi\", \"tạo playlist nhạc buồn rồi phát ngẫu nhiên\".",
            AssistantReply.Source.NONE,
        )
    }

    private fun remember(reply: AssistantReply) = reply.also { pending = it.pending }
}
