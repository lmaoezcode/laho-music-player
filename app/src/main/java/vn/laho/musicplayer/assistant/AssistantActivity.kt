// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chromahub.rhythm.app.R
import chromahub.rhythm.app.ui.theme.RhythmTheme
import kotlinx.coroutines.launch

/** One line in the assistant chat. */
data class ChatMessage(val fromUser: Boolean, val text: String, val source: AssistantReply.Source? = null)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = MediaControllerGateway(app)
    private val engine = AssistantEngine(ToolRegistry.default(gateway))

    val messages = mutableStateListOf(
        ChatMessage(
            fromUser = false,
            text = "Xin chào! Mình là trợ lý LaHo (chế độ offline). Thử nói hoặc gõ: " +
                "\"bài tiếp\", \"tạm dừng\", \"to lên\", \"âm lượng 40\", \"phát bài …\", \"nhạc của …\".",
        ),
    )
    var busy by mutableStateOf(false)
        private set

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        messages += ChatMessage(fromUser = true, text = trimmed)
        busy = true
        viewModelScope.launch {
            val started = System.nanoTime()
            val reply = engine.handle(trimmed)
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "\"$trimmed\" -> ${reply.toolCall} [${reply.source}, ${ms} ms] ${reply.result?.success}")
            messages += ChatMessage(fromUser = false, text = reply.text, source = reply.source)
            busy = false
        }
    }

    override fun onCleared() {
        gateway.release()
    }

    private companion object {
        const val TAG = "LahoAssistant"
    }
}

/** Experimental chat screen for the LaHo assistant (spike 3, offline part). */
class AssistantActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RhythmTheme {
                AssistantScreen(viewModel, onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(viewModel: AssistantViewModel, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) viewModel.send(spoken)
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) listState.animateScrollToItem(viewModel.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.laho_assistant_title)) },
                navigationIcon = { OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(viewModel.messages) { MessageBubble(it) }
            }

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (suggestion in SUGGESTIONS) {
                    AssistChip(onClick = { viewModel.send(suggestion) }, label = { Text(suggestion) })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhập lệnh…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send(input); input = "" }),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói lệnh cho LaHo")
                        try {
                            speechLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            viewModel.messages += ChatMessage(false, "Máy không có trình nhận dạng giọng nói.")
                        }
                    },
                    enabled = !viewModel.busy,
                ) { Text("Nói") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.send(input); input = "" },
                    enabled = input.isNotBlank() && !viewModel.busy,
                ) { Text("Gửi") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = if (message.fromUser) colors.primaryContainer else colors.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.text,
                color = if (message.fromUser) colors.onPrimaryContainer else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (message.source != null && message.source != AssistantReply.Source.NONE) {
                Text(
                    text = if (message.source == AssistantReply.Source.OFFLINE) "offline" else "online",
                    color = colors.outline,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private val SUGGESTIONS = listOf("Bài tiếp", "Bài trước", "Tạm dừng", "Phát tiếp", "To lên", "Nhỏ lại", "Bài gì đây?")
