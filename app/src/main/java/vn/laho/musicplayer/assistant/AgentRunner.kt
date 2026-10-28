// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/** Progress of one step of a multi-step request, shown in the chat ("1/3 Tìm trên Drive…"). */
data class AgentProgress(val step: Int, val total: Int?, val label: String, val state: State) {
    enum class State { RUNNING, DONE, FAILED, WAITING, CANCELLED }

    /** One chat line, e.g. "1/3 Tìm "Lạc Trôi" trên Drive…" or "✓ 1/3 Tìm …". */
    fun line(): String {
        val n = if (total != null && total > 1) "$step/$total" else "Bước $step:"
        return when (state) {
            State.RUNNING -> "$n $label…"
            State.DONE -> "✓ $n $label"
            State.FAILED -> "✗ $n $label"
            State.WAITING -> "? $n $label (chờ xác nhận)"
            State.CANCELLED -> "– $n $label (đã huỷ)"
        }
    }
}

fun interface AgentListener {
    fun onProgress(progress: AgentProgress)

    companion object {
        val NONE = AgentListener { }
    }
}

/**
 * A chain paused before a destructive or long step. The engine keeps it until the next user
 * message: "có" resumes it, "không" cancels it, anything else abandons it.
 */
interface PendingConfirmation {
    suspend fun resume(approved: Boolean, listener: AgentListener): AssistantReply

    /** The user moved on: close the paused step without running it. */
    fun abandon()
}

/** Rules shared by the offline chain runner and the Gemini loop. */
object AgentPolicy {
    /** At most this many tool steps per user request. */
    const val MAX_STEPS = 6

    /** Tools that only act after the user said "có"; the model can never pre-confirm them. */
    val CONFIRMED_TOOLS = setOf("drive_download", "queue_clear")

    fun needsConfirmation(result: ToolResult) = result.data["requires_confirmation"] == true

    fun confirmed(call: ToolCall) = call.copy(args = call.args + ("confirmed" to true))

    /** Strips a self-granted confirmation from a model call. */
    fun unconfirmed(call: ToolCall) =
        if (call.name in CONFIRMED_TOOLS) call.copy(args = call.args - "confirmed") else call

    /** A failed step, or a search with nothing found, stops the rest of the chain. */
    fun stopsChain(result: ToolResult) = !result.success || (result.data["count"] as? Number)?.toInt() == 0
}

/** "có" / "không" answers to a confirmation question (null = something else). */
object ConfirmWords {
    private val YES = setOf(
        "co", "u", "uh", "um", "uhm", "ok", "oke", "okay", "yes", "y", "dong y", "dong y tai", "tai", "tai ve",
        "duoc", "duoc do", "lam di", "xac nhan", "chac chan", "xoa", "tiep tuc", "dung vay", "chuan", "co tai", "co xoa",
        "ok tai", "yes tai", "xac nhan tai", "dong y xoa", "tai luon", "tai ngay", "xoa luon",
    )
    private val NO = setOf(
        "khong", "ko", "k", "no", "thoi", "huy", "huy bo", "khoi", "khong can", "khong tai", "khong xoa",
        "bo qua", "dung tai", "dung xoa", "cancel", "thoi khoi",
    )
    private val TRAILING = setOf("nhe", "nha", "di", "a", "ban", "luon", "nao", "roi", "ma", "vay", "nhe ban")

    fun classify(text: String): Boolean? {
        val words = VietnameseText.fold(text).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last() in TRAILING) words.removeAt(words.lastIndex)
        val phrase = words.joinToString(" ")
        return when (phrase) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }
}

/**
 * Runs a fixed list of tool calls (an offline chain such as "tạo playlist nhạc buồn rồi phát
 * ngẫu nhiên") in order: progress per step, at most [maxSteps] steps, stop at the first
 * failure, pause where a tool asks for confirmation.
 */
class PlanRunner(private val tools: ToolRegistry, private val maxSteps: Int = AgentPolicy.MAX_STEPS) {

