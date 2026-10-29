// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

/**
 * One listen of one song. [fraction] is the share of the song that was heard (0..1) or null
 * when the song length is unknown; [artist] is kept so artist affinity works without a join.
 */
data class ListenEvent(
    val songId: String,
    val artist: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val fraction: Double? = null,
) {
    /** A skip: under a quarter of the song, or (length unknown) under 30 seconds. */
    val isSkip: Boolean
        get() = fraction?.let { it < SKIP_FRACTION } ?: (listenedMs < SKIP_UNKNOWN_MS)

    /** How much this listen counts as a positive signal: 0 for a skip, else the share heard. */
    val completion: Double get() = if (isSkip) 0.0 else (fraction ?: 1.0).coerceIn(0.0, 1.0)

    companion object {
        const val SKIP_FRACTION = 0.25
        const val SKIP_UNKNOWN_MS = 30_000L
    }
}

/**
 * Source of listening history. LaHo reuses Rhythm's own playback log (see
 * `RhythmListeningHistory`); tests use an in-memory list.
 */
fun interface ListeningHistory {
    suspend fun events(): List<ListenEvent>
}
