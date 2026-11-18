package com.theatresync.app

import android.content.Context
import android.net.Uri
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
    interface Events {
        /** Headphones disconnected or another app took the sound: the movie should pause. */
        fun onShouldPause()
        /** A phone call or similar temporarily interrupted the sound. */
        fun onInterrupted(interrupted: Boolean)
        fun onError(message: String)
    }

    private val corrector = DriftCorrector()

    val exo: ExoPlayer = ExoPlayer.Builder(context)
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
                    events.onError("This phone can't play the sound of this file (${error.errorCodeName}).")
                }
            })
            prepare()
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
        if (!exo.playWhenReady) {
            corrector.reset()
            exo.setPlaybackSpeed(1f)
            exo.seekTo(targetMs)
            exo.play()
            return
        }
        if (exo.playbackState != Player.STATE_READY) return // seeking or buffering
        val local = exo.currentPosition
        lastDriftMs = local - targetMs
        when (val action = corrector.decide(local, targetMs)) {
            is DriftCorrector.Action.SetSpeed -> exo.setPlaybackSpeed(action.speed.toFloat())
            is DriftCorrector.Action.Seek -> {
                exo.setPlaybackSpeed(1f)
                exo.seekTo(action.positionMs)
            }
            DriftCorrector.Action.None -> Unit
        }
    }

    fun release() = exo.release()
}
