// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * Offline Vietnamese phrases for explain_audio_path, kept apart from [OfflineCommandParser]
 * (one-line hook there). Input is already diacritic-folded.
 */
internal object AudioPathPhrases {

    private val BIT_PERFECT = Regex("\\bbit[ -]?perfect\\b")
    private val CODEC = Regex(
        "\\bcodec\\b|\\b(?:ldac|aptx|sbc)\\b|\\bbluetooth\\b.*\\b(?:chat luong|bitrate|toc do)\\b",
    )
    private val SAMPLE_RATE = Regex(
        "\\b(?:tan so|sample ?rate|do sau bit|bit depth)\\b|\\bdang (?:phat|nghe|chay) o (?:bao nhieu|may|muc nao)\\b",
    )
    private val PATH = Regex("\\b(?:duong tin hieu|signal path|duong am thanh)\\b")

    /** Commands that merely contain these words ("phát bài Bit Perfect") are not questions. */
    private val NOT_QUESTION = Regex("^(?:phat|mo|bat|choi|nghe|play|tim|tai|tao|them|xoa)\\s")

    fun parse(folded: String): ToolCall? {
        // "tôi đang nghe bit-perfect không" starts with a play verb: only reject real commands.
        if (NOT_QUESTION.containsMatchIn(folded) && !QUESTION_WORD.containsMatchIn(folded)) return null
        val aspect = when {
            BIT_PERFECT.containsMatchIn(folded) -> "bit_perfect"
            CODEC.containsMatchIn(folded) -> "codec"
            SAMPLE_RATE.containsMatchIn(folded) -> "sample_rate"
            PATH.containsMatchIn(folded) -> "all"
            else -> return null
        }
        return ToolCall("explain_audio_path", mapOf("aspect" to aspect))
    }

    private val QUESTION_WORD = Regex("\\b(?:khong|chua|gi|nao|may|bao nhieu|the nao|ra sao)\\b|\\?")
}
