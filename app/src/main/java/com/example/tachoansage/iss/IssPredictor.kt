package com.example.tachoansage.iss

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Beobachterstandort: Breite/Länge in Grad, Höhe in km. */
data class Observer(val latDeg: Double, val lonDeg: Double, val altKm: Double = 0.0)

/** Ein Überflug der ISS. Zeiten als Unix-Millisekunden, Winkel in Grad. */
data class IssPass(
    val startMillis: Long,
    val endMillis: Long,
    val maxMillis: Long,
    val maxElevation: Double,
    val startAzimuth: Double,
    val endAzimuth: Double,
    /** Mit bloßem Auge sichtbar: ISS von der Sonne beleuchtet, bei dir ist es dunkel. */
    val visible: Boolean,
    val visibleStartMillis: Long,
    val visibleEndMillis: Long,
    val visibleStartAzimuth: Double,
)

object IssPredictor {

    private const val DEG = PI / 180.0
    private const val AU_KM = 149_597_870.7
    private const val EARTH_RADIUS_KM = 6378.137

    /** Mindesthöhe über dem Horizont, ab der die ISS als "sichtbar" zählt. */
    private const val VISIBLE_MIN_ELEVATION = 10.0

    /** Sonne mindestens so tief unter dem Horizont = dunkel genug (bürgerliche Dämmerung). */
    private const val DARK_SUN_ELEVATION = -6.0

    /**
     * Sucht alle Überflüge im Zeitraum [fromMillis] bis [fromMillis] + [hours],
     * deren höchster Punkt mindestens [minElevation] Grad erreicht.
     */
    fun findPasses(
        sat: Sgp4,
        obs: Observer,
        fromMillis: Long,
        hours: Int,
        minElevation: Double = 10.0,
    ): List<IssPass> {
        val station = Station(obs)
        val step = 30_000L
        val end = fromMillis + hours * 3_600_000L
        val passes = mutableListOf<IssPass>()

        // 20 Minuten früher anfangen, damit ein gerade laufender Überflug mitgezählt wird
        var t = fromMillis - 20 * 60_000L
        var prevEl = elevation(sat, station, t)
        var riseTime = if (prevEl >= 0.0) t else -1L

        while (t < end) {
            val next = t + step
            val el = elevation(sat, station, next)
            if (prevEl < 0.0 && el >= 0.0) {
                riseTime = refine(sat, station, t, next)
            } else if (prevEl >= 0.0 && el < 0.0 && riseTime >= 0L) {
                val setTime = refine(sat, station, t, next)
                if (setTime > fromMillis) {
                    analyse(sat, station, riseTime, setTime)?.let {
                        if (it.maxElevation >= minElevation) passes += it
                    }
                }
                riseTime = -1L
            }
            prevEl = el
            t = next
        }
        return passes
    }

