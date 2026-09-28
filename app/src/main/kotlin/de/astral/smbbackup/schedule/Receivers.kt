package de.astral.smbbackup.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.astral.smbbackup.app
import de.astral.smbbackup.backup.BackupWorker
import de.astral.smbbackup.data.Trigger

/** Wird zum geplanten Zeitpunkt ausgelöst und startet die Sicherung. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_RUN) return
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        val waitMillis = context.app.settings.load().waitMinutes * 60_000L
        BackupWorker.enqueue(context, Trigger.SCHEDULED, deadline = maxOf(scheduledAt, System.currentTimeMillis()) + waitMillis)
        AlarmScheduler.reschedule(context)
    }
}

/** Nach Neustart, App-Update, Zeitumstellung oder Berechtigungsänderung den Alarm neu setzen. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.reschedule(context)
    }
}
