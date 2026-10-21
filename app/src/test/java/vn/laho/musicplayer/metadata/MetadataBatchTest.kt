// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryMetadataStore : MetadataStore {
    val map = LinkedHashMap<String, MetadataSuggestion>()
    override suspend fun all() = map.values.toList()
    override suspend fun upsert(rows: List<MetadataSuggestion>) = rows.forEach { map[it.songId] = it }
    override suspend fun clear() = map.clear()
}

private fun song(id: String, title: String = "T$id", artist: String = "A", album: String = "Alb", genre: String? = "Pop", year: Int = 2000) =
    MetaSong(id, title, artist, album, genre, year, 200_000)

private fun cand(conf: Double, genre: String? = "Rock", year: Int? = 1999) = MetadataCandidate(
    "MusicBrainz", conf, "t", "a", "Album", "AA", year, 2, 1, genre, 200_000, "rec", "rel", "rg",
)

class MetadataBatchTest {

    @Test
    fun onlySongsWithIncompleteTagsAreLookedUp() = runBlocking {
        val looked = mutableListOf<String>()
        val store = InMemoryMetadataStore()
        val report = MetadataBatch(
            store,
            library = {
                listOf(
                    song("complete"),
                    song("nogenre", genre = null),
                    song("noyear", year = 0),
                    song("noalbum", album = "<unknown>"),
                    song("noartist", artist = "<unknown>"),
                )
            },
            lookup = { q -> looked += q.title; listOf(cand(0.9)) },
        ).run()
        assertEquals(listOf("Tnogenre", "Tnoyear", "Tnoalbum", "Tnoartist"), looked)
        assertEquals(4, report.candidates)
        assertEquals(4, report.found)
        assertEquals("Rock", store.map["nogenre"]!!.genre)
        assertEquals(1999, store.map["noyear"]!!.year)
        assertFalse("complete" in store.map)
    }

    @Test
    fun lowConfidenceAndMissingMatchesAreRecordedAsNotFound() = runBlocking {
        val store = InMemoryMetadataStore()
        val results = mapOf("a" to listOf(cand(0.5)), "b" to emptyList(), "c" to listOf(cand(0.8)))
        val report = MetadataBatch(
            store,
            library = { listOf(song("a", genre = null), song("b", genre = null), song("c", genre = null)) },
            lookup = { q -> results.getValue(q.title.removePrefix("T")) },
        ).run()
        assertEquals(1, report.found)
        assertEquals(2, report.notFound)
        assertFalse(store.map["a"]!!.found)
        assertTrue(store.map["c"]!!.found)
        assertEquals(0.8, store.map["c"]!!.confidence!!, 1e-9)
    }

    @Test
    fun rerunSkipsDoneSongsAndRetriesFailures() = runBlocking {
        val store = InMemoryMetadataStore()
        var failB = true
        val calls = mutableListOf<String>()
        fun batch() = MetadataBatch(
            store,
            library = { listOf(song("a", genre = null), song("b", genre = null)) },
            lookup = { q ->
                calls += q.title
                if (q.title == "Tb" && failB) throw MetadataLookupException("HTTP 503", 503)
                listOf(cand(0.9))
            },
        )
        val first = batch().run()
        assertEquals(1, first.found)
        assertEquals(1, first.failed)
        assertEquals("HTTP 503", first.lastError)
        failB = false
        val second = batch().run()
        assertEquals(listOf("Ta", "Tb", "Tb"), calls) // "a" is not looked up again
        assertEquals(1, second.skipped)
        assertEquals(1, second.found)
        assertEquals(2, store.map.size)
    }

    @Test
    fun stopsAfterConsecutiveFailures() = runBlocking {
        var calls = 0
        val report = MetadataBatch(
            InMemoryMetadataStore(),
            library = { (1..20).map { song("$it", genre = null) } },
            lookup = { calls++; throw java.io.IOException("offline") },
            maxConsecutiveFailures = 3,
        ).run()
        assertEquals(3, calls)
        assertEquals(3, report.failed)
    }

    @Test
    fun cancellationStopsAndKeepsProgress() = runBlocking {
        val store = InMemoryMetadataStore()
        lateinit var job: kotlinx.coroutines.Job
        var cancelled = false
        job = launch {
            try {
                MetadataBatch(
                    store,
                    library = { (1..10).map { song("$it", genre = null) } },
                    lookup = { q -> if (q.title == "T3") job.cancel(); listOf(cand(0.9)) },
                ).run()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        job.join()
        assertTrue(cancelled)
        assertEquals(setOf("1", "2", "3"), store.map.keys)
    }

    @Test
    fun progressIsReported() = runBlocking {
        val seen = mutableListOf<MetaProgress>()
        MetadataBatch(
            InMemoryMetadataStore(),
            library = { listOf(song("a", genre = null), song("b", genre = null)) },
            lookup = { emptyList() },
        ).run { seen += it }
        assertEquals(listOf("Ta" to 0, "Tb" to 1, null to 2), seen.map { it.current to it.done })
        assertTrue(seen.all { it.total == 2 })
    }
}
