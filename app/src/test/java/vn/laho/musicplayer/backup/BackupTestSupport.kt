// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import vn.laho.musicplayer.drive.AppDataFile
import vn.laho.musicplayer.drive.AppDataStore
import vn.laho.musicplayer.dsp.FilterType
import vn.laho.musicplayer.dsp.PeqBand
import vn.laho.musicplayer.dsp.PeqConfig
import vn.laho.musicplayer.dsp.PeqPreset
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqSource
import vn.laho.musicplayer.metadata.MetadataStore
import vn.laho.musicplayer.metadata.MetadataSuggestion
import vn.laho.musicplayer.mood.IndexableSong
import vn.laho.musicplayer.mood.MoodRecord
import vn.laho.musicplayer.mood.MoodStore

class FakeDrive : AppDataStore {
    val files = LinkedHashMap<String, Pair<AppDataFile, ByteArray>>()
    val deleted = mutableListOf<String>()
    private var n = 0
    override suspend fun list(namePrefix: String) =
        files.values.map { it.first }.filter { it.name.startsWith(namePrefix) }.sortedByDescending { it.name }
    override suspend fun upload(name: String, content: ByteArray, mimeType: String): AppDataFile {
        val f = AppDataFile("fileid%05d".format(n++), name, content.size.toLong(), null)
        files[f.id] = f to content
        return f
    }
    override suspend fun download(id: String, maxBytes: Long) = files.getValue(id).second
    override suspend fun delete(id: String) { files.remove(id); deleted += id }
}

class FakePeq(var state: PeqPresetState = PeqPresetState.DEFAULT) : PeqAccess {
    override fun current() = state
    override fun replace(state: PeqPresetState) { this.state = state }
}

class MemMoods(vararg r: MoodRecord) : MoodStore {
    val rows = r.associateBy { it.songId }.toMutableMap()
    override suspend fun all() = rows.values.toList()
    override suspend fun upsert(records: List<MoodRecord>) { records.forEach { rows[it.songId] = it } }
    override suspend fun clear() = rows.clear()
}

class MemSuggestions(vararg r: MetadataSuggestion) : MetadataStore {
    val rows = r.associateBy { it.songId }.toMutableMap()
    override suspend fun all() = rows.values.toList()
    override suspend fun upsert(rows: List<MetadataSuggestion>) { rows.forEach { this.rows[it.songId] = it } }
    override suspend fun clear() = rows.clear()
}

fun song(id: String, title: String, artist: String, durationMs: Long) =
    IndexableSong(id, title, artist, "Album", null, "content://media/external/audio/media/$id", durationMs)

fun preset(name: String, gain: Double = 3.0) =
    PeqPreset(name, PeqConfig(true, -2.0, listOf(PeqBand(FilterType.PEAKING, 1000.0, gain, 1.0, true))))

val samplePeq = PeqPresetState(PeqSource.PRESET, "HD 600", listOf(preset("HD 600"), preset("Flat", 0.0)))

fun mood(id: String, title: String, artist: String, updatedAt: Long = 100, valence: Double = 0.5) =
    MoodRecord(id, title, artist, bpm = 120.0, rmsDb = -14.0, centroidHz = 2000.0, featureVersion = 1, valence = valence,
        labels = "vui,soi_dong", llmModel = "gemini", updatedAt = updatedAt)
