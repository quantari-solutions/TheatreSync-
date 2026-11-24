package com.theatresync.app

import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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

enum class Page { HOME, HEADPHONES, NOW_CASTING, YOUTUBE, MIRROR, SETTINGS, CRASHES, GITHUB, REMOTE, MOVIE, HEADPHONE_SETTINGS, EXPERIMENTAL, MULTI_AUDIO, REMOTE_CAST }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val app: TheatreSyncApplication get() = application as TheatreSyncApplication
    private val session: MovieSession get() = app.session
    private val outputs = mutableStateOf<List<DiagnosticsReport.Output>>(emptyList())
    private val details = mutableStateOf("")
    private val sharedLink = mutableStateOf<String?>(null)
    /** The YouTube page's link and chosen TV live here, so nothing on the way (TV list refreshes, the
     *  Chromecast switching apps, a trip to another page) can blank them while casting. */
    private val ytLink = mutableStateOf("")
    private val ytTvName = mutableStateOf<String?>(null)
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

    private val pickSubtitles = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.loadSubtitles(uri)
    }

    /** Subtitles are made from the movie file itself: no microphone permission needed. */
    private fun generateSubtitles(spoken: String, shownIn: String, label: String) = session.generateSubtitles(spoken, shownIn, label)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshAudio()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshAudio()
    }

    private fun refreshAudio() {
        outputs.value = DiagnosticsReport.friendlyOutputs(this)
        details.value = DiagnosticsReport.build(this)
        refreshConnectedHeadsets()
    }

    /** Battery % per headset name, where the phone shares it. */
    private val headsetBattery = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Android tracks headset battery (shown in its Bluetooth settings) but has no public API for it. */
    private fun batteryLevel(device: android.bluetooth.BluetoothDevice): Int? = runCatching {
        (device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int).takeIf { it in 0..100 }
    }.getOrNull()

    /** Every Bluetooth headset connected to the phone (Android plays on the active one only). */
    private val connectedHeadsets = mutableStateOf<List<String>>(emptyList())

    @android.annotation.SuppressLint("MissingPermission") // checked just below
    private fun refreshConnectedHeadsets() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    connectedHeadsets.value = devices.mapNotNull { it.name }
                    headsetBattery.value = devices.mapNotNull { d -> d.name?.let { n -> batteryLevel(d)?.let { n to it } } }.toMap()
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
        // Two phones back to back both try to read each other; while sharing, this phone only answers
        // (acts as the tag), so the guest's phone reads the page instead of this one reading theirs
        // (the "choose TheatreSync" prompt with nothing on the other phone). Android 15+.
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            if (on) adapter.setDiscoveryTechnology(this, android.nfc.NfcAdapter.FLAG_READER_DISABLE, android.nfc.NfcAdapter.FLAG_LISTEN_KEEP)
            else adapter.resetDiscoveryTechnology(this)
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
        // The opening plays only when TheatreSync starts fresh: not when Android rebuilds the screen of an app
        // that was running in the background (it has saved state), nor from the notification or a share.
        // Coming back to it while it's running never shows it (onStart does nothing here).
        if (openingGate.created(fromNotificationOrShare(intent), restoredFromBackground = savedInstanceState != null) && !runningUnderTest) openingRequest.intValue++
        handleShare(intent)
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_REMOTE) openRemote.intValue++
        mirroringLabel = getString(R.string.screen_mirroring)
        mirroringHeadline = getString(R.string.mirroring_a_screen)
        videoLabel = getString(R.string.video_label)
        setContent {
            QuantariTheme {
                GridBackground {
                    val ui by session.ui.collectAsState()
                    val updateState = AppUpdates.state.collectAsState()
                    var page by rememberSaveable { mutableStateOf(Page.HOME) }
                    var castTvId by rememberSaveable { mutableStateOf<String?>(null) }
                    val casting = ui.nowCasting.firstOrNull { it.tvId == castTvId }

                    var hpSettingsFrom by rememberSaveable { mutableStateOf(Page.HOME) }
                    fun openHeadphoneSettings(from: Page) { hpSettingsFrom = from; refreshAudio(); page = Page.HEADPHONE_SETTINGS }
                    BackHandler(enabled = page != Page.HOME) {
                        page = when (page) {
                            Page.CRASHES, Page.GITHUB, Page.REMOTE, Page.EXPERIMENTAL, Page.MULTI_AUDIO -> Page.SETTINGS
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
                            battery = headsetBattery.value,
                            listenUrl = listenUrl,
                            onRoomSpeakers = session::setRoomSpeakers,
                            onSelectOutput = session::setPreferredOutput,
                            onPickDevice = ::pickAudioDevice,
                            onShareNfc = { setNfcSharing(true, listenUrl) },
                            onShowQr = { qrDialog.value = true },
                            onGuestSound = session::setGuestSound,
                            onGuestVolume = session::setGuestVolume,
                            onGuestRemove = session::removeGuest,
                            onYouTubeHeadphones = session::setYouTubeHeadphones,
                            onYouTubeRoomSpeakers = { tv, on -> session.castMute(tv, !on) },
                            onMore = { audioSheet.value = false; openHeadphoneSettings(page) },
                            onDismiss = { audioSheet.value = false },
                        )
                    }
                    if (nfcSharing.value) NfcShareDialog(onDone = { setNfcSharing(false, null) })
                    if (smartTvInfo.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { smartTvInfo.value = false },
                            containerColor = Q.Card,
                            title = { Text(stringResource(R.string.smart_tv_title), color = Q.Text) },
                            text = { Text(stringResource(R.string.smart_tv_text), color = Q.TextMuted) },
                            confirmButton = { GoldButton(stringResource(R.string.youtube), { smartTvInfo.value = false; page = Page.YOUTUBE }, height = 48.dp) },
                            dismissButton = { GhostButton(stringResource(R.string.done), { smartTvInfo.value = false }, height = 48.dp) },
                        )
                    }
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
                        page == Page.MULTI_AUDIO && ui.multiAudio -> "multiaudio"
                        page == Page.SETTINGS -> "settings"
                        page == Page.CRASHES -> "crashes"
                        page == Page.HEADPHONES -> "headphones"
                        page == Page.YOUTUBE -> "youtube"
                        page == Page.MIRROR -> "mirror"
                        page == Page.REMOTE_CAST -> "remotecast"
                        page == Page.NOW_CASTING && casting != null -> "casting"
                        page == Page.MOVIE && ui.phase != Phase.SETUP -> "playing"
                        else -> "setup"
                    }
                    // YouTube sound in the headphones: one phone player for as long as it's on, created here (before
                    // the opening, so the opening never interrupts the sound) and shown wherever it fits below.
                    val ytTv = ui.youTubeHeadphonesTv
                    val ytVideo = ytTv?.let { id -> ui.nowCasting.firstOrNull { it.tvId == id }?.activity?.contentId }
                    val ytWeb = if (ytTv != null && ytVideo != null) {
                        rememberYouTubeHeadphonesWeb(ytTv, ytVideo, { session.youTubeTarget(ytTv) }, session::onYouTubeHeadphonesReport)
                    } else null
                    // The Quantari opening, only when TheatreSync starts fresh (see onCreate).
                    var openingDone by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    if (openingRequest.intValue > openingDone) {
                        // Logged so the emulator check (CI) can prove it shows and for how long.
                        val shownAt = remember(openingRequest.intValue) { android.os.SystemClock.elapsedRealtime().also { android.util.Log.i("TheatreSync", "Opening: shown") } }
                        OpeningAnimation {
                            android.util.Log.i("TheatreSync", "Opening: finished after ${android.os.SystemClock.elapsedRealtime() - shownAt} ms")
                            openingDone = openingRequest.intValue
                        }
                        return@GridBackground
                    }
                    if (inPip.value) {
                        if (ytWeb != null && ytVideo != null) {
                            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                YouTubeHeadphonesView(ytWeb, ytVideo)
                            }
                        } else PipContent(ui, ui.nowCasting.firstOrNull { it.tvId == castTvId } ?: ui.nowCasting.firstOrNull())
                        return@GridBackground
                    }
                    // Pull down anywhere to look for TVs, smart TVs and headphones again.
                    var refreshing by remember { mutableStateOf(false) }
                    val hostView = androidx.compose.ui.platform.LocalView.current
                    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = refreshing,
                        state = pullState,
                        // Our own spinner: always whole turns, then it goes (it calls back when done).
                        indicator = { RefreshSpinner(pullState, refreshing) { refreshing = false } },
                        onRefresh = {
                            // A gentle buzz on release, so you can feel it refreshed.
                            hostView.performHapticFeedback(
                                if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                            )
                            refreshing = true
                            session.refreshDevices()
                            refreshAudio()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AnimatedContent(
                            targetState = target,
                            // Owner: no animations, everything instant and simple.
                            transitionSpec = { androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None },
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
                                    onCopyCode = ::copyGitHubCode,
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
                                    onShareApp = ::shareApp,
                                    multiAudioOn = ui.multiAudio,
                                    onMultiAudio = { page = Page.MULTI_AUDIO },
                                    updates = updateState.value,
                                    onCheckUpdate = { AppUpdates.check(this@MainActivity, force = true) },
                                    onUpdate = { AppUpdates.update(this@MainActivity) },
                                    samsungIp = session.samsungIp(),
                                    onSamsungIp = session::setSamsungIp,
                                )
                                "experimental" -> ExperimentalScreen(
                                    ui = ui,
                                    onSignatureIntro = session::setSignatureIntro,
                                    onScreensaver = session::setScreensaver,
                                    onMultiAudio = session::setMultiAudio,
                                    onAdSkipper = session::setAdSkipper,
                                    onRemoteCast = session::setRemoteCast,
                                    onBack = { page = Page.SETTINGS },
                                )
                                "multiaudio" -> MultiAudioScreen(
                                    status = remember { audioSharingSupport() },
                                    connectedHeadsets = connectedHeadsets.value,
                                    onOpenSharing = ::openAudioSharing,
                                    onTapIn = { refreshAudio(); audioSheet.value = true },
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
                                    onDismissMessage = session::dismissMessage,
                                    onWake = session::wakeTv,
                                    onPower = session::setRoomPower,
                                    onFindPower = session::findPowerDevices,
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
                                    onCopyCode = ::copyGitHubCode,
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
                                    link = ytLink.value,
                                    onLink = { ytLink.value = it },
                                    tvName = ytTvName.value,
                                    onTv = { ytTvName.value = it },
                                    onCast = { id, tv -> session.castYouTube(id, tv) },
                                    onOpenApp = { link -> openYouTubeApp(link) },
                                    onBack = { session.resetYouTube(); sharedLink.value = null; page = Page.HOME },
                                )
                                "remotecast" -> RemoteCastScreen(
                                    tvs = ui.tvs.filter { it.away }.map { it.name to session.rememberedKind(it.name) },
                                    onYouTube = { page = Page.YOUTUBE },
                                    onBack = { page = Page.HOME },
                                )
                                "mirror" -> MirrorScreen(
                                    onStart = { session.playIntroThen(session.defaultTvId()) { startMirroring() } },
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
                                        onCastAnother = { page = Page.YOUTUBE },
                                        message = ui.message,
                                        onDismissMessage = session::dismissMessage,
                                        upNext = ui.upNext[c.tvId].orEmpty(),
                                        onUpNext = { id -> session.playUpNext(c.tvId, id) },
                                        onRefreshUpNext = { session.refreshUpNext(c.tvId) },
                                        ytHeadphonesOn = ui.youTubeHeadphonesTv == c.tvId,
                                        ytHeadphonesDriftMs = ui.youTubeHeadphonesDriftMs,
                                        ytHeadphonesProblem = ui.youTubeHeadphonesProblem,
                                        onYtHeadphones = { on -> session.setYouTubeHeadphones(c.tvId, on) },
                                        // On this page the headphones player sits inside its card (not floating over the page).
                                        ytPlayer = if (ytWeb != null && ytVideo != null && ytTv == c.tvId) { { YouTubeHeadphonesView(ytWeb, ytVideo, compact = true) } } else null,
                                        headphoneName = ui.headphoneName,
                                        ytTapInUrl = if (c.activity.kind == CastActivity.Kind.YOUTUBE) remember(c.tvId) { session.youTubeTapInUrl(c.tvId) } else null,
                                        onShareNfc = { url -> setNfcSharing(true, url) },
                                        onListenAlong = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
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
                                onSmartTv = { name -> ytTvName.value = name; page = Page.YOUTUBE },
                                onSubtitles = SubtitleActions(
                                    supported = session.subtitlesSupported(),
                                    onLoad = { pickSubtitles.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream")) },
                                    onGenerate = ::generateSubtitles,
                                    onCancel = session::cancelSubtitles,
                                    onClear = session::clearSubtitles,
                                ),
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
                                    onRemoteCast = { page = Page.REMOTE_CAST },
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
                                    onHeadphonesHere = session::setHeadphonesHere,
                                    onMuteTv = session::setMuteTv,
                                    onStop = session::stopMovie,
                                    onDismiss = session::dismissMessage,
                                    onBack = { page = Page.HOME },
                                    onHeadphoneOptions = { openHeadphoneSettings(Page.MOVIE) },
                                    onPip = ::enterPip,
                                    onSubtitlesOn = session::setSubtitlesOn,
                                    onResync = session::resetHeadphoneSync,
                                    onAudio = { refreshAudio(); audioSheet.value = true },
                                    onRemote = { page = Page.REMOTE },
                                    onShareNfc = { ui.joinAddress?.let { setNfcSharing(true, "http://$it/listen") } },
                                )
                            }
                        }
                        // Other pages: a small framed player in the corner (YouTube requires it to stay visible),
                        // labelled, with ✕ to switch headphone sound off.
                        if (ytWeb != null && ytVideo != null && ytTv != null && target != "casting") {
                            Column(
                                Modifier.align(Alignment.BottomEnd).padding(16.dp).width(IntrinsicSize.Max)
                                    .background(Q.Card, RoundedCornerShape(14.dp))
                                    .border(1.dp, Q.BorderAccent, RoundedCornerShape(14.dp))
                                    .padding(6.dp)
                                    .testTag("yt_mini_player"),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.yt_mini_label), color = Q.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(
                                        "✕", color = Q.Primary, style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.yt_mini_close)) { session.setYouTubeHeadphones(ytTv, false) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("yt_mini_close"),
                                    )
                                }
                                YouTubeHeadphonesView(ytWeb, ytVideo, compact = true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep running in the background for as long as needed (owner). Started now, while on screen,
        // because Android doesn't allow it from the background.
        if (!runningUnderTest) HostService.start(this, movie = false)
        askToRunInBackgroundOnce()
        refreshCrashes()
        AppUpdates.check(this)        // quietly, at most every 10 minutes
        GitHubLink.keepAlive(this)    // renew the crash-report sign-in before it runs out
        CrashReporter.prune(this)     // reports older than 69 days are deleted from the phone
        AppUpdates.onResume(this)     // back from allowing updates: carry on
        session.startTvSearch()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAudio()
    }

    override fun onStop() {
        // Left for other apps (screen still on) — not the phone going idle, a rotation or picture-in-picture.
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
    private val smartTvInfo = mutableStateOf(false)
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

    /** Each increase shows the Quantari opening once. */
    private val openingRequest = androidx.compose.runtime.mutableIntStateOf(0)
    private val openingGate = OpeningGate()

    /**
     * Once: ask Android to let TheatreSync always run in the background (its own "Let app always run in
     * background?" dialog). Phones like Motorola otherwise close background apps to save battery.
     * Asked a few seconds after start, so it never covers the opening.
     */
    private fun askToRunInBackgroundOnce() {
        if (runningUnderTest) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedBattery", false)) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        window.decorView.postDelayed({
            if (isFinishing || prefs.getBoolean("askedBattery", false)) return@postDelayed
            prefs.edit().putBoolean("askedBattery", true).apply()
            @android.annotation.SuppressLint("BatteryLife")
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
            runCatching { startActivity(ask) }
        }, 8_000)
    }

    private fun fromNotificationOrShare(i: Intent?) =
        i?.getStringExtra(EXTRA_OPEN) != null || i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_VIEW

    /** "Share → TheatreSync" from the YouTube app (or any app sharing a YouTube link). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (YouTubeLinks.videoId(text) != null) { sharedLink.value = text; ytLink.value = text }
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
                runOnUiThread { gitHubCode.value = code; copyGitHubCode(code.userCode) } // ready to paste on GitHub's page
                val deadline = System.currentTimeMillis() + 15 * 60_000
                var interval = code.intervalSec * 1000L + 500
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval)
                    val result = GitHubLink.poll(this, code)
                    if (result == GitHubLink.Poll.SLOW_DOWN) interval += 5_000 // GitHub's rule: wait 5 s longer each time it asks
                    if (result == GitHubLink.Poll.APPROVED) {
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

    /** Copies the GitHub sign-in code, so it can just be pasted on GitHub's page. */
    private fun copyGitHubCode(code: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("GitHub code", code))
        // Android 13+ shows its own "Copied" bubble; older versions get ours.
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, getString(R.string.github_code_copied), android.widget.Toast.LENGTH_SHORT).show()
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

    /** Settings → Share TheatreSync: sends this app (APK) to another Android phone; iPhones join with TapIn. */
    private fun shareApp() {
        Thread({
            runCatching {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                val apk = java.io.File(dir, "TheatreSync-${BuildConfig.VERSION_NAME}.apk")
                if (!apk.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                    java.io.File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.share", apk)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, getString(R.string.share_app))) }
            }.onFailure { CrashReporter.handled(this, "share app", it) }
        }, "share-app").start()
    }

    /** Can this phone share one audio stream to several LE Audio headphones (Auracast)? */
    private fun audioSharingSupport(): AudioSharing {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return AudioSharing.NO_BLUETOOTH
        if (android.os.Build.VERSION.SDK_INT < 33) return AudioSharing.UNSUPPORTED
        return when (runCatching { adapter.isLeAudioBroadcastSourceSupported }.getOrDefault(android.bluetooth.BluetoothStatusCodes.FEATURE_NOT_SUPPORTED)) {
            android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED -> AudioSharing.SUPPORTED
            else -> AudioSharing.UNSUPPORTED
        }
    }

    /** Opens Android's own audio-sharing screen where the phone has one, else Bluetooth settings. */
    private fun openAudioSharing() {
        val attempts = listOf(
            Intent("com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
            Intent("android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )
        attempts.firstOrNull { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
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
    onRemoteCast: () -> Unit = onYouTube,
    onMirror: () -> Unit = {},
    onMenu: () -> Unit = {},
    onQuality: (Quality) -> Unit = {},
    onCancelPrepare: () -> Unit = {},
    onClearMovie: () -> Unit = {},
    onOpenMovie: () -> Unit = {},
    onRoomSound: (Boolean) -> Unit = {},
    onSmartTv: (String) -> Unit = {},
    onSubtitles: SubtitleActions = SubtitleActions(),
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

    val awayTvs = ui.tvs.filter { it.away }
    val remoteCasting = ui.nowCasting.any { it.tvId.startsWith("dial:away:") }
    if (awayTvs.isNotEmpty() && !remoteCasting && ui.tvs.none { !it.away && !it.youTubeOnly }) QCard(highlighted = true) {
        Text(stringResource(R.string.remote_cast_home_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.remote_cast_home_text, awayTvs.joinToString(", ") { it.name }))
        GoldButton(stringResource(R.string.remote_cast_home_button), onRemoteCast, modifier = Modifier.fillMaxWidth().testTag("remote_cast_home"))
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
            SubtitlesSection(ui, onSubtitles)
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
            else -> ui.tvs.forEach { tv ->
                val busy = ui.nowCasting.firstOrNull { it.tvId == tv.id }?.activity
                // Smart TVs (e.g. Samsung The Frame) are in the same list; tapping one opens YouTube with that TV chosen.
                TvRow(tv.name, busy?.label(), tv.isSpeaker, tv.youTubeOnly, tv.away, tv.kind) { if (tv.youTubeOnly) onSmartTv(tv.name) else onConnectTv(tv.id) }
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
    onShareApp: () -> Unit = {},
    multiAudioOn: Boolean = false,
    onMultiAudio: () -> Unit = {},
    updates: AppUpdates.State = AppUpdates.State(),
    onCheckUpdate: () -> Unit = {},
    onUpdate: () -> Unit = {},
    samsungIp: String = "",
    onSamsungIp: (String) -> Unit = {},
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
    // Experimental "Share audio" appears in the menu only when switched on in Experimental.
    if (multiAudioOn) SettingsRow("🎶", stringResource(R.string.multi_audio), null, onMultiAudio, "settings_multi_audio", status = stringResource(R.string.multi_audio_status))
    SettingsRow("📤", stringResource(R.string.share_app), null, onShareApp, "settings_share", status = stringResource(R.string.share_app_status))
    SettingsRow("🧪", stringResource(R.string.experimental), null, onExperimental, "settings_experimental", status = stringResource(R.string.experimental_status))
    SamsungTvCard(samsungIp, onSamsungIp)
    AboutCard(updates, onCheckUpdate, onUpdate)
    Footer()
}

/** The Frame / Samsung TV: found by itself; if not, its address typed here (also on iOS). */
@Composable
internal fun SamsungTvCard(saved: String, onSave: (String) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(saved) }
    var done by remember { mutableStateOf(false) }
    QCard {
        Text(stringResource(R.string.samsung_tv_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Text(stringResource(R.string.samsung_tv_note), color = Q.TextMuted, style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.OutlinedTextField(
            value = text, onValueChange = { text = it; done = false }, singleLine = true,
            placeholder = { Text("192.168.1.40") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("samsung_ip"),
        )
        GoldButton(stringResource(if (done) R.string.samsung_tv_saved else R.string.samsung_tv_save), onClick = { onSave(text); done = true }, modifier = Modifier.fillMaxWidth())
    }
}

/** About: this version, and an update right under it when there is one (download + install in the app). */
@Composable
internal fun AboutCard(updates: AppUpdates.State, onCheck: () -> Unit, onUpdate: () -> Unit) {
    val u = updates.available
    QCard(highlighted = u != null) {
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Text(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), color = Q.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("version"))
        when {
            updates.downloadPercent != null -> StatusPill(stringResource(R.string.update_downloading, updates.downloadPercent), Q.Primary, pulsing = true)
            updates.installing -> StatusPill(stringResource(R.string.update_installing), Q.Primary, pulsing = true)
            u != null -> {
                StatusPill(stringResource(R.string.update_available, u.versionName), Q.Success)
                if (u.notes.isNotBlank()) Hint(u.notes)
                GoldButton(stringResource(R.string.update_now), onUpdate, Modifier.fillMaxWidth().testTag("update_now"), height = 52.dp)
                if (updates.needsPermission) Hint(stringResource(R.string.update_permission))
            }
            updates.checking -> StatusPill(stringResource(R.string.update_checking), Q.TextMuted, pulsing = true)
            updates.upToDate -> Hint(stringResource(R.string.update_none))
        }
        updates.error?.let { Text(it, color = Q.Error, style = MaterialTheme.typography.bodyMedium) }
        if (u == null && updates.downloadPercent == null && !updates.installing) {
            GhostButton(stringResource(R.string.update_check), onCheck, Modifier.fillMaxWidth().testTag("update_check"), height = 44.dp)
        }
        Hint(stringResource(R.string.about_text, BuildConfig.VERSION_NAME))
    }
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
        Hint(pluralStringResource(R.plurals.crash_reports_kept, CrashReporter.KEEP_DAYS, CrashReporter.KEEP_DAYS))
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
    link: String = initialLink.orEmpty(),
    onLink: (String) -> Unit = {},
    tvName: String? = null,
    onTv: (String) -> Unit = {},
) = Screen {
    val clipboard = LocalClipboardManager.current
    val videoId = YouTubeLinks.videoId(link)
    // Chosen by name, so it stays chosen even if Android gives the TV a new id mid-cast.
    val chosenTv = ui.tvs.firstOrNull { it.name == tvName } ?: ui.tvs.firstOrNull { it.name == ui.connectedTv } ?: ui.tvs.singleOrNull()
    val busy = ui.youTube == MovieSession.YouTubeState.STARTING

    // Shared straight from the YouTube app: cast at once if there's exactly one TV.
    LaunchedEffect(initialLink, chosenTv?.id) {
        val id = YouTubeLinks.videoId(initialLink)
        if (id != null && chosenTv != null && ui.youTube == MovieSession.YouTubeState.IDLE) onCast(id, chosenTv.id)
    }

    TopBar(stringResource(R.string.youtube), onBack)
    // While it connects: one clear card with progress, instead of the page changing under you.
    if (busy) QCard(highlighted = true, modifier = Modifier.testTag("yt_connecting")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = Q.Primary, strokeWidth = 3.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.yt_connecting_to, ui.tvs.firstOrNull { it.id == ui.youTubeTvId }?.name ?: chosenTv?.name ?: "TV"), color = Q.Text, style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(ui.youTubeStep ?: R.string.yt_starting))
            }
        }
        val step = when (ui.youTubeStep) { R.string.yt_step_intro -> 1; R.string.yt_step_opening -> 2; R.string.yt_step_video -> 3; else -> 0 }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { step / 3f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Q.Primary, trackColor = Q.Border,
        )
    }
    QCard(highlighted = !busy) {
        Text(stringResource(R.string.yt_cast_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Hint(stringResource(R.string.yt_cast_hint))
        OutlinedTextField(
            value = link,
            onValueChange = onLink,
            enabled = !busy,
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
            GhostButton(stringResource(R.string.paste), { if (!busy) clipboard.getText()?.text?.let(onLink) })
            if (link.isNotBlank() && videoId == null) Hint(stringResource(R.string.yt_not_a_link))
        }
        when {
            ui.tvs.isEmpty() -> StatusPill(stringResource(R.string.searching), Q.Primary, pulsing = true)
            ui.tvs.size == 1 -> Column {
                Text(ui.tvs[0].name, color = Q.Text, style = MaterialTheme.typography.titleMedium)
                if (ui.tvs[0].youTubeOnly) Hint(stringResource(if (ui.tvs[0].away) R.string.away_tv_hint else R.string.smart_tv_youtube))
            }
            ui.tvs.size > 1 -> ui.tvs.forEach { tv ->
                val selected = tv.id == chosenTv?.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(BorderStroke(1.dp, if (selected) Q.Primary else Q.BorderAccent), RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton, enabled = !busy) { onTv(tv.name) }
                        .padding(14.dp)
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioDot(selected)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(tv.name, color = Q.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tv.youTubeOnly) Hint(stringResource(if (tv.away) R.string.away_tv_hint else R.string.smart_tv_youtube))
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
            MovieSession.YouTubeState.STARTING -> Unit // shown in the Connecting card at the top
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
private fun TvRow(name: String, busyWith: String? = null, isSpeaker: Boolean = false, youTubeOnly: Boolean = false, away: Boolean = false, kind: com.theatresync.core.CastModel.Kind? = null, onClick: () -> Unit) {
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
            if (youTubeOnly) Text(stringResource(if (away) R.string.away_tv_youtube else R.string.smart_tv_youtube), style = MaterialTheme.typography.bodySmall, color = if (away) Q.Primary else Q.TextMuted)
            else when (kind) {
                com.theatresync.core.CastModel.Kind.GOOGLE_TV -> Text(stringResource(R.string.kind_google_tv), style = MaterialTheme.typography.bodySmall, color = Q.TextMuted)
                com.theatresync.core.CastModel.Kind.CHROMECAST -> Text(stringResource(R.string.kind_chromecast), style = MaterialTheme.typography.bodySmall, color = Q.TextMuted)
                else -> Unit
            }
        }
        Text(
            stringResource(when { youTubeOnly -> R.string.youtube; busyWith != null -> R.string.use_for_movie; else -> R.string.connect }),
            color = Q.Primary, style = MaterialTheme.typography.labelLarge,
        )
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
    onSubtitlesOn: (Boolean) -> Unit = {},
    onResync: () -> Unit = {},
    onShareNfc: () -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.movie_player), onBack) { PlayerActions(onAudio, onRemote, onPip) }
    Eyebrow(
        when {
            ui.introPlaying -> stringResource(R.string.status_intro)
            else -> null
        } ?: when (ui.phase) {
            Phase.STARTING -> ui.tvReadyPercent?.let { p ->
                ui.tvReadyEtaSec?.takeIf { it > 0 }?.let { stringResource(R.string.status_tv_ready_eta, p, it) } ?: stringResource(R.string.status_tv_ready, p)
            } ?: stringResource(R.string.status_starting, ui.connectedTv ?: "TV")
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
    }

    // Sync: always automatic; shows how well in step the headphones are right now, and re-locks on a tap.
    if (ui.headphonesHere) QCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.hp_sync), style = MaterialTheme.typography.titleMedium, color = Q.Text)
                SyncPill(ui)
            }
            GhostButton(stringResource(R.string.sync_now), onResync, Modifier.testTag("sync_now"), height = 48.dp)
        }
        Hint(stringResource(R.string.sync_auto_hint))
    }

    AudioSummary(ui, onAudio)
    if (ui.subtitleName != null) QCard { SwitchRow("CC", stringResource(R.string.subtitles_on), ui.subtitlesOn, onSubtitlesOn) }

    QCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.friends_heading), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = Q.Text)
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (ui.friends > 0) Q.Gold else androidx.compose.ui.graphics.SolidColor(Q.Border)), contentAlignment = Alignment.Center) {
                Text(ui.friends.toString(), color = if (ui.friends > 0) Color.Black else Q.TextFaint, style = MaterialTheme.typography.titleMedium)
            }
        }
        ui.joinAddress?.let { TapInCard("http://$it/listen", onShareNfc, stringResource(R.string.friends_hint)) }
    }


    HeadphoneOptionsButton(onHeadphoneOptions)
    GhostButton("■  " + stringResource(R.string.stop_movie), onStop, Modifier.fillMaxWidth(), height = 52.dp, description = stringResource(R.string.stop_movie))
    Footer()
}

