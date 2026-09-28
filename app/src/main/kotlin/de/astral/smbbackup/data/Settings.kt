package de.astral.smbbackup.data

import android.content.Context
import androidx.core.content.edit
import de.astral.smbbackup.core.ChangedFilePolicy
import de.astral.smbbackup.core.Schedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

data class FolderConfig(
    /** Per Ordnerauswahl (SAF) freigegebener Ordner. */
    val uri: String,
    val name: String,
    /** Unterordner auf dem NAS, relativ zum Zielordner. */
    val remoteFolder: String,
    val enabled: Boolean = true,
)

data class AppSettings(
    val host: String = "",
    val port: Int = 445,
    val share: String = "",
    val basePath: String = "Handy-Backup",
    val username: String = "",
    val domain: String = "",
    val requireEncryption: Boolean = true,
    val homeSsids: List<String> = emptyList(),
    val scheduleEnabled: Boolean = false,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val hour: Int = 20,
    val minute: Int = 0,
    /** So lange wird nach dem geplanten Zeitpunkt auf WLAN und hochfahrendes NAS gewartet. */
    val waitMinutes: Int = 15,
    val folders: List<FolderConfig> = emptyList(),
    val overwriteChanged: Boolean = true,
    val skipHidden: Boolean = true,
    val notifySuccess: Boolean = false,
    /** Warnung, wenn so viele Tage kein erfolgreiches Backup lief (0 = aus). */
    val staleWarningDays: Int = 3,
) {
    val schedule: Schedule get() = Schedule(days, LocalTime.of(hour, minute))
    val changedFilePolicy: ChangedFilePolicy
        get() = if (overwriteChanged) ChangedFilePolicy.OVERWRITE else ChangedFilePolicy.SKIP

    /** Fehlende Pflichtangaben (ohne Passwort) als lesbare Liste. */
    fun missingFields(): List<String> = buildList {
        if (host.isBlank()) add("NAS-Adresse")
        if (share.isBlank()) add("Freigabe")
        if (username.isBlank()) add("Benutzername")
        if (homeSsids.isEmpty()) add("Heim-WLAN")
        if (folders.none { it.enabled }) add("Ordner")
    }
}

/** Nicht geheime Einstellungen; das Passwort liegt im [CredentialStore]. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            host = prefs.getString("host", d.host)!!,
            port = prefs.getInt("port", d.port),
            share = prefs.getString("share", d.share)!!,
            basePath = prefs.getString("basePath", d.basePath)!!,
            username = prefs.getString("username", d.username)!!,
            domain = prefs.getString("domain", d.domain)!!,
            requireEncryption = prefs.getBoolean("requireEncryption", d.requireEncryption),
            homeSsids = prefs.getString("homeSsids", null)?.let { json ->
                JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }
            } ?: d.homeSsids,
            scheduleEnabled = prefs.getBoolean("scheduleEnabled", d.scheduleEnabled),
            days = prefs.getString("days", null)?.split(',')?.filter { it.isNotBlank() }
                ?.map { DayOfWeek.valueOf(it) }?.toSet() ?: d.days,
            hour = prefs.getInt("hour", d.hour),
            minute = prefs.getInt("minute", d.minute),
            waitMinutes = prefs.getInt("waitMinutes", d.waitMinutes),
            folders = prefs.getString("folders", null)?.let(::parseFolders) ?: d.folders,
            overwriteChanged = prefs.getBoolean("overwriteChanged", d.overwriteChanged),
            skipHidden = prefs.getBoolean("skipHidden", d.skipHidden),
            notifySuccess = prefs.getBoolean("notifySuccess", d.notifySuccess),
            staleWarningDays = prefs.getInt("staleWarningDays", d.staleWarningDays),
        )
    }

    fun save(s: AppSettings) = prefs.edit {
        putString("host", s.host.trim())
        putInt("port", s.port)
        putString("share", s.share.trim().trim('/', '\\'))
        putString("basePath", s.basePath.trim())
        putString("username", s.username.trim())
        putString("domain", s.domain.trim())
        putBoolean("requireEncryption", s.requireEncryption)
        putString("homeSsids", JSONArray(s.homeSsids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()).toString())
        putBoolean("scheduleEnabled", s.scheduleEnabled)
        putString("days", s.days.sorted().joinToString(",") { it.name })
        putInt("hour", s.hour)
        putInt("minute", s.minute)
        putInt("waitMinutes", s.waitMinutes)
        putString("folders", foldersToJson(s.folders))
        putBoolean("overwriteChanged", s.overwriteChanged)
        putBoolean("skipHidden", s.skipHidden)
        putBoolean("notifySuccess", s.notifySuccess)
        putInt("staleWarningDays", s.staleWarningDays)
    }

    var nextRunAt: Long
        get() = prefs.getLong("nextRunAt", 0)
        set(value) = prefs.edit { putLong("nextRunAt", value) }

    private fun parseFolders(json: String): List<FolderConfig> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            FolderConfig(o.getString("uri"), o.getString("name"), o.getString("remoteFolder"), o.optBoolean("enabled", true))
        }
    }

    private fun foldersToJson(folders: List<FolderConfig>) = JSONArray(
        folders.map {
            JSONObject().put("uri", it.uri).put("name", it.name).put("remoteFolder", it.remoteFolder.trim())
                .put("enabled", it.enabled)
        },
    ).toString()
}
