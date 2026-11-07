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
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Порог кластеризации диаризации (подобран на eval, titanet_small). */
const val CLUSTER_THRESHOLD = 0.5f
const val NUM_THREADS = 4

private const val EMB_MODEL = "models/diar/titanet_small.onnx"

/** Режим языка по умолчанию: "auto" (LID), "ru" или "en". */
const val DEFAULT_LANG = "auto"
/** Сколько речи (мс) отдаём LID. */
const val LID_SPEECH_MS = 20_000L

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
    private var recLang: String? = null
    /** Режим языка: "auto" (LID), "ru" (GigaAM v3) или "en" (Whisper base.en). */
    @Volatile var lang: String = DEFAULT_LANG
    private var lid: SpokenLanguageIdentification? = null
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
        val l = if (lang == "en") "en" else "ru"
        val r = buildRecognizer(context, l)
        val li = if (lang == "auto") buildLid(context) else null
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
        synchronized(lock) { sd = d; emb = e; rec = r; recLang = l; vad = v; lid = li }
        Log.i(TAG, "models loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Ждёт окончания загрузки (запускает её, если ещё не начата). */
    fun await(context: Context) {
        preload(context)
        loader?.join()
        error?.let { throw IllegalStateException("model load failed: ${it.message}", it) }
    }

    /** Распознаватель нужного языка; при смене языка старый освобождается (вместе не держим — память). */
    fun recognizer(context: Context, l: String): OfflineRecognizer {
        synchronized(lock) {
            rec?.let { if (recLang == l) return it }
            val t0 = SystemClock.elapsedRealtime()
            rec?.release(); rec = null
            return buildRecognizer(context.applicationContext, l).also {
                rec = it; recLang = l
                Log.i(TAG, "recognizer $l loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        }
    }

    /** Смена языка в UI: модель подгружается в фоне, к нажатию «Импорт» обычно уже готова. */
    fun switchLang(context: Context, l: String) {
        lang = l
        val app = context.applicationContext
        Thread({
            try {
                loader?.join()
                if (sd != null) {
                    if (l == "auto") lidModel(app) else recognizer(app, l)
                }
            } catch (t: Throwable) { Log.e(TAG, "recognizer load failed", t) }
        }, "lang-loader").start()
    }

    /**
     * Язык речи по whisper tiny (multilingual): только "ru" или "en", всё остальное и ошибки → "ru".
     * [speech] — до [LID_SPEECH_MS] речи (16 кГц).
     */
    fun detectLang(context: Context, speech: FloatArray): String {
        if (speech.size < AudioDecoder.TARGET_RATE) return "ru"
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val m = lidModel(context)
            val s = m.createStream()
            s.acceptWaveform(speech, AudioDecoder.TARGET_RATE)
            val raw = m.compute(s).trim()
            s.release()
            val res = if (raw == "en") "en" else "ru"
            Log.i(TAG, "lid: '$raw' -> $res in ${SystemClock.elapsedRealtime() - t0} ms (${speech.size * 1000L / AudioDecoder.TARGET_RATE} ms speech)")
            res
        } catch (t: Throwable) {
            Log.e(TAG, "lid failed", t)
            "ru"
        }
    }

    private fun lidModel(context: Context): SpokenLanguageIdentification = synchronized(lock) {
        lid ?: buildLid(context.applicationContext).also { lid = it }
    }

    // модели LID лежат в APK сжатыми (расширение не из noCompress): ~103 → ~60 МБ
    private fun buildLid(context: Context) = SpokenLanguageIdentification(
        context.assets,
        SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = "models/lid/tiny-encoder.int8.model",
                decoder = "models/lid/tiny-decoder.int8.model",
            ),
            numThreads = NUM_THREADS,
            provider = "cpu",
        ),
    )

    private fun buildRecognizer(context: Context, l: String): OfflineRecognizer {
        val model = if (l == "en") OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = "models/en/base.en-encoder.int8.onnx",
                decoder = "models/en/base.en-decoder.int8.onnx",
                language = "en",
                task = "transcribe",
            ),
            tokens = "models/en/base.en-tokens.txt",
            modelType = "whisper",
            numThreads = NUM_THREADS,
            provider = "cpu",
        ) else OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "models/ru/encoder.int8.onnx",
                decoder = "models/ru/decoder.onnx",
                joiner = "models/ru/joiner.onnx",
            ),
            tokens = "models/ru/tokens.txt",
            modelType = "nemo_transducer",
            numThreads = NUM_THREADS,
            provider = "cpu",
        )
        return OfflineRecognizer(
            context.assets,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioDecoder.TARGET_RATE, featureDim = 80),
                modelConfig = model,
            ),
        )
    }

    val diarizer get() = sd!!
    val embedder get() = emb!!
    val vadModel get() = vad!!

    fun setClusters(numClusters: Int) {
        val d = sd!!
        d.setConfig(d.config.copy(clustering = FastClusteringConfig(numClusters = numClusters, threshold = CLUSTER_THRESHOLD)))
    }

    /** Освобождает всё (перед загрузкой LLM). */
    fun releaseAll() {
        loader?.join()
        synchronized(lock) {
            sd?.release(); emb?.release(); rec?.release(); vad?.release(); lid?.release()
            sd = null; emb = null; rec = null; recLang = null; vad = null; lid = null
            loader = null
        }
        Log.i(TAG, "models released")
    }
}
