// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * Rule-based Vietnamese/English command parser that works without network.
 *
 * It covers the basic, fixed-shape commands (transport, volume, "phát bài …"). Anything
 * it does not recognise returns null so the caller can fall back to the online LLM.
 * Matching runs on diacritic-folded text ("tam dung" == "tạm dừng"), but extracted song
 * and artist names are cut from the original text so they keep their diacritics.
 */
class OfflineCommandParser {

    /**
     * Parses one command or a simple chain ("tạo playlist nhạc buồn 20 bài rồi phát ngẫu
     * nhiên"). A chain is split at "rồi" / "sau đó" / "xong" / commas and is only accepted
     * when every part is an offline command; later parts may refer to the previous one
     * ("tải về", "phát"). Otherwise the whole sentence is parsed as one command, or null.
     */
    fun parsePlan(input: String): List<ToolCall>? {
        val parts = splitChain(input)
        if (parts.size >= 2) {
            val calls = mutableListOf<ToolCall>()
            for (part in parts) {
                val call = parseStep(part, calls.lastOrNull()) ?: break
                calls += call
            }
            if (calls.size == parts.size) return calls
        }
        return parse(input)?.let { listOf(it) }
    }

    /** One part of a chain, given the step before it. */
    private fun parseStep(part: String, previous: ToolCall?): ToolCall? {
        val c = TRAILING_FILLERS.replace(VietnameseText.fold(part.trim()), "").trim()
        when (previous?.name) {
            "drive_search" -> if (DOWNLOAD_IT.matches(c)) return ToolCall("drive_download", mapOf("file_id" to "1"))
            "drive_download" -> if (PLAY_IT.matches(c)) return ToolCall("play_downloaded")
            "create_playlist" -> if (PLAY_IT.matches(c)) {
                return ToolCall("play_playlist", mapOf("shuffle" to SHUFFLE.containsMatchIn(c)))
            }
        }
        return parse(part)
    }

    /** Splits at chain connectors, after removing leading wake words ("hey LaHo, …"). */
    internal fun splitChain(input: String): List<String> {
        val original = VietnameseText.nfc(input).trim()
        val folded = VietnameseText.fold(original)
        val start = LEADING_FILLERS.find(folded)?.range?.last?.plus(1) ?: 0
        val body = original.substring(start)
        val foldedBody = folded.substring(start)
        val parts = mutableListOf<String>()
        var from = 0
        for (m in CHAIN_SEPARATOR.findAll(foldedBody)) {
            parts += body.substring(from, m.range.first)
            from = m.range.last + 1
        }
        parts += body.substring(from)
        return parts.map { it.trim().trim(',', '.') }.filter { it.isNotEmpty() }
    }

    fun parse(input: String): ToolCall? {
        val original = VietnameseText.nfc(input).trim()
        if (original.isEmpty()) return null
        val folded = VietnameseText.fold(original)

        // Leading politeness/wake words are never part of a command.
        val start = LEADING_FILLERS.find(folded)?.range?.last?.plus(1) ?: 0
        val body = original.substring(start).trim()
        val foldedBody = VietnameseText.fold(body)

        // Short fixed commands: trailing fillers ("đi", "nhé"…) can be dropped too.
        val command = TRAILING_FILLERS.replace(foldedBody, "").trim()
        parseControl(command)?.let { return it }

        // "hẹn giờ tắt nhạc sau 30 phút", "xoá hàng đợi", "thêm 5 bài của Đen vào hàng đợi",
        // "tạo playlist nhạc buồn 20 bài".
        parseAgentCommand(body.substring(0, command.length), command)?.let { return it }

        // "tìm X trên drive", "tải bài X từ drive", "tải số 2", "đồng ý tải".
        parseDrive(body, command)?.let { return it }

        // "bật nhạc buồn", "phát vài bài nhẹ nhàng để học", "nhạc tập gym" → mood index.
        parseMoodPlay(foldedBody)?.let { return it }

        // "phát playlist Chill ngẫu nhiên".
        parsePlaylistPlay(body.substring(0, command.length), command)?.let { return it }

        // "phát bài …": keep the rest verbatim (song titles may end in "đi", "nhà"…).
        return parsePlayQuery(body, foldedBody)
    }

