// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineChainParserTest {
    private val parser = OfflineCommandParser()
    private fun names(text: String) = parser.parsePlan(text)?.map { it.name }

    @Test fun driveSearchDownloadPlay() {
        val plan = parser.parsePlan("tìm bài Lạc Trôi trên drive, tải về rồi phát")!!
        assertEquals(listOf("drive_search", "drive_download", "play_downloaded"), plan.map { it.name })
        assertEquals("Lạc Trôi", plan[0].args["query"])
        assertEquals("1", plan[1].args["file_id"])
    }

    @Test fun playlistThenShuffle() {
        val plan = parser.parsePlan("tạo playlist nhạc buồn 20 bài rồi phát ngẫu nhiên")!!
        assertEquals(listOf("create_playlist", "play_playlist"), plan.map { it.name })
        assertEquals("buon", plan[0].args["mood"])
        assertEquals(20, plan[0].args["limit"])
        assertEquals(true, plan[1].args["shuffle"])
    }

    @Test fun queueAddByArtist() {
        val plan = parser.parsePlan("thêm 5 bài của Đen vào hàng đợi")!!
        assertEquals(1, plan.size)
        assertEquals("queue_add", plan[0].name)
        assertEquals("Đen", plan[0].args["artist"])
        assertEquals(5, plan[0].args["count"])
    }

    @Test fun otherSingleCommands() {
        assertEquals(mapOf("minutes" to 30), parser.parsePlan("hẹn giờ tắt nhạc sau 30 phút")!![0].args)
        assertEquals(mapOf("minutes" to 120), parser.parsePlan("hẹn giờ 2 tiếng")!![0].args)
        assertEquals("queue_clear", parser.parsePlan("xóa hàng đợi")!![0].name)
        assertEquals("sleep_timer", parser.parsePlan("hủy hẹn giờ tắt nhạc")!![0].name)
    }

    @Test fun mixedChain() {
        assertEquals(listOf("set_volume", "playback_control"), names("giảm âm lượng rồi tạm dừng"))
        assertEquals(listOf("search_and_play", "sleep_timer"), names("phát bài Lạc Trôi rồi hẹn giờ tắt nhạc sau 30 phút"))
    }

    @Test fun chainOnlyWhenEveryPartIsOffline() {
        assertNull(parser.parsePlan("kể chuyện cười rồi phát nhạc"))
    }

    @Test fun songTitleWithRoiIsNotSplit() {
        val plan = parser.parsePlan("phát bài Yêu Là Rồi Quên")!!
        assertEquals(listOf("search_and_play"), plan.map { it.name })
        assertEquals("Yêu Là Rồi Quên", plan[0].args["query"])
    }

    @Test fun confirmWords() {
        assertEquals(true, ConfirmWords.classify("có"))
        assertEquals(true, ConfirmWords.classify("Đồng ý nhé"))
        assertEquals(false, ConfirmWords.classify("không"))
        assertEquals(false, ConfirmWords.classify("thôi"))
        assertNull(ConfirmWords.classify("phát bài khác"))
    }

    @Test fun multiStepHint() {
        assertTrue(OfflineCommandParser.looksMultiStep("tạo playlist rồi phát"))
        assertFalse(OfflineCommandParser.looksMultiStep("bật nhạc buồn"))
    }
}

class AgentToolsAndChainTest {

    private fun engine(rig: AgentRig) = AssistantEngine(rig.registry)

    @Test fun createPlaylistThenPlayShuffled() = runBlocking {
        val rig = AgentRig()
        val reply = engine(rig).handle("tạo playlist nhạc buồn 2 bài rồi phát ngẫu nhiên", rig.listener)

        assertEquals(1, rig.playlists.stored.size)
        val (name, ids) = rig.playlists.stored.values.single()
        assertEquals("Nhạc buồn", name)
        assertEquals(2, ids.size)
        assertTrue(ids.all { it in setOf("1", "2", "4") })
        assertEquals(ids.toSet(), rig.player.queue.map { it.id }.toSet())
        assertEquals(2, reply.steps)
        assertTrue(reply.text, reply.text.startsWith("1/2 Đã tạo playlist"))
        assertEquals(
            listOf("1/2 Tạo playlist…", "✓ 1/2 Tạo playlist", "2/2 Phát ngẫu nhiên playlist…", "✓ 2/2 Phát ngẫu nhiên playlist"),
            rig.progress,
        )
    }

