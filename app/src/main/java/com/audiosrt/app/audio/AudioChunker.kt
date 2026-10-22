package com.audiosrt.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Memotong PCM 16 kHz mono menjadi beberapa WAV agar muat batas upload API (25 MB).
 * Titik potong dicari di bagian paling hening, jadi tidak ada kata yang terpotong di tengah.
 */
object AudioChunker {
    const val SAMPLE_RATE = 16_000

    data class Chunk(val file: File, val startSec: Double, val durationSec: Double)

    fun split(pcm: File, outDir: File, maxChunkSec: Int, searchSec: Int = 45): List<Chunk> {
        val total = pcm.length() / 2
        val maxS = maxChunkSec.toLong() * SAMPLE_RATE
        val chunks = ArrayList<Chunk>()
        RandomAccessFile(pcm, "r").use { raf ->
            var start = 0L
            var idx = 0
            while (start < total) {
                var end = if (total - start <= maxS) total else {
                    val winStart = max(start + maxS - searchSec.toLong() * SAMPLE_RATE, start + SAMPLE_RATE)
                    findQuietPoint(raf, winStart, start + maxS)
                }
                // Jangan sisakan potongan super pendek (< 2 dtk) — Whisper suka berhalusinasi di situ.
                if (total - end < 2L * SAMPLE_RATE) end = total
                val f = File(outDir, "chunk_%03d.wav".format(idx++))
                writeChunk(raf, start, end, f)
                chunks += Chunk(f, start.toDouble() / SAMPLE_RATE, (end - start).toDouble() / SAMPLE_RATE)
                start = end
            }
        }
        return chunks
    }

    private fun findQuietPoint(raf: RandomAccessFile, from: Long, to: Long): Long {
        val n = (to - from).toInt()
        val bytes = ByteArray(n * 2)
        raf.seek(from * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frame = 320 // 20 ms
        val frames = n / frame
        val win = 10   // dirata-rata 200 ms agar yang dipilih benar-benar jeda, bukan sela antar suku kata
        if (frames < win + 2) return to
        val energy = DoubleArray(frames)
        for (f in 0 until frames) {
            var e = 0.0
            val base = f * frame
            for (k in 0 until frame) {
                val v = sb.get(base + k).toDouble()
                e += v * v
            }
            energy[f] = e
        }
        var best = frames - 1
        var bestE = Double.MAX_VALUE
        var sum = 0.0
        for (f in 0 until frames) {
            sum += energy[f]
            if (f >= win) sum -= energy[f - win]
            if (f >= win - 1 && sum <= bestE) { bestE = sum; best = f - win / 2 }
        }
        return from + best.toLong() * frame + frame / 2
    }

    private fun writeChunk(raf: RandomAccessFile, start: Long, end: Long, out: File) {
        val n = (end - start).toInt()
        val bytes = ByteArray(n * 2)
        raf.seek(start * 2)
        raf.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Normalisasi puncak: rekaman pelan dikeraskan (maks 8x) agar Whisper lebih jelas mendengar.
        var peak = 1
        for (i in 0 until n) {
            val v = abs(bb.getShort(i * 2).toInt())
            if (v > peak) peak = v
        }
        val gain = min(8.0, 29_491.0 / peak)
        if (gain > 1.2) {
            for (i in 0 until n) {
                val v = (bb.getShort(i * 2) * gain).roundToInt().coerceIn(-32768, 32767)
                bb.putShort(i * 2, v.toShort())
            }
        }
        WavWriter.write(out, bytes, SAMPLE_RATE)
    }
}
