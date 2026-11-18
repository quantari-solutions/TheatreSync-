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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theatresync.app.MovieSession.Phase
import kotlin.math.abs

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        setContent {
            QuantariTheme {
                GridBackground {
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

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Brand(subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Quantari · TheatreSync")
        GradientText(stringResource(R.string.app_name), MaterialTheme.typography.displaySmall)
        Body(subtitle)
    }
}

@Composable
private fun Message(text: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        QCard(highlighted = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("!", color = Color.Black, modifier = Modifier.size(26.dp).clip(CircleShape).background(Q.Gold).padding(top = 2.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Text(text ?: "", Modifier.weight(1f), color = Q.Text, style = MaterialTheme.typography.bodyMedium)
                GhostButton(stringResource(R.string.ok), onDismiss, height = 36.dp)
            }
        }
    }
}

@Composable
internal fun SetupScreen(
    ui: MovieSession.Ui,
    onChooseMovie: () -> Unit,
    onConnectTv: (String) -> Unit,
    onDisconnectTv: () -> Unit,
    onStart: () -> Unit,
    onCheckHeadphones: () -> Unit,
    onDismiss: () -> Unit,
) = Screen {
    Brand(stringResource(R.string.setup_intro))
    Message(ui.message, onDismiss)

    QCard(highlighted = ui.movieTitle == null) {
        StepHeader(1, stringResource(R.string.step_movie), done = ui.movieTitle != null)
        if (ui.movieTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎬  ${ui.movieTitle}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                GhostButton(stringResource(R.string.change), onChooseMovie, height = 40.dp)
            }
        } else {
            GoldButton(stringResource(R.string.choose_movie), onChooseMovie, Modifier.fillMaxWidth(), leading = "🎬")
            Hint(stringResource(R.string.movie_hint))
        }
    }

    QCard(highlighted = ui.movieTitle != null && ui.connectedTv == null) {
        StepHeader(2, stringResource(R.string.step_tv), done = ui.connectedTv != null)
        when {
            !ui.castAvailable -> Body(stringResource(R.string.msg_no_cast))
            ui.connectedTv != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📺  ${stringResource(R.string.connected_to, ui.connectedTv)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                GhostButton(stringResource(R.string.disconnect), onDisconnectTv, height = 40.dp)
            }
            ui.tvs.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true)
                Hint(stringResource(R.string.looking_for_tv))
            }
            else -> ui.tvs.forEach { tv ->
                TvRow(tv.name) { onConnectTv(tv.id) }
            }
        }
    }

    QCard(highlighted = ui.canStart) {
        StepHeader(3, stringResource(R.string.step_play), done = false)
        Hint(stringResource(R.string.headphones_hint))
        GoldButton(
            stringResource(R.string.start_movie), onStart,
            Modifier.fillMaxWidth().testTag("start"),
            enabled = ui.canStart, height = 64.dp, leading = "▶",
        )
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GhostButton("🎧  " + stringResource(R.string.check_headphones), onCheckHeadphones)
    }
    Footer()
}

