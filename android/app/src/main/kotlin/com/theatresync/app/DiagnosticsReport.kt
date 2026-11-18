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
    enum class Kind { BLUETOOTH, WIRED, SPEAKER }

    /** A friendly view of where sound can go. */
    data class Output(val name: String, val kind: Kind)

    /** Headphones first, then the phone speaker. Hides earpiece, telephony and other internal outputs. */
    @android.annotation.SuppressLint("InlinedApi")
    fun friendlyOutputs(context: Context): List<Output> {
        val am = context.getSystemService(AudioManager::class.java)
        val canReadNames = bluetoothNamesAllowed(context)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Kind.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> Kind.WIRED
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
                else -> null
            } ?: return@mapNotNull null
            val raw = d.productName?.toString()?.trim().orEmpty()
            val name = when {
                kind == Kind.SPEAKER -> context.getString(R.string.phone_speaker)
                kind == Kind.BLUETOOTH && !canReadNames -> context.getString(R.string.bluetooth_headphones)
                raw.isBlank() || raw == Build.MODEL ->
                    context.getString(if (kind == Kind.WIRED) R.string.wired_headphones else R.string.bluetooth_headphones)
                else -> raw
            }
            Output(name, kind)
        }.distinct().sortedBy { it.kind.ordinal }
    }

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
            appendLine("All local addresses: ${NetworkInfo.candidates(context).joinToString { "${it.address.hostAddress}/${it.prefixLength}" }.ifEmpty { "none" }}")
            val session = (context.applicationContext as? TheatreSyncApplication)?.session
            session?.lastDiagnostics?.let { d ->
                appendLine()
                appendLine("Last movie sent to TV")
                appendLine("  URL: ${d.movieUrl}")
                appendLine("  Phone IP given to TV: ${d.phoneIp}")
                appendLine("  TV IP: ${d.tvIp ?: "unknown"}")
                appendLine("  TV requests for the movie: ${session.tvMovieRequests}")
            }
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
