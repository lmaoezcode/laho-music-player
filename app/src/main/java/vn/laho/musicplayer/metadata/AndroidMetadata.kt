// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import android.content.Context
import chromahub.rhythm.app.BuildConfig
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import vn.laho.musicplayer.mood.metadataStore

/**
 * Android wiring for metadata lookup. Uses its own OkHttp client (no Deezer/logging
 * interceptors) so the MusicBrainz User-Agent is exactly what we set.
 *
 * Data from MusicBrainz is CC0 (core) / CC BY-NC-SA (some supplementary data), free for
 * non-commercial use; commercial use needs a MetaBrainz commercial plan.
 */
object AndroidMetadata {

    val userAgent: String get() = MusicBrainzClient.userAgent(BuildConfig.VERSION_NAME)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val lookup: MetadataLookup by lazy { MetadataLookup(MusicBrainzClient(http, userAgent)) }
    val covers: CoverArtFetcher by lazy { CoverArtFetcher(http, userAgent) }

    suspend fun librarySongs(context: Context): List<MetaSong> = withContext(Dispatchers.IO) {
        RhythmDatabase.getInstance(context).songDao().getAllSongs().map {
            MetaSong(it.id, it.title, it.artist, it.album, it.genre, it.year, it.duration)
        }
    }

    fun batch(context: Context): MetadataBatch {
        val app = context.applicationContext
        return MetadataBatch(
            store = metadataStore(app),
            library = { librarySongs(app) },
            lookup = { q -> lookup.candidates(q, max = 1) },
            onError = { e -> android.util.Log.w("LahoMeta", "MusicBrainz lookup failed", e) },
        )
    }
}
