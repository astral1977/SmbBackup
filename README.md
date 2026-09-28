# SmbBackup

Android-App, die zu festen Uhrzeiten ausgewählte Ordner des Handys auf eine SMB-Freigabe
(z. B. ein Synology-NAS mit DSM 6) kopiert – aber nur, wenn das Handy im Heim-WLAN ist und
das NAS erreichbar ist.

- **Nur kopieren:** Auf dem NAS wird nie etwas gelöscht. Neue Dateien werden hochgeladen,
  geänderte (optional) überschrieben.
- **SMB 2 / SMB 3** mit SMB3-Verschlüsselung und Signierung (Bibliothek [smbj](https://github.com/hierynomus/smbj)).
- **Zugangsdaten** AES-256-GCM-verschlüsselt, Schlüssel im Android Keystore (StrongBox, falls vorhanden).
- **Zeitplan:** Wochentage + Uhrzeit, exakter Alarm. Danach wird eine einstellbare Zeit auf
  Heim-WLAN und hochfahrendes NAS gewartet; klappt es nicht, wird es zum nächsten Termin wieder versucht.
- **Protokoll** jedes Laufs, **Benachrichtigung** bei Fehlern und wenn mehrere Tage kein Backup gelang.
- Mindestversion **Android 16**.

Details zu Anforderungen und Entscheidungen: [docs/ANFORDERUNGEN.md](docs/ANFORDERUNGEN.md)

## Installation (ohne Play Store)

1. Auf GitHub im Reiter **Actions** den neuesten erfolgreichen Lauf von „Build“ öffnen und unten
   unter *Artifacts* `SmbBackup-apk-…` herunterladen (ZIP, darin die APK).
2. APK aufs Handy kopieren (oder direkt am Handy herunterladen) und antippen.
3. Android fragt einmalig, ob die App (Dateimanager bzw. Browser) „unbekannte Apps installieren“ darf –
   erlauben. Bei Motorola: *Einstellungen → Apps → Spezieller App-Zugriff → Unbekannte Apps installieren*.
4. Play Protect warnt ggf. vor einer unbekannten App – „Trotzdem installieren“.

Updates: neue APK genauso installieren, die Einstellungen bleiben erhalten (gleicher Signaturschlüssel,
siehe unten).

## Synology DSM 6 vorbereiten

1. **Eigenen Benutzer** anlegen (z. B. `handy-backup`), nicht in der Gruppe *administrators*.
2. **Freigabe** anlegen oder bestehende nutzen, dem Benutzer dort *Lesen/Schreiben* geben, allen anderen Freigaben *Kein Zugriff*.
3. *Systemsteuerung → Dateidienste → SMB → Erweiterte Einstellungen*:
   - Maximales SMB-Protokoll: **SMB3**
   - Minimales SMB-Protokoll: **SMB2** (SMB1 aus)
4. Dem NAS im Router eine **feste IP-Adresse** geben und diese in der App eintragen
   (Android löst NetBIOS-Namen nicht zuverlässig auf).
5. Fährt das NAS per Zeitplan hoch, die Uhrzeit in der App so wählen, dass das NAS spätestens
   nach der eingestellten Wartezeit (Standard 15 Minuten) erreichbar ist.

## App einrichten

1. **Status**-Tab: alle Punkte unter *Einrichtung* erlauben.
   - *Standort „Immer erlauben“* ist nötig, weil Android den WLAN-Namen sonst im Hintergrund nicht verrät.
     Die App ermittelt keinen Standort.
   - *Keine Akku-Einschränkung*: sonst kann Android die Sicherung verzögern oder nach 10 Minuten abbrechen.
2. **Einstellungen**-Tab: NAS-Adresse, Freigabe, Zielordner, Benutzer, Passwort, Heim-WLAN
   („Aktuelles WLAN übernehmen“), Zeitplan und Ordner eintragen, **Speichern**.
3. „Verbindung testen“ prüft WLAN, Erreichbarkeit, Anmeldung, Verschlüsselung und Schreibrecht.
4. „Jetzt sichern“ für den ersten (evtl. großen) Durchlauf am besten mit geöffneter App im WLAN.

Zielpfad auf dem NAS: `\\NAS\Freigabe\<Zielordner>\<Unterordner je Ordner>\…`

## So funktioniert es

```
Exakter Alarm (Wochentag + Uhrzeit)
  └─ WorkManager-Job (Vordergrunddienst "dataSync")
       ├─ Heim-WLAN? (SSID)      ─┐ alle 30 s erneut prüfen,
       ├─ NAS:445 erreichbar?     ─┘ bis Wartezeit abgelaufen
       ├─ Anmeldung (SMB3, verschlüsselt) – Verbindung ausdrücklich über das WLAN
       ├─ je Ordner: Dateien vergleichen (Größe + Änderungszeit), Neues hochladen
       │    Upload in temporäre Datei, danach Umbenennen → keine halben Dateien
       └─ Protokoll-Eintrag, bei Fehlern Benachrichtigung
```

- Nicht im Heim-WLAN → Eintrag „Übersprungen“, keine Fehlermeldung.
- Im Heim-WLAN, aber NAS nicht erreichbar / Anmeldung fehlgeschlagen → „Fehlgeschlagen“ + Benachrichtigung.
- Einzelne Dateien fehlerhaft → „Teilweise fehlgeschlagen“ + Benachrichtigung, Details im Protokoll.
- Nach einem Abbruch geht der nächste Lauf dort weiter, wo es fehlt (bereits Kopiertes wird übersprungen).

## Entwicklung

Projektstruktur:

- `core/` – reines Kotlin/JVM: Kopierlogik, SMB-Zugriff, Zeitplan. Mit Unit-Tests und einem
  Integrationstest gegen einen echten SMB-Server.
- `app/` – Android-App (Jetpack Compose, WorkManager, AlarmManager, Keystore).

```bash
./gradlew :core:test                 # Unit-Tests
SMBBACKUP_TEST_HOST=127.0.0.1 SMBBACKUP_TEST_SHARE=backup \
SMBBACKUP_TEST_USER=backupuser SMBBACKUP_TEST_PASSWORD=geheim123 \
./gradlew :core:test                 # zusätzlich gegen echten Samba-Server
./gradlew :app:assembleRelease       # APK, benötigt Android SDK
```

GitHub Actions führt bei jedem Push die Tests (inkl. Samba-Server) aus und baut die APK.

### Signaturschlüssel

Damit Updates über eine installierte Version passen, wird jede APK mit demselben Schlüssel
`app/signing/dev.keystore` signiert. Der liegt im Repository – für eine rein private App in Ordnung.
Wer das nicht möchte, erzeugt einen eigenen Schlüssel und übergibt ihn per Umgebungsvariablen
`SMBBACKUP_KEYSTORE`, `SMBBACKUP_KEYSTORE_PASSWORD`, `SMBBACKUP_KEY_ALIAS`, `SMBBACKUP_KEY_PASSWORD`
(im Workflow z. B. aus GitHub-Secrets). Achtung: Bei Schlüsselwechsel muss die App einmal
deinstalliert und neu eingerichtet werden.