@Composable
private fun TvRow(name: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Q.Background.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, Q.Secondary.copy(alpha = 0.5f)), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📺", fontSize = 22.sp)
        Text(name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Text(stringResource(R.string.connect), color = Q.Primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Footer() {
    Text(
        stringResource(R.string.footer), Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        color = Q.TextFaint.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
    )
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun PlayingScreen(
    ui: MovieSession.Ui,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit,
    onSoundEarly: () -> Unit,
    onSoundLate: () -> Unit,
    onHeadphonesHere: (Boolean) -> Unit,
    onMuteTv: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) = Screen {
    Eyebrow(
        when (ui.phase) {
            Phase.STARTING -> stringResource(R.string.status_starting, ui.connectedTv ?: "TV")
            Phase.PAUSED -> stringResource(R.string.status_paused)
            Phase.ENDED -> stringResource(R.string.status_ended)
            else -> stringResource(R.string.status_playing, ui.connectedTv ?: "TV")
        },
    )
    GradientText(ui.movieTitle ?: "", MaterialTheme.typography.headlineMedium)
    Message(ui.message, onDismiss)

    QCard(highlighted = ui.phase == Phase.PLAYING) {
        val progress = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f
        GoldProgress(progress)
        Row {
            Text(formatTime(ui.positionMs), color = Q.TextMuted, style = MaterialTheme.typography.labelLarge)
            Spacer1()
            Text(if (ui.durationMs > 0) formatTime(ui.durationMs) else "--:--", color = Q.TextFaint, style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−10s", { onSkip(-10_000) }, Modifier.testTag("back10"), height = 52.dp)
            PlayButton(playing = ui.phase == Phase.PLAYING, onClick = onPlayPause)
            GhostButton("+10s", { onSkip(10_000) }, Modifier.testTag("fwd10"), height = 52.dp)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SyncPill(ui) }
    }

    QCard {
        Text(stringResource(R.string.lips_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Hint(stringResource(R.string.lips_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoldButton(stringResource(R.string.sound_early), onSoundEarly, Modifier.weight(1f).testTag("early"), height = 52.dp)
            GoldButton(stringResource(R.string.sound_late), onSoundLate, Modifier.weight(1f).testTag("late"), height = 52.dp)
        }
        Text(
            stringResource(R.string.sound_delay_value, ui.soundDelayMs), Modifier.fillMaxWidth(),
            color = Q.TextFaint, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
        )
    }

    QCard {
        SwitchRow("🎧", stringResource(R.string.sound_here, ui.headphoneName ?: stringResource(R.string.this_phone)), ui.headphonesHere, onHeadphonesHere)
        SwitchRow("🔇", stringResource(R.string.mute_tv), ui.muteTv, onMuteTv)
    }

    QCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.friends_heading), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Q.Text)
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (ui.friends > 0) Q.Gold else androidx.compose.ui.graphics.SolidColor(Q.Border)), contentAlignment = Alignment.Center) {
                Text(ui.friends.toString(), color = if (ui.friends > 0) Color.Black else Q.TextFaint, style = MaterialTheme.typography.titleMedium)
            }
        }
        Hint(stringResource(R.string.friends_hint))
        ui.joinAddress?.let {
            Text(
                stringResource(R.string.friends_address, it),
                Modifier.clip(RoundedCornerShape(10.dp)).background(Q.Background).border(BorderStroke(1.dp, Q.BorderAccent), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                fontFamily = FontFamily.Monospace, color = Q.PrimaryLight,
            )
        }
    }

    GhostButton("■  " + stringResource(R.string.stop_movie), onStop, Modifier.fillMaxWidth(), height = 52.dp)
    Footer()
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val glow = rememberInfiniteTransition(label = "glow")
    val pulse by glow.animateFloat(14f, 28f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    Box(
        Modifier.size(92.dp)
            .shadow(if (playing) pulse.dp else 14.dp, CircleShape, ambientColor = Q.Glow, spotColor = Q.Glow)
            .clip(CircleShape).background(Q.Gold)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("playpause"),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (playing) "❚❚" else "▶", color = Color.Black, fontSize = 30.sp)
    }
}

@Composable
private fun SyncPill(ui: MovieSession.Ui) {
    val drift = ui.driftMs
    when {
        !ui.headphonesHere -> StatusPill(stringResource(R.string.sync_off), Q.TextFaint)
        ui.phase == Phase.PAUSED -> StatusPill(stringResource(R.string.status_paused), Q.TextMuted)
        ui.phase == Phase.ENDED -> StatusPill(stringResource(R.string.status_ended), Q.TextMuted)
        ui.phase != Phase.PLAYING || drift == null -> StatusPill(stringResource(R.string.sync_waiting), Q.TextFaint)
        abs(drift) <= 40 -> StatusPill(stringResource(R.string.sync_ok, drift), Q.Success)
        else -> StatusPill(stringResource(R.string.sync_fixing, drift), Q.Primary, pulsing = true)
    }
}

@Composable
private fun SwitchRow(icon: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), color = Q.Text, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary,
                uncheckedThumbColor = Q.TextFaint, uncheckedTrackColor = Q.Border, uncheckedBorderColor = Q.BorderAccent,
            ),
        )
    }
}

@Composable
internal fun DiagnosticsScreen(report: String, onAllow: () -> Unit, onBack: () -> Unit) = Screen {
    Eyebrow(stringResource(R.string.app_name))
    GradientText(stringResource(R.string.check_headphones), MaterialTheme.typography.headlineMedium)
    Body(stringResource(R.string.check_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GoldButton(stringResource(R.string.allow_bluetooth), onAllow, height = 48.dp)
        GhostButton(stringResource(R.string.back), onBack)
    }
    QCard {
        Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Q.TextMuted)
    }
}
