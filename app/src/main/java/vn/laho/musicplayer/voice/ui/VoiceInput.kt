// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import vn.laho.musicplayer.voice.EngineAvailability
import vn.laho.musicplayer.voice.ModelState
import vn.laho.musicplayer.voice.PushToTalk
import vn.laho.musicplayer.voice.TalkAction
import vn.laho.musicplayer.voice.VoiceEngine
import vn.laho.musicplayer.voice.VoiceError
import vn.laho.musicplayer.voice.VoiceInputCoordinator
import vn.laho.musicplayer.voice.VoiceInputListener
import vn.laho.musicplayer.voice.VoskModelManager
import vn.laho.musicplayer.voice.VoskRecognizer

/** Compose-facing state of the voice input of the assistant chat. */
@Stable
class VoiceInputState internal constructor() {
    var listening by mutableStateOf(false)
        internal set

    /** Live text while speaking; empty when idle. */
    var partial by mutableStateOf("")
        internal set

    var engine by mutableStateOf(VoiceEngine.KEYBOARD)
        internal set

    internal val focusRequester = FocusRequester()
    internal var startAction: () -> Unit = {}
    internal var stopAction: () -> Unit = {}

    /** Apply to the chat input field so the keyboard fallback can focus it. */
    val inputModifier: Modifier get() = Modifier.focusRequester(focusRequester)

    /** What the chat input shows: the partial recognition while listening, else the typed text. */
    fun preview(typed: String): String = if (listening && partial.isNotEmpty()) partial else typed

    fun start() = startAction()
    fun stop() = stopAction()
}

/**
 * Wires the recogniser order Vosk → system recogniser → keyboard to the screen. [onResult] gets
 * normalised text for the command parser.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInputState {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state = remember { VoiceInputState() }
    val currentOnResult = rememberUpdatedState(onResult)
    val models = remember { VoskModelManager.get(context) }
    val coordinatorRef = remember { arrayOfNulls<VoiceInputCoordinator>(1) }

    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        coordinatorRef[0]?.onSystemResult(spoken)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted → Vosk is used; denied → the coordinator picks the system recogniser.
        coordinatorRef[0]?.begin()
    }

    val coordinator = remember {
        VoiceInputCoordinator(
            availability = {
                EngineAvailability(
                    modelInstalled = models.state.value == ModelState.Installed,
                    micPermissionGranted = hasMicPermission(context),
                    systemRecognizerAvailable = true,
                )
            },
            createOfflineRecognizer = { VoskRecognizer(models.modelPath.absolutePath) },
            launchSystemRecognizer = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.laho_voice_prompt))
                try {
                    systemLauncher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            },
            focusKeyboard = {
                runCatching { state.focusRequester.requestFocus() }
                keyboard?.show()
            },
            listener = object : VoiceInputListener {
                override fun onListening(listening: Boolean, engine: VoiceEngine) {
                    state.listening = listening
                    state.engine = engine
                    if (!listening) state.partial = ""
                }

                override fun onPartial(text: String) {
                    state.partial = text
                }

                override fun onResult(text: String, engine: VoiceEngine) {
                    currentOnResult.value(text)
                }

                override fun onFallback(from: VoiceEngine, to: VoiceEngine, error: VoiceError) {
                    val res = if (to == VoiceEngine.KEYBOARD) R.string.laho_voice_fallback_keyboard else R.string.laho_voice_fallback_system
                    Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    coordinatorRef[0] = coordinator

    state.startAction = {
        val installed = models.state.value == ModelState.Installed
        if (installed && !hasMicPermission(context)) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            coordinator.begin()
        }
    }
    state.stopAction = { coordinator.end() }

    DisposableEffect(coordinator) {
        onDispose {
            coordinator.cancel()
            coordinator.release()
        }
    }
    return state
}

private fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The assistant's microphone button: hold to talk, or tap to start and tap again to stop.
 * Works with touch, D-pad centre and keyboard Enter (all arrive as press interactions).
 */
@Composable
fun VoiceButton(voice: VoiceInputState, enabled: Boolean, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val gesture = remember { PushToTalk() }
    val currentVoice = rememberUpdatedState(voice)
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            val v = currentVoice.value
            val action = when (interaction) {
                is PressInteraction.Press -> gesture.onPress(System.currentTimeMillis(), v.listening)
                is PressInteraction.Release -> gesture.onRelease(System.currentTimeMillis())
                is PressInteraction.Cancel -> gesture.onCancel(System.currentTimeMillis())
                else -> TalkAction.NONE
            }
            when (action) {
                TalkAction.START -> v.start()
                TalkAction.STOP -> v.stop()
                TalkAction.NONE -> Unit
            }
        }
    }
    OutlinedButton(
        onClick = {},
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier,
    ) {
        Text(stringResource(if (voice.listening) R.string.laho_voice_listening else R.string.laho_voice_talk))
    }
}
