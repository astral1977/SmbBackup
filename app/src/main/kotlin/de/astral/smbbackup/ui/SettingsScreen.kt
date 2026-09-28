@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.astral.smbbackup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.astral.smbbackup.core.CopyEngine
import de.astral.smbbackup.data.AppSettings
import de.astral.smbbackup.data.FolderConfig
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var draft by remember(vm.settings) { mutableStateOf(vm.settings) }
    var password by rememberSaveable { mutableStateOf("") }
    val dirty = draft != vm.settings || password.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerSection(draft, { draft = it }, password, { password = it }, vm.hasPassword)
            WifiSection(draft, { draft = it }, vm)
            ScheduleSection(draft) { draft = it }
            FolderSection(draft, { draft = it }, vm)
            OptionsSection(draft) { draft = it }

            OutlinedButton(onClick = { vm.testConnection(draft, password) }, enabled = !vm.testing) {
                Text(if (vm.testing) "Teste …" else "Verbindung mit diesen Angaben testen")
            }
            vm.testResult?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
        }
        if (dirty) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { draft = vm.settings; password = "" }) { Text("Verwerfen") }
                    Button(onClick = { vm.save(draft, password); password = "" }) { Text("Speichern") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { n -> n in range }?.let(onChange)
        },
        label = { Text(label) },
        isError = !valid,
        supportingText = { if (!valid) Text("${range.first} bis ${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerSection(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    hasPassword: Boolean,
) = Section("NAS") {
    OutlinedTextField(
        s.host, { onChange(s.copy(host = it)) },
        label = { Text("Adresse") },
        supportingText = { Text("Am besten feste IP, z. B. 192.168.178.20") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    NumberField("Port", s.port, 1..65535) { onChange(s.copy(port = it)) }
    OutlinedTextField(
        s.share, { onChange(s.copy(share = it)) },
        label = { Text("Freigabe") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        s.basePath, { onChange(s.copy(basePath = it)) },
        label = { Text("Zielordner in der Freigabe") },
        supportingText = { Text("z. B. Handy-Backup oder Backup/Pixel") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        s.username, { onChange(s.copy(username = it)) },
        label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        password, onPassword,
        label = { Text("Passwort") },
        placeholder = { if (hasPassword) Text("gespeichert – leer lassen zum Behalten") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        s.domain, { onChange(s.copy(domain = it)) },
        label = { Text("Domäne (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(
        "Verschlüsselung erzwingen", s.requireEncryption,
        "Nur mit SMB3-Verschlüsselung übertragen (DSM: max. Protokoll SMB3)",
    ) { onChange(s.copy(requireEncryption = it)) }
}

@Composable
private fun WifiSection(s: AppSettings, onChange: (AppSettings) -> Unit, vm: MainViewModel) = Section("Heim-WLAN") {
    val scope = rememberCoroutineScope()
    var newSsid by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    Text("Gesichert wird nur, wenn das Handy mit einem dieser WLANs verbunden ist.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.homeSsids.forEach { ssid ->
            InputChip(
                selected = false,
                onClick = { onChange(s.copy(homeSsids = s.homeSsids - ssid)) },
                label = { Text(ssid) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Entfernen") },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            newSsid, { newSsid = it }, label = { Text("WLAN-Name (SSID)") }, singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                if (newSsid.isNotBlank()) onChange(s.copy(homeSsids = (s.homeSsids + newSsid.trim()).distinct()))
                newSsid = ""
            },
        ) { Text("Hinzufügen") }
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                val ssid = vm.currentSsid()
                if (ssid == null) {
                    hint = "WLAN-Name nicht lesbar – im Status-Tab die Standortberechtigung erteilen."
                } else {
                    hint = null
                    onChange(s.copy(homeSsids = (s.homeSsids + ssid).distinct()))
                }
            }
        },
    ) { Text("Aktuelles WLAN übernehmen") }
    hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ScheduleSection(s: AppSettings, onChange: (AppSettings) -> Unit) = Section("Zeitplan") {
    var showTimePicker by remember { mutableStateOf(false) }
    SwitchRow("Automatisch sichern", s.scheduleEnabled) { onChange(s.copy(scheduleEnabled = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in s.days,
                onClick = { onChange(s.copy(days = if (day in s.days) s.days - day else s.days + day)) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.GERMAN)) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Uhrzeit", Modifier.weight(1f))
        OutlinedButton(onClick = { showTimePicker = true }) { Text("%02d:%02d Uhr".format(s.hour, s.minute)) }
    }
    NumberField("Auf WLAN/NAS warten (Minuten)", s.waitMinutes, 0..180) { onChange(s.copy(waitMinutes = it)) }
    Text(
        "Nach der Uhrzeit wird so lange alle 30 Sekunden geprüft, ob Heim-WLAN und NAS verfügbar sind " +
            "(z. B. während das NAS hochfährt). Klappt es nicht, wird es am nächsten geplanten Tag erneut versucht.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = s.hour, initialMinute = s.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(s.copy(hour = state.hour, minute = state.minute))
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Abbrechen") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun FolderSection(s: AppSettings, onChange: (AppSettings) -> Unit, vm: MainViewModel) = Section("Ordner") {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && s.folders.none { it.uri == uri.toString() }) {
            onChange(s.copy(folders = s.folders + vm.folderFromPicker(uri)))
        }
    }
    if (s.folders.isEmpty()) Text("Noch keine Ordner ausgewählt")
    s.folders.forEachIndexed { index, folder ->
        if (index > 0) HorizontalDivider()
        FolderRow(
            folder = folder,
            targetPreview = "\\\\${s.host}\\${s.share}\\" +
                CopyEngine.joinPath(s.basePath, folder.remoteFolder).replace('/', '\\'),
            onChange = { updated -> onChange(s.copy(folders = s.folders.toMutableList().also { it[index] = updated })) },
            onRemove = { onChange(s.copy(folders = s.folders - folder)) },
        )
    }
    OutlinedButton(onClick = { picker.launch(null) }) { Text("Ordner hinzufügen") }
}

@Composable
private fun FolderRow(folder: FolderConfig, targetPreview: String, onChange: (FolderConfig) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = folder.enabled, onCheckedChange = { onChange(folder.copy(enabled = it)) })
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Ordner entfernen") }
        }
        OutlinedTextField(
            folder.remoteFolder, { onChange(folder.copy(remoteFolder = it)) },
            label = { Text("Unterordner auf dem NAS") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Ziel: $targetPreview", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionsSection(s: AppSettings, onChange: (AppSettings) -> Unit) = Section("Optionen") {
    SwitchRow(
        "Geänderte Dateien überschreiben", s.overwriteChanged,
        "Aus: Dateien, die schon auf dem NAS liegen, werden nie angefasst",
    ) { onChange(s.copy(overwriteChanged = it)) }
    SwitchRow("Versteckte Dateien überspringen", s.skipHidden, "Namen mit Punkt am Anfang, z. B. .thumbnails") {
        onChange(s.copy(skipHidden = it))
    }
    SwitchRow("Auch Erfolg melden", s.notifySuccess, "Benachrichtigung nach jeder erfolgreichen Sicherung") {
        onChange(s.copy(notifySuccess = it))
    }
    NumberField("Warnen nach Tagen ohne Erfolg (0 = aus)", s.staleWarningDays, 0..60) {
        onChange(s.copy(staleWarningDays = it))
    }
    Text("Auf dem NAS wird nie etwas gelöscht.", style = MaterialTheme.typography.bodySmall)
}
