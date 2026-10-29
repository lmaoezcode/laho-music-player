// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import vn.laho.musicplayer.assistant.VietnameseText
import vn.laho.musicplayer.mood.Mood

/**
 * Coarse part of the day. Listening taste is much more stable per part of the day than per
 * exact hour, and four buckets keep enough history in each one to learn from.
 *
 * [priorValence]/[priorArousal] are the default target on the mood plane when the user has
 * no history yet (cold start): bright and gently upbeat in the morning, calm and low-energy
 * late at night. They are a prior, not a rule: with enough history the engine blends in the
 * mood the user actually listens to in that bucket.
 */
enum class TimeBucket(val key: String, val label: String, val priorValence: Double, val priorArousal: Double) {
    MORNING("sang", "buổi sáng", 0.45, 0.25),
    AFTERNOON("chieu", "buổi chiều", 0.25, 0.15),
    EVENING("toi", "buổi tối", 0.35, -0.10),
    LATE_NIGHT("khuya", "đêm khuya", 0.0, -0.60);

    companion object {
        /** 05-10 morning, 11-16 afternoon, 17-21 evening, 22-04 late night. */
        fun ofHour(hour: Int): TimeBucket = when (hour) {
            in 5..10 -> MORNING
            in 11..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> LATE_NIGHT
        }

        fun fromKey(key: String): TimeBucket? = values().firstOrNull { it.key == key }
    }
}

/**
 * What the user wants to hear: a [bucket] and weekday/weekend, optionally with an explicit
 * mood (a [Mood] label or a valence/arousal point). An explicit mood overrides the clock.
 */
data class SuggestContext(
    val bucket: TimeBucket,
    val weekend: Boolean,
    val mood: Mood? = null,
    val valence: Double? = null,
    val arousal: Double? = null,
) {
    /** True when the user asked for a mood, as opposed to "whatever fits the time". */
    val explicit: Boolean get() = mood != null || valence != null || arousal != null

    /** Target point on the mood plane for this context (before history is blended in). */
    fun priorTarget(): Pair<Double, Double> {
        val weekendBoost = if (weekend && !explicit) WEEKEND_BOOST else 0.0
        val v = valence ?: mood?.valence ?: (bucket.priorValence + weekendBoost)
        val a = arousal ?: mood?.arousal ?: (bucket.priorArousal + weekendBoost)
        return v.coerceIn(-1.0, 1.0) to a.coerceIn(-1.0, 1.0)
    }

    val label: String
        get() = mood?.label ?: if (explicit) "theo yêu cầu" else bucket.label + if (weekend) " cuối tuần" else ""

    companion object {
        /** Weekends are a little more upbeat and energetic than weekdays at the same hour. */
        const val WEEKEND_BOOST = 0.10

        fun at(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), mood: Mood? = null): SuggestContext {
            val t = Instant.ofEpochMilli(nowMs).atZone(zone)
            return SuggestContext(TimeBucket.ofHour(t.hour), isWeekend(t.dayOfWeek), mood)
        }

        fun isWeekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

        private val BUCKET_PHRASES: List<Pair<String, TimeBucket>> = listOf(
            "toi khuya" to TimeBucket.LATE_NIGHT, "dem khuya" to TimeBucket.LATE_NIGHT, "nua dem" to TimeBucket.LATE_NIGHT,
            "khuya" to TimeBucket.LATE_NIGHT, "ban dem" to TimeBucket.LATE_NIGHT, "dem nay" to TimeBucket.LATE_NIGHT,
            "buoi sang" to TimeBucket.MORNING, "sang nay" to TimeBucket.MORNING, "sang som" to TimeBucket.MORNING,
            "buoi trua" to TimeBucket.AFTERNOON, "trua nay" to TimeBucket.AFTERNOON,
            "buoi chieu" to TimeBucket.AFTERNOON, "chieu nay" to TimeBucket.AFTERNOON,
            "buoi toi" to TimeBucket.EVENING, "toi nay" to TimeBucket.EVENING,
        ).sortedByDescending { it.first.length }

        /**
         * Reads a free-text context such as "buổi sáng", "tối khuya", "tập trung" or "buồn";
         * blank text means "now". A part-of-day phrase sets the bucket, a mood word sets the mood.
         */
        fun parse(text: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SuggestContext {
            val base = at(nowMs, zone)
            if (text.isNullOrBlank()) return base
            val folded = " " + VietnameseText.tokens(text).joinToString(" ") + " "
            val bucket = BUCKET_PHRASES.firstOrNull { " ${it.first} " in folded }?.second
            val mood = Mood.findIn(text)
            return base.copy(bucket = bucket ?: base.bucket, mood = mood)
        }
    }
}

/** Local date of the listening session an event belongs to (small hours count for the evening before). */
internal fun sessionDate(ms: Long, zone: ZoneId): LocalDate {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return if (t.hour < 5) t.toLocalDate().minusDays(1) else t.toLocalDate()
}
