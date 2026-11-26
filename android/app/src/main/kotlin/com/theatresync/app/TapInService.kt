package com.theatresync.app

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.theatresync.core.Type4Tag

/**
 * TapIn over NFC: while a movie plays, this phone answers like an NFC tag that holds the
 * guest listening page's address. Guests hold their phone to the back of this one (iPhone XS
 * or newer, or any Android with NFC) and the page opens in their browser.
 */
class TapInService : HostApduService() {
    private var session = Type4Tag.Session()

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val reply = runCatching { Type4Tag.respond(session, commandApdu, url) }.getOrDefault(byteArrayOf(0x6F, 0x00))
        // The other phone read the link (READ BINARY answered OK, past the 2-byte length): show "Sent ✓".
        if (commandApdu.size > 1 && commandApdu[1] == 0xB0.toByte() && reply.size > 4 && url != null) {
            lastReadMs = android.os.SystemClock.elapsedRealtime()
        }
        return reply
    }

    override fun onDeactivated(reason: Int) {
        session = Type4Tag.Session() // next tap starts fresh
    }

    companion object {
        /** http://<phone>:<port>/listen while a movie plays; null otherwise (nothing is shared). */
        @Volatile var url: String? = null
        /** When another phone last read the link (elapsedRealtime), for the share window's "Sent ✓". */
        @Volatile var lastReadMs: Long = 0L
    }
}
