// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

/**
 * [DriveGateway] on top of the REST client: search, metadata, and download straight into
 * the music library through [saver]. Enforces the audio-only and size guards again here so
 * no caller can skip them.
 */
class DriveRepository(
    private val client: DriveClient,
    private val saver: TrackSaver,
    private val maxBytes: Long = DriveRules.MAX_DOWNLOAD_BYTES,
) : DriveGateway {

    override suspend fun search(query: String, limit: Int): List<DriveFile> =
        client.search(query, limit).filter { it.isAudio }

    override suspend fun file(id: String): DriveFile = client.file(id)

    override suspend fun download(file: DriveFile): SavedTrack {
        if (!file.isAudio) throw DriveException("Chỉ tải được tệp âm thanh")
        if ((file.sizeBytes ?: 0) > maxBytes) throw DriveException("Tệp vượt giới hạn ${DriveRules.formatSize(maxBytes)}")
        return saver.save(DriveRules.fileName(file.name, file.mimeType), file.mimeType) { out ->
            client.download(file.id, out, maxBytes)
        }
    }
}
