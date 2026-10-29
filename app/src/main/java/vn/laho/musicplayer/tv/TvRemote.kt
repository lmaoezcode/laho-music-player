// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.tv

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import vn.laho.musicplayer.assistant.AssistantActivity

/**
 * Remote-control handling for MainActivity on TV. Hooked from `onKeyDown` / `onKeyUp` /
 * `onSearchRequested`; returns false on phones so upstream behaviour is untouched.
 */
object TvRemote {

    private const val TAG = "LahoTv"

    /** Playback hooks; MainActivity forwards them to MusicViewModel. */
    class Controls(
        val isPlaying: () -> Boolean,
        val togglePlayPause: () -> Unit,
        val next: () -> Unit,
        val previous: () -> Unit,
    )

    fun onKeyDown(activity: Activity, keyCode: Int, event: KeyEvent, controls: Controls): Boolean {
        if (!TvDevice.isTv(activity)) return false
        if (TvKeys.isAssistantKey(keyCode)) {
            if (event.repeatCount == 0) openAssistant(activity)
            return true
        }
        val action = TvKeys.mediaAction(keyCode) ?: return false
        if (event.repeatCount > 0) return true
        Log.d(TAG, "media key $keyCode -> $action")
        when (action) {
            TvMediaAction.NEXT -> controls.next()
            TvMediaAction.PREVIOUS -> controls.previous()
            else -> if (TvKeys.needsToggle(action, controls.isPlaying())) controls.togglePlayPause()
        }
        return true
    }

    /** Consumes the matching key-up so the platform does not also start its own search. */
    fun onKeyUp(activity: Activity, keyCode: Int): Boolean =
        TvDevice.isTv(activity) && (TvKeys.isAssistantKey(keyCode) || TvKeys.mediaAction(keyCode) != null)

    /** `Activity.onSearchRequested` hook: on TV the search action opens the LaHo assistant. */
    fun onSearchRequested(activity: Activity): Boolean {
        if (!TvDevice.isTv(activity)) return false
        openAssistant(activity)
        return true
    }

    fun openAssistant(activity: Activity, startVoice: Boolean = true) {
        val intent = Intent(activity, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_START_VOICE, startVoice)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
    }
}
