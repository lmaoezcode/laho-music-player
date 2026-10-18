// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal client for the MusicBrainz web service v2 (https://musicbrainz.org/doc/MusicBrainz_API).
 *
 * Follows the service rules: a descriptive User-Agent with contact info, at most one request
 * per second for the whole app ([SHARED_LIMITER]), and exponential backoff when the server
 * answers 503 (rate limited / overloaded), honouring Retry-After.
 */
class MusicBrainzClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val limiter: RateLimiter = SHARED_LIMITER,
    private val maxRetries: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun searchRecordings(query: LookupQuery, limit: Int = 10): List<MbRecording> {
        val lucene = buildQuery(query) ?: return emptyList()
        val url = baseUrl.newBuilder()
            .addPathSegment("recording")
            .addQueryParameter("query", lucene)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("fmt", "json")
            .build()
        return MusicBrainzParser.parseRecordingSearch(get(url))
    }

    /** Genre of a release group (curated genres, else tags), or null. */
    suspend fun releaseGroupGenre(releaseGroupMbid: String): String? {
        val url = baseUrl.newBuilder()
            .addPathSegment("release-group")
            .addPathSegment(releaseGroupMbid)
            .addQueryParameter("inc", "genres tags")
            .addQueryParameter("fmt", "json")
            .build()
        return MusicBrainzParser.parseGenre(get(url))
    }

    private suspend fun get(url: HttpUrl): String {
        var attempt = 0
        while (true) {
            limiter.acquire()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
            val (code, body, retryAfter) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    Triple(r.code, r.body.string(), r.header("Retry-After")?.trim()?.toLongOrNull())
                }
            }
            if (code in 200..299) return body
            if ((code == 503 || code == 429) && attempt < maxRetries) {
                val wait = retryAfter?.times(1000) ?: (BACKOFF_BASE_MS shl attempt)
                attempt++
                limiter.penalize(wait)
                sleep(wait)
                continue
            }
            throw MetadataLookupException("MusicBrainz HTTP $code", code)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://musicbrainz.org/ws/2/"
        const val CONTACT = "https://github.com/lmaoezcode/laho-music-player"
        const val BACKOFF_BASE_MS = 1_000L

        /** One request per second, shared by every MusicBrainz call in the app. */
        val SHARED_LIMITER = RateLimiter(1_000)

        /** "LaHoMusicPlayer/1.2-debug ( https://github.com/… )" as MusicBrainz asks. */
        fun userAgent(version: String): String =
            "LaHoMusicPlayer/${version.trim().replace(Regex("\\s+"), "-").ifEmpty { "dev" }} ( $CONTACT )"

        /**
         * Lucene query: title and artist must match (as phrases); the duration window
         * (±[MatchScorer.DURATION_TOLERANCE_MS]) is an optional clause that only boosts.
         * Returns null when there is nothing to search for.
         */
        fun buildQuery(query: LookupQuery): String? {
            val q = query.normalized()
            if (q.title.isBlank()) return null
            val sb = StringBuilder("recording:\"${escape(q.title)}\"")
            q.artist?.let { sb.append(" AND artist:\"${escape(it)}\"") }
            q.durationMs?.let { d ->
                val lo = (d - MatchScorer.DURATION_TOLERANCE_MS).coerceAtLeast(0)
                val hi = d + MatchScorer.DURATION_TOLERANCE_MS
                sb.append(" dur:[$lo TO $hi]")
            }
            return sb.toString()
        }

        /** Escapes Lucene special characters inside a quoted phrase. */
        fun escape(s: String): String = buildString {
            for (ch in s) {
                if (ch in "+-&|!(){}[]^\"~*?:\\/") append('\\')
                append(ch)
            }
        }
    }
}

/** Downloads front covers from the Cover Art Archive. */
class CoverArtFetcher(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = CoverArtArchive.BASE_URL,
) {
    /** Saves the first available cover of [candidate] into [dest]; false when none exists. */
    suspend fun download(candidate: MetadataCandidate, dest: File): Boolean = withContext(Dispatchers.IO) {
        for (url in candidate.coverUrls(baseUrl)) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            try {
                http.newCall(request).execute().use { r ->
                    if (r.isSuccessful) {
                        val bytes = r.body.bytes()
                        if (bytes.isNotEmpty()) {
                            dest.parentFile?.mkdirs()
                            dest.writeBytes(bytes)
                            return@withContext true
                        }
                    }
                }
            } catch (e: IOException) {
                // Try the release-group fallback, then give up.
            }
        }
        false
    }
}

/**
 * Lookup service used by the tag editor and the batch job: search, rank, then fetch the
 * release-group genre for the chosen candidate when the recording has no genre tag.
 */
class MetadataLookup(private val client: MusicBrainzClient) {

    suspend fun candidates(query: LookupQuery, max: Int = 5): List<MetadataCandidate> {
        val q = query.normalized()
        var recordings = client.searchRecordings(q)
        if (recordings.isEmpty() && q.artist != null) {
            // The artist tag may be wrong or spelled differently: retry by title only.
            recordings = client.searchRecordings(q.copy(artist = null))
            if (recordings.isNotEmpty()) {
                // Keep scoring against the local artist so wrong-artist hits stay low.
                return withTopGenre(MatchScorer.rank(q, recordings).take(max))
            }
        }
        return withTopGenre(MatchScorer.rank(q, recordings).take(max))
    }

    private suspend fun withTopGenre(ranked: List<MetadataCandidate>): List<MetadataCandidate> {
        val top = ranked.firstOrNull() ?: return ranked
        return listOf(withGenre(top)) + ranked.drop(1)
    }

    /** Fills [MetadataCandidate.genre] from the release group if it is missing. */
    suspend fun withGenre(candidate: MetadataCandidate): MetadataCandidate {
        if (candidate.genre != null) return candidate
        val rg = candidate.releaseGroupMbid ?: return candidate
        val genre = try {
            client.releaseGroupGenre(rg)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            null // genre is optional; keep the rest of the match
        }
        return candidate.copy(genre = genre)
    }
}
