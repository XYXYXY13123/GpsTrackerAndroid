package com.example.tachoansage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.tachoansage.iss.IssScheduler
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Läuft als Vordergrund-Dienst (mit Benachrichtigung), damit GPS und Ansage
 * auch bei ausgeschaltetem Bildschirm weiterlaufen.
 */
class SpeedService : Service(), LocationListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.example.tachoansage.STOP"
        const val ACTION_SPEAK_NOW = "com.example.tachoansage.SPEAK_NOW"

        private const val CHANNEL_ID = "tacho_ansage"
        private const val NOTIFICATION_ID = 1

        /** Unter ca. 1 km/h gilt als Stillstand (GPS "zittert" sonst im Stand). */
        private const val MIN_SPEED_MS = 0.3f

        /** Älter als 5 Sekunden = kein aktuelles GPS-Signal. */
        private const val FIX_TIMEOUT_MS = 5000L

        // Wird von der MainActivity gelesen, um die Anzeige zu aktualisieren.
        @Volatile var isRunning = false
            private set
        @Volatile var currentSpeedMs = 0f
            private set
        @Volatile private var lastFixTime = 0L
        @Volatile private var lastPositionTime = 0L
        @Volatile private var startTime = 0L

        /** Satelliten, die das Handy gerade empfängt bzw. für die Position nutzt. */
        @Volatile var satellitesInView = 0
            private set
        @Volatile var satellitesUsed = 0
            private set

        fun hasFix(): Boolean =
            isRunning && lastFixTime > 0 && SystemClock.elapsedRealtime() - lastFixTime < FIX_TIMEOUT_MS

        /** Standort bekannt (z. B. über WLAN), aber noch keine GPS-Geschwindigkeit. */
        fun hasPositionOnly(): Boolean =
            isRunning && !hasFix() && lastPositionTime > 0 &&
                    SystemClock.elapsedRealtime() - lastPositionTime < 30_000L

        fun secondsSinceStart(): Long =
            if (startTime == 0L) 0 else (SystemClock.elapsedRealtime() - startTime) / 1000
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastLocation: Location? = null
    private var noFixAnnounced = false
    private var lastNotificationText = ""

    // Zeigt an, wie viele Satelliten empfangen werden (hilft bei der Fehlersuche)
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesInView = status.satelliteCount
            satellitesUsed = used
        }
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Musik o. Ä. wird während der Ansage kurz leiser gestellt ("Ducking").
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    private val announceLoop = object : Runnable {
        override fun run() {
            announce(force = false)
            handler.postDelayed(this, Prefs.interval(this@SpeedService) * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK_NOW -> {
                if (isRunning) announce(force = true) else stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) return START_NOT_STICKY // läuft schon

        createChannel()
        try {
            val notification = buildNotification("Warte auf GPS-Signal …")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // z. B. fehlende Standortberechtigung
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startLocationUpdates()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        currentSpeedMs = 0f
        lastFixTime = 0L
        lastPositionTime = 0L
        startTime = SystemClock.elapsedRealtime()
        noFixAnnounced = false

        tts = TextToSpeech(this, this)
        // Erste Ansage nach einem Intervall, damit GPS Zeit hat, ein Signal zu finden
        handler.postDelayed(announceLoop, Prefs.interval(this) * 1000L)

        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        satellitesInView = 0
        satellitesUsed = 0
        var ok = false
        // 1) Reines GPS, jede Sekunde ein neuer Wert
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
            ok = true
        } catch (e: Exception) { }

        // 2) Zusätzlich der Android-Standortdienst (kombiniert GPS, WLAN, Mobilfunk, Sensoren).
        //    Findet oft schneller ein Signal und liefert auf manchen Handys die bessere Geschwindigkeit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
                    )
                    ok = true
                }
            } catch (e: Exception) { }
        }

        try {
            locationManager.registerGnssStatusCallback(gnssCallback, handler)
        } catch (e: Exception) { }
        return ok
    }

    // ---------- GPS ----------

    override fun onLocationChanged(location: Location) {
        lastPositionTime = SystemClock.elapsedRealtime()
        val fromGps = location.provider == LocationManager.GPS_PROVIDER

        // Position ohne Geschwindigkeit (z. B. über WLAN) ist für den Tacho zu ungenau
        if (!fromGps && !location.hasSpeed()) {
            saveObserverIfDue(location)
            return
        }

        val speed = when {
            location.hasSpeed() -> location.speed
            else -> speedFromDistance(location)
        }
        if (fromGps) lastLocation = location
        currentSpeedMs = if (speed < MIN_SPEED_MS) 0f else speed
        lastFixTime = SystemClock.elapsedRealtime()
        noFixAnnounced = false

        val unit = Prefs.unit(this)
        updateNotification("${unit.convert(currentSpeedMs).roundToInt()} ${unit.label}")

        saveObserverIfDue(location)
    }

    /** Standort alle 10 Minuten für die ISS-Berechnung merken. */
    private fun saveObserverIfDue(location: Location) {
        if (location.time - lastObserverSave > 10 * 60_000L) {
            lastObserverSave = location.time
            IssScheduler.saveObserver(this, location)
        }
    }

    private var lastObserverSave = 0L

    /** Fallback, falls das Gerät keine Geschwindigkeit liefert: Strecke / Zeit. */
    private fun speedFromDistance(location: Location): Float {
        val previous = lastLocation ?: return 0f
        val seconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000f
        if (seconds <= 0f) return currentSpeedMs
        return previous.distanceTo(location) / seconds
    }

    // Diese drei müssen für ältere Android-Versionen (< 11) ausdrücklich vorhanden sein.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER) return
        lastFixTime = 0L
        speak("GPS wurde ausgeschaltet")
    }

    // ---------- Sprachausgabe ----------

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return

        val result = engine.setLanguage(Locale.GERMAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setAudioAttributes(audioAttributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
        ttsReady = true
        speak("Tacho-Ansage gestartet")
    }

    private fun announce(force: Boolean) {
        if (!hasFix()) {
            if (force || !noFixAnnounced) {
                noFixAnnounced = true
                speak("Kein GPS-Signal")
            }
            return
        }
        if (currentSpeedMs == 0f && Prefs.skipZero(this) && !force) return

        val unit = Prefs.unit(this)
        val value = unit.convert(currentSpeedMs).roundToInt()
        speak("$value ${unit.spoken}")
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        audioManager.requestAudioFocus(focusRequest)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ansage")
    }

    // ---------- Benachrichtigung ----------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Tacho-Ansage", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Zeigt an, dass die Geschwindigkeitsansage läuft"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_speed), "Stopp", stop
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return // nur bei Änderung aktualisieren
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        isRunning = false
        currentSpeedMs = 0f
        lastFixTime = 0L
        handler.removeCallbacks(announceLoop)
        locationManager.removeUpdates(this)
        try { locationManager.unregisterGnssStatusCallback(gnssCallback) } catch (e: Exception) { }
        satellitesInView = 0
        satellitesUsed = 0
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        super.onDestroy()
    }
}
