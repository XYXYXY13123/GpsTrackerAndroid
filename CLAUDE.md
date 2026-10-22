# Tacho Ansage – Hinweise für Claude

Android-App (Kotlin): GPS-Geschwindigkeit mit deutscher Sprachansage + Benachrichtigung bei ISS-Überflügen.
Der Besitzer (Niki) installiert die APK direkt am Handy, nicht über den Play Store. Antworten auf Deutsch.

## Aufbau (≈1.700 Zeilen, Paket `com.example.tachoansage`)
- `MainActivity.kt` – Bildschirm, Einstellungen, Berechtigungen (Layout: `res/layout/activity_main.xml`)
- `SpeedService.kt` – Foreground-Service: GPS (`LocationManager`), TextToSpeech, Benachrichtigung
- `Prefs.kt` / `iss/IssPrefs.kt` – Einstellungen als `object` über `SharedPreferences`
- `iss/Sgp4.kt` – SGP4-Bahnberechnung, gegen offizielle Testwerte geprüft. **Nur ändern, wenn wirklich nötig.**
- `iss/IssPredictor.kt` – Überflüge, Sonnenstand, Erdschatten
- `iss/IssScheduler.kt` – TLE von celestrak.org laden (2× täglich), Wecker (`AlarmManager`) planen, Benachrichtigungen
- `iss/IssReceivers.kt` – Alarm-Receiver + Neustart/Update-Receiver

Gezielt die betroffene Datei lesen statt alles; die Liste oben reicht zur Orientierung.

## Stil & Regeln
- **Keine externen Bibliotheken, kein AndroidX, kein Compose.** Nur Android-Framework: `Activity`, XML-Layout + `findViewById`, `Theme.Material`, `Notification.Builder`.
- Hintergrundarbeit mit `Thread { … }` + `runOnUiThread`, keine Coroutines. Netzwerk mit `HttpURLConnection`.
- UI-Texte und Kommentare auf **Deutsch**; Texte stehen direkt im Code/Layout (`strings.xml` hat nur `app_name`).
- minSdk 26, target/compileSdk 35, Java/Kotlin 17. Neue Berechtigungen immer im `AndroidManifest.xml` und, wo nötig, zur Laufzeit in `MainActivity` anfragen.
- Kleine, gezielte Änderungen; bestehende Struktur beibehalten.

## Version & Signatur
- Bei jeder Funktionsänderung `versionCode` (+1) und `versionName` in `app/build.gradle.kts` erhöhen.
- `app/tacho-signing.jks` und die `signingConfig` **nicht ändern** – sonst lässt sich die neue APK nicht über die alte installieren.

## Bauen & prüfen
- Jeder Push auf `main` baut per GitHub Actions (`.github/workflows/build-apk.yml`, `assembleDebug`) und veröffentlicht `TachoAnsage.apk` als Release `build-N`.
- Nach dem Push den Lauf prüfen: `gh run watch` bzw. `gh run view --log-failed`. Fehler beheben, bis der Build grün ist.
- Lokal (falls SDK vorhanden): `./gradlew assembleDebug`.

## Doku
- Neue oder geänderte Funktionen in `LIESMICH.md` (Abschnitte „Funktionen“ / „Aufbau“) nachtragen.
