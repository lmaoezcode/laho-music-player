// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryMoodStore : MoodStore {
    val map = LinkedHashMap<String, MoodRecord>()
    var upserts = 0
    override suspend fun all() = map.values.toList()
    override suspend fun upsert(records: List<MoodRecord>) { upserts++; records.forEach { map[it.songId] = it } }
    override suspend fun clear() = map.clear()
}

private fun song(id: String, title: String, artist: String = "A", genre: String? = null) =
    IndexableSong(id, title, artist, "Album", genre, "content://x/$id", 200_000)

private fun features(arousal: Double) = AudioFeatures(
    bpm = 60 + 100 * arousal, rmsDb = -30 + 22 * arousal, dynamicRangeDb = 8.0,
    spectralCentroidHz = 800 + 2700 * arousal, spectralFlux = 0.5 + 3.5 * arousal, lowEnergyRatio = 0.2, analysedSeconds = 30.0,
)

class MoodVocabularyTest {
    @Test
    fun findsMoodsInVietnameseRequests() {
        assertEquals(Mood.SAD, Mood.findIn("bật nhạc buồn đi"))
        assertEquals(Mood.ENERGETIC, Mood.findIn("nhạc để tập gym"))
        assertEquals(Mood.CALM, Mood.findIn("cho tôi nghe gì đó nhẹ nhàng để học bài")) // longest phrase wins
        assertEquals(Mood.FOCUS, Mood.findIn("nhạc học bài"))
        assertEquals(Mood.ROMANTIC, Mood.findIn("nhạc LÃNG MẠN"))
        assertNull(Mood.findIn("phát bài Lạc Trôi"))
        assertNull(Mood.findIn("người yêu cũ")) // "ngu" alone is not a mood word
    }

    @Test
    fun keysRoundTrip() {
        Mood.values().forEach { assertEquals(it, Mood.fromKey(it.key)) }
        assertNull(Mood.fromKey("angry"))
    }

    @Test
    fun profileCombinesAudioAndLlmArousal() {
        val r = MoodRecord("1", "t", "a", arousalAudio = 1.0, valence = -0.5, arousalLlm = 0.0, labels = "buon,xyz")
        val p = r.profile()!!
        assertEquals(-0.5, p.valence!!, 1e-9)
        assertEquals(0.5, p.arousal!!, 1e-9) // avg of audio (+1) and llm (0)
        assertEquals(setOf(Mood.SAD), p.moods)
        assertNull(MoodRecord("2", "t", "a").profile())
        assertEquals(-1.0, MoodRecord("3", "t", "a", arousalAudio = 0.0).profile()!!.arousal!!, 1e-9)
    }
}

class LlmMoodClassifierTest {
    private val batch = listOf(
        MoodInput("song-17", "Em Của Ngày Hôm Qua", "Sơn Tùng M-TP", "m-tp", "V-Pop", features(0.7)),
        MoodInput("song-42", "Một Ngày Mùa Đông", "Mỹ Tâm", "", null, null),
    )

    @Test
    fun promptUsesBatchIndicesAndLocaleIndependentNumbers() {
        val defaultLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("vi-VN"))
            val prompt = LlmMoodClassifier({ _, _ -> "[]" }, "m").promptFor(batch)
            assertFalse("library ids stay on device", "song-17" in prompt)
            assertTrue(prompt, "\"i\":0" in prompt && "\"i\":1" in prompt)
            assertTrue(prompt, "\"loudness_db\":-14.6" in prompt) // dot, not comma
            assertFalse("unknown/blank album omitted", "\"album\":\"\"" in prompt)
        } finally {
            java.util.Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun parsesAndSanitisesTheModelAnswer() = runBlocking {
        val answer = """Here you go:
            [{"i":1,"valence":-0.8,"arousal":-0.5,"moods":["buon","co_don","nhe_nhang"],"confidence":0.9},
             {"i":0,"v":1.7,"a":"0.6","labels":["vui","soi_dong","vui","lang_man","buon"]},
             {"i":0,"valence":-1,"arousal":-1},
             {"i":7,"valence":0,"arousal":0},
             {"valence":0}]"""
        val verdicts = LlmMoodClassifier({ _, _ -> answer }, "m").classify(batch).associateBy { it.id }
        assertEquals(2, verdicts.size)
        val sad = verdicts.getValue("song-42")
        assertEquals(-0.8, sad.valence, 1e-9)
        assertEquals(listOf(Mood.SAD, Mood.CALM), sad.moods) // unknown "co_don" dropped
        val happy = verdicts.getValue("song-17")
        assertEquals(1.0, happy.valence, 1e-9) // clamped
        assertEquals(0.6, happy.arousal, 1e-9) // numeric string accepted
        assertEquals(listOf(Mood.HAPPY, Mood.ENERGETIC, Mood.ROMANTIC), happy.moods) // dedup, max 3
        assertEquals(0.5, happy.confidence, 1e-9) // default
    }

    @Test
    fun garbageAnswerYieldsNoVerdicts() = runBlocking {
        assertTrue(LlmMoodClassifier({ _, _ -> "xin lỗi, tôi không biết" }, "m").classify(batch).isEmpty())
        assertTrue(LlmMoodClassifier({ _, _ -> "[{broken" }, "m").classify(batch).isEmpty())
    }
}

