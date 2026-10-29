// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.privacy

/**
 * Builds the library summary placed in Gemini's system instruction. Data minimisation:
 * only the song count, a few top genres/artists, a handful of sample titles (each truncated)
 * and the mood-index count; never file paths, URIs, albums or the full track list.
 */
object LibrarySummary {
    const val MAX_ARTISTS = 10
    const val MAX_GENRES = 10
    const val MAX_SAMPLE_TITLES = 5
    const val MAX_NAME_CHARS = 60

    fun format(data: Map<String, Any?>): String = buildString {
        append("Thư viện nhạc trên máy: ${data["songCount"]} bài.")
        names(data["topGenres"], MAX_GENRES)?.let { append("\nThể loại: ${it.joinToString(", ")}.") }
            ?: append("\nThể loại: (không có thông tin thể loại).")
        names(data["topArtists"], MAX_ARTISTS)?.let { append("\nNghệ sĩ: ${it.joinToString(", ")}.") }
        names(data["sampleTitles"], MAX_SAMPLE_TITLES)?.let { append("\nVài bài mẫu: ${it.joinToString("; ")}.") }
        (data["moodIndexed"] as? Number)?.toInt()?.let { n ->
            append(
                if (n > 0) "\nChỉ mục tâm trạng: đã phân tích $n/${data["songCount"]} bài (dùng play_by_mood)."
                else "\nChỉ mục tâm trạng: chưa có (play_by_mood chưa dùng được).",
            )
        }
    }

    private fun names(raw: Any?, max: Int): List<String>? =
        (raw as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(max)?.map { if (it.length > MAX_NAME_CHARS) it.take(MAX_NAME_CHARS - 1) + "…" else it }
            ?.takeIf { it.isNotEmpty() }
}
