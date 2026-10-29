// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Process-wide holder of the (large) native Vosk model. */
object VoskEngine {
    @Volatile
    private var model: Model? = null

    @Synchronized
    fun model(path: String): Model = model ?: Model(path).also { model = it }

    @Synchronized
    fun unload() {
        model?.close()
        model = null
    }
}

/**
 * Offline recogniser backed by Vosk's [SpeechService]. Needs RECORD_AUDIO to be granted already.
 * Loading the model can take a second or two, so [start] must not be called on a hot path
 * without the caller expecting that delay.
 */
class VoskRecognizer(private val modelPath: String) : VoiceRecognizer {
    override val engine = VoiceEngine.VOSK_OFFLINE

    private var service: SpeechService? = null

    override fun start(callback: VoiceCallback) {
        cancel()
        val recognizer = try {
            Recognizer(VoskEngine.model(modelPath), SAMPLE_RATE)
        } catch (e: Exception) {
            throw IllegalStateException("cannot load Vosk model: ${e.message}", e)
        }
        val s = SpeechService(recognizer, SAMPLE_RATE)
        service = s
        // Finished segments; a command is usually one utterance, so the first one ends the session.
        val heard = StringBuilder()
        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val text = hypothesis?.let { json(it, "partial") }.orEmpty()
                if (text.isNotEmpty()) callback.onPartial((heard.toString() + " " + text).trim())
            }

            override fun onResult(hypothesis: String?) {
                val text = hypothesis?.let { json(it, "text") }.orEmpty()
                if (text.isNotBlank()) {
                    heard.append(' ').append(text)
                    stop()
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = (heard.toString() + " " + hypothesis?.let { json(it, "text") }.orEmpty()).trim()
                release()
                if (text.isBlank()) callback.onError(VoiceError.NoSpeech) else callback.onFinal(text)
            }

            override fun onError(exception: Exception?) {
                release()
                callback.onError(VoiceError.Audio(exception?.message ?: "audio"))
            }

            override fun onTimeout() {
                stop()
            }
        }
        if (!s.startListening(listener, TIMEOUT_MS)) {
            release()
            throw IllegalStateException("microphone busy")
        }
    }

    override fun stop() {
        // Flushes the pending audio and reports it through onFinalResult.
        service?.stop()
    }

    override fun cancel() {
        service?.cancel()
        release()
    }

    override fun release() {
        service?.shutdown()
        service = null
    }

    private fun json(raw: String, key: String): String =
        try {
            JSONObject(raw).optString(key, "")
        } catch (_: Exception) {
            ""
        }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
        const val TIMEOUT_MS = 10_000
    }
}
