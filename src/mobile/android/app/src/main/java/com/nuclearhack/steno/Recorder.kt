package com.nuclearhack.steno

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Запись с микрофона сразу в WAV 16 кГц mono PCM16 (формат пайплайна, без пересэмплирования). */
class Recorder(private val file: File) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var samples = 0L; private set
    /** Уровень сигнала 0..1 последнего буфера — для индикатора. */
    @Volatile var level = 0f; private set

    val durationMs get() = samples * 1000 / RATE

    @SuppressLint("MissingPermission") // разрешение проверяет UI до вызова
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, RATE) // ~0.5 с
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw UserFacingException("Микрофон недоступен") }
        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(header(0)) }
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(RATE / 10)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var peak = 0
                    for (i in 0 until n) { bytes.putShort(buf[i]); peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
                    raf.write(bytes.array(), 0, n * 2)
                    samples += n
                    level = peak / 32768f
                }
            } catch (t: Throwable) {
                Log.e(TAG, "recording failed", t)
            } finally {
                rec.stop(); rec.release()
                raf.seek(0); raf.write(header(samples * 2)); raf.close()
            }
        }, "recorder").also { it.start() }
    }

    /** Останавливает запись и дожидается записи заголовка WAV. */
    fun stop(): File {
        running = false
        thread?.join()
        return file
    }

    companion object {
        const val RATE = 16000

        fun header(dataBytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }
}
