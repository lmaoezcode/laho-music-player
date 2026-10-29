// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import vn.laho.musicplayer.voice.FailureReason
import vn.laho.musicplayer.voice.ModelState
import vn.laho.musicplayer.voice.VoskModelManager
import vn.laho.musicplayer.voice.VoskModelSpec

/**
 * Offers the offline speech model: an explicit download button with a size warning, progress,
 * errors with retry, and a delete action once installed. Shows nothing when there is nothing to do.
 */
@Composable
fun VoiceModelCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { VoskModelManager.get(context) }
    val state by manager.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val sizeMb = VoskModelSpec.VI_SMALL.sizeMegabytes

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.laho_voice_download_title)) },
            text = { Text(stringResource(R.string.laho_voice_download_warning, sizeMb)) },
            confirmButton = {
                Button(onClick = { confirm = false; manager.download() }) {
                    Text(stringResource(R.string.laho_voice_download_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.laho_voice_cancel)) }
            },
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val s = state) {
                ModelState.NotInstalled -> {
                    Text(stringResource(R.string.laho_voice_model_missing), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirm = true }) {
                        Text(stringResource(R.string.laho_voice_download_button, sizeMb))
                    }
                }
                is ModelState.Downloading -> {
                    Text(
                        stringResource(
                            R.string.laho_voice_downloading,
                            (s.downloadedBytes / 1_048_576).toInt(),
                            (maxOf(s.totalBytes, 1L) / 1_048_576).toInt(),
                        ),
                    )
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { manager.cancel() }) { Text(stringResource(R.string.laho_voice_cancel)) }
                }
                ModelState.Verifying -> Text(stringResource(R.string.laho_voice_verifying))
                ModelState.Unpacking -> Text(stringResource(R.string.laho_voice_unpacking))
                ModelState.Installed -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.laho_voice_model_ready), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { manager.delete() }) { Text(stringResource(R.string.laho_voice_delete)) }
                }
                is ModelState.Failed -> {
                    val reason = when (s.reason) {
                        FailureReason.NETWORK -> R.string.laho_voice_error_network
                        FailureReason.CHECKSUM -> R.string.laho_voice_error_checksum
                        FailureReason.SIZE -> R.string.laho_voice_error_checksum
                        FailureReason.STORAGE -> R.string.laho_voice_error_storage
                        FailureReason.UNPACK -> R.string.laho_voice_error_unpack
                        FailureReason.CANCELLED -> R.string.laho_voice_error_network
                    }
                    Text(stringResource(reason), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { manager.download() }) { Text(stringResource(R.string.laho_voice_retry)) }
                }
            }
        }
    }
}
