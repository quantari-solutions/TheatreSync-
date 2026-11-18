package com.theatresync.app

import android.Manifest
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theatresync.app.MovieSession.Phase
import com.theatresync.core.CastActivity
import com.theatresync.core.YouTubeLinks
import kotlin.math.abs

enum class Page { HOME, HEADPHONES, NOW_CASTING, YOUTUBE, MIRROR }

class MainActivity : ComponentActivity() {
    private val session: MovieSession get() = (application as TheatreSyncApplication).session
    private val outputs = mutableStateOf<List<DiagnosticsReport.Output>>(emptyList())
    private val details = mutableStateOf("")
    private val sharedLink = mutableStateOf<String?>(null)
    private val crashReport = mutableStateOf<String?>(null)
    private lateinit var audioManager: AudioManager

    private val pickMovie = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            session.chooseMovie(uri)
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAudio()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshAudio()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshAudio()
    }

    private fun refreshAudio() {
        outputs.value = DiagnosticsReport.friendlyOutputs(this)
        details.value = DiagnosticsReport.build(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        crashReport.value = CrashReporter.pending(this)
        handleShare(intent)
        mirroringLabel = getString(R.string.screen_mirroring)
        mirroringHeadline = getString(R.string.mirroring_a_screen)
        videoLabel = getString(R.string.video_label)
        setContent {
            QuantariTheme {
                GridBackground {
                    val ui by session.ui.collectAsState()
                    var page by rememberSaveable { mutableStateOf(Page.HOME) }
                    var castTvId by rememberSaveable { mutableStateOf<String?>(null) }
                    val casting = ui.nowCasting.firstOrNull { it.tvId == castTvId }

                    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
                    // Shared from the YouTube app: jump straight to the YouTube page.
                    LaunchedEffect(sharedLink.value) { if (sharedLink.value != null) page = Page.YOUTUBE }
                    // If the cast we're looking at ends, go back home on its own.
                    LaunchedEffect(page, casting == null) {
                        if (page == Page.NOW_CASTING && casting == null) page = Page.HOME
                    }

                    val target = when {
                        page == Page.HEADPHONES -> "headphones"
                        page == Page.YOUTUBE -> "youtube"
                        page == Page.MIRROR -> "mirror"
                        page == Page.NOW_CASTING && casting != null -> "casting"
                        ui.phase == Phase.SETUP -> "setup"
                        else -> "playing"
                    }
                    AnimatedContent(
                        targetState = target,
                        transitionSpec = {
                            val forward = targetState in setOf("headphones", "casting", "youtube", "mirror")
                            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220)))
                                .togetherWith(slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut(tween(180)))
                        },
                        label = "page",
                    ) { t ->
                        when (t) {
                            "headphones" -> HeadphonesScreen(
                                outputs = outputs.value,
                                details = details.value,
                                namesAllowed = DiagnosticsReport.bluetoothNamesAllowed(this@MainActivity),
                                onAllow = { askPermissions.launch(runtimePermissions()) },
                                onOpenBluetooth = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                                onBack = { page = Page.HOME },
                            )
                            "youtube" -> YouTubeScreen(
                                ui = ui,
                                initialLink = sharedLink.value,
                                onCast = { id, tv -> session.castYouTube(id, tv) },
                                onOpenApp = { link -> openYouTubeApp(link) },
                                onBack = { session.resetYouTube(); sharedLink.value = null; page = Page.HOME },
                            )
                            "mirror" -> MirrorScreen(
                                onStart = { startMirroring() },
                                onBack = { page = Page.HOME },
                            )
                            "casting" -> casting?.let { c ->
                                NowCastingScreen(
                                    item = c,
                                    onPlayPause = { session.castPlayPause(c.tvId) },
                                    onSkip = { session.castSkip(c.tvId, it) },
                                    onBack = { page = Page.HOME },
                                    onStop = { session.castStop(c.tvId); page = Page.HOME },
                                )
                            }
                            "setup" -> SetupScreen(
                                ui = ui,
                                onChooseMovie = { pickMovie.launch(arrayOf("video/*")) },
                                onConnectTv = session::connectTv,
                                onDisconnectTv = session::disconnectTv,
                                onStart = {
                                    askPermissions.launch(runtimePermissions())
                                    session.startMovie()
                                },
                                onCheckHeadphones = {
                                    refreshAudio()
                                    page = Page.HEADPHONES
                                },
                                onDismiss = session::dismissMessage,
                                onOpenCasting = { id -> castTvId = id; page = Page.NOW_CASTING },
                                onCastPlayPause = session::castPlayPause,
                                onCastStop = session::castStop,
                                onYouTube = { page = Page.YOUTUBE },
                                onMirror = { page = Page.MIRROR },
                                crashReport = crashReport.value,
                                onShareCrash = { shareCrash() },
                                onDismissCrash = { CrashReporter.clear(this@MainActivity); crashReport.value = null },
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
    }

    override fun onStart() {
        super.onStart()
        session.startTvSearch()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAudio()
    }

    override fun onStop() {
        session.stopTvSearch()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → TheatreSync" from the YouTube app (or any app sharing a YouTube link). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (YouTubeLinks.videoId(text) != null) sharedLink.value = text
    }

    private fun openYouTubeApp(link: String?) {
        val uri = Uri.parse(link?.takeIf { YouTubeLinks.videoId(it) != null } ?: "https://www.youtube.com/")
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")
        try {
            startActivity(inApp)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    /**
     * Android doesn't let apps start screen mirroring themselves, so open the phone's own
     * Cast/screen-mirroring screen, falling back to the Google Home app.
     */
    private fun startMirroring() {
        val attempts = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app"),
        )
        for (i in attempts) {
            if (i == null) continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try the next one
            }
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.chromecast.app")))
        }
    }

    private fun shareCrash() {
        val report = crashReport.value ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_crash))) }
    }

    private fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        const val STEP_MS = 20
    }
}

