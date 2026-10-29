// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo

/**
 * "Bluetooth link" card of the playback quality sheet: codec, bitrate, and the warning that
 * Bluetooth is not bit-perfect. Asks for BLUETOOTH_CONNECT only here (Android 12+), with the
 * reason, and everything still works when the user declines.
 */
@Composable
fun BluetoothAudioPathCard(quality: PlaybackQualityInfo, modifier: Modifier = Modifier) {
    if (!quality.isBluetooth) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AudioPathStore.hasBluetoothPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) AudioPathStore.refreshBluetooth?.invoke()
    }
    val path = quality.bluetoothAudioPath(granted) ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.laho_bt_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Line(
                stringResource(R.string.laho_bt_codec),
                if (path.isCodecKnown) path.codecDisplayName else stringResource(R.string.laho_bt_codec_unknown),
            )
            path.bitrateKbps?.let {
                Line(
                    stringResource(R.string.laho_bt_bitrate),
                    stringResource(if (path.bitrateApproximate) R.string.laho_bt_bitrate_typical else R.string.laho_bt_bitrate_value, it),
                )
            }
            path.sampleRateHz?.let { hz ->
                val rate = PlaybackQualityInfo.formatSampleRate(hz)
                Line(
                    stringResource(R.string.laho_bt_format),
                    path.bitsPerSample?.let { stringResource(R.string.laho_bt_format_value, rate, it) } ?: rate,
                )
            }
            Text(
                text = when (path.warning) {
                    BluetoothWarning.LOSSY -> stringResource(R.string.laho_bt_warning_lossy)
                    BluetoothWarning.LOSSY_HIGH_BITRATE ->
                        stringResource(R.string.laho_bt_warning_high, path.codecDisplayName)
                    BluetoothWarning.UNKNOWN_CODEC -> stringResource(R.string.laho_bt_warning_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (path.needsPermissionPrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text(
                    stringResource(R.string.laho_bt_permission_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text(stringResource(R.string.laho_bt_permission_button))
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
