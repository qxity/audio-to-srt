package com.audiosrt.app.subtitle

import kotlin.math.max

/**
 * Membersihkan hasil Whisper satu potongan audio:
 * 1. Membuang segmen yang kemungkinan halusinasi (hening / teks berulang).
 * 2. Menempelkan tanda baca & huruf kapital dari teks segmen ke daftar kata
 *    (daftar kata Whisper sering tanpa tanda baca, padahal tanda baca penting
 *    untuk memotong subtitle per kalimat).
 */
object TranscriptProcessor {

    data class Processed(val words: List<Word>, val text: String)

    private val BREAK_CHARS = setOf('、', '。', '，', '！', '？', '!', '?', ',', '.', '；', ';')

    fun process(raw: RawTranscript, filterHallucination: Boolean): Processed {
        val dropped = if (filterHallucination) raw.segments.filter(::isHallucination) else emptyList()
        val kept = raw.segments.filterNot { it in dropped }
        val text = if (raw.segments.isEmpty()) raw.text.trim()
        else kept.joinToString(" ") { it.text.trim() }.trim()

        val words = raw.words
            .filter { w ->
                val mid = (w.start + w.end) / 2
                dropped.none { mid >= it.start && mid <= it.end }
            }
            .filter { it.text.isNotBlank() }
            .sortedBy { it.start }
            .map { w -> if (w.end < w.start + 0.02) w.copy(end = w.start + 0.02) else w }

        if (words.isEmpty()) return Processed(wordsFromSegments(kept), text)
        return Processed(PunctuationAligner.align(words, text), text)
    }

    /** Aturan yang sama dengan Whisper asli: tidak ada suara + keyakinan rendah, atau teks berulang-ulang. */
    fun isHallucination(s: Segment): Boolean =
        s.text.isBlank() ||
            (s.noSpeechProb > 0.6 && s.avgLogprob < -1.0) ||
            (s.compressionRatio > 2.4 && s.avgLogprob < -0.7)

    /** Bahasa berspasi (Indonesia, Inggris) vs tanpa spasi (Jepang, Mandarin). */
    fun isSpacedText(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        if (letters == 0) return true
        val spaces = t.count { it == ' ' }
        return spaces * 15 >= letters
    }

