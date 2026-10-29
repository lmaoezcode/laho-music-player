// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.suggest

import android.content.Context
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Listening history from Rhythm's own playback log (`playback_history.json`, last 90 days,
 * one event per listen with the time actually heard). The log has no song length, so the
 * fraction listened is derived from the scanned library; unknown lengths give a null fraction.
 */
class RhythmListeningHistory(context: Context) : ListeningHistory {
    private val appContext = context.applicationContext

    override suspend fun events(): List<ListenEvent> = withContext(Dispatchers.IO) {
        val durations = RhythmDatabase.getInstance(appContext).songDao().getAllSongs()
            .map { it.toSong() }.associate { it.id to it.duration }
        PlaybackStatsRepository.getInstance(appContext).allEvents().map { e ->
            val total = durations[e.songId] ?: 0L
            ListenEvent(
                songId = e.songId,
                artist = e.artistName.orEmpty(),
                startedAtMs = e.startMillis(),
                listenedMs = e.durationMs,
                fraction = if (total > 0) (e.durationMs.toDouble() / total).coerceIn(0.0, 1.0) else null,
            )
        }
    }
}
