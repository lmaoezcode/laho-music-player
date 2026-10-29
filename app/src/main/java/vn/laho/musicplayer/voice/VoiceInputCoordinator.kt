// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

/** What the coordinator tells the screen. All calls happen on the main thread. */
interface VoiceInputListener {
    /** Listening started/stopped; [engine] is the one in use. */
    fun onListening(listening: Boolean, engine: VoiceEngine)

    fun onPartial(text: String)

    /** Normalised text ready for the command parser; never empty. */
    fun onResult(text: String, engine: VoiceEngine)

    /** [from] failed with [error] and [to] takes over (the keyboard when [to] is KEYBOARD). */
    fun onFallback(from: VoiceEngine, to: VoiceEngine, error: VoiceError)
}

/**
 * Runs the recogniser order Vosk → system recogniser → keyboard. Android-free so it can be
 * tested with fakes; the screen supplies the engines as lambdas.
 */
class VoiceInputCoordinator(
    private val availability: () -> EngineAvailability,
    private val createOfflineRecognizer: () -> VoiceRecognizer,
    /** Starts the system speech dialog; false when it cannot be shown. */
    private val launchSystemRecognizer: () -> Boolean,
    private val focusKeyboard: () -> Unit,
    private val listener: VoiceInputListener,
) {
    private var offline: VoiceRecognizer? = null
    private var active: VoiceEngine? = null

    val isListening: Boolean get() = active != null

    /** Starts listening with the best available engine. No-op if already listening. */
    fun begin() {
        if (active != null) return
        run(EngineSelector.select(availability()))
    }

    /** Ends the utterance (Vosk delivers its final text asynchronously). */
    fun end() {
        if (active == VoiceEngine.VOSK_OFFLINE) offline?.stop()
    }

    fun cancel() {
        if (active == VoiceEngine.VOSK_OFFLINE) offline?.cancel()
        finishListening()
    }

    fun release() {
        offline?.release()
        offline = null
        active = null
    }

    /** The system dialog returned [spoken] (null/blank when cancelled). */
    fun onSystemResult(spoken: String?) {
        if (active != VoiceEngine.SYSTEM) return
        finishListening()
        deliver(spoken, VoiceEngine.SYSTEM)
    }

    private fun run(engine: VoiceEngine) {
        when (engine) {
            VoiceEngine.VOSK_OFFLINE -> startOffline()
            VoiceEngine.SYSTEM -> {
                active = VoiceEngine.SYSTEM
                listener.onListening(true, VoiceEngine.SYSTEM)
                val launched = runCatching(launchSystemRecognizer).getOrDefault(false)
                if (!launched) {
                    finishListening()
                    fallBack(VoiceEngine.SYSTEM, VoiceError.Other("system recogniser unavailable"))
                }
            }
            VoiceEngine.KEYBOARD -> focusKeyboard()
        }
    }

    private fun startOffline() {
        val recognizer = try {
            offline ?: createOfflineRecognizer().also { offline = it }
        } catch (e: Exception) {
            fallBack(VoiceEngine.VOSK_OFFLINE, VoiceError.ModelLoad(e.message ?: "model"))
            return
        }
        active = VoiceEngine.VOSK_OFFLINE
        listener.onListening(true, VoiceEngine.VOSK_OFFLINE)
        try {
            recognizer.start(object : VoiceCallback {
                override fun onPartial(text: String) {
                    if (active == VoiceEngine.VOSK_OFFLINE) listener.onPartial(VoiceTextNormalizer.normalize(text))
                }

                override fun onFinal(text: String) {
                    finishListening()
                    deliver(text, VoiceEngine.VOSK_OFFLINE)
                }

                override fun onError(error: VoiceError) {
                    finishListening()
                    // "nothing heard" is not an engine failure: do not open another dialog.
                    if (error is VoiceError.NoSpeech) return
                    fallBack(VoiceEngine.VOSK_OFFLINE, error)
                }
            })
        } catch (e: Exception) {
            finishListening()
            fallBack(VoiceEngine.VOSK_OFFLINE, VoiceError.Audio(e.message ?: "audio"))
        }
    }

    private fun finishListening() {
        val engine = active ?: return
        active = null
        listener.onListening(false, engine)
    }

    private fun deliver(raw: String?, engine: VoiceEngine) {
        val text = VoiceTextNormalizer.normalize(raw.orEmpty())
        if (text.isNotEmpty()) listener.onResult(text, engine)
    }

    private fun fallBack(failed: VoiceEngine, error: VoiceError) {
        // A failed engine counts as unavailable when choosing the next one.
        val a = availability().let {
            if (failed == VoiceEngine.VOSK_OFFLINE) it.copy(modelInstalled = false) else it.copy(systemRecognizerAvailable = false)
        }
        val next = EngineSelector.fallbackAfter(failed, a) ?: VoiceEngine.KEYBOARD
        listener.onFallback(failed, next, error)
        run(next)
    }
}
