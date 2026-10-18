// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A model call that returns JSON text (abstracted so the classifier is unit-testable). */
fun interface JsonTextModel {
    suspend fun generate(systemInstruction: String, prompt: String): String
}

/** [JsonTextModel] on Firebase AI Logic in JSON mode. */
class FirebaseJsonTextModel(val modelName: String) : JsonTextModel {
    private var cached: Pair<String, GenerativeModel>? = null

    override suspend fun generate(systemInstruction: String, prompt: String): String {
        val model = cached?.takeIf { it.first == systemInstruction }?.second
            ?: Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    temperature = 0.2f
                },
                systemInstruction = content { text(systemInstruction) },
            ).also { cached = systemInstruction to it }
        return model.generateContent(prompt).text ?: error("Empty model response")
    }
}

data class MoodInput(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val features: AudioFeatures?,
)

data class MoodVerdict(
    val id: String,
    val valence: Double,
    val arousal: Double,
    val moods: List<Mood>,
    val confidence: Double,
)

/**
 * Classifies songs in batches: the model gets title, artist, album, genre and a short
 * summary of the offline audio features, and returns valence/arousal on [-1, 1] plus mood
 * labels from the fixed [Mood] vocabulary. Songs are referenced by batch-local indices, so
 * library ids never leave the device and a malformed entry cannot hit the wrong song.
 */
class LlmMoodClassifier(private val model: JsonTextModel, val modelName: String) {

    suspend fun classify(batch: List<MoodInput>): List<MoodVerdict> {
        if (batch.isEmpty()) return emptyList()
        val raw = model.generate(SYSTEM, promptFor(batch))
        return parse(raw, batch)
    }

    internal fun promptFor(batch: List<MoodInput>): String = buildJsonArray {
        batch.forEachIndexed { i, s ->
            add(buildJsonObject {
                put("i", i)
                put("title", s.title)
                put("artist", s.artist)
                if (s.album.isNotBlank() && !s.album.startsWith("<unknown", true)) put("album", s.album)
                s.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
                s.features?.let { f ->
                    if (f.bpm > 0) put("bpm", f.bpm.toInt())
                    // Locale-independent rounding (String.format would use "," in vi-VN).
                    put("loudness_db", Math.round(f.rmsDb * 10) / 10.0)
                    put("brightness_hz", f.spectralCentroidHz.toInt())
                    put("audio_energy", Math.round(f.arousal() * 100) / 100.0)
                }
            })
        }
    }.toString()

    internal fun parse(raw: String, batch: List<MoodInput>): List<MoodVerdict> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = try {
            JSON.parseToJsonElement(raw.substring(start, end + 1)) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val seen = HashSet<Int>()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val i = (o["i"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            if (i !in batch.indices || !seen.add(i)) return@mapNotNull null
            val v = o.num("valence") ?: o.num("v") ?: return@mapNotNull null
            val a = o.num("arousal") ?: o.num("a") ?: return@mapNotNull null
            val moods = (o["moods"] ?: o["labels"])?.let { m ->
                runCatching { m.jsonArray.mapNotNull { Mood.fromKey(it.jsonPrimitive.contentOrNull) } }.getOrNull()
            }.orEmpty().distinct().take(3)
            MoodVerdict(
                id = batch[i].id,
                valence = v.coerceIn(-1.0, 1.0),
                arousal = a.coerceIn(-1.0, 1.0),
                moods = moods,
                confidence = (o.num("confidence") ?: o.num("c") ?: 0.5).coerceIn(0.0, 1.0),
            )
        }
    }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        val SYSTEM: String = """
            You classify songs by mood for a music player. For each input song (identified by "i")
            return an object {"i": <same i>, "valence": <-1..1>, "arousal": <-1..1>,
            "moods": [<1-3 keys>], "confidence": <0..1>}.
            valence: -1 very sad/dark … +1 very happy/positive (use lyrics/theme knowledge of the
            song if you know it; Vietnamese songs included). arousal: -1 very calm … +1 very
            energetic; combine your knowledge with the measured audio fields (bpm, loudness_db,
            brightness_hz, audio_energy 0..1) when present.
            Allowed mood keys: ${Mood.values().joinToString { "\"${it.key}\" (${it.label})" }}.
            If you do not know the song, infer from title words, artist style, genre and audio
            features, and lower confidence. Answer with a JSON array only, one object per input.
        """.trimIndent()
    }
}
