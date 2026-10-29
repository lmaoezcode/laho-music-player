// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo

/** The latest output path plus whether we were allowed to read the Bluetooth codec. */
data class AudioPathSnapshot(
    val quality: PlaybackQualityInfo,
    val bluetoothPermissionGranted: Boolean,
)

/** Where the assistant (and tests, with a fake) reads the current output path from. */
fun interface AudioPathSource {
    fun current(): AudioPathSnapshot?
}

/**
 * Process-wide copy of the last [PlaybackQualityInfo] computed by MediaPlaybackService, so the
 * assistant answers from exactly what the signal-path sheet shows.
 */
object AudioPathStore : AudioPathSource {
    @Volatile private var snapshot: AudioPathSnapshot? = null

    /** Set by the service: re-reads the Bluetooth codec (after the user grants the permission). */
    @Volatile var refreshBluetooth: (() -> Unit)? = null

    override fun current(): AudioPathSnapshot? = snapshot

    fun publish(context: Context, quality: PlaybackQualityInfo) {
        snapshot = AudioPathSnapshot(quality, hasBluetoothPermission(context))
    }

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
