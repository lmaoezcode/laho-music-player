// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import vn.laho.musicplayer.assistant.VietnameseText

/**
 * Ranks MusicBrainz recordings against a local song.
 *
 * Confidence (0..1) is a weighted mix of
 * - title similarity (diacritics-insensitive, ignoring "(feat. …)"/"(Remastered)" noise),
 * - artist similarity (skipped when the local artist tag is unknown),
 * - duration: full marks within [DURATION_TOLERANCE_MS], dropping to 0 at 15 s,
 * - release quality: official album releases beat singles, compilations and bootlegs.
 * Weights are renormalised over the signals that are available. A duration mismatch caps the
 * confidence (probably another version: live, radio edit, remix), as does an unknown artist.
 */
object MatchScorer {

    const val SOURCE = "MusicBrainz"
    const val DURATION_TOLERANCE_MS = 3_000L
    private const val DURATION_ZERO_MS = 15_000L

    private const val W_TITLE = 0.45
    private const val W_ARTIST = 0.30
    private const val W_DURATION = 0.15
    private const val W_RELEASE = 0.10

    /** Confidence cap when durations differ by more than [DURATION_TOLERANCE_MS]. */
    const val CAP_DURATION_MISMATCH = 0.6
    /** Confidence cap when the local artist is unknown (title-only match). */
    const val CAP_UNKNOWN_ARTIST = 0.8

    /** Candidates from [recordings], best first, at most one per recording. */
    fun rank(query: LookupQuery, recordings: List<MbRecording>): List<MetadataCandidate> {
        val seen = HashSet<String>()
        return recordings
            .map { candidate(query, it) }
            .sortedWith(compareByDescending<MetadataCandidate> { it.confidence }.thenBy { it.year ?: Int.MAX_VALUE })
            .filter { c ->
                // Drop exact duplicates (same recording, or same title/artist/album).
                seen.add(c.recordingMbid) &&
                    seen.add("${key(c.title)}|${key(c.artist)}|${key(c.album.orEmpty())}")
            }
    }

    /**
     * True when the user should pick among candidates rather than accept the top one: low
     * confidence, or a close runner-up that would write different album/year.
     */
    fun isAmbiguous(ranked: List<MetadataCandidate>): Boolean {
        val top = ranked.firstOrNull() ?: return false
        if (top.confidence < 0.85) return ranked.size > 1 || top.confidence < 0.6
        val second = ranked.getOrNull(1) ?: return false
        return top.confidence - second.confidence < 0.08 &&
            (key(second.album.orEmpty()) != key(top.album.orEmpty()) || second.year != top.year)
    }

    fun candidate(query: LookupQuery, rec: MbRecording): MetadataCandidate {
        val q = query.normalized()
        val release = bestRelease(rec.releases)
        val titleScore = titleSimilarity(q.title, rec.title)
        val artistScore = q.artist?.let { artistSimilarity(it, rec.artistName) }
        val durationDiff = if (q.durationMs != null && rec.lengthMs != null) abs(q.durationMs - rec.lengthMs) else null
        val durationScore = durationDiff?.let(::durationScore)
        val releaseScore = release?.let(::releaseScore) ?: 0.0

        var weighted = W_TITLE * titleScore + W_RELEASE * releaseScore
        var weights = W_TITLE + W_RELEASE
        if (artistScore != null) {
            weighted += W_ARTIST * artistScore
            weights += W_ARTIST
        }
        if (durationScore != null) {
            weighted += W_DURATION * durationScore
            weights += W_DURATION
        }
        var confidence = weighted / weights
        if (durationDiff != null && durationDiff > DURATION_TOLERANCE_MS) confidence = min(confidence, CAP_DURATION_MISMATCH)
        if (artistScore == null) confidence = min(confidence, CAP_UNKNOWN_ARTIST)
        // A bad title or artist match should never look trustworthy, however good the rest is.
        if (titleScore < 0.5) confidence = min(confidence, titleScore)
        if (artistScore != null && artistScore < 0.5) confidence = min(confidence, artistScore)

        return MetadataCandidate(
            source = SOURCE,
            confidence = Math.round(confidence * 1000) / 1000.0,
            title = rec.title,
            artist = rec.artistName,
            album = release?.title?.takeIf { it.isNotBlank() },
            albumArtist = release?.artistCredit?.takeIf { it.isNotEmpty() }?.let(MbCredit::join),
            year = MusicBrainzParser.year(rec.firstReleaseDate) ?: MusicBrainzParser.year(release?.date),
            trackNumber = release?.trackNumber,
            discNumber = release?.discNumber,
            genre = MusicBrainzParser.bestGenre(rec.tags),
            durationMs = rec.lengthMs,
            recordingMbid = rec.id,
            releaseMbid = release?.id,
            releaseGroupMbid = release?.releaseGroupId,
            artistMbids = rec.artistCredit.mapNotNull { it.artistId },
        )
    }

