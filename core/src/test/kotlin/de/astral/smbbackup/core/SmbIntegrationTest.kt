package de.astral.smbbackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Läuft nur, wenn ein SMB-Server konfiguriert ist, z. B.:
 * SMBBACKUP_TEST_HOST=127.0.0.1 SMBBACKUP_TEST_SHARE=backup SMBBACKUP_TEST_USER=u SMBBACKUP_TEST_PASSWORD=p
 */
class SmbIntegrationTest {
    private val host = System.getenv("SMBBACKUP_TEST_HOST")

    private fun config(password: String = System.getenv("SMBBACKUP_TEST_PASSWORD").orEmpty()) = SmbServerConfig(
        host = host,
        port = System.getenv("SMBBACKUP_TEST_PORT")?.toInt() ?: 445,
        share = System.getenv("SMBBACKUP_TEST_SHARE") ?: "backup",
        username = System.getenv("SMBBACKUP_TEST_USER").orEmpty(),
        password = password,
    )

    @Test
    fun copiesTreeWithEncryptionAndPreservesTimestamps() {
        assumeTrue("SMBBACKUP_TEST_HOST nicht gesetzt", host != null)
        val c = config()
        assertTrue(HostProbe.isReachable(c.host, c.port))

        val dir = Files.createTempDirectory("smbbackup").toFile()
        File(dir, "Camera").mkdirs()
        val big = File(dir, "Camera/video.mp4").apply { writeBytes(ByteArray(3_000_000) { (it % 251).toByte() }) }
        big.setLastModified(1_700_000_000_000)
        File(dir, "notiz.txt").apply { writeText("hallo") }.setLastModified(1_600_000_000_000)
        File(dir, "Ungültig?.txt").writeText("x")
        File(dir, ".versteckt").writeText("nicht kopieren")
        val remoteRoot = "test-${System.currentTimeMillis()}/DCIM"

        SmbBackupSession.open(c).use { session ->
            println("Verbindung: ${session.info}, frei: ${session.freeSpaceBytes()}")
            assertTrue("SMB3-Verschlüsselung erwartet", session.info.encrypted)
            session.verifyWritable(remoteRoot)
            assertEquals(emptyList<RemoteEntry>(), session.list(remoteRoot))

            val first = CopyEngine(session).copy(FileSystemSource(dir), remoteRoot)
            assertEquals(first.toString(), 3, first.uploaded)
            assertEquals(0, first.failed)

            val entries = session.list(remoteRoot)!!.associateBy { it.name }
            assertEquals(setOf("Camera", "notiz.txt", "Ungültig_.txt"), entries.keys)
            assertEquals(1_600_000_000_000, entries.getValue("notiz.txt").lastModifiedMillis)
            val video = session.list("$remoteRoot/Camera")!!.single()
            assertEquals(3_000_000L, video.size)
            assertEquals(1_700_000_000_000, video.lastModifiedMillis)

            val second = CopyEngine(session).copy(FileSystemSource(dir), remoteRoot)
            assertEquals(second.toString(), 0, second.uploaded)
            assertEquals(3, second.skipped)

            File(dir, "notiz.txt").apply { writeText("hallo welt") }.setLastModified(1_650_000_000_000)
            val third = CopyEngine(session).copy(FileSystemSource(dir), remoteRoot)
            assertEquals(1, third.uploaded)
            assertEquals(10L, session.list(remoteRoot)!!.single { it.name == "notiz.txt" }.size)
        }
    }

    @Test
    fun wrongPasswordGivesReadableError() {
        assumeTrue("SMBBACKUP_TEST_HOST nicht gesetzt", host != null)
        val error = runCatching { SmbBackupSession.open(config(password = "falsch")) }.exceptionOrNull()
        assertEquals("Anmeldung fehlgeschlagen: Benutzername oder Passwort falsch", describeError(error!!))
    }
}
