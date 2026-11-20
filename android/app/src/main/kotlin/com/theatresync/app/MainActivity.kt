package com.theatresync.app

import androidx.core.net.toUri
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
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

enum class Page { HOME, HEADPHONES, NOW_CASTING, YOUTUBE, MIRROR, SETTINGS, CRASHES, GITHUB, REMOTE, MOVIE, HEADPHONE_SETTINGS, EXPERIMENTAL }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val session: MovieSession get() = (application as TheatreSyncApplication).session
    private val outputs = mutableStateOf<List<DiagnosticsReport.Output>>(emptyList())
    private val details = mutableStateOf("")
    private val sharedLink = mutableStateOf<String?>(null)
    private val pendingCrash = mutableStateOf<CrashReporter.Entry?>(null)
    private val crashEntries = mutableStateOf<List<CrashReporter.Entry>>(emptyList())
    private val gitHubCode = mutableStateOf<GitHubLink.DeviceCode?>(null)
    private val gitHubState = mutableStateOf(GitHubState.IDLE)
    private val gitHubConnected = mutableStateOf(false)
    private val gitHubConfigured = mutableStateOf(false)
    private val gitHubLastUpload = mutableStateOf<String?>(null)
    private val gitHubTesting = mutableStateOf(false)
    private val soundTest = mutableStateOf<SoundTestUi>(SoundTestUi.Idle)
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
        refreshConnectedHeadsets()
    }

    /** Every Bluetooth headset connected to the phone (Android plays on the active one only). */
    private val connectedHeadsets = mutableStateOf<List<String>>(emptyList())

    @android.annotation.SuppressLint("MissingPermission") // checked just below
    private fun refreshConnectedHeadsets() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    connectedHeadsets.value = runCatching { proxy.connectedDevices.mapNotNull { it.name } }.getOrDefault(emptyList())
                    adapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, android.bluetooth.BluetoothProfile.A2DP)
        }
    }

    /** "Share by NFC" screen open: this phone answers guest taps with the listening page, and TheatreSync
     *  is the preferred NFC app so Android doesn't ask which app to use. */
    private val nfcSharing = mutableStateOf(false)

    private fun setNfcSharing(on: Boolean, url: String?) {
        nfcSharing.value = on
        TapInService.url = if (on) url else null
        val adapter = runCatching { android.nfc.NfcAdapter.getDefaultAdapter(this) }.getOrNull() ?: return
        val emulation = runCatching { android.nfc.cardemulation.CardEmulation.getInstance(adapter) }.getOrNull() ?: return
        runCatching {
            if (on) emulation.setPreferredService(this, android.content.ComponentName(this, TapInService::class.java))
            else emulation.unsetPreferredService(this)
        }
    }

    override fun onPause() {
        if (nfcSharing.value) setNfcSharing(false, null) // preferred service only while on screen
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        refreshCrashes()
        handleShare(intent)
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_REMOTE) openRemote.intValue++
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

                    var hpSettingsFrom by rememberSaveable { mutableStateOf(Page.HOME) }
                    fun openHeadphoneSettings(from: Page) { hpSettingsFrom = from; refreshAudio(); page = Page.HEADPHONE_SETTINGS }
                    BackHandler(enabled = page != Page.HOME) {
                        page = when (page) {
                            Page.CRASHES, Page.GITHUB, Page.REMOTE, Page.EXPERIMENTAL -> Page.SETTINGS
                            Page.HEADPHONE_SETTINGS -> hpSettingsFrom
                            else -> Page.HOME
                        }
                    }
                    // YouTube accepted the video: open its Now casting player at once.
                    LaunchedEffect(ui.youTube, ui.youTubeTvId) {
                        if (ui.youTube == MovieSession.YouTubeState.PLAYING && page == Page.YOUTUBE && ui.youTubeTvId != null) {
                            castTvId = ui.youTubeTvId
                            page = Page.NOW_CASTING
                            sharedLink.value = null // don't cast the same shared link again next time
                            session.resetYouTube()
                        }
                    }
                    // A movie just started: show its player. Back always returns to the home screen,
                    // which then shows a "Movie playing" card to come back here.
                    var lastPhase by remember { mutableStateOf(ui.phase) }
                    LaunchedEffect(ui.phase) {
                        if (lastPhase == Phase.SETUP && ui.phase != Phase.SETUP && page == Page.HOME) page = Page.MOVIE
                        if (ui.phase == Phase.SETUP && page == Page.MOVIE) page = Page.HOME
                        lastPhase = ui.phase
                    }
                    LaunchedEffect(openRemote.intValue) { if (openRemote.intValue > 0) page = Page.REMOTE }
                    // Headphones connected or removed: refresh the device lists straight away.
                    LaunchedEffect(ui.audioDevicesVersion) { refreshAudio() }
                    ui.newDevice?.let { d ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = session::dismissNewDevice,
                            containerColor = Q.Card,
                            title = { Text(stringResource(R.string.new_device_title), color = Q.Text) },
                            text = { Text(stringResource(R.string.new_device_text, d.name), color = Q.TextMuted) },
                            confirmButton = { GoldButton(stringResource(R.string.new_device_use, d.name), { session.setPreferredOutput(d.id) }, Modifier.testTag("use_new_device"), height = 48.dp) },
                            dismissButton = { GhostButton(stringResource(R.string.new_device_keep), session::dismissNewDevice, height = 48.dp) },
                        )
                    }
                    val listenUrl = ui.joinAddress?.let { "http://$it/listen" }
                    if (audioSheet.value) {
                        AudioSheet(
                            ui = ui,
                            outputs = outputs.value,
                            connectedHeadsets = connectedHeadsets.value,
                            listenUrl = listenUrl,
                            onRoomSpeakers = session::setRoomSpeakers,
                            onSelectOutput = session::setPreferredOutput,
                            onPickDevice = ::pickAudioDevice,
                            onShareNfc = { setNfcSharing(true, listenUrl) },
                            onShowQr = { qrDialog.value = true },
                            onMore = { audioSheet.value = false; openHeadphoneSettings(page) },
                            onDismiss = { audioSheet.value = false },
                        )
                    }
                    if (nfcSharing.value && listenUrl != null) NfcShareDialog(onDone = { setNfcSharing(false, null) })
                    if (qrDialog.value && listenUrl != null) QrDialog(listenUrl, onDone = { qrDialog.value = false })
                    // Shared from the YouTube app: jump straight to the YouTube page.
                    LaunchedEffect(sharedLink.value) { if (sharedLink.value != null) page = Page.YOUTUBE }
                    // If the cast we're looking at ends, go back home on its own.
                    LaunchedEffect(page, casting == null) {
                        if (page == Page.NOW_CASTING && casting == null) {
                            // After returning from another app the TV status takes a moment to
                            // come back: wait before deciding the cast really ended.
                            kotlinx.coroutines.delay(6_000)
                            page = Page.HOME
                        }
                    }

                    val target = when {
                        page == Page.NOW_CASTING && casting == null -> "reconnecting"
                        page == Page.GITHUB -> "github"
                        page == Page.REMOTE -> "remote"
                        page == Page.HEADPHONE_SETTINGS -> "hpsettings"
                        page == Page.EXPERIMENTAL -> "experimental"
                        page == Page.SETTINGS -> "settings"
                        page == Page.CRASHES -> "crashes"
                        page == Page.HEADPHONES -> "headphones"
                        page == Page.YOUTUBE -> "youtube"
                        page == Page.MIRROR -> "mirror"
                        page == Page.NOW_CASTING && casting != null -> "casting"
                        page == Page.MOVIE && ui.phase != Phase.SETUP -> "playing"
                        else -> "setup"
                    }
                    if (inPip.value) {
                        PipContent(ui, ui.nowCasting.firstOrNull { it.tvId == castTvId } ?: ui.nowCasting.firstOrNull())
                        return@GridBackground
                    }
                    // Pull down anywhere to look for TVs, smart TVs and headphones again.
                    var refreshing by remember { mutableStateOf(false) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            refreshing = true
                            session.refreshDevices()
                            refreshAudio()
                            scope.launch { kotlinx.coroutines.delay(1_500); refreshing = false }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AnimatedContent(
                            targetState = target,
                            transitionSpec = {
                                val forward = targetState in setOf("headphones", "casting", "youtube", "mirror", "settings", "crashes", "github", "remote", "hpsettings", "experimental")
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
                                    onOpenBluetooth = ::pickAudioDevice,
                                    onBack = { page = Page.HOME },
                                    preferredOutputId = ui.preferredOutputId,
                                    onSelectOutput = { id -> session.setPreferredOutput(id); soundTest.value = SoundTestUi.Idle },
                                    soundTest = soundTest.value,
                                    onRunSoundTest = ::runSoundTest,
                                    onAnswerSoundTest = ::answerSoundTest,
                                    onSettings = { openHeadphoneSettings(Page.HEADPHONES) },
                                )
                                "hpsettings" -> HeadphoneSettingsScreen(
                                    ui = ui,
                                    outputs = outputs.value,
                                    onSelectOutput = { id -> session.setPreferredOutput(id) },
                                    onPickDevice = ::pickAudioDevice,
                                    onDelay = { step -> session.adjustSoundDelay(step) },
                                    onResetSync = session::resetHeadphoneSync,
                                    onFx = session::setFx,
                                    onSoundTest = { refreshAudio(); page = Page.HEADPHONES },
                                    onBack = { page = hpSettingsFrom },
                                    onAutoSwitch = session::setAutoSwitch,
                                )
                                "reconnecting" -> ReconnectingScreen(onBack = { page = Page.HOME })
                                "github" -> ConnectGitHubScreen(
                                    configured = gitHubConfigured.value,
                                    connected = gitHubConnected.value,
                                    code = gitHubCode.value,
                                    state = gitHubState.value,
                                    onStart = ::startGitHubConnect,
                                    onOpenGitHub = { c -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, c.verificationUri.toUri())) } },
                                    onDisconnect = { GitHubLink.disconnect(this@MainActivity); gitHubConnected.value = false; gitHubLastUpload.value = null },
                                    onBack = { page = Page.SETTINGS },
                                    onCreateApp = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, GitHubLink.createAppUrl.toUri())) } },
                                    onSaveClientId = { id ->
                                        GitHubLink.saveClientId(this@MainActivity, id).also { if (it) gitHubConfigured.value = true }
                                    },
                                    lastUpload = gitHubLastUpload.value,
                                    testing = gitHubTesting.value,
                                    onSendTest = ::sendGitHubTest,
                                )
                                "settings" -> SettingsScreen(
                                    crashCount = crashEntries.value.size,
                                    autoReports = gitHubConnected.value,
                                    onAutoReports = { page = Page.GITHUB },
                                    onCrashes = { refreshCrashes(); page = Page.CRASHES },
                                    onHeadphones = { openHeadphoneSettings(Page.SETTINGS) },
                                    onBack = { page = Page.HOME },
                                    onRemote = { page = Page.REMOTE },
                                    onExperimental = { page = Page.EXPERIMENTAL },
                                )
                                "experimental" -> ExperimentalScreen(
                                    ui = ui,
                                    onSignatureIntro = session::setSignatureIntro,
                                    onBack = { page = Page.SETTINGS },
                                )
                                "remote" -> RemoteScreen(
                                    ui = ui,
                                    onPlayPause = session::castPlayPause,
                                    onSkip = session::castSkip,
                                    onVolumeStep = session::castVolumeStep,
                                    onMute = session::castMute,
                                    onNext = session::youTubeNext,
                                    onPrevious = session::youTubePrevious,
                                    onCaptions = { tvId, lang ->
                                        session.youTubeCaptions(tvId, ui.nowCasting.firstOrNull { it.tvId == tvId }?.activity?.contentId, lang)
                                    },
                                    onStop = session::castStop,
                                    onYouTube = { page = Page.YOUTUBE },
                                    onBack = { page = Page.SETTINGS },
                                )
                                "crashes" -> CrashReportsScreen(
                                    entries = crashEntries.value,
                                    autoReports = gitHubConnected.value,
                                    onSendGitHub = ::sendToGitHub,
                                    onShare = ::shareCrash,
                                    onBack = { page = Page.SETTINGS },
                                    autoReportsSection = {
                                        GitHubSection(
                                            configured = gitHubConfigured.value,
                                            connected = gitHubConnected.value,
                                            code = gitHubCode.value,
                                            state = gitHubState.value,
                                            onStart = ::startGitHubConnect,
                                            onOpenGitHub = { c -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, c.verificationUri.toUri())) } },
                                            onDisconnect = { GitHubLink.disconnect(this@MainActivity); gitHubConnected.value = false; gitHubLastUpload.value = null },
                                            onCreateApp = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, GitHubLink.createAppUrl.toUri())) } },
                                            onSaveClientId = { id -> GitHubLink.saveClientId(this@MainActivity, id).also { if (it) gitHubConfigured.value = true } },
                                            lastUpload = gitHubLastUpload.value,
                                            testing = gitHubTesting.value,
                                            onSendTest = ::sendGitHubTest,
                                        )
                                    },
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
                                        onVolume = { session.castVolume(c.tvId, it) },
                                        onMute = { session.castMute(c.tvId, it) },
                                        onSeekTo = { session.castSeekTo(c.tvId, it) },
                                        onNext = { session.youTubeNext(c.tvId) },
                                        onPrevious = { session.youTubePrevious(c.tvId) },
                                        onCaptions = { lang -> session.youTubeCaptions(c.tvId, c.activity.contentId, lang) },
                                        onHeadphoneOptions = { openHeadphoneSettings(Page.NOW_CASTING) },
                                        onPip = ::enterPip,
                                        onAudio = { refreshAudio(); audioSheet.value = true },
                                        onRemote = { page = Page.REMOTE },
                                        message = ui.message,
                                        onDismissMessage = session::dismissMessage,
                                    )
                                }
                                "setup" -> SetupScreen(
                                    ui = ui,
                                    onChooseMovie = { pickMovie.launch(arrayOf("video/*")) },
                                    onConnectTv = { id ->
                                        // Needed for the cast icon in the status bar (Android 13+).
                                        askPermissions.launch(runtimePermissions())
                                        session.connectTv(id)
                                    },
                                    onMenu = { page = Page.SETTINGS },
                                    onQuality = session::setQuality,
                                    onCancelPrepare = session::cancelPrepare,
                                    onClearMovie = session::clearMovie,
                                    onOpenMovie = { page = Page.MOVIE },
                                    onRoomSound = session::setRoomSoundToo,
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
                                    pendingCrash = pendingCrash.value,
                                    onSendCrash = { e -> sendToGitHub(e) },
                                    onShareCrash = { e -> shareCrash(e) },
                                    onDismissCrash = { e -> CrashReporter.dismiss(this@MainActivity, e.id); refreshCrashes() },
                                )
                                else -> PlayingScreen(
                                    ui = ui,
                                    onPlayPause = session::togglePause,
                                    onSkip = session::skip,
                                    onSeekTo = session::seekTo,
                                    onSoundEarly = { session.adjustSoundDelay(+STEP_MS) },
                                    onSoundLate = { session.adjustSoundDelay(-STEP_MS) },
                                    onHeadphonesHere = session::setHeadphonesHere,
                                    onMuteTv = session::setMuteTv,
                                    onStop = session::stopMovie,
                                    onDismiss = session::dismissMessage,
                                    onBack = { page = Page.HOME },
                                    onHeadphoneOptions = { openHeadphoneSettings(Page.MOVIE) },
                                    onPip = ::enterPip,
                                    onAudio = { refreshAudio(); audioSheet.value = true },
                                    onRemote = { page = Page.REMOTE },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshCrashes()
        session.startTvSearch()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAudio()
    }

    override fun onStop() {
        session.stopTvSearch()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    /**
     * Android's own device sheet (Output Switcher), shown over TheatreSync: lists paired
     * headphones and connects them with one tap. Apps can't connect Bluetooth audio
     * devices themselves. Falls back to Bluetooth settings on phones without it.
     */
    private fun pickAudioDevice() {
        val shown = runCatching {
            androidx.mediarouter.app.SystemOutputSwitcherDialogController.showDialog(this)
        }.getOrDefault(false)
        if (!shown) runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_REMOTE) openRemote.intValue++
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_PIP) window.decorView.post { enterPip() }
    }

    private val audioSheet = mutableStateOf(false)
    private val qrDialog = mutableStateOf(false)

    /** True while the app is shown as a small floating picture-in-picture window. */
    private val inPip = mutableStateOf(false)

    /** Small floating window showing what's on the TV, while you use other apps. */
    fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .setActions(listOf(pipAction(R.string.notif_play_pause, android.R.drawable.ic_media_pause, HostService.ACTION_PLAY_PAUSE_PUBLIC)))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    private fun pipAction(title: Int, icon: Int, action: String) = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(this, icon), getString(title), getString(title),
        android.app.PendingIntent.getBroadcast(
            this, 11, Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** Incremented to ask the UI to show the remote (from the notification). */
    private val openRemote = androidx.compose.runtime.mutableIntStateOf(0)

    /** "Share → TheatreSync" from the YouTube app (or any app sharing a YouTube link). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (YouTubeLinks.videoId(text) != null) sharedLink.value = text
    }

    private fun openYouTubeApp(link: String?) {
        val uri = (link?.takeIf { YouTubeLinks.videoId(it) != null } ?: "https://www.youtube.com/").toUri()
        // YouTube ReVanced first (the owner's YouTube), then official YouTube, then any browser.
        val launched = YOUTUBE_PACKAGES.any { pkg ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)) }.isSuccess ||
                (link == null && packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) }.isSuccess } == true)
        }
        if (!launched) runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
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
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.google.android.apps.chromecast.app".toUri()))
        }
    }

    private fun refreshCrashes() {
        crashEntries.value = CrashReporter.entries(this)
        pendingCrash.value = CrashReporter.pendingCrash(this)
        gitHubConnected.value = GitHubLink.isConnected(this)
        gitHubConfigured.value = GitHubLink.isConfigured(this)
        gitHubLastUpload.value = GitHubLink.lastUpload(this)
    }

    private fun runSoundTest() {
        if (soundTest.value == SoundTestUi.Playing) return
        soundTest.value = SoundTestUi.Playing
        val preferred = session.ui.value.preferredOutputId
        Thread({
            val r = SoundTest.run(this, preferred)
            runOnUiThread { soundTest.value = SoundTestUi.Done(r) }
        }, "sound-test").apply { isDaemon = true }.start()
    }

    /** The owner's answer is recorded so a failed test reaches Claude with the routing details. */
    private fun answerSoundTest(heard: Boolean) {
        val r = (soundTest.value as? SoundTestUi.Done)?.result ?: return
        val summary = "Sound test: heard=$heard, routed to ${r.deviceName ?: "unknown"} (type ${r.deviceType}, ${r.route})" +
            (r.error?.let { ", error $it" } ?: "") + ", outputs: " +
            DiagnosticsReport.friendlyOutputs(this).joinToString { "${it.name}/${it.kind}" }
        session.recordSoundTest(summary)
        if (!heard || !r.isHeadphones) CrashReporter.handled(this, "headphone sound test", IllegalStateException(summary))
        soundTest.value = SoundTestUi.Answered(r, heard)
    }

    /** One-time GitHub approval (device flow): show a code, poll until the owner approves. */
    private fun startGitHubConnect() {
        if (!GitHubLink.isConfigured(this)) return
        gitHubState.value = GitHubState.WAITING
        Thread({
            try {
                val code = GitHubLink.requestCode(this)
                runOnUiThread { gitHubCode.value = code }
                val deadline = System.currentTimeMillis() + 15 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(code.intervalSec * 1000L + 500)
                    if (GitHubLink.poll(this, code)) {
                        runOnUiThread {
                            gitHubConnected.value = true
                            gitHubState.value = GitHubState.DONE
                            gitHubCode.value = null
                        }
                        sendGitHubTest()
                        return@Thread
                    }
                }
                runOnUiThread { gitHubState.value = GitHubState.FAILED }
            } catch (e: Exception) {
                CrashReporter.handled(this, "connect GitHub", e)
                runOnUiThread { gitHubState.value = GitHubState.FAILED; gitHubCode.value = null }
            }
        }, "github-connect").apply { isDaemon = true }.start()
    }

    /** Proves the whole path: adds a test report, uploads it, shows "Uploaded #N" or the reason it failed. */
    private fun sendGitHubTest() = runOnUiThread {
        if (gitHubTesting.value) return@runOnUiThread
        gitHubTesting.value = true
        GitHubLink.sendTest(this) { result ->
            runOnUiThread {
                gitHubTesting.value = false
                gitHubLastUpload.value = result
                refreshCrashes()
            }
        }
    }

    private fun shareCrash(e: CrashReporter.Entry) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.report)
        if (runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_crash))) }.isSuccess) {
            CrashReporter.mark(this, e.id, CrashReporter.Status.SHARED)
            refreshCrashes()
        }
    }

    /** Opens a pre-filled GitHub issue; the owner taps Submit with their own login. */
    private fun sendToGitHub(e: CrashReporter.Entry) {
        if (runCatching { startActivity(Intent(Intent.ACTION_VIEW, CrashReporter.gitHubIssueUri(e))) }.isSuccess) {
            CrashReporter.mark(this, e.id, CrashReporter.Status.SENT_GITHUB)
            refreshCrashes()
        }
    }

    private fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        const val STEP_MS = 20

        const val EXTRA_OPEN = "open"
        const val OPEN_REMOTE = "remote"
        const val OPEN_PIP = "pip"

        /** "Pop out" button in the casting notifications: show the small floating window. */
        fun pipIntent(context: android.content.Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            context, 8,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, OPEN_PIP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** "Remote" button in the casting notifications: opens the app on the remote page. */
        fun remoteIntent(context: android.content.Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, OPEN_REMOTE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** ReVanced, ReVanced Extended, then official YouTube. */
        val YOUTUBE_PACKAGES = listOf(
            "app.revanced.android.youtube", "app.rvx.android.youtube", "com.google.android.youtube",
        )
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
    onMenu: () -> Unit = {},
    onQuality: (Quality) -> Unit = {},
    onCancelPrepare: () -> Unit = {},
    onClearMovie: () -> Unit = {},
    onOpenMovie: () -> Unit = {},
    onRoomSound: (Boolean) -> Unit = {},
    pendingCrash: CrashReporter.Entry? = null,
    onSendCrash: (CrashReporter.Entry) -> Unit = {},
    onShareCrash: (CrashReporter.Entry) -> Unit = {},
    onDismissCrash: (CrashReporter.Entry) -> Unit = {},
) = Screen {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) { Brand(stringResource(R.string.setup_intro)) }
        RoundIconButton("☰", stringResource(R.string.menu), onMenu, Modifier.testTag("menu"))
    }
    // No crash card here any more: reports upload to GitHub automatically (Settings → Crash reports).
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
    if (ui.phase != Phase.SETUP) {
        MoviePlayingCard(ui, onOpenMovie)
        Footer()
        return@Screen
    }
    QCard(highlighted = ui.movieTitle == null) {
        StepHeader(1, stringResource(R.string.step_movie), done = ui.movieTitle != null)
        if (ui.movieTitle != null) {
            ChosenRow(R.drawable.ic_movie, ui.movieTitle) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(stringResource(R.string.change), onChooseMovie, height = 40.dp)
                    GhostButton("✕", onClearMovie, Modifier.testTag("clear_movie"), height = 40.dp, description = stringResource(R.string.remove_movie))
                }
            }
            MovieQuality(ui, onQuality, onRoomSound)
        } else {
            GoldButton(stringResource(R.string.choose_movie), onChooseMovie, Modifier.fillMaxWidth())
            Hint(stringResource(R.string.movie_hint))
        }
    }

    QCard(highlighted = ui.movieTitle != null && ui.connectedTv == null) {
        StepHeader(2, stringResource(R.string.step_tv), done = ui.connectedTv != null)
        when {
            !ui.castAvailable -> Body(stringResource(R.string.msg_no_cast))
            ui.connectedTv != null -> ChosenRow(R.drawable.ic_device_cast, stringResource(R.string.connected_to, ui.connectedTv)) {
                GhostButton(stringResource(R.string.disconnect), onDisconnectTv, height = 40.dp)
            }
            ui.tvs.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true)
                Hint(stringResource(R.string.looking_for_tv))
            }
            ui.tvs.none { !it.youTubeOnly } -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true)
                Hint(stringResource(R.string.looking_for_tv))
            }
            else -> ui.tvs.filterNot { it.youTubeOnly }.forEach { tv ->
                val busy = ui.nowCasting.firstOrNull { it.tvId == tv.id }?.activity
                TvRow(tv.name, busy?.label(), tv.isSpeaker) { onConnectTv(tv.id) }
            }
        }
    }

    QCard(highlighted = ui.canStart) {
        StepHeader(3, stringResource(R.string.step_play), done = false)
        Hint(stringResource(R.string.headphones_hint))
        val preparing = ui.prepareProgress
        if (preparing != null) {
            // Converting the movie for the TV (one time per movie and quality).
            StatusPill(stringResource(if (ui.prepareSoundOnly) R.string.preparing_sound else R.string.preparing, preparing), Q.Primary, pulsing = true)
            GoldProgress(preparing / 100f)
            ui.prepareEtaSec?.let { eta ->
                Hint(
                    if (eta < 60) pluralStringResource(R.plurals.eta_seconds, eta.toInt(), eta.toInt())
                    else ((eta + 59) / 60).toInt().let { m -> pluralStringResource(R.plurals.eta_minutes, m, m) },
                )
            }
            Hint(stringResource(if (ui.prepareSoundOnly) R.string.preparing_sound_hint else R.string.preparing_hint))
            GhostButton(stringResource(R.string.cancel), onCancelPrepare, Modifier.fillMaxWidth())
        } else {
            GoldButton(
                stringResource(R.string.start_movie), onStart,
                Modifier.fillMaxWidth().testTag("start"),
                enabled = ui.canStart, height = 64.dp, leading = "▶",
            )
        }
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
private fun CrashCard(onSend: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    QCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("crash_card"), highlighted = true) {
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.crash_hint))
        GoldButton(stringResource(R.string.send_github), onSend, Modifier.fillMaxWidth(), height = 48.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.share), onShare)
            GhostButton(stringResource(R.string.ok), onDismiss)
        }
    }
}

