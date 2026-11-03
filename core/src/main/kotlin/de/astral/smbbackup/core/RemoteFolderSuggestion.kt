package de.astral.smbbackup.core

/**
 * Schlägt beim Hinzufügen eines Ordners den Unterordner auf dem NAS vor:
 * den Pfad ab dem Speicher-Hauptverzeichnis, z. B. "WhatsApp/Media" statt nur "Media".
 */
object RemoteFolderSuggestion {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    // Interner Speicher und SD-Karten, z. B. "/storage/emulated/0/" oder "/storage/1234-ABCD/".
    private val storageRoot = Regex("""^/storage/(emulated/\d+|[^/]+)/?""")

    /**
     * @param authority Authority der Ordner-URI aus der Ordnerauswahl
     * @param treeDocumentId z. B. "primary:Android/media/com.whatsapp/WhatsApp/Media"
     * @param displayName Anzeigename des Ordners, falls sich kein Pfad ermitteln lässt
     */
    fun suggest(authority: String?, treeDocumentId: String, displayName: String): String {
        val path = when (authority) {
            // Dokument-ID "<Speicher>:<Pfad>", z. B. "primary:DCIM/Camera" oder "1234-ABCD:Fotos".
            EXTERNAL_STORAGE_AUTHORITY -> treeDocumentId.substringAfter(':', "")
            // Ordner unter "Downloads" haben teils die ID "raw:/storage/emulated/0/Download/…".
            DOWNLOADS_AUTHORITY -> treeDocumentId.removePrefix("raw:").takeIf { it.startsWith("/") }
                ?.replace(storageRoot, "")
            else -> null
        }
        val segments = path.orEmpty().split('/').filter { it.isNotBlank() }
        return segments.ifEmpty { listOf(displayName) }
            .let(::withoutAppMediaPrefix)
            .joinToString("/") { NameSanitizer.sanitize(it) }
    }

    /**
     * Seit Android 11 liegen z. B. WhatsApp-Medien unter "Android/media/com.whatsapp/WhatsApp/Media".
     * Der technische Teil "Android/media/<App>" wird weggelassen.
     */
    private fun withoutAppMediaPrefix(segments: List<String>): List<String> =
        if (segments.size > 3 && segments[0] == "Android" && segments[1] == "media") segments.drop(3) else segments
}