/** TapIn, right on the movie screen: guests scan the QR code with their camera (no app) or tap NFC. */
@Composable
internal fun ColumnScope.TapInCard(url: String, onShareNfc: () -> Unit, footer: String? = null) {
    Hint(stringResource(R.string.guests_hint))
    val qr = remember(url) { qrBitmap(url, 512) }
    if (qr != null) {
        androidx.compose.foundation.Image(
            qr, contentDescription = stringResource(R.string.tapin_qr_description),
            modifier = Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp).testTag("tapin_qr_inline"),
        )
    }
    Text(
        url,
        Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp)).background(Q.Background).border(BorderStroke(1.dp, Q.BorderAccent), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        fontFamily = FontFamily.Monospace, color = Q.PrimaryLight,
    )
    GoldButton(stringResource(R.string.share_nfc), onShareNfc, Modifier.fillMaxWidth().testTag("share_nfc_inline"), height = 52.dp)
    footer?.let { Hint(it) }
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
                val viaYouTube = item.tvId.startsWith("dial:") // smart TV or remote cast: YouTube's remote link
                if (a.canControl || viaYouTube) RoundIconButton(if (a.isPlaying) "❚❚" else "▶", stringResource(R.string.play_pause), onPlayPause)
                if (a.sessionId != null || viaYouTube) RoundIconButton("■", stringResource(R.string.stop_casting), onStop, Modifier.testTag("card_stop"))
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
    onCastAnother: () -> Unit = {},
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    upNext: List<MovieSession.UpNextVideo> = emptyList(),
    onUpNext: (String) -> Unit = {},
    onRefreshUpNext: () -> Unit = {},
    ytHeadphonesOn: Boolean = false,
    ytHeadphonesDriftMs: Long? = null,
    ytHeadphonesProblem: String? = null,
    onYtHeadphones: (Boolean) -> Unit = {},
    /** The YouTube headphones player, shown inside its card while sound in headphones is on. */
    ytPlayer: (@Composable () -> Unit)? = null,
    /** TapIn for YouTube: guests' listening page (QR / NFC), or null. */
    ytTapInUrl: String? = null,
    onShareNfc: (String) -> Unit = {},
    headphoneName: String? = null,
    onListenAlong: (String) -> Unit = {},
) = Screen {
    val a = item.activity
    // Keep "Up next" fresh while this page is open (and right after the video changes).
    if (a.kind == CastActivity.Kind.YOUTUBE) LaunchedEffect(item.tvId, a.contentId) {
        while (true) { onRefreshUpNext(); kotlinx.coroutines.delay(20_000) }
    }
    TopBar(stringResource(R.string.now_casting), onBack) { PlayerActions(onAudio, onRemote, onPip) }
    Message(message, onDismissMessage)
    val swipe = if (a.kind == CastActivity.Kind.YOUTUBE) Modifier.pointerInput(item.tvId) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total < -120f) onNext() else if (total > 120f) onPrevious() },
            onHorizontalDrag = { _, dx -> total += dx },
        )
    } else Modifier
    Thumb(a, Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(22.dp, RoundedCornerShape(18.dp), ambientColor = Q.Glow, spotColor = Q.Glow).then(swipe).testTag("yt_swipe"), big = true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(a.label(), if (a.isPlaying || a.kind == CastActivity.Kind.MIRRORING) Q.Success else Q.TextMuted, pulsing = a.isPlaying)
        Text(stringResource(R.string.on_tv, item.tvName), color = Q.TextFaint, style = MaterialTheme.typography.bodyMedium)
    }
    GradientText(a.headline(), MaterialTheme.typography.headlineSmall)
    a.subtitle?.let { Body(it) }

    // YouTube sound in the headphones: the same video plays on the phone, in step with the TV.
    if (a.kind == CastActivity.Kind.YOUTUBE && a.contentId != null && !item.tvId.startsWith("dial:away:")) QCard(highlighted = ytHeadphonesOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.yt_headphones), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(
                    when {
                        !ytHeadphonesOn -> stringResource(R.string.yt_headphones_off, item.tvName)
                        ytHeadphonesProblem != null -> stringResource(R.string.yt_headphones_problem)
                        ytHeadphonesDriftMs != null -> syncText(ytHeadphonesDriftMs)
                        else -> stringResource(R.string.yt_headphones_starting)
                    },
                )
            }
            Switch(
                checked = ytHeadphonesOn, onCheckedChange = onYtHeadphones, modifier = Modifier.testTag("yt_headphones"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        if (ytHeadphonesOn) {
            // The TV's sound, going to your headphones.
            Spacer(Modifier.height(10.dp))
            Text(
                "📺  " + item.tvName + "   →   🎧  " + (headphoneName ?: stringResource(R.string.yt_your_headphones)),
                color = Q.Primary, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                modifier = Modifier.testTag("yt_route"),
            )
            ytPlayer?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    it()
                    Spacer(Modifier.width(12.dp))
                    Hint(stringResource(R.string.yt_sound_link_label))
                }
            }
            Hint(stringResource(R.string.yt_headphones_note))
        }
    }

    // TapIn for YouTube: guests hear the TV's YouTube in their own headphones (no app), whoever cast it.
    if (a.kind == CastActivity.Kind.YOUTUBE && ytTapInUrl != null && !item.tvId.startsWith("dial:away:")) QCard {
        Text(stringResource(R.string.guests_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        TapInCard(ytTapInUrl, { onShareNfc(ytTapInUrl) }, stringResource(R.string.yt_tapin_note))
    }

    // A movie another phone is casting with TheatreSync: listen along in this phone's headphones (TapIn page).
    val listenUrl = a.contentId?.takeIf { it.startsWith("http://") && "/media" in it }?.let { it.substringBefore("/media") + "/listen" }
    if (listenUrl != null && a.kind != CastActivity.Kind.YOUTUBE) QCard(highlighted = true) {
        Text(stringResource(R.string.listen_along_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        Hint(stringResource(R.string.listen_along_hint))
        GoldButton(stringResource(R.string.listen_along), { onListenAlong(listenUrl) }, Modifier.fillMaxWidth().testTag("listen_along"), height = 52.dp)
    }

    val viaYouTube = item.tvId.startsWith("dial:")
    if (viaYouTube) QCard(highlighted = a.isPlaying) {
        Text(a.subtitle ?: stringResource(R.string.remote_waiting), style = MaterialTheme.typography.titleMedium, color = Q.Primary, modifier = Modifier.testTag("remote_status"))
        Hint(stringResource(R.string.remote_status_hint))
        GoldButton(stringResource(R.string.remote_cast_another), onCastAnother, Modifier.fillMaxWidth().testTag("cast_another"), height = 48.dp)
    }
    if (a.canControl || viaYouTube) {
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
            if (a.kind == CastActivity.Kind.YOUTUBE || viaYouTube) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("⏮  " + stringResource(R.string.yt_previous), onPrevious, Modifier.weight(1f).testTag("yt_previous"), height = 48.dp)
                    GhostButton(stringResource(R.string.yt_next) + "  ⏭", onNext, Modifier.weight(1f).testTag("yt_next"), height = 48.dp)
                }
                CaptionsRow(onCaptions)
            }
        }
        if (a.kind == CastActivity.Kind.YOUTUBE) UpNextRow(upNext, onUpNext)
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
    if (a.sessionId != null || viaYouTube) {
        GhostButton("■  " + stringResource(R.string.stop_casting), onStop, Modifier.fillMaxWidth().testTag("stop_casting"), height = 52.dp, description = stringResource(R.string.stop_casting))
    }
    // YouTube has its own "Sound in headphones" card above; this note is for mirroring and other apps.
    if (a.kind != CastActivity.Kind.YOUTUBE) QCard {
        Text(stringResource(R.string.cast_sound_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.cast_sound_hint))
        GhostButton("🎧  " + stringResource(R.string.hp_settings), onHeadphoneOptions, Modifier.fillMaxWidth().testTag("cast_hp_settings"), height = 48.dp)
    }
    Footer()
}

// ---------------------------------------------------------------- remote cast (away from home)

/**
 * Remote cast: everything you can send to the home TV while you're away. YouTube goes over YouTube's own
 * link (works now). A movie from this phone and mirroring the screen need something at home to receive
 * them (a Chromecast only takes those from its own Wi-Fi): a Chromecast with Google TV as the home hub,
 * or the home router's VPN. Each TV shows which applies.
 */
@Composable
internal fun RemoteCastScreen(tvs: List<Pair<String, com.theatresync.core.CastModel.Kind?>>, onYouTube: () -> Unit, onBack: () -> Unit) = Screen {
    TopBar(stringResource(R.string.remote_cast), onBack)
    Hint(stringResource(R.string.remote_cast_intro))
    // Samsung TVs (The Frame) can be switched on and have YouTube opened from anywhere with Samsung's free
    // SmartThings app; then YouTube from here plays on it.
    val context = LocalContext.current
    QCard(highlighted = true) {
        Text(stringResource(R.string.remote_samsung_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.remote_samsung_text))
        GhostButton(stringResource(R.string.remote_samsung_button), {
            val pm = context.packageManager
            val open = pm.getLaunchIntentForPackage("com.samsung.android.oneconnect")
                ?: Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.samsung.android.oneconnect".toUri())
            runCatching { context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, Modifier.fillMaxWidth().testTag("open_smartthings"), height = 48.dp)
    }
    RemoteOption("▶", stringResource(R.string.youtube), stringResource(R.string.remote_opt_youtube), stringResource(R.string.remote_ready), true, onYouTube, "remote_opt_youtube")
    RemoteOption("🎬", stringResource(R.string.remote_opt_movie_title), stringResource(R.string.remote_opt_movie), stringResource(R.string.remote_needs_hub), false, {}, "remote_opt_movie")
    RemoteOption("⧉", stringResource(R.string.mirror_screen), stringResource(R.string.remote_opt_mirror), stringResource(R.string.remote_needs_hub), false, {}, "remote_opt_mirror")
    if (tvs.isNotEmpty()) QCard {
        Text(stringResource(R.string.remote_your_tvs), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        tvs.forEach { (name, kind) ->
            Text(name, color = Q.Text, style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(when (kind) {
                com.theatresync.core.CastModel.Kind.GOOGLE_TV -> R.string.remote_tv_google
                com.theatresync.core.CastModel.Kind.CHROMECAST -> R.string.remote_tv_classic
                else -> R.string.remote_tv_unknown
            }))
        }
    }
    Footer()
}

@Composable
private fun RemoteOption(icon: String, title: String, text: String, status: String, ready: Boolean, onClick: () -> Unit, tag: String) {
    QCard(Modifier.then(if (ready) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).testTag(tag), highlighted = ready) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(icon, style = MaterialTheme.typography.headlineSmall, color = if (ready) Q.Primary else Q.TextFaint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (ready) Q.Text else Q.TextMuted)
                Hint(text)
            }
        }
        StatusPill(status, if (ready) Q.Success else Q.TextFaint)
    }
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
    onCopyCode: (String) -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.auto_reports), onBack)
    GitHubSection(configured = configured, connected = connected, code = code, state = state, onStart = onStart, onOpenGitHub = onOpenGitHub, onDisconnect = onDisconnect, onCreateApp = onCreateApp, onSaveClientId = onSaveClientId, lastUpload = lastUpload, testing = testing, onSendTest = onSendTest, onCopyCode = onCopyCode)
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
    onCopyCode: (String) -> Unit = {},
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
            Hint(stringResource(R.string.github_code_ready))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.github_copy_code), { onCopyCode(code.userCode) }, Modifier.weight(1f).testTag("github_copy"), height = 56.dp)
                GoldButton(stringResource(R.string.github_open), { onCopyCode(code.userCode); onOpenGitHub(code) }, Modifier.weight(1f), height = 56.dp)
            }
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
    onWake: (String) -> Unit = {},
    onPower: (String, Boolean) -> Unit = { id, on -> if (on) onWake(id) },
    onFindPower: (Boolean) -> Unit = {},
) = Screen {
    TopBar(stringResource(R.string.remote), onBack)
    // Look for projectors / receivers that can be switched on and off (quietly, at most every 10 minutes).
    LaunchedEffect(Unit) { onFindPower(false) }
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
    if (!tv.isSpeaker && !tv.youTubeOnly) QCard {
        Text(stringResource(R.string.power), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldButton(stringResource(R.string.power_on), { onPower(tv.id, true) }, Modifier.weight(1f).testTag("wake"), height = 52.dp)
            GhostButton(stringResource(R.string.power_off), { onPower(tv.id, false) }, Modifier.weight(1f).testTag("power_off"), height = 52.dp)
        }
        when {
            ui.powerSearching -> Hint(stringResource(R.string.power_searching))
            ui.powerDevices.isNotEmpty() -> Hint(stringResource(R.string.power_found, ui.powerDevices.joinToString(", ")))
            else -> Hint(stringResource(R.string.power_none))
        }
        Hint(stringResource(R.string.power_hint))
        if (!ui.powerSearching) GhostButton(stringResource(R.string.power_search), { onFindPower(true) }, Modifier.fillMaxWidth().testTag("power_search"), height = 44.dp)
    }
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

    // Auto sync: always on, nothing to set.
    QCard {
        Text(stringResource(R.string.hp_sync), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        val drift = ui.driftMs
        StatusPill(
            if (drift != null) stringResource(R.string.hp_sync_live, kotlin.math.abs(drift)) else stringResource(R.string.hp_sync_idle),
            if (drift != null && kotlin.math.abs(drift) <= 40) Q.Success else Q.Primary,
            pulsing = drift != null,
        )
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
        // Presets, by kind of sound. Tapping one switches the equalizer on and is heard at once.
        HeadphoneFx.Group.entries.forEach { group ->
            Text(stringResource(group.label), color = Q.TextFaint, style = MaterialTheme.typography.labelLarge)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeadphoneFx.Preset.entries.filter { it.group == group && it != HeadphoneFx.Preset.CUSTOM }.forEach { preset ->
                    val selected = fx.enabled && fx.preset == preset
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier.height(40.dp).clip(shape)
                            .background(if (selected) Q.Gold else SolidColor(Q.Card))
                            .border(BorderStroke(1.dp, if (selected) Q.Secondary else Q.BorderAccent), shape)
                            .clickable(role = Role.RadioButton) { onFx(HeadphoneFx.withPreset(fx, preset)) }
                            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
                            .testTag("eq_${preset.name}")
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(preset.label), color = if (selected) Color.Black else Q.PrimaryLight, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (fx.enabled && fx.preset == HeadphoneFx.Preset.CUSTOM) Hint(stringResource(R.string.eq_custom_hint))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.eq_night_mode), color = Q.Text, style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(R.string.eq_night_mode_hint))
            }
            Switch(
                checked = fx.night, onCheckedChange = { onFx(fx.copy(night = it, enabled = true, preset = HeadphoneFx.Preset.CUSTOM)) },
                modifier = Modifier.testTag("eq_night"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        EqSlider(stringResource(R.string.eq_bass_boost), fx.bass / 1000f, enabled = fx.enabled) {
            onFx(fx.copy(bass = (it * 1000).toInt(), preset = HeadphoneFx.Preset.CUSTOM))
        }
        HeadphoneFx.BAND_LABELS.forEachIndexed { i, hz ->
            EqSlider("$hz Hz", (fx.bandsDb[i] + HeadphoneFx.MAX_DB) / (2f * HeadphoneFx.MAX_DB), enabled = fx.enabled, valueText = "%+d dB".format(fx.bandsDb[i])) { v ->
                val db = Math.round(v * 2 * HeadphoneFx.MAX_DB) - HeadphoneFx.MAX_DB
                if (db != fx.bandsDb[i]) onFx(fx.copy(bandsDb = fx.bandsDb.toMutableList().also { it[i] = db }, preset = HeadphoneFx.Preset.CUSTOM))
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
            value = local, onValueChange = { local = it; onChange(it) },
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
internal fun ExperimentalScreen(ui: MovieSession.Ui, onSignatureIntro: (Boolean) -> Unit, onBack: () -> Unit, onMultiAudio: (Boolean) -> Unit = {}, onAdSkipper: (Boolean) -> Unit = {}, onScreensaver: (Boolean) -> Unit = {}, onRemoteCast: (Boolean) -> Unit = {}) = Screen {
    TopBar(stringResource(R.string.experimental), onBack)
    QCard(highlighted = ui.remoteCast) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.remote_cast), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(stringResource(R.string.remote_cast_short))
            }
            Switch(
                checked = ui.remoteCast, onCheckedChange = onRemoteCast, modifier = Modifier.testTag("remote_cast"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Hint(stringResource(R.string.remote_cast_text))
    }
    QCard(highlighted = ui.adSkipper) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.ad_skipper), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(stringResource(R.string.ad_skipper_short))
            }
            Switch(
                checked = ui.adSkipper, onCheckedChange = onAdSkipper, modifier = Modifier.testTag("ad_skipper"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Hint(stringResource(R.string.ad_skipper_text))
    }
    QCard(highlighted = ui.multiAudio) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.multi_audio), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(stringResource(R.string.multi_audio_short))
            }
            Switch(
                checked = ui.multiAudio, onCheckedChange = onMultiAudio, modifier = Modifier.testTag("multi_audio"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Hint(stringResource(R.string.multi_audio_toggle_hint))
    }
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
    QCard(highlighted = ui.screensaver) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.screensaver), style = MaterialTheme.typography.titleLarge, color = Q.Text)
                Hint(stringResource(R.string.screensaver_short))
            }
            Switch(
                checked = ui.screensaver, onCheckedChange = onScreensaver,
                modifier = Modifier.testTag("screensaver"),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Q.Primary),
            )
        }
        Hint(stringResource(R.string.screensaver_note))
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
                    (ui.friends + ui.guests.size).takeIf { it > 0 }?.let { n -> pluralStringResource(R.plurals.guests_listening, n, n) },
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
    battery: Map<String, Int> = emptyMap(),
    listenUrl: String?,
    onRoomSpeakers: (Boolean) -> Unit,
    onSelectOutput: (Int?) -> Unit,
    onPickDevice: () -> Unit,
    onShareNfc: () -> Unit,
    onShowQr: () -> Unit,
    onGuestSound: (String, Boolean) -> Unit = { _, _ -> },
    onGuestVolume: (String, Int) -> Unit = { _, _ -> },
    onGuestRemove: (String) -> Unit = {},
    onYouTubeHeadphones: (String, Boolean) -> Unit = { _, _ -> },
    onYouTubeRoomSpeakers: (String, Boolean) -> Unit = { _, _ -> },
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Q.Card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.audio_sources), style = MaterialTheme.typography.titleLarge, color = Q.Text)
            Hint(stringResource(R.string.audio_sources_hint))

            // YouTube on a TV (no movie from the phone): where its sound plays, with the same checkboxes.
            val yt = if (ui.phase == Phase.SETUP) ui.nowCasting.firstOrNull { it.activity.kind == CastActivity.Kind.YOUTUBE && it.activity.contentId != null } else null
            if (yt != null) {
                Text(stringResource(R.string.audio_youtube_on, yt.tvName), style = MaterialTheme.typography.titleMedium, color = Q.Primary)
                CheckRow(
                    checked = ui.youTubeHeadphonesTv == yt.tvId, title = stringResource(R.string.audio_youtube_headphones),
                    subtitle = ui.headphoneName ?: stringResource(R.string.this_phone), tag = "check_yt_headphones",
                ) { onYouTubeHeadphones(yt.tvId, it) }
                CheckRow(
                    checked = yt.activity.muted != true, title = stringResource(R.string.room_speakers),
                    subtitle = stringResource(R.string.audio_youtube_room), tag = "check_yt_room",
                ) { onYouTubeRoomSpeakers(yt.tvId, it) }
                Text(stringResource(R.string.audio_movies), style = MaterialTheme.typography.titleMedium, color = Q.Primary, modifier = Modifier.padding(top = 6.dp))
            }

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
                    ) + (battery[name]?.let { " · 🔋 $it%" } ?: "") +
                        (if (isActive && output.kind != DiagnosticsReport.Kind.SPEAKER) ui.driftMs?.let { " · " + syncText(it) } ?: "" else ""),
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
                if (ui.guestSoundUnsupported) Hint(stringResource(R.string.guests_sound_unsupported))
                if (ui.guests.isNotEmpty()) {
                    Text(stringResource(R.string.guests_now), style = MaterialTheme.typography.titleSmall, color = Q.Primary)
                    ui.guests.forEach { g -> GuestRow(g, onGuestSound, onGuestVolume, onGuestRemove) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldButton(stringResource(R.string.share_nfc), onShareNfc, Modifier.weight(1f).testTag("share_nfc"), height = 52.dp)
                    GhostButton(stringResource(R.string.show_qr), onShowQr, Modifier.weight(1f).testTag("show_qr"), height = 52.dp)
                }
            }
            GhostButton("⚙  " + stringResource(R.string.hp_settings), onMore, Modifier.fillMaxWidth(), height = 48.dp)
        }
    }
}

