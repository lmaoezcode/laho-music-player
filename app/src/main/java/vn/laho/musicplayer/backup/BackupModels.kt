// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import org.json.JSONArray
import org.json.JSONObject
import vn.laho.musicplayer.dsp.PeqPresetCodec
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.metadata.MetadataSuggestion
import vn.laho.musicplayer.mood.MoodRecord

/**
 * What identifies a song across devices. Local ids (MediaStore) differ between devices, so a
 * backup row carries title + artist + duration instead; see [SongMatcher] for the rule.
 * [durationMs] is null when the song was not in the library at backup time.
 */
data class SongKey(val title: String, val artist: String, val durationMs: Long?)

/** A mood row as stored in a backup: the record (its [MoodRecord.songId] is the OLD device's id) plus the song key. */
data class BackupMood(val key: SongKey, val record: MoodRecord)

data class BackupSuggestion(val key: SongKey, val row: MetadataSuggestion)

/**
 * One backup file. Contains only LaHo data: PEQ presets + active choice, the mood index and
 * metadata suggestions. Never OAuth/App Check tokens, file paths or URIs, or audio.
 * Listening history is not included: it lives in Rhythm's own playback log, not a LaHo table.
 * DSP/USB toggles are device-specific and excluded. (No AI-online switch exists on this base.)
 */
data class BackupDocument(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtMs: Long,
    val peq: PeqPresetState,
    val moods: List<BackupMood>,
    val suggestions: List<BackupSuggestion>,
)

class BackupFormatException(message: String) : Exception(message)

/** The file was written by a newer LaHo than this one understands. */
class BackupTooNewException(val found: Int) : Exception(
    "Bản sao lưu này từ phiên bản LaHo mới hơn (định dạng v$found, app hiểu tới v${BackupCodec.SCHEMA_VERSION}). " +
        "Hãy cập nhật LaHo rồi thử lại.",
)

object BackupLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_MOODS = 50_000
    const val MAX_SUGGESTIONS = 50_000
    const val MAX_PRESETS = 200
    const val MAX_BANDS = 64
    const val MAX_TEXT = 500
}

object BackupCodec {
    /** Bump when the layout changes incompatibly; older readers refuse newer files. */
    const val SCHEMA_VERSION = 1
    const val FORMAT = "laho-backup"

