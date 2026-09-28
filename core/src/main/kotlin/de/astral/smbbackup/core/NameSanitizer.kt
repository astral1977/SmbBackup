package de.astral.smbbackup.core

import java.text.Normalizer

/** Macht Datei- und Ordnernamen für SMB/Windows-Dateisysteme gültig. */
object NameSanitizer {
    private val invalidChars = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    fun sanitize(name: String): String {
        var result = Normalizer.normalize(name, Normalizer.Form.NFC)
        result = invalidChars.replace(result, "_")
        // Windows/SMB erlaubt keine Punkte oder Leerzeichen am Ende.
        result = result.trimEnd('.', ' ')
        if (result.isEmpty()) result = "_"
        if (result.substringBefore('.').uppercase() in reservedNames) result = "_$result"
        return result
    }

    /** Hängt " (n)" vor der Dateiendung an, z. B. "Foto.jpg" -> "Foto (2).jpg". */
    fun withSuffix(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
