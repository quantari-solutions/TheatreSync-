package com.theatresync.app

import androidx.core.net.toUri
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The Quantari / TheatreSync opening (the same clip as the Signature Intro), shown full screen when
 * the app starts fresh. Tap to skip. Calls [onDone] when it ends, fails, or after 7 s at most.
 */
@Composable
fun OpeningAnimation(onDone: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.signature_intro}".toUri()))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onDone() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) = onDone()
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(7_000); onDone() }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF050507)).clickable(onClick = onDone).testTag("opening"),
    ) {
        AndroidView(factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } }, modifier = Modifier.fillMaxSize())
    }
}

/** Instrumented tests drive the app directly; the opening would only get in their way. */
internal val runningUnderTest: Boolean by lazy {
    runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
}
