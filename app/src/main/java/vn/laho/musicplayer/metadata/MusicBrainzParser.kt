// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A recording from the MusicBrainz search API (only the fields LaHo uses). */
data class MbRecording(
    val id: String,
    val title: String,
    val lengthMs: Long?,
    val artistCredit: List<MbCredit>,
    val firstReleaseDate: String?,
    val releases: List<MbRelease>,
    val tags: List<MbTag>,
    /** MusicBrainz' own search score 0..100. */
    val score: Int,
) {
    val artistName: String get() = MbCredit.join(artistCredit)
}

data class MbCredit(val name: String, val joinPhrase: String, val artistId: String?) {
    companion object {
        fun join(credits: List<MbCredit>): String = credits.joinToString("") { it.name + it.joinPhrase }.trim()
    }
}

data class MbRelease(
    val id: String,
    val title: String,
    /** "Official", "Promotion", "Bootleg", "Pseudo-Release" or null. */
    val status: String?,
    /** "Album", "Single", "EP", "Broadcast", "Other" or null. */
    val primaryType: String?,
    /** e.g. "Compilation", "Live", "Soundtrack", "Remix". */
    val secondaryTypes: List<String>,
    val date: String?,
    val artistCredit: List<MbCredit>,
    val releaseGroupId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
)

data class MbTag(val name: String, val count: Int)

/** Parses MusicBrainz web service v2 JSON (fmt=json). */
object MusicBrainzParser {

    private val JSON = Json { ignoreUnknownKeys = true }

    fun parseRecordingSearch(body: String): List<MbRecording> {
        val root = JSON.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val recordings = root["recordings"] as? JsonArray ?: return emptyList()
        return recordings.mapNotNull { (it as? JsonObject)?.let(::recording) }
    }

    /**
     * Best genre of a release group lookup (inc=genres+tags): curated genres first, then
     * folksonomy tags, by vote count.
     */
    fun parseGenre(body: String): String? {
        val root = JSON.parseToJsonElement(body) as? JsonObject ?: return null
        return bestGenre(tags(root["genres"])) ?: bestGenre(tags(root["tags"]))
    }

    fun bestGenre(tags: List<MbTag>): String? = tags
        .filter { it.count > 0 && isGenreLike(it.name) }
        .maxWithOrNull(compareBy<MbTag> { it.count }.thenByDescending { it.name })
        ?.name
        ?.let(::titleCase)

    private val NOT_GENRES = setOf("seen live", "favorites", "favourite", "my favorite", "spotify", "owned")

    private fun isGenreLike(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.length in 2..40 && n.any { it.isLetter() } && n !in NOT_GENRES && !n.matches(Regex(".*\\d{4}.*"))
    }

    /** "hip hop" → "Hip Hop", "v-pop" → "V-Pop", "r&b" → "R&B". */
    internal fun titleCase(s: String): String {
        val sb = StringBuilder(s.length)
        var upper = true
        for (ch in s.trim()) {
            sb.append(if (upper) ch.uppercaseChar() else ch)
            upper = !ch.isLetterOrDigit() && ch != '\''
        }
        return sb.toString()
    }

    private fun recording(o: JsonObject): MbRecording? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        return MbRecording(
            id = id,
            title = title,
            lengthMs = (o["length"] as? JsonPrimitive)?.longOrNull,
            artistCredit = credits(o["artist-credit"]),
            firstReleaseDate = o.str("first-release-date"),
            releases = (o["releases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::release) },
            tags = tags(o["tags"]),
            score = (o["score"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    private fun release(o: JsonObject): MbRelease? {
        val id = o.str("id") ?: return null
        val group = o["release-group"] as? JsonObject
        val medium = (o["media"] as? JsonArray)?.firstOrNull() as? JsonObject
        val track = (medium?.get("track") as? JsonArray)?.firstOrNull() as? JsonObject
        val trackNumber = track?.str("number")?.trim()?.toIntOrNull()
            ?: (medium?.get("track-offset") as? JsonPrimitive)?.intOrNull?.plus(1)
        return MbRelease(
            id = id,
            title = o.str("title").orEmpty(),
            status = o.str("status"),
            primaryType = group?.str("primary-type"),
            secondaryTypes = (group?.get("secondary-types") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            date = o.str("date"),
            artistCredit = credits(o["artist-credit"]),
            releaseGroupId = group?.str("id"),
            trackNumber = trackNumber?.takeIf { it > 0 },
            discNumber = (medium?.get("position") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
        )
    }

    private fun credits(el: JsonElement?): List<MbCredit> =
        (el as? JsonArray).orEmpty().mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            val artist = o["artist"] as? JsonObject
            val name = o.str("name") ?: artist?.str("name") ?: return@mapNotNull null
            MbCredit(name, o.str("joinphrase").orEmpty(), artist?.str("id"))
        }

    private fun tags(el: JsonElement?): List<MbTag> =
        (el as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            MbTag(name, (o["count"] as? JsonPrimitive)?.intOrNull ?: 0)
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Year from "2019-05-03", "2019-05" or "2019". */
    fun year(date: String?): Int? =
        date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()?.takeIf { it in 1000..2999 }
}
