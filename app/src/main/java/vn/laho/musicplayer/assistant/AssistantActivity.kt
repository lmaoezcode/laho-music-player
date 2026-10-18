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
import vn.laho.musicplayer.mood.asIndex
import kotlinx.coroutines.launch

/** One line in the assistant chat. */
data class ChatMessage(val fromUser: Boolean, val text: String, val source: AssistantReply.Source? = null)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = MediaControllerGateway(app)
    private val gemini = vn.laho.musicplayer.assistant.online.GeminiAssistant.createOrNull(app)
    private val moodStore = vn.laho.musicplayer.mood.RoomMoodStore.get(app)
    private val engine = AssistantEngine(
        ToolRegistry.default(gateway, moodStore.asIndex()),
        online = gemini,
    )
    private var indexJob: kotlinx.coroutines.Job? = null

    val messages = mutableStateListOf(
        ChatMessage(
            fromUser = false,
            text = if (gemini != null) {
                "Xin chào! Mình là trợ lý LaHo. Lệnh cơ bản (\"bài tiếp\", \"tạm dừng\", \"to lên\", " +
                    "\"phát bài …\") chạy offline; yêu cầu khác như \"bật nhạc buồn\" hay " +
                    "\"nhạc để tập gym\" sẽ hỏi Gemini (cần mạng)."
            } else {
                "Xin chào! Mình là trợ lý LaHo (chỉ offline trong bản này). Thử nói hoặc gõ: " +
                    "\"bài tiếp\", \"tạm dừng\", \"to lên\", \"âm lượng 40\", \"phát bài …\", \"nhạc của …\"."
            },
        ),
    )
    var busy by mutableStateOf(false)
        private set

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.equals("/stop", ignoreCase = true) && indexJob?.isActive == true) {
            messages += ChatMessage(fromUser = true, text = trimmed)
            indexJob?.cancel()
            return
        }
        if (trimmed.isEmpty() || busy) return
        messages += ChatMessage(fromUser = true, text = trimmed)
        if (trimmed.startsWith("/index", ignoreCase = true) || trimmed.startsWith("/mood", ignoreCase = true)) {
            busy = true
            moodCommand(trimmed.lowercase())
            return
        }
        busy = true
        if (trimmed.startsWith("/bench", ignoreCase = true)) {
            // "/bench" compares flash and flash-lite; "/bench lite" or "/bench flash" runs one.
            runBenchmark(trimmed.removePrefix("/bench").trim().lowercase())
            return
        }
        viewModelScope.launch {
            val started = System.nanoTime()
            val reply = engine.handle(trimmed)
            val ms = (System.nanoTime() - started) / 1_000_000
            val rounds = if (reply.source == AssistantReply.Source.OFFLINE) 0 else gemini?.lastModelRounds ?: 0
            Log.i(
                TAG,
                "\"$trimmed\" -> ${reply.toolCall} [${reply.source}, ${ms} ms, $rounds model rounds] ${reply.result?.success}",
            )
            messages += ChatMessage(fromUser = false, text = reply.text, source = reply.source)
            busy = false
        }
    }

    /**
     * Mood index commands: "/index" (analyse new songs; "/stop" cancels), "/mood" (status),
     * "/mood export" (CSV for evaluation), "/mood reset" (forget everything).
     */
    private fun moodCommand(cmd: String) {
        val ctx = getApplication<Application>()
        when {
            cmd.startsWith("/index") -> {
                messages += ChatMessage(false, "Đang lập chỉ mục tâm trạng… (gõ /stop để dừng, chạy lại sẽ tiếp tục)")
                val progressAt = messages.lastIndex
                indexJob = viewModelScope.launch {
                    val started = System.currentTimeMillis()
                    try {
                        val report = vn.laho.musicplayer.mood.AndroidMoodIndexing.indexer(ctx, moodStore).run { p ->
                            val text = when (p.stage) {
                                vn.laho.musicplayer.mood.IndexProgress.Stage.AUDIO ->
                                    "Phân tích âm thanh ${p.done + 1}/${p.total}: ${p.current.orEmpty()}"
                                vn.laho.musicplayer.mood.IndexProgress.Stage.LLM ->
                                    "Gemini phân loại ${p.done}/${p.total} bài…"
                                vn.laho.musicplayer.mood.IndexProgress.Stage.DONE -> "Hoàn tất."
                            }
                            messages[progressAt] = ChatMessage(false, text)
                        }
                        val secs = (System.currentTimeMillis() - started) / 1000
                        val summary = "Chỉ mục tâm trạng xong sau ${secs}s: ${report.indexedWithMood}/${report.songs} bài có tâm trạng. " +
                            "Âm thanh: ${report.analysed} bài mới phân tích, ${report.decodeFailures} bài không giải mã được. " +
                            "Gemini: ${report.classified} bài phân loại" +
                            (if (report.llmFailures > 0) ", ${report.llmFailures} bài lỗi (chạy lại /index để thử lại)." else ".") +
                            llmErrorHint(report.lastLlmError) +
                            " Thử: \"bật nhạc buồn\", \"nhạc tập gym\"."
                        Log.i(TAG, summary)
                        messages += ChatMessage(false, summary)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        messages += ChatMessage(false, "Đã dừng. Gõ /index để tiếp tục từ chỗ dừng.")
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Mood indexing failed", e)
                        messages += ChatMessage(false, "Lập chỉ mục lỗi: ${e.message}")
                    } finally {
                        busy = false
                    }
                }
            }
            cmd.startsWith("/mood export") -> viewModelScope.launch {
                try {
                    val f = vn.laho.musicplayer.mood.AndroidMoodIndexing.exportCsv(ctx, moodStore)
                    messages += ChatMessage(false, "Đã xuất ${f.absolutePath}")
                } finally {
                    busy = false
                }
            }
            cmd.startsWith("/mood reset") -> viewModelScope.launch {
                moodStore.clear()
                messages += ChatMessage(false, "Đã xoá chỉ mục tâm trạng.")
                busy = false
            }
            else -> viewModelScope.launch {
                val records = moodStore.all()
                val profiles = records.mapNotNull { it.profile() }
                val byMood = vn.laho.musicplayer.mood.Mood.values().joinToString(", ") { m ->
                    "${m.label} ${profiles.count { m in it.moods }}"
                }
                messages += ChatMessage(
                    false,
                    "Chỉ mục: ${records.size} bài (${records.count { it.hasFeatures && it.featureError == null }} có đặc trưng âm thanh, " +
                        "${records.count { it.hasVerdict }} đã qua Gemini). Theo nhãn: $byMood.",
                )
                busy = false
            }
        }
    }

    private fun llmErrorHint(error: String?): String {
        if (error == null) return ""
        val lower = error.lowercase()
        return when {
            "app check" in lower || "appcheck" in lower || "attestation" in lower ->
                " Lý do: App Check từ chối. Hãy thêm debug token của máy này (logcat, tag DebugAppCheckProvider) " +
                    "vào Firebase Console → Security → App Check → ⋮ → Manage debug tokens."
            "unable to resolve host" in lower || "timeout" in lower || "network" in lower ->
                " Lý do: không có mạng."
            "quota" in lower || "429" in lower -> " Lý do: hết lượt miễn phí, thử lại sau."
            else -> " Lý do: $error"
        }
    }

    /** "/bench": compares Gemini models on a fixed prompt set without touching playback. */
    private fun runBenchmark(which: String) {
        if (gemini == null) {
            messages += ChatMessage(false, "Bản build này không có Firebase nên không chạy được benchmark.")
            busy = false
            return
        }
        val flash = vn.laho.musicplayer.assistant.online.GeminiAssistant.MODEL_FLASH
        val lite = vn.laho.musicplayer.assistant.online.GeminiAssistant.MODEL_FLASH_LITE
        val models = when (which) {
            "lite" -> listOf(lite)
            "flash" -> listOf(flash)
            else -> listOf(flash, lite)
        }
        messages += ChatMessage(
            false,
            "Đang chạy benchmark ${models.joinToString(" và ")} (10 câu mỗi model, dry-run, " +
                "khoảng 3-5 phút)…",
        )
        viewModelScope.launch {
            try {
                val bench = vn.laho.musicplayer.assistant.online.AssistantBenchmark(gateway)
                val summaries = bench.run(models) { progress -> Log.i(TAG, progress) }
                val report = vn.laho.musicplayer.assistant.online.AssistantBenchmark.format(summaries)
                Log.i(TAG, report)
                messages += ChatMessage(false, report, AssistantReply.Source.ONLINE)
            } catch (e: Exception) {
                Log.w(TAG, "Benchmark failed", e)
                messages += ChatMessage(false, "Benchmark lỗi: ${e.message}")
            } finally {
                busy = false
            }
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
