package de.astral.smbbackup.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.net.toUri
import de.astral.smbbackup.app
import de.astral.smbbackup.core.CopyEngine
import de.astral.smbbackup.core.CopyOptions
import de.astral.smbbackup.core.CopyProgress
import de.astral.smbbackup.core.CopyProgressListener
import de.astral.smbbackup.core.CopyStats
import de.astral.smbbackup.core.SmbBackupSession
import de.astral.smbbackup.core.SmbServerConfig
import de.astral.smbbackup.core.describeError
import de.astral.smbbackup.core.isTransientConnectionError
import de.astral.smbbackup.data.AppSettings
import de.astral.smbbackup.data.RunStatus
import de.astral.smbbackup.data.Trigger
import de.astral.smbbackup.notify.Notifier
import de.astral.smbbackup.ui.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Ein kompletter Sicherungslauf: Vorbedingungen prüfen, kopieren, protokollieren, benachrichtigen. */
class BackupRunner(private val context: Context) {

    data class Outcome(val status: RunStatus, val message: String, val stats: CopyStats? = null)

    /** Text für die Anzeige; [fraction] (0..1) nur, wenn der Fortschritt bekannt ist. */
    data class Progress(val text: String, val fraction: Float? = null)

    /** Verbindungsaufbau zum NAS fehlgeschlagen (im Unterschied zu Fehlern beim Kopieren). */
    private class ConnectFailed(cause: Exception) : Exception(cause)

    private val latestStats = AtomicReference<CopyStats?>(null)

    /**
     * @param deadline bis wann auf Heim-WLAN und NAS gewartet wird
     * @param stopReason Text, warum der Lauf beendet wurde, falls er abgebrochen wird
     */
    suspend fun run(
        trigger: Trigger,
        deadline: Long,
        stopReason: () -> String? = { null },
        onProgress: suspend (Progress) -> Unit,
    ): Outcome {
        val log = context.app.runLog
        log.markInterrupted()
        val runId = log.start(trigger)
        var outcome = Outcome(RunStatus.FAILED, "Unbekannter Fehler")
        try {
            outcome = execute(trigger, deadline, onProgress)
        } catch (e: CancellationException) {
            outcome = Outcome(RunStatus.CANCELLED, stopReason() ?: "Abgebrochen", latestStats.get())
            throw e
        } catch (e: Exception) {
            outcome = Outcome(RunStatus.FAILED, describeError(e), latestStats.get())
        } finally {
            withContext(NonCancellable) {
                val s = outcome.stats
                log.finish(
                    id = runId,
                    status = outcome.status,
                    message = outcome.message,
                    checked = s?.checked ?: 0,
                    uploaded = s?.uploaded ?: 0,
                    skipped = s?.skipped ?: 0,
                    failed = s?.failed ?: 0,
                    bytes = s?.bytesUploaded ?: 0,
                    details = s?.failures.orEmpty().take(200).joinToString("\n") { "${it.path}: ${it.message}" },
                )
                notify(trigger, outcome)
            }
        }
        return outcome
    }

    private suspend fun execute(trigger: Trigger, initialDeadline: Long, onProgress: suspend (Progress) -> Unit): Outcome {
        val settings = context.app.settings.load()
        val password = context.app.credentials.loadPassword()
        val missing = settings.missingFields() + listOfNotNull(if (password == null) "Passwort" else null)
        if (missing.isNotEmpty()) {
            return Outcome(RunStatus.FAILED, "Einstellungen unvollständig: ${missing.joinToString()}")
        }

        var deadline = initialDeadline
        val gate = NetworkGate(context)
        var wolNote: String? = null
        while (true) {
            val result = gate.check(settings)

            // NAS aus: per Wake-on-LAN wecken (bei jedem Versuch erneut, falls ein Paket verloren geht).
            if (result is GateResult.NasOffline && settings.wolMac.isNotBlank()) {
                val firstWake = wolNote == null
                wolNote = try {
                    WolSender(context).wake(settings.wolMac, result.network)
                    "Wake-on-LAN gesendet"
                } catch (e: Exception) {
                    describeError(e)
                }
                // Manuell gestartet: dem geweckten NAS Zeit zum Hochfahren geben.
                if (firstWake && trigger == Trigger.MANUAL) {
                    deadline = maxOf(deadline, System.currentTimeMillis() + settings.waitMinutes * 60_000L)
                }
            }

            val now = System.currentTimeMillis()
            if (result is GateResult.Ready) {
                onProgress(Progress("Verbinde mit ${settings.host} …"))
                try {
                    return classify(copyAll(settings, password!!, result.network, onProgress))
                } catch (e: ConnectFailed) {
                    val cause = e.cause as Exception
                    // Port offen, aber das NAS antwortet nicht (fährt z. B. noch hoch): später erneut versuchen.
                    if (!isTransientConnectionError(cause) || System.currentTimeMillis() >= deadline) throw cause
                    onProgress(Progress("Warte: NAS antwortet noch nicht (${describeError(cause)})"))
                }
            } else if (now >= deadline) {
                val reason = result.message.orEmpty() + (wolNote?.let { " ($it)" } ?: "")
                return if (result is GateResult.NotHome) Outcome(RunStatus.SKIPPED, reason) else Outcome(RunStatus.FAILED, reason)
            } else {
                onProgress(Progress("Warte: ${result.message}" + (wolNote?.let { "\n$it" } ?: "")))
            }
            delay(30_000)
        }
    }

