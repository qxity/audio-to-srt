package com.audiosrt.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.audiosrt.app.data.Settings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initial: Settings, onSave: (Settings) -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { onSave(s) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) { Text("Simpan pengaturan") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Groq API key")
            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { s = s.copy(apiKey = it.trim()) },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Sembunyikan" else "Lihat") }
                },
                supportingText = {
                    Text("Buat gratis di console.groq.com/keys. Audio dikirim ke Groq untuk ditranskripsi.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Tampilan subtitle")
            LabeledSlider(
                "Maks. karakter per baris", s.maxCharsPerLine.toFloat(), 20f..60f, steps = 39,
                format = { "${it.roundToInt()}" },
            ) { s = s.copy(maxCharsPerLine = it.roundToInt()) }

            Text("Jumlah baris per subtitle", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.maxLines == 1, onClick = { s = s.copy(maxLines = 1) }, label = { Text("1 baris") })
                FilterChip(selected = s.maxLines == 2, onClick = { s = s.copy(maxLines = 2) }, label = { Text("2 baris") })
            }

            LabeledSlider(
                "Maks. durasi satu subtitle", s.maxCueSec, 2f..10f, steps = 15,
                format = { "%.1f dtk".format(it) },
            ) { s = s.copy(maxCueSec = it) }

            LabeledSlider(
                "Min. durasi tampil", s.minCueSec, 0.5f..2.5f, steps = 7,
                format = { "%.2f dtk".format(it) },
            ) { s = s.copy(minCueSec = it) }

            LabeledSlider(
                "Pisah subtitle jika jeda bicara lebih dari", s.pauseSplitSec, 0.3f..2f, steps = 16,
                format = { "%.1f dtk".format(it) },
            ) { s = s.copy(pauseSplitSec = it) }

            LabeledSlider(
                "Maks. kecepatan baca", s.maxCps.toFloat(), 10f..25f, steps = 14,
                format = { "${it.roundToInt()} huruf/dtk" },
            ) { s = s.copy(maxCps = it.roundToInt()) }

            SectionTitle("Pemrosesan")
            LabeledSlider(
                "Panjang potongan audio yang dikirim", s.chunkMinutes.toFloat(), 2f..12f, steps = 9,
                format = { "${it.roundToInt()} menit" },
            ) { s = s.copy(chunkMinutes = it.roundToInt()) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Buang teks halusinasi", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Menghapus kalimat yang muncul di bagian hening padahal tidak diucapkan, " +
                            "mis. \"Terima kasih telah menonton\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.filterHallucination,
                    onCheckedChange = { s = s.copy(filterHallucination = it) },
                )
            }

            TextButton(onClick = {
                s = Settings(apiKey = s.apiKey, model = s.model, language = s.language, prompt = s.prompt)
            }) { Text("Kembalikan tampilan & pemrosesan ke bawaan") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}
