package com.audiosrt.app.data

import android.content.Context
import com.audiosrt.app.subtitle.BuilderConfig

data class Settings(
    val apiKey: String = "",
    val model: String = MODEL_ACCURATE,
    val language: String = "id",
    val prompt: String = "",
    val maxCharsPerLine: Int = 42,
    val maxLines: Int = 2,
    val maxCueSec: Float = 6f,
    val minCueSec: Float = 1f,
    val pauseSplitSec: Float = 0.7f,
    val maxCps: Int = 17,
    val chunkMinutes: Int = 10,
    val filterHallucination: Boolean = true,
) {
    fun toBuilderConfig() = BuilderConfig(
        maxCharsPerLine = maxCharsPerLine,
        maxLines = maxLines,
        maxDuration = maxCueSec.toDouble(),
        minDuration = minCueSec.toDouble(),
        pauseSplit = pauseSplitSec.toDouble(),
        maxCps = maxCps.toDouble(),
    )

    companion object {
        const val MODEL_ACCURATE = "whisper-large-v3"
        const val MODEL_FAST = "whisper-large-v3-turbo"

        /** Kode ISO-639-1 → nama. "" = deteksi otomatis. */
        val LANGUAGES = listOf(
            "id" to "Indonesia",
            "en" to "Inggris",
            "ja" to "Jepang",
            "ms" to "Melayu",
            "ar" to "Arab",
            "ko" to "Korea",
            "zh" to "Mandarin",
            "su" to "Sunda",
            "" to "Deteksi otomatis",
        )
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            apiKey = prefs.getString("apiKey", d.apiKey) ?: "",
            model = prefs.getString("model", d.model) ?: d.model,
            language = prefs.getString("language", d.language) ?: d.language,
            prompt = prefs.getString("prompt", d.prompt) ?: "",
            maxCharsPerLine = prefs.getInt("maxCharsPerLine", d.maxCharsPerLine),
            maxLines = prefs.getInt("maxLines", d.maxLines),
            maxCueSec = prefs.getFloat("maxCueSec", d.maxCueSec),
            minCueSec = prefs.getFloat("minCueSec", d.minCueSec),
            pauseSplitSec = prefs.getFloat("pauseSplitSec", d.pauseSplitSec),
            maxCps = prefs.getInt("maxCps", d.maxCps),
            chunkMinutes = prefs.getInt("chunkMinutes", d.chunkMinutes),
            filterHallucination = prefs.getBoolean("filterHallucination", d.filterHallucination),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model)
            .putString("language", s.language)
            .putString("prompt", s.prompt)
            .putInt("maxCharsPerLine", s.maxCharsPerLine)
            .putInt("maxLines", s.maxLines)
            .putFloat("maxCueSec", s.maxCueSec)
            .putFloat("minCueSec", s.minCueSec)
            .putFloat("pauseSplitSec", s.pauseSplitSec)
            .putInt("maxCps", s.maxCps)
            .putInt("chunkMinutes", s.chunkMinutes)
            .putBoolean("filterHallucination", s.filterHallucination)
            .apply()
    }
}
