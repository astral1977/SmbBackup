package de.astral.smbbackup.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import de.astral.smbbackup.backup.BackupWorker
import de.astral.smbbackup.data.RunRecord

@Composable
fun StatusScreen(vm: MainViewModel, openSettings: () -> Unit) {
    val work by vm.work.collectAsStateWithLifecycle()
    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sicherung läuft", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(work?.progress?.getString(BackupWorker.KEY_PROGRESS) ?: "Wird vorbereitet …")
                    OutlinedButton(onClick = vm::cancelBackup) { Text("Abbrechen") }
                }
            }
        }

        val last = vm.runs.firstOrNull { it.status != de.astral.smbbackup.data.RunStatus.RUNNING }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Letzte Sicherung", style = MaterialTheme.typography.titleMedium)
                if (last == null) Text("Noch keine") else LastRun(last)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nächste geplante Sicherung", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !vm.settings.scheduleEnabled -> "Zeitplan ist ausgeschaltet"
                        vm.nextRunAt > 0 -> formatDateTime(vm.nextRunAt)
                        else -> "Kein Wochentag gewählt"
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::backupNow, enabled = !running) { Text("Jetzt sichern") }
            OutlinedButton(onClick = { vm.testConnection(vm.settings, "") }, enabled = !vm.testing) {
                Text(if (vm.testing) "Teste …" else "Verbindung testen")
            }
        }
        vm.testResult?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Text(result, Modifier.padding(16.dp))
            }
        }

        val missing = vm.settings.missingFields() + listOfNotNull(if (!vm.hasPassword) "Passwort" else null)
        if (missing.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Noch nicht eingerichtet: ${missing.joinToString()}")
                    TextButton(onClick = openSettings) { Text("Zu den Einstellungen") }
                }
            }
        }

        SetupChecklist(vm.resumeTick)
    }
}

@Composable
private fun LastRun(run: RunRecord) {
    Text("${formatDateTime(run.startedAt)} · ${run.trigger.label}")
    Text(run.status.label, color = statusColor(run.status), style = MaterialTheme.typography.titleSmall)
    Text(run.message)
}

private data class Check(val title: String, val ok: Boolean, val hint: String, val action: (() -> Unit)?)

@Composable
private fun SetupChecklist(resumeTick: Int) {
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.openAppSettings()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    val checks = remember(resumeTick) {
        val pkg = context.packageName
        listOf(
            Check(
                "Benachrichtigungen", context.granted(Manifest.permission.POST_NOTIFICATIONS),
                "Für Fehlermeldungen",
            ) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            Check(
                "Standort (genau)", context.granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "Android gibt den WLAN-Namen nur mit Standortberechtigung heraus",
            ) {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            Check(
                "Standort \"Immer erlauben\"", context.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                "Damit das WLAN auch im Hintergrund geprüft werden kann",
            ) { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            Check(
                "Standortdienst eingeschaltet",
                context.getSystemService(LocationManager::class.java).isLocationEnabled,
                "Ohne eingeschalteten Standort ist der WLAN-Name unbekannt",
            ) { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            Check(
                "Keine Akku-Einschränkung",
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg),
                "Sonst kann Android die Sicherung verzögern oder abbrechen",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$pkg".toUri()),
                )
            },
            Check(
                "Exakte Uhrzeit",
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                "Damit die Sicherung pünktlich startet",
            ) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$pkg".toUri()))
            },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Einrichtung", style = MaterialTheme.typography.titleMedium)
            checks.forEach { check ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (check.ok) "✓" else "✗",
                        color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(check.title)
                        if (!check.ok) Text(check.hint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!check.ok && check.action != null) {
                        TextButton(onClick = check.action) { Text("Erlauben") }
                    }
                }
            }
        }
    }
}

private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}
