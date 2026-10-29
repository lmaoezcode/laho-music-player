// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

/** Speech engines, in the order LaHo prefers them. */
enum class VoiceEngine {
    /** Vosk running on the phone with the downloaded Vietnamese model (no network). */
    VOSK_OFFLINE,

    /** The system recogniser through [android.speech.RecognizerIntent] (may use the network). */
    SYSTEM,

    /** No speech engine: the user types. */
    KEYBOARD,
}

/** Why an engine could not deliver a result. */
sealed interface VoiceError {
    val message: String

    data class ModelLoad(override val message: String) : VoiceError
    data class Audio(override val message: String) : VoiceError
    data object PermissionDenied : VoiceError {
        override val message = "microphone permission denied"
    }
    data object NoSpeech : VoiceError {
        override val message = "no speech detected"
    }
    data class Other(override val message: String) : VoiceError
}

/** Receives what a [VoiceRecognizer] hears. Called on the main thread. */
interface VoiceCallback {
    /** Live text while the user is still speaking (preview in the chat input). */
    fun onPartial(text: String)

    /** The final text for this utterance (raw engine output, not normalised). */
    fun onFinal(text: String)

    fun onError(error: VoiceError)
}

/**
 * A streaming recogniser that owns the microphone between [start] and [stop]/[cancel].
 * The Vosk engine implements this; the system recogniser is an Activity result and is driven by
 * the UI, so only its selection lives in [EngineSelector].
 */
interface VoiceRecognizer {
    val engine: VoiceEngine

    fun start(callback: VoiceCallback)

    /** Ends the utterance; the final text still arrives through [VoiceCallback.onFinal]. */
    fun stop()

    /** Drops the utterance without a result. */
    fun cancel()

    /** Frees native resources. */
    fun release()
}
