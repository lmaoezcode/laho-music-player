// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlin.math.sqrt
import kotlin.random.Random
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.mood.MoodProfile

/**
 * Plays songs whose position on the valence/arousal plane is closest to the requested mood,
 * using the mood index (offline audio analysis + LLM labels).
 */
class PlayByMoodTool(
    private val player: PlayerGateway,
    private val index: MoodIndex,
    private val random: Random = Random.Default,
) : AssistantTool {
    override val name = "play_by_mood"
    override val description =
        "Phát nhạc trong thư viện theo tâm trạng hoặc hoạt động, dựa trên chỉ mục tâm trạng đã phân tích. " +
            "Dùng 'mood' với một nhãn có sẵn, hoặc 'valence' (-100 buồn … 100 vui) và 'arousal' " +
            "(-100 êm dịu … 100 sôi động) cho yêu cầu tinh tế hơn."
    override val parameters = listOf(
        ToolParameter(
            "mood", ToolParameter.Type.STRING,
            "Nhãn tâm trạng: " + Mood.values().joinToString { "${it.key} (${it.label})" },
            enumValues = Mood.values().map { it.key },
        ),
        ToolParameter("valence", ToolParameter.Type.INTEGER, "-100 (rất buồn) … 100 (rất vui)"),
        ToolParameter("arousal", ToolParameter.Type.INTEGER, "-100 (rất êm dịu) … 100 (rất sôi động)"),
        ToolParameter("limit", ToolParameter.Type.INTEGER, "Số bài tối đa trong hàng đợi (mặc định 25)"),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val mood = Mood.fromKey(args.stringArg("mood"))
        val valence = args.intArg("valence")?.coerceIn(-100, 100)?.div(100.0) ?: mood?.valence
        val arousal = args.intArg("arousal")?.coerceIn(-100, 100)?.div(100.0) ?: mood?.arousal
        if (valence == null && arousal == null) {
            return ToolResult.fail("Cần 'mood' hoặc 'valence'/'arousal'.")
        }
        val limit = (args.intArg("limit") ?: DEFAULT_LIMIT).coerceIn(1, 100)

        val profiles = index.profiles()
        if (profiles.isEmpty()) {
            return ToolResult.fail("Chưa có chỉ mục tâm trạng. Gõ /index trong màn trợ lý để phân tích thư viện.")
        }
        val library = player.librarySongs().associateBy { it.id }
        val ranked = profiles
            .filter { it.songId in library }
            .map { it to distance(it, valence, arousal, mood) }
            .sortedBy { it.second }
        if (ranked.isEmpty()) return ToolResult.fail("Chỉ mục tâm trạng không khớp thư viện hiện tại; hãy chạy lại /index.")

        // Prefer songs reasonably close on the mood plane; for longer queues take a slightly
        // larger pool of the closest and shuffle it, so repeating a request gives variety.
        val close = ranked.filter { it.second <= MAX_DISTANCE }
        val candidates = if (close.size >= limit) close else ranked
        val poolSize = if (limit >= 10) limit * 3 / 2 else limit
        val pool = candidates.take(poolSize).map { it.first }.shuffled(random).take(limit)
        val songs = pool.mapNotNull { library[it.songId] }
        player.playSongs(songs, 0)

        val what = mood?.let { "\"${it.label}\"" } ?: "bạn yêu cầu"
        val first = songs.first()
        return ToolResult.ok(
            "Đang phát ${songs.size} bài hợp tâm trạng $what, bắt đầu với \"${first.title}\" – ${first.artist}.",
            mapOf("count" to songs.size, "mood" to mood?.key, "indexed" to profiles.size),
        )
    }

    /** Euclidean distance on the mood plane, with a bonus for an exact label match. */
    internal fun distance(p: MoodProfile, valence: Double?, arousal: Double?, mood: Mood?): Double {
        var sum = 0.0
        // Unknown coordinates count as neutral (0) with a small penalty.
        if (valence != null) sum += sq((p.valence ?: 0.0) - valence) + if (p.valence == null) 0.15 else 0.0
        if (arousal != null) sum += sq((p.arousal ?: 0.0) - arousal) + if (p.arousal == null) 0.15 else 0.0
        val bonus = if (mood != null && mood in p.moods) LABEL_BONUS else 0.0
        return sqrt(sum) - bonus
    }

    private fun sq(x: Double) = x * x

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val LABEL_BONUS = 0.35
        const val MAX_DISTANCE = 0.9
    }
}
