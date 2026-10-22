package com.audiosrt.app.ui

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiosrt.app.MainViewModel
import com.audiosrt.app.UiState
import com.audiosrt.app.data.Settings
import com.audiosrt.app.subtitle.Cue
import com.audiosrt.app.subtitle.SrtWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(-1) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current

    // Layar tetap menyala selama proses agar tidak terhenti saat HP terkunci.
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.selectFile(uri)
    }
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) vm.saveSrt(uri)
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            initial = settings,
            onSave = { vm.updateSettings(it); showSettings = false },
            onBack = { showSettings = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio ke SRT") },
                actions = {
                    IconButton(onClick = { showSettings = true }, enabled = !ui.running) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FileCard(ui, enabled = !ui.running) { pickFile.launch(arrayOf("audio/*", "video/*")) }
            }
            item {
                OptionsCard(settings, enabled = !ui.running, onChange = vm::updateSettings)
            }
            item {
                ActionArea(ui, onStart = vm::start, onCancel = vm::cancel)
            }
            ui.error?.let { err ->
                item { ErrorCard(err) }
            }
            if (ui.cues.isNotEmpty()) {
                item {
                    ResultBar(
                        canRebuild = vm.canRebuild() && !ui.running,
                        onSave = { saveFile.launch(vm.suggestedFileName()) },
                        onCopy = {
                            clipboard.setText(AnnotatedString(vm.srtText()))
                            if (Build.VERSION.SDK_INT < 33) {
                                Toast.makeText(context, "Subtitle disalin", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRebuild = vm::rebuild,
                    )
                }
                itemsIndexed(ui.cues) { i, cue ->
                    CueRow(i, cue) { editIndex = i }
                    HorizontalDivider()
                }
            }
        }
    }

    val editing = ui.cues.getOrNull(editIndex)
    if (editing != null) {
        EditCueDialog(
            index = editIndex,
            cue = editing,
            onSave = { vm.editCue(editIndex, it); editIndex = -1 },
            onDelete = { vm.deleteCue(editIndex); editIndex = -1 },
            onDismiss = { editIndex = -1 },
        )
    }
}

@Composable
private fun FileCard(ui: UiState, enabled: Boolean, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (ui.fileUri == null) "Pilih rekaman yang ingin dibuatkan subtitle" else ui.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Audio atau video: mp3, m4a, wav, ogg, opus, flac, mp4, mkv, dan lainnya. " +
                    "Bisa juga lewat menu Bagikan dari aplikasi lain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onPick, enabled = enabled) {
                Text(if (ui.fileUri == null) "Pilih file" else "Ganti file")
            }
        }
    }
}

@Composable
private fun OptionsCard(s: Settings, enabled: Boolean, onChange: (Settings) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf(s.prompt) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bahasa yang diucapkan", style = MaterialTheme.typography.labelLarge)
            LanguagePicker(s.language, enabled) { onChange(s.copy(language = it)) }

            Text("Model", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.model == Settings.MODEL_ACCURATE,
                    onClick = { onChange(s.copy(model = Settings.MODEL_ACCURATE)) },
                    label = { Text("Paling akurat") },
                    enabled = enabled,
                )
                FilterChip(
                    selected = s.model == Settings.MODEL_FAST,
                    onClick = { onChange(s.copy(model = Settings.MODEL_FAST)) },
                    label = { Text("Lebih cepat") },
                    enabled = enabled,
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it.take(300)
                    onChange(s.copy(prompt = prompt))
                },
                label = { Text("Nama & istilah khusus (opsional)") },
                supportingText = {
                    Text("Tulis ejaan yang benar, dipisah koma — nama orang, tempat, merek, istilah. Ini sangat membantu akurasi.")
                },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LanguagePicker(code: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = Settings.LANGUAGES.firstOrNull { it.first == code }?.second ?: code
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Settings.LANGUAGES.forEach { (c, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(c); open = false })
            }
        }
    }
}

@Composable
private fun ActionArea(ui: UiState, onStart: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.running) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Batalkan") }
        } else {
            Button(onClick = onStart, enabled = ui.fileUri != null, modifier = Modifier.fillMaxWidth()) {
                Text("Buat subtitle")
            }
        }
        if (ui.stage.isNotEmpty()) {
            Text(ui.stage, style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.running) {
            LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "Biarkan aplikasi tetap terbuka sampai selesai.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ResultBar(canRebuild: Boolean, onSave: () -> Unit, onCopy: () -> Unit, onRebuild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Simpan .srt") }
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Salin") }
        }
        if (canRebuild) {
            TextButton(onClick = onRebuild) { Text("Susun ulang memakai pengaturan terbaru") }
        }
        Text(
            "Ketuk baris untuk mengoreksi teks. Susun ulang akan menghapus koreksi manual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CueRow(index: Int, cue: Cue, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            "${index + 1}   ${SrtWriter.timestamp(cue.start)}  –  ${SrtWriter.timestamp(cue.end)}",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(cue.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EditCueDialog(index: Int, cue: Cue, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by remember(index, cue) { mutableStateOf(cue.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Baris ${index + 1}") },
        text = {
            Column {
                Text(
                    "${SrtWriter.timestamp(cue.start)}  –  ${SrtWriter.timestamp(cue.end)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Simpan") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Hapus baris", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        },
    )
}
