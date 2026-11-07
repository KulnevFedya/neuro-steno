package com.nuclearhack.steno

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import java.io.File
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

const val TAG = "STENO"

data class TranscribeResult(
    val utterances: List<Utterance>,
    val audioMs: Long,
    val decodeAsrMs: Long,
    val numSpeakers: Int = 1,
) {
    val rtf: Double get() = if (audioMs > 0) decodeAsrMs.toDouble() / audioMs else 0.0
}

/** Порог кластеризации диаризации (подобран на eval, titanet_small). */
const val CLUSTER_THRESHOLD = 0.5f

/**
 * Файл → декод 16k mono (во временный файл) → диаризация (pyannote-3.0 + titanet) → release →
 * ASR (GigaAM v3 transducer, RU) по сегментам диаризации. speaker_id и таймкоды — из диаризации.
 * [numSpeakers] <= 0 — авто (порог кластеризации).
 */
class Transcriber(private val context: Context) {

    fun run(uri: Uri, numSpeakers: Int = -1, onProgress: (Float) -> Unit = {}): TranscribeResult {
        val assets = context.assets
        val t0 = SystemClock.elapsedRealtime()
        PcmStore(File(context.cacheDir, "pcm_${System.nanoTime()}.raw")).use { pcm ->
            AudioDecoder.decode(context, uri, { onProgress(it * 0.1f) }) { pcm.append(it) }
            pcm.finish()
            val audioMs = pcm.durationMs
            val tDecode = SystemClock.elapsedRealtime()
            Log.i(TAG, "decode ${tDecode - t0} ms, audio $audioMs ms")

            // 1. диаризация, затем освобождаем и модель, и PCM-массив
            val raw = diarize(pcm, numSpeakers) { onProgress(0.1f + it * 0.4f) }
            val tDiar = SystemClock.elapsedRealtime()
            val segs = Segmenter.build(raw)
            val ids = Segmenter.speakerIds(segs)
            Log.i(TAG, "diarization ${tDiar - tDecode} ms, raw=${raw.size} segs=${segs.size} speakers=${ids.size}")

            // 2. ASR по сегментам
            val utterances = ArrayList<Utterance>()
            val rec = createRecognizer(assets)
            var vad: Vad? = null
            try {
                for ((i, s) in segs.withIndex()) {
                    val w = Segmenter.padded(s, audioMs)
                    val text = if (w.last - w.first <= Segmenter.MAX_ASR_MS) {
                        recognize(rec, pcm.read(w.first, w.last))
                    } else {
                        val v = vad ?: createVad(assets).also { vad = it }
                        recognizeLong(rec, v, pcm.read(w.first, w.last))
                    }
                    if (text.isNotBlank()) utterances.add(Utterance(ids.getValue(s.speaker), s.startMs, s.endMs, text))
                    onProgress(0.5f + 0.5f * (i + 1) / segs.size)
                }
            } finally {
                vad?.release(); rec.release()
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            val res = TranscribeResult(utterances, audioMs, elapsed, ids.size)
            Log.i(TAG, "asr ${SystemClock.elapsedRealtime() - tDiar} ms; total ${elapsed} ms, audio ${audioMs} ms, RTF=${"%.3f".format(res.rtf)}, utterances=${utterances.size}")
            return res
        }
    }

    private fun diarize(pcm: PcmStore, numSpeakers: Int, onProgress: (Float) -> Unit): List<Seg> {
        val sd = OfflineSpeakerDiarization(
            context.assets,
            OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "models/diar/seg.int8.onnx"),
                    numThreads = 4,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = "models/diar/titanet_small.onnx", numThreads = 4),
                clustering = FastClusteringConfig(numClusters = if (numSpeakers > 0) numSpeakers else -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f,
            ),
        )
        try {
            val samples = pcm.readAll()
            // processWithCallback в AAR падает в JNI (лямбда Function3 не находит invoke(IIJ)) — берём process()
            val res = sd.process(samples)
            onProgress(1f)
            return res.sortedBy { it.start }.map { Seg((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
        } finally {
            sd.release()
        }
    }

    /** Сегмент > 30 с: режем Silero VAD внутри. */
    private fun recognizeLong(rec: OfflineRecognizer, vad: Vad, x: FloatArray): String {
        val window = vad.config.sileroVadModelConfig.windowSize
        val parts = ArrayList<String>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front(); vad.pop()
                recognize(rec, seg.samples).takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        var off = 0
        while (off + window <= x.size) {
            vad.acceptWaveform(x.copyOfRange(off, off + window)); off += window
            drain()
        }
        vad.flush(); drain()
        vad.reset()
        return parts.joinToString(" ")
    }

    private fun recognize(rec: OfflineRecognizer, samples: FloatArray): String {
        val s = rec.createStream()
        s.acceptWaveform(samples, AudioDecoder.TARGET_RATE)
        rec.decode(s)
        val text = rec.getResult(s).text.trim()
        s.release()
        return text
    }

    private fun createVad(assets: AssetManager) = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 25f,
            ),
            sampleRate = AudioDecoder.TARGET_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private fun createRecognizer(assets: AssetManager) = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioDecoder.TARGET_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "models/ru/encoder.int8.onnx",
                    decoder = "models/ru/decoder.onnx",
                    joiner = "models/ru/joiner.onnx",
                ),
                tokens = "models/ru/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = 4,
                provider = "cpu",
            ),
        ),
    )
}
