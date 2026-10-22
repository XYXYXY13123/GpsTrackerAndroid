package com.example.tachoansage.iss

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.example.tachoansage.MainActivity
import com.example.tachoansage.R
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lädt die ISS-Bahndaten, berechnet die nächsten Überflüge und plant
 * die Benachrichtigungen. [refresh] blockiert (Netzwerk) und muss in einem
 * Hintergrund-Thread laufen.
 */
object IssScheduler {

    const val CHANNEL_ID = "iss_ueberflug"
    const val ACTION_REFRESH = "com.example.tachoansage.ISS_REFRESH"

    private const val TLE_URL = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"
    private const val HOUR = 3_600_000L
    private const val TLE_MAX_AGE = 12 * HOUR      // Bahndaten zweimal täglich erneuern
    private const val MIN_ELEVATION = 10.0         // Überflüge darunter sind kaum zu sehen
    private const val HOURS_AHEAD = 48
    private const val MAX_ALARMS = 15
    private const val ALARM_REQUEST_BASE = 1000

    /** Ergebnis einer Aktualisierung, für die Anzeige in der App. */
    data class Result(val passes: List<IssPass>, val status: String)

    // ---------- Gesamtablauf ----------

    fun refresh(c: Context, allowNetwork: Boolean, forceNetwork: Boolean = false): Result {
        updateObserverFromLastKnown(c)
        val obs = IssPrefs.observer(c)
            ?: return Result(emptyList(), "Noch kein Standort bekannt – bitte Standortzugriff erlauben und GPS/Standort einschalten.")

        var networkError = false
        val age = System.currentTimeMillis() - IssPrefs.tleFetchTime(c)
        if (allowNetwork && (age > TLE_MAX_AGE || (forceNetwork && age > 2 * HOUR))) {
            networkError = !downloadTle(c)
        }
        val tle = IssPrefs.tle(c)
            ?: return Result(emptyList(), "Keine Bahndaten – für den ersten Abruf wird Internet benötigt.")

        val sat = try {
            Sgp4(tle.first, tle.second)
        } catch (e: Exception) {
            return Result(emptyList(), "Bahndaten fehlerhaft – bitte später erneut versuchen.")
        }

        val passes = IssPredictor.findPasses(sat, obs, System.currentTimeMillis(), HOURS_AHEAD, MIN_ELEVATION)
        if (IssPrefs.enabled(c)) scheduleNotifications(c, passes)

        val tleAgeH = (System.currentTimeMillis() - IssPrefs.tleFetchTime(c)) / HOUR
        val status = buildString {
            append("Standort ${"%.2f".format(Locale.GERMAN, obs.latDeg)}, ${"%.2f".format(Locale.GERMAN, obs.lonDeg)}")
            append(" · Bahndaten ")
            append(if (tleAgeH < 1) "aktuell" else "vor $tleAgeH Std. geladen")
            if (networkError) append(" · Abruf fehlgeschlagen, nutze gespeicherte Daten")
        }
        return Result(passes, status)
    }

    // ---------- Bahndaten ----------

    private fun downloadTle(c: Context): Boolean = try {
        val conn = URL(TLE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "TachoAnsage-Android/1.1")
        val lines = conn.inputStream.bufferedReader().use { it.readLines() }.map { it.trimEnd() }
        conn.disconnect()
        val l1 = lines.first { it.startsWith("1 25544") }
        val l2 = lines.first { it.startsWith("2 25544") }
        Sgp4(l1, l2) // prüft, ob die Daten gültig sind
        IssPrefs.setTle(c, l1, l2, System.currentTimeMillis())
        true
    } catch (e: Exception) {
        false
    }

    // ---------- Standort ----------

    /** Speichert einen neuen Standort (z. B. vom Tacho-GPS). */
    fun saveObserver(c: Context, location: Location) {
        val altKm = if (location.hasAltitude()) location.altitude / 1000.0 else 0.0
        IssPrefs.setObserver(c, location.latitude, location.longitude, altKm, location.time)
    }

