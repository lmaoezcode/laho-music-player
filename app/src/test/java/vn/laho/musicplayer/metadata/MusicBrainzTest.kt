// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal fun fixture(name: String): String =
    MusicBrainzTest::class.java.getResourceAsStream("/musicbrainz/$name")!!.bufferedReader(Charsets.UTF_8).readText()

class MusicBrainzTest {
    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client(maxRetries: Int = 3) = MusicBrainzClient(
        http = OkHttpClient(),
        userAgent = "LaHoMusicPlayer/test ( https://example.org )",
        baseUrl = server.url("/ws/2/"),
        limiter = RateLimiter(0),
        maxRetries = maxRetries,
        sleep = { waits += it },
    )

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    private val query = LookupQuery("Lạc Trôi", "Sơn Tùng M-TP", 232_000)

    @Test
    fun parsesRecordings() {
        val rs = MusicBrainzParser.parseRecordingSearch(fixture("recording_search.json"))
        assertEquals(3, rs.size)
        val main = rs[1]
        assertEquals("rec-main", main.id)
        assertEquals(231_000L, main.lengthMs)
        assertEquals("Sơn Tùng M-TP", main.artistName)
        assertEquals(listOf("artist-stmtp"), main.artistCredit.mapNotNull { it.artistId })
        assertEquals(3, main.releases.size)
        val album = main.releases.first { it.id == "rel-album" }
        assertEquals("Album", album.primaryType)
        assertEquals("rg-album", album.releaseGroupId)
        assertEquals(3, album.trackNumber)
        assertEquals(1, album.discNumber)
        val comp = main.releases.first { it.id == "rel-comp" }
        assertEquals(listOf("Compilation"), comp.secondaryTypes)
        assertEquals(2, comp.discNumber)
        assertTrue(MusicBrainzParser.parseRecordingSearch(fixture("empty_search.json")).isEmpty())
        assertTrue(MusicBrainzParser.parseRecordingSearch("[]").isEmpty())
    }

    @Test
    fun genreSkipsNonGenreTagsAndTitleCases() {
        assertEquals("V-Pop", MusicBrainzParser.bestGenre(listOf(MbTag("seen live", 9), MbTag("v-pop", 4), MbTag("pop", 2))))
        assertNull(MusicBrainzParser.bestGenre(listOf(MbTag("seen live", 9), MbTag("1990s", 3))))
        assertEquals("Vietnamese Pop", MusicBrainzParser.parseGenre(fixture("release_group_genres.json")))
    }

    @Test
    fun searchSendsRequiredHeadersAndQuery() = runBlocking {
        server.enqueue(json(fixture("recording_search.json")))
        val result = client().searchRecordings(query)
        assertEquals(3, result.size)
        val req = server.takeRequest()
        assertEquals("LaHoMusicPlayer/test ( https://example.org )", req.headers["User-Agent"])
        assertEquals("/ws/2/recording", req.url.encodedPath)
        assertEquals("json", req.url.queryParameter("fmt"))
        val q = req.url.queryParameter("query")!!
        assertTrue(q, q.contains("recording:\"Lạc Trôi\""))
        assertTrue(q, q.contains("artist:\"Sơn Tùng M\\-TP\""))
        assertTrue(q, q.contains("dur:[229000 TO 235000]"))
    }

    @Test
    fun retriesOn503WithBackoffThenSucceeds() = runBlocking {
        server.enqueue(json("busy", 503))
        server.enqueue(json("busy", 503))
        server.enqueue(json(fixture("recording_search.json")))
        assertEquals(3, client().searchRecordings(query).size)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun honoursRetryAfterHeader() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "7").build())
        server.enqueue(json(fixture("empty_search.json")))
        client().searchRecordings(query)
        assertEquals(listOf(7_000L), waits)
    }

    @Test
    fun givesUpAfterMaxRetries() {
        repeat(3) { server.enqueue(json("busy", 503)) }
        val e = runCatching { runBlocking { client(maxRetries = 2).searchRecordings(query) } }.exceptionOrNull()
        assertTrue(e is MetadataLookupException)
        assertEquals(503, (e as MetadataLookupException).httpCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun otherErrorsAreNotRetried() {
        server.enqueue(json("nope", 400))
        val e = runCatching { runBlocking { client().searchRecordings(query) } }.exceptionOrNull()
        assertEquals(400, (e as MetadataLookupException).httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun lookupRanksRecordingsAndUsesRecordingTagGenre() = runBlocking {
        server.enqueue(json(fixture("recording_search.json")))
        val result = MetadataLookup(client()).candidates(query)
        val top = result.first()
        assertEquals("rec-main", top.recordingMbid)
        assertEquals("V-Pop", top.genre)
        assertEquals(1, server.requestCount) // no release-group request needed
        assertEquals("rel-album", top.releaseMbid) // official studio album beats single and compilation
        assertEquals("m-tp M-TP", top.album)
        assertEquals("Sơn Tùng M-TP", top.albumArtist)
        assertEquals(2017, top.year)
        assertEquals(3, top.trackNumber)
        assertEquals(1, top.discNumber)
        assertTrue(top.confidence > 0.95)
        // Same artist but 30 s longer (live version): capped well below the studio take.
        assertEquals("rec-single-live", result[1].recordingMbid)
        assertTrue(result[1].confidence <= MatchScorer.CAP_DURATION_MISMATCH)
    }

    @Test
    fun lookupFallsBackToReleaseGroupGenre() = runBlocking {
        val noTags = fixture("recording_search.json").replace("\"v-pop\"", "\"seen live\"").replace("\"pop\"", "\"seen live\"")
        server.enqueue(json(noTags))
        server.enqueue(json(fixture("release_group_genres.json")))
        val top = MetadataLookup(client()).candidates(query).first()
        assertEquals("Vietnamese Pop", top.genre)
        server.takeRequest()
        assertEquals("/ws/2/release-group/rg-album", server.takeRequest().url.encodedPath)
    }

    @Test
    fun noArtistHitsRetryByTitleOnly() = runBlocking {
        server.enqueue(json(fixture("empty_search.json")))
        server.enqueue(json(fixture("recording_search.json")))
        val result = MetadataLookup(client()).candidates(query, max = 3)
        assertEquals(2, server.requestCount)
        assertFalse(result.isEmpty())
        assertTrue(server.takeRequest().url.queryParameter("query")!!.contains("artist:"))
        assertFalse(server.takeRequest().url.queryParameter("query")!!.contains("artist:"))
    }

    @Test
    fun userAgentHasProductVersionAndContact() {
        val ua = MusicBrainzClient.userAgent("5.5.500 Beta-debug")
        assertEquals("LaHoMusicPlayer/5.5.500-Beta-debug ( https://github.com/lmaoezcode/laho-music-player )", ua)
    }

    @Test
    fun luceneEscaping() {
        assertEquals("AC\\/DC \\(live\\)", MusicBrainzClient.escape("AC/DC (live)"))
        assertNull(MusicBrainzClient.buildQuery(LookupQuery("   ", "x", null)))
    }

    @Test
    fun coverUrlsPreferReleaseThenReleaseGroup() {
        val c = MatchScorer.candidate(query, MusicBrainzParser.parseRecordingSearch(fixture("recording_search.json"))[1])
        assertEquals(
            listOf(
                "https://coverartarchive.org/release/rel-album/front-500",
                "https://coverartarchive.org/release-group/rg-album/front-500",
            ),
            c.coverUrls(),
        )
    }
}
