package com.audiosrt.app.api

import com.audiosrt.app.subtitle.RawTranscript
import com.audiosrt.app.subtitle.Segment
import com.audiosrt.app.subtitle.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String) : Exception(message)

/**
 * Whisper large-v3 lewat Groq (gratis dengan batas pemakaian, sangat cepat).
 * Meminta verbose_json + timestamp per kata & per segmen.
 */
class GroqClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private data class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    suspend fun transcribe(
        file: File,
        model: String,
        language: String?,
        prompt: String?,
        onStatus: (String) -> Unit,
    ): RawTranscript = withContext(Dispatchers.IO) {
        transcribeWithRetry(file, model, language, prompt, onStatus)
    }

    private suspend fun transcribeWithRetry(
        file: File,
        model: String,
        language: String?,
        prompt: String?,
        onStatus: (String) -> Unit,
    ): RawTranscript {
        var attempt = 0
        while (true) {
            val result: HttpResult? = try {
                execute(buildRequest(file, model, language, prompt))
            } catch (e: IOException) {
                null
            }

            if (result != null && result.code in 200..299) return parse(result.body)

            if (result != null) {
                val msg = errorMessage(result.body) ?: "HTTP ${result.code}"
                when {
                    result.code == 401 -> throw ApiException("API key Groq tidak valid. Periksa di Pengaturan.")
                    result.code == 413 -> throw ApiException("Potongan audio terlalu besar. Kecilkan \"Panjang potongan audio\" di Pengaturan.")
                    result.code != 429 && result.code < 500 -> throw ApiException("Groq menolak permintaan: $msg")
                }
            }

            attempt++
            if (attempt > MAX_RETRY) {
                throw ApiException(
                    if (result == null) "Koneksi internet gagal berkali-kali. Coba lagi nanti."
                    else "Groq masih sibuk/terbatas: ${errorMessage(result.body) ?: "HTTP ${result.code}"}"
                )
            }
            val waitMs = result?.retryAfter?.toDoubleOrNull()?.let { (it * 1000).toLong() + 500 }
                ?: (3000L shl attempt)
            val reason = when {
                result == null -> "Koneksi terputus"
                result?.code == 429 -> "Batas pemakaian Groq tercapai"
                else -> "Server Groq sibuk"
            }
            countdown(waitMs.coerceAtMost(MAX_WAIT_MS), reason, onStatus)
        }
    }

    private suspend fun countdown(ms: Long, reason: String, onStatus: (String) -> Unit) {
        var left = ms
        while (left > 0) {
            val sec = (left + 999) / 1000
            val text = if (sec >= 60) "${sec / 60} mnt ${sec % 60} dtk" else "$sec dtk"
            onStatus("$reason, mencoba lagi dalam $text…")
            val step = minOf(left, 1000L)
            delay(step)
            left -= step
        }
    }

    private fun buildRequest(file: File, model: String, language: String?, prompt: String?): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
        if (!language.isNullOrBlank()) body.addFormDataPart("language", language)
        if (!prompt.isNullOrBlank()) body.addFormDataPart("prompt", prompt)
        return Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body.build())
            .build()
    }

    private suspend fun execute(request: Request): HttpResult {
        val response = http.newCall(request).await()
        return response.use { r -> HttpResult(r.code, r.body?.string().orEmpty(), r.header("retry-after")) }
    }

    private fun parse(body: String): RawTranscript {
        val o = JSONObject(body)
        val words = ArrayList<Word>()
        o.optJSONArray("words")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                words += Word(w.optString("word"), w.optDouble("start", 0.0), w.optDouble("end", 0.0))
            }
        }
        val segments = ArrayList<Segment>()
        o.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                segments += Segment(
                    start = s.optDouble("start", 0.0),
                    end = s.optDouble("end", 0.0),
                    text = s.optString("text"),
                    avgLogprob = s.optDouble("avg_logprob", 0.0),
                    noSpeechProb = s.optDouble("no_speech_prob", 0.0),
                    compressionRatio = s.optDouble("compression_ratio", 1.0),
                )
            }
        }
        return RawTranscript(o.optString("text"), segments, words)
    }

    private fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MAX_RETRY = 6
        private const val MAX_WAIT_MS = 20 * 60 * 1000L
    }
}
