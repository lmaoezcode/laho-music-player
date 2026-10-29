// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.laho.musicplayer.assistant.VietnameseText
import vn.laho.musicplayer.drive.AppDataFile
import vn.laho.musicplayer.drive.AppDataStore
import vn.laho.musicplayer.dsp.PeqPreset
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqSource
import vn.laho.musicplayer.metadata.MetadataStore
import vn.laho.musicplayer.mood.IndexableSong
import vn.laho.musicplayer.mood.MoodStore

/**
 * Song matching rule across devices (local MediaStore ids differ):
 *  1. title and artist are compared accent-/case-/punctuation-insensitively ([normalize]);
 *  2. among local songs with the same title+artist, if the backup knows the duration, only
 *     songs within [DURATION_TOLERANCE_MS] qualify and the closest wins (different versions,
 *     e.g. live vs studio, stay apart);
 *  3. if the backup has no duration, a match is accepted only when exactly one local song
 *     has that title+artist (ambiguous matches are skipped, never guessed).
 */
object SongMatcher {
    const val DURATION_TOLERANCE_MS = 3_000L

    fun normalize(text: String): String = VietnameseText.tokens(text).joinToString(" ")

    fun match(key: SongKey, library: List<IndexableSong>): IndexableSong? {
        val t = normalize(key.title)
        val a = normalize(key.artist)
        val same = library.filter { normalize(it.title) == t && normalize(it.artist) == a }
        if (same.isEmpty()) return null
        val wanted = key.durationMs ?: return same.singleOrNull()
        return same.filter { it.durationMs <= 0 || kotlin.math.abs(it.durationMs - wanted) <= DURATION_TOLERANCE_MS }
            .minByOrNull { if (it.durationMs <= 0) DURATION_TOLERANCE_MS else kotlin.math.abs(it.durationMs - wanted) }
    }
}

/** What a restore changed (or would change). Nothing is ever deleted. */
data class RestoreReport(
    val presetsAdded: Int,
    val presetsSkipped: Int,
    val activeRestored: Boolean,
    val moodsMerged: Int,
    val moodsKeptLocal: Int,
    val moodsUnmatched: Int,
    val suggestionsMerged: Int,
    val suggestionsKeptLocal: Int,
    val suggestionsUnmatched: Int,
)

data class BackupInfo(val file: AppDataFile, val document: BackupDocument)

/** Creates, lists, previews and restores LaHo backups in the Drive app data folder. */
class BackupService(
    private val drive: AppDataStore,
    private val peq: PeqAccess,
    private val moods: MoodStore,
    private val suggestions: MetadataStore,
    private val library: suspend () -> List<IndexableSong>,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val keep: Int = KEEP_BACKUPS,
) {
    /** Newest first. */
    suspend fun list(): List<AppDataFile> = drive.list(PREFIX).sortedByDescending { it.name }

    /** Builds a backup of the current data and uploads it, then deletes all but the newest [keep]. */
    suspend fun backupNow(): BackupInfo = withContext(dispatcher) {
        val songs = library()
        val byId = songs.associateBy { it.id }
        fun key(id: String, title: String, artist: String) =
            SongKey(title, artist, byId[id]?.durationMs?.takeIf { it > 0 })
        val doc = BackupDocument(
            BackupCodec.SCHEMA_VERSION, appVersion, now(), peq.current(),
            moods.all().map { BackupMood(key(it.songId, it.title, it.artist), it) },
            suggestions.all().map { BackupSuggestion(key(it.songId, it.title, it.artist), it) },
        )
        val file = drive.upload(fileName(doc.createdAtMs), BackupCodec.encode(doc), "application/json")
        prune()
        BackupInfo(file, doc)
    }

    /** Downloads and validates the newest backup, or null when there is none. */
    suspend fun latest(): BackupInfo? = withContext(dispatcher) {
        val file = list().firstOrNull() ?: return@withContext null
        BackupInfo(file, BackupCodec.decode(drive.download(file.id, BackupLimits.MAX_BYTES.toLong())))
    }

    /** Merges [doc] into local data on a background dispatcher. Local data is never deleted or overwritten. */
    suspend fun restore(doc: BackupDocument): RestoreReport = withContext(dispatcher) {
        val songs = library()

        // PEQ: add presets by name; a same-name preset with a different curve is kept as "name (2)".
        val local = peq.current()
        var merged = local
        var added = 0
        var skipped = 0
        for (p in doc.peq.presets) {
            val same = merged.presets.firstOrNull { it.name == p.name }
            when {
                same == null -> { merged = merged.withPreset(p); added++ }
                same.config == p.config -> skipped++
                merged.presets.any { it.config == p.config } -> skipped++
                else -> { merged = merged.withPreset(PeqPreset(merged.uniqueName(p.name), p.config)); added++ }
            }
        }
        // The active choice is taken from the backup only on a device without presets of its own.
        val restoreActive = local.presets.isEmpty() && doc.peq.activeName != null && doc.peq.source == PeqSource.PRESET
        if (restoreActive) {
            val name = doc.peq.activeName
            if (merged.presets.any { it.name == name }) merged = merged.copy(source = PeqSource.PRESET, activeName = name)
        }
        if (merged != local) peq.replace(merged)

        // Mood index: newest row wins per song; unmatched songs are skipped.
        val localMoods = moods.all().associateBy { it.songId }
        var moodMerged = 0
        var moodKept = 0
        var moodMiss = 0
        val moodWrites = ArrayList<vn.laho.musicplayer.mood.MoodRecord>()
        for (m in doc.moods) {
            val song = SongMatcher.match(m.key, songs)
            if (song == null) { moodMiss++; continue }
            val existing = localMoods[song.id]
            if (existing != null && existing.updatedAt >= m.record.updatedAt) { moodKept++; continue }
            moodWrites += m.record.copy(songId = song.id, title = song.title, artist = song.artist)
            moodMerged++
        }
        if (moodWrites.isNotEmpty()) moods.upsert(moodWrites)

        val localSug = suggestions.all().associateBy { it.songId }
        var sugMerged = 0
        var sugKept = 0
        var sugMiss = 0
        val sugWrites = ArrayList<vn.laho.musicplayer.metadata.MetadataSuggestion>()
        for (s in doc.suggestions) {
            val song = SongMatcher.match(s.key, songs)
            if (song == null) { sugMiss++; continue }
            val existing = localSug[song.id]
            if (existing != null && existing.updatedAt >= s.row.updatedAt) { sugKept++; continue }
            sugWrites += s.row.copy(songId = song.id, title = song.title, artist = song.artist)
            sugMerged++
        }
        if (sugWrites.isNotEmpty()) suggestions.upsert(sugWrites)

        RestoreReport(added, skipped, restoreActive, moodMerged, moodKept, moodMiss, sugMerged, sugKept, sugMiss)
    }

    /** Deletes backups beyond the newest [keep]; only files of this app data folder named like ours. */
    private suspend fun prune() {
        list().drop(keep).forEach { drive.delete(it.id) }
    }

    companion object {
        const val PREFIX = "laho-backup-"
        const val KEEP_BACKUPS = 3

        fun fileName(createdAtMs: Long): String {
            val t = java.time.Instant.ofEpochMilli(createdAtMs).atZone(java.time.ZoneOffset.UTC)
            return PREFIX + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(t) + ".json"
        }
    }
}

/** The PEQ preset store, as far as backup needs it (fake in tests). */
interface PeqAccess {
    fun current(): PeqPresetState
    fun replace(state: PeqPresetState)
}
