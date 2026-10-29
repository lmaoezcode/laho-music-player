// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import android.content.Context
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.laho.musicplayer.assistant.PlayerGateway
import vn.laho.musicplayer.assistant.suggestCandidates
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.suggest.ListeningHistory
import vn.laho.musicplayer.suggest.SuggestContext
import vn.laho.musicplayer.suggest.SuggestionEngine
import vn.laho.musicplayer.suggest.SuggestionEvaluator
import vn.laho.musicplayer.suggest.TimeBucket

/**
 * "/suggest" shows the top suggestions for now (nothing is played); "/suggest eval" writes the
 * thesis evaluation files to the app's external files dir (Android/data/<package>/files):
 * suggest_history.csv, suggest_by_bucket.csv and suggest_replay.csv, and prints hit rate@k.
 * Run docs/thesis/scripts/eval_suggest.py on them.
 */
class SuggestCommand(
    private val context: Context,
    private val player: PlayerGateway,
    private val moodIndex: MoodIndex?,
    private val history: ListeningHistory,
) : SlashCommand {
    override val prefixes = listOf("/suggest")

    override suspend fun run(input: String, chat: ChatSink) {
        if (input.removePrefix("/suggest").trim().startsWith("eval")) eval(chat) else preview(chat)
    }

    private suspend fun preview(chat: ChatSink) {
        val now = System.currentTimeMillis()
        val ranked = SuggestionEngine().rank(
            suggestCandidates(player, moodIndex), history.events(), SuggestContext.at(now), now, PREVIEW_COUNT,
        )
        if (ranked.isEmpty()) {
            chat.post("Thư viện trống.")
            return
        }
        chat.post(
            ranked.mapIndexed { i, s -> "${i + 1}. ${s.song.title} – ${s.song.artist} (${"%.2f".format(Locale.ROOT, s.score)})" }
                .joinToString("\n"),
        )
    }

    private suspend fun eval(chat: ChatSink) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val candidates = suggestCandidates(player, moodIndex)
        val events = history.events()
        val engine = SuggestionEngine()

        val byBucket = TimeBucket.values().flatMap { bucket ->
            listOf(false, true).map { weekend ->
                val ctx = SuggestContext(bucket, weekend)
                ctx to engine.rank(candidates, events, ctx, now, EXPORT_COUNT, zone)
            }
        }
        val rows = SuggestionEvaluator.replay(candidates, events, zone)
        val dir = withContext(Dispatchers.IO) {
            val d = context.getExternalFilesDir(null) ?: context.filesDir
            File(d, "suggest_history.csv").writeText(SuggestionEvaluator.historyCsv(events, zone), Charsets.UTF_8)
            File(d, "suggest_by_bucket.csv").writeText(SuggestionEvaluator.suggestionsCsv(byBucket), Charsets.UTF_8)
            File(d, "suggest_replay.csv").writeText(SuggestionEvaluator.replayCsv(rows), Charsets.UTF_8)
            d
        }

        val sessions = rows.map { it.date to it.bucket }.toSet().size
        val summary = SuggestionEvaluator.summarize(rows).filter { it.k == SUMMARY_K }
            .joinToString("\n") { "  ${it.method}: hit@$SUMMARY_K ${pct(it.hitRate)}, recall ${pct(it.recall)}" }
        chat.post(
            "Đã xuất 3 file CSV vào ${dir.absolutePath}\n" +
                "Lịch sử: ${events.size} lượt nghe, ${events.count { it.isSkip }} lượt bỏ qua; " +
                "phát lại trên $sessions phiên (ngày × buổi).\n" +
                (if (summary.isEmpty()) "Chưa đủ lịch sử để tính hit rate (cần ít nhất ${SuggestionEvaluator.DEFAULT_MIN_HISTORY} lượt nghe trước mỗi phiên)."
                else "Kết quả ngoại tuyến:\n$summary"),
        )
    }

    private fun pct(x: Double) = "%.1f%%".format(Locale.ROOT, x * 100)

    private companion object {
        const val PREVIEW_COUNT = 8
        const val EXPORT_COUNT = 20
        const val SUMMARY_K = 10
    }
}
