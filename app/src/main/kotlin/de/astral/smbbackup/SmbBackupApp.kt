package de.astral.smbbackup

import android.app.Application
import de.astral.smbbackup.data.CredentialStore
import de.astral.smbbackup.data.RunLog
import de.astral.smbbackup.data.SettingsRepository
import de.astral.smbbackup.notify.Notifier
import de.astral.smbbackup.schedule.AlarmScheduler

class SmbBackupApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val credentials by lazy { CredentialStore(this) }
    val runLog by lazy { RunLog(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // Stellt sicher, dass der Alarm nach Updates o. Ä. gesetzt ist.
        AlarmScheduler.reschedule(this)
    }
}

val android.content.Context.app: SmbBackupApp get() = applicationContext as SmbBackupApp
