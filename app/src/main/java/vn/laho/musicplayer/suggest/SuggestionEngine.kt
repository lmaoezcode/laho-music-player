// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random
import vn.laho.musicplayer.mood.MoodProfile

/** A library song the engine may suggest; [profile] is null when it is not in the mood index. */
data class SuggestCandidate(val id: String, val title: String, val artist: String, val profile: MoodProfile? = null)

/** The additive terms of a score, kept for explanations and for the thesis CSV. */
data class ScoreParts(
    val mood: Double,
    val song: Double,
    val artist: Double,
    val bucket: Double,
    val recency: Double,
    val skip: Double,
)

data class Suggestion(val song: SuggestCandidate, val score: Double, val parts: ScoreParts)

/**
 * Weights of the score. Every weight multiplies a term that is already in 0..1, so the
 * values are directly comparable:
 *
 *   score = mood*fit + song*sat(song affinity) + artist*sat(artist affinity)
 *         + bucket*sat(affinity in this part of the day) - recency*recent - skip*skipRate
 */
data class Weights(
    /** Mood fit is the base signal and the only one available at cold start. */
    val mood: Double = 1.0,
    /** Songs the user finishes again and again; the strongest personal signal. */
    val song: Double = 0.5,
    /** Weaker than song: liking an artist helps discovery of that artist's other songs. */
    val artist: Double = 0.25,
    /** "What I listen to at this time of day": larger than artist because habits are time-bound. */
    val bucket: Double = 0.6,
    /**
     * Must outweigh a favourite's combined song+artist+bucket affinity (~0.5) plus a perfect mood
     * fit gap, so a song heard minutes ago drops below fresh candidates; it fades within a day.
     */
    val recency: Double = 1.5,
    /** Skips are explicit negative feedback, so they outweigh any single positive term. */
    val skip: Double = 0.9,
    /** When the user asked for a mood, honour it over habits. */
    val explicitMoodBoost: Double = 2.0,
    /** Random tie-breaking noise so the list does not freeze; small versus the gaps above. */
    val jitter: Double = 0.06,
) {
    companion object {
        /** "Daily mix": favourites first, mood fit relaxed, more variety. */
        val DAILY_MIX = Weights(mood = 0.5, song = 0.9, artist = 0.4, bucket = 0.2, recency = 1.0, skip = 0.9, jitter = 0.12)
    }
}

/**
 * Pure context-aware ranking: mood fit for the requested context, plus affinity learned
 * from listening history, minus penalties for songs heard recently or skipped. No Android
 * dependencies, so it is unit-tested and also replayed offline for the thesis evaluation.
 */
class SuggestionEngine(private val weights: Weights = Weights()) {

