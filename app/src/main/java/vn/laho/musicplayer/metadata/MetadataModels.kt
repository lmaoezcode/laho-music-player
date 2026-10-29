// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import vn.laho.musicplayer.assistant.VietnameseText

/** What we know about a local song when asking an online source for its metadata. */
data class LookupQuery(
    val title: String,
    /** Null/blank or a placeholder ("<unknown>") when the file has no artist tag. */
    val artist: String?,
    /** Length of the local file; used to tell versions (radio edit, live, …) apart. */
    val durationMs: Long?,
) {
    /**
     * Cleans the query for searching: strips track numbers, file extensions and
     * "(Official Video)"-style noise, and splits "Artist - Title" when the artist is unknown
     * (common for downloaded files).
     */
    fun normalized(): LookupQuery {
        var t = TagText.cleanTitle(title)
        var a = artist?.trim()?.takeUnless { TagText.isUnknown(it) }
        if (a == null) {
            val dash = Regex("\\s+[-–—]\\s+").find(t)
            if (dash != null) {
                val left = t.substring(0, dash.range.first).trim()
                val right = t.substring(dash.range.last + 1).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    a = left
                    t = right
                }
            }
        }
        return LookupQuery(t, a, durationMs?.takeIf { it > 0 })
    }
}

/** One suggestion from an online source, ready to pre-fill the tag editor. */
data class MetadataCandidate(
    /** Human-readable source name shown to the user, e.g. "MusicBrainz". */
    val source: String,
    /** 0..1, from [MatchScorer]. */
    val confidence: Double,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val durationMs: Long?,
    val recordingMbid: String,
    val releaseMbid: String?,
    val releaseGroupMbid: String?,
    val artistMbids: List<String> = emptyList(),
) {
    val confidencePercent: Int get() = Math.round(confidence * 100).toInt().coerceIn(0, 100)

    /** Cover Art Archive URLs to try, best first. */
    fun coverUrls(base: String = CoverArtArchive.BASE_URL): List<String> =
        CoverArtArchive.frontUrls(releaseMbid, releaseGroupMbid, base)
}

/** Helpers for tag text: placeholders and title clean-up. */
object TagText {

    private val PLACEHOLDERS = setOf(
        "", "<unknown>", "unknown", "unknown artist", "unknown album", "unknown title",
        "khong ro", "khong xac dinh", "nghe si khong xac dinh", "n/a", "-",
    )

    /** True for blank tags and the placeholders Android/Rhythm use for missing tags. */
    fun isUnknown(value: String?): Boolean =
        value == null || VietnameseText.fold(value.trim()) in PLACEHOLDERS

    private val NOISE_BRACKET = Regex(
        "\\s*[(\\[{][^)\\]}]*(official|lyric|video|audio|visuali[sz]er|\\bmv\\b|\\bhd\\b|\\b4k\\b|m/v)[^)\\]}]*[)\\]}]",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_TRACK = Regex("^\\s*\\d{1,3}\\s*([.)_-]|\\s-\\s)\\s*")
    private val EXTENSION = Regex("\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|alac|ape|dsf|dff)$", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String {
        var t = title.trim().replace(EXTENSION, "")
        t = t.replace(NOISE_BRACKET, "")
        // Only strip a leading number when something meaningful remains ("1979" stays).
        val stripped = t.replace(LEADING_TRACK, "")
        if (stripped.any { it.isLetter() }) t = stripped
        return t.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }
}

/** Cover Art Archive (coverartarchive.org): free cover images linked to MusicBrainz releases. */
object CoverArtArchive {
    const val BASE_URL = "https://coverartarchive.org"

    /** Front cover (500 px thumbnail) of the release, falling back to the release group. */
    fun frontUrls(releaseMbid: String?, releaseGroupMbid: String?, base: String = BASE_URL): List<String> {
        val b = base.trimEnd('/')
        return listOfNotNull(
            releaseMbid?.let { "$b/release/$it/front-500" },
            releaseGroupMbid?.let { "$b/release-group/$it/front-500" },
        )
    }
}

class MetadataLookupException(message: String, val httpCode: Int? = null) : Exception(message)