    @Test fun playlistNameCollisionGetsSuffix() = runBlocking {
        val rig = AgentRig()
        val e = engine(rig)
        e.handle("tạo playlist nhạc buồn 2 bài")
        e.handle("tạo playlist nhạc buồn 2 bài")
        assertEquals(listOf("Nhạc buồn", "Nhạc buồn 2"), rig.playlists.stored.values.map { it.first })
    }

    @Test fun queueAddByArtistLeavesPlaybackAlone() = runBlocking {
        val rig = AgentRig()
        val reply = engine(rig).handle("thêm 2 bài của Ngọt vào hàng đợi")
        assertTrue(reply.text, reply.result!!.success)
        assertEquals(setOf("4", "5"), rig.queue.added.map { it.id }.toSet())
        assertTrue(rig.player.log.isEmpty())
    }

    @Test fun queueAddUnknownArtistFails() = runBlocking {
        val rig = AgentRig()
        assertFalse(engine(rig).handle("thêm 3 bài của Nobody vào hàng đợi").result!!.success)
        assertTrue(rig.queue.added.isEmpty())
    }

    @Test fun sleepTimerStartsAndCancels() = runBlocking {
        val rig = AgentRig()
        val e = engine(rig)
        e.handle("hẹn giờ tắt nhạc sau 30 phút")
        e.handle("hủy hẹn giờ tắt nhạc")
        assertEquals(listOf("start:30", "cancel"), rig.timer.log)
    }

    @Test fun chainStopsAtFirstFailureAndSkipsTheRest() = runBlocking {
        val rig = AgentRig()
        val reply = engine(rig).handle("tạo playlist của Nobody rồi phát ngẫu nhiên", rig.listener)
        assertTrue(rig.playlists.stored.isEmpty())
        assertTrue(rig.player.log.isEmpty())
        assertTrue(reply.text, "bỏ qua 1 bước" in reply.text)
        assertTrue(rig.progress.last().startsWith("✗ 1/2"))
    }

    @Test fun stepCapIsEnforcedOffline() = runBlocking {
        val rig = AgentRig()
        val reply = engine(rig).handle(List(8) { "bài tiếp" }.joinToString(" rồi "), rig.listener)
        assertEquals(6, rig.player.log.count { it == "next" })
        assertTrue(reply.text, "tối đa 6 bước" in reply.text)
    }

    @Test fun driveChainPausesForConfirmationAndResumesOnYes() = runBlocking {
        val rig = AgentRig(drive = true)
        val e = engine(rig)
        val paused = e.handle("tìm bài Lạc Trôi trên drive, tải về rồi phát", rig.listener)

        assertTrue(e.awaitingConfirmation)
        assertTrue(paused.text, paused.text.startsWith("Bước 2/3:") && paused.text.endsWith("(có/không)"))
        assertTrue(rig.driveFiles.downloads.isEmpty())
        assertTrue(rig.queue.played.isEmpty())

        val done = e.handle("có", rig.listener)
        assertFalse(e.awaitingConfirmation)
        assertEquals(listOf("aaaaaaaaaa1"), rig.driveFiles.downloads)
        assertEquals(listOf("Lạc Trôi@content://media/aaaaaaaaaa1"), rig.queue.played)
        assertEquals(3, done.text.lines().size)
        assertTrue(rig.progress.any { it.startsWith("? 2/3") })
        assertTrue(rig.progress.last().startsWith("✓ 3/3"))
    }

    @Test fun driveChainCancelsOnNo() = runBlocking {
        val rig = AgentRig(drive = true)
        val e = engine(rig)
        e.handle("tìm bài Lạc Trôi trên drive, tải về rồi phát")
        val reply = e.handle("không", rig.listener)
        assertTrue(rig.driveFiles.downloads.isEmpty())
        assertTrue(rig.queue.played.isEmpty())
        assertTrue(reply.text, "huỷ" in reply.text)
        assertFalse(e.awaitingConfirmation)
    }

    @Test fun unrelatedMessageAbandonsThePausedStep() = runBlocking {
        val rig = AgentRig(drive = true)
        val e = engine(rig)
        e.handle("tìm bài Lạc Trôi trên drive, tải về rồi phát")
        e.handle("bài tiếp")
        assertTrue(rig.driveFiles.downloads.isEmpty())
        e.handle("có")
        assertTrue(rig.driveFiles.downloads.isEmpty())
    }

    @Test fun queueClearAsksFirst() = runBlocking {
        val rig = AgentRig()
        rig.queue.size = 5
        val e = engine(rig)
        val ask = e.handle("xóa hàng đợi")
        assertTrue(e.awaitingConfirmation)
        assertEquals(5, rig.queue.size)
        assertNotNull(ask.pending)
        e.handle("có")
        assertEquals(1, rig.queue.size)
    }
}
