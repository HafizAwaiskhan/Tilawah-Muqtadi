package com.qurancompanion.asr

import org.junit.Assume
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-audio test inputs. Nothing here is committed: run
 * `tools/model_eval/fetch_inputs.sh` then
 * `tools/model_eval/.venv/bin/python -I asr/tools/prepare_test_data.py`.
 * If the inputs are missing but the venv and audio exist, [requireClips] runs the script itself.
 */
object TestData {
    val root: File = File(System.getProperty("repo.root") ?: "..").absoluteFile
    val modelDir = File(root, "models/abdou-quran-asr-small")
    val modelFile = File(modelDir, "model.int8.onnx")
    val tokensFile = File(modelDir, "tokens.txt")
    val clipDir = File(root, "models/asr_test")
    val dbFile = File(root, "data/build/quran_ref_v1.db")

    class RefWord(val text: String, val startS: Double, val endS: Double)
    class Clip(val id: String, val golden: Boolean) {
        val surah: Int; val ayah: Int; val samples: Int
        val words: List<RefWord>
        init {
            val lines = File(clipDir, "$id.tsv").readLines(Charsets.UTF_8)
            val h = lines[0].split('\t')
            surah = h[1].toInt(); ayah = h[2].toInt(); samples = h[3].toInt()
            words = lines.drop(1).filter { it.isNotBlank() }.map { val p = it.split('\t'); RefWord(p[0], p[1].toDouble(), p[2].toDouble()) }
        }
        fun pcm(): ShortArray = shorts(File(clipDir, "$id.pcm").readBytes())
        fun goldenFbank(): FloatArray = floats(File(clipDir, "$id.fbank.f32").readBytes())
        val seconds get() = samples / 16000.0
    }

    fun shorts(b: ByteArray): ShortArray {
        val s = ShortArray(b.size / 2); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); return s
    }
    fun floats(b: ByteArray): FloatArray {
        val f = FloatArray(b.size / 4); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f); return f
    }

    private var tried = false

    fun clips(): List<Clip> {
        val m = File(clipDir, "manifest.txt")
        if (!m.exists() && !tried) {
            tried = true
            val py = File(root, "tools/model_eval/.venv/bin/python")
            if (py.exists() && modelFile.exists() && File(root, "models/audio/everyayah").isDirectory) {
                val p = ProcessBuilder(py.path, "-I", File(root, "asr/tools/prepare_test_data.py").path)
                    .directory(root).redirectErrorStream(true).start()
                p.inputStream.readAllBytes(); p.waitFor()
            }
        }
        if (!m.exists()) return emptyList()
        return m.readLines().filter { it.isNotBlank() }.map { val p = it.split('\t'); Clip(p[0], p[1] == "1") }
    }

    fun requireModel() = Assume.assumeTrue("model files absent (run tools/model_eval/fetch_inputs.sh)", modelFile.exists() && tokensFile.exists())
    fun requireClips(): List<Clip> { val c = clips(); Assume.assumeTrue("real-audio test inputs absent (asr/tools/prepare_test_data.py)", c.isNotEmpty()); return c }
    fun requireDb() = Assume.assumeTrue("reference DB absent", dbFile.exists())

    val vocab: Vocabulary by lazy { Vocabulary.load(tokensFile) }
    val model: AbdouOnnxModel by lazy { AbdouOnnxModel.load(modelFile, vocab.size, threads = 2) }
}