/** Top bar for inner pages: round back button, title, optional action on the right. */
@Composable
private fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton("←", stringResource(R.string.back), onBack, Modifier.testTag("back"))
        Text(title, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        action?.invoke()
    }
}

@Composable
private fun RoundIconButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(Q.Card)
            .border(BorderStroke(1.dp, Q.BorderAccent), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Q.PrimaryLight, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    // Tablets / landscape: keep a comfortable reading width, centred.
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun Brand(subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(stringResource(R.string.brand_eyebrow))
        GradientText(stringResource(R.string.app_name), MaterialTheme.typography.displaySmall)
        Body(subtitle)
    }
}

@Composable
private fun Message(text: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        QCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, highlighted = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("!", color = Color.Black, modifier = Modifier.clearAndSetSemantics { }.size(26.dp).clip(CircleShape).background(Q.Gold).padding(top = 2.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
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
    onOpenCasting: (String) -> Unit = {},
    onCastPlayPause: (String) -> Unit = {},
    onCastStop: (String) -> Unit = {},
    onYouTube: () -> Unit = {},
    onMirror: () -> Unit = {},
    crashReport: String? = null,
    onShareCrash: () -> Unit = {},
    onDismissCrash: () -> Unit = {},
) = Screen {
    Brand(stringResource(R.string.setup_intro))
    if (crashReport != null) CrashCard(onShareCrash, onDismissCrash)
    Message(ui.message, onDismiss)
    ui.nowCasting.forEach { item ->
        NowCastingCard(item, onOpen = { onOpenCasting(item.tvId) }, onPlayPause = { onCastPlayPause(item.tvId) }, onStop = { onCastStop(item.tvId) })
    }

    Eyebrow(stringResource(R.string.quick_watch))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WatchTile("▶", stringResource(R.string.youtube), stringResource(R.string.youtube_tile_hint), Modifier.weight(1f).testTag("tile_youtube"), Color(0xFFE62117), onYouTube)
        WatchTile("⧉", stringResource(R.string.mirror_screen), stringResource(R.string.mirror_tile_hint), Modifier.weight(1f).testTag("tile_mirror"), Q.Primary, onMirror)
    }

    Eyebrow(stringResource(R.string.movie_night))
    QCard(highlighted = ui.movieTitle == null) {
        StepHeader(1, stringResource(R.string.step_movie), done = ui.movieTitle != null)
        if (ui.movieTitle != null) {
            ChosenRow("🎬", ui.movieTitle) { GhostButton(stringResource(R.string.change), onChooseMovie, height = 40.dp) }
        } else {
            GoldButton(stringResource(R.string.choose_movie), onChooseMovie, Modifier.fillMaxWidth())
            Hint(stringResource(R.string.movie_hint))
        }
    }

    QCard(highlighted = ui.movieTitle != null && ui.connectedTv == null) {
        StepHeader(2, stringResource(R.string.step_tv), done = ui.connectedTv != null)
        when {
            !ui.castAvailable -> Body(stringResource(R.string.msg_no_cast))
            ui.connectedTv != null -> ChosenRow("📺", stringResource(R.string.connected_to, ui.connectedTv)) {
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
        GhostButton("🎧  " + stringResource(R.string.check_headphones), onCheckHeadphones, description = stringResource(R.string.check_headphones))
    }
    Footer()
}

@Composable
private fun WatchTile(glyph: String, title: String, hint: String, modifier: Modifier, accent: Color, onClick: () -> Unit) {
    QCard(modifier.clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(accent).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text(glyph, color = if (accent == Q.Primary) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Text(title, color = Q.Text, style = MaterialTheme.typography.titleMedium)
        Hint(hint)
    }
}

@Composable
private fun CrashCard(onShare: () -> Unit, onDismiss: () -> Unit) {
    QCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, highlighted = true) {
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.crash_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoldButton(stringResource(R.string.share_crash), onShare, height = 48.dp)
            GhostButton(stringResource(R.string.ok), onDismiss)
        }
    }
}

// ---------------------------------------------------------------- YouTube

@Composable
internal fun YouTubeScreen(
    ui: MovieSession.Ui,
    initialLink: String?,
    onCast: (videoId: String, tvId: String) -> Unit,
    onOpenApp: (String?) -> Unit,
    onBack: () -> Unit,
) = Screen {
    var link by rememberSaveable(initialLink) { mutableStateOf(initialLink.orEmpty()) }
    var tvId by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val videoId = YouTubeLinks.videoId(link)
    val chosenTv = ui.tvs.firstOrNull { it.id == tvId } ?: ui.tvs.firstOrNull { it.name == ui.connectedTv } ?: ui.tvs.singleOrNull()

    // Shared straight from the YouTube app: cast at once if there's exactly one TV.
    LaunchedEffect(initialLink, chosenTv?.id) {
        val id = YouTubeLinks.videoId(initialLink)
        if (id != null && chosenTv != null && ui.youTube == MovieSession.YouTubeState.IDLE) onCast(id, chosenTv.id)
    }

    TopBar(stringResource(R.string.youtube), onBack)
    QCard(highlighted = true) {
        Text(stringResource(R.string.yt_cast_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Hint(stringResource(R.string.yt_cast_hint))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth().testTag("yt_link"),
            singleLine = true,
            placeholder = { Text("https://youtu.be/…", color = Q.TextFaint) },
            label = { Text(stringResource(R.string.yt_link_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Q.Primary, unfocusedBorderColor = Q.BorderAccent,
                focusedLabelColor = Q.Primary, unfocusedLabelColor = Q.TextFaint,
                focusedTextColor = Q.Text, unfocusedTextColor = Q.Text, cursorColor = Q.Primary,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(R.string.paste), { clipboard.getText()?.text?.let { link = it } })
            if (link.isNotBlank() && videoId == null) Hint(stringResource(R.string.yt_not_a_link))
        }
        when {
            ui.tvs.isEmpty() -> StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true)
            ui.tvs.size > 1 -> ui.tvs.forEach { tv ->
                val selected = tv.id == chosenTv?.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(BorderStroke(1.dp, if (selected) Q.Primary else Q.BorderAccent), RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton) { tvId = tv.id }
                        .padding(14.dp)
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (selected) "◉" else "○", color = Q.Primary, modifier = Modifier.clearAndSetSemantics { })
                    Text(tv.name, Modifier.padding(start = 12.dp), color = Q.Text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        GoldButton(
            stringResource(R.string.yt_cast_to, chosenTv?.name ?: "TV"),
            { if (videoId != null && chosenTv != null) onCast(videoId, chosenTv.id) },
            Modifier.fillMaxWidth().testTag("yt_cast"),
            enabled = videoId != null && chosenTv != null && ui.youTube != MovieSession.YouTubeState.STARTING,
            height = 56.dp, leading = "▶",
        )
        when (ui.youTube) {
            MovieSession.YouTubeState.STARTING -> StatusPill(stringResource(R.string.yt_starting), Q.Primary, pulsing = true)
            MovieSession.YouTubeState.PLAYING -> StatusPill(stringResource(R.string.yt_playing), Q.Success)
            MovieSession.YouTubeState.FAILED -> {
                Body(stringResource(R.string.yt_failed))
                GoldButton(stringResource(R.string.yt_open_this), { onOpenApp(link) }, height = 48.dp)
            }
            MovieSession.YouTubeState.IDLE -> Unit
        }
    }
    QCard {
        Text(stringResource(R.string.yt_app_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.yt_app_hint))
        GhostButton(stringResource(R.string.yt_open_app), { onOpenApp(null) }, Modifier.fillMaxWidth())
    }
    QCard {
        Text(stringResource(R.string.yt_share_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.yt_share_hint))
    }
    Hint(stringResource(R.string.yt_sound_note))
    Footer()
}

// ---------------------------------------------------------------- Mirror

@Composable
internal fun MirrorScreen(onStart: () -> Unit, onBack: () -> Unit) = Screen {
    TopBar(stringResource(R.string.mirror_screen), onBack)
    QCard(highlighted = true) {
        Text(stringResource(R.string.mirror_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Body(stringResource(R.string.mirror_steps))
        GoldButton(stringResource(R.string.mirror_start), onStart, Modifier.fillMaxWidth().testTag("mirror_start"), height = 56.dp, leading = "⧉")
    }
    QCard {
        Text(stringResource(R.string.mirror_laptop_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.mirror_laptop_hint))
    }
    Hint(stringResource(R.string.mirror_sound_note))
    Footer()
}

/**
 * Icon + text + action. Side by side normally; with large system fonts the action
 * moves underneath so words never break mid-word (accessibility).
 */
@Composable
private fun ChosenRow(icon: String, text: String, action: @Composable () -> Unit) {
    val large = LocalDensity.current.fontScale > 1.3f
    val label: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, Modifier.clearAndSetSemantics { }, fontSize = 20.sp)
            Text(text, style = MaterialTheme.typography.titleMedium, color = Q.Text)
        }
    }
    if (large) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            label(Modifier)
            action()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            label(Modifier.weight(1f))
            action()
        }
    }
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
        Text("📺", Modifier.clearAndSetSemantics { }, fontSize = 22.sp)
        Text(name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Text(stringResource(R.string.connect), color = Q.Primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Footer() {
    Text(
        stringResource(R.string.footer), Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        color = Q.TextFaint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
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
            GhostButton(stringResource(R.string.skip_back_short), { onSkip(-10_000) }, Modifier.testTag("back10"), height = 52.dp, description = stringResource(R.string.skip_back))
            PlayButton(playing = ui.phase == Phase.PLAYING, onClick = onPlayPause)
            GhostButton(stringResource(R.string.skip_fwd_short), { onSkip(10_000) }, Modifier.testTag("fwd10"), height = 52.dp, description = stringResource(R.string.skip_forward))
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
            Text(stringResource(R.string.friends_heading), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = Q.Text)
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

    GhostButton("■  " + stringResource(R.string.stop_movie), onStop, Modifier.fillMaxWidth(), height = 52.dp, description = stringResource(R.string.stop_movie))
    Footer()
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.pause else R.string.play)
    val glow = rememberInfiniteTransition(label = "glow")
    val pulse by glow.animateFloat(14f, 28f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    Box(
        Modifier.size(92.dp)
            .shadow(if (playing) pulse.dp else 14.dp, CircleShape, ambientColor = Q.Glow, spotColor = Q.Glow)
            .clip(CircleShape).background(Q.Gold)
            // Order matters: clearAndSetSemantics wipes everything *after* it in the chain,
            // so the test tag and click action must come before it.
            .testTag("playpause")
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
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
        Text(icon, Modifier.clearAndSetSemantics { }, fontSize = 20.sp)
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


// ---------------------------------------------------------------- now casting

private fun CastActivity.label(): String = when (kind) {
    CastActivity.Kind.YOUTUBE -> "YouTube" // brand name, not translated
    CastActivity.Kind.MIRRORING -> mirroringLabel
    else -> if (appId == com.theatresync.core.CastStatus.APP_DEFAULT_MEDIA_RECEIVER) videoLabel else appName
}

/** Set from resources at startup so non-Composable helpers stay translatable. */
internal var mirroringLabel = "Screen mirroring"
internal var mirroringHeadline = "Mirroring a screen"
internal var videoLabel = "Video"

private fun CastActivity.headline(): String = title
    ?: when (kind) {
        CastActivity.Kind.MIRRORING -> statusText ?: mirroringHeadline
        else -> statusText ?: appName
    }

@Composable
private fun Thumb(a: CastActivity, modifier: Modifier, big: Boolean = false) {
    val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
    Box(modifier.clip(shape).border(BorderStroke(1.dp, Q.BorderAccent), shape)) {
        RemoteImage(a.imageUrl, a.title, Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Q.BackgroundAlt, Q.Border, Q.Card))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (a.kind) { CastActivity.Kind.YOUTUBE -> "▶"; CastActivity.Kind.MIRRORING -> "⧉"; else -> "♪" },
                    style = MaterialTheme.typography.headlineMedium.copy(brush = Q.Gold, fontSize = if (big) 56.sp else 26.sp),
                )
            }
        }
        if (a.kind == CastActivity.Kind.YOUTUBE) {
            Text(
                "YouTube", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCCE62117)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun NowCastingCard(item: TvWatchers.NowCasting, onOpen: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit) {
    val a = item.activity
    QCard(Modifier.clickable(role = Role.Button, onClick = onOpen).testTag("nowcasting"), highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Thumb(a, Modifier.width(128.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.now_casting).uppercase(),
                    color = Q.Primary, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                )
                Text(a.headline(), color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(a.subtitle ?: a.label(), item.tvName).joinToString(" · "),
                    color = Q.TextFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.canControl) RoundIconButton(if (a.isPlaying) "❚❚" else "▶", stringResource(R.string.play_pause), onPlayPause)
                if (a.sessionId != null) RoundIconButton("■", stringResource(R.string.stop_casting), onStop, Modifier.testTag("card_stop"))
            }
        }
        val d = a.durationSec
        val t = a.currentTimeSec
        if (d != null && t != null && d > 0) GoldProgress((t / d).toFloat())
    }
}

@Composable
internal fun NowCastingScreen(
    item: TvWatchers.NowCasting,
    onPlayPause: () -> Unit,
    onSkip: (Double) -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit = {},
) = Screen {
    val a = item.activity
    TopBar(stringResource(R.string.now_casting), onBack)
    Thumb(a, Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(22.dp, RoundedCornerShape(18.dp), ambientColor = Q.Glow, spotColor = Q.Glow), big = true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(a.label(), if (a.isPlaying || a.kind == CastActivity.Kind.MIRRORING) Q.Success else Q.TextMuted, pulsing = a.isPlaying)
        Text(stringResource(R.string.on_tv, item.tvName), color = Q.TextFaint, style = MaterialTheme.typography.bodyMedium)
    }
    GradientText(a.headline(), MaterialTheme.typography.headlineSmall)
    a.subtitle?.let { Body(it) }

    if (a.canControl) {
        QCard {
            val d = a.durationSec
            val t = a.currentTimeSec ?: 0.0
            if (d != null && d > 0) {
                GoldProgress((t / d).toFloat())
                Row {
                    Text(formatTime((t * 1000).toLong()), color = Q.TextMuted, style = MaterialTheme.typography.labelLarge)
                    Spacer1()
                    Text(formatTime((d * 1000).toLong()), color = Q.TextFaint, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GhostButton(stringResource(R.string.skip_back_short), { onSkip(-10.0) }, height = 52.dp, description = stringResource(R.string.skip_back))
                PlayButton(playing = a.isPlaying, onClick = onPlayPause)
                GhostButton(stringResource(R.string.skip_fwd_short), { onSkip(10.0) }, height = 52.dp, description = stringResource(R.string.skip_forward))
            }
        }
    } else {
        QCard {
            Text(stringResource(R.string.mirroring_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            Hint(stringResource(R.string.mirroring_hint))
        }
    }

    if (a.sessionId != null) {
        GhostButton("■  " + stringResource(R.string.stop_casting), onStop, Modifier.fillMaxWidth().testTag("stop_casting"), height = 52.dp, description = stringResource(R.string.stop_casting))
    }
    QCard {
        Text(stringResource(R.string.cast_sound_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.cast_sound_hint))
    }
    Footer()
}

// ---------------------------------------------------------------- headphones

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeadphonesScreen(
    outputs: List<DiagnosticsReport.Output>,
    details: String,
    namesAllowed: Boolean,
    onAllow: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onBack: () -> Unit,
    showDetailsInitially: Boolean = false,
) {
    var showDetails by remember { mutableStateOf(showDetailsInitially) }
    val headphones = outputs.filter { it.kind != DiagnosticsReport.Kind.SPEAKER }
    Screen {
        TopBar(stringResource(R.string.headphones), onBack) {
            RoundIconButton("i", stringResource(R.string.technical_details), { showDetails = true }, Modifier.testTag("info"))
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            val connected = headphones.isNotEmpty()
            val glow = rememberInfiniteTransition(label = "hp")
            val pulse by glow.animateFloat(12f, 30f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "hp")
            Box(
                Modifier.size(132.dp)
                    .shadow(if (connected) pulse.dp else 0.dp, CircleShape, ambientColor = Q.Glow, spotColor = Q.Glow)
                    .clip(CircleShape)
                    .background(if (connected) Q.Gold else SolidColor(Q.Card))
                    .border(BorderStroke(1.dp, if (connected) Q.Secondary else Q.BorderAccent), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("🎧", Modifier.clearAndSetSemantics { }, fontSize = 56.sp) }
        }
        if (headphones.isEmpty()) {
            Text(stringResource(R.string.no_headphones), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = Q.Text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.no_headphones_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = Q.TextMuted, textAlign = TextAlign.Center)
            GoldButton(stringResource(R.string.open_bluetooth), onOpenBluetooth, Modifier.fillMaxWidth())
        } else {
            headphones.forEach { o ->
                QCard(highlighted = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            GradientText(o.name, MaterialTheme.typography.headlineSmall)
                            Hint(stringResource(if (o.kind == DiagnosticsReport.Kind.WIRED) R.string.wired else R.string.bluetooth))
                        }
                        StatusPill(stringResource(R.string.connected), Q.Success)
                    }
                    Body(stringResource(R.string.sound_plays_here))
                }
            }
            GhostButton(stringResource(R.string.open_bluetooth), onOpenBluetooth, Modifier.fillMaxWidth())
        }
        if (!namesAllowed) {
            QCard {
                Text(stringResource(R.string.see_names_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                Hint(stringResource(R.string.see_names_hint))
                GoldButton(stringResource(R.string.allow_bluetooth), onAllow, height = 48.dp)
            }
        }
        Footer()
    }
    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }, containerColor = Q.Card) {
            TechnicalDetails(details)
        }
    }
}

@Composable
internal fun TechnicalDetails(details: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(stringResource(R.string.technical_details))
        Hint(stringResource(R.string.check_hint))
        Text(
            details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Q.TextMuted,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Q.Background)
                .border(BorderStroke(1.dp, Q.BorderAccent), RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}