// ---------------------------------------------------------------- settings & crash timeline

@Composable
internal fun SettingsScreen(
    crashCount: Int,
    onCrashes: () -> Unit,
    onHeadphones: () -> Unit,
    onBack: () -> Unit,
    autoReports: Boolean = false,
    onAutoReports: () -> Unit = {},
    onRemote: () -> Unit = {},
    onExperimental: () -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.settings), onBack)
    SettingsRow("🎛", stringResource(R.string.remote), null, onRemote, "settings_remote", status = stringResource(R.string.remote_status))
    // Everything about crash reports (automatic upload + timeline) lives on one page.
    SettingsRow(
        "🧾", stringResource(R.string.crash_reports), if (crashCount > 0) crashCount.toString() else null, onCrashes, "settings_crashes",
        status = stringResource(if (autoReports) R.string.auto_reports_on else R.string.auto_reports_off),
        statusOk = autoReports,
    )
    SettingsRow("🎧", stringResource(R.string.headphones), null, onHeadphones, "settings_headphones")
    SettingsRow("🧪", stringResource(R.string.experimental), null, onExperimental, "settings_experimental", status = stringResource(R.string.experimental_status))
    QCard {
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.about_text, BuildConfig.VERSION_NAME))
    }
    Footer()
}

@Composable
private fun SettingsRow(
    icon: String, title: String, badge: String?, onClick: () -> Unit, tag: String,
    status: String? = null, statusOk: Boolean = false,
) {
    QCard(Modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(icon, Modifier.clearAndSetSemantics { }, fontSize = 22.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Q.Text)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (statusOk) Q.Success else Q.TextFaint) }
            }
            badge?.let {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Q.Gold), contentAlignment = Alignment.Center) {
                    Text(it, color = Color.Black, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("›", color = Q.PrimaryLight, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashReportsScreen(
    entries: List<CrashReporter.Entry>,
    onSendGitHub: (CrashReporter.Entry) -> Unit,
    onShare: (CrashReporter.Entry) -> Unit,
    onBack: () -> Unit,
    autoReports: Boolean = false,
    autoReportsSection: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf<CrashReporter.Entry?>(null) }
    Screen {
        TopBar(stringResource(R.string.crash_reports), onBack)
        autoReportsSection?.let {
            Eyebrow(stringResource(R.string.auto_reports))
            it()
            Eyebrow(stringResource(R.string.crash_timeline))
        }
        Hint(stringResource(if (autoReports) R.string.crash_reports_hint_auto else R.string.crash_reports_hint))
        if (entries.isEmpty()) {
            QCard {
                StatusPill(stringResource(R.string.no_crashes), Q.Success)
            }
        }
        entries.forEach { e ->
            QCard(Modifier.clickable(role = Role.Button) { open = e }.testTag("crash_entry")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (e.kind == CrashReporter.Kind.CRASH) Q.Error else Q.Primary)
                            .clearAndSetSemantics { },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(if (e.kind == CrashReporter.Kind.CRASH) R.string.crash_kind_crash else R.string.crash_kind_handled),
                            style = MaterialTheme.typography.titleMedium, color = Q.Text,
                        )
                        Text("${CrashReporter.formatTime(e.timeMs)} · ${e.version}", color = Q.TextFaint, style = MaterialTheme.typography.bodySmall)
                        Text(e.title, color = Q.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                CrashStatus(e)
            }
        }
        Footer()
    }
    open?.let { e ->
        ModalBottomSheet(onDismissRequest = { open = null }, containerColor = Q.Card) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow(stringResource(if (e.kind == CrashReporter.Kind.CRASH) R.string.crash_kind_crash else R.string.crash_kind_handled))
                CrashStatus(e)
                if (!autoReports && e.status != CrashReporter.Status.SENT_GITHUB) {
                    GoldButton(stringResource(R.string.send_github), { onSendGitHub(e); open = null }, Modifier.fillMaxWidth(), height = 48.dp)
                    Hint(stringResource(R.string.send_github_hint))
                }
                GhostButton(stringResource(R.string.share), { onShare(e); open = null }, Modifier.fillMaxWidth())
                Text(
                    e.report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Q.TextMuted,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Q.Background)
                        .border(BorderStroke(1.dp, Q.BorderAccent), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun CrashStatus(e: CrashReporter.Entry) {
    val t = e.statusTimeMs?.let { CrashReporter.formatTime(it) } ?: ""
    when (e.status) {
        CrashReporter.Status.SAVED -> StatusPill(stringResource(R.string.status_saved), Q.TextMuted)
        CrashReporter.Status.SHARED -> StatusPill(stringResource(R.string.status_shared, t), Q.Primary)
        CrashReporter.Status.SENT_GITHUB ->
            StatusPill(e.reference?.let { stringResource(R.string.status_uploaded, it, t) } ?: stringResource(R.string.status_sent, t), Q.Success)
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
                    RadioDot(selected)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(tv.name, color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tv.youTubeOnly) Hint(stringResource(R.string.smart_tv_youtube))
                    }
                }
            }
        }
        GoldButton(
            if (ui.youTube == MovieSession.YouTubeState.STARTING) stringResource(R.string.yt_starting_button)
            else stringResource(R.string.yt_cast_to, chosenTv?.name ?: "TV"),
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
private fun ChosenRow(icon: Int, text: String, action: @Composable () -> Unit) {
    val large = LocalDensity.current.fontScale > 1.3f
    val label: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceIcon(icon)
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

/** Modern device icon in a small gold-tinted tile. */
@Composable
private fun DeviceIcon(res: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Q.Primary.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, Q.Primary.copy(alpha = 0.3f)), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(res), contentDescription = null, tint = Q.Primary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TvRow(name: String, busyWith: String? = null, isSpeaker: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Q.Background.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, Q.Secondary.copy(alpha = 0.5f)), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIcon(if (isSpeaker) R.drawable.ic_device_speaker else R.drawable.ic_device_cast)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Q.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The TV is already showing something (e.g. YouTube from TheatreSync or another phone).
            busyWith?.let { Text(stringResource(R.string.tv_busy, it), style = MaterialTheme.typography.bodySmall, color = Q.Success) }
        }
        Text(stringResource(if (busyWith != null) R.string.use_for_movie else R.string.connect), color = Q.Primary, style = MaterialTheme.typography.labelLarge)
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
    onSeekTo: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    onHeadphoneOptions: () -> Unit = {},
    onPip: () -> Unit = {},
    onAudio: () -> Unit = {},
    onRemote: () -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.movie_player), onBack) { PlayerActions(onAudio, onRemote, onPip) }
    Eyebrow(
        when {
            ui.introPlaying -> stringResource(R.string.status_intro)
            else -> null
        } ?: when (ui.phase) {
            Phase.STARTING -> stringResource(R.string.status_starting, ui.connectedTv ?: "TV")
            Phase.PAUSED -> stringResource(R.string.status_paused)
            Phase.ENDED -> stringResource(R.string.status_ended)
            else -> stringResource(R.string.status_playing, ui.connectedTv ?: "TV")
        },
    )
    GradientText(ui.movieTitle ?: "", MaterialTheme.typography.headlineMedium)
    Message(ui.message, onDismiss)

    QCard(highlighted = ui.phase == Phase.PLAYING) {
        SeekBar(ui.positionMs, ui.durationMs, onSeekTo, Modifier.testTag("movie_seek"))
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

    AudioSummary(ui, onAudio)

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


    HeadphoneOptionsButton(onHeadphoneOptions)
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
        ui.tvBuffering -> StatusPill(stringResource(R.string.tv_loading), Q.Primary, pulsing = true)
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
    QCard(Modifier.clickable(role = Role.Button, onClick = onOpen).testTag("nowcasting"), highlighted = true, orbit = a.isPlaying) {
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
    onVolume: (Double) -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onSeekTo: (Double) -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onCaptions: (String?) -> Unit = {},
    onHeadphoneOptions: () -> Unit = {},
    onPip: () -> Unit = {},
    onAudio: () -> Unit = {},
    onRemote: () -> Unit = {},
    message: String? = null,
    onDismissMessage: () -> Unit = {},
) = Screen {
    val a = item.activity
    TopBar(stringResource(R.string.now_casting), onBack) { PlayerActions(onAudio, onRemote, onPip) }
    Message(message, onDismissMessage)
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
                SeekBar((t * 1000).toLong(), (d * 1000).toLong(), { ms -> onSeekTo(ms / 1000.0) }, Modifier.testTag("cast_seek"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GhostButton(stringResource(R.string.skip_back_short), { onSkip(-10.0) }, height = 52.dp, description = stringResource(R.string.skip_back))
                PlayButton(playing = a.isPlaying, onClick = onPlayPause)
                GhostButton(stringResource(R.string.skip_fwd_short), { onSkip(10.0) }, height = 52.dp, description = stringResource(R.string.skip_forward))
            }
            if (a.kind == CastActivity.Kind.YOUTUBE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("⏮  " + stringResource(R.string.yt_previous), onPrevious, Modifier.weight(1f).testTag("yt_previous"), height = 48.dp)
                    GhostButton(stringResource(R.string.yt_next) + "  ⏭", onNext, Modifier.weight(1f).testTag("yt_next"), height = 48.dp)
                }
                CaptionsRow(onCaptions)
            }
        }
    } else {
        QCard {
            Text(stringResource(R.string.mirroring_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            Hint(stringResource(R.string.mirroring_hint))
        }
    }

    // TV volume works for YouTube, mirroring and movies alike.
    a.volumeLevel?.let { level ->
        QCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tv_volume), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                GhostButton(
                    stringResource(if (a.muted == true) R.string.unmute else R.string.mute),
                    { onMute(a.muted != true) }, height = 40.dp,
                    description = stringResource(if (a.muted == true) R.string.unmute else R.string.mute),
                )
            }
            var local by remember(level) { mutableFloatStateOf(level.toFloat()) }
            Slider(
                value = local, onValueChange = { local = it }, onValueChangeFinished = { onVolume(local.toDouble()) },
                modifier = Modifier.testTag("tv_volume"),
                colors = SliderDefaults.colors(thumbColor = Q.Primary, activeTrackColor = Q.Primary, inactiveTrackColor = Q.Border),
            )
            Hint(stringResource(R.string.tv_volume_hint))
        }
    }
    if (a.sessionId != null) {
        GhostButton("■  " + stringResource(R.string.stop_casting), onStop, Modifier.fillMaxWidth().testTag("stop_casting"), height = 52.dp, description = stringResource(R.string.stop_casting))
    }
    QCard {
        Text(stringResource(R.string.cast_sound_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.cast_sound_hint))
        GhostButton("🎧  " + stringResource(R.string.hp_settings), onHeadphoneOptions, Modifier.fillMaxWidth().testTag("cast_hp_settings"), height = 48.dp)
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
    preferredOutputId: Int? = null,
    onSelectOutput: (Int?) -> Unit = {},
    soundTest: SoundTestUi = SoundTestUi.Idle,
    onRunSoundTest: () -> Unit = {},
    onAnswerSoundTest: (Boolean) -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var showDetails by remember { mutableStateOf(showDetailsInitially) }
    val headphones = outputs.filter { it.kind != DiagnosticsReport.Kind.SPEAKER }
    Screen {
        TopBar(stringResource(R.string.headphones), onBack) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton("⚙", stringResource(R.string.hp_settings), onSettings, Modifier.testTag("hp_settings"))
                RoundIconButton("i", stringResource(R.string.technical_details), { showDetails = true }, Modifier.testTag("info"))
            }
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
            GoldButton(stringResource(R.string.pick_headphones), onOpenBluetooth, Modifier.fillMaxWidth().testTag("pick_device"))
        } else if (outputs.size > 1) {
            // Two or more places the sound could go: let the owner choose.
            Text(stringResource(R.string.play_sound_on), style = MaterialTheme.typography.titleLarge, color = Q.Text)
            outputs.forEach { o ->
                val selected = preferredOutputId == o.id || (preferredOutputId == null && o == headphones.first())
                QCard(
                    Modifier.heightIn(min = 80.dp).clickable(role = Role.RadioButton) { onSelectOutput(o.id) }
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
                        .testTag("output_${o.id}"),
                    highlighted = selected,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioDot(selected)
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = MaterialTheme.typography.titleMedium, color = Q.Text)
                            Hint(stringResource(when (o.kind) {
                                DiagnosticsReport.Kind.BLUETOOTH -> R.string.bluetooth
                                DiagnosticsReport.Kind.WIRED -> R.string.wired
                                DiagnosticsReport.Kind.SPEAKER -> R.string.this_phone_speaker
                            }))
                        }
                        if (o.kind != DiagnosticsReport.Kind.SPEAKER) StatusPill(stringResource(R.string.connected), Q.Success)
                    }
                }
            }
            Hint(stringResource(R.string.play_sound_on_hint))
            GoldButton(stringResource(R.string.pick_headphones), onOpenBluetooth, Modifier.fillMaxWidth().testTag("pick_device"))
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
            GhostButton(stringResource(R.string.pick_headphones), onOpenBluetooth, Modifier.fillMaxWidth().testTag("pick_device"))
        }
        SoundTestCard(soundTest, onRunSoundTest, onAnswerSoundTest, onOpenBluetooth)
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