    /** Cadangan jika API tidak mengirim timestamp per kata: bagi waktu segmen sesuai panjang kata. */
    fun wordsFromSegments(segments: List<Segment>): List<Word> {
        val out = ArrayList<Word>()
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            val spaced = isSpacedText(t)
            val pieces = if (spaced) t.split(Regex("\\s+")).filter { it.isNotEmpty() } else splitUnspaced(t)
            val total = pieces.sumOf { it.length }.coerceAtLeast(1)
            val dur = max(0.05, s.end - s.start)
            var acc = 0
            for (p in pieces) {
                val st = s.start + dur * acc / total
                acc += p.length
                val en = s.start + dur * acc / total
                out += Word(p, st, en, spaceAfter = spaced)
            }
        }
        return out
    }

    private fun splitUnspaced(t: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (i in t.indices) {
            val c = t[i]
            if (c.isWhitespace()) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                continue
            }
            sb.append(c)
            val next = t.getOrNull(i + 1)
            val nextIsPunct = next != null && !next.isLetterOrDigit() && !next.isWhitespace()
            if (c in BREAK_CHARS || (sb.length >= 8 && !nextIsPunct)) {
                out += sb.toString(); sb.clear()
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}

object PunctuationAligner {
    private const val NEAR_WINDOW = 60
    private const val FAR_WINDOW = 400

    fun align(words: List<Word>, fullText: String): List<Word> {
        if (words.isEmpty()) return words
        if (fullText.isBlank()) return words.map { it.copy(text = it.text.trim()) }

        // Teks dinormalisasi (hanya huruf/angka, huruf kecil) + peta ke indeks asli.
        val norm = StringBuilder(fullText.length)
        val map = ArrayList<Int>(fullText.length)
        fullText.forEachIndexed { i, c ->
            if (c.isLetterOrDigit()) { norm.append(c.lowercaseChar()); map.add(i) }
        }
        val spaced = TranscriptProcessor.isSpacedText(fullText)

        val out = ArrayList<Word>(words.size)
        var cursor = 0
        var prevK = 0
        var lastMatched = true
        var pendingLead = ""

        for (i in words.indices) {
            val w = words[i]
            val nw = normalize(w.text)
            var pos = if (nw.isEmpty()) -1 else find(norm, nw, cursor, NEAR_WINDOW)
            if (pos < 0 && nw.length >= 4) {
                val far = find(norm, nw, cursor, FAR_WINDOW)
                // Lompatan jauh hanya diterima kalau kata berikutnya juga cocok tepat sesudahnya.
                val nextNorm = words.getOrNull(i + 1)?.let { normalize(it.text) }.orEmpty()
                if (far >= 0 && (nextNorm.isEmpty() || find(norm, nextNorm, far + nw.length, 20) >= 0)) pos = far
            }

            if (pos < 0) {
                val t = w.text.trim()
                if (nw.isEmpty()) {
                    if (t.isNotEmpty() && out.isNotEmpty()) {
                        val last = out.removeAt(out.lastIndex)
                        out += last.copy(text = last.text + t)
                    }
                    continue
                }
                out += w.copy(text = pendingLead + t, spaceAfter = spaced)
                pendingLead = ""
                lastMatched = false
                continue
            }

            val startIdx = map[pos]
            val endIdx = map[pos + nw.length - 1]
            var skipped = if (lastMatched && prevK < startIdx) fullText.substring(prevK, startIdx) else ""
            if (skipped.length > 80) skipped = ""

            var k = endIdx + 1
            while (k < fullText.length && !fullText[k].isLetterOrDigit()) k++
            val tail = fullText.substring(endIdx + 1, k)
            val firstWs = tail.indexOfFirst { it.isWhitespace() }
            val trailing: String
            val spaceAfter: Boolean
            val nextLead: String
            when {
                k >= fullText.length -> { trailing = tail.trim(); spaceAfter = spaced; nextLead = "" }
                firstWs < 0 -> { trailing = tail; spaceAfter = false; nextLead = "" }
                else -> {
                    trailing = tail.substring(0, firstWs)
                    spaceAfter = true
                    nextLead = tail.substring(tail.indexOfLast { it.isWhitespace() } + 1)
                }
            }
            val prefix = collapseWs(pendingLead + skipped).trimStart()
            val core = fullText.substring(startIdx, endIdx + 1)
            out += w.copy(text = prefix + core + trailing, spaceAfter = spaceAfter)
            pendingLead = nextLead
            cursor = pos + nw.length
            prevK = k
            lastMatched = true
        }

        // Sisa teks di akhir yang tidak punya timestamp -> tempel ke kata terakhir.
        if (lastMatched && prevK < fullText.length && out.isNotEmpty()) {
            val rest = collapseWs(pendingLead + fullText.substring(prevK)).trim()
            if (rest.isNotEmpty() && rest.length <= 80) {
                val last = out.removeAt(out.lastIndex)
                out += last.copy(text = last.text + (if (spaced) " " else "") + rest)
            }
        }
        return out
    }

    fun normalize(s: String): String = buildString {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun collapseWs(s: String) = s.replace(Regex("\\s+"), " ")

    private fun find(norm: CharSequence, nw: String, from: Int, window: Int): Int {
        val last = minOf(from + window, norm.length - nw.length)
        var p = from
        while (p <= last) {
            var ok = true
            for (j in nw.indices) if (norm[p + j] != nw[j]) { ok = false; break }
            if (ok) return p
            p++
        }
        return -1
    }
}
