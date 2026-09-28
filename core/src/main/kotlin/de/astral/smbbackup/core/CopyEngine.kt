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
    fun onProgress(stats: CopyStats, currentFile: String?)
}

/**
 * Kopiert Dateien einer [LocalSource] in einen Ordner des [RemoteTarget].
 * Es wird nie etwas auf dem Ziel gelöscht ("nur kopieren").
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

    private var checked = 0
    private var uploaded = 0
    private var skipped = 0
    private var bytes = 0L
    private val failures = mutableListOf<FileFailure>()

    fun copy(source: LocalSource, remoteRoot: String, isCancelled: () -> Boolean = { false }): CopyStats {
        checked = 0; uploaded = 0; skipped = 0; bytes = 0; failures.clear()
        val dirs = HashMap<String, DirState>()
        var consecutiveFailures = 0
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
            listener?.onProgress(stats(), file.displayPath)
            try {
                val remoteDir = joinPath(remoteRoot, *file.relativePath.dropLast(1).map(NameSanitizer::sanitize).toTypedArray())
                val state = dirs.getOrPut(remoteDir) {
                    val listing = target.list(remoteDir)
                    DirState(listing != null, listing.orEmpty())
                }
                val remoteName = claimName(state, file.relativePath.last())
                when (decide(file.size, file.lastModifiedMillis, state.entries[remoteName.lowercase()], options)) {
                    Decision.UPLOAD_NEW, Decision.UPLOAD_CHANGED -> {
                        if (!state.created) {
                            target.ensureDirectory(remoteDir)
                            state.created = true
                        }
                        target.upload(remoteDir, remoteName, file)
                        state.entries[remoteName.lowercase()] =
                            RemoteEntry(remoteName, file.size, max(file.lastModifiedMillis, 0), false)
                        uploaded++
                        bytes += file.size
                    }
                    Decision.SKIP_UNCHANGED, Decision.SKIP_CHANGED -> skipped++
                    Decision.TARGET_IS_DIRECTORY ->
                        throw SmbBackupException("Auf dem NAS existiert ein Ordner mit diesem Namen")
                }
                consecutiveFailures = 0
            } catch (e: Exception) {
                failures += FileFailure("${source.label}/${file.displayPath}", describeError(e))
                consecutiveFailures++
                if (consecutiveFailures >= options.maxConsecutiveFailures) {
                    return stats(abortReason = "Abbruch nach $consecutiveFailures Fehlern in Folge: ${describeError(e)}")
                }
            }
        }
        return stats().also { listener?.onProgress(it, null) }
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
