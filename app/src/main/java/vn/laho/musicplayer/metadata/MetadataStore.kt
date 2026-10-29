// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A metadata suggestion kept in LaHo's own database. The audio file is never modified by the
 * batch job; these rows only feed LaHo features (e.g. the mood index) and can be reviewed.
 */
@Entity(tableName = "metadata_suggestions")
data class MetadataSuggestion(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    /** "found" (a candidate above the threshold was stored) or "not_found". */
    val status: String,
    val source: String? = null,
    val confidence: Double? = null,
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val releaseGroupMbid: String? = null,
    val updatedAt: Long = 0,
) {
    val found: Boolean get() = status == STATUS_FOUND

    companion object {
        const val STATUS_FOUND = "found"
        const val STATUS_NOT_FOUND = "not_found"

        fun found(songId: String, title: String, artist: String, c: MetadataCandidate, now: Long) = MetadataSuggestion(
            songId, title, artist, STATUS_FOUND, c.source, c.confidence, c.title, c.artist, c.album, c.albumArtist,
            c.year, c.trackNumber, c.discNumber, c.genre, c.recordingMbid, c.releaseMbid, c.releaseGroupMbid, now,
        )

        fun notFound(songId: String, title: String, artist: String, now: Long) =
            MetadataSuggestion(songId, title, artist, STATUS_NOT_FOUND, updatedAt = now)
    }
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata_suggestions")
    suspend fun all(): List<MetadataSuggestion>

    @Upsert
    suspend fun upsert(rows: List<MetadataSuggestion>)

    @Query("DELETE FROM metadata_suggestions")
    suspend fun clear()
}

/** How stored suggestions fill gaps in file tags (file tags always win). */
object SuggestionFill {
    fun genre(tagGenre: String?, s: MetadataSuggestion?): String? =
        tagGenre?.takeUnless { TagText.isUnknown(it) } ?: s?.takeIf { it.found }?.genre

    fun year(tagYear: Int, s: MetadataSuggestion?): Int? =
        tagYear.takeIf { it > 0 } ?: s?.takeIf { it.found }?.year
}

/** Storage abstraction so the batch job can be tested without Room. */
interface MetadataStore {
    suspend fun all(): List<MetadataSuggestion>
    suspend fun upsert(rows: List<MetadataSuggestion>)
    suspend fun clear()
}

class RoomMetadataStore(private val dao: MetadataDao) : MetadataStore {
    override suspend fun all() = dao.all()
    override suspend fun upsert(rows: List<MetadataSuggestion>) = dao.upsert(rows)
    override suspend fun clear() = dao.clear()
}

/** Stored suggestions of the app's LaHo database, by song id (empty on any storage error). */
suspend fun metadataSuggestionsById(context: android.content.Context): Map<String, MetadataSuggestion> =
    try {
        vn.laho.musicplayer.mood.metadataStore(context).all().associateBy { it.songId }
    } catch (e: Exception) {
        emptyMap()
    }
