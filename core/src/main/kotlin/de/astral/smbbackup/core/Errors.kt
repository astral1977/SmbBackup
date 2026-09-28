package de.astral.smbbackup.core

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Übersetzt technische Fehler in verständliche Meldungen für Protokoll und Benachrichtigung. */
fun describeError(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    chain.filterIsInstance<SmbBackupException>().firstOrNull()?.let { return it.message ?: "Fehler" }
    chain.filterIsInstance<SMBApiException>().firstOrNull()?.let { return describeStatus(it) }
    chain.forEach { cause ->
        when (cause) {
            is UnknownHostException -> return "Server-Name nicht auflösbar (${cause.message}) – besser feste IP-Adresse verwenden"
            is ConnectException, is NoRouteToHostException -> return "Server nicht erreichbar (${cause.message})"
            is SocketTimeoutException -> return "Zeitüberschreitung bei der Verbindung zum Server"
            is FileNotFoundException -> return "Lokale Datei nicht gefunden (${cause.message})"
            is SecurityException -> return "Keine Berechtigung für lokalen Ordner – Ordner in der App neu auswählen"
        }
    }
    return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

private fun describeStatus(e: SMBApiException): String = when (e.status) {
    NtStatus.STATUS_LOGON_FAILURE -> "Anmeldung fehlgeschlagen: Benutzername oder Passwort falsch"
    NtStatus.STATUS_ACCOUNT_DISABLED -> "NAS-Benutzerkonto ist deaktiviert"
    NtStatus.STATUS_PASSWORD_EXPIRED -> "Passwort des NAS-Benutzers ist abgelaufen"
    NtStatus.STATUS_BAD_NETWORK_NAME -> "Freigabe auf dem NAS nicht gefunden"
    NtStatus.STATUS_ACCESS_DENIED -> "Zugriff verweigert – hat der NAS-Benutzer Schreibrechte auf die Freigabe?"
    NtStatus.STATUS_DISK_FULL -> "Speicherplatz auf dem NAS ist voll"
    NtStatus.STATUS_OBJECT_NAME_INVALID -> "Ungültiger Datei- oder Ordnername auf dem NAS"
    NtStatus.STATUS_SHARING_VIOLATION -> "Datei auf dem NAS ist gerade von einem anderen Programm geöffnet"
    NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED ->
        "Verbindung zum NAS wurde unterbrochen"
    else -> "SMB-Fehler ${e.status} (0x%08X)".format(e.statusCode)
}
