// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

/**
 * Diacritic-insensitive library search for voice/chat commands.
 *
 * Every query word must appear in the title, artist or album. Matches are ranked so that
 * "phát Lạc Trôi" prefers a song titled exactly "Lạc Trôi" over one that merely mentions it.
 */
object LibrarySearch {

    fun search(
        songs: List<LibrarySong>,
        query: String?,
        artist: String? = null,
        limit: Int = 50,
        genre: String? = null,
    ): List<LibrarySong> {
        val queryTokens = query?.let { VietnameseText.tokens(it) }.orEmpty()
        val artistTokens = artist?.let { VietnameseText.tokens(it) }.orEmpty()
        val genreTokens = genre?.let { VietnameseText.tokens(it) }.orEmpty()
        if (queryTokens.isEmpty() && artistTokens.isEmpty() && genreTokens.isEmpty()) return emptyList()

        val queryPhrase = queryTokens.joinToString(" ")
        return songs.asSequence()
            .mapNotNull { song ->
                val title = VietnameseText.tokens(song.title)
                val artistNames = VietnameseText.tokens(song.artist)
                val album = VietnameseText.tokens(song.album)

                if (artistTokens.isNotEmpty() && !containsAll(artistNames, artistTokens)) return@mapNotNull null
                if (genreTokens.isNotEmpty() &&
                    !containsAll(VietnameseText.tokens(song.genre.orEmpty()), genreTokens)
                ) return@mapNotNull null
                if (queryTokens.isNotEmpty() && !queryTokens.all { it in title || it in artistNames || it in album }) {
                    return@mapNotNull null
                }

                var score = 0
                if (queryTokens.isNotEmpty()) {
                    val titlePhrase = title.joinToString(" ")
                    score += when {
                        titlePhrase == queryPhrase -> 100
                        titlePhrase.startsWith(queryPhrase) -> 70
                        titlePhrase.contains(queryPhrase) -> 50
                        else -> 0
                    }
                    score += queryTokens.count { it in title } * 10
                    score += queryTokens.count { it in artistNames } * 6
                    score += queryTokens.count { it in album } * 3
                } else {
                    score = 1
                }
                song to score
            }
            .sortedWith(compareByDescending<Pair<LibrarySong, Int>> { it.second }.thenBy { it.first.title })
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun containsAll(haystack: List<String>, needles: List<String>) = needles.all { it in haystack }
}
