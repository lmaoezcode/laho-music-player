// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import java.io.IOException
import java.io.OutputStream

/** Metadata of one file in the user's Google Drive (fields id,name,size,mimeType,modifiedTime). */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val mimeType: String,
    val modifiedTime: String? = null,
) {
    val isAudio: Boolean get() = DriveRules.isAudioMime(mimeType)
}

/** Where a downloaded song ended up (display name + a URI or path for logs/messages). */
data class SavedTrack(val displayName: String, val location: String)

/** The user has not granted Drive access yet (or the grant expired and needs the consent UI). */
class DriveAuthRequiredException(message: String = "Google Drive authorization required") : IOException(message)

/** Drive answered with an error status, or the download broke a guard (size, MIME). */
class DriveException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Access token source. Tokens live in memory only and are never logged; there are no
 * refresh tokens: an expired token is replaced by asking Google Play services again.
 */
interface DriveTokenSource {
    /** Current token, or null when the user still has to authorize. */
    suspend fun token(): String?

    /** Drops [token] after a 401 and tries to obtain a fresh one without UI (null if impossible). */
    suspend fun refresh(expired: String): String?
}

/** What the assistant tools need from Drive. Implemented by [DriveRepository]; faked in tests. */
interface DriveGateway {
    /** Audio files whose name contains [query], newest first. */
    suspend fun search(query: String, limit: Int): List<DriveFile>

    suspend fun file(id: String): DriveFile

    /** Downloads [file] into the device music library (Music/LaHo). */
    suspend fun download(file: DriveFile): SavedTrack
}

/** Writes a downloaded song into shared storage. */
interface TrackSaver {
    /**
     * Creates a new audio entry called [displayName] and lets [write] fill it. On any
     * exception the partial entry is removed and the exception rethrown.
     */
    suspend fun save(displayName: String, mimeType: String, write: suspend (OutputStream) -> Long): SavedTrack
}

/** Guards and formatting shared by the client, the repository and the tools. */
object DriveRules {
    /** Largest file the assistant will download (lossless albums as one file stay below this). */
    const val MAX_DOWNLOAD_BYTES: Long = 300L * 1024 * 1024

    private val ID = Regex("^[A-Za-z0-9_-]{10,200}$")
    private val UNSAFE_NAME_CHARS = Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+")
    private const val MAX_NAME = 120

    fun isAudioMime(mime: String?): Boolean = mime != null && mime.trim().lowercase().startsWith("audio/")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /**
     * Drive file names are untrusted data: control characters, line breaks and path
     * separators are removed and the length is capped before a name is shown, sent to the
     * LLM or used as a file name.
     */
    fun safeName(name: String): String =
        name.replace(UNSAFE_NAME_CHARS, " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME).ifEmpty { "drive-audio" }

    /** File name for the library: the safe name, plus an extension derived from the MIME type if missing. */
    fun fileName(name: String, mimeType: String): String {
        val safe = safeName(name)
        if (Regex("\\.[A-Za-z0-9]{2,5}$").containsMatchIn(safe)) return safe
        val ext = when (mimeType.lowercase().substringBefore(';').trim()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "audio/vorbis" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/x-ms-wma" -> "wma"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            else -> null
        }
        return if (ext != null) "$safe.$ext" else safe
    }

    /** "12,3 MB" style size for Vietnamese messages. */
    fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "không rõ dung lượng"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format(java.util.Locale.ROOT, "%.1f MB", mb).replace('.', ',')
        else "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}
