// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import java.io.File
import kotlinx.coroutines.launch

private sealed interface LookupState {
    data object Loading : LookupState
    data class Done(val candidates: List<MetadataCandidate>, val ambiguous: Boolean) : LookupState
    data class Failed(val message: String) : LookupState
}

/**
 * "Tìm metadata" dialog of the tag editor: queries MusicBrainz, lists the best candidates with
 * source and confidence, and lets the user pick one. Nothing is written here: [onApply] only
 * pre-fills the editor fields (and a downloaded cover in the app cache); the file is changed
 * only when the user presses the editor's Save.
 */
@Composable
fun MetadataLookupDialog(
    query: LookupQuery,
    cacheKey: String,
    onDismiss: () -> Unit,
    onApply: (candidate: MetadataCandidate, cover: Uri?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LookupState>(LookupState.Loading) }
    var selected by remember { mutableStateOf(0) }
    var useCover by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(query, attempt) {
        state = LookupState.Loading
        state = try {
            val found = AndroidMetadata.lookup.candidates(query, max = 5)
            selected = 0
            LookupState.Done(found, MatchScorer.isAmbiguous(found))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            LookupState.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(stringResource(R.string.laho_meta_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    LookupState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.laho_meta_searching), modifier = Modifier.padding(start = 12.dp))
                    }
                    is LookupState.Failed -> Text(
                        stringResource(R.string.laho_meta_error, s.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is LookupState.Done -> if (s.candidates.isEmpty()) {
                        Text(stringResource(R.string.laho_meta_none))
                    } else {
                        Text(
                            stringResource(if (s.ambiguous) R.string.laho_meta_pick else R.string.laho_meta_review),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        s.candidates.forEachIndexed { i, c ->
                            CandidateCard(c, selected == i) { selected = i }
                            Spacer(Modifier.height(8.dp))
                        }
                        val hasCover = s.candidates.getOrNull(selected)?.let { it.coverUrls().isNotEmpty() } == true
                        if (hasCover) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { useCover = !useCover },
                            ) {
                                Checkbox(checked = useCover, onCheckedChange = { useCover = it })
                                Text(stringResource(R.string.laho_meta_use_cover))
                            }
                        }
                        Text(
                            stringResource(R.string.laho_meta_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val done = state as? LookupState.Done
            if (done != null && done.candidates.isNotEmpty()) {
                Button(
                    enabled = !applying,
                    onClick = {
                        val c = done.candidates[selected.coerceIn(0, done.candidates.lastIndex)]
                        applying = true
                        scope.launch {
                            // Genre for the chosen candidate (only the top one is pre-fetched).
                            val full = try { AndroidMetadata.lookup.withGenre(c) } catch (e: Exception) { c }
                            var cover: Uri? = null
                            if (useCover) {
                                val file = File(context.cacheDir, "laho_meta_cover_$cacheKey.jpg")
                                if (AndroidMetadata.covers.download(full, file)) cover = Uri.fromFile(file)
                            }
                            applying = false
                            onApply(full, cover)
                        }
                    },
                ) { Text(stringResource(R.string.laho_meta_apply)) }
            } else if (state is LookupState.Failed) {
                Button(onClick = { attempt++ }) { Text(stringResource(R.string.laho_meta_retry)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying) { Text(stringResource(R.string.ui_cancel)) }
        },
    )
}

@Composable
private fun CandidateCard(c: MetadataCandidate, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                Text(c.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(c.artist, style = MaterialTheme.typography.bodyMedium)
                val details = listOfNotNull(
                    c.album,
                    c.year?.toString(),
                    c.genre,
                    c.trackNumber?.let { "#$it" },
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.laho_meta_source_confidence, c.source, c.confidencePercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
