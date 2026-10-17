// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/** Minimal view of a library track used by the assistant. */
data class LibrarySong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String? = null,
)

data class NowPlaying(val title: String, val artist: String, val isPlaying: Boolean)

/**
 * Everything the assistant tools need from the app. The Android implementation talks to
 * the playback service through a Media3 MediaController (so the assistant never touches
 * the UI ViewModel); tests use a fake.
 */
interface PlayerGateway {
    suspend fun play()
    suspend fun pause()
    suspend fun next()
    suspend fun previous()

    /** Media volume in percent (0..100). */
    suspend fun volumePercent(): Int
    suspend fun setVolumePercent(percent: Int)

    suspend fun librarySongs(): List<LibrarySong>

    /** Replaces the queue with [songs] and starts playing [startIndex]. */
    suspend fun playSongs(songs: List<LibrarySong>, startIndex: Int = 0)

    suspend fun nowPlaying(): NowPlaying?
}