    suspend fun run(plan: List<ToolCall>, listener: AgentListener = AgentListener.NONE): AssistantReply {
        require(plan.isNotEmpty())
        return Chain(plan.take(maxSteps), plan.size - maxSteps.coerceAtMost(plan.size), listener).runFrom(0)
    }

    private inner class Chain(
        val steps: List<ToolCall>,
        val skippedByCap: Int,
        var listener: AgentListener,
    ) : PendingConfirmation {
        val total = steps.size
        val lines = mutableListOf<String>()
        var lastCall: ToolCall? = null
        var lastResult: ToolResult? = null
        var pausedAt = -1

        private fun label(call: ToolCall) = tools[call.name]?.progressLabel(call.args) ?: call.name

        private fun emit(i: Int, call: ToolCall, state: AgentProgress.State) =
            listener.onProgress(AgentProgress(i + 1, total, label(call), state))

        private suspend fun step(i: Int, call: ToolCall): ToolResult {
            emit(i, call, AgentProgress.State.RUNNING)
            val result = tools.execute(call)
            lastCall = call
            lastResult = result
            val state = when {
                AgentPolicy.needsConfirmation(result) -> AgentProgress.State.WAITING
                AgentPolicy.stopsChain(result) -> AgentProgress.State.FAILED
                else -> AgentProgress.State.DONE
            }
            emit(i, call, state)
            return result
        }

        private fun prefix(i: Int) = if (total > 1) "${i + 1}/$total " else ""

        suspend fun runFrom(start: Int): AssistantReply {
            for (i in start until steps.size) {
                val result = step(i, steps[i])
                if (AgentPolicy.needsConfirmation(result)) {
                    pausedAt = i
                    val question = (if (total > 1) "Bước ${i + 1}/$total: " else "") + result.message + " (có/không)"
                    return reply(question, pending = this)
                }
                lines += prefix(i) + firstLine(result.message)
                if (AgentPolicy.stopsChain(result)) return finish(stoppedAt = i)
            }
            return finish(stoppedAt = null)
        }

        override suspend fun resume(approved: Boolean, listener: AgentListener): AssistantReply {
            this.listener = listener
            val i = pausedAt.also { pausedAt = -1 }
            check(i >= 0) { "chain is not paused" }
            if (!approved) {
                emit(i, steps[i], AgentProgress.State.CANCELLED)
                lines += prefix(i) + "Đã huỷ theo yêu cầu."
                return finish(stoppedAt = i)
            }
            val result = step(i, AgentPolicy.confirmed(steps[i]))
            if (AgentPolicy.needsConfirmation(result)) {
                // The tool refused the confirmation (e.g. it expired): ask again.
                pausedAt = i
                return reply(result.message + " (có/không)", pending = this)
            }
            lines += prefix(i) + firstLine(result.message)
            if (AgentPolicy.stopsChain(result)) return finish(stoppedAt = i)
            return runFrom(i + 1)
        }

        override fun abandon() {
            if (pausedAt >= 0) emit(pausedAt, steps[pausedAt], AgentProgress.State.CANCELLED)
            pausedAt = -1
        }

        private fun finish(stoppedAt: Int?): AssistantReply {
            val text = buildString {
                append(if (total == 1) lastResult?.message.orEmpty() else lines.joinToString("\n"))
                val left = if (stoppedAt != null) total - stoppedAt - 1 else 0
                if (left > 0) append("\nDừng lại, bỏ qua $left bước còn lại.")
                if (skippedByCap > 0) append("\nMỗi yêu cầu tối đa $maxSteps bước nên mình bỏ qua $skippedByCap bước cuối.")
            }
            return reply(text, pending = null)
        }

        private fun reply(text: String, pending: PendingConfirmation?) = AssistantReply(
            text, AssistantReply.Source.OFFLINE, lastCall, lastResult, pending = pending, steps = total,
        )
    }

    /** Drive search results list every file; in a chain summary the first line is enough. */
    private fun firstLine(message: String) = message.lineSequence().first()
}
