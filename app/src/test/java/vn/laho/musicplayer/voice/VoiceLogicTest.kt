// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.assistant.OfflineCommandParser
import vn.laho.musicplayer.assistant.ToolCall

class EngineSelectorTest {
    private fun avail(model: Boolean = true, mic: Boolean = true, system: Boolean = true, offline: Boolean = true) =
        EngineAvailability(model, mic, system, offline)

    @Test fun offlineFirstWhenModelAndMic() {
        assertEquals(VoiceEngine.VOSK_OFFLINE, EngineSelector.select(avail()))
        assertEquals(
            listOf(VoiceEngine.VOSK_OFFLINE, VoiceEngine.SYSTEM, VoiceEngine.KEYBOARD),
            EngineSelector.order(avail()),
        )
    }

    @Test fun systemWhenModelMissingOrMicDenied() {
        assertEquals(VoiceEngine.SYSTEM, EngineSelector.select(avail(model = false)))
        assertEquals(VoiceEngine.SYSTEM, EngineSelector.select(avail(mic = false)))
        assertEquals(VoiceEngine.SYSTEM, EngineSelector.select(avail(offline = false)))
    }

    @Test fun keyboardWhenNothingElse() {
        assertEquals(VoiceEngine.KEYBOARD, EngineSelector.select(avail(model = false, system = false)))
        assertEquals(listOf(VoiceEngine.KEYBOARD), EngineSelector.order(avail(model = false, system = false)))
    }

    @Test fun fallbackWalksDownTheOrder() {
        assertEquals(VoiceEngine.SYSTEM, EngineSelector.fallbackAfter(VoiceEngine.VOSK_OFFLINE, avail()))
        assertEquals(VoiceEngine.KEYBOARD, EngineSelector.fallbackAfter(VoiceEngine.SYSTEM, avail()))
        assertNull(EngineSelector.fallbackAfter(VoiceEngine.KEYBOARD, avail()))
        // a failed engine that is no longer in the list still moves on to the next lower one
        assertEquals(VoiceEngine.KEYBOARD, EngineSelector.fallbackAfter(VoiceEngine.SYSTEM, avail(system = false)))
    }
}

class ModelStateMachineTest {
    private fun run(start: ModelState, vararg events: ModelEvent) =
        events.fold(start) { s, e -> ModelStateMachine.reduce(s, e) }

    @Test fun happyPath() {
        val s = run(
            ModelState.NotInstalled,
            ModelEvent.StartDownload,
            ModelEvent.Progress(10, 100),
            ModelEvent.DownloadFinished,
            ModelEvent.VerifiedOk,
            ModelEvent.Unpacked,
        )
        assertEquals(ModelState.Installed, s)
    }

    @Test fun progressIsTracked() {
        val s = run(ModelState.NotInstalled, ModelEvent.StartDownload, ModelEvent.Progress(25, 100))
        assertEquals(ModelState.Downloading(25, 100), s)
        assertEquals(0.25f, (s as ModelState.Downloading).fraction, 0.0001f)
    }

    @Test fun checksumFailureThenRetry() {
        val failed = run(
            ModelState.NotInstalled,
            ModelEvent.StartDownload,
            ModelEvent.DownloadFinished,
            ModelEvent.Error(FailureReason.CHECKSUM),
        )
        assertEquals(ModelState.Failed(FailureReason.CHECKSUM), failed)
        assertEquals(ModelState.Downloading(0, 0), run(failed, ModelEvent.StartDownload))
    }

    @Test fun cancelReturnsToNotInstalled() {
        assertEquals(
            ModelState.NotInstalled,
            run(ModelState.NotInstalled, ModelEvent.StartDownload, ModelEvent.Cancel),
        )
    }

    @Test fun illegalTransitionsAreIgnored() {
        assertEquals(ModelState.NotInstalled, run(ModelState.NotInstalled, ModelEvent.VerifiedOk, ModelEvent.Unpacked))
        assertEquals(ModelState.Installed, run(ModelState.Installed, ModelEvent.StartDownload))
        assertEquals(ModelState.Installed, run(ModelState.Installed, ModelEvent.Error(FailureReason.NETWORK)))
        val busy = run(ModelState.NotInstalled, ModelEvent.StartDownload)
        assertEquals(busy, run(busy, ModelEvent.Delete))
    }

    @Test fun diskAndDelete() {
        assertEquals(ModelState.Installed, run(ModelState.NotInstalled, ModelEvent.FoundOnDisk))
        assertEquals(ModelState.NotInstalled, run(ModelState.Installed, ModelEvent.Delete))
        assertTrue(ModelStateMachine.isBusy(ModelState.Verifying))
        assertFalse(ModelStateMachine.isBusy(ModelState.Installed))
    }
}

class ChecksumTest {
    private fun bytes(s: String) = s.toByteArray().inputStream()

