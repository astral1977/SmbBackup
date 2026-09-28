package de.astral.smbbackup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.astral.smbbackup.data.RunRecord
import de.astral.smbbackup.data.RunStatus

@Composable
fun LogScreen(vm: MainViewModel) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::refreshLog) { Text("Aktualisieren") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = vm.runs.isNotEmpty()) { Text("Leeren") }
        }
        if (vm.runs.isEmpty()) {
            Text("Noch keine Einträge", Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.runs, key = { it.id }) { RunCard(it) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Protokoll leeren?") },
            confirmButton = {
                TextButton(onClick = { vm.clearLog(); confirmClear = false }) { Text("Leeren") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun RunCard(run: RunRecord) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(formatDateTime(run.startedAt), style = MaterialTheme.typography.labelLarge)
                Text(run.trigger.label, style = MaterialTheme.typography.labelMedium)
            }
            Text(run.status.label, color = statusColor(run.status), style = MaterialTheme.typography.titleSmall)
            Text(run.message, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                val duration = run.finishedAt?.let { " · Dauer ${(it - run.startedAt) / 1000} s" }.orEmpty()
                Text(
                    "Geprüft ${run.checked} · kopiert ${run.uploaded} (${formatBytes(run.bytes)}) · " +
                        "unverändert ${run.skipped} · Fehler ${run.failed}$duration",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (run.details.isNotBlank()) {
                    Text(run.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    RunStatus.WARNING -> MaterialTheme.colorScheme.tertiary
    RunStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
