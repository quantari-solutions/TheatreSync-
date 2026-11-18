package com.theatresync.app

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import com.theatresync.core.OutputRoute

/** Maps Android audio device types onto protocol [OutputRoute]s. Pure function so it is unit-testable. */
object AudioOutputs {
    // Comparing against newer TYPE_* ints is safe on old APIs: they simply never match.
    @SuppressLint("InlinedApi")
    fun routeFor(deviceType: Int): OutputRoute = when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputRoute.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRoute.BLUETOOTH_LE_AUDIO
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputRoute.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRoute.SPEAKER
        else -> OutputRoute.UNKNOWN
    }
}