    /**
     * Ranks [candidates] for [context] as of [nowMs] using only [history] older than [nowMs]
     * (so the same code can replay the past). [jitterSeed] null disables the random noise.
     */
    fun rank(
        candidates: List<SuggestCandidate>,
        history: List<ListenEvent>,
        context: SuggestContext,
        nowMs: Long,
        count: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        jitterSeed: Long? = null,
    ): List<Suggestion> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val stats = HistoryStats.from(history, candidates, context, nowMs, zone)
        val (tv, ta) = target(context, stats)
        val random = jitterSeed?.let { Random(it) }
        val moodWeight = if (context.explicit) weights.mood * weights.explicitMoodBoost else weights.mood
        val scored = candidates.map { c ->
            val s = stats.songs[c.id]
            val ak = artistKey(c.artist)
            val parts = ScoreParts(
                mood = moodFit(c.profile, tv, ta, context),
                song = sat(s?.positive ?: 0.0),
                artist = sat(stats.artistPositive[ak] ?: 0.0),
                bucket = sat(s?.inBucket ?: 0.0),
                recency = s?.lastStartMs?.let { recent(nowMs - it) } ?: 0.0,
                skip = skipPenalty(s, stats.artistSkipRate[ak]),
            )
            val noise = random?.let { (it.nextDouble() - 0.5) * 2 * weights.jitter } ?: 0.0
            val score = moodWeight * parts.mood + weights.song * parts.song + weights.artist * parts.artist +
                weights.bucket * parts.bucket - weights.recency * parts.recency - weights.skip * parts.skip + noise
            Suggestion(c, score, parts)
        }.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.song.id })
        return capPerArtist(scored, count)
    }

    /** Daily mix: same machinery with [Weights.DAILY_MIX] and a seed that changes once a day. */
    fun dailyMix(
        candidates: List<SuggestCandidate>,
        history: List<ListenEvent>,
        nowMs: Long,
        count: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Suggestion> {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        return SuggestionEngine(Weights.DAILY_MIX)
            .rank(candidates, history, SuggestContext.at(nowMs, zone), nowMs, count, zone, jitterSeed = day)
    }

    /** Clock prior, pulled towards the mood the user really plays in this bucket once there is enough history. */
    private fun target(context: SuggestContext, stats: HistoryStats): Pair<Double, Double> {
        val (pv, pa) = context.priorTarget()
        if (context.explicit || stats.bucketWeight < MIN_LEARN_WEIGHT) return pv to pa
        val lv = stats.bucketValence / stats.bucketWeight
        val la = stats.bucketArousal / stats.bucketWeight
        return (pv + (lv - pv) * LEARN_BLEND) to (pa + (la - pa) * LEARN_BLEND)
    }

    private fun moodFit(p: MoodProfile?, tv: Double, ta: Double, context: SuggestContext): Double {
        if (p == null || (p.valence == null && p.arousal == null)) return UNKNOWN_MOOD_FIT
        // Only the axes the user asked about count; a missing song coordinate is neutral (0).
        val useV = !context.explicit || context.mood != null || context.valence != null
        val useA = !context.explicit || context.mood != null || context.arousal != null
        val dv = if (useV) (p.valence ?: 0.0) - tv else 0.0
        val da = if (useA) (p.arousal ?: 0.0) - ta else 0.0
        val gauss = exp(-(dv * dv + da * da) / (2 * MOOD_SIGMA * MOOD_SIGMA))
        val label = if (context.mood != null && context.mood in p.moods) LABEL_BONUS else 0.0
        return gauss + label
    }

    private fun skipPenalty(s: SongStats?, artistSkipRate: Double?): Double {
        val own = if (s == null) 0.0 else s.skips / (s.plays + 1.0) // Laplace: 1 skip of 1 play = 0.5
        return (own + (artistSkipRate ?: 0.0) * ARTIST_SKIP_SHARE).coerceAtMost(1.0)
    }

    /** At most [MAX_PER_ARTIST] songs per artist in the list, unless nothing else is left. */
    private fun capPerArtist(sorted: List<Suggestion>, count: Int): List<Suggestion> {
        val perArtist = HashMap<String, Int>()
        val picked = ArrayList<Suggestion>(count)
        val deferred = ArrayList<Suggestion>()
        for (s in sorted) {
            if (picked.size >= count) break
            val key = artistKey(s.song.artist)
            if ((perArtist[key] ?: 0) < MAX_PER_ARTIST) {
                perArtist[key] = (perArtist[key] ?: 0) + 1
                picked += s
            } else {
                deferred += s
            }
        }
        if (picked.size < count) picked += deferred.take(count - picked.size)
        return picked
    }

    private fun sat(x: Double) = x / (x + SATURATION)
    private fun recent(ageMs: Long): Double = exp(-(ageMs.coerceAtLeast(0) / HOUR_MS) / RECENCY_TAU_HOURS)

    private class SongStats {
        var positive = 0.0
        var inBucket = 0.0
        var plays = 0.0
        var skips = 0.0
        var lastStartMs: Long? = null
    }

    /** Time-decayed sums over the history, computed once per ranking. */
    private class HistoryStats(
        val songs: Map<String, SongStats>,
        val artistPositive: Map<String, Double>,
        val artistSkipRate: Map<String, Double>,
        val bucketWeight: Double,
        val bucketValence: Double,
        val bucketArousal: Double,
    ) {
        companion object {
            fun from(
                history: List<ListenEvent>,
                candidates: List<SuggestCandidate>,
                context: SuggestContext,
                nowMs: Long,
                zone: ZoneId,
            ): HistoryStats {
                val profiles = candidates.associate { it.id to it.profile }
                val songs = HashMap<String, SongStats>()
                val artistPos = HashMap<String, Double>()
                val artistPlays = HashMap<String, Double>()
                val artistSkips = HashMap<String, Double>()
                var bw = 0.0
                var bv = 0.0
                var ba = 0.0
                for (e in history) {
                    if (e.startedAtMs >= nowMs) continue // never peek at the future when replaying
                    val ageDays = (nowMs - e.startedAtMs) / DAY_MS
                    val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
                    val s = songs.getOrPut(e.songId) { SongStats() }
                    val completion = e.completion
                    s.plays += decay
                    if (e.isSkip) s.skips += decay
                    s.positive += decay * completion
                    s.lastStartMs = maxOf(s.lastStartMs ?: Long.MIN_VALUE, e.startedAtMs)
                    val ak = artistKey(e.artist)
                    artistPos[ak] = (artistPos[ak] ?: 0.0) + decay * completion
                    artistPlays[ak] = (artistPlays[ak] ?: 0.0) + decay
                    if (e.isSkip) artistSkips[ak] = (artistSkips[ak] ?: 0.0) + decay

                    val t = Instant.ofEpochMilli(e.startedAtMs).atZone(zone)
                    if (TimeBucket.ofHour(t.hour) == context.bucket) {
                        val dayMatch = if (SuggestContext.isWeekend(t.dayOfWeek) == context.weekend) 1.0 else OTHER_DAYTYPE_SHARE
                        val w = decay * completion * dayMatch
                        s.inBucket += w
                        val p = profiles[e.songId]
                        val pv = p?.valence
                        val pa = p?.arousal
                        if (w > 0 && pv != null && pa != null) {
                            bw += w
                            bv += w * pv
                            ba += w * pa
                        }
                    }
                }
                val skipRate = artistPlays.mapValues { (k, plays) -> (artistSkips[k] ?: 0.0) / (plays + 1.0) }
                return HistoryStats(songs, artistPos, skipRate, bw, bv, ba)
            }
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000.0
        private const val DAY_MS = 86_400_000.0

        /** A listen loses half its weight every 30 days: taste drifts, but not week to week. */
        const val HALF_LIFE_DAYS = 30.0

        /** x/(x+k): two full plays already give 0.5, so one obsession cannot dominate the list. */
        const val SATURATION = 2.0

        /** A song heard an hour ago keeps ~0.88 of the penalty; after a day it is ~0.05. */
        const val RECENCY_TAU_HOURS = 8.0

        /** Gaussian width on the valence/arousal plane: 0.6 away still scores about 0.6. */
        const val MOOD_SIGMA = 0.6

        /** Exact label match (e.g. "tập trung") helps beyond raw distance. */
        const val LABEL_BONUS = 0.25

        /** Songs not yet analysed: below an average match so they appear mainly through history. */
        const val UNKNOWN_MOOD_FIT = 0.25

        /** Listening on the other kind of day (weekday vs weekend) still counts, at 60%. */
        const val OTHER_DAYTYPE_SHARE = 0.6

        /** Need this much decayed listening in the bucket before trusting what it says about mood. */
        const val MIN_LEARN_WEIGHT = 4.0

        /** Learned bucket mood moves the target half-way from the clock prior. */
        const val LEARN_BLEND = 0.5

        /** Skipping an artist's songs lowers their other songs, at half the strength. */
        const val ARTIST_SKIP_SHARE = 0.5

        const val MAX_PER_ARTIST = 3
    }
}

internal fun artistKey(artist: String) = artist.trim().lowercase()
