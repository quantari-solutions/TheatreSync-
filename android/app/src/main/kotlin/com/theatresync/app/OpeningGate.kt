package com.theatresync.app

/**
 * When the Quantari opening plays on the phone (owner: each time TheatreSync is opened, then it carries on
 * where you left off; not when the phone just went idle with it open).
 *
 *  - the screen is built (app opened, or rebuilt after Android closed it in the background): plays;
 *  - opened again after being away in other apps / the home screen for [awayMs] or more: plays;
 *  - the phone went idle (screen off / locked) with TheatreSync open, a quick switch, a rotation,
 *    picture-in-picture, the notification or a share: doesn't.
 */
class OpeningGate(private val awayMs: Long = 30_000L) {
    private var startedJustNow = false
    private var skipOnce = false
    private var leftAtMs = 0L

    /** The screen was built. True if the opening should play. */
    fun created(fromNotificationOrShare: Boolean): Boolean {
        startedJustNow = true
        return !fromNotificationOrShare
    }

    /** Brought back by the notification or a share (an existing screen). */
    fun newIntent(fromNotificationOrShare: Boolean) { if (fromNotificationOrShare) skipOnce = true }

    /** The app went out of view. */
    fun stopped(nowMs: Long, screenOn: Boolean, rotating: Boolean, pictureInPicture: Boolean) {
        leftAtMs = if (screenOn && !rotating && !pictureInPicture) nowMs else 0L
    }

    /** The app came into view. True if the opening should play. */
    fun started(nowMs: Long): Boolean {
        val show = !startedJustNow && !skipOnce && leftAtMs > 0 && nowMs - leftAtMs >= awayMs
        startedJustNow = false; skipOnce = false; leftAtMs = 0L
        return show
    }
}
