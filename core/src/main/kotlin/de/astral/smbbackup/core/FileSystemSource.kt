package de.astral.smbbackup.core

import java.io.File

/** [LocalSource] für einen normalen Dateisystem-Ordner (Tests, Desktop). */
class FileSystemSource(
    private val root: File,
    private val skipHidden: Boolean = true,
) : LocalSource {
    override val label: String = root.name

    override fun files(): Sequence<LocalFile> = sequence {
        suspend fun SequenceScope<LocalFile>.walk(dir: File, prefix: List<String>) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (skipHidden && child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child, prefix + child.name)
                } else if (child.isFile) {
                    yield(LocalFile(prefix + child.name, child.length(), child.lastModified()) { child.inputStream() })
                }
            }
        }
        walk(root, emptyList())
    }
}
