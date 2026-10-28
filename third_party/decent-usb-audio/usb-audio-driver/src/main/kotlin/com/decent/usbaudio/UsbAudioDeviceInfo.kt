package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection

/**
 * Information about an opened USB audio device, ready for native I/O.
 */
data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    /** LaHo: raw bInterval of the ISO OUT endpoint (high-speed: 2^(bInterval-1) microframes per packet). */
    val endpointOutInterval: Int = 1,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,
    /** LaHo: clock source GET RANGE (CS_SAM_FREQ_CONTROL); null if the DAC did not answer. */
    val supportedSampleRates: List<SampleRateRange>? = null,
    /** LaHo: per-alt format and ISO OUT endpoint (wMaxPacketSize, bInterval) from descriptors. */
    val altFormats: List<UsbAltFormat> = emptyList()
) {
    /** LaHo: format of [altSetting], or null if the descriptors did not describe it. */
    fun altFormat(altSetting: Int): UsbAltFormat? = altFormats.firstOrNull { it.altSetting == altSetting }
}