    /** [core] is the original text aligned with the folded [c] (same length). */
    private fun parseAgentCommand(core: String, c: String): ToolCall? {
        if (SLEEP_CANCEL.matches(c)) return ToolCall("sleep_timer", mapOf("minutes" to 0))
        (SLEEP_TIMER.matchEntire(c) ?: SLEEP_TIMER_REVERSED.matchEntire(c))?.let { m ->
            val n = m.groupValues[1].toInt()
            val minutes = if (m.groupValues[2] in HOUR_UNITS) n * 60 else n
            return ToolCall("sleep_timer", mapOf("minutes" to minutes))
        }
        if (QUEUE_CLEAR.matches(c)) return ToolCall("queue_clear")
        QUEUE_ADD.matchEntire(c)?.let { m ->
            val range = m.groups[2]?.range ?: return null
            val selector = selectorArgs(core.substring(range.first, range.last + 1)) ?: return null
            val count = m.groups[1]?.value?.toIntOrNull()
            return ToolCall("queue_add", selector + listOfNotNull(count?.let { "count" to it }))
        }
        CREATE_PLAYLIST.matchEntire(c)?.let { m ->
            val range = m.groups[1]?.range ?: return null
            var rest = core.substring(range.first, range.last + 1)
            var foldedRest = c.substring(range.first, range.last + 1)
            val args = mutableMapOf<String, Any?>()
            // Name first ("tên Chill gồm 15 bài …"), then the count, then the selector.
            PLAYLIST_NAME.find(foldedRest)?.let { nm ->
                val g = nm.groups[1]!!.range
                args["name"] = rest.substring(g.first, g.last + 1).trim().trim('"', '“', '”')
                rest = rest.removeRange(nm.range)
                foldedRest = foldedRest.removeRange(nm.range)
            }
            PLAYLIST_COUNT.find(foldedRest)?.let { cm ->
                args["limit"] = cm.groupValues[1].toInt()
                rest = rest.removeRange(cm.range)
            }
            val selector = selectorArgs(rest) ?: return null
            return ToolCall("create_playlist", args + selector)
        }
        return null
    }

    /** "của Đen" → artist, "nhạc buồn" → mood, "Lạc Trôi của Sơn Tùng" → query + artist, else query. */
    private fun selectorArgs(text: String): Map<String, Any?>? {
        val desc = text.trim().trim(',', '.', '"', '“', '”')
        if (desc.isEmpty()) return null
        val folded = VietnameseText.fold(desc)
        val moodWords = MOOD_PREFIX.replace(folded, "").trim()
        vn.laho.musicplayer.mood.Mood.values().firstOrNull { moodWords in it.phrases }?.let { return mapOf("mood" to it.key) }
        ARTIST_SPLIT.find(folded)?.let { m ->
            val artist = desc.substring(m.range.last + 1).trim()
            val title = desc.substring(0, m.range.first).trim()
            if (artist.isEmpty()) return null
            val foldedTitle = VietnameseText.fold(title)
            return if (title.isEmpty() || foldedTitle in GENERIC_WORDS || foldedTitle in GENERIC_SONG_WORDS) mapOf("artist" to artist)
            else mapOf("artist" to artist, "query" to title)
        }
        if (folded in GENERIC_WORDS || folded in GENERIC_SONG_WORDS) return null
        return mapOf("query" to desc)
    }

    private fun parsePlaylistPlay(core: String, c: String): ToolCall? {
        val m = PLAY_PLAYLIST.matchEntire(c) ?: return null
        val range = m.groups[2]?.range ?: return null
        val name = core.substring(range.first, range.last + 1).trim().trim('"', '“', '”')
        if (name.isEmpty()) return null
        val shuffle = m.groups[1] != null || m.groups[3] != null
        return ToolCall("play_playlist", mapOf("name" to name, "shuffle" to shuffle))
    }

    private fun parseControl(c: String): ToolCall? {
        VOLUME_SET.matchEntire(c)?.let {
            return ToolCall("set_volume", mapOf("level" to it.groupValues[1].toInt()))
        }
        if (MUTE.matches(c)) return ToolCall("set_volume", mapOf("level" to 0))
        VOLUME_UP.matchEntire(c)?.let {
            val amount = it.groupValues[1].toIntOrNull() ?: DEFAULT_VOLUME_STEP
            return ToolCall("set_volume", mapOf("delta" to amount))
        }
        VOLUME_DOWN.matchEntire(c)?.let {
            val amount = it.groupValues[1].toIntOrNull() ?: DEFAULT_VOLUME_STEP
            return ToolCall("set_volume", mapOf("delta" to -amount))
        }
        if (NEXT.matches(c)) return control("next")
        if (PREVIOUS.matches(c)) return control("previous")
        if (PAUSE.matches(c)) return control("pause")
        if (RESUME.matches(c)) return control("play")
        // "bài gì đây?" — but not "phát bài gì đó vui vui" (a request for the LLM).
        if (NOW_PLAYING.containsMatchIn(c) && !PLAY_QUERY.containsMatchIn(c)) return ToolCall("get_now_playing")
        return null
    }

