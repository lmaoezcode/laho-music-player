// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

/**
 * Offline replay of the user's real listening history, for the thesis.
 *
 * History is cut into sessions (one calendar day x one [TimeBucket]). For each session with
 * enough earlier history, every method ranks the library using only what happened before the
 * session started; a hit is a song the user then really played (without skipping) in it.
 * Metrics: hit rate@k (at least one hit in the top k), recall@k and precision@k.
 */
object SuggestionEvaluator {

    const val METHOD_ENGINE = "engine"
    const val METHOD_MOOD_TIME = "mood_time"
    const val METHOD_POPULAR = "popular"
    const val METHOD_RANDOM = "random"
    val METHODS = listOf(METHOD_ENGINE, METHOD_MOOD_TIME, METHOD_POPULAR, METHOD_RANDOM)

    const val DEFAULT_MIN_HISTORY = 20
    val DEFAULT_KS = listOf(5, 10, 20)

    /** One method at one cut-off for one session. */
    data class ReplayRow(
        val date: LocalDate,
        val bucket: TimeBucket,
        val weekend: Boolean,
        val historySize: Int,
        val truthSize: Int,
        val method: String,
        val k: Int,
        val hits: Int,
    ) {
        val hit: Boolean get() = hits > 0
        val recall: Double get() = if (truthSize == 0) 0.0 else hits.toDouble() / truthSize
        val precision: Double get() = hits.toDouble() / k
    }

    fun replay(
        candidates: List<SuggestCandidate>,
        events: List<ListenEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
        ks: List<Int> = DEFAULT_KS,
        minHistory: Int = DEFAULT_MIN_HISTORY,
        engine: SuggestionEngine = SuggestionEngine(),
    ): List<ReplayRow> {
        val known = candidates.map { it.id }.toSet()
        val sorted = events.filter { it.songId in known }.sortedBy { it.startedAtMs }
        val maxK = ks.maxOrNull() ?: return emptyList()
        val sessions = sorted.groupBy { sessionDate(it.startedAtMs, zone) to TimeBucket.ofHour(hourOf(it.startedAtMs, zone)) }
        val rows = ArrayList<ReplayRow>()
        for ((key, group) in sessions.entries.sortedBy { it.value.first().startedAtMs }) {
            val start = group.first().startedAtMs
            val truth = group.filterNot { it.isSkip }.map { it.songId }.toSet()
            val history = sorted.filter { it.startedAtMs < start }
            if (truth.isEmpty() || history.size < minHistory) continue
            val weekend = SuggestContext.isWeekend(Instant.ofEpochMilli(start).atZone(zone).dayOfWeek)
            val context = SuggestContext(key.second, weekend)

            val ranked = mapOf(
                METHOD_ENGINE to engine.rank(candidates, history, context, start, maxK, zone).map { it.song.id },
                METHOD_MOOD_TIME to engine.rank(candidates, emptyList(), context, start, maxK, zone).map { it.song.id },
                METHOD_POPULAR to popular(candidates, history, maxK),
                METHOD_RANDOM to candidates.map { it.id }.shuffled(Random(start)).take(maxK),
            )
            for ((method, ids) in ranked) {
                for (k in ks) {
                    rows += ReplayRow(key.first, key.second, weekend, history.size, truth.size, method, k, ids.take(k).count { it in truth })
                }
            }
        }
        return rows
    }

    private fun popular(candidates: List<SuggestCandidate>, history: List<ListenEvent>, k: Int): List<String> {
        val plays = history.filterNot { it.isSkip }.groupingBy { it.songId }.eachCount()
        return candidates.map { it.id }.sortedWith(compareByDescending<String> { plays[it] ?: 0 }.thenBy { it }).take(k)
    }

    /** Mean hit rate / recall / precision per method and k (the numbers for the thesis table). */
    data class Summary(val method: String, val k: Int, val sessions: Int, val hitRate: Double, val recall: Double, val precision: Double)

    fun summarize(rows: List<ReplayRow>): List<Summary> =
        rows.groupBy { it.method to it.k }.map { (key, rs) ->
            Summary(key.first, key.second, rs.size, rs.count { it.hit }.toDouble() / rs.size, rs.map { it.recall }.average(), rs.map { it.precision }.average())
        }.sortedWith(compareBy({ METHODS.indexOf(it.method) }, { it.k }))

    private fun hourOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour

    // ---- CSV (UTF-8, header + rows; consumed by docs/thesis/scripts/eval_suggest.py) ----

    const val HISTORY_HEADER = "song_id,artist,started_at_ms,iso_time,hour,weekday,listened_ms,fraction,skip"
    const val REPLAY_HEADER = "date,bucket,weekend,history_size,truth_size,method,k,hits"
    const val SUGGEST_HEADER = "bucket,weekend,rank,song_id,title,artist,score,mood,song,artist_aff,bucket_aff,recency,skip,valence,arousal"

    fun historyCsv(events: List<ListenEvent>, zone: ZoneId = ZoneId.systemDefault()): String =
        (listOf(HISTORY_HEADER) + events.sortedBy { it.startedAtMs }.map { e ->
            val t = Instant.ofEpochMilli(e.startedAtMs).atZone(zone)
            row(e.songId, e.artist, e.startedAtMs, t.toLocalDateTime(), t.hour, t.dayOfWeek.value, e.listenedMs, e.fraction, e.isSkip)
        }).joinToString("\n")

    fun replayCsv(rows: List<ReplayRow>): String =
        (listOf(REPLAY_HEADER) + rows.map { row(it.date, it.bucket.key, it.weekend, it.historySize, it.truthSize, it.method, it.k, it.hits) })
            .joinToString("\n")

    fun suggestionsCsv(byContext: List<Pair<SuggestContext, List<Suggestion>>>): String =
        (listOf(SUGGEST_HEADER) + byContext.flatMap { (ctx, list) ->
            list.mapIndexed { i, s ->
                row(
                    ctx.bucket.key, ctx.weekend, i + 1, s.song.id, s.song.title, s.song.artist, s.score,
                    s.parts.mood, s.parts.song, s.parts.artist, s.parts.bucket, s.parts.recency, s.parts.skip,
                    s.song.profile?.valence, s.song.profile?.arousal,
                )
            }
        }).joinToString("\n")

    private fun row(vararg v: Any?) = v.joinToString(",") { csv(it) }

    private fun csv(v: Any?): String = when (v) {
        null -> ""
        is Double -> String.format(Locale.ROOT, "%.4f", v)
        else -> v.toString().let { s -> if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
    }
}
