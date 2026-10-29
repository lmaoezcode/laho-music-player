// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

import java.text.Normalizer

/**
 * Cleans recogniser output before it reaches [vn.laho.musicplayer.assistant.OfflineCommandParser].
 *
 * Vosk's Vietnamese model returns lower-case words without punctuation and spells numbers out
 * ("âm lượng bốn mươi"), while the parser expects digits ("âm lượng 40"). This removes
 * recogniser markers, unifies Unicode and converts spoken numbers up to 999 to digits when the
 * context makes it unambiguous (a song called "Ba" stays a song).
 */
object VoiceTextNormalizer {

    private val MARKERS = Regex("\\[unk]|<unk>|\\[noise]", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    private val DIGITS = mapOf(
        "không" to 0, "một" to 1, "hai" to 2, "ba" to 3, "bốn" to 4, "năm" to 5,
        "sáu" to 6, "bảy" to 7, "tám" to 8, "chín" to 9,
    )
    private val UNIT_FORMS = mapOf("mốt" to 1, "tư" to 4, "lăm" to 5, "nhăm" to 5)
    private val NUMBER_WORDS = DIGITS.keys + UNIT_FORMS.keys + setOf("mười", "mươi", "trăm", "linh", "lẻ")

    /** A single number word counts as a number when the previous word is one of these... */
    private val NUMBER_CONTEXT_BEFORE = setOf("lượng", "số", "thứ", "sau", "khoảng", "mức")

    /** ... or when the next word is one of these units. */
    private val NUMBER_UNIT_AFTER = setOf("phút", "giờ", "giây", "phần", "bài", "lần", "%")

    fun normalize(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
        text = MARKERS.replace(text, " ")
        text = SPACES.replace(text, " ").trim()
        if (text.isEmpty()) return ""
        return spokenNumbersToDigits(text)
    }

    internal fun spokenNumbersToDigits(text: String): String {
        val words = text.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (words[i].lowercase() !in NUMBER_WORDS) {
                out += words[i]
                i++
                continue
            }
            var j = i
            while (j < words.size && words[j].lowercase() in NUMBER_WORDS) j++
            val run = words.subList(i, j).map { it.lowercase() }
            val value = parseNumber(run)
            val before = words.getOrNull(i - 1)?.lowercase()
            val after = words.getOrNull(j)?.lowercase()
            val convert = value != null && (
                run.size >= 2 ||
                    (before != null && before in NUMBER_CONTEXT_BEFORE) ||
                    (after != null && after in NUMBER_UNIT_AFTER)
                )
            if (convert) out += value.toString() else out.addAll(words.subList(i, j))
            i = j
        }
        return out.joinToString(" ")
    }

    /** 0..999 in spoken Vietnamese ("một trăm lẻ năm", "hai mươi mốt", "mười lăm"); null if not a valid number. */
    internal fun parseNumber(t: List<String>): Int? {
        if (t.isEmpty()) return null
        var i = 0
        var value = 0
        var hasHundreds = false
        val first = DIGITS[t[0]]
        if (t.size >= 2 && t[1] == "trăm" && first != null && first in 1..9) {
            value = first * 100
            hasHundreds = true
            i = 2
        }
        val rest = t.subList(i, t.size)
        if (rest.isEmpty()) return if (hasHundreds) value else null

        if (hasHundreds && (rest[0] == "linh" || rest[0] == "lẻ")) {
            if (rest.size != 2) return null
            val u = DIGITS[rest[1]] ?: return null
            return if (u in 1..9) value + u else null
        }
        if (rest[0] == "mười") {
            return when (rest.size) {
                1 -> value + 10
                2 -> {
                    val u = (DIGITS[rest[1]] ?: UNIT_FORMS[rest[1]]) ?: return null
                    if (rest[1] == "mốt" || rest[1] == "tư" || u == 0) null else value + 10 + u
                }
                else -> null
            }
        }
        if (rest.size >= 2 && rest[1] == "mươi") {
            val tens = DIGITS[rest[0]] ?: return null
            if (tens !in 2..9) return null
            return when (rest.size) {
                2 -> value + tens * 10
                3 -> {
                    val u = (DIGITS[rest[2]] ?: UNIT_FORMS[rest[2]]) ?: return null
                    if (u == 0) null else value + tens * 10 + u
                }
                else -> null
            }
        }
        if (rest.size == 1) {
            val d = DIGITS[rest[0]] ?: return null
            // "một trăm năm" = 105 in everyday speech.
            return value + d
        }
        return null
    }
}
