package com.audiosrt.app.subtitle

/** Satu kata dengan timestamp (detik). spaceAfter = perlu spasi sebelum kata berikutnya. */
data class Word(
    val text: String,
    val start: Double,
    val end: Double,
    val spaceAfter: Boolean = true,
)

/** Segmen mentah dari Whisper (dipakai untuk filter halusinasi & tanda baca). */
data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val avgLogprob: Double = 0.0,
    val noSpeechProb: Double = 0.0,
    val compressionRatio: Double = 1.0,
)

/** Hasil mentah satu potongan audio dari API. */
data class RawTranscript(
    val text: String,
    val segments: List<Segment>,
    val words: List<Word>,
)

/** Satu baris subtitle. text boleh berisi '\n' (maks 2 baris). */
data class Cue(
    val start: Double,
    val end: Double,
    val text: String,
)

data class BuilderConfig(
    val maxCharsPerLine: Int = 42,
    val maxLines: Int = 2,
    val maxDuration: Double = 6.0,
    val minDuration: Double = 1.0,
    val pauseSplit: Double = 0.7,
    val maxCps: Double = 17.0,
    val minGap: Double = 0.08,
    val tailPad: Double = 0.25,
)
