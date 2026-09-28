package de.astral.smbbackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), zone)

    @Test
    fun laterTodayWhenTimeNotPassed() {
        // 2026-09-28 ist ein Montag
        val s = Schedule(setOf(MONDAY), LocalTime.of(21, 0))
        assertEquals(at("2026-09-28T21:00"), s.nextAfter(at("2026-09-28T20:59")))
    }

    @Test
    fun exactlyAtTimeMeansNextOccurrence() {
        val s = Schedule(setOf(MONDAY), LocalTime.of(21, 0))
        assertEquals(at("2026-10-05T21:00"), s.nextAfter(at("2026-09-28T21:00")))
    }

    @Test
    fun skipsToNextSelectedDay() {
        val s = Schedule(setOf(WEDNESDAY, FRIDAY), LocalTime.of(7, 30))
        assertEquals(at("2026-09-30T07:30"), s.nextAfter(at("2026-09-28T12:00")))
        assertEquals(at("2026-10-02T07:30"), s.nextAfter(at("2026-09-30T07:31")))
    }

    @Test
    fun wrapsAroundWeek() {
        val s = Schedule(setOf(SUNDAY), LocalTime.of(3, 0))
        assertEquals(at("2026-10-04T03:00"), s.nextAfter(at("2026-09-28T00:00")))
    }

    @Test
    fun noDaysMeansNoSchedule() {
        assertNull(Schedule(emptySet(), LocalTime.NOON).nextAfter(at("2026-09-28T00:00")))
    }

    @Test
    fun daylightSavingGapIsShifted() {
        // 2027-03-28: in Deutschland springt die Uhr von 02:00 auf 03:00
        val s = Schedule(setOf(SUNDAY), LocalTime.of(2, 30))
        val next = s.nextAfter(at("2027-03-27T12:00"))!!
        assertEquals(LocalDateTime.parse("2027-03-28T03:30"), next.toLocalDateTime())
    }
}
