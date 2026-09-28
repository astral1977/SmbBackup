package de.astral.smbbackup.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormat = DateTimeFormatter.ofPattern("EE dd.MM.yyyy HH:mm", Locale.GERMAN)

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.GERMAN, "%.1f %s", value, units[unit])
}
