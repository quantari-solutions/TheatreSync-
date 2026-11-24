package com.theatresync.core

/**
 * Which kind of Cast device a TV is, so the app can adapt: a Chromecast with Google TV (or a TV with
 * Google TV / Android TV built in) runs Android and can host TheatreSync itself (home hub for remote
 * control from anywhere); a classic Chromecast (puck, Ultra) can't run apps, so from away it gets
 * YouTube over the internet only. Google TV devices run Google's TV-remote service (ports 6466/6467);
 * classic Chromecasts don't.
 */
object CastModel {
    enum class Kind { GOOGLE_TV, CHROMECAST, SPEAKER, OTHER }

    val REMOTE_SERVICE_PORTS = listOf(6466, 6467)

    fun classify(modelName: String?, isSpeaker: Boolean, hasRemoteService: Boolean): Kind {
        val m = modelName.orEmpty().lowercase()
        return when {
            isSpeaker -> Kind.SPEAKER
            hasRemoteService || "google tv" in m || "android tv" in m -> Kind.GOOGLE_TV
            "chromecast" in m -> Kind.CHROMECAST
            else -> Kind.OTHER
        }
    }

    /** True when this device can run TheatreSync as the home hub. */
    fun canHostHub(kind: Kind) = kind == Kind.GOOGLE_TV

    /** Asks the device whether it runs Google's TV-remote service (blocking, short timeout). */
    fun hasRemoteService(host: String): Boolean = REMOTE_SERVICE_PORTS.any { NetworkPower.portOpen(host, it, 400) }
}
