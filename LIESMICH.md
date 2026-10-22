# Tacho Ansage

Android-App, die per GPS die Geschwindigkeit misst und sie regelmäßig auf Deutsch ansagt.

## Funktionen
- Große Live-Anzeige der Geschwindigkeit
- Sprachansage alle 5 / 10 / 15 / 30 / 60 Sekunden
- Einheiten: km/h, mph, Knoten
- Läuft weiter bei ausgeschaltetem Bildschirm (Benachrichtigung mit Stopp-Knopf)
- Musik wird während der Ansage kurz leiser
- Optional: keine Ansage im Stillstand
- Zahl antippen = sofortige Ansage
- **ISS-Überflüge:** Benachrichtigung 5–30 Minuten bevor die Raumstation über dich fliegt
  (mind. 10° über dem Horizont), mit Richtung, Höhe und ob sie mit bloßem Auge sichtbar ist.
  Bahndaten kommen zweimal täglich von celestrak.org, berechnet wird direkt am Handy (SGP4).

## Installieren
1. Android Studio installieren: https://developer.android.com/studio
2. ZIP entpacken, in Android Studio: **File → Open** → Ordner `TachoAnsage` wählen.
   Beim ersten Öffnen lädt Android Studio alles Nötige herunter (dauert ein paar Minuten).
3. Am Handy: **Einstellungen → Über das Telefon → 7× auf "Build-Nummer" tippen**
   → dann unter **Entwickleroptionen** "USB-Debugging" einschalten.
4. Handy per USB anschließen, in Android Studio oben das Handy auswählen und auf ▶ (Run) klicken.

Alternativ ohne Kabel: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**,
die APK-Datei aufs Handy kopieren und dort öffnen (Installation aus unbekannten Quellen erlauben).

## Aufbau
- `MainActivity.kt` – Bildschirm, Einstellungen, Berechtigungen
- `SpeedService.kt` – GPS-Abfrage, Sprachausgabe, Benachrichtigung
- `Prefs.kt` – gespeicherte Einstellungen und Einheiten
- `iss/Sgp4.kt` – Bahnberechnung (SGP4, geprüft gegen die offiziellen Testwerte)
- `iss/IssPredictor.kt` – Überflüge, Sonnenstand, Erdschatten
- `iss/IssScheduler.kt` – Bahndaten laden, Wecker planen, Benachrichtigungen

## Hinweise
- GPS braucht freie Sicht zum Himmel; im Gebäude kommt oft kein Signal.
- Falls keine deutsche Stimme installiert ist: Handy-Einstellungen → "Text-in-Sprache"
  → Sprachdaten für Deutsch herunterladen.
- Im Straßenverkehr: Handy nicht während der Fahrt bedienen.
