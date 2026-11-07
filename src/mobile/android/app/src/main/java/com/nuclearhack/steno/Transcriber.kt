package com.nuclearhack.steno

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
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
) {
    val rtf: Double get() = if (audioMs > 0) decodeAsrMs.toDouble() / audioMs else 0.0
}

/** Файл → декод 16k mono → Silero VAD → GigaAM v3 (RU, с пунктуацией) → реплики. Диаризации пока нет: все speaker_1. */
class Transcriber(private val context: Context) {

    fun run(uri: Uri, onProgress: (Float) -> Unit = {}): TranscribeResult {
        val assets = context.assets
        val t0 = SystemClock.elapsedRealtime()
        val vad = createVad(assets)
        val rec = createRecognizer(assets)
        Log.i(TAG, "models loaded in ${SystemClock.elapsedRealtime() - t0} ms")

        val utterances = ArrayList<Utterance>()
        val window = vad.config.sileroVadModelConfig.windowSize
        val pending = FloatArray(window)
        var pendingN = 0

        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val startMs = seg.start * 1000L / AudioDecoder.TARGET_RATE
                val endMs = startMs + seg.samples.size * 1000L / AudioDecoder.TARGET_RATE
                val text = recognize(rec, seg.samples)
                if (text.isNotBlank()) utterances.add(Utterance("speaker_1", startMs, endMs, text))
            }
        }

        val t1 = SystemClock.elapsedRealtime()
        val audioMs: Long
        try {
            audioMs = AudioDecoder.decode(context, uri, onProgress) { chunk ->
                var off = 0
                while (off < chunk.size) {
                    val n = minOf(window - pendingN, chunk.size - off)
                    System.arraycopy(chunk, off, pending, pendingN, n)
                    pendingN += n; off += n
                    if (pendingN == window) {
                        vad.acceptWaveform(pending.copyOf())
                        pendingN = 0
                        drain()
                    }
                }
            }
            vad.flush()
            drain()
        } finally {
            vad.release(); rec.release()
        }
        val elapsed = SystemClock.elapsedRealtime() - t1

        val res = TranscribeResult(utterances, audioMs, elapsed)
        Log.i(TAG, "decode+vad+asr ${elapsed} ms, audio ${audioMs} ms, RTF=${"%.3f".format(res.rtf)}, utterances=${utterances.size}")
        return res
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
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f,
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
                nemo = OfflineNemoEncDecCtcModelConfig(model = "models/ru/model.int8.onnx"),
                tokens = "models/ru/tokens.txt",
                numThreads = 4,
                provider = "cpu",
            ),
        ),
    )
}
