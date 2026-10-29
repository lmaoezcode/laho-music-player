// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import vn.laho.musicplayer.drive.DriveException
import vn.laho.musicplayer.drive.DriveFile
import vn.laho.musicplayer.drive.DriveGateway
import vn.laho.musicplayer.drive.SavedTrack
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.mood.MoodProfile

/** Fakes for the agent gateways, shared by the agent tests. */
class FakeQueue(val player: FakePlayer) : QueueGateway {
    val added = mutableListOf<LibrarySong>()
    val played = mutableListOf<String>()
    var size = 0
    override suspend fun addToQueue(songs: List<LibrarySong>) { added += songs; size += songs.size }
    override suspend fun clearQueue(): Int { val n = (size - 1).coerceAtLeast(0); size = size.coerceAtMost(1); return n }
    override suspend fun queueSize() = size
    override suspend fun playUri(uri: String, title: String) { played += "$title@$uri" }
}

class FakePlaylists : PlaylistGateway {
    val stored = linkedMapOf<String, Pair<String, List<String>>>()
    override suspend fun playlists() = stored.map { (id, v) -> PlaylistInfo(id, v.first, v.second.size) }
    override suspend fun createPlaylist(name: String, songIds: List<String>): String {
        val id = "pl${stored.size + 1}"
        stored[id] = name to songIds
        return id
    }
    override suspend fun songIds(playlistId: String) = stored[playlistId]?.second.orEmpty()
}

class FakeTimer : SleepTimerGateway {
    val log = mutableListOf<String>()
    override fun start(minutes: Int) { log += "start:$minutes" }
    override fun cancel() { log += "cancel" }
}

class FakeDriveFiles(var files: List<DriveFile>) : DriveGateway {
    val downloads = mutableListOf<String>()
    override suspend fun search(query: String, limit: Int) = files.take(limit)
    override suspend fun file(id: String) = files.firstOrNull { it.id == id } ?: throw DriveException("Không tìm thấy tệp", 404)
    override suspend fun download(file: DriveFile): SavedTrack {
        downloads += file.id
        return SavedTrack(file.name, "Music/LaHo/${file.name}", "content://media/${file.id}")
    }
}

val DRIVE_SONG = DriveFile("aaaaaaaaaa1", "Lạc Trôi.mp3", 5_000_000, "audio/mpeg")

/** Library songs 1,2,4 are sad, 3,5 energetic. */
val MOOD_PROFILES = listOf(
    MoodProfile("1", -0.7, -0.4, setOf(Mood.SAD)),
    MoodProfile("2", -0.6, -0.5, setOf(Mood.SAD)),
    MoodProfile("4", -0.8, -0.3, setOf(Mood.SAD)),
    MoodProfile("3", 0.5, 0.9, setOf(Mood.ENERGETIC)),
    MoodProfile("5", 0.6, 0.8, setOf(Mood.ENERGETIC)),
)

class AgentRig(drive: Boolean = false, withMood: Boolean = true) {
    val player = FakePlayer(LIBRARY)
    val queue = FakeQueue(player)
    val playlists = FakePlaylists()
    val timer = FakeTimer()
    val driveFiles = FakeDriveFiles(listOf(DRIVE_SONG))
    val registry = ToolRegistry.default(
        player,
        moodIndex = if (withMood) MoodIndex { MOOD_PROFILES } else null,
        drive = if (drive) driveFiles else null,
        queue = queue, playlists = playlists, sleepTimer = timer,
        random = kotlin.random.Random(1),
    )
    val progress = mutableListOf<String>()
    val listener = AgentListener { progress += it.line() }
}
