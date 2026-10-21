// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputPolicyTest {
    private fun mode(usb: Boolean, dsp: Boolean, sysBp: Boolean = false, primary: Boolean = true) =
        AudioOutputPolicy.resolve(usb, dsp, sysBp, primary)

    @Test fun defaultsToStandard() = assertEquals(AudioOutputMode.STANDARD, mode(false, false))

    @Test fun dspWhenEnabled() = assertEquals(AudioOutputMode.DSP_FLOAT, mode(false, true))

    @Test fun usbBeatsDsp() = assertEquals(AudioOutputMode.USB_BIT_PERFECT, mode(true, true))

    @Test fun systemBitPerfectDisablesDsp() = assertEquals(AudioOutputMode.STANDARD, mode(false, true, sysBp = true))

    @Test fun secondaryPlayerNeverUsesUsbOrDspWhileUsbIsOn() {
        assertEquals(AudioOutputMode.STANDARD, mode(true, false, primary = false))
        assertEquals(AudioOutputMode.STANDARD, mode(true, true, primary = false))
    }

    @Test fun secondaryPlayerStillUsesDspWithoutUsb() =
        assertEquals(AudioOutputMode.DSP_FLOAT, mode(false, true, primary = false))
}
