// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import android.content.Intent
import android.net.Uri
import chromahub.rhythm.app.features.local.data.database.entity.PlaylistEntity
import chromahub.rhythm.app.features.local.data.database.entity.PlaylistSongEntity
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import chromahub.rhythm.app.util.ServiceStartUtils
import chromahub.rhythm.app.shared.data.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PlayerGateway] backed by the app's playback service.
 *
 * Uses its own Media3 [MediaController] (the session accepts any local controller), the
 * system media volume, and the scanned library in the Room database — so the assistant is
 * decoupled from the UI ViewModel.
 */
class MediaControllerGateway(context: Context) : PlayerGateway, QueueGateway, PlaylistGateway, SleepTimerGateway {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val connectLock = Mutex()
    private var controller: MediaController? = null

    @Volatile
    private var songsById: Map<String, Song> = emptyMap()

    private suspend fun controller(): MediaController = connectLock.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        withContext(Dispatchers.Main) {
            val token = SessionToken(appContext, ComponentName(appContext, MediaPlaybackService::class.java))
            MediaController.Builder(appContext, token).buildAsync().await().also { controller = it }
        }
    }

    private suspend fun <T> onPlayer(block: (MediaController) -> T): T {
        val c = controller()
        return withContext(Dispatchers.Main) { block(c) }
    }

    override suspend fun play() = onPlayer {
        if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) it.prepare()
        it.play()
    }

    override suspend fun pause() = onPlayer { it.pause() }

    override suspend fun next() = onPlayer { it.seekToNextMediaItem() }

    override suspend fun previous() = onPlayer {
        // Like a "previous" button: restart the song if we're more than a few seconds in.
        if (it.currentPosition > RESTART_THRESHOLD_MS || !it.hasPreviousMediaItem()) it.seekTo(0)
        else it.seekToPreviousMediaItem()
    }

    override suspend fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100.0 / max).roundToInt()
    }

    override suspend fun setVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) * max / 100.0).roundToInt()
        withContext(Dispatchers.Main) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        }
    }

    override suspend fun librarySongs(): List<LibrarySong> {
        val songs = withContext(Dispatchers.IO) {
            RhythmDatabase.getInstance(appContext).songDao().getAllSongs().map { it.toSong() }
        }
        songsById = songs.associateBy { it.id }
        return songs.map { LibrarySong(it.id, it.title, it.artist, it.album, it.genre) }
    }

    override suspend fun playSongs(songs: List<LibrarySong>, startIndex: Int) {
        if (songsById.isEmpty()) librarySongs()
        val items = songs.mapNotNull { songsById[it.id]?.toMediaItem() }
        require(items.isNotEmpty()) { "Songs are no longer in the library" }
        onPlayer {
            it.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
            it.prepare()
            it.play()
        }
    }

    override suspend fun nowPlaying(): NowPlaying? = onPlayer {
        if (it.currentMediaItem == null) return@onPlayer null
        val meta = it.mediaMetadata
        NowPlaying(
            title = meta.title?.toString().orEmpty().ifBlank { "Không rõ tên" },
            artist = meta.artist?.toString().orEmpty().ifBlank { "Không rõ nghệ sĩ" },
            isPlaying = it.isPlaying,
        )
    }

    // --- QueueGateway -------------------------------------------------------------------

    override suspend fun addToQueue(songs: List<LibrarySong>) {
        if (songsById.isEmpty()) librarySongs()
        val items = songs.mapNotNull { songsById[it.id]?.toMediaItem() }
        require(items.isNotEmpty()) { "Songs are no longer in the library" }
        onPlayer {
            val wasEmpty = it.mediaItemCount == 0
            it.addMediaItems(items)
            if (wasEmpty) { it.prepare(); it.play() }
        }
    }

    override suspend fun clearQueue(): Int = onPlayer {
        val current = it.currentMediaItemIndex
        val count = it.mediaItemCount
        if (count <= 1 || current < 0) return@onPlayer 0
        if (current + 1 < count) it.removeMediaItems(current + 1, count)
        if (current > 0) it.removeMediaItems(0, current)
        count - 1
    }

    override suspend fun queueSize(): Int = onPlayer { it.mediaItemCount }

    override suspend fun playUri(uri: String, title: String) {
        val item = MediaItem.Builder()
            .setMediaId(uri)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        onPlayer { it.setMediaItem(item); it.prepare(); it.play() }
    }

    // --- PlaylistGateway (Rhythm's Room tables) -----------------------------------------

    private val playlistDao get() = RhythmDatabase.getInstance(appContext).playlistDao()

    override suspend fun playlists(): List<PlaylistInfo> = withContext(Dispatchers.IO) {
        playlistDao.getAllPlaylists().map { PlaylistInfo(it.id, it.name, playlistDao.getSongIdsForPlaylist(it.id).size) }
    }

    override suspend fun createPlaylist(name: String, songIds: List<String>): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var id = now.toString()
        while (playlistDao.getPlaylistById(id) != null) id = (id.toLong() + 1).toString()
        playlistDao.insertPlaylist(PlaylistEntity(id, name, now, now, null))
        playlistDao.insertPlaylistSongs(songIds.distinct().mapIndexed { i, sid -> PlaylistSongEntity(id, sid, i) })
        // Same broadcast Rhythm uses when favourites change: MusicViewModel reloads playlists from Room.
        appContext.sendBroadcast(
            Intent("chromahub.rhythm.app.action.FAVORITE_CHANGED").setPackage(appContext.packageName),
        )
        id
    }

    override suspend fun songIds(playlistId: String): List<String> =
        withContext(Dispatchers.IO) { playlistDao.getSongIdsForPlaylist(playlistId) }

    // --- SleepTimerGateway (MediaPlaybackService) ---------------------------------------

    override fun start(minutes: Int) = sendTimer(MediaPlaybackService.ACTION_START_SLEEP_TIMER) {
        putExtra("duration", minutes * 60_000L)
        putExtra("fadeOut", true)
        putExtra("pauseOnly", false)
    }

    override fun cancel() = sendTimer(MediaPlaybackService.ACTION_STOP_SLEEP_TIMER) {}

    private fun sendTimer(action: String, extras: Intent.() -> Unit) {
        val intent = Intent(appContext, MediaPlaybackService::class.java).apply { this.action = action; extras() }
        ServiceStartUtils.startServiceSafely(appContext, intent, "LahoAssistant", "assistant_sleep_timer")
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e.cause ?: e)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        cont.invokeOnCancellation { cancel(true) }
    }

    private fun Song.toMediaItem(): MediaItem {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .apply { MIME_BY_EXTENSION[ext]?.let(::setMimeType) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setDurationMs(duration)
                    .setArtworkUri(artworkUri)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L

        // Same hints the main UI uses when queueing songs.
        val MIME_BY_EXTENSION = mapOf(
            "opus" to "audio/ogg", "opa" to "audio/ogg", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "mkv" to "audio/x-matroska", "mka" to "audio/x-matroska", "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4", "m4b" to "audio/mp4", "mp4" to "audio/mp4", "flac" to "audio/flac",
            "wav" to "audio/wav", "aac" to "audio/aac", "adts" to "audio/aac", "ac3" to "audio/ac3",
            "eac3" to "audio/eac3", "ape" to "audio/x-ape", "wv" to "audio/x-wavpack",
            "aiff" to "audio/aiff", "aif" to "audio/aiff", "dsf" to "audio/dsd", "dff" to "audio/dsd",
        )
    }
}
