// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.signal

import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import com.decent.usbaudio.UsbRateRejection
import com.decent.usbaudio.media3.UsbEngine
import com.decent.usbaudio.media3.UsbSignalPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.dsp.FilterType
import vn.laho.musicplayer.dsp.PeqBand
import vn.laho.musicplayer.dsp.PeqConfig

class LahoSignalPathTest {

    private val usb96 = UsbSignalPath(
        active = true,
        dacName = "Sonata BHD",
        sampleRateHz = 96_000,
        dacReportedRateHz = 96_000,
        sourceBitDepth = 24,
        usbBitDepth = 32,
        engine = UsbEngine.NATIVE_FLAC,
        bitPerfect = true,
        supportedRates = listOf(44_100, 48_000, 96_000),
    )

    // ── bit-perfect rule ──

    @Test
    fun bitPerfectNeedsMatchingRateAndEnoughBits() {
        assertTrue(UsbSignalPath.isBitPerfect(true, 96_000, 96_000, 24, 32))
        assertTrue(UsbSignalPath.isBitPerfect(true, 192_000, -1, 24, 24))      // no GET_CUR answer
        assertFalse(UsbSignalPath.isBitPerfect(false, 96_000, 96_000, 24, 32)) // not streaming
        assertFalse(UsbSignalPath.isBitPerfect(true, 96_000, 48_000, 24, 32))  // DAC at another rate
        assertFalse(UsbSignalPath.isBitPerfect(true, 96_000, 96_000, 32, 24))  // truncation
        assertFalse(UsbSignalPath.isBitPerfect(true, 96_000, 96_000, 0, 32))   // unknown source
        assertFalse(UsbSignalPath.isBitPerfect(true, 0, 0, 16, 32))
    }

    // ── USB mapping ──

    @Test
    fun activeUsbPathMapsEveryField() {
        val info = LahoSignalPath.fromUsb(usb96)!!
        assertEquals(SignalPathInfo.Mode.USB_DRIVER, info.mode)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.sourceBitDepth)
        assertEquals(32, info.outputBitDepth)
        assertEquals(SignalPathInfo.Engine.NATIVE_FLAC, info.engine)
        assertEquals("Sonata BHD", info.dacName)
        assertTrue(info.isBitPerfect)
        assertEquals(SignalPathInfo.Badge.BIT_PERFECT, info.badge)
        assertEquals(
            SignalPathInfo.Badge.USB,
            LahoSignalPath.fromUsb(usb96.copy(bitPerfect = false, engine = UsbEngine.EXOPLAYER))!!.badge
        )
    }

    @Test
    fun inactiveUsbPathIsNull() {
        assertNull(LahoSignalPath.fromUsb(UsbSignalPath.INACTIVE))
    }

    @Test
    fun fallbackIsReported() {
        val path = UsbSignalPath(
            active = false,
            dacName = "Sonata BHD",
            fallbackRateHz = 352_800,
            fallbackReason = UsbRateRejection.RATE_NOT_SUPPORTED,
        )
        val info = LahoSignalPath.fromUsb(path)!!
        assertEquals(SignalPathInfo.Mode.USB_FALLBACK, info.mode)
        assertEquals(352_800, info.fallbackRateHz)
        assertEquals(SignalPathInfo.FallbackReason.RATE_NOT_SUPPORTED, info.fallbackReason)
        assertFalse(info.isBitPerfect)
        assertEquals(SignalPathInfo.Badge.NONE, info.badge)
    }

    @Test
    fun everyRejectionHasAReason() {
        for (r in UsbRateRejection.values()) {
            assertEquals(r.name, LahoSignalPath.map(r)!!.name)
        }
        assertNull(LahoSignalPath.map(null))
    }

    // ── DSP ──

    @Test
    fun dspShowsFloatAndBandCount() {
        val bands = List(10) { PeqBand(FilterType.PEAKING, 100.0 * (it + 1), gainDb = 1.0) } +
            PeqBand(FilterType.PEAKING, 5_000.0, enabled = false)
        val info = LahoSignalPath.fromDsp(PeqConfig(enabled = true, preampDb = -3.0, bands = bands))
        assertEquals(SignalPathInfo.Mode.LAHO_DSP, info.mode)
        assertEquals(32, info.outputBitDepth)
        assertEquals(SignalPathInfo.Engine.FLOAT_DSP, info.engine)
        assertEquals(10, info.peqBandCount)
        assertEquals(SignalPathInfo.Badge.PEQ, info.badge)

        val off = LahoSignalPath.fromDsp(PeqConfig.FLAT)
        assertFalse(off.peqEnabled)
        assertEquals(0, off.peqBandCount)
        assertEquals(SignalPathInfo.Badge.DSP, off.badge)
    }

    // ── merge into Rhythm's PlaybackQualityInfo ──

    private val speakerQuality = PlaybackQualityInfo(
        outputDeviceName = "Device Speaker",
        outputDeviceType = "Built-in Speaker",
        isResampled = true,
        resamplingDescription = "Resampled to 48 kHz",
        outputQualityStatus = "Resampled to 48 kHz • Capped by Android audio HAL",
    )

    @Test
    fun noLahoPathLeavesRhythmTelemetryUntouched() {
        assertSame(speakerQuality, LahoSignalPath.applyTo(speakerQuality, null))
    }

    @Test
    fun usbDriverOverridesTheSpeakerReport() {
        val q = LahoSignalPath.applyTo(speakerQuality, LahoSignalPath.fromUsb(usb96))
        assertTrue(q.isBitPerfect)
        assertFalse(q.isResampled)
        assertFalse(q.isBluetooth)
        assertEquals("Sonata BHD", q.outputDeviceName)
        assertTrue(q.outputDeviceType.contains("LaHo"))
        assertTrue(q.outputQualityStatus.startsWith("Bit-Perfect"))
        assertEquals(SignalPathInfo.Mode.USB_DRIVER, q.lahoSignalPath!!.mode)
    }

    @Test
    fun dspAndFallbackOnlyAttachThePath() {
        val dsp = LahoSignalPath.fromDsp(PeqConfig.FLAT)
        val q = LahoSignalPath.applyTo(speakerQuality.copy(isBitPerfect = true), dsp)
        assertFalse(q.isBitPerfect)
        assertEquals("Device Speaker", q.outputDeviceName)
        assertEquals(dsp, q.lahoSignalPath)
    }
}