    fun encode(doc: BackupDocument): ByteArray {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", doc.schemaVersion)
            .put("appVersion", doc.appVersion)
            .put("createdAtMs", doc.createdAtMs)
            .put("peq", JSONObject(PeqPresetCodec.encode(doc.peq)))
        val moods = JSONArray()
        doc.moods.forEach { moods.put(moodJson(it)) }
        root.put("moods", moods)
        val sug = JSONArray()
        doc.suggestions.forEach { sug.put(suggestionJson(it)) }
        root.put("suggestions", sug)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupLimits.MAX_BYTES) throw BackupFormatException("Dữ liệu sao lưu quá lớn (${bytes.size / 1024} KB)")
        return bytes
    }

    /** Parses and validates [bytes]. Throws [BackupFormatException] or [BackupTooNewException]. */
    fun decode(bytes: ByteArray): BackupDocument {
        if (bytes.size > BackupLimits.MAX_BYTES) throw BackupFormatException("Tệp sao lưu quá lớn")
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFormatException("Tệp không phải bản sao lưu LaHo hợp lệ")
        }
        if (root.optString("format") != FORMAT) throw BackupFormatException("Tệp không phải bản sao lưu LaHo")
        val version = root.optInt("schemaVersion", -1)
        if (version < 1) throw BackupFormatException("Bản sao lưu thiếu phiên bản định dạng")
        if (version > SCHEMA_VERSION) throw BackupTooNewException(version)

        val peqObj = root.optJSONObject("peq")
        val peq = if (peqObj == null) PeqPresetState.DEFAULT else PeqPresetCodec.decode(peqObj.toString())
        if (peq.presets.size > BackupLimits.MAX_PRESETS || peq.presets.any { it.config.bands.size > BackupLimits.MAX_BANDS }) {
            throw BackupFormatException("Số preset PEQ vượt giới hạn hợp lý")
        }
        val moodArray = root.optJSONArray("moods") ?: JSONArray()
        val sugArray = root.optJSONArray("suggestions") ?: JSONArray()
        if (moodArray.length() > BackupLimits.MAX_MOODS || sugArray.length() > BackupLimits.MAX_SUGGESTIONS) {
            throw BackupFormatException("Số bản ghi vượt giới hạn hợp lý")
        }
        val moods = (0 until moodArray.length()).mapNotNull { moodFrom(moodArray.optJSONObject(it)) }
        val sugs = (0 until sugArray.length()).mapNotNull { suggestionFrom(sugArray.optJSONObject(it)) }
        return BackupDocument(
            version, root.optString("appVersion").take(BackupLimits.MAX_TEXT), root.optLong("createdAtMs"),
            peq, moods, sugs,
        )
    }

    // --- mood rows ---

    private fun moodJson(m: BackupMood): JSONObject {
        val r = m.record
        return keyJson(m.key, r.songId)
            .putN("bpm", r.bpm).putN("rmsDb", r.rmsDb).putN("dynamicRangeDb", r.dynamicRangeDb)
            .putN("centroidHz", r.centroidHz).putN("flux", r.flux).putN("lowEnergyRatio", r.lowEnergyRatio)
            .putN("arousalAudio", r.arousalAudio).put("featureVersion", r.featureVersion)
            .putN("featureError", r.featureError).putN("valence", r.valence).putN("arousalLlm", r.arousalLlm)
            .putN("labels", r.labels).putN("confidence", r.confidence).putN("llmModel", r.llmModel)
            .put("updatedAt", r.updatedAt)
    }

    private fun moodFrom(o: JSONObject?): BackupMood? {
        val key = o?.let(::keyFrom) ?: return null
        val id = o.optString("songId").take(BackupLimits.MAX_TEXT)
        return BackupMood(
            key,
            MoodRecord(
                songId = id, title = key.title, artist = key.artist,
                bpm = o.dbl("bpm"), rmsDb = o.dbl("rmsDb"), dynamicRangeDb = o.dbl("dynamicRangeDb"),
                centroidHz = o.dbl("centroidHz"), flux = o.dbl("flux"), lowEnergyRatio = o.dbl("lowEnergyRatio"),
                arousalAudio = o.dbl("arousalAudio"), featureVersion = o.optInt("featureVersion", 0),
                featureError = o.str("featureError"), valence = o.dbl("valence"), arousalLlm = o.dbl("arousalLlm"),
                labels = o.str("labels"), confidence = o.dbl("confidence"), llmModel = o.str("llmModel"),
                updatedAt = o.optLong("updatedAt"),
            ),
        )
    }

    // --- metadata suggestions ---

    private fun suggestionJson(s: BackupSuggestion): JSONObject {
        val r = s.row
        return keyJson(s.key, r.songId)
            .put("status", r.status).putN("source", r.source).putN("confidence", r.confidence)
            .putN("matchedTitle", r.matchedTitle).putN("matchedArtist", r.matchedArtist).putN("album", r.album)
            .putN("albumArtist", r.albumArtist).putN("year", r.year).putN("trackNumber", r.trackNumber)
            .putN("discNumber", r.discNumber).putN("genre", r.genre).putN("recordingMbid", r.recordingMbid)
            .putN("releaseMbid", r.releaseMbid).putN("releaseGroupMbid", r.releaseGroupMbid)
            .put("updatedAt", r.updatedAt)
    }

    private fun suggestionFrom(o: JSONObject?): BackupSuggestion? {
        val key = o?.let(::keyFrom) ?: return null
        return BackupSuggestion(
            key,
            MetadataSuggestion(
                songId = o.optString("songId").take(BackupLimits.MAX_TEXT), title = key.title, artist = key.artist,
                status = o.optString("status").takeIf { it == MetadataSuggestion.STATUS_FOUND || it == MetadataSuggestion.STATUS_NOT_FOUND }
                    ?: return null,
                source = o.str("source"), confidence = o.dbl("confidence"), matchedTitle = o.str("matchedTitle"),
                matchedArtist = o.str("matchedArtist"), album = o.str("album"), albumArtist = o.str("albumArtist"),
                year = o.int("year"), trackNumber = o.int("trackNumber"), discNumber = o.int("discNumber"),
                genre = o.str("genre"), recordingMbid = o.str("recordingMbid"), releaseMbid = o.str("releaseMbid"),
                releaseGroupMbid = o.str("releaseGroupMbid"), updatedAt = o.optLong("updatedAt"),
            ),
        )
    }

    // --- helpers ---

    private fun keyJson(key: SongKey, songId: String) = JSONObject()
        .put("songId", songId).put("title", key.title).put("artist", key.artist)
        .also { if (key.durationMs != null) it.put("durationMs", key.durationMs) }

    private fun keyFrom(o: JSONObject): SongKey? {
        val title = o.optString("title").trim().take(BackupLimits.MAX_TEXT)
        if (title.isEmpty()) return null
        val duration = if (o.has("durationMs") && !o.isNull("durationMs")) o.optLong("durationMs").takeIf { it > 0 } else null
        return SongKey(title, o.optString("artist").trim().take(BackupLimits.MAX_TEXT), duration)
    }

    private fun JSONObject.putN(name: String, value: Any?): JSONObject = apply { if (value != null) put(name, value) }
    private fun JSONObject.dbl(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name, Double.NaN).takeUnless { it.isNaN() || it.isInfinite() } else null
    private fun JSONObject.int(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
    private fun JSONObject.str(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).take(BackupLimits.MAX_TEXT) else null
}
