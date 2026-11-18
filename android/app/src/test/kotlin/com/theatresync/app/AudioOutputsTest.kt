package com.theatresync.app

import android.media.AudioDeviceInfo
import com.theatresync.core.OutputRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputsTest {
    @Test fun a2dpIsBluetooth() = assertEquals(OutputRoute.BLUETOOTH_A2DP, AudioOutputs.routeFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    @Test fun speaker() = assertEquals(OutputRoute.SPEAKER, AudioOutputs.routeFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    @Test fun usbIsWired() = assertEquals(OutputRoute.WIRED, AudioOutputs.routeFor(AudioDeviceInfo.TYPE_USB_HEADSET))
    @Test fun unknownFallsBack() = assertEquals(OutputRoute.UNKNOWN, AudioOutputs.routeFor(-12345))
}
