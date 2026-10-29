// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

/** What can run right now. */
data class EngineAvailability(
    val modelInstalled: Boolean,
    val micPermissionGranted: Boolean,
    val systemRecognizerAvailable: Boolean,
    /** User turned the offline engine off (kept for tests/settings; default on). */
    val offlinePreferred: Boolean = true,
)

/** Pure engine choice: Vosk offline → system recogniser → keyboard. */
object EngineSelector {

    /** Engines to try in order; [VoiceEngine.KEYBOARD] is always last and always works. */
    fun order(a: EngineAvailability): List<VoiceEngine> = buildList {
        if (a.offlinePreferred && a.modelInstalled && a.micPermissionGranted) add(VoiceEngine.VOSK_OFFLINE)
        if (a.systemRecognizerAvailable) add(VoiceEngine.SYSTEM)
        add(VoiceEngine.KEYBOARD)
    }

    fun select(a: EngineAvailability): VoiceEngine = order(a).first()

    /** The engine to fall back to after [failed] could not run, or null when only the keyboard is left. */
    fun fallbackAfter(failed: VoiceEngine, a: EngineAvailability): VoiceEngine? {
        val list = order(a)
        val idx = list.indexOf(failed)
        val next = if (idx >= 0) list.getOrNull(idx + 1) else list.firstOrNull { it.ordinal > failed.ordinal }
        return next
    }
}