class MoodIndexerTest {

    private val library = listOf(
        song("1", "Calm Song"), song("2", "Loud Song"), song("3", "Broken.ape"), song("4", "Unknown"),
    )

    private fun classifier(calls: MutableList<String>, skip: Set<String> = emptySet()) =
        LlmMoodClassifier({ _, prompt ->
            calls += prompt
            val count = Regex("\"i\":(\\d+)").findAll(prompt).count()
            (0 until count).filter { "item$it" !in skip }
                .joinToString(",", "[", "]") { """{"i":$it,"valence":0.${it + 1},"arousal":-0.$it,"moods":["vui"]}""" }
        }, "test-model")

    @Test
    fun indexesAudioThenLlmAndResumesWithoutRedoing() = runBlocking {
        val store = InMemoryMoodStore()
        val analysed = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val indexer = MoodIndexer(
            store, { library },
            analyse = { s -> analysed += s.id; if (s.id == "3") null else features(if (s.id == "2") 0.9 else 0.1) },
            classifier = classifier(calls), batchSize = 3,
        )
        val stages = mutableListOf<IndexProgress.Stage>()
        val report = indexer.run { stages += it.stage }

        assertEquals(4, report.songs)
        assertEquals(3, report.analysed)
        assertEquals(1, report.decodeFailures)
        assertEquals(4, report.classified)
        assertEquals(4, report.indexedWithMood)
        assertEquals(2, calls.size) // 4 songs in batches of 3
        assertTrue(store.map.getValue("2").arousalAudio!! > store.map.getValue("1").arousalAudio!!)
        assertEquals("decode_failed", store.map.getValue("3").featureError)
        assertEquals(IndexProgress.Stage.DONE, stages.last())

        // Second run: nothing left to do.
        analysed.clear(); calls.clear()
        val again = indexer.run()
        assertTrue(analysed.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(4, again.indexedWithMood)
    }

    @Test
    fun offlineRunStillIndexesArousal() = runBlocking {
        val store = InMemoryMoodStore()
        val report = MoodIndexer(store, { library }, { features(0.5) }, classifier = null).run()
        assertEquals(0, report.classified)
        assertEquals(4, report.indexedWithMood) // arousal from audio only
        assertNull(store.map.getValue("1").valence)
    }

    @Test
    fun songsTheModelSkipsAreRetriedLater() = runBlocking {
        val store = InMemoryMoodStore()
        val calls = mutableListOf<String>()
        val first = MoodIndexer(store, { library }, { null }, classifier(calls, skip = setOf("item1")), batchSize = 10).run()
        assertEquals(3, first.classified)
        assertEquals(1, first.llmFailures)
        calls.clear()
        val second = MoodIndexer(store, { library }, { null }, classifier(calls), batchSize = 10).run()
        assertEquals(1, second.classified)
        assertEquals(1, calls.size)
    }

    @Test
    fun failingModelCountsAsFailuresNotCrash() = runBlocking {
        val broken = LlmMoodClassifier({ _, _ -> error("Firebase App Check token is invalid.") }, "m")
        val errors = mutableListOf<Exception>()
        val report = MoodIndexer(InMemoryMoodStore(), { library }, { null }, broken, onError = { errors += it }).run()
        assertEquals(4, report.llmFailures)
        assertEquals(0, report.indexedWithMood)
        assertEquals("Firebase App Check token is invalid.", report.lastLlmError)
        assertEquals(1, errors.size)
    }
}
