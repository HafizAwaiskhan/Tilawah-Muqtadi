package com.qurancompanion.asr.dbg
import com.qurancompanion.asr.*
import ai.onnxruntime.*
import org.junit.Test
import java.nio.*
class DbgTest {
  @Test fun dbg() {
    val c = TestData.clips().first { it.id == "Hani_Rifai_64kbps__001002" }
    val f = Features(c.goldenFbank().size/80, 80, c.goldenFbank())
    val env = OrtEnvironment.getEnvironment()
    println("DBG ort " + env.version)
    for (opt in OrtSession.SessionOptions.OptLevel.values()) for (th in listOf(1, 2)) {
      val so = OrtSession.SessionOptions(); so.setOptimizationLevel(opt); so.setIntraOpNumThreads(th)
      val s = env.createSession(TestData.modelFile.path, so)
      val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(f.data), longArrayOf(1, f.numFrames.toLong(), 80))
      val xl = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(f.numFrames.toLong())), longArrayOf(1))
      val r = s.run(mapOf("x" to x, "x_lens" to xl))
      val lp = (r.get("log_probs").get() as OnnxTensor).floatBuffer
      val n = lp.remaining() / 31
      val ids = (0 until n).map { t -> (0 until 31).maxByOrNull { lp.get(t*31+it) }!! }.filter { it != 0 }
      println("DBG $opt $th ${ids.joinToString(" ")}")
    }
  }
}
