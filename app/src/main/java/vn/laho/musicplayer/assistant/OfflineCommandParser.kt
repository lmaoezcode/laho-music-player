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

        // "tìm X trên drive", "tải bài X từ drive", "tải số 2", "đồng ý tải".
        parseDrive(body, command)?.let { return it }

        // "nhạc cho lúc này", "gợi ý nhạc", "nhạc buổi sáng", "daily mix" → suggestion tools.
        SuggestPhrases.parse(foldedBody)?.let { return it }

        // "bật nhạc buồn", "phát vài bài nhẹ nhàng để học", "nhạc tập gym" → mood index.
        parseMoodPlay(foldedBody)?.let { return it }

        // "phát bài …": keep the rest verbatim (song titles may end in "đi", "nhà"…).
        return parsePlayQuery(body, foldedBody)
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
