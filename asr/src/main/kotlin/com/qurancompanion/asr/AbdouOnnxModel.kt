package com.qurancompanion.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Acoustic model: log-mel features -> CTC log-posteriors. Implementations need not be thread-safe. */
interface CtcAcousticModel : AutoCloseable {
    val vocabSize: Int
    /** Output frames for [featureFrames] input frames (`((T - 1) / 2 - 1) / 2` for this model). */
    fun outputFrames(featureFrames: Int): Int
    fun logProbs(features: Features): LogProbs
    override fun close() {}
}

/**
 * ONNX Runtime runner for Abdou/quran-asr-small (`model.int8.onnx`): inputs `x[1,T,80]` float32,
 * `x_lens[1]` int64; outputs `log_probs[1,T_out,V]`, `log_probs_len[1]`.
 *
 * Uses only the `ai.onnxruntime` Java API shared by `com.microsoft.onnxruntime:onnxruntime` (JVM)
 * and `onnxruntime-android`; :asr compiles against it but does not ship it (the app adds
 * onnxruntime-android). One instance = one OrtSession; calls are serialized by the caller.
 */
class AbdouOnnxModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    override val vocabSize: Int,
    val loadMs: Long,
) : CtcAcousticModel {

    override fun outputFrames(featureFrames: Int): Int = if (featureFrames < 7) 0 else ((featureFrames - 1) / 2 - 1) / 2

    override fun logProbs(features: Features): LogProbs {
        val t = features.numFrames
        if (outputFrames(t) <= 0) return LogProbs(0, vocabSize, FloatArray(0))
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features.data), longArrayOf(1, t.toLong(), features.numBins.toLong())).use { x ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.toLong())), longArrayOf(1)).use { xl ->
                session.run(mapOf("x" to x, "x_lens" to xl)).use { r ->
                    val lp = r.get("log_probs").get() as OnnxTensor
                    val lens = r.get("log_probs_len").get() as OnnxTensor
                    val shape = lp.info.shape
                    val v = shape[2].toInt()
                    check(v == vocabSize) { "vocab $v != $vocabSize" }
                    val n = minOf(lens.longBuffer.get(0).toInt(), shape[1].toInt())
                    val buf = lp.floatBuffer
                    val data = FloatArray(n * v)
                    buf.get(data, 0, data.size)
                    return LogProbs(n, v, data)
                }
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        fun options(threads: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        /** Loads from a file path (preferred on Android: avoids a 36 MB heap copy). */
        fun load(modelFile: File, vocabSize: Int, threads: Int = 2): AbdouOnnxModel {
            val env = OrtEnvironment.getEnvironment()
            val t0 = System.nanoTime()
            val s = options(threads).use { env.createSession(modelFile.absolutePath, it) }
            return AbdouOnnxModel(env, s, vocabSize, (System.nanoTime() - t0) / 1_000_000)
        }

        /** Loads from bytes (e.g. an asset read into memory). */
        fun load(modelBytes: ByteArray, vocabSize: Int, threads: Int = 2): AbdouOnnxModel {
            val env = OrtEnvironment.getEnvironment()
            val t0 = System.nanoTime()
            val s = options(threads).use { env.createSession(modelBytes, it) }
            return AbdouOnnxModel(env, s, vocabSize, (System.nanoTime() - t0) / 1_000_000)
        }
    }
}

/** Full offline pipeline for one utterance (features -> model -> greedy CTC). */
class OfflineTranscriber(
    val model: CtcAcousticModel,
    val vocab: Vocabulary,
    val fbank: FbankExtractor = FbankExtractor(),
) {
    private val decoder = CtcGreedyDecoder(vocab)

    fun transcribe(wave: FloatArray, offset: Int = 0, length: Int = wave.size - offset): List<DecodedWord> =
        decoder.decode(model.logProbs(fbank.compute(wave, offset, length)))

    fun transcribe(pcm: ShortArray): List<DecodedWord> = transcribe(FloatArray(pcm.size) { pcm[it] / 32768f })
}
