package com.theatresync.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.theatresync.core.PROTOCOL_VERSION

/**
 * Milestone 0: hardware diagnostics. Shows what the phone reports about its
 * audio outputs and permissions, so the first physical test can confirm the
 * Sony WH-1000XM4 is visible to the app before any capture code exists.
 */
class MainActivity : ComponentActivity() {
    private val report = mutableStateOf("")
    private lateinit var audioManager: AudioManager

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    DiagnosticsScreen(
                        report = report.value,
                        onGrant = {
                            permissionLauncher.launch(runtimePermissions())
                        },
                        onRefresh = ::refresh,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refresh()
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    private fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    /** BLUETOOTH_CONNECT only exists on Android 12+; earlier versions grant it implicitly. */
    private fun bluetoothNamesAllowed() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val canReadNames = bluetoothNamesAllowed()
        report.value = buildString {
            appendLine("TheatreSync ${BuildConfig.VERSION_NAME} · protocol v$PROTOCOL_VERSION")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("AudioPlaybackCapture API available: yes (minSdk 29)")
            appendLine()
            appendLine("Permissions")
            appendLine("  RECORD_AUDIO:      ${granted(Manifest.permission.RECORD_AUDIO)}")
            appendLine("  Bluetooth names:   $canReadNames")
            appendLine()
            appendLine("Audio outputs (${outputs.size})")
            for (d in outputs) {
                val name = if (canReadNames || !isBluetooth(d.type)) d.productName else "(grant Bluetooth to see name)"
                appendLine("  • $name")
                appendLine("    type=${d.type} route=${AudioOutputs.routeFor(d.type)} id=${d.id}")
            }
            appendLine()
            appendLine("Output sample rate: ${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz")
            appendLine("Output frames/buffer: ${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        }
    }

    @android.annotation.SuppressLint("InlinedApi")
    private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER
}

@Composable
private fun DiagnosticsScreen(report: String, onGrant: () -> Unit, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TheatreSync — diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Take a screenshot of this screen and share it back in Claude Code.")
        Button(onClick = onGrant) { Text("Grant permissions") }
        Button(onClick = onRefresh) { Text("Refresh") }
        Card {
            Text(report, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
