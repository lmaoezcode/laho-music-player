// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Saves downloaded songs to the shared music collection under `Music/LaHo`.
 *
 * - Android 10+: a `MediaStore.Audio` entry with `RELATIVE_PATH` and `IS_PENDING` while
 *   writing (no storage permission needed for the app's own files).
 * - Android 8–9: a file in the public `Music/LaHo` directory (needs WRITE_EXTERNAL_STORAGE),
 *   then indexed with [MediaScannerConnection].
 *
 * Either way MediaStore changes, which fires Rhythm's existing MediaStore ContentObserver
 * (`MusicRepository.registerMediaStoreObserver`, used by `MusicViewModel`) so the library is
 * rescanned and the song becomes playable and indexable by `/index`. If the main screen is
 * not alive, the startup staleness check (`isLibraryStale`) picks it up on the next launch.
 */
class MediaStoreTrackSaver(context: Context) : TrackSaver {

    private val appContext = context.applicationContext

    override suspend fun save(displayName: String, mimeType: String, write: suspend (OutputStream) -> Long): SavedTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(displayName, mimeType, write)
        else saveLegacy(displayName, mimeType, write)

    private suspend fun saveScoped(displayName: String, mimeType: String, write: suspend (OutputStream) -> Long): SavedTrack {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUBDIR")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = withContext(Dispatchers.IO) { resolver.insert(collection, values) }
            ?: throw DriveException("Không tạo được tệp trong thư viện nhạc")
        try {
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "w") }
                ?: throw DriveException("Không ghi được tệp trong thư viện nhạc")
            out.use { write(it) }
            withContext(Dispatchers.IO) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
        return SavedTrack(displayName, "${Environment.DIRECTORY_MUSIC}/$SUBDIR/$displayName")
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(displayName: String, mimeType: String, write: suspend (OutputStream) -> Long): SavedTrack {
        val granted = appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) throw DriveException("Cần quyền bộ nhớ để lưu nhạc (hãy cấp quyền Bộ nhớ cho app trong Cài đặt)")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBDIR)
        val target = withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw DriveException("Không tạo được thư mục ${dir.path}")
            uniqueFile(dir, displayName)
        }
        val partial = File(dir, ".${target.name}.part")
        try {
            withContext(Dispatchers.IO) { FileOutputStream(partial) }.use { write(it) }
            withContext(Dispatchers.IO) {
                if (!partial.renameTo(target)) throw DriveException("Không đổi tên được tệp tải về")
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { partial.delete() }
            throw e
        }
        scan(target, mimeType)
        return SavedTrack(target.name, target.path)
    }

    private suspend fun scan(file: File, mimeType: String) {
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(appContext, arrayOf(file.path), arrayOf(mimeType)) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) candidate = File(dir, "$base (${i++})$ext")
        return candidate
    }

    private companion object {
        const val SUBDIR = "LaHo"
        const val SCAN_TIMEOUT_MS = 15_000L
    }
}
