// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * Offline Vietnamese phrases for the suggestion tools, kept apart from [OfflineCommandParser]
 * so that file only needs a one-line hook. Input is already diacritic-folded.
 */
internal object SuggestPhrases {

    private val COUNT = Regex("(\\d{1,3})\\s*bai")

    private val DAILY = Regex(
        "\\b(?:daily mix|dailymix|mix hom nay|mix cho hom nay|playlist hom nay|danh sach hom nay|nhac hom nay|nhac cho hom nay)\\b",
    )

    /** "nhạc cho lúc này", "gợi ý nhạc", "nghe gì bây giờ"... */
    private val NOW = Regex(
        "\\bluc nay\\b|^goi y(?: (?:nhac|bai hat|bai))?\\b|\\bnghe gi (?:bay gio|bay h|nhi|day)\\b|" +
            "\\bnhac (?:hop )?(?:voi )?(?:bay gio|bay h)\\b|\\bnhac cho (?:bay gio|bay h)\\b",
    )

    /** "nhạc buổi sáng", "nhạc tối khuya", "nhạc đêm khuya". */
    private val DAYPART = Regex(
        "^(?:(?:bat|phat|mo|choi|nghe|play)\\s+)?(?:(?:mot )?(?:vai |may )?(?:bai hat|bai|nhac|playlist)\\s+)" +
            "(?:cho\\s+)?(buoi (?:sang|trua|chieu|toi)|sang nay|chieu nay|trua nay|toi nay|toi khuya|dem khuya|khuya|ban dem|nua dem|dem nay)\\b",
    )

    fun parse(folded: String): ToolCall? {
        val count = COUNT.find(folded)?.groupValues?.get(1)?.toIntOrNull()
        fun args(vararg extra: Pair<String, Any?>) = buildMap<String, Any?> {
            if (count != null) put("count", count)
            extra.forEach { put(it.first, it.second) }
        }
        if (DAILY.containsMatchIn(folded)) return ToolCall("daily_mix", args())
        DAYPART.find(folded)?.let { return ToolCall("suggest_for_now", args("context" to it.groupValues[1])) }
        if (NOW.containsMatchIn(folded)) return ToolCall("suggest_for_now", args())
        return null
    }
}
