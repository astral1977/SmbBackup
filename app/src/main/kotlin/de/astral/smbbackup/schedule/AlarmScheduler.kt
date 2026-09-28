package de.astral.smbbackup.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.astral.smbbackup.app
import java.time.ZonedDateTime

/**
 * Setzt einen exakten Alarm auf den nächsten geplanten Zeitpunkt. AlarmManager statt WorkManager,
 * weil WorkManager-Jobs im Doze-Modus um Stunden verschoben werden können – das NAS fährt aber
 * zu einer festen Uhrzeit hoch.
 */
object AlarmScheduler {
    const val ACTION_RUN = "de.astral.smbbackup.action.RUN_SCHEDULED"
    const val EXTRA_SCHEDULED_AT = "scheduledAt"

    /** Plant den nächsten Lauf (oder entfernt ihn) und gibt den Zeitpunkt zurück. */
    fun reschedule(context: Context): ZonedDateTime? {
        val settings = context.app.settings.load()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val next = if (settings.scheduleEnabled) settings.schedule.nextAfter(ZonedDateTime.now()) else null

        if (next == null) {
            alarmManager.cancel(pendingIntent(context, 0))
            context.app.settings.nextRunAt = 0
            return null
        }
        val triggerAt = next.toInstant().toEpochMilli()
        val intent = pendingIntent(context, triggerAt)
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            // Ohne Berechtigung kann Android den Alarm etwas verzögern.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
        context.app.settings.nextRunAt = triggerAt
        return next
    }

    private fun pendingIntent(context: Context, scheduledAt: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_SCHEDULED_AT, scheduledAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
