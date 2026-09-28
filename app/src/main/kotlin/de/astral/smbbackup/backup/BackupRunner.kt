package de.astral.smbbackup.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.net.toUri
import de.astral.smbbackup.app
import de.astral.smbbackup.core.CopyEngine
import de.astral.smbbackup.core.CopyOptions
import de.astral.smbbackup.core.CopyProgressListener
import de.astral.smbbackup.core.CopyStats
import de.astral.smbbackup.core.SmbBackupSession
import de.astral.smbbackup.core.SmbServerConfig
import de.astral.smbbackup.core.describeError
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

    private val latestStats = AtomicReference<CopyStats?>(null)

    suspend fun run(trigger: Trigger, deadline: Long, onProgress: suspend (String) -> Unit): Outcome {
        val log = context.app.runLog
        log.markInterrupted()
        val runId = log.start(trigger)
        var outcome = Outcome(RunStatus.FAILED, "Unbekannter Fehler")
        try {
            outcome = execute(deadline, onProgress)
        } catch (e: CancellationException) {
            outcome = Outcome(RunStatus.CANCELLED, "Abgebrochen", latestStats.get())
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

    private suspend fun execute(deadline: Long, onProgress: suspend (String) -> Unit): Outcome {
        val settings = context.app.settings.load()
        val password = context.app.credentials.loadPassword()
        val missing = settings.missingFields() + listOfNotNull(if (password == null) "Passwort" else null)
        if (missing.isNotEmpty()) {
            return Outcome(RunStatus.FAILED, "Einstellungen unvollständig: ${missing.joinToString()}")
        }

        val gate = NetworkGate(context)
        var result = gate.check(settings)
        while (result !is GateResult.Ready && System.currentTimeMillis() < deadline) {
            val reason = (result as? GateResult.NotHome)?.reason ?: (result as GateResult.Problem).reason
            onProgress("Warte: $reason")
            delay(30_000)
            result = gate.check(settings)
        }
        val ready = when (result) {
            is GateResult.NotHome -> return Outcome(RunStatus.SKIPPED, result.reason)
            is GateResult.Problem -> return Outcome(RunStatus.FAILED, result.reason)
            is GateResult.Ready -> result
        }

        onProgress("Verbinde mit ${settings.host} …")
        val stats = copyAll(settings, password!!, ready.network, onProgress)
        return classify(stats)
    }

    private suspend fun copyAll(
        settings: AppSettings,
        password: String,
        network: Network,
        onProgress: suspend (String) -> Unit,
    ): CopyStats = coroutineScope {
        val job = coroutineContext.job
        val progressText = AtomicReference<String?>(null)
        val ticker = launch {
            while (isActive) {
                delay(1_000)
                progressText.getAndSet(null)?.let { onProgress(it) }
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
                SmbBackupSession.open(config, network.socketFactory).use { session ->
                    var total = CopyStats()
                    for (folder in settings.folders.filter { it.enabled }) {
                        if (!job.isActive) break
                        val listener = CopyProgressListener { s, file ->
                            latestStats.set(total + s)
                            progressText.set("${folder.name}: ${s.checked} geprüft, ${s.uploaded} kopiert" + (file?.let { "\n$it" } ?: ""))
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
