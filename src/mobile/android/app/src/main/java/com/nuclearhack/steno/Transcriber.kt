package com.nuclearhack.steno

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import java.io.File

const val TAG = "STENO"

data class TranscribeResult(
    val utterances: List<Utterance>,
    val audioMs: Long,
    val decodeAsrMs: Long,
    val numSpeakers: Int = 1,
    val lang: String = "ru",
) {
    val rtf: Double get() = if (audioMs > 0) decodeAsrMs.toDouble() / audioMs else 0.0
}

/** Ожидаемый RTF диаризации — для оценочного прогресса (JNI-колбэк в AAR не работает). */
private const val EXPECTED_DIAR_RTF = 0.15

/**
 * Файл → декод 16k mono (во временный файл) → диаризация (pyannote-3.0 + titanet) →
 * ASR (GigaAM v3 transducer, RU) по сегментам диаризации. Модели — из [Models] (грузятся при старте).
 * speaker_id и таймкоды — из диаризации + [Recluster]. [numSpeakers] <= 0 — авто (2..6 спикеров).
 */
class Transcriber(private val context: Context) {

    fun run(uri: Uri, numSpeakers: Int = -1, lang: String = "ru", onProgress: (Float) -> Unit = {}): TranscribeResult {
        val t0 = SystemClock.elapsedRealtime()
        Models.await(context)
        val tModels = SystemClock.elapsedRealtime()
        if (tModels - t0 > 50) Log.i(TAG, "waited for models ${tModels - t0} ms")
        PcmStore(File(context.cacheDir, "pcm_${System.nanoTime()}.raw")).use { pcm ->
            AudioDecoder.decode(context, uri, { onProgress(it * 0.1f) }) { pcm.append(it) }
            pcm.finish()
            val audioMs = pcm.durationMs
            if (audioMs < 500) throw UserFacingException("В файле нет звука или запись слишком короткая")
            val tDecode = SystemClock.elapsedRealtime()
            Log.i(TAG, "decode ${tDecode - tModels} ms, audio $audioMs ms")

            val samples = pcm.readAll()
            val raw = diarize(samples, -1, audioMs) { onProgress(0.1f + it * 0.37f) }
            val tDiar = SystemClock.elapsedRealtime()
            val merged = recluster(samples, raw, numSpeakers, audioMs)
            onProgress(0.5f)
            val tRecl = SystemClock.elapsedRealtime()
            val segs = Segmenter.build(merged)
            val ids = Segmenter.speakerIds(segs)
            Log.i(TAG, "diarization ${tDiar - tDecode} ms + recluster ${tRecl - tDiar} ms, raw=${raw.size} segs=${segs.size} speakers=${ids.size}")

            val asrLang = if (lang == "auto") Models.detectLang(context, lidSpeech(pcm, segs)) else lang
            val utterances = ArrayList<Utterance>()
            val rec = Models.recognizer(context, asrLang)
            for ((i, s) in segs.withIndex()) {
                val w = Segmenter.padded(s, audioMs)
                val text = if (!Segmenter.needsVadSplit(w)) {
                    recognize(rec, pcm.read(w.first, w.last))
                } else {
                    recognizeLong(rec, Models.vadModel, pcm.read(w.first, w.last))
                }
                if (text.isNotBlank()) utterances.add(Utterance(ids.getValue(s.speaker), s.startMs, s.endMs, text))
                onProgress(0.5f + 0.5f * (i + 1) / segs.size)
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            val res = TranscribeResult(utterances, audioMs, elapsed, ids.size, asrLang)
            Log.i(TAG, "asr[$asrLang] ${SystemClock.elapsedRealtime() - tDiar} ms; total ${elapsed} ms, audio ${audioMs} ms, RTF=${"%.3f".format(res.rtf)}, utterances=${utterances.size}")
            return res
        }
    }

    /** Первые [LID_SPEECH_MS] речи по сегментам диаризации (паузы между репликами выкинуты). */
    private fun lidSpeech(pcm: PcmStore, segs: List<Seg>): FloatArray {
        val parts = ArrayList<FloatArray>()
        var left = LID_SPEECH_MS
        for (s in segs) {
            if (left <= 0) break
            val end = minOf(s.endMs, s.startMs + left)
            parts.add(pcm.read(s.startMs, end))
            left -= end - s.startMs
        }
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** sherpa-onnx: [numSpeakers] <= 0 — авто (порог [CLUSTER_THRESHOLD]). */
    private fun diarize(samples: FloatArray, numSpeakers: Int, audioMs: Long, onProgress: (Float) -> Unit): List<Seg> {
        Models.setClusters(if (numSpeakers > 0) numSpeakers else -1)
        // processWithCallback в AAR падает в JNI → process() + оценочный прогресс по времени
        val expectedMs = (audioMs * EXPECTED_DIAR_RTF).coerceAtLeast(1000.0)
        val start = SystemClock.elapsedRealtime()
        val ticker = Thread {
            try {
                while (true) {
                    Thread.sleep(300)
                    onProgress(((SystemClock.elapsedRealtime() - start) / expectedMs).toFloat().coerceAtMost(0.95f))
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
        val res = try { Models.diarizer.process(samples) } finally { ticker.interrupt() }
        onProgress(1f)
        return res.sortedBy { it.start }.map { Seg((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
    }

    /**
     * [Recluster] поверх авто-диаризации + добор речи, пропущенной pyannote (Silero VAD). Заданное число
     * спикеров: сливаем авто-кластеры до N; если авто нашла меньше N — повторная диаризация с numClusters=N.
     */
    private fun recluster(samples: FloatArray, auto: List<Seg>, numSpeakers: Int, audioMs: Long): List<Seg> {
        var base = auto
        var p = if (numSpeakers > 0) Recluster.fixed(numSpeakers) else Recluster.AUTO
        if (numSpeakers > 0 && auto.map { it.speaker }.distinct().size < numSpeakers) {
            base = diarize(samples, numSpeakers, audioMs) {}
            p = Recluster.fixed(numSpeakers, absorbSmall = false)
        }
        val fill = Recluster.uncovered(speechRegions(samples), base)
        val all = (base + fill).sortedBy { it.startMs }
        val embs = HashMap<Int, FloatArray>()
        for ((i, s) in all.withIndex()) Recluster.embWindow(s)?.let { w -> embed(samples, w)?.let { embs[i] = it } }
        val res = Recluster.run(all, embs, p)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "recluster raw: " + all.joinToString(" ") { "${it.startMs}-${it.endMs}:${it.speaker}" })
        Log.i(TAG, "recluster: sherpa ${base.map { it.speaker }.distinct().size} spk -> ${res.map { it.speaker }.distinct().size}, vad fill ${fill.size} (${fill.sumOf { it.endMs - it.startMs }} ms), emb ${embs.size}/${all.size}")
        return res
    }

    private fun embed(samples: FloatArray, w: LongRange): FloatArray? {
        val ex = Models.embedder
        val a = (w.first * AudioDecoder.TARGET_RATE / 1000).toInt().coerceIn(0, samples.size)
        val b = (w.last * AudioDecoder.TARGET_RATE / 1000).toInt().coerceIn(a, samples.size)
        val st = ex.createStream()
        st.acceptWaveform(samples.copyOfRange(a, b), AudioDecoder.TARGET_RATE)
        st.inputFinished()
        val v = if (ex.isReady(st)) Recluster.normalize(ex.compute(st)) else null
        st.release()
        return v
    }

    /** Речевые участки Silero VAD по всему файлу (мс). */
    private fun speechRegions(samples: FloatArray): List<LongRange> {
        val vad = Models.vadModel
        val win = vad.config.sileroVadModelConfig.windowSize
        val res = ArrayList<LongRange>()
        fun drain() {
            while (!vad.empty()) {
                val f = vad.front()
                res.add(f.start * 1000L / AudioDecoder.TARGET_RATE..(f.start + f.samples.size) * 1000L / AudioDecoder.TARGET_RATE)
                vad.pop()
            }
        }
        vad.reset()
        var off = 0
        while (off + win <= samples.size) { vad.acceptWaveform(samples.copyOfRange(off, off + win)); off += win; drain() }
        vad.flush(); drain()
        vad.reset()
        return res
    }

    /** Сегмент > 30 с: режем Silero VAD внутри. */
    private fun recognizeLong(rec: OfflineRecognizer, vad: Vad, x: FloatArray): String {
        val det = object : Segmenter.SpeechDetector {
            override fun accept(window: FloatArray) = vad.acceptWaveform(window)
            override fun drain(): List<FloatArray> {
                val r = ArrayList<FloatArray>()
                while (!vad.empty()) { r.add(vad.front().samples); vad.pop() }
                return r
            }
            override fun flush() = vad.flush()
            override fun reset() = vad.reset()
        }
        return Segmenter.recognizeLong(x, vad.config.sileroVadModelConfig.windowSize, det) { recognize(rec, it) }
    }

    private fun recognize(rec: OfflineRecognizer, samples: FloatArray): String {
        val s = rec.createStream()
        s.acceptWaveform(samples, AudioDecoder.TARGET_RATE)
        rec.decode(s)
        val text = rec.getResult(s).text.trim()
        s.release()
        return text
    }
}
