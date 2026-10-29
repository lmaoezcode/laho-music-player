// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

/** What the microphone button should do after a press or release. */
enum class TalkAction { NONE, START, STOP }

/**
 * Hold-to-talk and tap-to-toggle on one button.
 *
 * - Press while idle → START at once. Releasing after at least [holdThresholdMs] → STOP
 *   (hold-to-talk); releasing sooner leaves listening on (tap-to-toggle).
 * - Press while already listening → STOP on release (second tap).
 */
class PushToTalk(private val holdThresholdMs: Long = 450L) {
    private var downAt = -1L
    private var wasListening = false

    fun onPress(nowMs: Long, listening: Boolean): TalkAction {
        downAt = nowMs
        wasListening = listening
        return if (listening) TalkAction.NONE else TalkAction.START
    }

    fun onRelease(nowMs: Long): TalkAction {
        if (downAt < 0) return TalkAction.NONE
        val held = nowMs - downAt
        downAt = -1
        return when {
            wasListening -> TalkAction.STOP
            held >= holdThresholdMs -> TalkAction.STOP
            else -> TalkAction.NONE
        }
    }

    /** Pointer cancelled (finger dragged away): behave like a release. */
    fun onCancel(nowMs: Long): TalkAction = onRelease(nowMs)
}
