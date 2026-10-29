// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveAppDataClientTest {
    private class Tokens : DriveTokenSource {
        var refreshes = 0
        override suspend fun token(): String? = "tok-1"
        override suspend fun refresh(expired: String): String? { refreshes++; return "tok-2" }
    }

    private lateinit var server: MockWebServer
    private val tokens = Tokens()
    private lateinit var client: DriveAppDataClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = DriveAppDataClient(OkHttpClient(), tokens, server.url("/"))
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun listUsesAppDataSpaceAndFiltersByPrefix() = runBlocking {
        server.enqueue(
            json(
                """{"files":[{"id":"idid0000001","name":"laho-backup-20261029-101500.json","size":"2048","modifiedTime":"2026-10-29T10:15:00Z"},
                {"id":"idid0000002","name":"other.json"}]}""",
            ),
        )
        val files = client.list("laho-backup-")
        assertEquals(1, files.size)
        assertEquals(2048L, files[0].sizeBytes)
        val req = server.takeRequest()
        assertEquals("Bearer tok-1", req.headers["Authorization"])
        assertEquals("/drive/v3/files", req.url.encodedPath)
        assertEquals("appDataFolder", req.url.queryParameter("spaces"))
        assertEquals("name contains 'laho-backup-' and trashed = false", req.url.queryParameter("q"))
    }

    @Test fun uploadIsMultipartIntoAppDataFolder() = runBlocking {
        server.enqueue(json("""{"id":"idid0000009","name":"laho-backup-x.json","size":"11"}"""))
        val f = client.upload("laho-backup-x.json", """{"a":"héllo"}""".toByteArray(), "application/json")
        assertEquals("idid0000009", f.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/upload/drive/v3/files", req.url.encodedPath)
        assertEquals("multipart", req.url.queryParameter("uploadType"))
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/related"))
        val body = req.body!!.utf8()
        assertTrue(body.contains(""""parents":["appDataFolder"]"""))
        assertTrue(body.contains(""""name":"laho-backup-x.json""""))
        assertTrue(body.contains("héllo"))
    }

    @Test fun downloadReturnsBytesAndEnforcesLimit() = runBlocking {
        server.enqueue(MockResponse.Builder().body("hello world").build())
        assertEquals("hello world", String(client.download("idid0000001", 100)))
        assertEquals("alt=media", server.takeRequest().url.encodedQuery)
        server.enqueue(MockResponse.Builder().body("x".repeat(50)).build())
        try {
            client.download("idid0000001", 10)
            fail()
        } catch (e: DriveException) {
        }
    }

    @Test fun deleteUsesDeleteVerbOnTheId() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.delete("idid0000001")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/drive/v3/files/idid0000001", req.url.encodedPath)
    }

    @Test fun invalidIdIsRejectedBeforeAnyRequest() = runBlocking {
        try {
            client.delete("../../about")
            fail()
        } catch (e: DriveException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun refreshesOnceOn401AndMapsInsufficientScope() = runBlocking {
        server.enqueue(json("{}", 401))
        server.enqueue(json("""{"files":[]}"""))
        assertEquals(0, client.list("laho-backup-").size)
        assertEquals(1, tokens.refreshes)
        server.takeRequest()
        assertEquals("Bearer tok-2", server.takeRequest().headers["Authorization"])

        server.enqueue(json("""{"error":{"message":"Insufficient Permission"}}""", 403))
        try {
            client.list("laho-backup-")
            fail()
        } catch (e: DriveException) {
            assertEquals(403, e.httpCode)
            assertTrue(e.message!!.contains("/backup login"))
        }
    }
}
