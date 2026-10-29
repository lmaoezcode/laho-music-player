// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import kotlin.random.Random
import vn.laho.musicplayer.mood.Mood
import vn.laho.musicplayer.mood.MoodIndex

/**
 * Picks library songs from the selector arguments shared by create_playlist and queue_add:
 * `songs` (titles separated by ";" or ","), `mood`, `artist`, `genre` or `query`.
 */
class SongPicker(
    private val player: PlayerGateway,
    private val moodIndex: MoodIndex?,
    private val random: Random = Random.Default,
) {
    sealed interface Pick {
        data class Songs(val songs: List<LibrarySong>, val description: String, val mood: Mood? = null) : Pick
        data class Problem(val message: String) : Pick
    }

    suspend fun pick(args: Map<String, Any?>, limit: Int): Pick {
        val titles = args.stringArg("songs")?.split(';', ',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val mood = Mood.fromKey(args.stringArg("mood")) ?: args.stringArg("mood")?.let(Mood::findIn)
        val artist = args.stringArg("artist")
        val genre = args.stringArg("genre")
        val query = args.stringArg("query")
        if (titles.isEmpty() && mood == null && artist == null && genre == null && query == null) {
            return Pick.Problem("Cần chọn nhạc theo 'songs', 'mood', 'artist', 'genre' hoặc 'query'.")
        }
        val library = player.librarySongs()
        if (library.isEmpty()) return Pick.Problem("Thư viện nhạc đang trống. Hãy quét nhạc trước.")

        if (titles.isNotEmpty()) {
            val found = titles.mapNotNull { LibrarySearch.search(library, it, limit = 1).firstOrNull() }.distinctBy { it.id }
            if (found.isEmpty()) return Pick.Problem("Không tìm thấy bài nào trong danh sách trong thư viện.")
            return Pick.Songs(found.take(limit), "${found.size} bài đã chọn")
        }
        if (mood != null && artist == null && query == null) {
            val profiles = moodIndex?.profiles().orEmpty()
            if (profiles.isEmpty()) {
                return Pick.Problem("Chưa có chỉ mục tâm trạng. Gõ /index trong màn trợ lý để phân tích thư viện.")
            }
            val songs = MoodSelection.select(profiles, library, mood.valence, mood.arousal, mood, limit, random)
            if (songs.isEmpty()) return Pick.Problem("Chỉ mục tâm trạng không khớp thư viện hiện tại; hãy chạy lại /index.")
            return Pick.Songs(songs, "nhạc ${mood.label}", mood)
        }
        val matches = LibrarySearch.search(library, query, artist, limit = 200, genre = genre)
        if (matches.isEmpty()) {
            val what = listOfNotNull(query?.let { "\"$it\"" }, artist?.let { "của $it" }, genre?.let { "thể loại $it" })
            return Pick.Problem("Không tìm thấy bài nào ${what.joinToString(" ")} trong thư viện.")
        }
        // A title query keeps the best matches first; an artist/genre pick varies on each request.
        val songs = if (query != null) matches.take(limit) else matches.shuffled(random).take(limit)
        val description = when {
            artist != null && query == null -> "của $artist"
            genre != null && query == null -> "thể loại $genre"
            else -> "\"${query ?: artist}\""
        }
        return Pick.Songs(songs, description)
    }

    companion object {
        val SELECTOR_PARAMETERS = listOf(
            ToolParameter("songs", ToolParameter.Type.STRING, "Tên các bài cụ thể, ngăn cách bằng dấu ;"),
            ToolParameter(
                "mood", ToolParameter.Type.STRING,
                "Tâm trạng (cần chỉ mục tâm trạng): " + Mood.values().joinToString { it.key },
                enumValues = Mood.values().map { it.key },
            ),
            ToolParameter("artist", ToolParameter.Type.STRING, "Tên nghệ sĩ"),
            ToolParameter("genre", ToolParameter.Type.STRING, "Thể loại có trong thư viện"),
            ToolParameter("query", ToolParameter.Type.STRING, "Tên bài/album/từ khoá"),
        )
    }
}

private fun titles(songs: List<LibrarySong>, max: Int = 3): String {
    val shown = songs.take(max).joinToString(", ") { "\"${it.title}\"" }
    return if (songs.size > max) "$shown…" else shown
}

/** Creates a playlist in Rhythm's playlist storage, filled by mood, artist, query or titles. */
class CreatePlaylistTool(
    private val playlists: PlaylistGateway,
    private val picker: SongPicker,
    private val memory: AgentMemory,
) : AssistantTool {
    override val name = "create_playlist"
    override val description =
        "Tạo playlist mới trong thư viện (hiện ở mục Playlist của app) từ các bài chọn theo tâm trạng, nghệ sĩ, " +
            "thể loại, từ khoá hoặc danh sách tên bài. Không tự phát; muốn phát thì gọi play_playlist sau đó."
    override val parameters = listOf(
        ToolParameter("name", ToolParameter.Type.STRING, "Tên playlist (bỏ trống để tự đặt, ví dụ 'Nhạc buồn')"),
    ) + SongPicker.SELECTOR_PARAMETERS + listOf(
        ToolParameter("limit", ToolParameter.Type.INTEGER, "Số bài tối đa (mặc định $DEFAULT_LIMIT, tối đa $MAX_LIMIT)"),
    )

    override fun progressLabel(args: Map<String, Any?>) =
        "Tạo playlist" + (args.stringArg("name")?.let { " \"$it\"" } ?: "")

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val limit = (args.intArg("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val pick = picker.pick(args, limit)
        if (pick is SongPicker.Pick.Problem) return ToolResult.fail(pick.message)
        pick as SongPicker.Pick.Songs

        val base = args.stringArg("name")?.take(MAX_NAME)
            ?: pick.mood?.let { "Nhạc ${it.label}" }
            ?: "Nhạc ${pick.description.trim('"')}".take(MAX_NAME)
        val taken = playlists.playlists().map { VietnameseText.fold(it.name) }.toSet()
        var name = base
        var n = 2
        while (VietnameseText.fold(name) in taken) name = "$base ${n++}"

        val id = playlists.createPlaylist(name, pick.songs.map { it.id })
        memory.lastPlaylist = PlaylistInfo(id, name, pick.songs.size)
        return ToolResult.ok(
            "Đã tạo playlist \"$name\" với ${pick.songs.size} bài (${titles(pick.songs)}).",
            mapOf("playlist_id" to id, "name" to name, "count" to pick.songs.size),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_NAME = 60
    }
}

/** Plays a saved playlist, by name or the one created just before. */
class PlayPlaylistTool(
    private val playlists: PlaylistGateway,
    private val player: PlayerGateway,
    private val memory: AgentMemory,
    private val random: Random = Random.Default,
) : AssistantTool {
    override val name = "play_playlist"
    override val description =
        "Phát một playlist đã lưu theo tên; bỏ trống 'name' để phát playlist vừa tạo. shuffle=true để phát ngẫu nhiên."
    override val parameters = listOf(
        ToolParameter("name", ToolParameter.Type.STRING, "Tên playlist (bỏ trống = playlist vừa tạo)"),
        ToolParameter("shuffle", ToolParameter.Type.STRING, "true để phát ngẫu nhiên", enumValues = listOf("true", "false")),
    )

    override fun progressLabel(args: Map<String, Any?>) =
        if (args.boolArg("shuffle")) "Phát ngẫu nhiên playlist" else "Phát playlist"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val wanted = args.stringArg("name")
        val target = if (wanted == null) {
            memory.lastPlaylist ?: return ToolResult.fail("Chưa có playlist nào vừa tạo; hãy nói tên playlist.")
        } else {
            val all = playlists.playlists()
            val key = VietnameseText.fold(wanted)
            all.firstOrNull { VietnameseText.fold(it.name) == key }
                ?: all.firstOrNull { key in VietnameseText.fold(it.name) }
                ?: return ToolResult.fail("Không có playlist nào tên \"$wanted\".")
        }
        val byId = player.librarySongs().associateBy { it.id }
        var songs = playlists.songIds(target.id).mapNotNull { byId[it] }
        if (songs.isEmpty()) return ToolResult.fail("Playlist \"${target.name}\" đang trống.")
        val shuffle = args.boolArg("shuffle")
        if (shuffle) songs = songs.shuffled(random)
        player.playSongs(songs, 0)
        val how = if (shuffle) "ngẫu nhiên " else ""
        return ToolResult.ok(
            "Đang phát ${how}playlist \"${target.name}\" (${songs.size} bài), bắt đầu với \"${songs.first().title}\".",
            mapOf("playlist_id" to target.id, "count" to songs.size, "shuffle" to shuffle),
        )
    }
}

/** Appends songs to the current queue without interrupting the song that is playing. */
class QueueAddTool(private val queue: QueueGateway, private val picker: SongPicker) : AssistantTool {
    override val name = "queue_add"
    override val description =
        "Thêm bài vào cuối hàng đợi đang phát (không ngắt bài hiện tại), chọn theo tên bài, nghệ sĩ, thể loại hoặc tâm trạng."
    override val parameters = SongPicker.SELECTOR_PARAMETERS + listOf(
        ToolParameter("count", ToolParameter.Type.INTEGER, "Số bài cần thêm (mặc định 1 khi có tên bài, 5 khi khác)"),
    )

    override fun progressLabel(args: Map<String, Any?>) = "Thêm vào hàng đợi"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val default = if (args.stringArg("query") != null && args.stringArg("artist") == null) 1 else DEFAULT_COUNT
        val count = (args.intArg("count") ?: default).coerceIn(1, MAX_COUNT)
        val pick = picker.pick(args, count)
        if (pick is SongPicker.Pick.Problem) return ToolResult.fail(pick.message)
        pick as SongPicker.Pick.Songs
        queue.addToQueue(pick.songs)
        return ToolResult.ok(
            "Đã thêm ${pick.songs.size} bài ${pick.description} vào hàng đợi: ${titles(pick.songs)}.",
            mapOf("count" to pick.songs.size, "queue_size" to queue.queueSize()),
        )
    }

    private companion object {
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 50
    }
}

/** Empties the queue (keeps the playing song). Destructive: always asks first. */
class QueueClearTool(private val queue: QueueGateway) : AssistantTool {
    override val name = "queue_clear"
    override val description =
        "Xoá các bài trong hàng đợi (giữ bài đang phát). Ứng dụng tự hỏi người dùng xác nhận trước khi xoá."
    override val parameters = emptyList<ToolParameter>()

    override fun progressLabel(args: Map<String, Any?>) = "Xoá hàng đợi"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val size = queue.queueSize()
        if (size <= 1) return ToolResult.ok("Hàng đợi đang trống.", mapOf("removed" to 0))
        if (!args.boolArg("confirmed")) {
            return ToolResult.ok(
                "Xoá ${size - 1} bài khỏi hàng đợi (giữ bài đang phát)?",
                mapOf("requires_confirmation" to true),
            )
        }
        val removed = queue.clearQueue()
        return ToolResult.ok("Đã xoá $removed bài khỏi hàng đợi.", mapOf("removed" to removed))
    }
}

