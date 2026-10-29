// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.privacy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.OnlineAssistant
import vn.laho.musicplayer.assistant.ToolRegistry

class AiPrivacyTest {

    private class MemStore(var value: Boolean? = null) : FlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private val noTools = ToolRegistry(emptyList())

    @Test
    fun defaultIsOnInDebugAndOffInReleaseUntilChosen() {
        assertTrue(AiPrivacySettings(MemStore(), isDebugBuild = true).isOnlineEnabled())
        assertFalse(AiPrivacySettings(MemStore(), isDebugBuild = false).isOnlineEnabled())
    }

    @Test
    fun explicitChoiceWinsAndPersists() {
        val store = MemStore()
        val s = AiPrivacySettings(store, isDebugBuild = true)
        s.setOnlineEnabled(false)
        assertFalse(s.enabled.value)
        assertEquals(false, store.value)
        assertTrue(AiPrivacySettings(MemStore(true), isDebugBuild = false).isOnlineEnabled())
        assertFalse(AiPrivacySettings(MemStore(false), isDebugBuild = true).isOnlineEnabled())
    }

    @Test
    fun gateOffYieldsNoBackendAndNeverCreatesOne() {
        var created = 0
        var enabled = false
        val gate = GatedOnlineAssistant({ enabled }, { created++; EchoAssistant })
        assertNull(gate.current())
        val reply = runBlocking { gate.handle("bật nhạc buồn", noTools) }
        assertEquals(AssistantReply.Source.NONE, reply.source)
        assertEquals(GatedOnlineAssistant.OFF_MESSAGE, reply.text)
        assertEquals(0, created)

        enabled = true
        assertSame(EchoAssistant, gate.current())
        assertEquals("echo", runBlocking { gate.handle("x", noTools) }.text)
        assertEquals(1, created)
    }

    @Test
    fun turningOffDropsTheBackendSoHistoryIsForgotten() {
        var created = 0
        var enabled = true
        val gate = GatedOnlineAssistant({ enabled }, { created++; EchoAssistant })
        gate.current()
        enabled = false
        assertNull(gate.current())
        enabled = true
        gate.current()
        assertEquals(2, created)
    }

    @Test
    fun missingFirebaseReportsOfflineOnlyNotOffSwitch() {
        val gate = GatedOnlineAssistant({ true }, { null })
        assertEquals(GatedOnlineAssistant.NO_BACKEND, runBlocking { gate.handle("x", noTools) }.text)
    }

    @Test
    fun librarySummaryIsCappedAndHasNoPathsOrUris() {
        val data = mapOf(
            "songCount" to 1234,
            "topGenres" to (1..30).map { "Genre$it ($it)" },
            "topArtists" to (1..30).map { "Artist$it ($it)" },
            "sampleTitles" to (1..30).map { "Title$it – Artist" } + "x".repeat(200),
            "moodIndexed" to 10,
            "uri" to "content://media/external/audio/media/1",
            "path" to "/storage/emulated/0/Music/a.flac",
        )
        val text = LibrarySummary.format(data)
        assertTrue(text.contains("1234 bài"))
        assertTrue(text.contains("Genre10 (10)"))
        assertFalse(text.contains("Genre11 "))
        assertTrue(text.contains("Artist10 (10)"))
        assertFalse(text.contains("Artist11 "))
        assertTrue(text.contains("Title5 "))
        assertFalse(text.contains("Title6 "))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/storage/"))
        assertTrue("summary stays small", text.length < 700)
    }

    @Test
    fun longNamesAreTruncated() {
        val text = LibrarySummary.format(mapOf("songCount" to 1, "topArtists" to listOf("a".repeat(500))))
        assertTrue(text.length < 200)
        assertTrue(text.contains("…"))
    }

    @Test
    fun emptyLibraryFieldsAreOmitted() {
        val text = LibrarySummary.format(mapOf("songCount" to 3, "topGenres" to emptyList<String>()))
        assertTrue(text.contains("không có thông tin thể loại"))
        assertFalse(text.contains("Nghệ sĩ"))
        assertFalse(text.contains("bài mẫu"))
    }

    private object EchoAssistant : OnlineAssistant {
        override suspend fun handle(text: String, tools: ToolRegistry) =
            AssistantReply("echo", AssistantReply.Source.ONLINE)
    }
}
