package com.example.tachoansage

import android.content.Context

/** Geschwindigkeitseinheiten: Umrechnungsfaktor von m/s, Anzeige-Kürzel, gesprochener Text. */
enum class SpeedUnit(val factor: Float, val label: String, val spoken: String) {
    KMH(3.6f, "km/h", "Kilometer pro Stunde"),
    MPH(2.236936f, "mph", "Meilen pro Stunde"),
    KNOTS(1.943844f, "kn", "Knoten");

    fun convert(metersPerSecond: Float): Float = metersPerSecond * factor
}

/** Einstellungen, gespeichert in SharedPreferences. Der Service liest sie bei jeder Ansage neu. */
object Prefs {
    val INTERVALS = listOf(5, 10, 15, 30, 60) // Sekunden

    private const val FILE = "tacho_prefs"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_UNIT = "unit"
    private const val KEY_SKIP_ZERO = "skip_zero"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun interval(c: Context): Int = prefs(c).getInt(KEY_INTERVAL, 10)
    fun setInterval(c: Context, seconds: Int) = prefs(c).edit().putInt(KEY_INTERVAL, seconds).apply()

    fun unit(c: Context): SpeedUnit {
        val name = prefs(c).getString(KEY_UNIT, SpeedUnit.KMH.name) ?: SpeedUnit.KMH.name
        return runCatching { SpeedUnit.valueOf(name) }.getOrDefault(SpeedUnit.KMH)
    }
    fun setUnit(c: Context, unit: SpeedUnit) = prefs(c).edit().putString(KEY_UNIT, unit.name).apply()

    fun skipZero(c: Context): Boolean = prefs(c).getBoolean(KEY_SKIP_ZERO, true)
    fun setSkipZero(c: Context, skip: Boolean) = prefs(c).edit().putBoolean(KEY_SKIP_ZERO, skip).apply()
}
