// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineCommandParserTest {

    private val parser = OfflineCommandParser()

    private fun control(action: String) = ToolCall("playback_control", mapOf("action" to action))
    private fun volume(vararg pairs: Pair<String, Any?>) = ToolCall("set_volume", mapOf(*pairs))
    private fun play(vararg pairs: Pair<String, Any?>) = ToolCall("search_and_play", mapOf(*pairs))

    private fun assertParses(expected: ToolCall?, vararg inputs: String) {
        for (input in inputs) assertEquals("input: \"$input\"", expected, parser.parse(input))
    }

    @Test
    fun driveSearchAndDownload() {
        val search = { q: String -> ToolCall("drive_search", mapOf("query" to q)) }
        assertParses(search("Lạc Trôi"), "tìm Lạc Trôi trên drive", "Tìm bài Lạc Trôi trên Google Drive nhé", "tai bai Lạc Trôi tu drive")
        assertParses(search("Em Đi"), "tải bài Em Đi từ drive", "tải Em Đi từ Google Drive")
        assertParses(ToolCall("drive_download", mapOf("file_id" to "2")), "tải số 2", "tải bài số 2", "tai 2")
        assertParses(ToolCall("drive_download", mapOf("confirmed" to true)), "đồng ý tải", "xác nhận tải", "tải đi", "tải luôn")
        assertNull(parser.parse("tìm Lạc Trôi"))
    }

    @Test
    fun next() = assertParses(
        control("next"),
        "bài tiếp", "Bài tiếp theo", "chuyển bài", "qua bài khác đi", "bỏ qua", "Next",
        "hãy chuyển bài giúp tôi", "LaHo ơi, bài kế tiếp nhé", "bai tiep", "đổi bài",
    )

    @Test
    fun previous() = assertParses(
        control("previous"),
        "bài trước", "quay lại bài trước", "lùi bài", "Bài vừa rồi", "previous track",
    )

    @Test
    fun pause() = assertParses(
        control("pause"),
        "tạm dừng", "Dừng lại!", "dừng nhạc đi", "ngừng", "tắt nhạc", "pause", "tam dung",
    )

    @Test
    fun resume() = assertParses(
        control("play"),
        "phát tiếp", "tiếp tục", "Phát", "bật nhạc lên", "mở nhạc", "play", "phát nhạc đi",
    )

    @Test
    fun volumeSteps() {
        assertParses(volume("delta" to 10), "to lên", "tăng âm lượng", "âm lượng to lên chút nữa", "louder", "mở to")
        assertParses(volume("delta" to -10), "nhỏ lại", "giảm âm lượng", "nhỏ đi một chút", "bé lại nhé")
        assertParses(volume("delta" to 20), "tăng âm lượng thêm 20%")
        assertParses(volume("delta" to -15), "giảm âm lượng bớt 15")
    }

    @Test
    fun volumeAbsoluteAndMute() {
        assertParses(volume("level" to 50), "âm lượng 50", "Âm lượng 50%", "đặt âm lượng ở mức 50 phần trăm", "volume 50")
        assertParses(volume("level" to 30), "tăng âm lượng lên 30%", "giảm âm lượng xuống 30")
        assertParses(volume("level" to 0), "tắt tiếng", "im lặng", "mute")
    }

    @Test
    fun nowPlaying() = assertParses(
        ToolCall("get_now_playing"),
        "bài gì đây?", "đang phát bài gì vậy", "bài này là bài gì", "tên bài hát này",
    )

    @Test
    fun playSongKeepsDiacritics() {
        assertParses(play("query" to "Lạc Trôi"), "phát bài Lạc Trôi", "Mở bài hát Lạc Trôi nhé", "bật Lạc Trôi")
        assertParses(play("query" to "Nơi Này Có Anh"), "hãy phát cho tôi bài Nơi Này Có Anh giúp tôi")
        assertParses(play("query" to "Hello"), "play Hello")
    }

    @Test
    fun ambiguousTrailingWordsStayInQuery() {
        // "đi" may be part of the title; the search tool retries without it if needed.
        assertParses(play("query" to "Em Đi"), "phát bài Em Đi")
        assertParses(play("query" to "Về Nhà"), "mở bài Về Nhà")
    }

    @Test
    fun playByArtist() {
        assertParses(play("artist" to "Sơn Tùng M-TP"), "phát nhạc của Sơn Tùng M-TP", "mở mấy bài của Sơn Tùng M-TP")
        assertParses(play("query" to "Lạc Trôi", "artist" to "Sơn Tùng"), "phát Lạc Trôi của Sơn Tùng")
        assertParses(play("query" to "Shape of You", "artist" to "Ed Sheeran"), "play Shape of You by Ed Sheeran")
    }

    @Test
    fun unknownOrOpenEndedRequestsAreLeftForTheLlm() = assertParses(
        null,
        "", "   ", "hôm nay trời đẹp quá", "tôi đang buồn", "đúng rồi", "tải nhạc từ google drive",
    )

    @Test
    fun openEndedPlayRequestIsNotNowPlaying() {
        // Not a "what's playing" question; with the mood index it is a mood request.
        assertEquals(mood("vui"), parser.parse("phát bài gì đó vui vui"))
    }

    private fun mood(key: String) = ToolCall("play_by_mood", mapOf("mood" to key))

    @Test
    fun moodRequestsGoToTheMoodIndex() {
        assertParses(mood("buon"), "bật nhạc buồn", "Bật nhạc buồn đi", "nhạc buồn", "phát vài bài buồn nhé", "phát bài buồn")
        assertParses(mood("soi_dong"), "mở nhạc để tập gym", "cho tôi nghe nhạc sôi động", "bật playlist quẩy")
        assertParses(mood("nhe_nhang"), "cho tôi nghe nhạc nhẹ nhàng để học bài", "mở mấy bài thư giãn", "nhạc dễ ngủ")
        assertParses(mood("lang_man"), "phát nhạc lãng mạn")
        assertParses(mood("tap_trung"), "bật nhạc tập trung")
    }

    @Test
    fun songTitlesWithMoodWordsStayTitleSearches() {
        assertParses(play("query" to "Vui Lên Đi"), "phát bài Vui Lên Đi")
        // "của" is read as "by"; SearchAndPlayTool retries the whole phrase as a title.
        assertParses(play("query" to "Buồn", "artist" to "Anh"), "mở bài hát Buồn Của Anh")
        // Artist requests are not moods.
        assertParses(play("artist" to "Sơn Tùng M-TP"), "phát nhạc của Sơn Tùng M-TP")
    }
}
