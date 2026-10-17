// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/** play / pause / next / previous. */
class PlaybackControlTool(private val player: PlayerGateway) : AssistantTool {
    override val name = "playback_control"
    override val description = "Điều khiển phát nhạc: phát tiếp, tạm dừng, chuyển bài tiếp theo hoặc bài trước."
    override val parameters = listOf(
        ToolParameter(
            name = "action",
            type = ToolParameter.Type.STRING,
            description = "Hành động cần thực hiện",
            required = true,
            enumValues = ACTIONS,
        ),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        when (args.stringArg("action")?.lowercase()) {
            "play" -> { player.play(); ToolResult.ok("Đang phát tiếp.") }
            "pause" -> { player.pause(); ToolResult.ok("Đã tạm dừng.") }
            "next" -> { player.next(); ToolResult.ok("Đã chuyển sang bài tiếp theo.") }
            "previous" -> { player.previous(); ToolResult.ok("Đã quay lại bài trước.") }
            else -> ToolResult.fail("Hành động không hợp lệ. Dùng một trong: ${ACTIONS.joinToString()}.")
        }

    companion object {
        val ACTIONS = listOf("play", "pause", "next", "previous")
    }
}

/** Sets the media volume to an absolute level or changes it by a delta (percent). */
class SetVolumeTool(private val player: PlayerGateway) : AssistantTool {
    override val name = "set_volume"
    override val description =
        "Đặt âm lượng nhạc. Dùng 'level' (0-100) để đặt mức cụ thể, hoặc 'delta' (-100..100) để tăng/giảm."
    override val parameters = listOf(
        ToolParameter("level", ToolParameter.Type.INTEGER, "Mức âm lượng tuyệt đối, 0-100 phần trăm"),
        ToolParameter("delta", ToolParameter.Type.INTEGER, "Mức thay đổi, ví dụ 10 để tăng, -10 để giảm"),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val level = args.intArg("level")
        val delta = args.intArg("delta")
        val target = when {
            level != null -> level
            delta != null -> player.volumePercent() + delta
            else -> return ToolResult.fail("Cần 'level' hoặc 'delta'.")
        }.coerceIn(0, 100)
        player.setVolumePercent(target)
        val message = if (target == 0) "Đã tắt tiếng." else "Âm lượng: $target%."
        return ToolResult.ok(message, mapOf("level" to target))
    }
}

/** Finds songs in the local library and plays them. */
class SearchAndPlayTool(private val player: PlayerGateway) : AssistantTool {
    override val name = "search_and_play"
    override val description =
        "Tìm bài hát trong thư viện nhạc trên máy theo tên bài và/hoặc nghệ sĩ rồi phát ngay."
    override val parameters = listOf(
        ToolParameter("query", ToolParameter.Type.STRING, "Tên bài hát, album hoặc từ khoá"),
        ToolParameter("artist", ToolParameter.Type.STRING, "Tên nghệ sĩ (nếu người dùng nói 'nhạc của ...')"),
        ToolParameter(
            "genre",
            ToolParameter.Type.STRING,
            "Thể loại, dùng đúng tên thể loại có trong thư viện (xem get_library_overview)",
        ),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = args.stringArg("query")
        val artist = args.stringArg("artist")
        val genre = args.stringArg("genre")
        if (query == null && artist == null && genre == null) return ToolResult.fail("Bạn muốn nghe bài gì?")

        val library = player.librarySongs()
        if (library.isEmpty()) return ToolResult.fail("Thư viện nhạc đang trống. Hãy quét nhạc trước.")

        var matches = LibrarySearch.search(library, query, artist, genre = genre)
        if (matches.isEmpty()) {
            // Spoken requests often end in words like "đi"/"nha" that may or may not belong
            // to the title ("Em Đi", "Về Nhà"); only drop them when the literal search fails.
            val q2 = query?.let(::dropTrailingFillers)
            val a2 = artist?.let(::dropTrailingFillers)
            if (q2 != query || a2 != artist) matches = LibrarySearch.search(library, q2, a2, genre = genre)
        }
        if (matches.isEmpty()) {
            val what = listOfNotNull(
                query?.let { "\"$it\"" },
                artist?.let { "của $it" },
                genre?.let { "thể loại $it" },
            ).joinToString(" ")
            return ToolResult.fail("Không tìm thấy bài nào $what trong thư viện.")
        }

        // A title search plays the best match first, followed by the other matches.
        player.playSongs(matches, startIndex = 0)
        val first = matches.first()
        val more = if (matches.size > 1) " (và ${matches.size - 1} bài khác)" else ""
        return ToolResult.ok(
            "Đang phát \"${first.title}\" – ${first.artist}$more.",
            mapOf("songId" to first.id, "matches" to matches.size),
        )
    }

    private fun dropTrailingFillers(text: String): String {
        val words = text.trim().split(Regex("\\s+")).toMutableList()
        while (words.size > 1 && VietnameseText.fold(words.last()).trim('.', '!', '?', ',') in TRAILING_FILLERS) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    private companion object {
        val TRAILING_FILLERS = setOf("di", "nha", "voi", "nao", "ne", "a", "len", "cai", "luon", "thu")
    }
}

/**
 * Summarises the local library (artists, genres) so an LLM can pick music for a mood or
 * activity using names that actually exist, instead of guessing titles.
 */
class LibraryOverviewTool(private val player: PlayerGateway) : AssistantTool {
    override val name = "get_library_overview"
    override val description =
        "Xem tổng quan thư viện nhạc trên máy: số bài, các nghệ sĩ và thể loại có nhiều bài nhất, " +
            "kèm vài tên bài mẫu. Dùng trước khi chọn nhạc theo tâm trạng/hoạt động."
    override val parameters = listOf(
        ToolParameter("limit", ToolParameter.Type.INTEGER, "Số nghệ sĩ/thể loại tối đa trả về (mặc định 25)"),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val limit = (args.intArg("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val songs = player.librarySongs()
        if (songs.isEmpty()) return ToolResult.fail("Thư viện nhạc đang trống.")

        fun top(key: (LibrarySong) -> String?) = songs
            .mapNotNull { key(it)?.trim()?.takeIf { s -> s.isNotEmpty() && !s.startsWith("<unknown", true) } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { "${it.key} (${it.value})" }

        val artists = top { it.artist }
        val genres = top { it.genre }
        val samples = songs.shuffled(kotlin.random.Random(songs.size)).take(SAMPLE_TITLES).map { "${it.title} – ${it.artist}" }
        return ToolResult.ok(
            "Thư viện có ${songs.size} bài, ${artists.size} nghệ sĩ hàng đầu, ${genres.size} thể loại.",
            mapOf(
                "songCount" to songs.size,
                "topArtists" to artists,
                "topGenres" to genres,
                "sampleTitles" to samples,
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val SAMPLE_TITLES = 30
    }
}

/** Reports what is playing ("bài gì đây?"). */
class NowPlayingTool(private val player: PlayerGateway) : AssistantTool {
    override val name = "get_now_playing"
    override val description = "Cho biết bài hát đang phát."
    override val parameters = emptyList<ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val now = player.nowPlaying() ?: return ToolResult.ok("Hiện không có bài nào đang phát.")
        val state = if (now.isPlaying) "Đang phát" else "Đang tạm dừng"
        return ToolResult.ok("$state \"${now.title}\" – ${now.artist}.", mapOf("title" to now.title, "artist" to now.artist))
    }
}
