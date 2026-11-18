package com.theatresync.app

import android.Manifest
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theatresync.app.MovieSession.Phase

class MainActivity : ComponentActivity() {
    private val session: MovieSession get() = (application as TheatreSyncApplication).session
    private val diagnostics = mutableStateOf("")
    private lateinit var audioManager: AudioManager

    private val pickMovie = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            session.chooseMovie(uri)
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        diagnostics.value = DiagnosticsReport.build(this)
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { diagnostics.value = DiagnosticsReport.build(this@MainActivity) }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { diagnostics.value = DiagnosticsReport.build(this@MainActivity) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Color.Black)) {
                Surface(Modifier.fillMaxSize()) {
                    val ui by session.ui.collectAsState()
                    var showCheck by rememberSaveable { mutableStateOf(false) }
                    when {
                        showCheck -> DiagnosticsScreen(
                            report = diagnostics.value,
                            onAllow = { askPermissions.launch(runtimePermissions()) },
                            onBack = { showCheck = false },
                        )
                        ui.phase == Phase.SETUP -> SetupScreen(
                            ui = ui,
                            onChooseMovie = { pickMovie.launch(arrayOf("video/*")) },
                            onConnectTv = session::connectTv,
                            onDisconnectTv = session::disconnectTv,
                            onStart = {
                                askPermissions.launch(runtimePermissions())
                                session.startMovie()
                            },
                            onCheckHeadphones = {
                                diagnostics.value = DiagnosticsReport.build(this@MainActivity)
                                showCheck = true
                            },
                            onDismiss = session::dismissMessage,
                        )
                        else -> PlayingScreen(
                            ui = ui,
                            onPlayPause = session::togglePause,
                            onSkip = session::skip,
                            onSoundEarly = { session.adjustSoundDelay(+STEP_MS) },
                            onSoundLate = { session.adjustSoundDelay(-STEP_MS) },
                            onHeadphonesHere = session::setHeadphonesHere,
                            onMuteTv = session::setMuteTv,
                            onStop = session::stopMovie,
                            onDismiss = session::dismissMessage,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.startTvSearch()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        // Keep the screen on while the app is open, so controls are ready in the dark.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        session.stopTvSearch()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    private fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        const val STEP_MS = 20
    }
}

private val Gold = Color(0xFFF5C542)

@Composable
private fun Message(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number  $title", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SetupScreen(
    ui: MovieSession.Ui,
    onChooseMovie: () -> Unit,
    onConnectTv: (String) -> Unit,
    onDisconnectTv: () -> Unit,
    onStart: () -> Unit,
    onCheckHeadphones: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Gold, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.setup_intro))
        Message(ui.message, onDismiss)

        Step(1, stringResource(R.string.step_movie)) {
            if (ui.movieTitle != null) {
                Text("🎬  ${ui.movieTitle}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onChooseMovie) { Text(stringResource(R.string.change)) }
            } else {
                Button(onClick = onChooseMovie, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.choose_movie), fontSize = 18.sp) }
                Text(stringResource(R.string.movie_hint), style = MaterialTheme.typography.bodySmall)
            }
        }

        Step(2, stringResource(R.string.step_tv)) {
            when {
                !ui.castAvailable -> Text(stringResource(R.string.msg_no_cast))
                ui.connectedTv != null -> {
                    Text("📺  ${stringResource(R.string.connected_to, ui.connectedTv)}", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onDisconnectTv) { Text(stringResource(R.string.disconnect)) }
                }
                ui.tvs.isEmpty() -> Text(stringResource(R.string.looking_for_tv))
                else -> ui.tvs.forEach { tv ->
                    Button(onClick = { onConnectTv(tv.id) }, Modifier.fillMaxWidth().height(56.dp)) { Text("📺  ${tv.name}", fontSize = 18.sp) }
                }
            }
        }

        Step(3, stringResource(R.string.step_play)) {
            Text(stringResource(R.string.headphones_hint), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onStart,
                enabled = ui.canStart,
                modifier = Modifier.fillMaxWidth().height(64.dp).testTag("start"),
            ) { Text("▶  ${stringResource(R.string.start_movie)}", fontSize = 20.sp) }
        }

        TextButton(onClick = onCheckHeadphones) { Text(stringResource(R.string.check_headphones)) }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PlayingScreen(
    ui: MovieSession.Ui,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit,
    onSoundEarly: () -> Unit,
    onSoundLate: () -> Unit,
    onHeadphonesHere: (Boolean) -> Unit,
    onMuteTv: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(ui.movieTitle ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            when (ui.phase) {
                Phase.STARTING -> stringResource(R.string.status_starting, ui.connectedTv ?: "TV")
                Phase.PAUSED -> stringResource(R.string.status_paused)
                Phase.ENDED -> stringResource(R.string.status_ended)
                else -> stringResource(R.string.status_playing, ui.connectedTv ?: "TV")
            },
            color = Gold,
        )
        Message(ui.message, onDismiss)

        val progress = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onSkip(-10_000) }) { Text("⟲ 10s") }
            Button(onClick = onPlayPause, modifier = Modifier.size(96.dp)) {
                Text(if (ui.phase == Phase.PLAYING) "❚❚" else "▶", fontSize = 32.sp)
            }
            OutlinedButton(onClick = { onSkip(10_000) }) { Text("10s ⟳") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lips_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.lips_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSoundEarly, Modifier.weight(1f)) { Text(stringResource(R.string.sound_early)) }
                    Button(onClick = onSoundLate, Modifier.weight(1f)) { Text(stringResource(R.string.sound_late)) }
                }
                Text(stringResource(R.string.sound_delay_value, ui.soundDelayMs), style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(stringResource(R.string.sound_here, ui.headphoneName ?: stringResource(R.string.this_phone)), ui.headphonesHere, onHeadphonesHere)
                SwitchRow(stringResource(R.string.mute_tv), ui.muteTv, onMuteTv)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.friends_title, ui.friends), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.friends_hint))
                ui.joinAddress?.let { Text(stringResource(R.string.friends_address, it), fontFamily = FontFamily.Monospace) }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.stop_movie)) }
        ui.driftMs?.let { Text(stringResource(R.string.sync_debug, it), style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DiagnosticsScreen(report: String, onAllow: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.check_headphones), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.check_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAllow) { Text(stringResource(R.string.allow_bluetooth)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        Card {
            Text(report, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
