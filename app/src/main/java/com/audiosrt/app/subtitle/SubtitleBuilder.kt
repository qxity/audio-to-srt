package com.audiosrt.app.subtitle

import kotlin.math.max
import kotlin.math.min

/**
 * Mengubah daftar kata bertimestamp menjadi baris subtitle yang enak dibaca:
 * - pecah di akhir kalimat, di jeda bicara, atau saat melebihi batas karakter/durasi
 * - titik potong dipilih cerdas (koma, jeda, hindari berakhir di "dan/yang/the")
 * - maksimal 2 baris, dibagi seimbang
 * - durasi minimum, kecepatan baca (CPS), dan tanpa tumpang tindih
 */
class SubtitleBuilder(private val cfg: BuilderConfig) {

    private val maxTotal = cfg.maxCharsPerLine * cfg.maxLines

    fun build(words: List<Word>): List<Cue> {
        if (words.isEmpty()) return emptyList()
        val raw = group(words).map { g -> Cue(g.first().start, g.last().end, wrap(g)) }
        return fixTiming(raw)
    }

    private fun group(words: List<Word>): List<List<Word>> {
        val result = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for (i in words.indices) {
            val w = words[i]
            if (cur.isNotEmpty() && w.start - cur.last().end >= cfg.pauseSplit) {
                result.add(cur); cur = ArrayList()
            }
            cur.add(w)
            while (cur.size > 1 && !fits(cur)) {
                val b = bestBreak(cur)
                result.add(ArrayList(cur.subList(0, b + 1)))
                cur = ArrayList(cur.subList(b + 1, cur.size))
            }
            if (isSentenceEnd(w.text)) {
                val next = words.getOrNull(i + 1)
                // Kalimat sangat pendek yang langsung disambung boleh digabung ("Ya. Saya setuju.")
                val tinyAndRushed = joinedLen(cur, 0, cur.lastIndex) < 12 &&
                    next != null && next.start - w.end < 0.3
                if (!tinyAndRushed) { result.add(cur); cur = ArrayList() }
            }
        }
        if (cur.isNotEmpty()) result.add(cur)
        return result
    }

    private fun fits(ws: List<Word>): Boolean {
        if (ws.last().end - ws.first().start > cfg.maxDuration) return false
        return maxLineLen(ws) <= cfg.maxCharsPerLine
    }

    private fun bestBreak(cur: List<Word>): Int {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (b in 0 until cur.lastIndex) {
            val prefix = cur.subList(0, b + 1)
            if (!fits(prefix)) break
            val len = joinedLen(prefix, 0, b).toDouble()
            // Panjang tetap dihargai, tapi titik potong alami (tanda baca/jeda) lebih diutamakan.
            var score = 0.6 * len / maxTotal
            if (len >= maxTotal * 0.35) {
                when {
                    isSentenceEnd(cur[b].text) -> score += 0.6
                    isClauseEnd(cur[b].text) -> score += 0.35
                }
                score += min(cur[b + 1].start - cur[b].end, 0.5)
            }
            if (isWeakEnding(cur[b].text)) score -= 0.3
            if (score > bestScore) { bestScore = score; best = b }
        }
        return best
    }

    private fun maxLineLen(ws: List<Word>): Int {
        val k = lineSplit(ws)
        return if (k < 0) joinedLen(ws, 0, ws.lastIndex)
        else max(joinedLen(ws, 0, k), joinedLen(ws, k + 1, ws.lastIndex))
    }

    /** -1 = cukup satu baris; selain itu indeks kata terakhir di baris pertama. */
    private fun lineSplit(ws: List<Word>): Int {
        val n = ws.size
        val textPre = IntArray(n + 1)
        val spacePre = IntArray(n + 1)
        for (i in 0 until n) {
            textPre[i + 1] = textPre[i] + ws[i].text.length
            spacePre[i + 1] = spacePre[i] + if (ws[i].spaceAfter) 1 else 0
        }
        fun len(a: Int, b: Int) = textPre[b + 1] - textPre[a] + spacePre[b] - spacePre[a]

        val total = len(0, n - 1)
        if (cfg.maxLines < 2 || n < 2 || total <= cfg.maxCharsPerLine) return -1
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (k in 0 until n - 1) {
            val l1 = len(0, k)
            val l2 = len(k + 1, n - 1)
            var score = max(l1, l2).toDouble()
            if (max(l1, l2) > cfg.maxCharsPerLine) score += 1000.0
            if (isSentenceEnd(ws[k].text) || isClauseEnd(ws[k].text)) score -= 6.0
            if (isWeakEnding(ws[k].text)) score += 4.0
            if (l1 > l2) score += 0.5 // baris atas sedikit lebih pendek lebih nyaman dibaca
            if (score < bestScore) { bestScore = score; best = k }
        }
        return best
    }

    private fun wrap(ws: List<Word>): String {
        val k = lineSplit(ws)
        return if (k < 0) join(ws, 0, ws.lastIndex)
        else join(ws, 0, k) + "\n" + join(ws, k + 1, ws.lastIndex)
    }

    private fun fixTiming(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>(cues.size)
        for (i in cues.indices) {
            val c = cues[i]
            var start = c.start
            if (out.isNotEmpty()) start = max(start, out.last().end + cfg.minGap)
            val nextStart = cues.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
            val chars = c.text.replace("\n", "").length
            val readTime = chars / cfg.maxCps
            val wanted = max(c.end + cfg.tailPad, start + max(cfg.minDuration, readTime))
            var end = min(wanted, max(c.end, start + cfg.maxDuration))
            end = min(end, nextStart - cfg.minGap)
            if (end < start + 0.3) end = start + 0.3
            out.add(Cue(start, end, c.text))
        }
        return out
    }

    companion object {
        private val CLOSERS = charArrayOf('"', '\'', ')', ']', '”', '’', '」', '』', '）', '»')
        private const val SENTENCE_END = ".?!…。？！"
        private const val CLAUSE_END = ",;:、，；：—–"
        private val WEAK = setOf(
            "dan", "yang", "di", "ke", "dari", "untuk", "dengan", "atau", "tetapi", "tapi",
            "karena", "pada", "para", "si", "sang", "akan", "adalah", "bahwa", "oleh",
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "with",
            "but", "that", "my", "your", "his", "her", "their", "our", "is", "are",
        )

        private fun core(t: String) = t.trimEnd(*CLOSERS).trimEnd()

        fun isSentenceEnd(t: String): Boolean = core(t).let { it.isNotEmpty() && it.last() in SENTENCE_END }
        fun isClauseEnd(t: String): Boolean = core(t).let { it.isNotEmpty() && it.last() in CLAUSE_END }
        fun isWeakEnding(t: String): Boolean =
            t.isNotEmpty() && t.last().isLetter() && t.lowercase() in WEAK

        fun joinedLen(ws: List<Word>, a: Int, b: Int): Int {
            var n = 0
            for (i in a..b) {
                n += ws[i].text.length
                if (i < b && ws[i].spaceAfter) n++
            }
            return n
        }

        fun join(ws: List<Word>, a: Int, b: Int): String = buildString {
            for (i in a..b) {
                append(ws[i].text)
                if (i < b && ws[i].spaceAfter) append(' ')
            }
        }
    }
}
