package com.nuclearhack.steno

/** Чистая логика резюме (без Android/JNI): язык, чанки, промпты, разбор ответа. */
object SummaryLogic {
    const val N_CTX = 4096
    const val MAX_NEW_MAP = 160
    /** Токенов расшифровки в одном чанке map-шага (запас под промпт и ответ). */
    const val CHUNK_TOKENS = 1700
    /** Токенов заметок, которые ещё влезают в один reduce-шаг. */
    const val REDUCE_TOKENS = 2400

    data class Summary(val summary: String, val decisions: List<String>, val tasks: List<String>)

    fun isRussian(utterances: List<Utterance>): Boolean {
        var cyr = 0; var lat = 0
        for (u in utterances) for (c in u.text) when {
            c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё' -> cyr++
            c in 'a'..'z' || c in 'A'..'Z' -> lat++
        }
        return cyr >= lat
    }

    /** "Спикер 1: текст" — одна строка на реплику. */
    fun lines(utterances: List<Utterance>, ru: Boolean): List<String> {
        val label = if (ru) "Спикер" else "Speaker"
        return utterances.sortedBy { it.startMs }.map { "$label ${it.speakerId.substringAfter('_')}: ${it.text}" }
    }

    /** Жадно собирает строки в чанки не более [budget] токенов (строка длиннее бюджета — отдельный чанк, режется по словам). */
    fun chunk(lines: List<String>, budget: Int, count: (String) -> Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder(); var curTok = 0
        fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString().trimEnd()); cur.clear(); curTok = 0 } }
        for (raw in lines) {
            for (line in splitLong(raw, budget, count)) {
                val t = count(line) + 1
                if (curTok + t > budget) flush()
                cur.append(line).append('\n'); curTok += t
            }
        }
        flush()
        return out
    }

    private fun splitLong(line: String, budget: Int, count: (String) -> Int): List<String> {
        if (count(line) <= budget) return listOf(line)
        val words = line.split(' ')
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (w in words) {
            if (cur.isNotEmpty() && count(cur.toString() + " " + w) > budget) { out.add(cur.toString()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** [prefill] — начало ответа ассистента (принуждает модель к формату). */
    fun chatml(system: String, user: String, prefill: String = "") =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n$prefill"


    private const val MAP_ASK_RU = "Запиши конспект фрагмента короткими пунктами: что обсуждали, какие решения приняты, кто что должен сделать."
    private const val MAP_ASK_EN = "Write short bullet-point notes for this fragment: what was discussed, what was decided, who must do what."

    fun mapPrompt(chunk: String, ru: Boolean): String = if (ru) chatml(
        "Ты помощник, который кратко конспектирует разговоры. Пиши только на русском языке, только по тексту, ничего не выдумывай, реплики не копируй.",
        "Фрагмент расшифровки разговора:\n\n$chunk\n\n$MAP_ASK_RU",
        prefill = "-",
    ) else chatml(
        "You are an assistant that takes concise notes of conversations. Write only in English, use only the text, invent nothing, do not copy the lines.",
        "Conversation transcript fragment:\n\n$chunk\n\n$MAP_ASK_EN",
        prefill = "-",
    )

    /** Конспект без эха инструкции и без дословных реплик («Спикер N: …»). */
    fun cleanNotes(text: String): String {
        val instr = norm(MAP_ASK_RU) + "|" + norm(MAP_ASK_EN)
        return text.lines().map { it.trim() }.filter { l ->
            val body = l.removePrefix("-").removePrefix("•").trim()
            val n = norm(body)
            n.isNotEmpty() && !COPIED.containsMatchIn(body) && !(n.length >= 8 && instr.contains(n))
        }.joinToString("\n")
    }

    enum class Kind { SUMMARY, DECISIONS, TASKS }

    private const val SYS_RU = "Ты помощник, который составляет итоги встреч. Пиши только на русском языке, только по данным заметкам, ничего не выдумывай."
    private const val SYS_EN = "You are an assistant that writes meeting summaries. Write only in English, use only the given notes, invent nothing."

    /** Три коротких запроса вместо одного большого: маленькая модель иначе копирует заметки. */
    fun finalPrompt(kind: Kind, notes: String, ru: Boolean): String {
        val (ask, prefill) = if (ru) when (kind) {
            Kind.SUMMARY -> "Напиши краткое содержание разговора: 2–4 предложения своими словами, без перечисления реплик." to "Краткое содержание: Участники обсудили"
            Kind.DECISIONS -> ("Какие решения приняты в разговоре? Перечисли 1–4 решения, по одному в строке, начиная с «- ». Если решений не было, напиши «- нет».") to "Решения:\n-"
            Kind.TASKS -> ("Какие задачи поставлены и кто за них отвечает? Перечисли 1–4 задачи, по одной в строке, начиная с «- ». Если задач нет, напиши «- нет».") to "Задачи:\n-"
        } else when (kind) {
            Kind.SUMMARY -> "Write a short summary of the conversation: 2-4 sentences in your own words, do not list the lines." to "Summary: The participants discussed"
            Kind.DECISIONS -> ("What decisions were made? List 1-4 decisions, one per line starting with \"- \". If there were none, write \"- none\".") to "Decisions:\n-"
            Kind.TASKS -> ("What tasks were assigned and to whom? List 1-4 tasks, one per line starting with \"- \". If there are none, write \"- none\".") to "Tasks:\n-"
        }
        val head = if (ru) "Заметки о разговоре:" else "Notes about the conversation:"
        return chatml(if (ru) SYS_RU else SYS_EN, "$head\n\n$notes\n\n$ask", prefill)
    }

    fun prefill(kind: Kind, ru: Boolean): String = when (kind) {
        Kind.SUMMARY -> if (ru) "Участники обсудили" else "The participants discussed"
        Kind.DECISIONS, Kind.TASKS -> "-"
    }

    private val NONE = Regex("^(нет|none|n/a|-)\\.?$", RegexOption.IGNORE_CASE)
    private val COPIED = Regex("^(спикер|speaker)\\s*\\d+\\s*:", RegexOption.IGNORE_CASE)

    /** Абзац резюме: до первой пустой строки / следующего заголовка. */
    fun parseSummary(text: String): String {
        val lines = text.trim().lines().takeWhile { it.isNotBlank() && !Regex("^(решения|задачи|decisions|tasks)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
        return lines.joinToString(" ") { it.trim() }.trim()
    }

    /** Список пунктов «- …»: без дословных реплик («Спикер N: …»), без «нет», до 5 штук. */
    fun parseItems(text: String, source: Collection<String> = emptyList()): List<String> {
        val src = source.map(::norm).toSet()
        return text.lines().map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !NONE.matches(it) && !COPIED.containsMatchIn(it) && !it.endsWith(":") }
            .filter { norm(it) !in src && !(norm(it).length >= 8 && ECHO.contains(norm(it))) }
            .distinct().take(5)
    }

    /** Тексты инструкций final-шага: модель иногда повторяет их в ответе. */
    private val ECHO: String by lazy {
        Kind.values().flatMap { k -> listOf(true, false).map { finalPrompt(k, "", it) } }.joinToString("|") { norm(it) }
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    fun toJson(s: Summary, lang: String, chunks: Int, llmMs: Long): String {
        fun arr(l: List<String>) = l.joinToString(",\n    ", prefix = "[", postfix = "]") { TranscriptJson.quote(it) }
        return "{\n  \"language\": ${TranscriptJson.quote(lang)},\n  \"summary\": ${TranscriptJson.quote(s.summary)},\n" +
            "  \"decisions\": ${arr(s.decisions)},\n  \"tasks\": ${arr(s.tasks)},\n  \"chunks\": $chunks,\n  \"llm_ms\": $llmMs\n}\n"
    }

    fun summaryName(audioName: String): String =
        audioName.substringBeforeLast('.', audioName).ifBlank { "transcript" } + ".summary.json"
}
