package de.astral.smbbackup.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.io.InputStreamByteChunkProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class ConnectionInfo(
    val dialect: String,
    val encrypted: Boolean,
    val signingRequired: Boolean,
    val serverName: String?,
)

/** Eine angemeldete Verbindung zu einer Datei-Freigabe (SMB 2/3). */
class SmbBackupSession private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    val info: ConnectionInfo,
) : RemoteTarget, Closeable {

    companion object {
        /**
         * Baut Verbindung auf und meldet sich an.
         * @param socketFactory z. B. die SocketFactory des WLAN-Netzes, damit nie über Mobilfunk verbunden wird.
         */
        fun open(config: SmbServerConfig, socketFactory: SocketFactory? = null): SmbBackupSession {
            val smbConfig = SmbConfig.builder()
                // SMB1 wird von smbj ohnehin nicht unterstützt; DSM 6 kann SMB 2 und 3.
                .withDialects(
                    SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2,
                )
                .withEncryptData(true)
                .withSigningRequired(true)
                .withDfsEnabled(false)
                // BouncyCastle direkt nutzen: Die Android-JCE bietet nicht alle SMB3-Algorithmen.
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
                .withSoTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
                .apply { if (socketFactory != null) withSocketFactory(socketFactory) }
                .build()

            val client = SMBClient(smbConfig)
            var connection: Connection? = null
            try {
                connection = client.connect(config.host, config.port)
                val auth = AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
                val session = connection.authenticate(auth)
                val share = session.connectShare(config.share) as? DiskShare
                    ?: throw SmbBackupException("\"${config.share}\" ist keine Datei-Freigabe")
                val encrypted = session.shouldEncryptData()
                val info = ConnectionInfo(
                    dialect = connection.negotiatedProtocol.dialect.name,
                    encrypted = encrypted,
                    signingRequired = session.isSigningRequired,
                    serverName = connection.connectionContext.serverName,
                )
                if (config.requireEncryption && !encrypted) {
                    runCatching { share.close() }
                    runCatching { session.close() }
                    throw SmbBackupException(
                        "Server hat keine SMB3-Verschlüsselung ausgehandelt (Protokoll ${info.dialect}). " +
                            "Im DSM unter Dateidienste > SMB > Erweitert das maximale Protokoll auf SMB3 stellen " +
                            "oder in der App \"Verschlüsselung erzwingen\" abschalten.",
                    )
                }
                return SmbBackupSession(client, connection, session, share, info)
            } catch (e: Exception) {
                runCatching { connection?.close() }
                runCatching { client.close() }
                throw e
            }
        }

        private fun smbPath(path: String) = CopyEngine.joinPath(path).replace('/', '\\')

        private fun isNotFound(e: SMBApiException) = e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
            e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND || e.status == NtStatus.STATUS_NO_SUCH_FILE
    }

    override fun list(dir: String): List<RemoteEntry>? = try {
        share.list(smbPath(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                RemoteEntry(
                    name = it.fileName,
                    size = it.endOfFile,
                    lastModifiedMillis = it.lastWriteTime.toEpochMillis(),
                    isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                )
            }
    } catch (e: SMBApiException) {
        if (isNotFound(e)) null else throw e
    }

    override fun ensureDirectory(dir: String) {
        var current = ""
        for (segment in CopyEngine.joinPath(dir).split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) segment else "$current\\$segment"
            if (!share.folderExists(current)) {
                try {
                    share.mkdir(current)
                } catch (e: SMBApiException) {
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
                }
            }
        }
    }

    override fun upload(dir: String, name: String, file: LocalFile, onBytes: (Long) -> Unit) {
        val finalPath = smbPath(CopyEngine.joinPath(dir, name))
        val tempPath = smbPath(CopyEngine.joinPath(dir, ".$name.smbbackup-part"))
        val remote = share.openFile(
            tempPath,
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.noneOf(SMB2ShareAccess::class.java),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        remote.use { handle ->
            var done = false
            try {
                file.open().use { input ->
                    handle.write(InputStreamByteChunkProvider(input)) { _, total -> onBytes(total) }
                }
                handle.setFileInformation(
                    FileBasicInformation(
                        FileBasicInformation.DONT_SET,
                        FileBasicInformation.DONT_SET,
                        FileTime.ofEpochMillis(file.lastModifiedMillis),
                        FileBasicInformation.DONT_SET,
                        0,
                    ),
                )
                handle.rename(finalPath, true)
                done = true
            } finally {
                if (!done) runCatching { handle.deleteOnClose() }
            }
        }
    }

    /** Legt [dir] an und prüft mit einer sofort wieder gelöschten Datei, ob geschrieben werden darf. */
    fun verifyWritable(dir: String) {
        ensureDirectory(dir)
        share.openFile(
            smbPath(CopyEngine.joinPath(dir, ".smbbackup-schreibtest")),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.noneOf(SMB2ShareAccess::class.java),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_DELETE_ON_CLOSE),
        ).use { it.write(byteArrayOf(1), 0) }
    }

    /** Freier Speicher der Freigabe in Bytes oder `null`, falls nicht ermittelbar. */
    fun freeSpaceBytes(): Long? = runCatching { share.shareInformation.freeSpace }.getOrNull()

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