enum class GitHubState { IDLE, WAITING, DONE, FAILED }

@Composable
internal fun ConnectGitHubScreen(
    configured: Boolean,
    connected: Boolean,
    code: GitHubLink.DeviceCode?,
    state: GitHubState,
    onStart: () -> Unit,
    onOpenGitHub: (GitHubLink.DeviceCode) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onCreateApp: () -> Unit = {},
    onSaveClientId: (String) -> Boolean = { false },
    lastUpload: String? = null,
    testing: Boolean = false,
    onSendTest: () -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.auto_reports), onBack)
    GitHubSection(configured = configured, connected = connected, code = code, state = state, onStart = onStart, onOpenGitHub = onOpenGitHub, onDisconnect = onDisconnect, onCreateApp = onCreateApp, onSaveClientId = onSaveClientId, lastUpload = lastUpload, testing = testing, onSendTest = onSendTest)
    Footer()
}

/** Automatic crash reports: setup, connect, status and test (shown on the Crash reports page). */
@Composable
internal fun GitHubSection(
    configured: Boolean,
    connected: Boolean,
    code: GitHubLink.DeviceCode?,
    state: GitHubState,
    onStart: () -> Unit,
    onOpenGitHub: (GitHubLink.DeviceCode) -> Unit,
    onDisconnect: () -> Unit,
    onCreateApp: () -> Unit = {},
    onSaveClientId: (String) -> Boolean = { false },
    lastUpload: String? = null,
    testing: Boolean = false,
    onSendTest: () -> Unit = {},
) {
    when {
        connected -> QCard(highlighted = true) {
            StatusPill(stringResource(R.string.auto_reports_on), Q.Success)
            Body(stringResource(R.string.github_connected_text))
            when {
                testing -> StatusPill(stringResource(R.string.github_testing), Q.Primary, pulsing = true)
                lastUpload != null -> StatusPill(lastUpload, if (lastUpload.startsWith("Uploaded")) Q.Success else Q.Primary)
            }
            GoldButton(stringResource(R.string.github_send_test), onSendTest, Modifier.fillMaxWidth().testTag("github_test"),
                enabled = !testing, height = 52.dp)
            GhostButton(stringResource(R.string.github_disconnect), onDisconnect)
        }
        !configured -> QCard(highlighted = true) {
            Text(stringResource(R.string.github_setup_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            Body(stringResource(R.string.github_setup_step1))
            GoldButton(stringResource(R.string.github_create_app), onCreateApp, Modifier.fillMaxWidth().testTag("github_create_app"), height = 52.dp)
            Body(stringResource(R.string.github_setup_step2))
            var id by rememberSaveable { mutableStateOf("") }
            var invalid by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = id,
                onValueChange = { id = it.trim(); invalid = false },
                modifier = Modifier.fillMaxWidth().testTag("github_client_id"),
                singleLine = true,
                isError = invalid,
                placeholder = { Text("Iv23li…", color = Q.TextFaint) },
                label = { Text(stringResource(R.string.github_client_id)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Q.Primary, unfocusedBorderColor = Q.BorderAccent,
                    focusedLabelColor = Q.Primary, unfocusedLabelColor = Q.TextFaint,
                    focusedTextColor = Q.Text, unfocusedTextColor = Q.Text, cursorColor = Q.Primary,
                ),
            )
            if (invalid) Hint(stringResource(R.string.github_client_id_invalid))
            GhostButton(stringResource(R.string.github_save_id), { invalid = !onSaveClientId(id) }, Modifier.fillMaxWidth().testTag("github_save_id"))
        }
        code != null -> QCard(highlighted = true) {
            Text(stringResource(R.string.github_enter_code), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            GradientText(code.userCode, MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace))
            GoldButton(stringResource(R.string.github_open), { onOpenGitHub(code) }, Modifier.fillMaxWidth(), height = 56.dp)
            StatusPill(stringResource(R.string.github_waiting), Q.Primary, pulsing = true)
        }
        else -> QCard(highlighted = true) {
            Text(stringResource(R.string.github_intro_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            Body(stringResource(R.string.github_intro_text))
            GoldButton(stringResource(R.string.github_connect), onStart, Modifier.fillMaxWidth().testTag("github_connect"),
                enabled = state != GitHubState.WAITING, height = 56.dp)
            if (state == GitHubState.FAILED) Hint(stringResource(R.string.github_failed))
        }
    }
    Hint(stringResource(R.string.github_privacy))
}


@Composable
internal fun ReconnectingScreen(onBack: () -> Unit) = Screen {
    TopBar(stringResource(R.string.now_casting), onBack)
    QCard(highlighted = true) {
        StatusPill(stringResource(R.string.reconnecting), Q.Primary, pulsing = true)
        Hint(stringResource(R.string.reconnecting_hint))
    }
}


sealed interface SoundTestUi {
    data object Idle : SoundTestUi
    data object Playing : SoundTestUi
    data class Done(val result: SoundTest.Result) : SoundTestUi
    data class Answered(val result: SoundTest.Result, val heard: Boolean) : SoundTestUi
}

/** Pre-screening: does TheatreSync's movie sound reach the headphones? */
@Composable
private fun SoundTestCard(state: SoundTestUi, onRun: () -> Unit, onAnswer: (Boolean) -> Unit, onPickDevice: () -> Unit) {
    QCard(Modifier.testTag("sound_test"), highlighted = state !is SoundTestUi.Idle) {
        Text(stringResource(R.string.sound_test_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Hint(stringResource(R.string.sound_test_hint))
        when (state) {
            SoundTestUi.Idle -> GoldButton(stringResource(R.string.sound_test_run), onRun, Modifier.fillMaxWidth().testTag("sound_test_run"), height = 52.dp, leading = "♪")
            SoundTestUi.Playing -> StatusPill(stringResource(R.string.sound_test_playing), Q.Primary, pulsing = true)
            is SoundTestUi.Done -> {
                SoundTestRoute(state.result)
                Text(stringResource(R.string.sound_test_question), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldButton(stringResource(R.string.sound_test_yes), { onAnswer(true) }, Modifier.weight(1f).testTag("sound_test_yes"), height = 48.dp)
                    GhostButton(stringResource(R.string.sound_test_no), { onAnswer(false) }, Modifier.weight(1f).testTag("sound_test_no"))
                }
                GhostButton(stringResource(R.string.sound_test_again), onRun, Modifier.fillMaxWidth())
            }
            is SoundTestUi.Answered -> {
                SoundTestRoute(state.result)
                if (state.heard && state.result.isHeadphones) {
                    StatusPill(stringResource(R.string.sound_test_pass), Q.Success)
                } else {
                    Body(stringResource(if (state.result.isHeadphones) R.string.sound_test_fail_heard_nothing else R.string.sound_test_fail_wrong_device))
                    GoldButton(stringResource(R.string.pick_headphones), onPickDevice, Modifier.fillMaxWidth(), height = 48.dp)
                }
                GhostButton(stringResource(R.string.sound_test_again), onRun, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SoundTestRoute(r: SoundTest.Result) {
    val where = r.deviceName ?: stringResource(R.string.unknown_device)
    if (r.error != null) {
        Body(stringResource(R.string.sound_test_error, r.error))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sound_test_went_to), color = Q.TextMuted, style = MaterialTheme.typography.bodyLarge)
            StatusPill(where, if (r.isHeadphones) Q.Success else Q.Error)
        }
    }
}


/** What the chosen movie is, whether the TV can show it, and the quality to cast it at. */
@Composable
private fun MovieQuality(ui: MovieSession.Ui, onQuality: (Quality) -> Unit, onRoomSound: (Boolean) -> Unit = {}) {
    ui.movieInfo?.let { Hint(it) }
    val verdict = ui.movieVerdict
    if (verdict != null) {
        val text = when {
            !verdict.playable && CastCompat.Problem.CODEC in verdict.problems -> stringResource(R.string.verdict_codec)
            !verdict.playable && CastCompat.Problem.RESOLUTION in verdict.problems -> stringResource(R.string.verdict_resolution)
            !verdict.playable -> stringResource(R.string.verdict_audio_fix)
            CastCompat.Problem.BITRATE in verdict.problems -> stringResource(R.string.verdict_bitrate)
            else -> stringResource(R.string.verdict_ok)
        }
        StatusPill(text, if (verdict.playable && verdict.problems.isEmpty()) Q.Success else Q.Primary)
    }
    Text(stringResource(R.string.quality), style = MaterialTheme.typography.titleMedium, color = Q.Text)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Quality.values().forEach { q ->
            val selected = ui.quality == q
            val shape = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).height(48.dp).clip(shape)
                    .background(if (selected) Q.Gold else SolidColor(Q.Card))
                    .border(BorderStroke(1.dp, if (selected) Q.Secondary else Q.BorderAccent), shape)
                    .clickable(role = Role.RadioButton) { onQuality(q) }
                    .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
                    .testTag("quality_${q.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(when (q) { Quality.ORIGINAL -> R.string.quality_original; Quality.P1080 -> R.string.quality_1080; Quality.P720 -> R.string.quality_720 }),
                    color = if (selected) Color.Black else Q.PrimaryLight, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
    val plan = ui.moviePlan
    Hint(
        stringResource(
            when (plan) {
                CastCompat.Plan.SOUND_ONLY -> if (ui.roomSoundToo) R.string.quality_sound_only_hint else R.string.quality_picture_only_hint
                CastCompat.Plan.FULL -> R.string.quality_convert_hint
                else -> R.string.quality_original_hint
            },
        ),
    )
    if (plan == CastCompat.Plan.SOUND_ONLY) {
        SwitchRow("🔊", stringResource(R.string.room_sound_too), ui.roomSoundToo, onRoomSound)
    }
}


/** Captions: tap to turn on/off; long-press to pick the language. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CaptionsRow(onCaptions: (String?) -> Unit) {
    var on by rememberSaveable { mutableStateOf(false) }
    var language by rememberSaveable { mutableStateOf("en") }
    var picking by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val label = stringResource(if (on) R.string.yt_captions_on else R.string.yt_captions_off) +
        if (on) " · " + (CAPTION_LANGUAGES.firstOrNull { it.first == language }?.second ?: language) else ""
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(shape)
            .background(if (on) Q.Gold else SolidColor(Q.Card))
            .border(BorderStroke(1.dp, if (on) Q.Secondary else Q.BorderAccent), shape)
            .combinedClickable(
                onClick = { on = !on; onCaptions(if (on) language else null) },
                onLongClick = { picking = true },
                onLongClickLabel = stringResource(R.string.yt_captions_pick),
                role = Role.Switch,
            )
            .semantics { stateDescription = if (on) "On" else "Off" }
            .testTag("yt_captions"),
        contentAlignment = Alignment.Center,
    ) {
        Text("CC  $label", color = if (on) Color.Black else Q.PrimaryLight, style = MaterialTheme.typography.labelLarge)
    }
    Hint(stringResource(R.string.yt_captions_hint))
    if (picking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Q.Card,
            title = { Text(stringResource(R.string.yt_captions_pick), color = Q.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CAPTION_LANGUAGES.forEach { (code, name) ->
                        Text(
                            name, color = if (code == language) Q.Primary else Q.Text,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().clickable {
                                language = code; on = true; picking = false; onCaptions(code)
                            }.padding(vertical = 10.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.yt_captions_off), color = Q.TextMuted, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { on = false; picking = false; onCaptions(null) }.padding(vertical = 10.dp),
                    )
                }
            },
            confirmButton = {},
        )
    }
}

/** Common caption languages; YouTube shows them if the video has that track (or auto-translates). */
private val CAPTION_LANGUAGES = listOf(
    "en" to "English", "hi" to "Hindi", "gu" to "Gujarati", "es" to "Spanish", "fr" to "French",
    "de" to "German", "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic", "zh-Hans" to "Chinese",
)


/**
 * A second remote for the Chromecast. Everything an older Chromecast (no Google TV) can be
 * controlled with: play/pause, skip, jump, volume, mute, YouTube next/previous and captions,
 * and stop (back to the TV's home screen).
 */
@Composable
internal fun RemoteScreen(
    ui: MovieSession.Ui,
    onPlayPause: (String) -> Unit,
    onSkip: (String, Double) -> Unit,
    onVolumeStep: (String, Double) -> Unit,
    onMute: (String, Boolean) -> Unit,
    onNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onCaptions: (String, String?) -> Unit,
    onStop: (String) -> Unit,
    onYouTube: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.remote), onBack)
    Message(ui.message, onDismissMessage)
    if (ui.tvs.isEmpty()) {
        QCard { StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true) }
        return@Screen
    }
    // The TV you're using: the connected one, else the one playing something, else the first.
    val tv = ui.tvs.firstOrNull { it.name == ui.connectedTv }
        ?: ui.tvs.firstOrNull { t -> ui.nowCasting.any { it.tvId == t.id } }
        ?: ui.tvs.first()
    val activity = ui.nowCasting.firstOrNull { it.tvId == tv.id }?.activity
    QCard(highlighted = activity?.isPlaying == true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceIcon(if (tv.isSpeaker) R.drawable.ic_device_speaker else R.drawable.ic_device_cast)
            Column(Modifier.weight(1f)) {
                Text(tv.name, style = MaterialTheme.typography.titleMedium, color = Q.Text)
                Hint(activity?.let { a -> a.title ?: a.label() } ?: stringResource(R.string.remote_idle))
            }
        }
        // Transport
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(R.string.skip_back_short), { onSkip(tv.id, -10.0) }, height = 52.dp, description = stringResource(R.string.skip_back))
            PlayButton(playing = activity?.isPlaying == true, onClick = { onPlayPause(tv.id) })
            GhostButton(stringResource(R.string.skip_fwd_short), { onSkip(tv.id, 10.0) }, height = 52.dp, description = stringResource(R.string.skip_forward))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("−30s", { onSkip(tv.id, -30.0) }, Modifier.weight(1f), height = 48.dp, description = "Back 30 seconds")
            GhostButton("+30s", { onSkip(tv.id, 30.0) }, Modifier.weight(1f), height = 48.dp, description = "Forward 30 seconds")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("⏮  " + stringResource(R.string.yt_previous), { onPrevious(tv.id) }, Modifier.weight(1f), height = 48.dp)
            GhostButton(stringResource(R.string.yt_next) + "  ⏭", { onNext(tv.id) }, Modifier.weight(1f), height = 48.dp)
        }
    }
    QCard {
        Text(stringResource(R.string.tv_volume), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−", { onVolumeStep(tv.id, -0.05) }, Modifier.weight(1f).testTag("remote_vol_down"), height = 56.dp, description = "Volume down")
            val muted = activity?.muted == true
            GoldButton(stringResource(if (muted) R.string.unmute else R.string.mute), { onMute(tv.id, !muted) }, Modifier.weight(1.4f), height = 56.dp)
            GhostButton("+", { onVolumeStep(tv.id, 0.05) }, Modifier.weight(1f).testTag("remote_vol_up"), height = 56.dp, description = "Volume up")
        }
        activity?.volumeLevel?.let { Hint(stringResource(R.string.remote_volume, (it * 100).toInt())) }
    }
    QCard {
        CaptionsRow { lang -> onCaptions(tv.id, lang) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GoldButton("▶  YouTube", onYouTube, Modifier.weight(1f), height = 52.dp)
        GhostButton("■  " + stringResource(R.string.remote_home), { onStop(tv.id) }, Modifier.weight(1f), height = 52.dp)
    }
    Hint(stringResource(R.string.remote_hint))
    Footer()
}


/** Home screen while a movie runs: tap to go back to its player. */
@Composable
private fun MoviePlayingCard(ui: MovieSession.Ui, onOpen: () -> Unit) {
    QCard(Modifier.clickable(role = Role.Button, onClick = onOpen).testTag("movie_playing"), highlighted = true, orbit = ui.phase == Phase.PLAYING) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceIcon(R.drawable.ic_movie)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (ui.phase) {
                            Phase.ENDED -> R.string.status_ended
                            Phase.PAUSED -> R.string.status_paused
                            else -> R.string.movie_playing_on
                        },
                        ui.connectedTv ?: "TV",
                    ),
                    color = Q.Primary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(ui.movieTitle ?: "", color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Q.Primary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
        }
        Hint(stringResource(R.string.movie_playing_hint))
    }
}


/** Small "Headphones" section at the bottom of the players: one button to all headphone options. */
@Composable
private fun HeadphoneOptionsButton(onClick: () -> Unit) {
    Eyebrow(stringResource(R.string.headphones))
    GhostButton("🎧  " + stringResource(R.string.hp_options), onClick, Modifier.fillMaxWidth().testTag("hp_options"), height = 52.dp)
}

/**
 * Additional headphone settings (⚙ on the Headphones page, ☰ → Headphones, or the Headphones
 * button in a player): device, sync tool and equalizer.
 */
@Composable
internal fun HeadphoneSettingsScreen(
    ui: MovieSession.Ui,
    outputs: List<DiagnosticsReport.Output>,
    onSelectOutput: (Int?) -> Unit,
    onPickDevice: () -> Unit,
    onDelay: (Int) -> Unit,
    onResetSync: () -> Unit,
    onFx: (HeadphoneFx.Settings) -> Unit,
    onSoundTest: () -> Unit,
    onBack: () -> Unit,
    onAutoSwitch: (Boolean) -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.hp_settings), onBack)
    // Be upfront: choosing headphones here can't move YouTube's sound off the TV.
    if (ui.phase == Phase.SETUP && ui.nowCasting.any { it.activity.kind == CastActivity.Kind.YOUTUBE }) {
        QCard(highlighted = true) {
            Text(stringResource(R.string.cast_sound_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
            Hint(stringResource(R.string.hp_youtube_note))
        }
    }

    // Device
    QCard {
        Text(stringResource(R.string.hp_device), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        val choices = outputs
        if (choices.isEmpty()) Hint(stringResource(R.string.hp_no_devices))
        choices.forEach { o ->
            val selected = o.id == ui.preferredOutputId || (ui.preferredOutputId == null && o.kind != DiagnosticsReport.Kind.SPEAKER && choices.count { it.kind != DiagnosticsReport.Kind.SPEAKER } == 1)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, if (selected) Q.Primary else Q.BorderAccent), RoundedCornerShape(14.dp))
                    .clickable(role = Role.RadioButton) { onSelectOutput(o.id) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(selected)
                Text(o.name, Modifier.padding(start = 12.dp).weight(1f), color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SwitchRow("⚡", stringResource(R.string.auto_switch), ui.autoSwitch, onAutoSwitch)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.hp_connect_other), onPickDevice, Modifier.weight(1f), height = 48.dp)
            GhostButton(stringResource(R.string.hp_test), onSoundTest, Modifier.weight(1f), height = 48.dp)
        }
        Hint(stringResource(R.string.hp_one_device))
    }

    // Sync tool
    QCard {
        Text(stringResource(R.string.hp_sync), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        val drift = ui.driftMs
        StatusPill(
            if (drift != null) stringResource(R.string.hp_sync_live, kotlin.math.abs(drift)) else stringResource(R.string.hp_sync_idle),
            if (drift != null && kotlin.math.abs(drift) <= 40) Q.Success else Q.Primary,
            pulsing = drift != null,
        )
        Text(stringResource(R.string.hp_delay_value, ui.soundDelayMs), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("−50", { onDelay(-50) }, Modifier.weight(1f), height = 48.dp, description = "Sound 50 ms earlier")
            GhostButton("−10", { onDelay(-10) }, Modifier.weight(1f), height = 48.dp, description = "Sound 10 ms earlier")
            GhostButton("+10", { onDelay(10) }, Modifier.weight(1f), height = 48.dp, description = "Sound 10 ms later")
            GhostButton("+50", { onDelay(50) }, Modifier.weight(1f), height = 48.dp, description = "Sound 50 ms later")
        }
        Hint(stringResource(R.string.hp_sync_hint))
        GhostButton("↺  " + stringResource(R.string.hp_reset_sync), onResetSync, Modifier.fillMaxWidth().testTag("hp_reset_sync"), height = 48.dp)
    }

    // Equalizer
    val fx = ui.fx
    QCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hp_eq), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Q.Text)
            Switch(
                checked = fx.enabled, onCheckedChange = { onFx(fx.copy(enabled = it)) },
                modifier = Modifier.testTag("eq_switch"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                HeadphoneFx.Preset.FLAT to R.string.eq_flat, HeadphoneFx.Preset.BASS_BOOST to R.string.eq_bass,
                HeadphoneFx.Preset.DIALOGUE to R.string.eq_dialogue, HeadphoneFx.Preset.CINEMA to R.string.eq_cinema,
            ).forEach { (preset, label) ->
                val selected = fx.enabled && fx.preset == preset
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier.weight(1f).height(44.dp).clip(shape)
                        .background(if (selected) Q.Gold else SolidColor(Q.Card))
                        .border(BorderStroke(1.dp, if (selected) Q.Secondary else Q.BorderAccent), shape)
                        .clickable(role = Role.RadioButton) { onFx(HeadphoneFx.withPreset(fx, preset)) }
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label), color = if (selected) Color.Black else Q.PrimaryLight, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
        EqSlider(stringResource(R.string.eq_bass_boost), fx.bass / 1000f, enabled = fx.enabled) {
            onFx(fx.copy(bass = (it * 1000).toInt(), preset = HeadphoneFx.Preset.CUSTOM))
        }
        HeadphoneFx.BAND_LABELS.forEachIndexed { i, hz ->
            EqSlider("$hz Hz", (fx.bandsDb[i] + HeadphoneFx.MAX_DB) / (2f * HeadphoneFx.MAX_DB), enabled = fx.enabled, valueText = "%+d dB".format(fx.bandsDb[i])) { v ->
                val db = Math.round(v * 2 * HeadphoneFx.MAX_DB) - HeadphoneFx.MAX_DB
                onFx(fx.copy(bandsDb = fx.bandsDb.toMutableList().also { it[i] = db }, preset = HeadphoneFx.Preset.CUSTOM))
            }
        }
        Hint(stringResource(R.string.hp_eq_hint))
    }
    Footer()
}

