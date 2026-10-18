// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Fields of a library song that the batch job looks at. */
data class MetaSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val year: Int,
    val durationMs: Long,
) {
    /** Missing genre/year/album, or an unknown artist: worth asking MusicBrainz. */
    val needsMetadata: Boolean
        get() = TagText.isUnknown(genre) || year <= 0 || TagText.isUnknown(album) || TagText.isUnknown(artist)
}

data class MetaProgress(val done: Int, val total: Int, val current: String?)

data class MetaReport(
    val candidates: Int,
    val found: Int,
    val notFound: Int,
    val failed: Int,
    val skipped: Int,
    /** Message of the last network/API failure, if any. */
    val lastError: String? = null,
)

/**
 * Looks up songs with incomplete tags and stores the best match in [store] (LaHo's database
 * only; files are never touched). Songs already looked up are skipped, so the run can be
 * stopped (coroutine cancellation) and resumed; failures are retried on the next run.
 */
class MetadataBatch(
    private val store: MetadataStore,
    private val library: suspend () -> List<MetaSong>,
    private val lookup: suspend (LookupQuery) -> List<MetadataCandidate>,
    private val minConfidence: Double = MIN_CONFIDENCE,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Gives up after this many consecutive failures (offline / blocked) instead of grinding on. */
    private val maxConsecutiveFailures: Int = 5,
    private val onError: (Exception) -> Unit = {},
) {

    suspend fun run(onProgress: (MetaProgress) -> Unit = {}): MetaReport {
        val songs = library().filter { it.needsMetadata }
        val done = store.all().map { it.songId }.toSet()
        val todo = songs.filter { it.id !in done }
        var found = 0
        var notFound = 0
        var failed = 0
        var consecutive = 0
        var lastError: String? = null
        for ((i, song) in todo.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(MetaProgress(i, todo.size, song.title))
            val candidates = try {
                lookup(LookupQuery(song.title, song.artist, song.durationMs)).also { consecutive = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                consecutive++
                lastError = e.message ?: e::class.simpleName
                onError(e)
                if (consecutive >= maxConsecutiveFailures) break
                continue
            }
            val best = candidates.firstOrNull()?.takeIf { it.confidence >= minConfidence }
            val row = if (best != null) {
                found++
                MetadataSuggestion.found(song.id, song.title, song.artist, best, clock())
            } else {
                notFound++
                MetadataSuggestion.notFound(song.id, song.title, song.artist, clock())
            }
            store.upsert(listOf(row))
        }
        onProgress(MetaProgress(todo.size, todo.size, null))
        return MetaReport(songs.size, found, notFound, failed, songs.size - todo.size, lastError)
    }

    companion object {
        /** Below this the match is too uncertain to store without the user looking at it. */
        const val MIN_CONFIDENCE = 0.75
    }
}
