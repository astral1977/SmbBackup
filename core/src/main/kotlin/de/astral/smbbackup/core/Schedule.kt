package de.astral.smbbackup.core

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/** Wochentage und Uhrzeit, zu denen gesichert wird. */
data class Schedule(
    val days: Set<DayOfWeek>,
    val time: LocalTime,
) {
    /** Nächster Zeitpunkt echt nach [now] oder `null`, wenn kein Tag gewählt ist. */
    fun nextAfter(now: ZonedDateTime): ZonedDateTime? {
        if (days.isEmpty()) return null
        val base = time.withSecond(0).withNano(0)
        for (offset in 0..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in days) continue
            // ZonedDateTime.of verschiebt Zeiten in einer Sommerzeit-Lücke automatisch nach hinten.
            val candidate = ZonedDateTime.of(date, base, now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}
