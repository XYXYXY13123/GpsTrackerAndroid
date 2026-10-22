package com.example.tachoansage.iss

import android.content.Context

/** Gespeicherte Einstellungen und Daten für die ISS-Benachrichtigung. */
object IssPrefs {
    val LEAD_MINUTES = listOf(5, 10, 15, 30)

    private const val FILE = "iss_prefs"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    fun visibleOnly(c: Context) = prefs(c).getBoolean("visible_only", false)
    fun setVisibleOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("visible_only", v).apply()

    fun leadMinutes(c: Context) = prefs(c).getInt("lead", 10)
    fun setLeadMinutes(c: Context, v: Int) = prefs(c).edit().putInt("lead", v).apply()

    // ---- Standort des Beobachters ----

    fun observer(c: Context): Observer? {
        val p = prefs(c)
        if (!p.contains("lat")) return null
        return Observer(
            latDeg = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L)),
            lonDeg = java.lang.Double.longBitsToDouble(p.getLong("lon", 0L)),
            altKm = java.lang.Double.longBitsToDouble(p.getLong("alt", 0L)),
        )
    }

    fun observerTime(c: Context) = prefs(c).getLong("obs_time", 0L)

    fun setObserver(c: Context, lat: Double, lon: Double, altKm: Double, time: Long) {
        prefs(c).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(lon))
            .putLong("alt", java.lang.Double.doubleToRawLongBits(altKm))
            .putLong("obs_time", time)
            .apply()
    }

    // ---- Bahndaten (TLE) ----

    fun tle(c: Context): Pair<String, String>? {
        val p = prefs(c)
        val l1 = p.getString("tle1", null) ?: return null
        val l2 = p.getString("tle2", null) ?: return null
        return l1 to l2
    }

    fun tleFetchTime(c: Context) = prefs(c).getLong("tle_time", 0L)

    fun setTle(c: Context, l1: String, l2: String, time: Long) {
        prefs(c).edit().putString("tle1", l1).putString("tle2", l2).putLong("tle_time", time).apply()
    }

    // ---- geplante Alarme ----

    fun scheduledCount(c: Context) = prefs(c).getInt("scheduled", 0)
    fun setScheduledCount(c: Context, n: Int) = prefs(c).edit().putInt("scheduled", n).apply()
}
