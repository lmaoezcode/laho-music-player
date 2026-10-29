// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import java.time.ZoneId
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.suggest.ListeningHistory
import vn.laho.musicplayer.suggest.SuggestCandidate
import vn.laho.musicplayer.suggest.SuggestContext
import vn.laho.musicplayer.suggest.Suggestion
import vn.laho.musicplayer.suggest.SuggestionEngine

/** Library songs joined with their mood profile, ready for [SuggestionEngine]. */
suspend fun suggestCandidates(player: PlayerGateway, moodIndex: MoodIndex?): List<SuggestCandidate> {
    val profiles = moodIndex?.profiles().orEmpty().associateBy { it.songId }
    return player.librarySongs().map { SuggestCandidate(it.id, it.title, it.artist, profiles[it.id]) }
}

/** Tool-registry entry point: one line in [ToolRegistry.default]. */
fun suggestTools(
    player: PlayerGateway,
    moodIndex: MoodIndex?,
    history: ListeningHistory?,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
): List<AssistantTool> = listOf(
    SuggestForNowTool(player, moodIndex, history, clock, zone),
    DailyMixTool(player, moodIndex, history, clock, zone),
)

/** Names of the suggestion tools; their results are complete answers (see GeminiAssistant). */
val SUGGEST_ACTION_TOOLS = setOf("suggest_for_now", "daily_mix")

private const val DEFAULT_COUNT = 15

private suspend fun playRanked(player: PlayerGateway, ranked: List<Suggestion>, what: String): ToolResult {
    if (ranked.isEmpty()) return ToolResult.fail("Thư viện đang trống nên chưa có gì để gợi ý.")
    val byId = player.librarySongs().associateBy { it.id }
    val songs = ranked.mapNotNull { byId[it.song.id] }
    if (songs.isEmpty()) return ToolResult.fail("Không tìm thấy bài nào trong thư viện hiện tại.")
    player.playSongs(songs, 0)
    val first = songs.first()
    return ToolResult.ok(
        "Đang phát ${songs.size} bài $what, bắt đầu với \"${first.title}\" – ${first.artist}.",
        mapOf(
            "count" to songs.size,
            "songs" to ranked.map { "${it.song.title} – ${it.song.artist}" },
            "ids" to ranked.map { it.song.id },
        ),
    )
}

/** "Nhạc cho lúc này": ranks by mood + time of day + history, optionally for another context. */
class SuggestForNowTool(
    private val player: PlayerGateway,
    private val moodIndex: MoodIndex?,
    private val history: ListeningHistory?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val engine: SuggestionEngine = SuggestionEngine(),
) : AssistantTool {
    override val name = "suggest_for_now"
    override val description =
        "Gợi ý và phát nhạc hợp với lúc này (giờ trong ngày, ngày thường/cuối tuần, thói quen nghe, " +
            "tâm trạng bài hát). Dùng cho \"nhạc cho lúc này\", \"gợi ý nhạc\", \"nhạc buổi sáng\", " +
            "\"nhạc tối khuya\", \"nhạc tập trung\". Không dùng khi người dùng nêu rõ tên bài/nghệ sĩ."
    override val parameters = listOf(
        ToolParameter("count", ToolParameter.Type.INTEGER, "Số bài (mặc định $DEFAULT_COUNT)"),
        ToolParameter(
            "context", ToolParameter.Type.STRING,
            "Tùy chọn: bối cảnh khác lúc này, ví dụ \"buổi sáng\", \"tối khuya\", \"tập trung\", \"buồn\". Bỏ trống = bây giờ.",
        ),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val count = (args.intArg("count") ?: DEFAULT_COUNT).coerceIn(1, 100)
        val now = clock()
        val context = SuggestContext.parse(args.stringArg("context"), now, zone)
        val ranked = engine.rank(
            suggestCandidates(player, moodIndex), history?.events().orEmpty(), context, now, count, zone,
            jitterSeed = now / JITTER_WINDOW_MS,
        )
        return playRanked(player, ranked, "hợp ${context.label}")
    }

    private companion object {
        /** The same request within a quarter of an hour gives the same list; later it varies a little. */
        const val JITTER_WINDOW_MS = 15 * 60_000L
    }
}

/** "Daily mix": favourites of the recent weeks, fresh each day, with variety. */
class DailyMixTool(
    private val player: PlayerGateway,
    private val moodIndex: MoodIndex?,
    private val history: ListeningHistory?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val engine: SuggestionEngine = SuggestionEngine(),
) : AssistantTool {
    override val name = "daily_mix"
    override val description =
        "Tạo và phát danh sách tổng hợp hằng ngày từ bài người dùng hay nghe, kèm vài bài mới hợp tâm trạng. " +
            "Dùng cho \"daily mix\", \"mix hôm nay\", \"playlist hôm nay\"."
    override val parameters = listOf(
        ToolParameter("count", ToolParameter.Type.INTEGER, "Số bài (mặc định 25)"),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val count = (args.intArg("count") ?: 25).coerceIn(1, 100)
        val ranked = engine.dailyMix(suggestCandidates(player, moodIndex), history?.events().orEmpty(), clock(), count, zone)
        return playRanked(player, ranked, "trong daily mix hôm nay")
    }
}
