package com.theatresync.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pre-screening for headphone sound: plays a short beep through the same kind of audio
 * output TheatreSync uses for movie sound (media usage, movie content type, the chosen
 * output if any) and reports which device Android actually routed it to.
 */
object SoundTest {
    data class Result(val deviceName: String?, val deviceType: Int?, val route: String, val error: String? = null) {
        val isHeadphones: Boolean
            @android.annotation.SuppressLint("InlinedApi") // newer TYPE_* ints simply never match on old phones
            get() = deviceType in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            )
    }

    /** Blocking (about 2 seconds): call from a background thread. */
    @android.annotation.SuppressLint("InlinedApi")
    fun run(context: Context, preferredOutputId: Int?): Result {
        val rate = 48_000
        val seconds = 1.5
        val frames = (rate * seconds).toInt()
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            // Two gentle beeps with a short gap, fading in/out to avoid clicks.
            val t = i.toDouble() / rate
            val inBeep = (t % 0.75) < 0.5
            val env = if (inBeep) minOf(1.0, (t % 0.75) * 40, (0.5 - (t % 0.75)) * 40) else 0.0
            val v = (sin(2 * PI * 880 * t) * 6000 * env).toInt().toShort()
            pcm[i * 2] = v
            pcm[i * 2 + 1] = v
        }
        var track: AudioTrack? = null
        return try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            preferredOutputId?.let { id ->
                context.getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                    ?.let { track.setPreferredDevice(it) }
            }
            track.play()
            Thread.sleep(400)
            val routed = track.routedDevice
            Thread.sleep((seconds * 1000).toLong() - 300)
            Result(
                routed?.productName?.toString()?.takeIf { it.isNotBlank() },
                routed?.type,
                routed?.let { AudioOutputs.routeFor(it.type).name } ?: "UNKNOWN",
            )
        } catch (e: Exception) {
            Result(null, null, "ERROR", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }
}