    @Test fun md5AndSha256OfAbc() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Checksum.hex(bytes("abc"), "MD5"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksum.hex(bytes("abc"), "SHA-256"),
        )
    }

    @Test fun matchesIgnoresCaseAndSpaces() {
        assertTrue(Checksum.matches(bytes("abc"), "MD5", " 900150983CD24FB0D6963F7D28E17F72 "))
        assertFalse(Checksum.matches(bytes("abd"), "MD5", "900150983cd24fb0d6963f7d28e17f72"))
    }

    @Test fun pinnedModelSpecIsWellFormed() {
        val spec = VoskModelSpec.VI_SMALL
        assertEquals(32, spec.checksumHex.length)
        assertTrue(spec.url.startsWith("https://"))
        assertEquals(33, spec.sizeMegabytes)
    }
}

class VoiceTextNormalizerTest {
    private fun n(s: String) = VoiceTextNormalizer.normalize(s)

    @Test fun cleansMarkersAndSpaces() {
        assertEquals("bài tiếp", n("  bài   [unk] tiếp "))
        assertEquals("", n("[unk]"))
        assertEquals("", n("   "))
    }

    @Test fun decomposedUnicodeBecomesNfc() {
        val decomposed = java.text.Normalizer.normalize("tạm dừng", java.text.Normalizer.Form.NFD)
        assertTrue(decomposed.length > "tạm dừng".length)
        assertEquals("tạm dừng", n(decomposed))
    }

    @Test fun spokenNumbersBecomeDigitsInContext() {
        assertEquals("âm lượng 40", n("âm lượng bốn mươi"))
        assertEquals("âm lượng 5", n("âm lượng năm"))
        assertEquals("hẹn giờ tắt nhạc sau 30 phút", n("hẹn giờ tắt nhạc sau ba mươi phút"))
        assertEquals("thêm 5 bài vào hàng đợi", n("thêm năm bài vào hàng đợi"))
        assertEquals("tạo playlist nhạc buồn 20 bài", n("tạo playlist nhạc buồn hai mươi bài"))
        assertEquals("âm lượng 100", n("âm lượng một trăm"))
        assertEquals("âm lượng 15", n("âm lượng mười lăm"))
        assertEquals("âm lượng 21", n("âm lượng hai mươi mốt"))
        assertEquals("âm lượng 105", n("âm lượng một trăm lẻ năm"))
    }

    @Test fun ambiguousSingleWordsStayWords() {
        assertEquals("phát bài ba", n("phát bài ba"))
        assertEquals("phát bài năm anh em", n("phát bài năm anh em"))
        assertEquals("phát bài hai", n("phát bài hai"))
    }

    @Test fun invalidNumbersAreLeftAlone() {
        assertEquals("sáu bảy", n("sáu bảy"))
        assertEquals("phát mười mốt", n("phát mười mốt"))
    }

    @Test fun parseNumberRange() {
        assertEquals(0, VoiceTextNormalizer.parseNumber(listOf("không")))
        assertEquals(10, VoiceTextNormalizer.parseNumber(listOf("mười")))
        assertEquals(99, VoiceTextNormalizer.parseNumber(listOf("chín", "mươi", "chín")))
        assertEquals(250, VoiceTextNormalizer.parseNumber(listOf("hai", "trăm", "năm", "mươi")))
        assertNull(VoiceTextNormalizer.parseNumber(listOf("trăm")))
    }

    @Test fun normalisedOutputReachesTheOfflineParser() {
        val parser = OfflineCommandParser()
        assertEquals(ToolCall("set_volume", mapOf("level" to 40)), parser.parse(n("âm lượng bốn mươi")))
        val next = parser.parse("bài tiếp")
        assertNotNull(next)
        assertEquals(next, parser.parse(n("bài tiếp [unk]")))
    }
}

class PushToTalkTest {
    @Test fun tapStartsAndKeepsListeningThenSecondTapStops() {
        val g = PushToTalk(holdThresholdMs = 400)
        assertEquals(TalkAction.START, g.onPress(0, listening = false))
        assertEquals(TalkAction.NONE, g.onRelease(120))
        assertEquals(TalkAction.NONE, g.onPress(5_000, listening = true))
        assertEquals(TalkAction.STOP, g.onRelease(5_100))
    }

    @Test fun holdStopsOnRelease() {
        val g = PushToTalk(holdThresholdMs = 400)
        assertEquals(TalkAction.START, g.onPress(0, listening = false))
        assertEquals(TalkAction.STOP, g.onRelease(1_500))
    }

    @Test fun releaseWithoutPressDoesNothing() {
        assertEquals(TalkAction.NONE, PushToTalk().onRelease(10))
    }
}

class VoiceInputCoordinatorTest {

    private class FakeRecognizer(val failOnStart: Exception? = null) : VoiceRecognizer {
        override val engine = VoiceEngine.VOSK_OFFLINE
        var callback: VoiceCallback? = null
        var stopped = false
        var cancelled = false
        var released = false

        override fun start(callback: VoiceCallback) {
            failOnStart?.let { throw it }
            this.callback = callback
        }

