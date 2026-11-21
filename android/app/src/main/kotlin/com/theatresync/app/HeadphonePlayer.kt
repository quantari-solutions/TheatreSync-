package com.theatresync.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.theatresync.core.DriftCorrector

/**
 * Plays only the SOUND of the movie on this phone (into whatever headphones are
 * connected) and keeps it in step with a target position supplied by the caller.
 * Must be created and used on the main thread.
 */
class HeadphonePlayer(context: Context, uri: Uri, private val events: Events) {
    private val contextRef: Context = context.applicationContext
    interface Events {
        /** Headphones disconnected or another app took the sound: the movie should pause. */
        fun onShouldPause()
        /** A phone call or similar temporarily interrupted the sound. */
        fun onInterrupted(interrupted: Boolean)
        fun onError(message: String)
    }

    private val corrector = DriftCorrector()

    /** Learned head start used when (re)starting or seeking, to cover output start-up and seek time. */
    @Volatile var startLeadMs: Long = 250
        private set
    private var justStarted = false

    /**
     * No drift decisions until this time. Bluetooth headphones take ~0.2–1 s to start after
     * play/seek/route change. Judging drift during that time made the player seek again, which
     * restarted the headphones, forever (the "no sound in headphones" bug in 0.4.x).
     */
    private var settleUntilMs = 0L
    /** One calibration seek is allowed right after a fresh start, measured once the output is steady. */
    private var calibrated = false
    private var lastSeekAtMs = 0L
    /** Counted for diagnostics: a healthy session seeks once or twice. */
    @Volatile var seeks = 0
        private set

    /** Last player error, for diagnostics. */
    @Volatile var lastError: String? = null
        private set

    /** Equalizer inside this player's own sound chain: changes are heard at once, on any phone. */
    private val eq = HeadphoneFx.Processor().also { it.settings = HeadphoneFx.load(context) }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(eq))
                .build()
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    val exo: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && (
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                            )
                    ) {
                        events.onShouldPause()
                    }
                }

                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    events.onInterrupted(reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                }

                override fun onPlayerError(error: PlaybackException) {
                    lastError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                    events.onError("This phone can't play the sound of this file (${error.errorCodeName}).")
                }
            })
            prepare()
        }

    /** Equalizer changes take effect on the next few milliseconds of sound. */
    fun applyFx(settings: HeadphoneFx.Settings) { eq.settings = settings }

    /** "Reset sync": forget the learned start lead and re-lock onto the TV from scratch. */
    fun resetSync() {
        startLeadMs = 250
        corrector.reset()
        exo.setPlaybackSpeed(1f)
        if (exo.playWhenReady) exo.pause() // the next follow() restarts cleanly in step
    }

    /** Last measured difference between the sound and the target, in ms (positive = sound early). */
    var lastDriftMs: Long? = null
        private set

    /**
     * Call regularly (every ~200 ms).
     * @param targetMs where the sound should be right now
     * @param shouldPlay whether the picture is currently moving
     */
    fun follow(targetMs: Long, shouldPlay: Boolean) {
        if (!shouldPlay) {
            if (exo.playWhenReady) {
                exo.pause()
                exo.setPlaybackSpeed(1f)
                corrector.reset()
            }
            lastDriftMs = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!exo.playWhenReady) {
            corrector.reset()
            exo.setPlaybackSpeed(1f)
            // Start a little ahead: audio output (especially Bluetooth) takes a moment to get
            // going. The lead is learned from the first measurement after each start.
            exo.seekTo(targetMs + startLeadMs)
            exo.play()
            justStarted = true
            calibrated = false
            lastSeekAtMs = now
            settleUntilMs = now + SETTLE_MS
            return
        }
        // Seeking, buffering or the output still starting: wait, and keep waiting a moment after.
        if (exo.playbackState != Player.STATE_READY || !exo.isPlaying) {
            settleUntilMs = now + SETTLE_MS
            return
        }
        if (now < settleUntilMs) return
        val local = exo.currentPosition
        val drift = local - targetMs
        lastDriftMs = drift
        if (justStarted) {
            // First steady measurement after (re)starting: learn the lead for next time.
            justStarted = false
            startLeadMs = (startLeadMs - drift * 3 / 4).coerceIn(0L, 2_000L)
            if (!calibrated && kotlin.math.abs(drift) > CALIBRATE_ABOVE_MS) {
                // The output is running now, so this measurement is real: correct once, at once,
                // with the freshly learned lead. Later corrections obey the seek gap.
                calibrated = true
                corrector.reset()
                exo.setPlaybackSpeed(1f)
                exo.seekTo((targetMs + startLeadMs).coerceAtLeast(0))
                seeks++
                lastSeekAtMs = now
                justStarted = true
                settleUntilMs = now + SETTLE_MS
                return
            }
            calibrated = true
        }
        when (val action = corrector.decide(local, targetMs)) {
            is DriftCorrector.Action.SetSpeed -> exo.setPlaybackSpeed(action.speed.toFloat())
            is DriftCorrector.Action.Seek -> {
                if (now - lastSeekAtMs < MIN_SEEK_GAP_MS && kotlin.math.abs(drift) < 3_000) {
                    // Just seeked: catch up smoothly instead of seeking again (avoids seek loops).
                    exo.setPlaybackSpeed(if (drift > 0) 0.95f else 1.05f)
                    corrector.reset()
                } else {
                    exo.setPlaybackSpeed(1f)
                    exo.seekTo((action.positionMs + startLeadMs).coerceAtLeast(0))
                    seeks++
                    lastSeekAtMs = now
                    justStarted = true
                    settleUntilMs = now + SETTLE_MS
                }
            }
            DriftCorrector.Action.None -> Unit
        }
    }

    /** Play on a specific output (e.g. one of two connected headphones), or null for Android's choice. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun setPreferredOutput(id: Int?) {
        val am = contextRef.getSystemService(android.media.AudioManager::class.java)
        val device = id?.let { wanted -> am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == wanted } }
        exo.setPreferredAudioDevice(device)
        // The new output needs a moment to start; don't treat that pause as drift.
        settleUntilMs = SystemClock.elapsedRealtime() + SETTLE_MS * 2
    }

    /** The user changed the sound delay: apply it smoothly even if it's small. */
    fun onDelayChanged() = corrector.forceCorrect()

    fun release() {
        exo.release()
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val MIN_SEEK_GAP_MS = 6_000L
        const val CALIBRATE_ABOVE_MS = 120L
    }
}