/** "In step · +12 ms": how far a device's sound is from the TV picture. */
@Composable
private fun syncText(driftMs: Long): String =
    stringResource(if (kotlin.math.abs(driftMs) <= 60) R.string.sync_in_step else R.string.sync_catching_up, (if (driftMs >= 0) "+" else "") + driftMs)

/** One TapIn guest: name, how well in step, and the host's controls (sound, volume, remove). */
@Composable
internal fun GuestRow(
    g: com.theatresync.core.Guests.Guest,
    onSound: (String, Boolean) -> Unit,
    onVolume: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    QCard(Modifier.testTag("guest_${g.id}")) {
        Text(g.name + (g.device?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium, color = Q.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        val status = when {
            g.status == "unsupported" -> stringResource(R.string.guest_unsupported)
            g.hidden -> stringResource(R.string.guest_screen_off) + (g.driftMs?.let { " · " + syncText(it) } ?: "")
            g.status == "playing" && g.driftMs != null -> syncText(g.driftMs!!)
            g.status == "paused" -> stringResource(R.string.guest_paused)
            else -> stringResource(R.string.guest_connecting)
        }
        Hint(status)
        CheckRow(checked = !g.muted, title = stringResource(R.string.guest_sound), subtitle = null, tag = "guest_sound_${g.id}") { onSound(g.id, it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("−", { onVolume(g.id, g.volume - 10) }, Modifier.width(56.dp), height = 44.dp, description = stringResource(R.string.guest_quieter))
            Text(stringResource(R.string.guest_volume, g.volume), Modifier.weight(1f), color = Q.Text, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            GhostButton("+", { onVolume(g.id, g.volume + 10) }, Modifier.width(56.dp), height = 44.dp, description = stringResource(R.string.guest_louder))
            GhostButton(stringResource(R.string.guest_remove), { onRemove(g.id) }, Modifier.testTag("guest_remove_${g.id}"), height = 44.dp)
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
                Text(url, color = Q.PrimaryLight, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { GoldButton(stringResource(R.string.done), onDone, height = 48.dp) },
    )
}


enum class AudioSharing { SUPPORTED, UNSUPPORTED, NO_BLUETOOTH }

/** Experimental: the same sound (music, calls, anything) on several headphones. */
@Composable
internal fun MultiAudioScreen(
    status: AudioSharing,
    connectedHeadsets: List<String>,
    onOpenSharing: () -> Unit,
    onTapIn: () -> Unit,
    onBack: () -> Unit,
) = Screen {
    TopBar(stringResource(R.string.multi_audio), onBack)
    QCard(highlighted = status == AudioSharing.SUPPORTED) {
        Text(stringResource(R.string.multi_audio_phone_title), style = MaterialTheme.typography.titleLarge, color = Q.Text)
        StatusPill(
            stringResource(if (status == AudioSharing.SUPPORTED) R.string.multi_audio_supported else R.string.multi_audio_unsupported),
            if (status == AudioSharing.SUPPORTED) Q.Success else Q.Primary,
        )
        Body(stringResource(if (status == AudioSharing.SUPPORTED) R.string.multi_audio_supported_text else R.string.multi_audio_unsupported_text))
        GoldButton(stringResource(R.string.multi_audio_open), onOpenSharing, Modifier.fillMaxWidth().testTag("open_audio_sharing"), height = 52.dp)
        if (connectedHeadsets.isNotEmpty()) Hint(stringResource(R.string.multi_audio_connected, connectedHeadsets.joinToString(", ")))
    }
    QCard {
        Text(stringResource(R.string.multi_audio_movies_title), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        Hint(stringResource(R.string.multi_audio_movies_text))
        GhostButton(stringResource(R.string.audio_sources), onTapIn, Modifier.fillMaxWidth(), height = 48.dp)
    }
    Footer()
}


/** YouTube "Up next": what the TV will play after this video; tap one to play it now. Swipe sideways to see more. */
@Composable
private fun UpNextRow(videos: List<MovieSession.UpNextVideo>, onPlay: (String) -> Unit) {
    QCard {
        Text(stringResource(R.string.up_next), style = MaterialTheme.typography.titleMedium, color = Q.Text)
        if (videos.isEmpty()) {
            Hint(stringResource(R.string.up_next_empty))
            return@QCard
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("up_next")) {
            items(videos.size) { i ->
                val v = videos[i]
                Column(
                    Modifier.width(176.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onPlay(v.videoId) },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Fixed 16:9 frame, so the row keeps its shape while thumbnails load (or offline).
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Q.Border), contentAlignment = Alignment.Center) {
                        RemoteImage(v.thumbnail, v.title, Modifier.fillMaxSize()) {
                            Text("▶", color = Q.Primary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                        }
                    }
                    Text(v.title ?: "YouTube", color = Q.Text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    v.channel?.let { Text(it, color = Q.TextFaint, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Hint(stringResource(R.string.up_next_hint))
    }
}


/** What the Subtitles section can do (bundled to keep the setup screen's parameter list short). */
class SubtitleActions(
    val supported: Boolean = false,
    val onLoad: () -> Unit = {},
    val onGenerate: (spoken: String, shownIn: String, label: String) -> Unit = { _, _, _ -> },
    val onCancel: () -> Unit = {},
    val onClear: () -> Unit = {},
)

private val SPOKEN = listOf("en-US" to "English", "hi-IN" to "Hindi", "gu-IN" to "Gujarati", "es-ES" to "Spanish", "fr-FR" to "French", "de-DE" to "German", "ja-JP" to "Japanese", "ko-KR" to "Korean")

/** Subtitles for the chosen movie: load a .srt file, or generate them on the phone. */
@Composable
private fun SubtitlesSection(ui: MovieSession.Ui, actions: SubtitleActions) {
    var choosing by remember { mutableStateOf(false) }
    Text(stringResource(R.string.subtitles), style = MaterialTheme.typography.titleMedium, color = Q.Text)
    val progress = ui.subtitleProgress
    when {
        progress != null -> {
            StatusPill(stringResource(if (ui.subtitleDownloading) R.string.subtitles_downloading else R.string.subtitles_generating, progress), Q.Primary, pulsing = true)
            GoldProgress(progress / 100f)
            ui.subtitleEtaSec?.let { eta ->
                Hint(if (eta < 60) pluralStringResource(R.plurals.eta_seconds, eta.toInt(), eta.toInt()) else ((eta + 59) / 60).toInt().let { m -> pluralStringResource(R.plurals.eta_minutes, m, m) })
            }
            GhostButton(stringResource(R.string.cancel), actions.onCancel, Modifier.fillMaxWidth(), height = 44.dp)
        }
        ui.subtitleName != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CC  " + ui.subtitleName, Modifier.weight(1f), color = Q.PrimaryLight, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GhostButton("✕", actions.onClear, height = 40.dp, description = stringResource(R.string.subtitles_none))
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.subtitles_load), actions.onLoad, Modifier.weight(1f).testTag("subs_load"), height = 48.dp)
            GhostButton(stringResource(R.string.subtitles_generate), { choosing = true }, Modifier.weight(1f).testTag("subs_generate"), height = 48.dp)
        }
    }
    if (choosing) {
        var spoken by remember { mutableStateOf(SPOKEN.first()) }
        var shown by remember { mutableStateOf(SPOKEN.first()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = Q.Card,
            title = { Text(stringResource(R.string.subtitles_generate_title), color = Q.Text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.subtitles_spoken), color = Q.Text, style = MaterialTheme.typography.titleSmall)
                    LanguageGrid(spoken) { spoken = it }
                    Text(stringResource(R.string.subtitles_show_in), color = Q.Text, style = MaterialTheme.typography.titleSmall)
                    LanguageGrid(shown) { shown = it }
                    Hint(stringResource(R.string.subtitles_generate_note))
                }
            },
            confirmButton = {
                if (actions.supported) GoldButton(stringResource(R.string.subtitles_generate), {
                    choosing = false
                    val label = if (spoken == shown) shown.second else "${shown.second} (from ${spoken.second})"
                    actions.onGenerate(spoken.first, shown.first, label)
                }, height = 48.dp)
            },
            dismissButton = { GhostButton(stringResource(R.string.cancel), { choosing = false }, height = 48.dp) },
        )
    }
}

/** Language choices as a simple two-column grid of radio rows. */
@Composable
private fun LanguageGrid(selected: Pair<String, String>, onSelect: (Pair<String, String>) -> Unit) {
    SPOKEN.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { lang ->
                val on = lang == selected
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, if (on) Q.Primary else Q.BorderAccent), RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { onSelect(lang) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioDot(on)
                    Text(lang.second, Modifier.padding(start = 8.dp), color = Q.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
