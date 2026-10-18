// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class FakeTokens(var current: String? = "tok-1", var next: String? = "tok-2") : DriveTokenSource {
    var refreshes = 0
    override suspend fun token() = current
    override suspend fun refresh(expired: String): String? { refreshes++; current = next; return current }
}

class DriveClientTest {
    private lateinit var server: MockWebServer
    private val tokens = FakeTokens()
    private lateinit var client: DriveClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = DriveClient(OkHttpClient(), tokens, server.url("/"))
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun searchBuildsQueryAndParsesFiles() = runBlocking {
        server.enqueue(
            json(
                """{"files":[{"id":"abcdefghij1","name":"Lạc Trôi.mp3","size":"4096","mimeType":"audio/mpeg","modifiedTime":"2026-01-01T00:00:00Z"},
                {"id":"abcdefghij2","name":"x","mimeType":"audio/flac"}]}""",
            ),
        )
        val files = client.search("lạc trôi", 5)
        assertEquals(2, files.size)
        assertEquals(4096L, files[0].sizeBytes)
        assertNull(files[1].sizeBytes)
        val req = server.takeRequest()
        assertEquals("Bearer tok-1", req.headers["Authorization"])
        val url = req.url
        assertEquals("/drive/v3/files", url.encodedPath)
        assertEquals("name contains 'lạc trôi' and mimeType contains 'audio/' and trashed = false", url.queryParameter("q"))
        assertEquals("files(id,name,size,mimeType,modifiedTime)", url.queryParameter("fields"))
        assertEquals("5", url.queryParameter("pageSize"))
    }

    @Test fun queryEscapesQuotesAndBackslashes() {
        assertEquals("it\\'s \\\\ ok", DriveClient.escape("it's \\ ok"))
        val q = DriveClient.audioNameQuery("x' or name contains '")
        assertEquals("name contains 'x\\' or name contains \\'' and mimeType contains 'audio/' and trashed = false", q)
    }

    @Test fun unauthorizedRefreshesTokenOnce() = runBlocking {
        server.enqueue(json("{}", 401))
        server.enqueue(json("""{"files":[]}"""))
        assertTrue(client.search("a", 3).isEmpty())
        assertEquals(1, tokens.refreshes)
        server.takeRequest()
        assertEquals("Bearer tok-2", server.takeRequest().headers["Authorization"])
    }

    @Test fun persistent401RequiresAuthorization() = runBlocking {
        server.enqueue(json("{}", 401))
        server.enqueue(json("{}", 401))
        try { client.search("a", 3); fail() } catch (e: DriveAuthRequiredException) { /* expected */ }
    }

    @Test fun noTokenRequiresAuthorizationWithoutNetwork() = runBlocking {
        tokens.current = null
        try { client.search("a", 3); fail() } catch (e: DriveAuthRequiredException) { /* expected */ }
        assertEquals(0, server.requestCount)
    }

    @Test fun errorStatusesBecomeDriveExceptions() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Rate Limit"}}""", 403))
        try { client.search("a", 3); fail() } catch (e: DriveException) {
            assertEquals(403, e.httpCode); assertTrue(e.message!!.contains("Rate Limit"))
        }
        server.enqueue(json("{}", 404))
        try { client.file("abcdefghij1"); fail() } catch (e: DriveException) { assertEquals(404, e.httpCode) }
    }

    @Test fun invalidFileIdIsRejectedBeforeAnyRequest() = runBlocking {
        try { client.file("../about"); fail() } catch (e: DriveException) { /* expected */ }
        assertEquals(0, server.requestCount)
    }

    @Test fun downloadStreamsBytes() = runBlocking {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(data)).build())
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, client.download("abcdefghij1", out))
        assertArrayEquals(data, out.toByteArray())
        val url = server.takeRequest().url
        assertEquals("/drive/v3/files/abcdefghij1", url.encodedPath)
        assertEquals("media", url.queryParameter("alt"))
    }

    @Test fun downloadStopsAtSizeCap() = runBlocking {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(10_000))).build())
        try { client.download("abcdefghij1", ByteArrayOutputStream(), maxBytes = 5_000); fail() } catch (e: DriveException) { /* expected */ }
    }

    @Test fun safeNameStripsControlAndPathCharacters() {
        assertEquals("a b c", DriveRules.safeName("a\nb/\\c"))
        assertFalse(DriveRules.safeName("x".repeat(500)).length > 120)
        assertEquals("song.mp3", DriveRules.fileName("song", "audio/mpeg"))
        assertEquals("song.flac", DriveRules.fileName("song.flac", "audio/flac"))
        assertTrue(DriveRules.isAudioMime("Audio/MPEG"))
        assertFalse(DriveRules.isAudioMime("application/pdf"))
    }
}
