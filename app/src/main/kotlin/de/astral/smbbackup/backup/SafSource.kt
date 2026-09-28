package de.astral.smbbackup.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import de.astral.smbbackup.core.LocalFile
import de.astral.smbbackup.core.LocalSource
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Liest einen per Ordnerauswahl freigegebenen Ordner (Storage Access Framework).
 * Nutzt DocumentsContract direkt, das ist deutlich schneller als DocumentFile.
 */
class SafSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    override val label: String,
    private val skipHidden: Boolean,
) : LocalSource {

    private class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)

    override fun files(): Sequence<LocalFile> = sequence {
        walk(DocumentsContract.getTreeDocumentId(treeUri), emptyList())
    }

    private suspend fun SequenceScope<LocalFile>.walk(documentId: String, prefix: List<String>) {
        for (child in children(documentId)) {
            if (skipHidden && child.name.startsWith(".")) continue
            if (child.mime == Document.MIME_TYPE_DIR) {
                walk(child.id, prefix + child.name)
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                yield(
                    LocalFile(prefix + child.name, child.size, child.modified) {
                        resolver.openInputStream(uri) ?: throw FileNotFoundException(child.name)
                    },
                )
            }
        }
    }

    private fun children(documentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Ordner \"$label\" kann nicht gelesen werden")
        val result = mutableListOf<Child>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name.isNullOrEmpty()) continue
                result += Child(
                    id = c.getString(0),
                    name = name,
                    mime = c.getString(2).orEmpty(),
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return result.sortedBy { it.name }
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
