package de.astral.smbbackup.backup

import android.app.job.JobParameters
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.astral.smbbackup.data.Trigger
import de.astral.smbbackup.notify.Notifier

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_NAME = "backup"
        const val KEY_PROGRESS = "progress"

        /** Anteil 0..1 oder -1, solange die Gesamtmenge noch nicht feststeht. */
        const val KEY_FRACTION = "fraction"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_DEADLINE = "deadline"

        /** @param deadline bis wann auf Heim-WLAN und NAS gewartet wird */
        fun enqueue(context: Context, trigger: Trigger, deadline: Long = System.currentTimeMillis()) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name, KEY_DEADLINE to deadline))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }

    private var foregroundAllowed = true

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Wird vorbereitet …")

    override suspend fun doWork(): Result {
        val trigger = runCatching { Trigger.valueOf(inputData.getString(KEY_TRIGGER)!!) }.getOrDefault(Trigger.MANUAL)
        val deadline = inputData.getLong(KEY_DEADLINE, 0)
        promote("Wird vorbereitet …")
        BackupRunner(applicationContext).run(trigger, deadline, stopReason = { describeStop(stopReason) }) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress.text, KEY_FRACTION to (progress.fraction ?: -1f)))
            promote(progress.text, progress.fraction)
        }
        // Fehler stehen im Protokoll; ein automatischer Neuversuch folgt erst zum nächsten Termin.
        return Result.success()
    }

    /** Warum Android/WorkManager den Lauf beendet hat; `null` bei Abbruch durch den Nutzer. */
    private fun describeStop(reason: Int): String? {
        val text = when (reason) {
            JobParameters.STOP_REASON_CANCELLED_BY_APP -> return null
            JobParameters.STOP_REASON_USER -> "Vom Nutzer in den Systemeinstellungen beendet"
            JobParameters.STOP_REASON_PREEMPT -> "Android hat einen anderen Job vorgezogen"
            JobParameters.STOP_REASON_TIMEOUT -> "Zeitlimit für Hintergrundarbeit erreicht"
            JobParameters.STOP_REASON_DEVICE_STATE -> "Gerätezustand (z. B. Energiesparmodus)"
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Netzwerkverbindung weggefallen"
            JobParameters.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "Ruhemodus (Doze)"
            JobParameters.STOP_REASON_QUOTA -> "Kontingent für Hintergrundarbeit aufgebraucht"
            JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> "App im Hintergrund eingeschränkt (Akku-Einstellungen)"
            JobParameters.STOP_REASON_APP_STANDBY -> "App-Standby"
            JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "Systemvorgang (z. B. App-Update oder Neuplanung)"
            JobParameters.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "Neuplanung durch das System"
            else -> "Code $reason"
        }
        return "Vom System unterbrochen: $text. Android startet die Sicherung automatisch neu."
    }

    /**
     * Als Vordergrunddienst laufen, damit längere Übertragungen und das Warten auf das NAS nicht
     * nach 10 Minuten beendet werden. Android erlaubt das aus dem Hintergrund nur mit
     * Ausnahme von der Akku-Optimierung; sonst läuft der Job normal weiter.
     */
    private suspend fun promote(text: String, fraction: Float? = null) {
        if (!foregroundAllowed) return
        try {
            setForeground(foregroundInfo(text, fraction))
        } catch (_: IllegalStateException) {
            foregroundAllowed = false
        }
    }

    private fun foregroundInfo(text: String, fraction: Float? = null) = ForegroundInfo(
        Notifier.ID_PROGRESS,
        Notifier.progress(applicationContext, text, fraction, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
