package com.audiosrt.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Ubah sample rate secara streaming (dipanggil per blok).
 * Saat menurunkan rate, sinyal difilter low-pass orde 4 dulu agar tidak terjadi aliasing
 * (suara "kresek" yang bisa menurunkan akurasi pengenalan).
 */
class Resampler(inRate: Int, outRate: Int) {

    private val ratio = inRate.toDouble() / outRate
    private val filters: List<Biquad> =
        if (inRate > outRate) {
            val cutoff = min(outRate * 0.45, inRate * 0.45)
            listOf(Biquad.lowPass(inRate, cutoff, 0.5412), Biquad.lowPass(inRate, cutoff, 1.3066))
        } else emptyList()

    private var pos = 0.0     // posisi sampel input berikutnya, relatif terhadap awal blok
    private var prev = 0f     // sampel terakhir blok sebelumnya (indeks -1)

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        val x = input.copyOf()
        for (f in filters) f.process(x)
        val n = x.size
        val out = FloatArray(((n - pos) / ratio).toInt() + 2)
        var count = 0
        while (pos < n - 1) {
            val i = floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else x[i]
            val b = x[i + 1]
            out[count++] = a + (b - a) * frac
            pos += ratio
        }
        prev = x[n - 1]
        pos -= n
        return out.copyOf(count)
    }

    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(buf: FloatArray) {
            for (i in buf.indices) {
                val x = buf[i].toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                buf[i] = y.toFloat()
            }
        }

        companion object {
            fun lowPass(sampleRate: Int, cutoff: Double, q: Double): Biquad {
                val w0 = 2 * PI * cutoff / sampleRate
                val alpha = sin(w0) / (2 * q)
                val cosw = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 - cosw) / 2 / a0,
                    b1 = (1 - cosw) / a0,
                    b2 = (1 - cosw) / 2 / a0,
                    a1 = -2 * cosw / a0,
                    a2 = (1 - alpha) / a0,
                )
            }
        }
    }
}
