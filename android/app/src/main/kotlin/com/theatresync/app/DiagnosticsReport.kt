package com.theatresync.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.theatresync.core.PROTOCOL_VERSION

/** Text report used by the "Check headphones" screen and physical test HW-01. */
object DiagnosticsReport {
    fun bluetoothNamesAllowed(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("InlinedApi")
    private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER

    fun build(context: Context): String {
        val am = context.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val canReadNames = bluetoothNamesAllowed(context)
        return buildString {
            appendLine("TheatreSync ${BuildConfig.VERSION_NAME} · protocol v$PROTOCOL_VERSION")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Wi-Fi address: ${NetworkInfo.localIpv4(context) ?: "not on Wi-Fi"}")
            appendLine()
            appendLine("Bluetooth names visible: $canReadNames")
            appendLine()
            appendLine("Audio outputs (${outputs.size})")
            for (d in outputs) {
                val name = if (canReadNames || !isBluetooth(d.type)) d.productName else "(allow Bluetooth to see name)"
                appendLine("  • $name")
                appendLine("    type=${d.type} route=${AudioOutputs.routeFor(d.type)} id=${d.id}")
            }
            appendLine()
            appendLine("Output sample rate: ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz")
            appendLine("Output frames/buffer: ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        }
    }
}
