package com.nuclearhack.steno

import java.io.Closeable

/** JNI над llama.cpp (cpp/llama_jni.cpp). Один экземпляр = одна загруженная модель; не потокобезопасен. */
class LlamaBridge private constructor(private var handle: Long) : Closeable {

    /** [prompt] — готовый ChatML. Возвращает текст или null (ошибка/отмена/не влезло в контекст). */
    fun generate(prompt: String, maxNew: Int): String? = nativeGenerate(handle, prompt, maxNew)

    fun tokenCount(text: String): Int = nativeTokenCount(handle, text)

    fun cancel() = nativeCancel()

    override fun close() { if (handle != 0L) { nativeFree(handle); handle = 0 } }

    private external fun nativeGenerate(handle: Long, prompt: String, maxNew: Int): String?
    private external fun nativeTokenCount(handle: Long, text: String): Int
    private external fun nativeCancel()
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("steno_llama") }

        @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long

        fun load(path: String, nCtx: Int = 2048, threads: Int = NUM_THREADS): LlamaBridge {
            val h = nativeLoad(path, nCtx, threads)
            check(h != 0L) { "llama: не удалось загрузить модель $path" }
            return LlamaBridge(h)
        }
    }
}
