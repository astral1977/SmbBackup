# Anforderungen und Entscheidungen

## Ziel

Ausgewählte Ordner des Handys (Fotos, Dokumente – typischerweise wenige Dateien pro Tag) werden zu
festen Zeiten auf eine SMB-Freigabe eines Synology-NAS (DSM 6) kopiert.

## Festgelegte Anforderungen

| Thema | Entscheidung |
|---|---|
| Kopiermodus | **Nur kopieren.** Auf dem NAS wird nie gelöscht. Geänderte Dateien werden überschrieben (abschaltbar: dann werden vorhandene Dateien nie angefasst). |
| Protokoll | SMB 2.0.2 bis SMB 3.1.1, SMB1 nicht unterstützt. SMB3-Verschlüsselung standardmäßig erzwungen (abschaltbar), Signierung immer. |
| NAS | Synology DSM 6, Anmeldung mit eigenem Benutzer (NTLMv2). |
| Zugangsdaten | Passwort AES-256-GCM-verschlüsselt, Schlüssel im Android Keystore (StrongBox wenn vorhanden). Kein Cloud-Backup der App-Daten. |
| Zeitplan | Feste Uhrzeit an gewählten Wochentagen (exakter Alarm). Das NAS fährt zu dieser Zeit hoch → einstellbare Wartezeit (Standard 15 min), in der alle 30 s geprüft wird. |
| Verpasster Termin | Kein Nachholen; nächster Versuch zum nächsten geplanten Termin. |
| Bedingungen | Handy ist mit einem eingetragenen **Heim-WLAN** (SSID) verbunden **und** das NAS ist auf Port 445 erreichbar. Keine Sicherung über Mobilfunk, Internet oder VPN; die Verbindung wird explizit an das WLAN gebunden. |
| Protokoll/Log | Jeder Lauf mit Zeit, Auslöser, Status, Anzahl geprüft/kopiert/unverändert/fehlerhaft, Datenmenge, Fehlerdetails je Datei. Aufbewahrung 180 Tage / max. 1000 Einträge. |
| Benachrichtigung | Bei Fehlschlag und Teilfehlern; Warnung nach n Tagen ohne erfolgreiche Sicherung (Standard 3); Erfolg optional. |
| Verteilung | Privat per APK (GitHub Actions), kein Play Store. |
| Android | minSdk = targetSdk = 36 (Android 16). |

## Weitere Punkte, die berücksichtigt sind

- **Ordnerzugriff** über die System-Ordnerauswahl (Storage Access Framework) mit dauerhafter
  Leseberechtigung – keine Berechtigung „Zugriff auf alle Dateien“ nötig.
- **Atomare Uploads:** erst `.<name>.smbbackup-part`, danach Umbenennen. Abgebrochene Übertragungen
  hinterlassen keine halben Dateien unter dem echten Namen.
- **Änderungserkennung** über Größe und Änderungszeit; die Änderungszeit wird auf dem NAS gesetzt.
  Ignoriert ein Server die Zeit, wird trotzdem nicht jedes Mal alles neu kopiert.
- **Dateinamen:** Unter SMB ungültige Zeichen werden ersetzt, reservierte Namen (CON, NUL, …)
  maskiert, Unicode normalisiert (NFC), Namen, die sich nur in Groß-/Kleinschreibung unterscheiden,
  erhalten „ (2)“.
- **Robustheit:** Fehler einzelner Dateien stoppen den Lauf nicht; nach 5 Fehlern in Folge
  (z. B. Verbindung weg) wird abgebrochen. Der nächste Lauf setzt automatisch fort.
- **Hintergrund:** Exakter Alarm (`USE_EXACT_ALARM`, bei Installation außerhalb des Play Stores
  automatisch gewährt) → WorkManager-Job als Vordergrunddienst (`dataSync`). Alarme werden nach
  Neustart, App-Update und Zeitzonenwechsel neu gesetzt.
- **WLAN-Name:** Android liefert die SSID nur mit Standortberechtigung „Immer erlauben“ und
  eingeschaltetem Standortdienst. Die App prüft das und zeigt eine verständliche Meldung.
- **Akku:** Ausnahme von der Akku-Optimierung wird angefragt, sonst darf der Job nicht als
  Vordergrunddienst laufen.
- **Diagnose:** „Verbindung testen“ prüft WLAN, Erreichbarkeit, Anmeldung, Protokollversion,
  Verschlüsselung, Schreibrecht und freien Speicher.

## Bewusst nicht umgesetzt (mögliche Erweiterungen)

- Wake-on-LAN (das NAS fährt per Zeitplan selbst hoch).
- Mehrere NAS/Zeitpläne, Ausschlussmuster außer „versteckte Dateien“.
- Prüfsummenvergleich (langsam; Größe + Zeit reicht für Fotos/Dokumente).
- Export/Import der Einstellungen.
