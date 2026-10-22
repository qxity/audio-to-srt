package com.audiosrt.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mendekode file audio/video apa pun yang didukung Android (mp3, m4a, aac, wav, ogg, opus,
 * flac, amr, mp4, mkv, webm, ...) menjadi PCM 16-bit, 16 kHz, mono — format asli Whisper.
 */
class AudioDecoder(private val context: Context) {

    /** @return jumlah sampel yang ditulis ke [out]. */
    suspend fun decodeToPcm16k(uri: Uri, out: File, onProgress: (Float) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (e: Exception) {
                    throw IllegalStateException("File tidak bisa dibuka atau formatnya tidak didukung.")
                }
                var track = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        track = i; format = f; break
                    }
                }
                if (track < 0 || format == null) throw IllegalStateException("Tidak ada trek audio di file ini.")
                extractor.selectTrack(track)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(format, null, null, 0)
                dec.start()

                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), AudioChunker.SAMPLE_RATE)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var written = 0L
                var lastReported = -1

                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                    while (!outputDone) {
                        ensureActive()
                        if (!inputDone) {
                            val inIdx = dec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = dec.getInputBuffer(inIdx)!!
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = dec.outputFormat
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            resampler = Resampler(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), AudioChunker.SAMPLE_RATE)
                        } else if (outIdx >= 0) {
                            if (info.size > 0) {
                                val buf = dec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val mono = toMonoFloat(buf.slice().order(ByteOrder.nativeOrder()), channels, encoding)
                                val res = resampler.process(mono)
                                writePcm16(os, res)
                                written += res.size
                                if (durationUs > 0) {
                                    val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                    if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                                }
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                written
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

    private fun toMonoFloat(buf: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
            ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val ch = channels.coerceAtLeast(1)
        val frames = buf.remaining() / (bytesPerSample * ch)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += readSample(buf, encoding)
            out[f] = sum / ch
        }
        return out
    }

    private fun readSample(buf: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_FLOAT -> buf.float
        ENCODING_PCM_32BIT -> buf.int / 2_147_483_648f
        ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() // bertanda
            ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
        }
        else -> buf.short / 32_768f
    }

    private fun writePcm16(os: OutputStream, samples: FloatArray) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        os.write(bytes)
    }

    private companion object {
        // Nilai konstanta AudioFormat (API 31) ditulis langsung agar aman di Android lama.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}
