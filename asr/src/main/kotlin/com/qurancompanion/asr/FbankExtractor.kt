package com.qurancompanion.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Log-mel features, row-major `[numFrames, numBins]`. */
class Features(val numFrames: Int, val numBins: Int, val data: FloatArray) {
    init { require(data.size == numFrames * numBins) }
    operator fun get(frame: Int, bin: Int): Float = data[frame * numBins + bin]
}

/**
 * 80-bin Kaldi fbank exactly as `tools/model_eval/asr_core.fbank` / kaldi-native-fbank defaults used
 * by sherpa-onnx (docs/MODEL_EVALUATION.md §3.2): 16 kHz, 25 ms povey window, 10 ms shift, dither 0,
 * per-frame DC removal, pre-emphasis 0.97, 512-point FFT power spectrum (bins 0..255), 80 triangular
 * Kaldi-mel filters 20..7600 Hz, `ln(max(mel, FLT_EPSILON))`, snip_edges=false (frames centred,
 * samples reflected at both edges, `numFrames = (n + 80) / 160`). No energy, no CMVN.
 *
 * Stateless after construction and thread-safe for concurrent [compute] calls (scratch is per call).
 */
class FbankExtractor {
    val numBins = NUM_BINS

    /** Number of output frames for [numSamples] input samples. */
    fun numFrames(numSamples: Int): Int = if (numSamples <= 0) 0 else (numSamples + SHIFT / 2) / SHIFT

    /** [wave]: mono float samples in [-1, 1] at 16 kHz. */
    fun compute(wave: FloatArray, offset: Int = 0, length: Int = wave.size - offset): Features {
        val n = length
        val t = numFrames(n)
        val out = FloatArray(t * NUM_BINS)
        val frame = DoubleArray(LENGTH)
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val power = DoubleArray(FFT / 2)
        for (f in 0 until t) {
            val start = f * SHIFT + SHIFT / 2 - LENGTH / 2
            var sum = 0.0
            for (k in 0 until LENGTH) {
                var j = start + k
                if (j < 0) j = -j - 1
                if (j >= n) j = 2 * n - 1 - j
                if (j < 0) j = 0 else if (j > n - 1) j = n - 1
                val v = wave[offset + j].toDouble()
                frame[k] = v
                sum += v
            }
            val mean = sum / LENGTH
            for (k in 0 until LENGTH) frame[k] -= mean
            // pre-emphasis, backwards so frame[k-1] is still the un-emphasised sample
            for (k in LENGTH - 1 downTo 1) frame[k] -= PREEMPH * frame[k - 1]
            frame[0] *= (1.0 - PREEMPH)
            for (k in 0 until LENGTH) { re[k] = frame[k] * WINDOW[k]; im[k] = 0.0 }
            for (k in LENGTH until FFT) { re[k] = 0.0; im[k] = 0.0 }
            fft(re, im)
            for (k in 0 until FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            val base = f * NUM_BINS
            for (b in 0 until NUM_BINS) {
                val w = BANK_WEIGHTS[b]
                val lo = BANK_START[b]
                var acc = 0.0
                for (i in w.indices) acc += power[lo + i] * w[i]
                out[base + b] = ln(max(acc, EPS)).toFloat()
            }
        }
        return Features(t, NUM_BINS, out)
    }

    /** PCM16 convenience: samples / 32768. */
    fun compute(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Features {
        val w = FloatArray(length) { pcm[offset + it] / 32768f }
        return compute(w)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val NUM_BINS = 80
        const val SHIFT = 160
        const val LENGTH = 400
        const val FFT = 512
        const val PREEMPH = 0.97
        const val LOW_HZ = 20.0
        const val HIGH_HZ = SAMPLE_RATE / 2 - 400.0
        /** float32 machine epsilon (FLT_EPSILON), the Kaldi log floor. */
        val EPS = Math.ulp(1.0f).toDouble()

        private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

        /** Povey window, rounded to float32 like the reference. */
        private val WINDOW = DoubleArray(LENGTH) {
            (0.5 - 0.5 * cos(2 * PI * it / (LENGTH - 1))).pow(0.85).toFloat().toDouble()
        }

        private val BANK_START = IntArray(NUM_BINS)
        private val BANK_WEIGHTS: Array<DoubleArray>

        init {
            val bins = FFT / 2
            val width = SAMPLE_RATE.toDouble() / FFT
            val lo = mel(LOW_HZ); val hi = mel(HIGH_HZ)
            val delta = (hi - lo) / (NUM_BINS + 1)
            BANK_WEIGHTS = Array(NUM_BINS) { b ->
                val left = lo + b * delta; val center = lo + (b + 1) * delta; val right = lo + (b + 2) * delta
                val w = DoubleArray(bins)
                var first = -1; var last = -1
                for (i in 0 until bins) {
                    val m = mel(width * i)
                    if (m > left && m < right) {
                        val v = if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)
                        w[i] = v.toFloat().toDouble() // reference stores the banks as float32
                        if (first < 0) first = i
                        last = i
                    }
                }
                if (first < 0) { BANK_START[b] = 0; DoubleArray(0) } else { BANK_START[b] = first; w.copyOfRange(first, last + 1) }
            }
        }

        // ---- radix-2 complex FFT of size 512 (in place), precomputed twiddles and bit reversal
        private val COS = DoubleArray(FFT / 2) { cos(-2 * PI * it / FFT) }
        private val SIN = DoubleArray(FFT / 2) { sin(-2 * PI * it / FFT) }
        private val REV = IntArray(FFT).also { r ->
            val bits = Integer.numberOfTrailingZeros(FFT)
            for (i in 0 until FFT) r[i] = Integer.reverse(i) ushr (32 - bits)
        }

        private fun fft(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until FFT) {
                val j = REV[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= FFT) {
                val half = size / 2
                val step = FFT / size
                var start = 0
                while (start < FFT) {
                    var k = 0
                    for (j in start until start + half) {
                        val wr = COS[k]; val wi = SIN[k]
                        val l = j + half
                        val tr = re[l] * wr - im[l] * wi
                        val ti = re[l] * wi + im[l] * wr
                        re[l] = re[j] - tr; im[l] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
        }
    }
}