    private fun parseDrive(body: String, command: String): ToolCall? {
        // `command` is the folded text minus trailing fillers; folding keeps length, so cut the original.
        val core = body.substring(0, command.length)
        DRIVE_PICK.matchEntire(command)?.let { return ToolCall("drive_download", mapOf("file_id" to it.groupValues[1])) }
        if (DRIVE_CONFIRM.matches(command)) return ToolCall("drive_download", mapOf("confirmed" to true))
        val m = DRIVE_SEARCH.matchEntire(command) ?: return null
        val range = m.groups[1]?.range ?: return null
        val query = core.substring(range.first, range.last + 1).trim().trim('"', '“', '”')
        // "tải nhạc từ google drive" has no song name: leave it to the LLM.
        if (query.isEmpty() || VietnameseText.fold(query) in GENERIC_WORDS) return null
        return ToolCall("drive_search", mapOf("query" to query))
    }

    private fun parseMoodPlay(folded: String): ToolCall? {
        val m = MOOD_REQUEST.find(folded) ?: return null
        val kind = m.groupValues[1].trim()
        val rest = TRAILING_FILLERS.replace(" " + m.groupValues[2], "").trim()
        val mood = vn.laho.musicplayer.mood.Mood.findIn(rest) ?: return null
        // "phát bài <X>" is normally a title search; only treat it as a mood when X is
        // nothing but a mood word ("phát bài buồn"), so "phát bài Vui Lên Đi" stays a search.
        if (kind in TITLE_KINDS && rest !in mood.phrases) return null
        return ToolCall("play_by_mood", mapOf("mood" to mood.key))
    }

    private fun parsePlayQuery(body: String, foldedBody: String): ToolCall? {
        val m = PLAY_QUERY.find(foldedBody) ?: return null
        val restRange = m.groups[2]?.range ?: return null
        var rest = body.substring(restRange.first, restRange.last + 1).trim().trimEnd('.', '!', '?')
        // Drop politeness words that never end a song title ("nhé", "giúp tôi"…). Ambiguous
        // ones such as "đi" / "nha" (cf. "Em Đi", "Về Nhà") are left to the search fallback.
        SAFE_TRAILING.find(VietnameseText.fold(rest))?.let { rest = rest.substring(0, it.range.first).trim() }
        val foldedRest = VietnameseText.fold(rest)
        if (rest.isEmpty() || TRAILING_FILLERS.replace(" $foldedRest", "").isBlank()) return control("play")

        // "… của <nghệ sĩ>" / "… by <artist>"
        val artistMatch = ARTIST_SPLIT.find(foldedRest)
        if (artistMatch != null) {
            val artist = rest.substring(artistMatch.range.last + 1).trim()
            val title = rest.substring(0, artistMatch.range.first).trim()
            if (artist.isNotEmpty()) {
                val args = buildMap {
                    put("artist", artist)
                    if (title.isNotEmpty() && VietnameseText.fold(title) !in GENERIC_WORDS) put("query", title)
                }
                return ToolCall("search_and_play", args)
            }
        }
        return ToolCall("search_and_play", mapOf("query" to rest))
    }

    private fun control(action: String) = ToolCall("playback_control", mapOf("action" to action))

