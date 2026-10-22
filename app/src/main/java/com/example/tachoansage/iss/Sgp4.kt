package com.example.tachoansage.iss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SGP4-Bahnmodell (Standardverfahren für TLE-Bahndaten), Variante für
 * erdnahe Satelliten wie die ISS. Nach Vallado et al., "Revisiting Spacetrack Report #3".
 * Liefert Positionen im TEME-Koordinatensystem in Kilometern.
 */
class Sgp4(line1: String, line2: String) {

    private companion object {
        const val TWO_PI = 2.0 * PI
        const val DEG2RAD = PI / 180.0
        const val X2O3 = 2.0 / 3.0

        // WGS-72 Konstanten (wie bei der Erzeugung der TLE-Daten verwendet)
        const val MU = 398600.8
        const val RE = 6378.135
        val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2
    }

    /** Epoche der Bahndaten als Unix-Zeit in Millisekunden. */
    val epochMillis: Long

    private val bstar: Double
    private val ecco: Double
    private val argpo: Double
    private val inclo: Double
    private val mo: Double
    private val nodeo: Double
    private val noUnkozai: Double

    private val isimp: Boolean
    private val aycof: Double
    private val con41: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val mdot: Double
    private val nodedot: Double
    private val xlcof: Double
    private val xmcof: Double
    private val nodecf: Double

    init {
        require(line1.length >= 63 && line2.length >= 63) { "TLE-Zeilen zu kurz" }

        // ---- TLE einlesen ----
        val yy = line1.substring(18, 20).trim().toInt()
        val year = if (yy < 57) 2000 + yy else 1900 + yy
        val epochDays = line1.substring(20, 32).trim().toDouble()
        epochMillis = startOfYearMillis(year) + ((epochDays - 1.0) * 86_400_000.0).toLong()
        bstar = parseExp(line1.substring(53, 61))

        inclo = line2.substring(8, 16).trim().toDouble() * DEG2RAD
        nodeo = line2.substring(17, 25).trim().toDouble() * DEG2RAD
        ecco = ("0." + line2.substring(26, 33).trim()).toDouble()
        argpo = line2.substring(34, 42).trim().toDouble() * DEG2RAD
        mo = line2.substring(43, 51).trim().toDouble() * DEG2RAD
        val noKozai = line2.substring(52, 63).trim().toDouble() * TWO_PI / 1440.0 // rad/min

        // ---- initl ----
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio

        val ak = (XKE / noKozai).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = noKozai / (1.0 + del)

        val ao = (XKE / noUnkozai).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        // ---- sgp4init (ohne Deep-Space-Teil) ----
        val ss = 78.0 / RE + 1.0
        val qzms2t = ((120.0 - 78.0) / RE).pow(4)
        isimp = rp < (220.0 / RE + 1.0)

        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq

        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                        J2 * tsi / (ao * psisq) *
                        (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                                0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)

        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        } else {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12
        }
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1.0 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0

        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    /** Position (TEME, km) zur Unix-Zeit [millis], oder null falls die Berechnung scheitert. */
    fun positionAt(millis: Long): DoubleArray? = propagate((millis - epochMillis) / 60_000.0)

    /** Position (TEME, km) [tsince] Minuten nach der Epoche. */
    fun propagate(tsince: Double): DoubleArray? {
        val t = tsince
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (!isimp) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }

        var nm = noUnkozai
        var em = ecco
        val inclm = inclo
        if (nm <= 0.0) return null
        val am = (XKE / nm).pow(X2O3) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem
        nodem = mod2pi(nodem)
        argpm = mod2pi(argpm)
        xlm = mod2pi(xlm)
        mm = mod2pi(xlm - argpm - nodem)

        val sinip = sin(inclm)
        val cosip = cos(inclm)

        // ---- Lang- und kurzperiodische Terme ----
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        // Kepler-Gleichung lösen
        val u = mod2pi(xl - nodem)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null

        val rl = am * (1.0 - ecose)
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u
        if (mrt < 1.0) return null // Satellit wäre abgestürzt

        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu

        val r = mrt * RE
        return doubleArrayOf(r * ux, r * uy, r * uz)
    }

    private fun mod2pi(x: Double): Double {
        var r = x % TWO_PI
        if (r < 0.0) r += TWO_PI
        return r
    }

    /** Liest TLE-Zahlen im Format " 12345-4" (= 0.12345e-4). */
    private fun parseExp(field: String): Double {
        val s = field.trim()
        if (s.isEmpty()) return 0.0
        var sign = 1.0
        var body = s
        if (body[0] == '-' || body[0] == '+') {
            if (body[0] == '-') sign = -1.0
            body = body.substring(1)
        }
        val expIndex = body.indexOfAny(charArrayOf('-', '+'), 1)
        return if (expIndex > 0) {
            val mantissa = ("0." + body.substring(0, expIndex)).toDouble()
            val exponent = body.substring(expIndex).toInt()
            sign * mantissa * 10.0.pow(exponent)
        } else {
            sign * ("0.$body").toDouble()
        }
    }

    private fun startOfYearMillis(year: Int): Long {
        // Tage seit 1970-01-01 bis zum 1. Januar des Jahres
        var days = 0L
        if (year >= 1970) {
            for (y in 1970 until year) days += if (isLeap(y)) 366 else 365
        } else {
            for (y in year until 1970) days -= if (isLeap(y)) 366 else 365
        }
        return days * 86_400_000L
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}
