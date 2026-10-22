package com.example.tachoansage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.tachoansage.iss.IssPrefs
import com.example.tachoansage.iss.IssScheduler
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_ISS_PERMISSIONS = 2
    }

    private lateinit var speedValue: TextView
    private lateinit var unitLabel: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var startStopButton: Button

    private lateinit var issSwitch: Switch
    private lateinit var issPassList: TextView
    private lateinit var issStatus: TextView
    private var issBusy = false
    private var issRefreshPending = false
    private var singleFixListener: LocationListener? = null

    private val handler = Handler(Looper.getMainLooper())

    // Anzeige zweimal pro Sekunde aktualisieren
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedValue = findViewById(R.id.speedValue)
        unitLabel = findViewById(R.id.unitLabel)
        gpsStatus = findViewById(R.id.gpsStatus)
        startStopButton = findViewById(R.id.startStopButton)

        setupIntervalChoice(findViewById(R.id.intervalGroup))
        setupUnitChoice(findViewById(R.id.unitGroup))

        val skipZero = findViewById<Switch>(R.id.skipZeroSwitch)
        skipZero.isChecked = Prefs.skipZero(this)
        skipZero.setOnCheckedChangeListener { _, checked -> Prefs.setSkipZero(this, checked) }

        startStopButton.setOnClickListener {
            if (SpeedService.isRunning) {
                startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_STOP))
            } else {
                startTracking()
            }
        }

        speedValue.setOnClickListener {
            if (SpeedService.isRunning) {
                startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_SPEAK_NOW))
            }
        }

        setupIss()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        if (IssPrefs.enabled(this)) {
            requestSingleLocation(force = false)
            refreshIss(force = false)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        stopSingleLocation()
        super.onDestroy()
    }

    // ---------- ISS-Überflüge ----------

    private fun setupIss() {
        issSwitch = findViewById(R.id.issSwitch)
        issPassList = findViewById(R.id.issPassList)
        issStatus = findViewById(R.id.issStatus)

        issSwitch.isChecked = IssPrefs.enabled(this)
        issSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                enableIss()
            } else {
                IssPrefs.setEnabled(this, false)
                IssScheduler.cancelNotifications(this)
                IssScheduler.cancelPeriodicRefresh(this)
                issPassList.text = "—"
                issStatus.text = "Benachrichtigungen sind aus."
            }
        }
        if (!IssPrefs.enabled(this)) issStatus.text = "Einschalten, um die Überflüge zu berechnen."

        val visibleSwitch = findViewById<Switch>(R.id.issVisibleSwitch)
        visibleSwitch.isChecked = IssPrefs.visibleOnly(this)
        visibleSwitch.setOnCheckedChangeListener { _, checked ->
            IssPrefs.setVisibleOnly(this, checked)
            if (IssPrefs.enabled(this)) refreshIss(force = false)
        }

        val leadGroup = findViewById<RadioGroup>(R.id.issLeadGroup)
        val currentLead = IssPrefs.leadMinutes(this)
        for (minutes in IssPrefs.LEAD_MINUTES) {
            val button = RadioButton(this).apply {
                text = "$minutes Min."
                textSize = 15f
                layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            leadGroup.addView(button)
            if (minutes == currentLead) button.isChecked = true
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    IssPrefs.setLeadMinutes(this, minutes)
                    if (IssPrefs.enabled(this)) refreshIss(force = false)
                }
            }
        }

        findViewById<Button>(R.id.issRefreshButton).setOnClickListener {
            if (IssPrefs.enabled(this)) {
                requestSingleLocation(force = true)
                refreshIss(force = true)
            } else {
                Toast.makeText(this, "Bitte zuerst die ISS-Benachrichtigung einschalten.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableIss() {
        val missing = mutableListOf<String>()
        if (!IssScheduler.hasLocationPermission(this)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_ISS_PERMISSIONS)
        } else {
            activateIss()
        }
    }

    private fun activateIss() {
        IssPrefs.setEnabled(this, true)
        IssScheduler.createChannel(this)
        IssScheduler.schedulePeriodicRefresh(this)
        requestSingleLocation(force = false)
        refreshIss(force = false)
    }

    /** Berechnet die Überflüge im Hintergrund neu und zeigt sie an. */
    private fun refreshIss(force: Boolean) {
        if (issBusy) {
            issRefreshPending = true // nach der laufenden Berechnung noch einmal rechnen
            return
        }
        issBusy = true
        issStatus.text = "Berechne Überflüge …"
        val app = applicationContext
        Thread {
            val result = IssScheduler.refresh(app, allowNetwork = true, forceNetwork = force)
            runOnUiThread {
                issBusy = false
                showIssResult(result)
                if (issRefreshPending) {
                    issRefreshPending = false
                    refreshIss(force = false)
                }
            }
        }.start()
    }

    private fun showIssResult(result: IssScheduler.Result) {
        val visibleOnly = IssPrefs.visibleOnly(this)
        val shown = if (visibleOnly) result.passes.filter { it.visible } else result.passes
        issPassList.text = when {
            shown.isNotEmpty() -> shown.take(8).joinToString("\n") { IssScheduler.listLine(it) }
            result.passes.isEmpty() && IssPrefs.observer(this) == null -> "—"
            visibleOnly -> "In den nächsten 48 Std. kein sichtbarer Überflug."
            else -> "In den nächsten 48 Std. kein Überflug."
        }
        issStatus.text = result.status
    }

    /** Holt einmalig einen frischen Standort (Netzwerk oder GPS), falls der gespeicherte alt ist. */
    @SuppressLint("MissingPermission")
    private fun requestSingleLocation(force: Boolean) {
        if (!IssScheduler.hasLocationPermission(this) || singleFixListener != null) return
        val age = System.currentTimeMillis() - IssPrefs.observerTime(this)
        if (!force && IssPrefs.observer(this) != null && age < 6 * 3_600_000L) return

        val lm = getSystemService(LocationManager::class.java)
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                LocationManager.GPS_PROVIDER
            else -> return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                IssScheduler.saveObserver(this@MainActivity, location)
                stopSingleLocation()
                refreshIss(force = false)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        singleFixListener = listener
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            handler.postDelayed({ stopSingleLocation() }, 60_000L) // nach 1 Minute aufgeben
        } catch (e: Exception) {
            singleFixListener = null
        }
    }

    private fun stopSingleLocation() {
        val listener = singleFixListener ?: return
        singleFixListener = null
        getSystemService(LocationManager::class.java).removeUpdates(listener)
    }

    // ---------- Auswahl-Knöpfe ----------

    private fun setupIntervalChoice(group: RadioGroup) {
        val current = Prefs.interval(this)
        for (seconds in Prefs.INTERVALS) {
            val button = RadioButton(this).apply {
                text = "$seconds s"
                textSize = 15f
                layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            group.addView(button)
            if (seconds == current) button.isChecked = true
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) Prefs.setInterval(this, seconds)
            }
        }
    }

    private fun setupUnitChoice(group: RadioGroup) {
        val current = Prefs.unit(this)
        for (unit in SpeedUnit.values()) {
            val button = RadioButton(this).apply {
                text = unit.label
                textSize = 15f
                layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            group.addView(button)
            if (unit == current) button.isChecked = true
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.setUnit(this, unit)
                    updateUi()
                }
            }
        }
    }

    // ---------- Start & Berechtigungen ----------

    private fun startTracking() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            launchService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_ISS_PERMISSIONS) {
            if (IssScheduler.hasLocationPermission(this)) {
                activateIss()
            } else {
                issSwitch.isChecked = false
                Toast.makeText(this, "Für die ISS-Berechnung wird dein Standort benötigt.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != REQUEST_PERMISSIONS) return

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            launchService()
        } else {
            Toast.makeText(
                this,
                "Ohne \"Genauer Standort\" kann die App die Geschwindigkeit nicht messen.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchService() {
        val locationManager = getSystemService(LocationManager::class.java)
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Bitte Standort (GPS) einschalten", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startForegroundService(Intent(this, SpeedService::class.java))
        handler.postDelayed({ updateUi() }, 300L)
    }

    // ---------- Anzeige ----------

    private fun updateUi() {
        val unit = Prefs.unit(this)
        unitLabel.text = unit.label

        when {
            !SpeedService.isRunning -> {
                speedValue.text = "--"
                gpsStatus.text = "Gestoppt"
                startStopButton.text = "Start"
            }
            SpeedService.hasFix() -> {
                speedValue.text = unit.convert(SpeedService.currentSpeedMs).roundToInt().toString()
                gpsStatus.text = "GPS-Signal ok"
                startStopButton.text = "Stopp"
            }
            else -> {
                speedValue.text = "--"
                startStopButton.text = "Stopp"
                val secs = SpeedService.secondsSinceStart()
                val inView = SpeedService.satellitesInView
                val used = SpeedService.satellitesUsed
                val sats = "Satelliten: $used genutzt, $inView empfangen · seit $secs s"
                gpsStatus.text = when {
                    SpeedService.hasPositionOnly() ->
                        "Standort gefunden, warte auf GPS-Geschwindigkeit …\n$sats"
                    secs > 45 && inView == 0 ->
                        "Kein einziger Satellit empfangen.\nBist du drinnen? Geh ins Freie oder ans Fenster.\n$sats"
                    secs > 90 && used < 4 ->
                        "Zu wenig Satelliten für eine Position (mind. 4 nötig).\nFreie Sicht zum Himmel hilft.\n$sats"
                    else ->
                        "Suche GPS-Signal …\n$sats"
                }
            }
        }
    }
}
