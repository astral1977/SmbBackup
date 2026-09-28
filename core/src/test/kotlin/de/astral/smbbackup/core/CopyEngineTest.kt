package de.astral.smbbackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CopyEngineTest {

    private class MemoryFile(val content: ByteArray, val mtime: Long)

    /** Ziel im Speicher, verhält sich wie SMB: Namen ohne Beachtung der Groß-/Kleinschreibung. */
    private class MemoryTarget : RemoteTarget {
        val dirs = mutableSetOf("")
        val files = mutableMapOf<String, MemoryFile>()
        var failUploads = false
        var uploads = 0

        private fun key(path: String) = path.lowercase()

        override fun list(dir: String): List<RemoteEntry>? {
            if (key(dir) !in dirs) return null
            val prefix = if (dir.isEmpty()) "" else key(dir) + "/"
            val fileEntries = files.filterKeys { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
                .map { (k, v) -> RemoteEntry(k.removePrefix(prefix), v.content.size.toLong(), v.mtime, false) }
            val dirEntries = dirs.filter { it.startsWith(prefix) && it != key(dir) && '/' !in it.removePrefix(prefix) }
                .map { RemoteEntry(it.removePrefix(prefix), 0, 0, true) }
            return fileEntries + dirEntries
        }

        override fun ensureDirectory(dir: String) {
            var current = ""
            dir.split('/').forEach {
                current = if (current.isEmpty()) key(it) else "$current/${key(it)}"
                dirs += current
            }
        }

        override fun upload(dir: String, name: String, file: LocalFile, onBytes: (Long) -> Unit) {
            if (failUploads) throw IOException("Verbindung verloren")
            check(key(dir) in dirs) { "Ordner $dir fehlt" }
            files[key(CopyEngine.joinPath(dir, name))] = MemoryFile(file.open().readBytes(), file.lastModifiedMillis)
            uploads++
        }
    }

    private class ListSource(override val label: String, val files: List<LocalFile>) : LocalSource {
        override fun files() = files.asSequence()
    }

    private fun local(path: String, content: String, mtime: Long = 1_000_000) =
        LocalFile(path.split('/'), content.length.toLong(), mtime) { ByteArrayInputStream(content.toByteArray()) }

    @Test
    fun copiesNewFilesAndCreatesDirectories() {
        val target = MemoryTarget()
        val source = ListSource("DCIM", listOf(local("a.jpg", "aaa"), local("Camera/b.jpg", "bb")))
        val stats = CopyEngine(target).copy(source, "Handy/DCIM")
        assertEquals(2, stats.uploaded)
        assertEquals(5, stats.bytesUploaded)
        assertEquals("aaa", String(target.files.getValue("handy/dcim/a.jpg").content))
        assertEquals("bb", String(target.files.getValue("handy/dcim/camera/b.jpg").content))
    }

    @Test
    fun secondRunSkipsUnchangedFiles() {
        val target = MemoryTarget()
        val source = ListSource("Docs", listOf(local("x.pdf", "12345"), local("y.pdf", "1")))
        CopyEngine(target).copy(source, "Backup")
        val stats = CopyEngine(target).copy(source, "Backup")
        assertEquals(0, stats.uploaded)
        assertEquals(2, stats.skipped)
        assertEquals(2, target.uploads)
    }

    @Test
    fun changedFileIsOverwrittenOrSkippedDependingOnPolicy() {
        val target = MemoryTarget()
        CopyEngine(target).copy(ListSource("D", listOf(local("n.txt", "alt"))), "B")
        val changed = ListSource("D", listOf(local("n.txt", "neu!", mtime = 2_000_000)))

        val skip = CopyEngine(target, CopyOptions(changedFilePolicy = ChangedFilePolicy.SKIP)).copy(changed, "B")
        assertEquals(0, skip.uploaded)
        assertEquals("alt", String(target.files.getValue("b/n.txt").content))

        val overwrite = CopyEngine(target).copy(changed, "B")
        assertEquals(1, overwrite.uploaded)
        assertEquals("neu!", String(target.files.getValue("b/n.txt").content))
    }

    @Test
    fun neverDeletesFilesOnTarget() {
        val target = MemoryTarget()
        CopyEngine(target).copy(ListSource("D", listOf(local("weg.txt", "1"), local("bleibt.txt", "2"))), "B")
        CopyEngine(target).copy(ListSource("D", listOf(local("bleibt.txt", "2"))), "B")
        assertTrue("b/weg.txt" in target.files)
    }

    @Test
    fun serverIgnoringMtimeDoesNotCauseReuploads() {
        val target = MemoryTarget()
        target.ensureDirectory("B")
        // NAS hat die Upload-Zeit statt der Original-Zeit gespeichert (neuer als lokal)
        target.files["b/f.jpg"] = MemoryFile("abc".toByteArray(), 9_000_000)
        val stats = CopyEngine(target).copy(ListSource("D", listOf(local("f.jpg", "abc", mtime = 1_000_000))), "B")
        assertEquals(1, stats.skipped)
    }

    @Test
    fun caseCollisionsGetSuffix() {
        val target = MemoryTarget()
        val source = ListSource("D", listOf(local("Foto.JPG", "1"), local("foto.jpg", "22")))
        val first = CopyEngine(target).copy(source, "B")
        assertEquals(2, first.uploaded)
        assertNotNull(target.files["b/foto.jpg"])
        assertNotNull(target.files["b/foto (2).jpg"])
        // Folgelauf ordnet die Namen identisch zu und kopiert nichts erneut
        assertEquals(0, CopyEngine(target).copy(source, "B").uploaded)
    }

    @Test
    fun invalidNamesAreSanitized() {
        val target = MemoryTarget()
        CopyEngine(target).copy(ListSource("D", listOf(local("Ordner:1/Notiz?.txt", "x"))), "B")
        assertNotNull(target.files["b/ordner_1/notiz_.txt"])
    }

    @Test
    fun abortsAfterConsecutiveFailures() {
        val target = MemoryTarget().apply { failUploads = true }
        val source = ListSource("D", (1..20).map { local("f$it.txt", "x") })
        val stats = CopyEngine(target, CopyOptions(maxConsecutiveFailures = 3)).copy(source, "B")
        assertEquals(3, stats.failed)
        assertNotNull(stats.abortReason)
    }

    @Test
    fun unreadableSourceFileIsReportedAndOthersContinue() {
        val target = MemoryTarget()
        val broken = LocalFile(listOf("kaputt.jpg"), 3, 0) { throw IOException("Lesefehler") }
        val stats = CopyEngine(target).copy(ListSource("D", listOf(broken, local("ok.jpg", "ok"))), "B")
        assertEquals(1, stats.uploaded)
        assertEquals(1, stats.failed)
        assertEquals("D/kaputt.jpg", stats.failures.single().path)
    }

    @Test
    fun cancellationStopsEarly() {
        val target = MemoryTarget()
        var calls = 0
        val source = ListSource("D", (1..10).map { local("f$it.txt", "x") })
        val stats = CopyEngine(target).copy(source, "B") { ++calls > 3 }
        assertTrue(stats.cancelled)
        assertEquals(3, stats.uploaded)
    }
}
