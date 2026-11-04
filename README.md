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
- Mindestversion **Android 16**. Getestet nur auf **Motorola Moto G75** und **Moto G86**.

Details zu Anforderungen und Entscheidungen: [docs/ANFORDERUNGEN.md](docs/ANFORDERUNGEN.md)

## Installation (ohne Play Store)

**Voraussetzung:** Android 16 oder neuer (*Einstellungen → Über das Telefon → Android-Version*).
Auf älteren Geräten bricht die Installation mit „App nicht installiert“ ab.
Getestet wurde die App bisher nur auf dem Motorola Moto G75 und Moto G86. Andere Hersteller
beenden Hintergrund-Apps teils strenger; dann die App in den Akku-Einstellungen auf
„Nicht einschränken“ stellen.

1. Unter **[Releases](https://github.com/astral1977/SmbBackup/releases/latest)** die Datei
   `SmbBackup-<Version>.apk` herunterladen (am einfachsten direkt am Handy).
2. Die APK antippen.
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
6. Optional **Wake-on-LAN**: *Systemsteuerung → Hardware & Energie → Allgemein → „WOL auf LAN 1
   aktivieren“*. Die MAC-Adresse steht unter *Systemsteuerung → Netzwerk → Netzwerkschnittstelle*.
   Das NAS muss per Kabel angeschlossen und normal heruntergefahren worden sein.

## App einrichten

1. **Status**-Tab: alle Punkte unter *Einrichtung* erlauben.
   - *Standort „Immer erlauben“* ist nötig, weil Android den WLAN-Namen sonst im Hintergrund nicht verrät.
     Die App ermittelt keinen Standort.
   - *Keine Akku-Einschränkung*: sonst kann Android die Sicherung verzögern oder nach 10 Minuten abbrechen.
2. **Einstellungen**-Tab: NAS-Adresse, Freigabe, Zielordner, Benutzer, Passwort, Heim-WLAN
   („Aktuelles WLAN übernehmen“), Zeitplan und Ordner eintragen, **Speichern**.
3. „Verbindung testen“ prüft WLAN, Erreichbarkeit, Anmeldung, Verschlüsselung und Schreibrecht.
   Mit eingetragener MAC-Adresse weckt „NAS aufwecken“ das NAS per Wake-on-LAN.
4. „Jetzt sichern“ für den ersten (evtl. großen) Durchlauf am besten mit geöffneter App im WLAN.

Zielpfad auf dem NAS: `\\NAS\Freigabe\<Zielordner>\<Unterordner je Ordner>\…`

## So funktioniert es

```
Exakter Alarm (Wochentag + Uhrzeit)
  └─ WorkManager-Job (Vordergrunddienst "dataSync")
       ├─ Heim-WLAN? (SSID)      ─┐
       ├─ NAS:445 erreichbar?     │ alle 30 s erneut prüfen,
       │    sonst Wake-on-LAN     │ bis Wartezeit abgelaufen
       ├─ Anmeldung (SMB3,       ─┘ (Zeitüberschreitung → neuer Versuch)
       │    verschlüsselt) – Verbindung ausdrücklich über das WLAN
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

GitHub Actions führt bei jedem Push die Tests (inkl. Samba-Server) aus und baut die APK
(zum Testen unter *Actions → Build → Artifacts*, 90 Tage aufbewahrt).

### Neue Version veröffentlichen

Am einfachsten auf GitHub: *Actions → Build → Run workflow*, Branch `main` wählen und bei
„Als Release veröffentlichen“ die Version eintragen (z. B. `1.1.0`). Alternativ einen Tag pushen:

```bash
git tag v1.1.0
git push origin v1.1.0
```

GitHub Actions baut daraus die APK mit Versionsname `1.1.0` und legt unter *Releases* ein
Release mit `SmbBackup-1.1.0.apk` an. Releases bleiben dauerhaft, GitHub zählt dort die Downloads.
Die Build-Nummer (`versionCode`) ist die fortlaufende Nummer des Actions-Laufs.

### Signaturschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte
Version. Ohne weitere Einrichtung signiert der Build mit `app/signing/dev.keystore` aus dem Repository
(praktisch, aber jeder mit Zugriff aufs Repository könnte damit signieren).

Eigener Schlüssel über GitHub-Secrets – unter Linux erledigt das ein Script (fragt nach einem
Passwort, legt den Schlüssel in `~/smbbackup-keys` ab und setzt die Secrets per `gh`, falls die
GitHub-CLI installiert und angemeldet ist; sonst zeigt es, was von Hand einzutragen ist):

```bash
./scripts/create-release-keystore.sh
```

Von Hand geht es so:

1. Schlüssel erzeugen (`keytool` gehört zu Java bzw. Android Studio, dort unter `jbr/bin`):
   ```bash
   keytool -genkeypair -keystore smbbackup-release.jks -storetype PKCS12 \
     -alias smbbackup -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=SmbBackup"
   ```
   Datei und Passwort sicher aufbewahren (z. B. Passwort-Manager). Geht der Schlüssel verloren,
   muss die App für Updates deinstalliert und neu eingerichtet werden.
2. Datei als Base64-Text kopieren:
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("smbbackup-release.jks")) | Set-Clipboard`
   - Linux: `base64 -w0 smbbackup-release.jks` · macOS: `base64 -i smbbackup-release.jks | pbcopy`
3. Auf GitHub: *Repository → Settings → Secrets and variables → Actions → New repository secret*:

   | Name | Wert |
   |---|---|
   | `SMBBACKUP_KEYSTORE_BASE64` | der Base64-Text aus Schritt 2 |
   | `SMBBACKUP_KEYSTORE_PASSWORD` | Passwort aus Schritt 1 |
   | `SMBBACKUP_KEY_ALIAS` | `smbbackup` |
   | `SMBBACKUP_KEY_PASSWORD` | dasselbe Passwort (bei PKCS12 identisch) |

4. Build neu starten (*Actions → Build → Run workflow* oder neuer Push). Im Schritt
   „Signatur anzeigen“ muss jetzt `CN=SmbBackup` statt `CN=SmbBackup private` stehen.

War schon eine APK mit dem Entwicklungsschlüssel installiert, muss sie einmal deinstalliert werden.
Lokal lässt sich derselbe Schlüssel über die Umgebungsvariablen `SMBBACKUP_KEYSTORE` (Pfad zur Datei),
`SMBBACKUP_KEYSTORE_PASSWORD`, `SMBBACKUP_KEY_ALIAS` und `SMBBACKUP_KEY_PASSWORD` verwenden.
