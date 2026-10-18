// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import androidx.room.Entity
import androidx.room.PrimaryKey
import vn.laho.musicplayer.assistant.VietnameseText

/**
 * Fixed mood vocabulary on the valence (sad -1 … happy +1) / arousal (calm -1 … energetic +1)
 * plane (Russell's circumplex model). Keys are stable ASCII ids stored in the database and
 * used by the LLM; [label] is the Vietnamese display name.
 */
enum class Mood(val key: String, val label: String, val valence: Double, val arousal: Double, synonyms: List<String>) {
    HAPPY("vui", "vui", 0.7, 0.45, listOf("vui", "vui ve", "happy", "tuoi vui", "hanh phuc", "yeu doi")),
    SAD("buon", "buồn", -0.7, -0.4, listOf("buon", "u buon", "co don", "sad", "tam trang", "that tinh", "chia tay")),
    CALM("nhe_nhang", "nhẹ nhàng", 0.25, -0.65, listOf("nhe nhang", "thu gian", "chill", "em diu", "de ngu", "di ngu", "relax", "binh yen", "lofi", "lo fi")),
    ENERGETIC("soi_dong", "sôi động", 0.45, 0.85, listOf("soi dong", "nang luong", "tap gym", "gym", "quay", "tiec", "party", "upbeat", "chay bo", "san khau")),
    ROMANTIC("lang_man", "lãng mạn", 0.45, -0.15, listOf("lang man", "romantic", "tinh cam", "hen ho")),
    FOCUS("tap_trung", "tập trung", 0.05, -0.3, listOf("tap trung", "hoc bai", "lam viec", "focus", "on thi", "doc sach")),
    INTENSE("manh_me", "mạnh mẽ", -0.35, 0.85, listOf("manh me", "rock", "intense", "gian du", "buc boi", "cuong chay"));

    /** Folded (no-diacritic) phrases that mean this mood in Vietnamese/English requests. */
    val phrases: List<String> = synonyms

    companion object {
        fun fromKey(key: String?): Mood? = values().firstOrNull { it.key == key?.trim()?.lowercase() }

        /** Finds the mood named anywhere in [text] (longest phrase wins), or null. */
        fun findIn(text: String): Mood? {
            val folded = " " + VietnameseText.tokens(text).joinToString(" ") + " "
            return values()
                .flatMap { m -> m.phrases.map { it to m } }
                .sortedByDescending { it.first.length }
                .firstOrNull { (phrase, _) -> " $phrase " in folded }
                ?.second
        }
    }
}

/** One analysed song. Audio features and LLM verdict are filled independently. */
@Entity(tableName = "moods")
data class MoodRecord(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    // Offline audio analysis (null when the format could not be decoded)
    val bpm: Double? = null,
    val rmsDb: Double? = null,
    val dynamicRangeDb: Double? = null,
    val centroidHz: Double? = null,
    val flux: Double? = null,
    val lowEnergyRatio: Double? = null,
    /** 0 (calm) … 1 (energetic) from [AudioFeatures.arousal]. */
    val arousalAudio: Double? = null,
    val featureVersion: Int = 0,
    val featureError: String? = null,
    // LLM classification
    val valence: Double? = null,
    val arousalLlm: Double? = null,
    /** Comma-separated [Mood.key]s. */
    val labels: String? = null,
    val confidence: Double? = null,
    val llmModel: String? = null,
    val updatedAt: Long = 0,
) {
    val hasFeatures: Boolean get() = featureVersion >= FEATURE_VERSION
    val hasVerdict: Boolean get() = llmModel != null

    fun withFeatures(f: AudioFeatures?, error: String?, now: Long) = copy(
        bpm = f?.bpm, rmsDb = f?.rmsDb, dynamicRangeDb = f?.dynamicRangeDb, centroidHz = f?.spectralCentroidHz,
        flux = f?.spectralFlux, lowEnergyRatio = f?.lowEnergyRatio, arousalAudio = f?.arousal(),
        featureVersion = FEATURE_VERSION, featureError = error, updatedAt = now,
    )

    /** Raw audio features, or null when the song could not be analysed. */
    fun features(): AudioFeatures? {
        if (bpm == null || rmsDb == null || centroidHz == null) return null
        return AudioFeatures(bpm, rmsDb, dynamicRangeDb ?: 0.0, centroidHz, flux ?: 0.0, lowEnergyRatio ?: 0.0, 0.0)
    }

    /**
     * Audio arousal in 0..1: library-relative when [calibration] is available, otherwise the
     * absolute scale recomputed from the raw features (stored [arousalAudio] as last resort).
     */
    fun audioArousal(calibration: LibraryCalibration? = null): Double? =
        calibration?.arousal(this) ?: features()?.arousal() ?: arousalAudio

    /** Combined position on the mood plane; null when nothing is known yet. */
    fun profile(calibration: LibraryCalibration? = null): MoodProfile? {
        val audioArousal = audioArousal(calibration)?.let { it * 2 - 1 } // 0..1 → -1..1
        val arousal = listOfNotNull(audioArousal, arousalLlm).takeIf { it.isNotEmpty() }?.average()
        if (arousal == null && valence == null) return null
        val moods = labels?.split(',')?.mapNotNull { Mood.fromKey(it) }?.toSet().orEmpty()
        return MoodProfile(songId, valence, arousal, moods)
    }

    companion object {
        /** Bump when the feature extractor changes so songs get re-analysed. */
        const val FEATURE_VERSION = 1
    }
}

data class MoodProfile(val songId: String, val valence: Double?, val arousal: Double?, val moods: Set<Mood>)

/** What the indexer needs to know about a library song. */
data class IndexableSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val uri: String,
    val durationMs: Long,
)

/** Persistence for mood records (Room on device, in-memory in tests). */
interface MoodStore {
    suspend fun all(): List<MoodRecord>
    suspend fun upsert(records: List<MoodRecord>)
    suspend fun clear()
}

/** Read side used by the assistant. */
fun interface MoodIndex {
    suspend fun profiles(): List<MoodProfile>
}

/** Index over the store, with audio arousal calibrated against the whole library. */
fun MoodStore.asIndex() = MoodIndex {
    val records = all()
    val calibration = LibraryCalibration.from(records)
    records.mapNotNull { it.profile(calibration) }
}
