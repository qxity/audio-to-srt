package com.audiosrt.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiosrt.app.api.GroqClient
import com.audiosrt.app.audio.AudioChunker
import com.audiosrt.app.audio.AudioDecoder
import com.audiosrt.app.data.Settings
import com.audiosrt.app.data.SettingsStore
import com.audiosrt.app.subtitle.Cue
import com.audiosrt.app.subtitle.SrtWriter
import com.audiosrt.app.subtitle.SubtitleBuilder
import com.audiosrt.app.subtitle.TranscriptProcessor
import com.audiosrt.app.subtitle.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val fileUri: Uri? = null,
    val fileName: String = "",
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val cues: List<Cue> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Kata mentah hasil transkripsi — disimpan agar subtitle bisa disusun ulang tanpa memanggil API lagi. */
    private var words: List<Word> = emptyList()
    private var job: Job? = null

    fun updateSettings(s: Settings) {
        _settings.value = s
        store.save(s)
    }

    fun selectFile(uri: Uri) {
        if (_ui.value.running) return
        words = emptyList()
        _ui.value = UiState(fileUri = uri, fileName = queryName(uri) ?: "audio")
    }

    fun start() {
        val uri = _ui.value.fileUri ?: return
        val s = _settings.value
        if (s.apiKey.isBlank()) {
            _ui.update { it.copy(error = "Isi Groq API key dulu di Pengaturan (ikon gerigi di kanan atas).") }
            return
        }
        if (_ui.value.running) return
        val app = getApplication<Application>()

        job = viewModelScope.launch(Dispatchers.Default) {
            val work = File(app.cacheDir, "job").apply { deleteRecursively(); mkdirs() }
            _ui.update { it.copy(running = true, error = null, cues = emptyList(), progress = 0f, stage = "Membaca audio…") }
            try {
                // 1. Decode ke PCM 16 kHz mono
                val pcm = File(work, "audio.pcm")
                val samples = AudioDecoder(app).decodeToPcm16k(uri, pcm) { p ->
                    _ui.update { it.copy(progress = p * 0.25f, stage = "Membaca audio… ${(p * 100).toInt()}%") }
                }
                if (samples < AudioChunker.SAMPLE_RATE / 2) throw IllegalStateException("Audio terlalu pendek atau kosong.")
                val totalSec = samples.toDouble() / AudioChunker.SAMPLE_RATE

                // 2. Potong di titik hening
                setStage("Memotong audio di titik hening…", 0.27f)
                val chunks = AudioChunker.split(pcm, work, s.chunkMinutes * 60)
                pcm.delete()

                // 3. Transkripsi tiap potongan
                val client = GroqClient(s.apiKey.trim())
                val language = s.language.ifBlank { null }
                val all = ArrayList<Word>()
                var previousText = ""
                chunks.forEachIndexed { i, ch ->
                    val base = 0.3f + 0.68f * i / chunks.size
                    val label = if (chunks.size > 1) "Transkripsi bagian ${i + 1}/${chunks.size}" else "Transkripsi"
                    setStage("$label (${formatDuration(totalSec)} audio)…", base)
                    val raw = client.transcribe(ch.file, s.model, language, buildPrompt(s.prompt, previousText)) { status ->
                        _ui.update { it.copy(stage = "$label: $status") }
                    }
                    val processed = TranscriptProcessor.process(raw, s.filterHallucination)
                    all += processed.words.map { it.copy(start = it.start + ch.startSec, end = it.end + ch.startSec) }
                    previousText = processed.text
                    ch.file.delete()
                }

                // 4. Susun subtitle
                setStage("Menyusun subtitle…", 0.99f)
                words = all
                val cues = SubtitleBuilder(s.toBuilderConfig()).build(all)
                _ui.update {
                    it.copy(
                        cues = cues,
                        progress = 1f,
                        stage = if (cues.isEmpty()) "Tidak ada ucapan yang terdeteksi." else "Selesai — ${cues.size} baris subtitle.",
                    )
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(stage = "Dibatalkan.", progress = 0f) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString(), stage = "") }
            } finally {
                _ui.update { it.copy(running = false) }
                work.deleteRecursively()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Susun ulang subtitle dengan pengaturan terbaru tanpa transkripsi ulang. */
    fun rebuild() {
        if (words.isEmpty() || _ui.value.running) return
        val cues = SubtitleBuilder(_settings.value.toBuilderConfig()).build(words)
        _ui.update { it.copy(cues = cues, stage = "Disusun ulang — ${cues.size} baris subtitle.") }
    }

    fun canRebuild() = words.isNotEmpty()

    fun editCue(index: Int, text: String) {
        _ui.update { st ->
            st.copy(cues = st.cues.mapIndexed { i, c -> if (i == index) c.copy(text = text.trim()) else c })
        }
    }

    fun deleteCue(index: Int) {
        _ui.update { st -> st.copy(cues = st.cues.filterIndexed { i, _ -> i != index }) }
    }

    fun srtText(): String = SrtWriter.write(_ui.value.cues)

    fun suggestedFileName(): String =
        _ui.value.fileName.substringBeforeLast('.').ifBlank { "subtitle" } + ".srt"

    fun saveSrt(target: Uri) {
        val text = srtText()
        val app = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val os = runCatching { app.contentResolver.openOutputStream(target, "wt") }.getOrNull()
                        ?: app.contentResolver.openOutputStream(target)
                    os!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            Toast.makeText(app, if (ok) "File .srt tersimpan" else "Gagal menyimpan file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setStage(text: String, progress: Float) {
        _ui.update { it.copy(stage = text, progress = progress) }
    }

    /** Konteks untuk Whisper: istilah dari pengguna + kalimat terakhir potongan sebelumnya (menjaga ejaan konsisten). */
    private fun buildPrompt(userPrompt: String, previous: String): String? {
        val u = userPrompt.trim().take(300)
        val p = previous.trim().takeLast(200).substringAfter(' ', previous.trim().takeLast(200))
        return listOf(u, p).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun formatDuration(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
