package com.nuclearhack.steno

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Резюме по кнопке (вне основного пайплайна): освобождает модели ASR/диаризации → копирует GGUF из assets
 * в filesDir (первый раз, с прогрессом) → map-reduce по чанкам расшифровки → free LLM.
 */
class Summarizer(private val context: Context) {
    data class Result(val summary: SummaryLogic.Summary, val json: String, val chunks: Int, val llmMs: Long)

    /** [onProgress]: (стадия, 0..1). Стадии: копирование модели, загрузка, конспект чанков, итоги. */
    fun run(utterances: List<Utterance>, onProgress: (String, Float) -> Unit = { _, _ -> }): Result {
        require(utterances.isNotEmpty()) { "нет расшифровки" }
        Models.releaseAll()
        val t0 = SystemClock.elapsedRealtime()
        val gguf = ensureModel(onProgress)
        val ru = SummaryLogic.isRussian(utterances)
        val sourceTexts = utterances.map { it.text }
        onProgress("load", 0f)
        LlamaBridge.load(gguf.path, SummaryLogic.N_CTX).use { llm ->
            val tLoad = SystemClock.elapsedRealtime()
            Log.i(TAG, "llm loaded in ${tLoad - t0} ms")
            val chunks = SummaryLogic.chunk(SummaryLogic.lines(utterances, ru), SummaryLogic.CHUNK_TOKENS, llm::tokenCount)
            Log.i(TAG, "summary: ${chunks.size} chunk(s)")
            var notes: String
            run {
                val parts = ArrayList<String>()
                for ((i, c) in chunks.withIndex()) {
                    onProgress("map", i.toFloat() / chunks.size)
                    val tc = SystemClock.elapsedRealtime()
                    val out = mapNotes(llm, c, ru)
                    Log.i(TAG, "map ${i + 1}/${chunks.size}: ${SystemClock.elapsedRealtime() - tc} ms, ${out.length} chars")
                    if (out.isNotBlank()) parts.add(out)
                }
                notes = parts.joinToString("\n")
                // иерархический reduce, пока заметки не влезают в один шаг
                var level = 0
                while (llm.tokenCount(notes) > SummaryLogic.REDUCE_TOKENS && level < 4) {
                    val groups = SummaryLogic.chunk(notes.lines().filter { it.isNotBlank() }, SummaryLogic.CHUNK_TOKENS, llm::tokenCount)
                    if (groups.size <= 1) break
                    notes = groups.mapIndexed { i, g ->
                        onProgress("reduce", i.toFloat() / groups.size)
                        mapNotes(llm, g, ru)
                    }.filter { it.isNotBlank() }.joinToString("\n")
                    level++
                }
            }
            onProgress("final", 0f)
            val tf = SystemClock.elapsedRealtime()
            fun ask(kind: SummaryLogic.Kind, maxNew: Int): String {
                val out = llm.generate(SummaryLogic.finalPrompt(kind, notes, ru), maxNew) ?: ""
                return (SummaryLogic.prefill(kind, ru) + out).also { Log.d(TAG, "final[$kind]: ${it.take(300).replace("\n", " | ")}") }
            }
            val summaryText = SummaryLogic.parseSummary(ask(SummaryLogic.Kind.SUMMARY, 160))
            onProgress("final", 0.4f)
            val decisions = SummaryLogic.parseItems(ask(SummaryLogic.Kind.DECISIONS, 140), sourceTexts)
            onProgress("final", 0.7f)
            val tasks = SummaryLogic.parseItems(ask(SummaryLogic.Kind.TASKS, 140), sourceTexts)
            Log.i(TAG, "final: ${SystemClock.elapsedRealtime() - tf} ms")
            val parsed = SummaryLogic.Summary(summaryText.ifBlank { notes.take(400) }, decisions, tasks)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "summary total $ms ms (chunks=${chunks.size})")
            onProgress("final", 1f)
            return Result(parsed, SummaryLogic.toJson(parsed, if (ru) "ru" else "en", chunks.size, ms), chunks.size, ms)
        }
    }

    /** Конспект одного чанка: в ответ дописываем префилл "-", чтобы получить маркированный список. */
    private fun mapNotes(llm: LlamaBridge, chunk: String, ru: Boolean): String =
        llm.generate(SummaryLogic.mapPrompt(chunk, ru), SummaryLogic.MAX_NEW_MAP)?.let { SummaryLogic.cleanNotes("-$it") }.orEmpty()

    /** GGUF из assets → filesDir (llama.cpp нужен путь к файлу), с прогрессом копирования. */
    private fun ensureModel(onProgress: (String, Float) -> Unit): File {
        val dst = File(context.filesDir, MODEL_NAME)
        val size = context.assets.openFd("models/llm/$MODEL_NAME").use { it.length }
        if (dst.exists() && dst.length() == size) return dst
        val tmp = File(context.filesDir, "$MODEL_NAME.part")
        context.assets.open("models/llm/$MODEL_NAME").use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    onProgress("copy", done.toFloat() / size)
                }
            }
        }
        check(tmp.renameTo(dst)) { "не удалось сохранить модель" }
        return dst
    }

    companion object { const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf" }
}
