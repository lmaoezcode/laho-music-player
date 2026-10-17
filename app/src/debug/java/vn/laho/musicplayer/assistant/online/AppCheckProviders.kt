// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: App Check debug provider. On first use it logs
 * "DebugAppCheckProvider: Enter this debug secret into the allow list…" — add that token in
 * Firebase Console → Security → App Check → Apps → ⋮ → Manage debug tokens.
 */
internal object AppCheckProviders {
    const val NAME = "debug"
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
