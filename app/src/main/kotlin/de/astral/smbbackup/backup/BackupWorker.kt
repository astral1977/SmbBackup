package de.astral.smbbackup.backup

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
        BackupRunner(applicationContext).run(trigger, deadline) { text ->
            setProgress(workDataOf(KEY_PROGRESS to text))
            promote(text)
        }
        // Fehler stehen im Protokoll; ein automatischer Neuversuch folgt erst zum nächsten Termin.
        return Result.success()
    }

    /**
     * Als Vordergrunddienst laufen, damit längere Übertragungen und das Warten auf das NAS nicht
     * nach 10 Minuten beendet werden. Android erlaubt das aus dem Hintergrund nur mit
     * Ausnahme von der Akku-Optimierung; sonst läuft der Job normal weiter.
     */
    private suspend fun promote(text: String) {
        if (!foregroundAllowed) return
        try {
            setForeground(foregroundInfo(text))
        } catch (_: IllegalStateException) {
            foregroundAllowed = false
        }
    }

    private fun foregroundInfo(text: String) = ForegroundInfo(
        Notifier.ID_PROGRESS,
        Notifier.progress(applicationContext, text, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