/** Rhythm's sleep timer: fade out and pause after N minutes; 0 cancels. */
class SleepTimerTool(private val timer: SleepTimerGateway) : AssistantTool {
    override val name = "sleep_timer"
    override val description = "Hẹn giờ tắt nhạc: nhạc nhỏ dần rồi tạm dừng sau số phút đã cho. minutes=0 để huỷ hẹn giờ."
    override val parameters = listOf(
        ToolParameter("minutes", ToolParameter.Type.INTEGER, "Số phút (1-$MAX_MINUTES), 0 để huỷ", required = true),
    )

    override fun progressLabel(args: Map<String, Any?>) = "Hẹn giờ tắt nhạc"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val minutes = args.intArg("minutes") ?: return ToolResult.fail("Cần số phút, ví dụ 30.")
        if (minutes <= 0) {
            timer.cancel()
            return ToolResult.ok("Đã huỷ hẹn giờ tắt nhạc.", mapOf("minutes" to 0))
        }
        val m = minutes.coerceAtMost(MAX_MINUTES)
        timer.start(m)
        val text = if (m >= 60 && m % 60 == 0) "${m / 60} giờ" else "$m phút"
        return ToolResult.ok("Nhạc sẽ nhỏ dần và tạm dừng sau $text.", mapOf("minutes" to m))
    }

    private companion object { const val MAX_MINUTES = 720 }
}

