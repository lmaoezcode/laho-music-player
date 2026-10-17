// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck

/**
 * Lazily initialises Firebase and installs the App Check provider before the first Gemini
 * call. The provider comes from the build type source set: debug builds use the debug
 * provider (register its token in the console), release builds use Play Integrity.
 */
object LahoFirebase {

    private const val TAG = "LahoFirebase"
    private var ready: Boolean? = null

    /** Returns false when the build has no google-services.json (e.g. release/F-Droid). */
    @Synchronized
    fun ensure(context: Context): Boolean {
        ready?.let { return it }
        val ok = try {
            val app = FirebaseApp.initializeApp(context.applicationContext)
            if (app == null) {
                Log.w(TAG, "No Firebase config in this build; online assistant disabled")
                false
            } else {
                Firebase.appCheck.installAppCheckProviderFactory(AppCheckProviders.factory())
                Log.i(TAG, "Firebase ready, App Check provider: ${AppCheckProviders.NAME}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed", e)
            false
        }
        ready = ok
        return ok
    }
}
