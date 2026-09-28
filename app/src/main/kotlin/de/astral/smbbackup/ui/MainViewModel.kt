package de.astral.smbbackup.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.astral.smbbackup.app
import de.astral.smbbackup.backup.BackupWorker
import de.astral.smbbackup.backup.NetworkGate
import de.astral.smbbackup.core.CopyEngine
import de.astral.smbbackup.core.HostProbe
import de.astral.smbbackup.core.NameSanitizer
import de.astral.smbbackup.core.SmbBackupSession
import de.astral.smbbackup.core.SmbServerConfig
import de.astral.smbbackup.core.describeError
import de.astral.smbbackup.data.AppSettings
import de.astral.smbbackup.data.FolderConfig
import de.astral.smbbackup.data.RunRecord
import de.astral.smbbackup.data.Trigger
import de.astral.smbbackup.schedule.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    var settings by mutableStateOf(context.app.settings.load())
        private set
    var hasPassword by mutableStateOf(context.app.credentials.hasPassword())
        private set
    var runs by mutableStateOf<List<RunRecord>>(emptyList())
        private set
    var nextRunAt by mutableStateOf(context.app.settings.nextRunAt)
        private set
    var testResult by mutableStateOf<String?>(null)
        private set
    var testing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Wird bei jedem onResume erhöht, damit Berechtigungsstatus neu geprüft wird. */
    var resumeTick by mutableIntStateOf(0)
        private set

    /** Laufender oder zuletzt beendeter Sicherungsjob. */
    val work: StateFlow<WorkInfo?> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refreshLog()
        viewModelScope.launch {
            var wasRunning = false
            work.collect { info ->
                val running = info != null && !info.state.isFinished
                if (wasRunning && !running) refreshLog()
                if (running) refreshLog()
                wasRunning = running
            }
        }
    }

    fun onResume() {
        resumeTick++
        nextRunAt = AlarmScheduler.reschedule(context)?.toInstant()?.toEpochMilli() ?: 0L
        refreshLog()
    }

    fun messageShown() {
        message = null
    }

    fun refreshLog() {
        viewModelScope.launch {
            runs = withContext(Dispatchers.IO) { context.app.runLog.recent() }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { context.app.runLog.clear() }
            refreshLog()
        }
    }

    fun save(draft: AppSettings, newPassword: String) {
        // Berechtigungen für entfernte Ordner zurückgeben.
        settings.folders.filter { old -> draft.folders.none { it.uri == old.uri } }.forEach {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(it.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.app.settings.save(draft)
        if (newPassword.isNotEmpty()) context.app.credentials.savePassword(newPassword)
        settings = context.app.settings.load()
        hasPassword = context.app.credentials.hasPassword()
        nextRunAt = AlarmScheduler.reschedule(context)?.toInstant()?.toEpochMilli() ?: 0L
        message = "Einstellungen gespeichert"
    }

    /** Übernimmt die dauerhafte Leseberechtigung für einen gewählten Ordner. */
    fun folderFromPicker(uri: Uri): FolderConfig {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val name = context.contentResolver.query(
            documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Ordner"
        return FolderConfig(uri = uri.toString(), name = name, remoteFolder = NameSanitizer.sanitize(name))
    }

    fun backupNow() {
        BackupWorker.enqueue(context, Trigger.MANUAL)
        message = "Sicherung gestartet"
    }

    fun cancelBackup() = BackupWorker.cancel(context)

    suspend fun currentSsid(): String? = NetworkGate(context).currentWifi()?.ssid

    /** Prüft Schritt für Schritt mit den (evtl. noch nicht gespeicherten) Eingaben. */
    fun testConnection(draft: AppSettings, draftPassword: String) {
        if (testing) return
        testing = true
        testResult = null
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            try {
                val wifi = NetworkGate(context).currentWifi()
                when {
                    wifi == null -> lines += "✗ Nicht mit einem WLAN verbunden"
                    wifi.ssid == null -> lines += "⚠ WLAN verbunden, Name nicht lesbar (Standortberechtigung?)"
                    wifi.ssid in draft.homeSsids -> lines += "✓ Im Heim-WLAN \"${wifi.ssid}\""
                    else -> lines += "⚠ WLAN \"${wifi.ssid}\" ist nicht als Heim-WLAN eingetragen"
                }
                val socketFactory = wifi?.network?.socketFactory ?: javax.net.SocketFactory.getDefault()
                val password = draftPassword.ifEmpty { context.app.credentials.loadPassword().orEmpty() }
                withContext(Dispatchers.IO) {
                    if (!HostProbe.isReachable(draft.host.trim(), draft.port, socketFactory = socketFactory)) {
                        lines += "✗ ${draft.host}:${draft.port} nicht erreichbar"
                        return@withContext
                    }
                    lines += "✓ ${draft.host}:${draft.port} erreichbar"
                    val config = SmbServerConfig(
                        host = draft.host.trim(),
                        port = draft.port,
                        share = draft.share.trim().trim('/', '\\'),
                        username = draft.username.trim(),
                        password = password,
                        domain = draft.domain.trim(),
                        requireEncryption = draft.requireEncryption,
                    )
                    SmbBackupSession.open(config, socketFactory).use { session ->
                        val info = session.info
                        lines += "✓ Angemeldet (${info.dialect.replace('_', '.')}, " +
                            (if (info.encrypted) "verschlüsselt" else "UNVERSCHLÜSSELT") +
                            (if (info.signingRequired) ", signiert" else "") + ")"
                        val target = CopyEngine.joinPath(draft.basePath)
                        session.verifyWritable(target)
                        lines += "✓ Schreibtest in \\\\${draft.host}\\${config.share}\\${target.replace('/', '\\')} erfolgreich"
                        session.freeSpaceBytes()?.let { lines += "Freier Speicher: ${formatBytes(it)}" }
                    }
                }
            } catch (e: Exception) {
                lines += "✗ ${describeError(e)}"
            } finally {
                testResult = lines.joinToString("\n")
                testing = false
            }
        }
    }
}