    /** Official studio albums first, then EPs/singles, compilations/live, then the rest; earliest first. */
    fun bestRelease(releases: List<MbRelease>): MbRelease? =
        releases.sortedWith(
            compareByDescending<MbRelease> { releaseScore(it) }
                .thenBy { it.date?.takeIf { d -> d.length >= 4 } ?: "9999" },
        ).firstOrNull()

    fun releaseScore(r: MbRelease): Double {
        val official = r.status.equals("Official", ignoreCase = true)
        val secondary = r.secondaryTypes.isNotEmpty()
        val type = r.primaryType?.lowercase()
        return when {
            !official -> 0.2
            type == "album" && !secondary -> 1.0
            (type == "ep" || type == "single") && !secondary -> 0.75
            type == "album" -> 0.5 // compilation, live, soundtrack, remix album
            else -> 0.4
        }
    }

    fun durationScore(diffMs: Long): Double = when {
        diffMs <= DURATION_TOLERANCE_MS -> 1.0
        diffMs >= DURATION_ZERO_MS -> 0.0
        else -> 1.0 - (diffMs - DURATION_TOLERANCE_MS).toDouble() / (DURATION_ZERO_MS - DURATION_TOLERANCE_MS)
    }

    private val FEAT = Regex("\\s(feat\\.?|ft\\.?|featuring|x|with)\\s.*$", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("\\s*[(\\[{][^)\\]}]*[)\\]}]")

    /** Title similarity 0..1; bracketed suffixes count a little less than the main title. */
    fun titleSimilarity(local: String, remote: String): Double {
        val full = similarity(local, remote)
        val bare = similarity(stripExtras(local), stripExtras(remote))
        return max(full, 0.95 * bare)
    }

    fun artistSimilarity(local: String, remote: String): Double {
        val a = tokens(local)
        val b = tokens(remote)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val direct = similarity(local, remote)
        // "A, B" locally vs "A" (or "A feat. B") remotely: every remote word is in the local tag.
        val main = tokens(stripExtras(remote))
        val containment = if (main.isNotEmpty() && a.containsAll(main)) 0.9 else 0.0
        val reverse = if (b.containsAll(a)) 0.9 else 0.0
        return max(direct, max(containment, reverse))
    }

    private fun stripExtras(s: String): String = s.replace(BRACKETS, " ").replace(FEAT, "").trim()

    private fun tokens(s: String): Set<String> = VietnameseText.tokens(s).toSet()

    private fun key(s: String): String = VietnameseText.tokens(s).joinToString(" ")

    /** Max of token Dice and character edit similarity, on folded text. */
    fun similarity(a: String, b: String): Double {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        val ta = ka.split(' ').toSet()
        val tb = kb.split(' ').toSet()
        val dice = 2.0 * ta.intersect(tb).size / (ta.size + tb.size)
        val edit = 1.0 - levenshtein(ka, kb).toDouble() / max(ka.length, kb.length)
        return max(dice, edit).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
