package de.astral.smbbackup.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.astral.smbbackup.R
import de.astral.smbbackup.ui.MainActivity

object Notifier {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_ERRORS = "errors"
    const val CHANNEL_RESULTS = "results"

    const val ID_PROGRESS = 1
    private const val ID_RESULT = 2
    private const val ID_STALE = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Laufende Sicherung", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ERRORS, "Fehler und Warnungen", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_RESULTS, "Erfolgreiche Sicherungen", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun progress(context: Context, text: String, cancelIntent: PendingIntent): Notification =
        Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sicherung läuft")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(context, MainActivity.TAB_STATUS))
            .addAction(Notification.Action.Builder(null, "Abbrechen", cancelIntent).build())
            .build()

    fun error(context: Context, title: String, text: String) = post(context, ID_RESULT, CHANNEL_ERRORS, title, text)

    fun success(context: Context, text: String) = post(context, ID_RESULT, CHANNEL_RESULTS, "Sicherung erfolgreich", text)

    fun stale(context: Context, days: Long) = post(
        context, ID_STALE, CHANNEL_ERRORS,
        "Kein aktuelles Backup",
        "Seit $days Tagen wurde nicht erfolgreich gesichert.",
    )

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            id,
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, MainActivity.TAB_LOG))
                .build(),
        )
    }

    private fun openApp(context: Context, tab: Int): PendingIntent = PendingIntent.getActivity(
        context,
        tab,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