/** Plays the song downloaded from Drive in an earlier step (before the library rescan sees it). */
class PlayDownloadedTool(private val queue: QueueGateway, private val memory: AgentMemory) : AssistantTool {
    override val name = "play_downloaded"
    override val description = "Phát bài vừa tải từ Google Drive (sau drive_download)."
    override val parameters = emptyList<ToolParameter>()

    override fun progressLabel(args: Map<String, Any?>) = "Phát bài vừa tải"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val d = memory.lastDownload ?: return ToolResult.fail("Chưa có bài nào vừa tải về.")
        queue.playUri(d.uri, d.title)
        return ToolResult.ok("Đang phát \"${d.title}\".", mapOf("title" to d.title))
    }
}

/**
 * Lets the model announce its plan for a multi-step request before acting, so the chat can
 * show "1/3 …" progress. It has no side effect.
 */
class PlanStepsTool : AssistantTool {
    override val name = NAME
    override val description =
        "Với yêu cầu nhiều bước: gọi một lần ĐẦU TIÊN (cùng lượt với bước 1) để báo kế hoạch. Không có tác dụng phụ."
    override val parameters = listOf(
        ToolParameter("steps", ToolParameter.Type.STRING, "Các bước ngắn gọn, ngăn cách bằng |, ví dụ: Tìm trên Drive|Tải về|Phát", required = true),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val steps = parse(args)
        if (steps.isEmpty()) return ToolResult.fail("Cần 'steps'.")
        return ToolResult.ok("Kế hoạch ${steps.size} bước.", mapOf("steps" to steps))
    }

    companion object {
        const val NAME = "plan_steps"
        fun parse(args: Map<String, Any?>): List<String> =
            args.stringArg("steps")?.split('|', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
}