@Composable
private fun EqSlider(label: String, value: Float, enabled: Boolean, valueText: String? = null, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), color = if (enabled) Q.Text else Q.TextFaint, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            enabled = enabled, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Q.Primary, activeTrackColor = Q.Primary, inactiveTrackColor = Q.Border),
        )
        Text(valueText ?: "${(value * 100).toInt()}%", Modifier.width(52.dp).padding(start = 6.dp), color = Q.TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}


/** Radio mark drawn at a fixed size, so rows line up whether selected or not (text glyphs differ in width). */
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).border(BorderStroke(2.dp, Q.Primary), CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Q.Primary))
    }
}


/** Small "picture in picture" button for the player top bars. */
@Composable
private fun PipButton(onClick: () -> Unit) =
    RoundIconButton("⧉", stringResource(R.string.pip), onClick, Modifier.testTag("pip"))

/** What the floating window shows: the TV's thumbnail, title and state. */
@Composable
private fun PipContent(ui: MovieSession.Ui, casting: TvWatchers.NowCasting?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (casting != null) {
            Thumb(casting.activity, Modifier.fillMaxSize(), big = true)
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(8.dp),
        ) {
            val title = casting?.activity?.title ?: ui.movieTitle ?: stringResource(R.string.app_name)
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val playing = casting?.activity?.isPlaying ?: (ui.phase == Phase.PLAYING)
            Text(
                (if (playing) "▶ " else "❚❚ ") + (casting?.tvName ?: ui.connectedTv ?: ""),
                color = Q.Primary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
    }
}


/** QR code (black on white) for [text], or null if it can't be made. */
internal fun qrBitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching<androidx.compose.ui.graphics.ImageBitmap> {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()


/** Settings → Experimental: features that are fun but optional. */
@Composable
internal fun ExperimentalScreen(ui: MovieSession.Ui, onSignatureIntro: (Boolean) -> Unit, onBack: () -> Unit) = Screen {
    TopBar(stringResource(R.string.experimental), onBack)
    QCard(highlighted = ui.signatureIntro) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.signature_intro), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(stringResource(R.string.signature_intro_short))
            }
            Switch(
                checked = ui.signatureIntro, onCheckedChange = onSignatureIntro,
                modifier = Modifier.testTag("signature_intro"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Body(stringResource(R.string.signature_intro_text))
        Hint(stringResource(R.string.signature_intro_note))
    }
    Footer()
}


/** Top-right player buttons: Audio sources, the remote, picture in picture. */
@Composable
private fun PlayerActions(onAudio: () -> Unit, onRemote: () -> Unit, onPip: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton("🎧", stringResource(R.string.audio), onAudio, Modifier.testTag("audio"))
        RoundIconButton("🎛", stringResource(R.string.remote), onRemote, Modifier.testTag("open_remote"))
        PipButton(onPip)
    }
}