    /** Himmelsrichtung auf Deutsch (16 Richtungen) zu einem Azimut in Grad. */
    fun compass(azimuth: Double): String {
        val names = arrayOf(
            "N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        val a = ((azimuth % 360.0) + 360.0) % 360.0
        return names[((a + 11.25) / 22.5).toInt() % 16]
    }

    /** Azimut und Höhe (Grad) der ISS für einen Beobachter. */
    fun lookAngles(sat: Sgp4, obs: Observer, millis: Long): DoubleArray? {
        val teme = sat.positionAt(millis) ?: return null
        return Station(obs).look(temeToEcef(teme, millis))
    }

    /** Höhe der Sonne über dem Horizont (Grad) für einen Beobachter. */
    fun sunElevationAt(obs: Observer, millis: Long): Double = sunElevation(Station(obs), millis)

    // ---------- intern ----------

    private fun analyse(sat: Sgp4, st: Station, rise: Long, set: Long): IssPass? {
        var maxEl = -90.0
        var maxTime = rise
        var visStart = -1L
        var visEnd = -1L
        var visAz = 0.0
        var startAz = 0.0
        var endAz = 0.0

        var t = rise
        while (t <= set) {
            val teme = sat.positionAt(t) ?: return null
            val look = st.look(temeToEcef(teme, t))
            val az = look[0]
            val el = look[1]
            if (t == rise) startAz = az
            endAz = az
            if (el > maxEl) {
                maxEl = el
                maxTime = t
            }
            if (el >= VISIBLE_MIN_ELEVATION && isSunlit(teme, t) &&
                sunElevation(st, t) < DARK_SUN_ELEVATION
            ) {
                if (visStart < 0) {
                    visStart = t
                    visAz = az
                }
                visEnd = t
            }
            t += 5_000L
        }
        return IssPass(
            startMillis = rise,
            endMillis = set,
            maxMillis = maxTime,
            maxElevation = maxEl,
            startAzimuth = startAz,
            endAzimuth = endAz,
            visible = visStart >= 0,
            visibleStartMillis = visStart,
            visibleEndMillis = visEnd,
            visibleStartAzimuth = visAz,
        )
    }

    /** Zeitpunkt des Horizontdurchgangs zwischen a und b per Intervallhalbierung. */
    private fun refine(sat: Sgp4, st: Station, a0: Long, b0: Long): Long {
        var a = a0
        var b = b0
        val signA = elevation(sat, st, a) >= 0.0
        while (b - a > 500L) {
            val m = (a + b) / 2
            if ((elevation(sat, st, m) >= 0.0) == signA) a = m else b = m
        }
        return b
    }

    private fun elevation(sat: Sgp4, st: Station, millis: Long): Double {
        val teme = sat.positionAt(millis) ?: return -90.0
        return st.look(temeToEcef(teme, millis))[1]
    }

    private fun julianDate(millis: Long) = millis / 86_400_000.0 + 2440587.5

    /** Mittlere Sternzeit Greenwich (Radiant). */
    private fun gmst(millis: Long): Double {
        val tut1 = (julianDate(millis) - 2451545.0) / 36525.0
        var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 +
                (876600.0 * 3600.0 + 8640184.812866) * tut1 + 67310.54841
        temp = (temp * DEG / 240.0) % (2.0 * PI)
        if (temp < 0.0) temp += 2.0 * PI
        return temp
    }

    /** Dreht ein TEME-/Inertial-Vektor in erdfeste Koordinaten. */
    private fun temeToEcef(v: DoubleArray, millis: Long): DoubleArray {
        val g = gmst(millis)
        val c = cos(g)
        val s = sin(g)
        return doubleArrayOf(c * v[0] + s * v[1], -s * v[0] + c * v[1], v[2])
    }

    /** Sonnenrichtung (Inertialsystem des Datums), Länge in km. Genauigkeit ~0,01°. */
    private fun sunPosition(millis: Long): DoubleArray {
        val n = julianDate(millis) - 2451545.0
        val l = (280.460 + 0.9856474 * n) * DEG
        val g = (357.528 + 0.9856003 * n) * DEG
        val lambda = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
        val eps = (23.439 - 0.0000004 * n) * DEG
        val r = (1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)) * AU_KM
        return doubleArrayOf(r * cos(lambda), r * cos(eps) * sin(lambda), r * sin(eps) * sin(lambda))
    }

    /** True, wenn die ISS nicht im Erdschatten liegt (zylindrisches Schattenmodell). */
    private fun isSunlit(sat: DoubleArray, millis: Long): Boolean {
        val sun = sunPosition(millis)
        val sunLen = sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2])
        val dot = (sat[0] * sun[0] + sat[1] * sun[1] + sat[2] * sun[2]) / sunLen
        if (dot > 0.0) return true // auf der Tagseite
        val satLen2 = sat[0] * sat[0] + sat[1] * sat[1] + sat[2] * sat[2]
        val perp = sqrt(satLen2 - dot * dot)
        return perp > EARTH_RADIUS_KM
    }

    private fun sunElevation(st: Station, millis: Long): Double {
        val sunEcef = temeToEcef(sunPosition(millis), millis)
        return st.look(sunEcef)[1]
    }

    /** Beobachter in erdfesten Koordinaten (WGS-84). */
    private class Station(obs: Observer) {
        private val sinLat = sin(obs.latDeg * DEG)
        private val cosLat = cos(obs.latDeg * DEG)
        private val sinLon = sin(obs.lonDeg * DEG)
        private val cosLon = cos(obs.lonDeg * DEG)
        private val x: Double
        private val y: Double
        private val z: Double

        init {
            val a = 6378.137
            val f = 1.0 / 298.257223563
            val e2 = f * (2.0 - f)
            val nRad = a / sqrt(1.0 - e2 * sinLat * sinLat)
            x = (nRad + obs.altKm) * cosLat * cosLon
            y = (nRad + obs.altKm) * cosLat * sinLon
            z = (nRad * (1.0 - e2) + obs.altKm) * sinLat
        }

        /** Liefert [Azimut, Höhe] in Grad zu einem erdfesten Zielpunkt (km). */
        fun look(target: DoubleArray): DoubleArray {
            val dx = target[0] - x
            val dy = target[1] - y
            val dz = target[2] - z
            val south = sinLat * cosLon * dx + sinLat * sinLon * dy - cosLat * dz
            val east = -sinLon * dx + cosLon * dy
            val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz
            val range = sqrt(dx * dx + dy * dy + dz * dz)
            val el = asin(up / range) / DEG
            var az = atan2(east, -south) / DEG
            if (az < 0.0) az += 360.0
            return doubleArrayOf(az, el)
        }
    }
}
