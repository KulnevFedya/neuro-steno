package com.nuclearhack.steno

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono 16 кГц PCM во временном файле: декодер пишет потоком, читаем окнами. */
class PcmStore(private val file: File) : Closeable {
    private var out: java.io.OutputStream? = file.outputStream().buffered(1 shl 16)
    var samples = 0L; private set
    private var raf: RandomAccessFile? = null

    fun append(chunk: FloatArray) {
        val bb = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in chunk) bb.putShort((f * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        out!!.write(bb.array())
        samples += chunk.size
    }

    fun finish() { out?.close(); out = null }

    val durationMs get() = samples * 1000L / AudioDecoder.TARGET_RATE

    /** Читает [fromMs, toMs) как float. */
    fun read(fromMs: Long, toMs: Long): FloatArray {
        val a = (fromMs * AudioDecoder.TARGET_RATE / 1000).coerceIn(0, samples)
        val b = (toMs * AudioDecoder.TARGET_RATE / 1000).coerceIn(a, samples)
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        val bytes = ByteArray(((b - a) * 2).toInt())
        r.seek(a * 2); r.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray((b - a).toInt()) { sb.get(it) / 32768f }
    }

    fun readAll(): FloatArray = read(0, durationMs + 1)

    override fun close() { finish(); raf?.close(); raf = null; file.delete() }
}
