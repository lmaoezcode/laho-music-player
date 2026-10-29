// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * Small gateways used by the multi-step agent tools. Each one is a thin seam over an existing
 * Rhythm feature (Media3 queue, Room playlists, the service's sleep timer) so the tools stay
 * unit-testable with fakes.
 */
interface QueueGateway {
    /** Appends [songs] after the current queue (starts playback when the queue was empty). */
    suspend fun addToQueue(songs: List<LibrarySong>)

    /** Removes every queued song except the one playing. Returns how many were removed. */
    suspend fun clearQueue(): Int

    suspend fun queueSize(): Int

    /** Plays a file outside the scanned library (e.g. a song just downloaded from Drive). */
    suspend fun playUri(uri: String, title: String)
}

data class PlaylistInfo(val id: String, val name: String, val songCount: Int)

/** Rhythm's playlist storage (Room `playlists` + `playlist_songs`). */
interface PlaylistGateway {
    suspend fun playlists(): List<PlaylistInfo>

    /** Creates a playlist named [name] holding [songIds] in order; returns its id. */
    suspend fun createPlaylist(name: String, songIds: List<String>): String

    suspend fun songIds(playlistId: String): List<String>
}

/** Rhythm's sleep timer in MediaPlaybackService. */
interface SleepTimerGateway {
    fun start(minutes: Int)
    fun cancel()
}

/**
 * What earlier steps produced, so a later step can say "phát" without repeating names:
 * the playlist just created, the song just downloaded. Shared by the tools of one registry.
 */
class AgentMemory {
    var lastPlaylist: PlaylistInfo? = null
    var lastDownload: Download? = null

    data class Download(val title: String, val uri: String)
}
