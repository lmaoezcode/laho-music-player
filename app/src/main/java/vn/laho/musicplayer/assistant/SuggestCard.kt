// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.laho.musicplayer.mood.MoodIndex
import vn.laho.musicplayer.suggest.ListeningHistory
import vn.laho.musicplayer.suggest.SuggestContext
import vn.laho.musicplayer.suggest.SuggestionEngine

/** What the "Gợi ý cho bạn" card shows: a context label and the top few "title – artist" lines. */
data class SuggestPreview(val label: String, val lines: List<String>)

suspend fun loadSuggestPreview(
    player: PlayerGateway,
    moodIndex: MoodIndex?,
    history: ListeningHistory?,
    count: Int = 3,
    nowMs: Long = System.currentTimeMillis(),
): SuggestPreview {
    val context = SuggestContext.at(nowMs)
    val ranked = SuggestionEngine().rank(
        suggestCandidates(player, moodIndex), history?.events().orEmpty(), context, nowMs, count,
    )
    return SuggestPreview(context.label, ranked.map { "${it.song.title} – ${it.song.artist}" })
}

/** Card on the assistant screen: top suggestions for this moment, with play and daily-mix buttons. */
@Composable
fun SuggestCard(preview: SuggestPreview?, enabled: Boolean, onPlayNow: () -> Unit, onDailyMix: () -> Unit, modifier: Modifier = Modifier) {
    if (preview == null || preview.lines.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gợi ý cho bạn · ${preview.label}", style = MaterialTheme.typography.titleSmall)
            for (line in preview.lines) {
                Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlayNow, enabled = enabled) { Text("Phát ngay") }
                OutlinedButton(onClick = onDailyMix, enabled = enabled) { Text("Daily mix") }
            }
        }
    }
}
