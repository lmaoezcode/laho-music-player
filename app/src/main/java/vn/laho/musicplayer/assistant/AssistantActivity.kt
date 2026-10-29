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
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Job
import vn.laho.musicplayer.assistant.commands.BenchCommand
import vn.laho.musicplayer.assistant.commands.ChatSink
import vn.laho.musicplayer.assistant.commands.CommandRouter
import vn.laho.musicplayer.assistant.commands.DriveCommand
import vn.laho.musicplayer.assistant.commands.MetaCommand
import vn.laho.musicplayer.assistant.commands.MoodCommand
import vn.laho.musicplayer.assistant.commands.SuggestCommand
import vn.laho.musicplayer.mood.asIndex
import vn.laho.musicplayer.tv.TvDevice
import vn.laho.musicplayer.tv.TvKeys
import vn.laho.musicplayer.tv.tvFocusableItem
import vn.laho.musicplayer.tv.tvInitialFocus
import kotlinx.coroutines.launch

/** One line in the assistant chat. */
data class ChatMessage(val fromUser: Boolean, val text: String, val source: AssistantReply.Source? = null)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = MediaControllerGateway(app)
    private val gemini = vn.laho.musicplayer.assistant.online.GeminiAssistant.createOrNull(app)
    private val moodStore = vn.laho.musicplayer.mood.RoomMoodStore.get(app)
    val driveAuth = vn.laho.musicplayer.drive.DriveAuth(app)
    private val drive = vn.laho.musicplayer.drive.DriveRepository(
        vn.laho.musicplayer.drive.DriveClient(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            driveAuth,
        ),
        vn.laho.musicplayer.drive.MediaStoreTrackSaver(app),
    )
    private val history = vn.laho.musicplayer.suggest.RhythmListeningHistory(app)
    private val engine = AssistantEngine(
        ToolRegistry.default(
            gateway, moodStore.asIndex(), drive,
            queue = gateway, playlists = gateway, sleepTimer = gateway, history = history,
        ),
        online = gemini,
    )

    /** Slash commands ("/index", "/drive", ...): one class each in [vn.laho.musicplayer.assistant.commands]. */
    private val commands = CommandRouter(
        listOf(
            MoodCommand(app, moodStore),
            DriveCommand(driveAuth),
            MetaCommand(app),
            SuggestCommand(app, gateway, moodStore.asIndex(), history),
            BenchCommand(gateway, onlineAvailable = gemini != null),
        ),
    )
    private var commandJob: Job? = null

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

    /** "Gợi ý cho bạn" card; refreshed on start and after each request. */
    var suggestPreview by mutableStateOf<SuggestPreview?>(null)
        private set

    fun refreshSuggestions() {
        viewModelScope.launch {
            suggestPreview = runCatching { loadSuggestPreview(gateway, moodStore.asIndex(), history) }.getOrNull()
        }
    }

    init {
        refreshSuggestions()
    }

    private val chat = object : ChatSink {
        override fun post(text: String, source: AssistantReply.Source?): Int {
            messages += ChatMessage(fromUser = false, text = text, source = source)
            return messages.lastIndex
        }

        override fun update(index: Int, text: String) {
            if (index in messages.indices) messages[index] = ChatMessage(false, text)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.equals(CommandRouter.STOP, ignoreCase = true) && commandJob?.isActive == true) {
            messages += ChatMessage(fromUser = true, text = trimmed)
            commandJob?.cancel()
            return
        }
        if (trimmed.isEmpty() || busy) return
        messages += ChatMessage(fromUser = true, text = trimmed)
        busy = true
        val routed = commands.route(trimmed)
        if (routed != null) {
            val (command, input) = routed
            commandJob = viewModelScope.launch {
                try {
                    command.run(input, chat)
                } finally {
                    busy = false
                }
            }
            return
        }
        viewModelScope.launch {
            val started = System.nanoTime()
            val lines = mutableListOf<String>()
            var progressIndex = -1
            val reply = engine.handle(trimmed) { p ->
                // Single-step requests need no progress; show it from the second step on.
                val line = p.line()
                if (p.state == AgentProgress.State.RUNNING) lines += line else if (lines.isNotEmpty()) lines[lines.lastIndex] = line
                if (p.step > 1 || (p.total ?: 1) > 1) {
                    val text = lines.joinToString("\n")
                    if (progressIndex < 0) progressIndex = chat.post(text) else chat.update(progressIndex, text)
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            val rounds = if (reply.source == AssistantReply.Source.OFFLINE) 0 else gemini?.lastModelRounds ?: 0
            Log.i(
                TAG,
                "\"$trimmed\" -> ${reply.toolCall} [${reply.source}, ${ms} ms, $rounds model rounds] ${reply.result?.success}",
            )
            messages += ChatMessage(fromUser = false, text = reply.text, source = reply.source)
            busy = false
            refreshSuggestions()
        }
    }

    /** Called with the result of the Google consent screen. */
    fun onDriveConsentResult(data: Intent?) {
        val ok = driveAuth.onConsentResult(data)
        messages += ChatMessage(
            false,
            if (ok) "Đã kết nối Google Drive. Hãy nói lại yêu cầu, ví dụ \"tìm <tên bài> trên drive\"."
            else "Chưa cấp quyền Google Drive.",
        )
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

    /** Bumped whenever speech input should start (TV remote voice/search key, launch extra). */
    private val voiceRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_VOICE, false)) voiceRequests.intValue++
        setContent {
            RhythmTheme {
                AssistantScreen(viewModel, onBack = ::finish, voiceRequest = voiceRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) voiceRequests.intValue++
    }

    // LaHo TV: the remote's search/voice key starts speech input again while the chat is open.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (TvDevice.isTv(this) && TvKeys.isAssistantKey(keyCode)) {
            if (event.repeatCount == 0) voiceRequests.intValue++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        (TvDevice.isTv(this) && TvKeys.isAssistantKey(keyCode)) || super.onKeyUp(keyCode, event)

    companion object {
        /** Boolean extra: start speech recognition as soon as the screen opens. */
        const val EXTRA_START_VOICE = "vn.laho.musicplayer.extra.START_VOICE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(viewModel: AssistantViewModel, onBack: () -> Unit, voiceRequest: Int = 0) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) viewModel.send(spoken)
    }

    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onDriveConsentResult(result.data)
    }
    val pendingConsent by viewModel.driveAuth.pending.collectAsState()
    LaunchedEffect(pendingConsent) {
        val intent = viewModel.driveAuth.consumePending() ?: return@LaunchedEffect
        driveConsent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
    }

    fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói lệnh cho LaHo")
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.messages += ChatMessage(false, "Máy không có trình nhận dạng giọng nói.")
        }
    }

    LaunchedEffect(voiceRequest) {
        if (voiceRequest > 0 && !viewModel.busy) startSpeech()
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

            SuggestCard(
                preview = viewModel.suggestPreview,
                enabled = !viewModel.busy,
                onPlayNow = { viewModel.send("nhạc cho lúc này") },
                onDailyMix = { viewModel.send("daily mix") },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

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
                    onClick = ::startSpeech,
                    enabled = !viewModel.busy,
                    // LaHo TV: D-pad starts on the mic button
                    modifier = Modifier.tvInitialFocus(),
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
                .tvFocusableItem() // LaHo TV: lets the D-pad scroll back through the chat
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
