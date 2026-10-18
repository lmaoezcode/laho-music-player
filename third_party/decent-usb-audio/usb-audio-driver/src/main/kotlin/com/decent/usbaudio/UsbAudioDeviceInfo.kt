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
    val bestBitDepth: Int
)
