// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Progress of an indexing run (for the chat UI / logs). */
data class IndexProgress(val stage: Stage, val done: Int, val total: Int, val current: String? = null) {
    enum class Stage { AUDIO, LLM, DONE }
}

data class IndexReport(
    val songs: Int,
    val analysed: Int,
    val decodeFailures: Int,
    val classified: Int,
    val llmFailures: Int,
    val indexedWithMood: Int,
    /** Message of the last failed classifier request (e.g. App Check / network), if any. */
    val lastLlmError: String? = null,
)

/**
 * Builds the mood index incrementally:
 * 1. offline audio features for songs without them (decoder + [AudioFeatureExtractor]);
 * 2. LLM verdicts in batches for songs without one (skipped when [classifier] is null,
 *    i.e. offline/no Firebase config).
 * Already indexed songs are never redone, so the run can be stopped and resumed.
 */
class MoodIndexer(
    private val store: MoodStore,
    private val library: suspend () -> List<IndexableSong>,
    private val analyse: suspend (IndexableSong) -> AudioFeatures?,
    private val classifier: LlmMoodClassifier?,
    private val batchSize: Int = 25,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called for each failed classifier request (logging). */
    private val onError: (Exception) -> Unit = {},
) {

    suspend fun run(onProgress: (IndexProgress) -> Unit = {}): IndexReport {
        val songs = library()
        val records = store.all().associateBy { it.songId }.toMutableMap()

        // 1) Audio features
        val needAudio = songs.filter { records[it.id]?.hasFeatures != true }
        var analysed = 0
        var decodeFailures = 0
        needAudio.forEachIndexed { i, song ->
            coroutineContext.ensureActive()
            onProgress(IndexProgress(IndexProgress.Stage.AUDIO, i, needAudio.size, song.title))
            val features = try {
                analyse(song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (features == null) decodeFailures++ else analysed++
            val base = records[song.id] ?: MoodRecord(song.id, song.title, song.artist)
            val updated = base.withFeatures(features, if (features == null) "decode_failed" else null, clock())
            records[song.id] = updated
            store.upsert(listOf(updated))
        }

        // 2) LLM verdicts
        var classified = 0
        var llmFailures = 0
        var lastLlmError: String? = null
        if (classifier != null) {
            val needLlm = songs.filter { records[it.id]?.hasVerdict != true }
            needLlm.chunked(batchSize).forEachIndexed { b, chunk ->
                coroutineContext.ensureActive()
                onProgress(IndexProgress(IndexProgress.Stage.LLM, b * batchSize, needLlm.size))
                val inputs = chunk.map { s ->
                    MoodInput(s.id, s.title, s.artist, s.album, s.genre, records[s.id]?.features(), s.year)
                }
                val verdicts = try {
                    classifier.classify(inputs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastLlmError = e.message ?: e::class.simpleName
                    onError(e)
                    emptyList()
                }
                val byId = verdicts.associateBy { it.id }
                // Songs the model skipped (or a failed request) are retried on the next run.
                llmFailures += chunk.count { it.id !in byId }
                val updated = chunk.mapNotNull { s ->
                    val v = byId[s.id] ?: return@mapNotNull null
                    (records[s.id] ?: MoodRecord(s.id, s.title, s.artist)).copy(
                        valence = v.valence,
                        arousalLlm = v.arousal,
                        labels = v.moods.joinToString(",") { it.key },
                        confidence = v.confidence,
                        llmModel = classifier.modelName,
                        updatedAt = clock(),
                    )
                }
                updated.forEach { records[it.songId] = it }
                if (updated.isNotEmpty()) store.upsert(updated)
                classified += updated.size
            }
        }

        val songIds = songs.map { it.id }.toSet()
        val withMood = records.values.count { it.songId in songIds && it.profile() != null }
        onProgress(IndexProgress(IndexProgress.Stage.DONE, songs.size, songs.size))
        return IndexReport(songs.size, analysed, decodeFailures, classified, llmFailures, withMood, lastLlmError)
    }
}
