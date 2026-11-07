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
) {
    val rtf: Double get() = if (audioMs > 0) decodeAsrMs.toDouble() / audioMs else 0.0
}

/** Ожидаемый RTF диаризации — для оценочного прогресса (JNI-колбэк в AAR не работает). */
private const val EXPECTED_DIAR_RTF = 0.15

/**
 * Файл → декод 16k mono (во временный файл) → диаризация (pyannote-3.0 + titanet) →
 * ASR (GigaAM v3 transducer, RU) по сегментам диаризации. Модели — из [Models] (грузятся при старте).
 * speaker_id и таймкоды — из диаризации. [numSpeakers] <= 0 — авто (порог кластеризации).
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

            val raw = diarize(pcm, numSpeakers, audioMs) { onProgress(0.1f + it * 0.4f) }
            val tDiar = SystemClock.elapsedRealtime()
            val merged = if (numSpeakers > 0) raw else mergeSmallClusters(pcm, raw)
            val segs = Segmenter.build(merged)
            val ids = Segmenter.speakerIds(segs)
            Log.i(TAG, "diarization ${tDiar - tDecode} ms, raw=${raw.size} segs=${segs.size} speakers=${ids.size}")

            val utterances = ArrayList<Utterance>()
            val rec = Models.recognizer(context, lang)
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
            val res = TranscribeResult(utterances, audioMs, elapsed, ids.size)
            Log.i(TAG, "asr[$lang] ${SystemClock.elapsedRealtime() - tDiar} ms; total ${elapsed} ms, audio ${audioMs} ms, RTF=${"%.3f".format(res.rtf)}, utterances=${utterances.size}")
            return res
        }
    }

    private fun diarize(pcm: PcmStore, numSpeakers: Int, audioMs: Long, onProgress: (Float) -> Unit): List<Seg> {
        Models.setClusters(if (numSpeakers > 0) numSpeakers else -1)
        val samples = pcm.readAll()
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

    /** Мелкие кластеры (<3% речи или <2 с) → ближайший по косинусу средних эмбеддингов. */
    private fun mergeSmallClusters(pcm: PcmStore, raw: List<Seg>): List<Seg> {
        val totals = ClusterMerge.totals(raw)
        val small = ClusterMerge.small(totals)
        if (small.isEmpty()) return raw
        val emb = totals.keys.associateWith { spk -> clusterEmbedding(pcm, raw.filter { it.speaker == spk }) }
        val map = ClusterMerge.mapping(small, emb)
        Log.i(TAG, "small clusters merged: $map (totals ms: $totals)")
        return ClusterMerge.apply(raw, map)
    }

    /** Средний эмбеддинг по самым длинным сегментам кластера (до ~20 с аудио). */
    private fun clusterEmbedding(pcm: PcmStore, segs: List<Seg>): FloatArray {
        val ex = Models.embedder
        var budget = 20_000L
        val embs = ArrayList<FloatArray>()
        for (s in segs.sortedByDescending { it.endMs - it.startMs }) {
            if (budget <= 0) break
            val end = minOf(s.endMs, s.startMs + budget)
            if (end - s.startMs < 400) continue
            val st = ex.createStream()
            st.acceptWaveform(pcm.read(s.startMs, end), AudioDecoder.TARGET_RATE)
            st.inputFinished()
            if (ex.isReady(st)) embs.add(ex.compute(st))
            st.release()
            budget -= end - s.startMs
        }
        if (embs.isEmpty()) return FloatArray(ex.dim())
        return FloatArray(embs[0].size) { i -> embs.sumOf { it[i].toDouble() }.toFloat() / embs.size }
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
