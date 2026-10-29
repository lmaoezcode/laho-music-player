// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Detects whether LaHo runs on an Android TV / leanback device. Every TV-only tweak in the app
 * is gated by this, so the phone UI stays exactly as upstream Rhythm draws it.
 */
object TvDevice {

    @Volatile
    private var cached: Boolean? = null

    /** Pure decision, unit-tested: TV ui mode or the leanback feature means "TV". */
    fun isTv(uiModeType: Int, hasLeanback: Boolean): Boolean =
        uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback

    fun isTv(context: Context): Boolean {
        cached?.let { return it }
        val app = context.applicationContext
        val uiMode = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            ?: Configuration.UI_MODE_TYPE_UNDEFINED
        val leanback = app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return (isTv(uiMode, leanback) || debugForcedTv()).also { cached = it }
    }

    /**
     * Debug builds only: `adb shell setprop debug.laho.force_tv 1` (then restart the app) turns on
     * the TV behaviour on a phone/tablet emulator, for testing without an Android TV image.
     */
    private fun debugForcedTv(): Boolean {
        if (!chromahub.rhythm.app.BuildConfig.DEBUG) return false
        return runCatching {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            get.invoke(null, "debug.laho.force_tv") == "1"
        }.getOrDefault(false)
    }
}

/** Composable shortcut for [TvDevice.isTv]. */
@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { TvDevice.isTv(context) }
}
