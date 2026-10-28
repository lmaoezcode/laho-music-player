// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.tv

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.interaction.FocusInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHelpersTest {

    @Test
    fun `television ui mode or leanback feature means TV`() {
        assertTrue(TvDevice.isTv(Configuration.UI_MODE_TYPE_TELEVISION, hasLeanback = false))
        assertTrue(TvDevice.isTv(Configuration.UI_MODE_TYPE_NORMAL, hasLeanback = true))
        assertFalse(TvDevice.isTv(Configuration.UI_MODE_TYPE_NORMAL, hasLeanback = false))
        assertFalse(TvDevice.isTv(Configuration.UI_MODE_TYPE_CAR, hasLeanback = false))
    }

    @Test
    fun `search and assistant keys open the assistant`() {
        assertTrue(TvKeys.isAssistantKey(KeyEvent.KEYCODE_SEARCH))
        assertTrue(TvKeys.isAssistantKey(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertTrue(TvKeys.isAssistantKey(KeyEvent.KEYCODE_ASSIST))
        assertFalse(TvKeys.isAssistantKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(TvKeys.isAssistantKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `media keys map to playback actions`() {
        assertEquals(TvMediaAction.PLAY_PAUSE, TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(TvMediaAction.PLAY_PAUSE, TvKeys.mediaAction(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(TvMediaAction.PLAY, TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(TvMediaAction.PAUSE, TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(TvMediaAction.NEXT, TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(TvMediaAction.PREVIOUS, TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // Left to the MediaSession / focus system
        assertNull(TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_STOP))
        assertNull(TvKeys.mediaAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertNull(TvKeys.mediaAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(TvKeys.mediaAction(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `play and pause keys only toggle when they change state`() {
        assertTrue(TvKeys.needsToggle(TvMediaAction.PLAY_PAUSE, isPlaying = true))
        assertTrue(TvKeys.needsToggle(TvMediaAction.PLAY_PAUSE, isPlaying = false))
        assertTrue(TvKeys.needsToggle(TvMediaAction.PLAY, isPlaying = false))
        assertFalse(TvKeys.needsToggle(TvMediaAction.PLAY, isPlaying = true))
        assertTrue(TvKeys.needsToggle(TvMediaAction.PAUSE, isPlaying = true))
        assertFalse(TvKeys.needsToggle(TvMediaAction.PAUSE, isPlaying = false))
        assertFalse(TvKeys.needsToggle(TvMediaAction.NEXT, isPlaying = true))
    }

    @Test
    fun `focus tracker follows focus and unfocus pairs`() {
        val tracker = FocusInteractionTracker()
        assertFalse(tracker.isFocused)
        val focus = FocusInteraction.Focus()
        assertTrue(tracker.onInteraction(focus))
        assertTrue(tracker.isFocused)
        assertFalse(tracker.onInteraction("not a focus interaction"))
        assertTrue(tracker.onInteraction(FocusInteraction.Unfocus(focus)))
        assertFalse(tracker.isFocused)
        // An unfocus for an unknown focus changes nothing
        assertFalse(tracker.onInteraction(FocusInteraction.Unfocus(FocusInteraction.Focus())))
    }
}