    private suspend fun copyAll(
        settings: AppSettings,
        password: String,
        network: Network,
        onProgress: suspend (Progress) -> Unit,
    ): CopyStats = coroutineScope {
        val job = coroutineContext.job
        val progress = AtomicReference<Progress?>(null)
        val ticker = launch {
            while (isActive) {
                delay(1_000)
                progress.getAndSet(null)?.let { onProgress(it) }
            }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Auch DNS-Auflösung und alle Sockets über das WLAN leiten.
        cm.bindProcessToNetwork(network)
        try {
            withContext(Dispatchers.IO) {
                val config = SmbServerConfig(
                    host = settings.host,
                    port = settings.port,
                    share = settings.share,
                    username = settings.username,
                    password = password,
                    domain = settings.domain,
                    requireEncryption = settings.requireEncryption,
                )
                val session = try {
                    SmbBackupSession.open(config, network.socketFactory)
                } catch (e: Exception) {
                    throw ConnectFailed(e)
                }
                session.use {
                    var total = CopyStats()
                    val folders = settings.folders.filter { it.enabled }
                    for ((index, folder) in folders.withIndex()) {
                        if (!job.isActive) break
                        val label = if (folders.size > 1) "Ordner ${index + 1}/${folders.size} · ${folder.name}" else folder.name
                        val listener = CopyProgressListener { s, p ->
                            latestStats.set(total + s)
                            progress.set(describe(label, p))
                        }
                        val engine = CopyEngine(session, CopyOptions(changedFilePolicy = settings.changedFilePolicy), listener)
                        val source = SafSource(context.contentResolver, folder.uri.toUri(), folder.name, settings.skipHidden)
                        val stats = engine.copy(source, CopyEngine.joinPath(settings.basePath, folder.remoteFolder)) { !job.isActive }
                        total += stats.copy(abortReason = stats.abortReason?.let { "${folder.name}: $it" })
                        latestStats.set(total)
                    }
                    total
                }
            }
        } finally {
            cm.bindProcessToNetwork(null)
            ticker.cancel()
        }
    }

    private fun describe(folder: String, p: CopyProgress): Progress {
        val where = p.currentFile?.let { "$folder: $it" } ?: folder
        return when {
            p.phase == CopyProgress.Phase.CHECKING -> Progress("Prüfe Dateien … ${p.checked} geprüft\n$where")
            p.filesToUpload == 0 -> Progress("Nichts Neues (${p.checked} Dateien geprüft)\n$folder", 1f)
            else -> Progress(
                "${p.filesUploaded} von ${p.filesToUpload} Dateien · " +
                    "${formatBytes(p.bytesUploaded)} von ${formatBytes(p.bytesToUpload)}\n$where",
                p.fraction ?: 1f,
            )
        }
    }

    private fun classify(stats: CopyStats): Outcome {
        val summary = buildString {
            append("${stats.uploaded} kopiert (${formatBytes(stats.bytesUploaded)}), ${stats.skipped} unverändert")
            if (stats.failed > 0) append(", ${stats.failed} Fehler")
            stats.abortReason?.let { append(". $it") }
        }
        val status = when {
            stats.cancelled -> RunStatus.CANCELLED
            stats.failed == 0 && stats.abortReason == null -> RunStatus.SUCCESS
            stats.uploaded > 0 || stats.skipped > 0 -> RunStatus.WARNING
            else -> RunStatus.FAILED
        }
        return Outcome(status, summary, stats)
    }

    private fun notify(trigger: Trigger, outcome: Outcome) {
        val settings = context.app.settings.load()
        when (outcome.status) {
            RunStatus.FAILED -> Notifier.error(context, "Backup fehlgeschlagen", outcome.message)
            RunStatus.WARNING -> Notifier.error(context, "Backup mit Fehlern", outcome.message)
            RunStatus.SUCCESS -> if (settings.notifySuccess) Notifier.success(context, outcome.message)
            else -> Unit
        }
        if (trigger == Trigger.SCHEDULED && settings.staleWarningDays > 0) {
            val log = context.app.runLog
            val reference = log.lastSuccessAt() ?: log.oldestRunAt() ?: return
            val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - reference)
            if (days >= settings.staleWarningDays) Notifier.stale(context, days)
        }
    }
}
