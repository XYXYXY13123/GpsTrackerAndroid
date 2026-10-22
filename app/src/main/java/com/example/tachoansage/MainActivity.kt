package com.example.tachoansage

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }

    private lateinit var speedValue: TextView
    private lateinit var unitLabel: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var startStopButton: Button

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
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
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
                gpsStatus.text = "Suche GPS-Signal …\n(klappt im Freien am besten)"
                startStopButton.text = "Stopp"
            }
        }
    }
}
