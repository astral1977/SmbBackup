package de.astral.smbbackup.core

import kotlin.math.max

enum class Decision {
    UPLOAD_NEW,
    UPLOAD_CHANGED,
    SKIP_UNCHANGED,
    SKIP_CHANGED,
    TARGET_IS_DIRECTORY,
}

fun interface CopyProgressListener {
    fun onProgress(stats: CopyStats, progress: CopyProgress)
}

/**
 * Kopiert Dateien einer [LocalSource] in einen Ordner des [RemoteTarget].
 * Es wird nie etwas auf dem Ziel gelöscht ("nur kopieren").
 *
 * Erst werden alle Dateien geprüft, dann kopiert. So steht beim Kopieren fest,
 * wie viele Dateien und welche Datenmenge noch kommen.
 */
class CopyEngine(
    private val target: RemoteTarget,
    private val options: CopyOptions = CopyOptions(),
    private val listener: CopyProgressListener? = null,
) {
    companion object {
        fun decide(size: Long, lastModifiedMillis: Long, existing: RemoteEntry?, options: CopyOptions): Decision {
            if (existing == null) return Decision.UPLOAD_NEW
            if (existing.isDirectory) return Decision.TARGET_IS_DIRECTORY
            // Gleiche Größe und das NAS ist nicht älter als die lokale Datei: aktuell.
            // ">=" statt "==", damit auch Server funktionieren, die die gesetzte Änderungszeit ignorieren.
            val upToDate = existing.size == size &&
                existing.lastModifiedMillis + options.mtimeToleranceMillis >= lastModifiedMillis
            return when {
                upToDate -> Decision.SKIP_UNCHANGED
                options.changedFilePolicy == ChangedFilePolicy.SKIP -> Decision.SKIP_CHANGED
                else -> Decision.UPLOAD_CHANGED
            }
        }

        fun joinPath(vararg parts: String): String =
            parts.flatMap { it.split('/', '\\') }.filter { it.isNotBlank() }.joinToString("/")
    }

    private class DirState(val exists: Boolean, entries: List<RemoteEntry>) {
        val entries: MutableMap<String, RemoteEntry> = entries.associateByTo(HashMap()) { it.name.lowercase() }

        /** Welcher lokale Name in diesem Lauf welchen (kleingeschriebenen) Zielnamen belegt. */
        val claimed = HashMap<String, String>()
        var created = exists
    }

    private class Planned(val file: LocalFile, val remoteDir: String, val remoteName: String, val state: DirState)

    private var checked = 0
    private var uploaded = 0
    private var skipped = 0
    private var bytes = 0L
    private val failures = mutableListOf<FileFailure>()
    private var consecutiveFailures = 0

    fun copy(source: LocalSource, remoteRoot: String, isCancelled: () -> Boolean = { false }): CopyStats {
        checked = 0; uploaded = 0; skipped = 0; bytes = 0; failures.clear(); consecutiveFailures = 0

        // 1. Prüfen: lokale Dateien mit dem NAS vergleichen.
        val dirs = HashMap<String, DirState>()
        val planned = mutableListOf<Planned>()
        val iterator = source.files().iterator()
        while (true) {
            if (isCancelled()) return stats(cancelled = true)

            val file = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (e: Exception) {
                return stats(abortReason = "Quellordner \"${source.label}\" nicht lesbar: ${describeError(e)}")
            }

            checked++
            report(CopyProgress(CopyProgress.Phase.CHECKING, checked, currentFile = file.displayPath))
            try {
                val remoteDir = joinPath(remoteRoot, *file.relativePath.dropLast(1).map(NameSanitizer::sanitize).toTypedArray())
                val state = dirs.getOrPut(remoteDir) {
                    val listing = target.list(remoteDir)
                    DirState(listing != null, listing.orEmpty())
                }
                val remoteName = claimName(state, file.relativePath.last())
                when (decide(file.size, file.lastModifiedMillis, state.entries[remoteName.lowercase()], options)) {
                    Decision.UPLOAD_NEW, Decision.UPLOAD_CHANGED -> planned += Planned(file, remoteDir, remoteName, state)
                    Decision.SKIP_UNCHANGED, Decision.SKIP_CHANGED -> skipped++
                    Decision.TARGET_IS_DIRECTORY ->
                        throw SmbBackupException("Auf dem NAS existiert ein Ordner mit diesem Namen")
                }
                consecutiveFailures = 0
            } catch (e: Exception) {
                fail(source, file, e)?.let { return it }
            }
        }

        // 2. Kopieren.
        val totalBytes = planned.sumOf { it.file.size }
        // Fehlgeschlagene Dateien zählen beim Fortschritt als erledigt.
        var processed = 0
        var processedBytes = 0L
        fun progress(currentBytes: Long, file: String?) = CopyProgress(
            CopyProgress.Phase.UPLOADING, checked,
            filesToUpload = planned.size, filesUploaded = processed,
            bytesToUpload = totalBytes, bytesUploaded = processedBytes + currentBytes, currentFile = file,
        )
        for (item in planned) {
            if (isCancelled()) return stats(cancelled = true)
            val file = item.file
            report(progress(0, file.displayPath))
            try {
                if (!item.state.created) {
                    target.ensureDirectory(item.remoteDir)
                    item.state.created = true
                }
                target.upload(item.remoteDir, item.remoteName, file) { done ->
                    report(progress(done.coerceAtMost(file.size), file.displayPath))
                }
                item.state.entries[item.remoteName.lowercase()] =
                    RemoteEntry(item.remoteName, file.size, max(file.lastModifiedMillis, 0), false)
                uploaded++
                bytes += file.size
                consecutiveFailures = 0
            } catch (e: Exception) {
                fail(source, file, e)?.let { return it }
            } finally {
                processed++
                processedBytes += file.size
            }
        }
        return stats().also {
            listener?.onProgress(it, CopyProgress(CopyProgress.Phase.DONE, checked, planned.size, uploaded, totalBytes, bytes))
        }
    }

    private fun report(progress: CopyProgress) {
        listener?.onProgress(stats(), progress)
    }

    /** Merkt den Fehler; liefert das Endergebnis, wenn wegen zu vieler Fehler in Folge abgebrochen wird. */
    private fun fail(source: LocalSource, file: LocalFile, e: Exception): CopyStats? {
        failures += FileFailure("${source.label}/${file.displayPath}", describeError(e))
        consecutiveFailures++
        return if (consecutiveFailures >= options.maxConsecutiveFailures) {
            stats(abortReason = "Abbruch nach $consecutiveFailures Fehlern in Folge: ${describeError(e)}")
        } else {
            null
        }
    }

    private fun claimName(state: DirState, localName: String): String {
        val base = NameSanitizer.sanitize(localName)
        var candidate = base
        var n = 2
        while (true) {
            val owner = state.claimed[candidate.lowercase()]
            if (owner == null || owner == localName) break
            candidate = NameSanitizer.withSuffix(base, n++)
        }
        state.claimed[candidate.lowercase()] = localName
        return candidate
    }

    private fun stats(cancelled: Boolean = false, abortReason: String? = null) = CopyStats(
        checked = checked,
        uploaded = uploaded,
        skipped = skipped,
        failed = failures.size,
        bytesUploaded = bytes,
        failures = failures.toList(),
        cancelled = cancelled,
        abortReason = abortReason,
    )
}