    /** Nimmt den neuesten Standort, den Android noch gespeichert hat (ohne GPS einzuschalten). */
    @SuppressLint("MissingPermission")
    fun updateObserverFromLastKnown(c: Context) {
        if (!hasLocationPermission(c)) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        val best = lm.allProviders.mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
        }.maxByOrNull { it.time } ?: return
        if (best.time > IssPrefs.observerTime(c)) saveObserver(c, best)
    }

    fun hasLocationPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------- Benachrichtigungen planen ----------

    private fun scheduleNotifications(c: Context, passes: List<IssPass>) {
        cancelNotifications(c)
        val am = c.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val lead = IssPrefs.leadMinutes(c) * 60_000L
        val visibleOnly = IssPrefs.visibleOnly(c)

        var count = 0
        for (pass in passes) {
            if (count >= MAX_ALARMS) break
            if (visibleOnly && !pass.visible) continue
            val begin = if (visibleOnly) pass.visibleStartMillis else pass.startMillis
            val notifyAt = begin - lead
            if (notifyAt < now) continue

            val (title, text) = notificationText(pass, IssPrefs.leadMinutes(c))
            val intent = Intent(c, IssAlarmReceiver::class.java)
                .putExtra(IssAlarmReceiver.EXTRA_TITLE, title)
                .putExtra(IssAlarmReceiver.EXTRA_TEXT, text)
                .putExtra(IssAlarmReceiver.EXTRA_ID, ALARM_REQUEST_BASE + count)
            val pi = PendingIntent.getBroadcast(
                c, ALARM_REQUEST_BASE + count, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAt, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAt, pi)
            }
            count++
        }
        IssPrefs.setScheduledCount(c, count)
    }

    fun cancelNotifications(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val old = maxOf(IssPrefs.scheduledCount(c), MAX_ALARMS)
        for (i in 0 until old) {
            val pi = PendingIntent.getBroadcast(
                c, ALARM_REQUEST_BASE + i, Intent(c, IssAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
        IssPrefs.setScheduledCount(c, 0)
    }

    /** Regelmäßige Neuberechnung (ca. alle 12 Stunden), auch wenn die App geschlossen ist. */
    fun schedulePeriodicRefresh(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_DAY,
            AlarmManager.INTERVAL_HALF_DAY,
            refreshIntent(c)
        )
    }

    fun cancelPeriodicRefresh(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(refreshIntent(c))
    }

    private fun refreshIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 1, Intent(c, IssRefreshReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ---------- Texte ----------

    private fun time(millis: Long) = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(millis))

    private fun notificationText(pass: IssPass, lead: Int): Pair<String, String> {
        val maxEl = pass.maxElevation.roundToInt()
        return if (pass.visible) {
            val title = "ISS in $lead Min. sichtbar!"
            val text = "Ab ${time(pass.visibleStartMillis)} aus ${IssPredictor.compass(pass.visibleStartAzimuth)}, " +
                    "höchster Punkt $maxEl° um ${time(pass.maxMillis)}, " +
                    "sichtbar bis ${time(pass.visibleEndMillis)}. Heller, ruhig wandernder Punkt – kein Blinken."
            title to text
        } else {
            val title = "ISS fliegt in $lead Min. über dich"
            val text = "Ab ${time(pass.startMillis)} aus ${IssPredictor.compass(pass.startAzimuth)}, " +
                    "höchster Punkt $maxEl° um ${time(pass.maxMillis)}, " +
                    "weg um ${time(pass.endMillis)} im ${IssPredictor.compass(pass.endAzimuth)}. " +
                    "Diesmal nicht mit bloßem Auge sichtbar."
            title to text
        }
    }

    /** Eine Zeile für die Liste in der App. */
    fun listLine(pass: IssPass): String {
        val day = SimpleDateFormat("EE dd.MM.", Locale.GERMAN).format(Date(pass.startMillis))
        val start = if (pass.visible) pass.visibleStartMillis else pass.startMillis
        val dir = IssPredictor.compass(if (pass.visible) pass.visibleStartAzimuth else pass.startAzimuth)
        val mark = if (pass.visible) "  ★ sichtbar" else ""
        return "$day ${time(start)}  aus $dir  max ${pass.maxElevation.roundToInt()}°$mark"
    }

    // ---------- Anzeige ----------

    fun createChannel(c: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "ISS-Überflüge", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Meldet, wenn die ISS bald über deinen Standort fliegt"
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showNotification(c: Context, id: Int, title: String, text: String) {
        createChannel(c)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_iss)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
