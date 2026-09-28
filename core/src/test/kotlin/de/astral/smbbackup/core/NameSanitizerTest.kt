package de.astral.smbbackup.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NameSanitizerTest {
    @Test
    fun replacesInvalidCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i", NameSanitizer.sanitize("a:b*c?d\"e<f>g|h\\i"))
    }

    @Test
    fun trimsTrailingDotsAndSpaces() {
        assertEquals("Notiz", NameSanitizer.sanitize("Notiz. . "))
        assertEquals("_", NameSanitizer.sanitize("..."))
    }

    @Test
    fun escapesReservedNames() {
        assertEquals("_CON", NameSanitizer.sanitize("CON"))
        assertEquals("_com1.txt", NameSanitizer.sanitize("com1.txt"))
        assertEquals("CONSOLE.txt", NameSanitizer.sanitize("CONSOLE.txt"))
    }

    @Test
    fun normalizesUnicodeToNfc() {
        val decomposed = "Müller.jpg"
        assertEquals("Müller.jpg", NameSanitizer.sanitize(decomposed))
    }

    @Test
    fun suffixGoesBeforeExtension() {
        assertEquals("Foto (2).jpg", NameSanitizer.withSuffix("Foto.jpg", 2))
        assertEquals("README (3)", NameSanitizer.withSuffix("README", 3))
        assertEquals(".nomedia (2)", NameSanitizer.withSuffix(".nomedia", 2))
    }
}
