// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import android.util.Log
import kotlinx.coroutines.delay
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.LibrarySong
import vn.laho.musicplayer.assistant.NowPlaying
import vn.laho.musicplayer.assistant.PlayerGateway
import vn.laho.musicplayer.assistant.ToolRegistry

/**
 * Wraps the real gateway for benchmarking: reads the real library/volume, but playback
 * and volume changes are only recorded, so running the benchmark does not disturb playback.
 */
class DryRunGateway(private val real: PlayerGateway) : PlayerGateway {
    val actions = mutableListOf<String>()
    private var volume: Int? = null

    override suspend fun play() { actions += "play" }
    override suspend fun pause() { actions += "pause" }
    override suspend fun next() { actions += "next" }
    override suspend fun previous() { actions += "previous" }
    override suspend fun volumePercent(): Int = volume ?: real.volumePercent()
    override suspend fun setVolumePercent(percent: Int) { volume = percent; actions += "volume=$percent" }
    override suspend fun librarySongs(): List<LibrarySong> = real.librarySongs()
    override suspend fun playSongs(songs: List<LibrarySong>, startIndex: Int) {
        actions += "playSongs(${songs.size})"
    }
    override suspend fun nowPlaying(): NowPlaying? = real.nowPlaying()
}

/**
 * Fixed Vietnamese prompt set run against one or more Gemini models, measuring end-to-end
 * latency, model round trips and whether the expected tool was chosen. Each prompt runs in a
 * fresh conversation; the offline parser is bypassed so every prompt reaches the model.
 */
class AssistantBenchmark(
    private val realGateway: PlayerGateway,
    private val backendFor: (String) -> ModelBackend = { FirebaseModelBackend(it) },
    private val pauseBetweenMs: Long = 4_000L,
    private val clock: () -> Long = System::nanoTime,
) {

    /**
     * [expectedTool] = name of the last tool that should run, or null when no tool should.
     * [alsoAccept] lists other acceptable outcomes, e.g. for mood requests a polite refusal
     * (null) is correct when the library has nothing that fits.
     */
    data class Case(val prompt: String, val expectedTool: String?, val alsoAccept: Set<String?> = emptySet())

    data class Run(
        val model: String,
        val case: Case,
        val latencyMs: Long,
        val rounds: Int,
        val toolCalled: String?,
        val success: Boolean,
        val reply: String,
    ) {
        val correct: Boolean
            get() = success && (toolCalled == case.expectedTool || toolCalled in case.alsoAccept)
    }

    data class Summary(val model: String, val runs: List<Run>) {
        val medianMs: Long get() = runs.map { it.latencyMs }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val meanMs: Long get() = if (runs.isEmpty()) 0 else runs.sumOf { it.latencyMs } / runs.size
        val maxMs: Long get() = runs.maxOfOrNull { it.latencyMs } ?: 0
        val meanRounds: Double get() = if (runs.isEmpty()) 0.0 else runs.sumOf { it.rounds }.toDouble() / runs.size
        val correct: Int get() = runs.count { it.correct }
    }

    suspend fun run(
        models: List<String>,
        cases: List<Case> = DEFAULT_CASES,
        onProgress: (String) -> Unit = {},
    ): List<Summary> = models.map { model ->
        val runs = cases.mapIndexed { i, case ->
            onProgress("[$model] ${i + 1}/${cases.size}: ${case.prompt}")
            val run = runCase(model, case)
            Log.i(TAG, "$model | ${run.latencyMs} ms | ${run.rounds} rounds | ${run.toolCalled} | " +
                "${if (run.correct) "OK" else "MISS"} | ${case.prompt} -> ${run.reply}")
            if (pauseBetweenMs > 0) delay(pauseBetweenMs) // stay under free-tier rate limits
            run
        }
        Summary(model, runs)
    }

    private suspend fun runCase(model: String, case: Case): Run {
        var attempt = 0
        while (true) {
            val tools = ToolRegistry.default(DryRunGateway(realGateway))
            val assistant = GeminiAssistant(backendFor(model))
            val start = clock()
            val reply = assistant.handle(case.prompt, tools, vn.laho.musicplayer.assistant.AgentListener.NONE)
            val ms = (clock() - start) / 1_000_000
            val quotaHit = reply.source == AssistantReply.Source.NONE && "hết lượt" in reply.text
            if (quotaHit && attempt++ < 2) {
                Log.w(TAG, "$model rate-limited, retrying in 20 s")
                delay(20_000)
                continue
            }
            return Run(
                model = model,
                case = case,
                latencyMs = ms,
                rounds = assistant.lastModelRounds,
                toolCalled = reply.toolCall?.name,
                success = reply.source == AssistantReply.Source.ONLINE,
                reply = reply.text,
            )
        }
    }

    companion object {
        private const val TAG = "LahoBench"

        /** Mood prompts: refusing (no tool) is fine when nothing in the library fits. */
        private val MOOD_REFUSAL_OK = setOf<String?>(null)

        val DEFAULT_CASES = listOf(
            Case("tôi đang buồn", "search_and_play", MOOD_REFUSAL_OK),
            Case("bật nhạc để tập gym", "search_and_play", MOOD_REFUSAL_OK),
            Case("cho tôi nghe gì đó nhẹ nhàng để học bài", "search_and_play", MOOD_REFUSAL_OK),
            Case("mở nhạc sôi động cho buổi tiệc", "search_and_play", MOOD_REFUSAL_OK),
            Case("giảm âm lượng xuống còn một nửa", "set_volume"),
            Case("tăng âm lượng lên một chút nhé", "set_volume"),
            Case("bỏ qua bài này, nghe chán quá", "playback_control"),
            Case("bài đang phát tên là gì thế", "get_now_playing"),
            Case("dừng nhạc giúp mình một lát", "playback_control"),
            Case("tải nhạc từ Google Drive về máy", null),
        )

        fun format(summaries: List<Summary>): String = buildString {
            appendLine("Kết quả benchmark (${summaries.firstOrNull()?.runs?.size ?: 0} câu, dry-run):")
            for (s in summaries) {
                appendLine(
                    "• ${s.model}: trung vị ${s.medianMs} ms, trung bình ${s.meanMs} ms, " +
                        "chậm nhất ${s.maxMs} ms, ${"%.1f".format(s.meanRounds)} lượt/câu, " +
                        "đúng tool ${s.correct}/${s.runs.size}",
                )
            }
            val misses = summaries.flatMap { s -> s.runs.filterNot { it.correct }.map { s.model to it } }
            if (misses.isNotEmpty()) {
                appendLine("Sai/không đạt:")
                misses.forEach { (m, r) ->
                    appendLine("  - [$m] \"${r.case.prompt}\": gọi ${r.toolCalled ?: "không tool"}, cần ${r.case.expectedTool ?: "không tool"}")
                }
            }
        }.trimEnd()
    }
}
