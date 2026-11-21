package com.qurancompanion.debug

import com.qurancompanion.core.audio.AudioCapture
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.settings.DeveloperSettings
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.QuranReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One scripted recognizer output: [text] spoken from [startMs] for [durationMs] (simulated audio time). */
data class SimWord(val text: String, val startMs: Long, val durationMs: Long, val note: String? = null)

/**
 * Builds the simulated recitation from the reference (never from literals): the match keys of
 * [DeveloperSettings.surah]:[DeveloperSettings.fromAyah]..[DeveloperSettings.toAyah], with
 * breathing gaps, a longer pause at ayah ends, optionally one long "forgot the next word" pause
 * in the middle and two injected errors (a wrong word followed by a self-correction restart two
 * words back, and a skipped word).
 */
object SimulatorScript {
    const val START_SILENCE_MS = 1_000L
    const val GAP_MS = 150L
    const val AYAH_END_MS = 900L
    const val FORGET_MS = 10_000L

    fun wordRange(ref: QuranReference, cfg: DeveloperSettings): IntRange? {
        val first = ref.ayahWords(cfg.surah, cfg.fromAyah) ?: return null
        var lastAyah = cfg.toAyah.coerceAtLeast(cfg.fromAyah)
        while (lastAyah > cfg.fromAyah && ref.ayahWords(cfg.surah, lastAyah) == null) lastAyah--
        val last = ref.ayahWords(cfg.surah, lastAyah) ?: first
        return first.first..last.last
    }

    fun build(ref: QuranReference, cfg: DeveloperSettings): List<SimWord> {
        val range = wordRange(ref, cfg) ?: return emptyList()
        val n = range.last - range.first + 1
        val wordMs = cfg.wordMs.coerceIn(150, 3_000).toLong()
        // Mid-ayah (not after an ayah end / waqf, where pause thresholds are doubled).
        val forgetAt = if (cfg.forgetPause && n >= 6) {
            ((range.first + n / 2)..range.last).firstOrNull { !ref.isPausePoint(it - 1) && !ref.isPausePoint(it) }
        } else null
        val wrongAt = if (cfg.injectErrors && n >= 10) range.first + maxOf(3, n / 4) else null
        val skipAt = if (cfg.injectErrors && n >= 12) range.first + (n * 3) / 4 else null
        val out = ArrayList<SimWord>()
        var t = START_SILENCE_MS
        fun say(id: Int, text: String = ref.word(id).matchKey, note: String? = null) {
            out += SimWord(text, t, wordMs, note)
            t += wordMs + GAP_MS
            if (ref.isAyahEnd(id)) t += AYAH_END_MS
        }
        for (id in range) {
            if (id == forgetAt) t += FORGET_MS
            if (id == skipAt) continue
            if (id == wrongAt) {
                // A distant word stands in for a misread word; then restart two words back.
                val wrong = (1 until ref.wordCount).asSequence()
                    .map { ref.word((id + ref.wordCount / 2 + it) % ref.wordCount).matchKey }
                    .first { it != ref.word(id).matchKey }
                out += SimWord(wrong, t, wordMs, "wrong word")
                t += wordMs + 600
                for (back in (id - 2).coerceAtLeast(range.first) until id) say(back, note = "self-correction")
            }
            say(id)
        }
        return out
    }
}

/**
 * Debug-only [QuranRecognitionEngine] that "recognises" the script built by [SimulatorScript].
 * Simulated time advances with the audio frames it receives (20 ms each), so it naturally
 * stops while the session is paused or a hint is playing (the gate stops the frames).
 * Emits a provisional token halfway through each word and the stable token at its end.
 */
class SimulatedRecognitionEngine(
    private val reference: EngineReferenceSource,
    private val settings: Flow<UserSettings>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : QuranRecognitionEngine {
    private val _readiness = MutableStateFlow<ModelReadiness>(ModelReadiness.Ready("simulated-recognizer", "debug"))
    override val readiness: StateFlow<ModelReadiness> = _readiness.asStateFlow()
    override val unit: TokenUnit = TokenUnit.WORD
    private val events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 64)
    private var job: Job? = null

    override suspend fun initialize(): ModelReadiness = _readiness.value

    override fun startListening(audio: Flow<ShortArray>) {
        job?.cancel()
        job = scope.launch {
            val ref = reference.get() ?: return@launch
            val script = SimulatorScript.build(ref, settings.first().developer)
            var simMs = 0L
            var next = 0
            var provisionalSent = false
            audio.collect { frame ->
                simMs += frame.size * 1000L / AudioCapture.SAMPLE_RATE
                while (next < script.size) {
                    val w = script[next]
                    val now = clock.elapsedRealtime()
                    if (!provisionalSent && simMs >= w.startMs + w.durationMs / 2) {
                        provisionalSent = true
                        events.emit(RecognitionEvent(listOf(Token(w.text, confidence = 0.6f)), isStable = false, timestampMs = now))
                    }
                    if (simMs < w.startMs + w.durationMs) break
                    events.emit(
                        RecognitionEvent(
                            listOf(Token(w.text, now - w.durationMs, now, confidence = 0.95f)),
                            isStable = true, timestampMs = now,
                        )
                    )
                    next++
                    provisionalSent = false
                }
            }
        }
    }

    override fun stopListening() {
        job?.cancel()
        job = null
    }

    override fun observeRecognition(): Flow<RecognitionEvent> = events.asSharedFlow()
}
