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

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        runCatching { Type4Tag.respond(session, commandApdu, url) }.getOrDefault(byteArrayOf(0x6F, 0x00))

    override fun onDeactivated(reason: Int) {
        session = Type4Tag.Session() // next tap starts fresh
    }

    companion object {
        /** http://<phone>:<port>/listen while a movie plays; null otherwise (nothing is shared). */
        @Volatile var url: String? = null
    }
}
