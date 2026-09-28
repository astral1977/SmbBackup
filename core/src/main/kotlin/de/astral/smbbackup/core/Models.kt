package de.astral.smbbackup.core

import java.io.InputStream

/** Verbindungsdaten zur SMB-Freigabe. */
data class SmbServerConfig(
    val host: String,
    val port: Int = 445,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    /** Bricht ab, wenn der Server keine SMB3-Verschlüsselung aushandelt. */
    val requireEncryption: Boolean = true,
    val timeoutSeconds: Long = 30,
) {
    override fun toString(): String =
        "SmbServerConfig(host=$host, port=$port, share=$share, username=$username, domain=$domain, " +
            "requireEncryption=$requireEncryption)"
}

/** Was mit Dateien passiert, die auf dem NAS bereits existieren, sich lokal aber geändert haben. */
enum class ChangedFilePolicy {
    /** Neue Version überschreibt die alte auf dem NAS. */
    OVERWRITE,

    /** Vorhandene Dateien werden nie angefasst. */
    SKIP,
}

data class CopyOptions(
    val changedFilePolicy: ChangedFilePolicy = ChangedFilePolicy.OVERWRITE,
    /** Toleranz beim Zeitstempelvergleich (FAT/SMB-Rundung). */
    val mtimeToleranceMillis: Long = 2_000,
    /** Nach so vielen Fehlern in Folge wird abgebrochen (z. B. Verbindung weg). */
    val maxConsecutiveFailures: Int = 5,
)

/** Eine lokale Datei; [relativePath] enthält die Pfadsegmente relativ zum Quellordner. */
class LocalFile(
    val relativePath: List<String>,
    val size: Long,
    val lastModifiedMillis: Long,
    private val opener: () -> InputStream,
) {
    val displayPath: String get() = relativePath.joinToString("/")
    fun open(): InputStream = opener()
}

/** Quelle der zu sichernden Dateien (auf Android: per SAF freigegebener Ordner). */
interface LocalSource {
    val label: String

    /**
     * Liefert alle Dateien. Dateien desselben Ordners sollten zusammenhängend und
     * sortiert kommen, damit Namenskonflikte bei jedem Lauf gleich aufgelöst werden.
     */
    fun files(): Sequence<LocalFile>
}

data class RemoteEntry(
    val name: String,
    val size: Long,
    val lastModifiedMillis: Long,
    val isDirectory: Boolean,
)

/** Ziel der Sicherung. Pfade verwenden "/" als Trenner und sind relativ zur Freigabe. */
interface RemoteTarget {
    /** Inhalt eines Ordners oder `null`, wenn er nicht existiert. */
    fun list(dir: String): List<RemoteEntry>?

    fun ensureDirectory(dir: String)

    /** Lädt atomar hoch (temporäre Datei, danach umbenennen) und setzt die Änderungszeit. */
    fun upload(dir: String, name: String, file: LocalFile, onBytes: (Long) -> Unit = {})
}

data class FileFailure(val path: String, val message: String)

data class CopyStats(
    val checked: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytesUploaded: Long = 0,
    val failures: List<FileFailure> = emptyList(),
    val cancelled: Boolean = false,
    /** Gesetzt, wenn der Lauf vorzeitig abgebrochen wurde (z. B. Verbindungsverlust). */
    val abortReason: String? = null,
) {
    operator fun plus(other: CopyStats) = CopyStats(
        checked = checked + other.checked,
        uploaded = uploaded + other.uploaded,
        skipped = skipped + other.skipped,
        failed = failed + other.failed,
        bytesUploaded = bytesUploaded + other.bytesUploaded,
        failures = failures + other.failures,
        cancelled = cancelled || other.cancelled,
        abortReason = abortReason ?: other.abortReason,
    )
}

class SmbBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
