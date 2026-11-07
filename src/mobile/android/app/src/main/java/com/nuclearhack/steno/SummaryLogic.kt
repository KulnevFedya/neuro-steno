package com.nuclearhack.steno

/** Чистая логика резюме (без Android/JNI): язык, чанки, промпты, разбор ответа. */
object SummaryLogic {
    const val N_CTX = 4096
    const val MAX_NEW_MAP = 160
    const val MAX_NEW_FINAL = 360
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

    fun chatml(system: String, user: String) =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    fun mapPrompt(chunk: String, ru: Boolean): String = if (ru) chatml(
        "Ты помощник, который кратко конспектирует разговоры. Пиши только на русском языке, только по тексту, ничего не выдумывай.",
        "Фрагмент расшифровки разговора:\n\n$chunk\n\nСделай краткий конспект фрагмента: 3–6 коротких пунктов (о чём говорили, какие решения приняты, кто что должен сделать). Каждый пункт с новой строки, начиная с «- ».",
    ) else chatml(
        "You are an assistant that takes concise notes of conversations. Write only in English, use only the text, invent nothing.",
        "Conversation transcript fragment:\n\n$chunk\n\nWrite concise notes for this fragment: 3-6 short bullet points (topics, decisions made, who must do what). One point per line starting with \"- \".",
    )

    fun finalPrompt(notes: String, ru: Boolean): String = if (ru) chatml(
        "Ты помощник, который составляет итоги встреч. Пиши только на русском языке, только по данным заметкам, ничего не выдумывай.",
        "Заметки о разговоре:\n\n$notes\n\nСоставь итоги строго в таком формате:\n" +
            "Краткое содержание: <2–4 предложения>\nРешения:\n- <решение>\nЗадачи:\n- <кто и что должен сделать>\n" +
            "Если решений или задач нет, напиши под заголовком «- нет».",
    ) else chatml(
        "You are an assistant that writes meeting summaries. Write only in English, use only the given notes, invent nothing.",
        "Notes about the conversation:\n\n$notes\n\nWrite the summary strictly in this format:\n" +
            "Summary: <2-4 sentences>\nDecisions:\n- <decision>\nTasks:\n- <who must do what>\n" +
            "If there are no decisions or tasks, write \"- none\" under the heading.",
    )

    private val H_SUMMARY = Regex("^\\s*[*#]*\\s*(краткое содержание|резюме|summary)\\s*[*]*\\s*:?[*]*\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val H_DECISIONS = Regex("^\\s*[*#]*\\s*(решения|decisions)\\s*[*]*\\s*:?[*]*\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val H_TASKS = Regex("^\\s*[*#]*\\s*(задачи|tasks|action items)\\s*[*]*\\s*:?[*]*\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val NONE = Regex("^(нет|none|n/a|-)\\.?$", RegexOption.IGNORE_CASE)

    /** Разбор ответа модели по заголовкам; если формата нет — весь текст идёт в summary. */
    fun parse(text: String): Summary {
        val sum = StringBuilder(); val dec = ArrayList<String>(); val tasks = ArrayList<String>()
        var section = 0
        fun add(section: Int, s: String) {
            val t = s.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim()
            if (t.isEmpty() || NONE.matches(t)) return
            when (section) { 0 -> { if (sum.isNotEmpty()) sum.append(' '); sum.append(t) }; 1 -> dec.add(t); else -> tasks.add(t) }
        }
        for (line in text.lines()) {
            val m = listOf(H_SUMMARY, H_DECISIONS, H_TASKS).withIndex().firstNotNullOfOrNull { (i, r) -> r.matchEntire(line)?.let { i to it } }
            if (m != null) { section = m.first; add(section, m.second.groupValues[2]) } else add(section, line)
        }
        return Summary(sum.toString().trim(), dec, tasks)
    }

    fun toJson(s: Summary, lang: String, chunks: Int, llmMs: Long): String {
        fun arr(l: List<String>) = l.joinToString(",\n    ", prefix = "[", postfix = "]") { TranscriptJson.quote(it) }
        return "{\n  \"language\": ${TranscriptJson.quote(lang)},\n  \"summary\": ${TranscriptJson.quote(s.summary)},\n" +
            "  \"decisions\": ${arr(s.decisions)},\n  \"tasks\": ${arr(s.tasks)},\n  \"chunks\": $chunks,\n  \"llm_ms\": $llmMs\n}\n"
    }

    fun summaryName(audioName: String): String =
        audioName.substringBeforeLast('.', audioName).ifBlank { "transcript" } + ".summary.json"
}