/** One line on the movie page saying where the sound goes; tap for the Audio sheet. */
@Composable
private fun AudioSummary(ui: MovieSession.Ui, onAudio: () -> Unit) {
    QCard(Modifier.clickable(role = Role.Button, onClick = onAudio).testTag("audio_summary")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎧", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.audio), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                val places = listOfNotNull(
                    if (ui.headphonesHere) (ui.headphoneName ?: stringResource(R.string.this_phone)) else null,
                    if (!ui.muteTv) stringResource(R.string.room_speakers) else null,
                    if (ui.friends > 0) pluralStringResource(R.plurals.guests_listening, ui.friends, ui.friends) else null,
                )
                Hint(places.joinToString(" · ").ifBlank { stringResource(R.string.audio_nowhere) })
            }
            Text("›", color = Q.Primary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/** Audio sources: tick everywhere the sound should play. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioSheet(
    ui: MovieSession.Ui,
    outputs: List<DiagnosticsReport.Output>,
    connectedHeadsets: List<String>,
    listenUrl: String?,
    onRoomSpeakers: (Boolean) -> Unit,
    onSelectOutput: (Int?) -> Unit,
    onPickDevice: () -> Unit,
    onShareNfc: () -> Unit,
    onShowQr: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Q.Card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.audio_sources), style = MaterialTheme.typography.titleLarge, color = Q.Text)
            Hint(stringResource(R.string.audio_sources_hint))

            // Room speakers
            CheckRow(
                checked = !ui.muteTv, title = stringResource(R.string.room_speakers),
                subtitle = stringResource(if (ui.tvSoundless && ui.muteTv) R.string.room_speakers_restart else R.string.room_speakers_hint),
                tag = "check_room", onChange = onRoomSpeakers,
            )

            // Headphones and the phone speaker. Android plays on one Bluetooth headset at a time.
            val active = outputs.firstOrNull { o -> o.id == ui.preferredOutputId } ?: outputs.firstOrNull { it.kind != DiagnosticsReport.Kind.SPEAKER } ?: outputs.firstOrNull()
            val names = (outputs.map { it.name } + connectedHeadsets).distinct()
            var triedSecond by remember { mutableStateOf(false) }
            names.forEach { name ->
                val output = outputs.firstOrNull { it.name == name }
                val isActive = output != null && output == active
                CheckRow(
                    checked = isActive, title = name,
                    subtitle = stringResource(
                        when {
                            output?.kind == DiagnosticsReport.Kind.SPEAKER -> R.string.out_speaker
                            output == null -> R.string.headset_connected_idle
                            else -> R.string.headset_playing
                        },
                    ),
                    tag = "check_out_$name",
                    onChange = { on ->
                        when {
                            !on -> Unit // something always plays; tick another one to move the sound
                            output != null -> { if (active != null && active.kind != DiagnosticsReport.Kind.SPEAKER && output.kind != DiagnosticsReport.Kind.SPEAKER) triedSecond = true; onSelectOutput(output.id) }
                            else -> { triedSecond = true; onPickDevice() }
                        }
                    },
                )
            }
            if (triedSecond || connectedHeadsets.size > 1) {
                QCard(highlighted = true) {
                    Text(stringResource(R.string.two_headsets_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                    Hint(stringResource(R.string.two_headsets_text))
                }
            }
            GhostButton(stringResource(R.string.hp_connect_other), onPickDevice, Modifier.fillMaxWidth(), height = 48.dp)

            // Guests
            Text(stringResource(R.string.guests_title), style = MaterialTheme.typography.titleMedium, color = Q.Text, modifier = Modifier.padding(top = 8.dp))
            if (listenUrl == null) Hint(stringResource(R.string.guests_need_movie))
            else {
                Hint(stringResource(R.string.guests_hint))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldButton(stringResource(R.string.share_nfc), onShareNfc, Modifier.weight(1f).testTag("share_nfc"), height = 52.dp)
                    GhostButton(stringResource(R.string.show_qr), onShowQr, Modifier.weight(1f).testTag("show_qr"), height = 52.dp)
                }
            }
            GhostButton("⚙  " + stringResource(R.string.hp_settings), onMore, Modifier.fillMaxWidth(), height = 48.dp)
        }
    }
}

/** A big tappable row with a checkbox. */
@Composable
private fun CheckRow(checked: Boolean, title: String, subtitle: String?, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, if (checked) Q.Primary else Q.BorderAccent), RoundedCornerShape(14.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked, onCheckedChange = null,
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Q.Primary, uncheckedColor = Q.PrimaryLight, checkmarkColor = Color.Black),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Hint(it) }
        }
    }
}

/** "Share by NFC": the guest holds their phone to this one. TheatreSync answers taps only while this is open. */
@Composable
private fun NfcShareDialog(onDone: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = Q.Card,
        title = { Text(stringResource(R.string.share_nfc), color = Q.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusPill(stringResource(R.string.nfc_hold), Q.Primary, pulsing = true)
                Text(stringResource(R.string.nfc_steps), color = Q.TextMuted)
            }
        },
        confirmButton = { GoldButton(stringResource(R.string.done), onDone, height = 48.dp) },
    )
}

/** The QR code, only when the owner asks for it. */
@Composable
private fun QrDialog(url: String, onDone: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = Q.Card,
        title = { Text(stringResource(R.string.show_qr), color = Q.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val qr = remember(url) { qrBitmap(url, 512) }
                if (qr != null) {
                    androidx.compose.foundation.Image(
                        qr, contentDescription = stringResource(R.string.tapin_qr_description),
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp).testTag("tapin_qr"),
                    )
                }
                Text(stringResource(R.string.qr_steps), color = Q.TextMuted)
            }
        },
        confirmButton = { GoldButton(stringResource(R.string.done), onDone, height = 48.dp) },
    )
}
