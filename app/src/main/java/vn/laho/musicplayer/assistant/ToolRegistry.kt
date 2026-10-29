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
        private fun driveTools(drive: vn.laho.musicplayer.drive.DriveGateway?, memory: AgentMemory): Array<AssistantTool?> {
            if (drive == null) return emptyArray()
            val session = DriveSession()
            return arrayOf(DriveSearchTool(drive, session), DriveDownloadTool(drive, session, memory = memory))
        }

        /**
         * [moodIndex] enables play_by_mood (LaHo mood index); the other optional gateways
         * enable the multi-step agent tools (queue, playlists, sleep timer). Null = not available.
         */
        fun default(
            player: PlayerGateway,
            moodIndex: vn.laho.musicplayer.mood.MoodIndex? = null,
            drive: vn.laho.musicplayer.drive.DriveGateway? = null,
            queue: QueueGateway? = null,
            playlists: PlaylistGateway? = null,
            sleepTimer: SleepTimerGateway? = null,
            memory: AgentMemory = AgentMemory(),
            random: kotlin.random.Random = kotlin.random.Random.Default,
            history: vn.laho.musicplayer.suggest.ListeningHistory? = null,
        ): ToolRegistry {
            val picker = SongPicker(player, moodIndex, random)
            return ToolRegistry(
                listOfNotNull(
                    PlaybackControlTool(player),
                    SetVolumeTool(player),
                    SearchAndPlayTool(player),
                    NowPlayingTool(player),
                    LibraryOverviewTool(player, moodIndex),
                    moodIndex?.let { PlayByMoodTool(player, it, random) },
                    *driveTools(drive, memory),
                    playlists?.let { CreatePlaylistTool(it, picker, memory) },
                    playlists?.let { PlayPlaylistTool(it, player, memory, random) },
                    queue?.let { QueueAddTool(it, picker) },
                    queue?.let { QueueClearTool(it) },
                    queue?.takeIf { drive != null }?.let { PlayDownloadedTool(it, memory) },
                    sleepTimer?.let { SleepTimerTool(it) },
                    *(if (history != null) suggestTools(player, moodIndex, history) else emptyList()).toTypedArray(),
                    ExplainAudioPathTool(vn.laho.musicplayer.audio.AudioPathStore),
                    PlanStepsTool(),
                ),
            )
        }
    }
}
