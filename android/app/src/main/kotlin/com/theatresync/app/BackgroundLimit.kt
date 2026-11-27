package com.theatresync.app

import android.content.Context

/**
 * How long TheatreSync keeps running in the background while idle (Settings → Experimental): a number of
 * hours (default 4), until a time of day, or always. "Idle" = not on screen and nothing playing through it.
 */
object BackgroundLimit {
    enum class Mode { HOURS, UNTIL, ALWAYS }
    data class Setting(val mode: Mode = Mode.HOURS, val hours: Double = 4.0, val until: String = "23:00")

    private fun prefs(c: Context) = c.getSharedPreferences("theatresync", Context.MODE_PRIVATE)

    fun get(c: Context): Setting {
        val p = prefs(c)
        val mode = runCatching { Mode.valueOf(p.getString("bgMode", "HOURS")!!) }.getOrDefault(Mode.HOURS)
        return Setting(mode, p.getFloat("bgHours", 4f).toDouble(), p.getString("bgUntil", "23:00") ?: "23:00")
    }

    fun set(c: Context, s: Setting) = prefs(c).edit()
        .putString("bgMode", s.mode.name).putFloat("bgHours", s.hours.toFloat()).putString("bgUntil", s.until).apply()

    /** True when TheatreSync, idle since [idleSinceMs], should stop now ([nowMs], wall clock). */
    fun expired(c: Context, idleSinceMs: Long, nowMs: Long): Boolean = expired(get(c), idleSinceMs, nowMs, java.util.TimeZone.getDefault())

    fun expired(s: Setting, idleSinceMs: Long, nowMs: Long, tz: java.util.TimeZone): Boolean = when (s.mode) {
        Mode.ALWAYS -> false
        Mode.HOURS -> nowMs - idleSinceMs >= (s.hours.coerceAtLeast(0.25) * 3_600_000).toLong()
        Mode.UNTIL -> {
            val (h, m) = s.until.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 23) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            // The next time-of-day [h:m] after the idle period began.
            val cal = java.util.Calendar.getInstance(tz).apply {
                timeInMillis = idleSinceMs
                set(java.util.Calendar.HOUR_OF_DAY, h.coerceIn(0, 23)); set(java.util.Calendar.MINUTE, m.coerceIn(0, 59))
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= idleSinceMs) add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            nowMs >= cal.timeInMillis
        }
    }
}