        override fun stop() { stopped = true }
        override fun cancel() { cancelled = true }
        override fun release() { released = true }
    }

    private class Recorder : VoiceInputListener {
        val events = mutableListOf<String>()
        val results = mutableListOf<Pair<String, VoiceEngine>>()
        val partials = mutableListOf<String>()
        override fun onListening(listening: Boolean, engine: VoiceEngine) { events += "listening=$listening:$engine" }
        override fun onPartial(text: String) { partials += text }
        override fun onResult(text: String, engine: VoiceEngine) { results += text to engine }
        override fun onFallback(from: VoiceEngine, to: VoiceEngine, error: VoiceError) { events += "fallback:$from->$to" }
    }

    private class Harness(
        var availability: EngineAvailability,
        val recognizer: () -> FakeRecognizer = { FakeRecognizer() },
        var systemLaunches: Boolean = true,
    ) {
        val rec = Recorder()
        var systemLaunched = 0
        var keyboardFocused = 0
        var created: FakeRecognizer? = null
        val coordinator = VoiceInputCoordinator(
            availability = { availability },
            createOfflineRecognizer = { recognizer().also { created = it } },
            launchSystemRecognizer = { systemLaunched++; systemLaunches },
            focusKeyboard = { keyboardFocused++ },
            listener = rec,
        )
    }

    private val ready = EngineAvailability(modelInstalled = true, micPermissionGranted = true, systemRecognizerAvailable = true)

    @Test fun offlineResultIsNormalisedAndDelivered() {
        val h = Harness(ready)
        h.coordinator.begin()
        assertTrue(h.coordinator.isListening)
        h.created!!.callback!!.onPartial("âm lượng bốn")
        h.created!!.callback!!.onFinal("âm lượng bốn mươi [unk]")
        assertEquals(listOf("âm lượng 40" to VoiceEngine.VOSK_OFFLINE), h.rec.results)
        assertFalse(h.coordinator.isListening)
        assertEquals(0, h.rec.events.count { it.startsWith("fallback") })
    }

    @Test fun usesSystemRecognizerWhenModelMissing() {
        val h = Harness(ready.copy(modelInstalled = false))
        h.coordinator.begin()
        assertEquals(1, h.systemLaunched)
        h.coordinator.onSystemResult("bài tiếp")
        assertEquals(listOf("bài tiếp" to VoiceEngine.SYSTEM), h.rec.results)
    }

    @Test fun modelLoadFailureFallsBackToSystem() {
        val h = Harness(ready, recognizer = { throw IllegalStateException("corrupt model") })
        h.coordinator.begin()
        assertEquals(1, h.systemLaunched)
        assertTrue(h.rec.events.contains("fallback:VOSK_OFFLINE->SYSTEM"))
    }

    @Test fun audioErrorFallsBackToSystem() {
        val h = Harness(ready)
        h.coordinator.begin()
        h.created!!.callback!!.onError(VoiceError.Audio("busy"))
        assertEquals(1, h.systemLaunched)
        assertTrue(h.rec.events.contains("fallback:VOSK_OFFLINE->SYSTEM"))
    }

    @Test fun startFailureFallsBackToSystem() {
        val h = Harness(ready, recognizer = { FakeRecognizer(failOnStart = IllegalStateException("mic busy")) })
        h.coordinator.begin()
        assertEquals(1, h.systemLaunched)
    }

    @Test fun noSpeechDoesNotOpenAnotherEngine() {
        val h = Harness(ready)
        h.coordinator.begin()
        h.created!!.callback!!.onError(VoiceError.NoSpeech)
        assertEquals(0, h.systemLaunched)
        assertTrue(h.rec.results.isEmpty())
        assertFalse(h.coordinator.isListening)
    }

    @Test fun systemUnavailableFallsBackToKeyboard() {
        val h = Harness(ready.copy(modelInstalled = false), systemLaunches = false)
        h.coordinator.begin()
        assertEquals(1, h.keyboardFocused)
        assertTrue(h.rec.events.contains("fallback:SYSTEM->KEYBOARD"))
        assertFalse(h.coordinator.isListening)
    }

    @Test fun keyboardOnlyWhenNothingAvailable() {
        val h = Harness(EngineAvailability(false, false, false))
        h.coordinator.begin()
        assertEquals(1, h.keyboardFocused)
        assertEquals(0, h.systemLaunched)
    }

    @Test fun endStopsOfflineAndCancelDropsIt() {
        val h = Harness(ready)
        h.coordinator.begin()
        h.coordinator.end()
        assertTrue(h.created!!.stopped)
        h.coordinator.cancel()
        assertTrue(h.created!!.cancelled)
        assertFalse(h.coordinator.isListening)
        assertTrue(h.rec.results.isEmpty())
    }

    @Test fun emptySystemResultIsIgnored() {
        val h = Harness(ready.copy(modelInstalled = false))
        h.coordinator.begin()
        h.coordinator.onSystemResult("  ")
        assertTrue(h.rec.results.isEmpty())
    }
}