    companion object {
        const val DEFAULT_VOLUME_STEP = 10

        /** A sentence that chains several actions; used to keep the Gemini loop going. */
        fun looksMultiStep(text: String): Boolean = MULTI_STEP_HINT.containsMatchIn(VietnameseText.fold(text))

        private val MULTI_STEP_HINT = Regex(",\\s*\\S|\\s(?:roi|sau do|xong|then|tiep theo)\\s+\\S")
        private val CHAIN_SEPARATOR = Regex(
            "\\s*,\\s*(?:(?:roi|sau do|xong roi|xong|then)\\s+)?|\\s+(?:roi|sau do|xong roi|xong|then|and then|tiep theo|va sau do)\\s+",
        )
        private val DOWNLOAD_IT = Regex(
            "(?:tai|download)(?: ve| xuong)?(?: (?:bai|file|tep) (?:do|nay|dau tien|dau))?(?: (?:no|luon|ngay|may))?",
        )
        private val PLAY_IT = Regex(
            "(?:phat|nghe|choi|bat|mo|play)(?: (?:no|bai do|bai nay|playlist do|playlist nay|luon|ngay|len|thu|ngau nhien|tron bai|tron|shuffle|di))*",
        )
        private val SHUFFLE = Regex("ngau nhien|tron|shuffle")

        private val HOUR_UNITS = setOf("gio", "tieng", "h")
        private const val TIME_UNIT = "\\s*(phut|p|gio|tieng|h)(?:\\s+nua)?"
        private val SLEEP_TIMER = Regex(
            "(?:hen gio(?: tat(?: nhac)?| ngu)?|dat (?:hen )?gio(?: tat(?: nhac)?)?|sleep timer|(?:tat|dung|ngung) nhac)\\s+" +
                "(?:(?:sau|trong|la|thanh|khoang)\\s+)?(\\d{1,3})$TIME_UNIT(?:\\s+(?:thi\\s+)?(?:tat|dung|ngung)(?: nhac)?)?",
        )
        private val SLEEP_TIMER_REVERSED = Regex("(\\d{1,3})$TIME_UNIT\\s+(?:sau\\s+|nua\\s+)?(?:thi\\s+)?(?:tat|dung|ngung)(?: nhac)?")
        private val SLEEP_CANCEL = Regex("(?:huy|tat|bo|xoa)(?: bo)? (?:hen gio(?: tat nhac| ngu)?|sleep timer)")
        private const val QUEUE = "(?:hang doi|danh sach cho|queue|danh sach phat hien tai)"
        private val QUEUE_CLEAR = Regex("(?:xoa|don|lam trong|clear)(?: het| sach)?(?: (?:toan bo|tat ca|het|ca))? $QUEUE")
        private val QUEUE_ADD = Regex(
            "(?:them|cho|bo|add|xep|dua)\\s+(?:(\\d{1,2})\\s+)?(?:(?:bai hat|bai|ca khuc)\\s+)?(.+?)\\s+(?:vao|toi|den|into|to)\\s+(?:cuoi\\s+)?$QUEUE",
        )
        private val CREATE_PLAYLIST = Regex("(?:tao|lam|lap)\\s+(?:(?:mot|1)\\s+)?(?:playlist|danh sach phat|list nhac)(?:\\s+moi)?\\s+(.+)")
        private val PLAYLIST_COUNT = Regex("(?:\\s+(?:gom|voi|co|khoang|tam))?\\s*(\\d{1,3})\\s*(?:bai hat|bai|ca khuc)\\b")
        private val PLAYLIST_NAME = Regex("^\\s*(?:ten(?: la)?|la)\\s+(.+?)\\s+(?=gom|voi|co|tu|theo)(?:gom|voi|co|tu|theo)")
        private val PLAY_PLAYLIST = Regex("(?:phat|mo|bat|nghe|choi|play)\\s+(ngau nhien\\s+|tron\\s+)?playlist\\s+(.+?)(\\s+(?:ngau nhien|tron bai|shuffle))?")
        private val MOOD_PREFIX = Regex("^(?:(?:cac|nhung|vai|may) )?(?:(?:bai hat|bai|nhac|ca khuc) )?(?:de |cho |kieu )?")
        private val GENERIC_SONG_WORDS = setOf("bai", "bai hat", "ca khuc", "nhac", "")

        private val LEADING_FILLERS = Regex(
            "^(?:(?:hey|ok|okay|e|ei|oi|laho|tro ly|nay|lam on|vui long|hay|giup (?:toi|minh|em)|" +
                "(?:toi|minh|em) muon|muon)\\s*[,!.]?\\s+)+",
        )
        private val TRAILING_FILLERS = Regex(
            "(?:\\s+(?:di|nhe|nha|nhe ban|giup (?:toi|minh|em)|dum|ho|voi|ne|nao|duoc khong|a|cai))+\\s*[.!?]*$|\\s*[.!?]+$",
        )

        private const val PERCENT = "\\s*(?:%|phan tram)?"

        private val SAFE_TRAILING = Regex(
            "(?:\\s+(?:nhe|nhe ban|giup (?:toi|minh|em)|duoc khong|dum|ho)\\b)+\\s*$",
        )

        private val VOLUME_SET = Regex(
            "(?:dat |chinh |de |cho |set |tang |giam )?(?:am luong|volume|loa)\\s*(?:len|xuong|o|o muc|muc|thanh|ve|=|:|to)?\\s*(?:muc\\s*)?(\\d{1,3})$PERCENT",
        )
        private val MUTE = Regex("tat tieng|tat am|im lang|mute|cam")
        private const val VOLUME_SUBJECT = "(?:(?:am luong|volume|loa|nhac) )?"
        private val VOLUME_UP = Regex(
            "$VOLUME_SUBJECT(?:tang am luong|tang volume|tang loa|tang len|to len|to hon|to them|lon len|lon hon|vang hon|" +
                "mo to|volume up|louder)(?:\\s+(?:them\\s+)?(\\d{1,3})$PERCENT)?" +
                "(?:\\s+(?:nua|chut|chut nua|mot chut|mot ti|ti|xiu|them))?",
        )
        private val VOLUME_DOWN = Regex(
            "$VOLUME_SUBJECT(?:giam am luong|giam volume|giam loa|giam xuong|nho lai|nho di|nho xuong|nho bot|nho hon|" +
                "be lai|be di|ha am luong|volume down|quieter)(?:\\s+(?:bot\\s+)?(\\d{1,3})$PERCENT)?" +
                "(?:\\s+(?:nua|chut|chut nua|mot chut|mot ti|ti|xiu|bot))?",
        )
        private val NEXT = Regex(
            "(?:bai|ban|track) (?:tiep|sau|ke|tiep theo|ke tiep|ke tiep theo)|chuyen bai(?: tiep| khac)?|" +
                "qua bai(?: khac| moi| tiep)?|doi bai(?: khac)?|bo qua(?: bai nay)?|next(?: song| track)?|skip",
        )
        private val PREVIOUS = Regex(
            "(?:bai|ban) (?:truoc|vua roi|luc nay|truoc do)|quay lai bai(?: truoc)?|lui bai|" +
                "tro lai bai truoc|previous(?: song| track)?|back",
        )
        private val PAUSE = Regex(
            "tam dung|tam dung nhac|dung|dung lai|dung nhac|ngung|ngung lai|ngung nhac|tat nhac|pause|stop",
        )
        private val RESUME = Regex(
            "phat|phat tiep|phat nhac|phat nhac len|tiep tuc|tiep tuc phat|choi|choi tiep|bat nhac|" +
                "bat nhac len|mo nhac|mo nhac len|nghe tiep|bat lai|phat lai|play|resume",
        )
        private val NOW_PLAYING = Regex(
            "\\bbai (?:gi|nao)\\b|bai nay la (?:bai )?gi|ten bai(?: hat)?(?: nay)?|dang (?:phat|nghe) (?:bai )?gi|" +
                "what(?:'s| is) playing",
        )

        /** Verb, optional "cho tôi (nghe)", optional kind word, then the rest (group 2). */
        private val PLAY_QUERY = Regex(
            "^(?:phat|mo|bat|choi|nghe|play|cho (?:toi|minh|em) nghe)\\s+" +
                "(?:cho (?:toi|minh|em)\\s+)?(?:nghe\\s+)?" +
                "(?:(bai hat|bai|ca khuc|nhac|song|album)\\s+)?(.+)$",
        )
        /** Optional verb, a music noun (group 1), then the description (group 2). */
        private val MOOD_REQUEST = Regex(
            "^(?:(?:bat|phat|mo|choi|nghe|play|cho (?:toi|minh|em) nghe)\\s+(?:cho (?:toi|minh|em)\\s+)?(?:nghe\\s+)?)?" +
                "((?:mot )?(?:vai |may |it |nhung )?(?:bai hat|bai|nhac|ca khuc|playlist)(?: gi do| nao do)?)\\s+(.+)$",
        )
        private val DRIVE_SEARCH = Regex(
            "(?:tim(?: kiem)?|search|find|tai(?: xuong)?|download)\\s+(?:(?:bai hat|bai|ca khuc|nhac|file|tep)\\s+)?" +
                "(.+?)\\s+(?:tu|tren|trong|o|from|on|in)\\s+(?:google\\s+)?drive",
        )
        private val DRIVE_PICK = Regex("(?:tai|download)\\s+(?:bai\\s+)?(?:so\\s+)?(\\d{1,2})")
        private val DRIVE_CONFIRM = Regex("tai|tai luon|tai ngay|(?:dong y|xac nhan|ok|yes) tai|(?:dong y|xac nhan) tai ve")
        private val TITLE_KINDS = setOf("bai", "bai hat", "ca khuc")
        private val ARTIST_SPLIT = Regex("(?:^|\\s)(?:cua|by)\\s+")
        private val GENERIC_WORDS = setOf("nhac", "bai", "bai hat", "ca khuc", "may bai", "vai bai", "cac bai")
    }
}
