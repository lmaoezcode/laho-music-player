// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlin.coroutines.cancellation.CancellationException

/**
 * Single entry point for every assistant action. UI buttons, the offline parser and the
 * (future) LLM all go through [execute], so behaviour and logging stay identical.
 */
class ToolRegistry(tools: List<AssistantTool>) {

    private val byName: Map<String, AssistantTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Duplicate tool names: ${tools.map { it.name }}" }
    }

    val tools: Collection<AssistantTool> get() = byName.values

    operator fun get(name: String): AssistantTool? = byName[name]

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = byName[call.name] ?: return ToolResult.fail("Không có công cụ '${call.name}'.")
        return try {
            tool.execute(call.args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("Lỗi khi chạy '${call.name}': ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        fun default(player: PlayerGateway) = ToolRegistry(
            listOf(
                PlaybackControlTool(player),
                SetVolumeTool(player),
                SearchAndPlayTool(player),
                NowPlayingTool(player),
            ),
        )
    }
}
