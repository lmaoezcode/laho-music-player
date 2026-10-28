// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.tv

import android.view.KeyEvent

/** What a remote-control media key should do to playback. */
enum class TvMediaAction { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }

/** Pure key-code mapping for TV remotes (unit-tested). */
object TvKeys {

    /**
     * Keys that should open the LaHo assistant. Remotes that send [KeyEvent.KEYCODE_SEARCH] reach
     * the app; ASSIST / VOICE_ASSIST are usually taken by the system, but are handled if delivered.
     */
    fun isAssistantKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_ASSIST -> true
        else -> false
    }

    /**
     * Media keys LaHo handles itself while it is in the foreground, so the remote controls LaHo
     * even when another app last held the system media-button session. Other media keys
     * (stop, fast-forward, rewind) fall through to the MediaSession as before.
     */
    fun mediaAction(keyCode: Int): TvMediaAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> TvMediaAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvMediaAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvMediaAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> TvMediaAction.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvMediaAction.PREVIOUS
        else -> null
    }

    /** Whether [action] needs a play/pause toggle given the current playing state. */
    fun needsToggle(action: TvMediaAction, isPlaying: Boolean): Boolean = when (action) {
        TvMediaAction.PLAY_PAUSE -> true
        TvMediaAction.PLAY -> !isPlaying
        TvMediaAction.PAUSE -> isPlaying
        TvMediaAction.NEXT, TvMediaAction.PREVIOUS -> false
    }
}
