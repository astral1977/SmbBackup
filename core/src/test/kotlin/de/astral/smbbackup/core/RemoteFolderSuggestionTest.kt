package de.astral.smbbackup.core

import de.astral.smbbackup.core.RemoteFolderSuggestion.DOWNLOADS_AUTHORITY
import de.astral.smbbackup.core.RemoteFolderSuggestion.EXTERNAL_STORAGE_AUTHORITY
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFolderSuggestionTest {
    private fun external(id: String, name: String) = RemoteFolderSuggestion.suggest(EXTERNAL_STORAGE_AUTHORITY, id, name)

    @Test
    fun usesPathFromStorageRoot() {
        assertEquals("WhatsApp/Media", external("primary:WhatsApp/Media", "Media"))
        assertEquals("DCIM/Camera", external("primary:DCIM/Camera", "Camera"))
        assertEquals("Fotos/Urlaub", external("1234-ABCD:Fotos/Urlaub", "Urlaub"))
    }

    @Test
    fun dropsAndroidMediaAppPrefix() {
        assertEquals("WhatsApp/Media", external("primary:Android/media/com.whatsapp/WhatsApp/Media", "Media"))
        assertEquals("WhatsApp", external("primary:Android/media/com.whatsapp/WhatsApp", "WhatsApp"))
        // Ohne Unterordner bleibt der volle Pfad stehen.
        assertEquals("Android/media/com.whatsapp", external("primary:Android/media/com.whatsapp", "com.whatsapp"))
    }

    @Test
    fun fallsBackToDisplayName() {
        assertEquals("Interner Speicher", external("primary:", "Interner Speicher"))
        assertEquals("Ordner", RemoteFolderSuggestion.suggest("com.google.android.apps.docs.storage", "abc123", "Ordner"))
        assertEquals("Download", RemoteFolderSuggestion.suggest(DOWNLOADS_AUTHORITY, "downloads", "Download"))
        assertEquals("Belege", RemoteFolderSuggestion.suggest(DOWNLOADS_AUTHORITY, "msf:42", "Belege"))
    }

    @Test
    fun handlesRawDownloadPaths() {
        assertEquals("Download/Belege", RemoteFolderSuggestion.suggest(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/Belege", "Belege"))
    }

    @Test
    fun sanitizesEverySegment() {
        assertEquals("Notizen_/Entwurf", external("primary:Notizen?/Entwurf. ", "Entwurf"))
    }
}
