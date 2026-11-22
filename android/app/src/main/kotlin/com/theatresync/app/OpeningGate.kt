package com.theatresync.app

/**
 * When the Quantari opening plays on the phone (owner, 2026-11-22): **only when TheatreSync starts fresh**
 * (opened after it was closed / swiped away). Coming back to it while it's running in the background,
 * from the notification or a share, after the phone was idle, or when Android rebuilds its screen in the
 * background: never. (It always plays in full when shown, whatever the phone's animation setting.)
 */
class OpeningGate {
    /** The screen was built. True if the opening should play. */
    fun created(fromNotificationOrShare: Boolean, restoredFromBackground: Boolean): Boolean =
        !fromNotificationOrShare && !restoredFromBackground

    /** The app came back into view: never the opening. */
    fun started(): Boolean = false
}
