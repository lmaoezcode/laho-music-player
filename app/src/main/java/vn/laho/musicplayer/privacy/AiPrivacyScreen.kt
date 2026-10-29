// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.privacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.ui.theme.RhythmTheme

/** What each feature sends, and where (also summarised in FORK_NOTICE.md). */
private class DataFlow(@StringRes val feature: Int, @StringRes val sent: Int, @StringRes val to: Int)

private val FLOWS = listOf(
    DataFlow(R.string.laho_ai_privacy_f_chat, R.string.laho_ai_privacy_f_chat_sent, R.string.laho_ai_privacy_f_chat_to),
    DataFlow(R.string.laho_ai_privacy_f_mood, R.string.laho_ai_privacy_f_mood_sent, R.string.laho_ai_privacy_f_mood_to),
    DataFlow(R.string.laho_ai_privacy_f_meta, R.string.laho_ai_privacy_f_meta_sent, R.string.laho_ai_privacy_f_meta_to),
    DataFlow(R.string.laho_ai_privacy_f_drive, R.string.laho_ai_privacy_f_drive_sent, R.string.laho_ai_privacy_f_drive_to),
    DataFlow(R.string.laho_ai_privacy_f_voice, R.string.laho_ai_privacy_f_voice_sent, R.string.laho_ai_privacy_f_voice_to),
)

/** Switch for online AI plus the per-feature data table. */
class AiPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RhythmTheme { AiPrivacyScreen(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiPrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AiPrivacySettings.get(context) }
    val enabled by settings.enabled.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.laho_ai_privacy_title)) },
                navigationIcon = { OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.laho_ai_privacy_switch), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.laho_ai_privacy_switch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = settings::setOnlineEnabled)
            }
            for (flow in FLOWS) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(flow.feature), style = MaterialTheme.typography.titleSmall)
                        Labeled(R.string.laho_ai_privacy_sent, flow.sent)
                        Labeled(R.string.laho_ai_privacy_to, flow.to)
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.laho_ai_privacy_stays), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.laho_ai_privacy_stays_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.laho_ai_privacy_off_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.laho_ai_privacy_off_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Labeled(@StringRes label: Int, @StringRes value: Int) {
    Column {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(value), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Short note shown at the top of the assistant screen while online AI is off. */
@Composable
fun AiOffNote(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.laho_ai_off_note),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpen) { Text(stringResource(R.string.laho_ai_off_details)) }
    }
}
