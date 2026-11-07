package com.nuclearhack.steno

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Потоковое декодирование любого поддерживаемого Android формата (MediaExtractor+MediaCodec)
 * в 16 кГц mono float. PCM целиком в памяти не держится: чанки отдаются в [onChunk].
 */
object AudioDecoder {
    const val TARGET_RATE = 16000

    /** @return длительность декодированного аудио в миллисекундах. */
    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        onChunk: (FloatArray) -> Unit,
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        require(track >= 0 && format != null) { "В файле нет аудиодорожки" }
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler = LinearResampler(rate, TARGET_RATE)
        var totalOut = 0L

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (newRate != rate) { rate = newRate; resampler = LinearResampler(rate, TARGET_RATE) }
                    }
                    out >= 0 -> {
                        if (info.size > 0) {
                            val shorts = codec.getOutputBuffer(out)!!
                                .order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            val mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var acc = 0
                                for (c in 0 until channels) acc += shorts.get()
                                mono[i] = acc / (32768f * channels)
                            }
                            val res = resampler.process(mono)
                            if (res.isNotEmpty()) { totalOut += res.size; onChunk(res) }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        onProgress(1f)
        return totalOut * 1000L / TARGET_RATE
    }
}

/** Потоковая линейная передискретизация (состояние переносится между чанками). */
class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev = 0f
    private var hasPrev = false

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        if (step == 1.0) return input
        val buf = if (hasPrev) FloatArray(input.size + 1).also { it[0] = prev; System.arraycopy(input, 0, it, 1, input.size) } else input
        val out = ArrayList<Float>((buf.size / step).toInt() + 2)
        var p = pos
        while (p + 1 < buf.size) {
            val i = p.toInt()
            val frac = (p - i).toFloat()
            out.add(buf[i] * (1 - frac) + buf[i + 1] * frac)
            p += step
        }
        pos = p - (buf.size - 1)
        prev = buf[buf.size - 1]
        hasPrev = true
        return out.toFloatArray()
    }
}
