// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import android.content.Context
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.laho.musicplayer.assistant.online.GeminiAssistant
import vn.laho.musicplayer.assistant.online.LahoFirebase

/** Android wiring for the mood index: library source, decoder, classifier, export. */
object AndroidMoodIndexing {

    suspend fun librarySongs(context: Context): List<IndexableSong> = withContext(Dispatchers.IO) {
        RhythmDatabase.getInstance(context).songDao().getAllSongs().map { it.toSong() }.map {
            IndexableSong(it.id, it.title, it.artist, it.album, it.genre, it.uri.toString(), it.duration)
        }
    }

    /** Creates an indexer; the LLM stage is skipped when this build has no Firebase config. */
    fun indexer(context: Context, store: MoodStore): MoodIndexer {
        val app = context.applicationContext
        val decoder = SegmentDecoder(app)
        val classifier = if (LahoFirebase.ensure(app)) {
            val model = GeminiAssistant.MODEL_FLASH_LITE
            LlmMoodClassifier(FirebaseJsonTextModel(model), model)
        } else null
        return MoodIndexer(
            store = store,
            library = { librarySongs(app) },
            analyse = { song ->
                withContext(Dispatchers.Default) {
                    decoder.decode(song.uri, song.durationMs)?.let { AudioFeatureExtractor.extract(it.mono, it.sampleRate) }
                }
            },
            classifier = classifier,
        )
    }

    /**
     * Writes the index as CSV (for evaluating against hand labels in the thesis). Returns the
     * file in the app's external files dir: Android/data/<package>/files/mood_index.csv.
     */
    suspend fun exportCsv(context: Context, store: MoodStore): File = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "mood_index.csv")
        val header = "song_id,title,artist,bpm,rms_db,dynamic_range_db,centroid_hz,flux,low_ratio," +
            "arousal_audio,valence_llm,arousal_llm,labels,confidence,model,combined_valence,combined_arousal"
        val rows = store.all().sortedBy { it.artist + it.title }.map { r ->
            val p = r.profile()
            listOf(
                r.songId, r.title, r.artist, r.bpm, r.rmsDb, r.dynamicRangeDb, r.centroidHz, r.flux, r.lowEnergyRatio,
                r.arousalAudio, r.valence, r.arousalLlm, r.labels, r.confidence, r.llmModel, p?.valence, p?.arousal,
            ).joinToString(",") { csv(it) }
        }
        file.writeText((listOf(header) + rows).joinToString("\n"), Charsets.UTF_8)
        file
    }

    private fun csv(v: Any?): String = when (v) {
        null -> ""
        is Double -> String.format(java.util.Locale.ROOT, "%.4f", v)
        else -> v.toString().let { s -> if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
    }
}
