// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import java.text.Normalizer

/** Text helpers for matching Vietnamese with or without diacritics. */
object VietnameseText {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Lower-cases and strips diacritics ("Tạm dừng" → "tam dung", "Đen Vâu" → "den vau").
     *
     * The result has exactly the same length as the NFC form of [text], character for
     * character, so match positions found in the folded string can be used to cut the
     * original text (keeping the user's diacritics in song/artist names).
     */
    fun fold(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) sb.append(foldChar(ch))
        return sb.toString()
    }

    /** NFC form of [text]; use together with [fold] to keep indices aligned. */
    fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun foldChar(ch: Char): Char {
        when (ch) {
            'đ', 'Đ' -> return 'd'
        }
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS.replace(decomposed, "")
        return if (stripped.length == 1) stripped[0].lowercaseChar() else ch.lowercaseChar()
    }

    /** Folded text split into words (letters/digits only). */
    fun tokens(text: String): List<String> =
        fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
