package com.theatresync.core

import java.util.concurrent.ConcurrentHashMap

/**
 * TapIn guests (phones listening in a browser), as the host sees and controls them.
 *
 * A guest's page reports about once a second (`GET /guest`): that report adds the guest the
 * moment they tap Start, carries their sync (drift), and its answer carries the host's controls
 * (sound on/off, volume, removed) — so a host change reaches the guest within a second.
 * Tapping Stop or closing the page sends `/leave`, which removes the guest at once; a page that
 * vanishes without that (phone off, Wi-Fi lost) is dropped after [timeoutMs]. A page in the
 * background (screen locked) reports less often, so it gets [hiddenTimeoutMs] instead.
 *
 * Every change to the list calls [onChange], so the host's screen updates immediately.
 */
class Guests(
    private val clockMs: () -> Long,
    private val timeoutMs: Long = 4_000,
    private val hiddenTimeoutMs: Long = 10 * 60_000,
) {
    data class Guest(
        val id: String,
        val name: String,
        val joinedAtMs: Long,
        val lastSeenMs: Long,
        /** Their sound minus the TV picture, in ms (positive = sound late); null while not playing. */
        val driftMs: Long?,
        /** What their page says: playing, buffering, paused, hidden, unsupported… */
        val status: String,
        val hidden: Boolean,
        val muted: Boolean = false,
        /** 0–100. iPhones ignore page volume (their own buttons work); mute works everywhere. */
        val volume: Int = 100,
    )

    /** The host's controls, sent back to the guest's page with every report. */
    data class Controls(val muted: Boolean, val volume: Int, val removed: Boolean) {
        fun toJson() = """{"muted":$muted,"volume":$volume,"removed":$removed}"""
    }

    @Volatile var onChange: (() -> Unit)? = null

    private val guests = ConcurrentHashMap<String, Guest>()
    /** Removed by the host: their page is told so (and stops) instead of being re-added. */
    private val removed = ConcurrentHashMap.newKeySet<String>()

    /** A guest's page reports in. Returns the host's controls for them. */
    fun report(id: String, name: String?, driftMs: Long?, status: String, hidden: Boolean): Controls {
        val cleanId = clean(id, 64)
        if (cleanId.isEmpty() || cleanId in removed) return Controls(muted = true, volume = 0, removed = true)
        val now = clockMs()
        var added = false
        val g = guests.compute(cleanId) { _, old ->
            if (old == null) added = true
            Guest(
                id = cleanId,
                name = name?.let { clean(it, 40) }?.ifBlank { null } ?: old?.name ?: "Guest",
                joinedAtMs = old?.joinedAtMs ?: now,
                lastSeenMs = now,
                driftMs = driftMs?.coerceIn(-600_000, 600_000),
                status = clean(status, 20).ifBlank { "connecting" },
                hidden = hidden,
                muted = old?.muted ?: false,
                volume = old?.volume ?: 100,
            )
        }!!
        if (added) changed()
        return Controls(g.muted, g.volume, removed = false)
    }

    /** The guest tapped Stop or closed the page. */
    fun leave(id: String) { if (guests.remove(clean(id, 64)) != null) changed() }

    fun setMuted(id: String, muted: Boolean) = update(id) { it.copy(muted = muted) }
    fun setVolume(id: String, volume: Int) = update(id) { it.copy(volume = volume.coerceIn(0, 100)) }

    /** The host removes a guest: their sound stops within a second. */
    fun remove(id: String) {
        removed += id
        if (guests.remove(id) != null) changed()
    }

    /** Current guests, oldest first; drops those that went silent. */
    fun list(): List<Guest> {
        val now = clockMs()
        var dropped = false
        for (g in guests.values) {
            val limit = if (g.hidden) hiddenTimeoutMs else timeoutMs
            if (now - g.lastSeenMs > limit && guests.remove(g.id, g)) dropped = true
        }
        if (dropped) changed()
        return guests.values.sortedBy { it.joinedAtMs }
    }

    private fun update(id: String, f: (Guest) -> Guest) {
        if (guests.computeIfPresent(id) { _, g -> f(g) } != null) changed()
    }

    private fun changed() { runCatching { onChange?.invoke() } }

    private fun clean(s: String, max: Int) = s.filter { it >= ' ' && it != '\u007F' }.trim().take(max)
}
