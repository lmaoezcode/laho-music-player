// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.backup

import android.content.Context
import okhttp3.OkHttpClient
import vn.laho.musicplayer.drive.DriveAppDataClient
import vn.laho.musicplayer.drive.DriveAuth
import vn.laho.musicplayer.drive.DriveTokenSource
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqPresetStore
import vn.laho.musicplayer.mood.metadataStore
import vn.laho.musicplayer.mood.AndroidMoodIndexing
import vn.laho.musicplayer.mood.RoomMoodStore

/** Consent state of the backup scope (`drive.appdata`), separate from the read-only Drive login. */
interface BackupAuthGate {
    /** True when a token is available without showing UI. */
    suspend fun isAuthorized(): Boolean

    /** Starts the consent screen (explicit user action). True when the grant already existed. */
    suspend fun requestConsent(): Boolean
}

/** [DriveAuth] limited to `drive.appdata`; never pops the consent screen by itself. */
class DriveBackupAuth(private val auth: DriveAuth) : BackupAuthGate, DriveTokenSource {
    override suspend fun isAuthorized(): Boolean = auth.isAuthorized || auth.authorize(interactive = false) != null
    override suspend fun requestConsent(): Boolean = auth.authorize(interactive = true) != null
    override suspend fun token(): String? = if (auth.isAuthorized) auth.token() else auth.authorize(interactive = false)
    override suspend fun refresh(expired: String): String? {
        // Drop the stale token, then ask Play services again without UI.
        auth.signOut()
        return auth.authorize(interactive = false)
    }
}

class PeqStoreAccess(private val store: PeqPresetStore) : PeqAccess {
    override fun current(): PeqPresetState = store.state.value
    override fun replace(state: PeqPresetState) = store.update { state }
}

object AndroidBackup {
    fun auth(context: Context) = DriveAuth(context, listOf(DriveAuth.SCOPE_DRIVE_APPDATA))

    fun service(context: Context, backupAuth: DriveBackupAuth, appVersion: String): BackupService {
        val app = context.applicationContext
        val http = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return BackupService(
            drive = DriveAppDataClient(http, backupAuth),
            peq = PeqStoreAccess(PeqPresetStore.get(app)),
            moods = RoomMoodStore.get(app),
            suggestions = metadataStore(app),
            library = { AndroidMoodIndexing.librarySongs(app) },
            appVersion = appVersion,
        )
    }
}
