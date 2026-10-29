// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.components.common.HeaderAction
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import vn.laho.musicplayer.assistant.AssistantActivity

/** Opens the assistant and starts listening at once. Also used by the "Hỏi LaHo" app shortcut. */
fun openAssistantWithVoice(context: Context) {
    context.startActivity(
        Intent(context, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_START_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

/** Home screen header button (one call from Rhythm's HomeScreen). */
fun assistantHeaderAction(context: Context) = HeaderAction(
    icon = MaterialSymbolIcon("mic"),
    contentDescription = context.getString(R.string.laho_voice_entry_cd),
    onClick = { openAssistantWithVoice(context) },
)

/** Small floating microphone for the player screen (one call from Rhythm's PlayerScreen). */
@Composable
fun AssistantMicButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = stringResource(R.string.laho_voice_entry_cd)
    SmallFloatingActionButton(
        onClick = { openAssistantWithVoice(context) },
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 56.dp, end = 12.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(icon = MaterialSymbolIcon("mic", filled = true), contentDescription = null)
    }
}
