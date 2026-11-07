package com.nuclearhack.steno

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Порог кластеризации диаризации (подобран на eval, titanet_small). */
const val CLUSTER_THRESHOLD = 0.5f
const val NUM_THREADS = 4

private const val EMB_MODEL = "models/diar/titanet_small.onnx"

/**
 * Модели ASR+диаризации: грузятся в фоне при старте приложения и живут в памяти до [releaseAll]
 * (его вызывают перед загрузкой LLM). [await] блокирует, пока загрузка не закончится; после
 * [releaseAll] следующий [await] перезагружает модели.
 */
object Models {
    @Volatile private var loader: Thread? = null
    @Volatile private var error: Throwable? = null
    private var sd: OfflineSpeakerDiarization? = null
    private var rec: OfflineRecognizer? = null
    private var emb: SpeakerEmbeddingExtractor? = null
    private var vad: Vad? = null
    private val lock = Any()

    fun preload(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (loader != null) return
            error = null
            loader = Thread({
                try { load(app) } catch (t: Throwable) { error = t; Log.e(TAG, "model load failed", t) }
            }, "model-loader").also { it.start() }
        }
    }

    private fun load(context: Context) {
        val t0 = SystemClock.elapsedRealtime()
        val a = context.assets
        val d = OfflineSpeakerDiarization(
            a,
            OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "models/diar/seg.int8.onnx"),
                    numThreads = NUM_THREADS,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = EMB_MODEL, numThreads = NUM_THREADS),
                clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f,
            ),
        )
        val e = SpeakerEmbeddingExtractor(a, SpeakerEmbeddingExtractorConfig(model = EMB_MODEL, numThreads = NUM_THREADS))
        val r = OfflineRecognizer(
            a,
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
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
            ),
        )
        val v = Vad(
            a,
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
                numThreads = NUM_THREADS,
                provider = "cpu",
            ),
        )
        synchronized(lock) { sd = d; emb = e; rec = r; vad = v }
        Log.i(TAG, "models loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Ждёт окончания загрузки (запускает её, если ещё не начата). */
    fun await(context: Context) {
        preload(context)
        loader?.join()
        error?.let { throw IllegalStateException("model load failed: ${it.message}", it) }
    }

    val diarizer get() = sd!!
    val embedder get() = emb!!
    val recognizer get() = rec!!
    val vadModel get() = vad!!

    fun setClusters(numClusters: Int) {
        val d = sd!!
        d.setConfig(d.config.copy(clustering = FastClusteringConfig(numClusters = numClusters, threshold = CLUSTER_THRESHOLD)))
    }

    /** Освобождает всё (перед загрузкой LLM). */
    fun releaseAll() {
        loader?.join()
        synchronized(lock) {
            sd?.release(); emb?.release(); rec?.release(); vad?.release()
            sd = null; emb = null; rec = null; vad = null
            loader = null
        }
        Log.i(TAG, "models released")
    }
}
