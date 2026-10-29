// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

/** The mutually exclusive ways LaHo can route decoded audio to the output. */
enum class AudioOutputMode {
    /** Rhythm's normal pipeline (system mixer, optional system effects). */
    STANDARD,

    /** 32-bit float pipeline with LaHo's parametric EQ ([vn.laho.musicplayer.dsp]). */
    DSP_FLOAT,

    /** Userspace USB DAC driver streaming unmodified samples ([vn.laho.musicplayer.usb]). */
    USB_BIT_PERFECT,
}

/**
 * Single place deciding which [AudioOutputMode] applies. Priority: USB driver, then DSP, then
 * standard. Replaces the conditions that used to be spread over `RhythmPlayerEngine`.
 */
object AudioOutputPolicy {

    /**
     * @param usbRequested the "USB DAC driver" setting is on (and supported by this Android version)
     * @param dspRequested the "LaHo DSP" setting is on
     * @param systemBitPerfect Rhythm's own bit-perfect routing ("app" mode), where no DSP may run
     * @param primary true for the main player; the crossfade helper player never streams to the DAC
     */
    fun resolve(
        usbRequested: Boolean,
        dspRequested: Boolean,
        systemBitPerfect: Boolean,
        primary: Boolean,
    ): AudioOutputMode = when {
        usbRequested && primary -> AudioOutputMode.USB_BIT_PERFECT
        // A non-primary player must not take the DSP path while USB is on either: it would
        // sound different from the primary one during a crossfade.
        usbRequested -> AudioOutputMode.STANDARD
        dspRequested && !systemBitPerfect -> AudioOutputMode.DSP_FLOAT
        else -> AudioOutputMode.STANDARD
    }
}
