package com.nuclearhack.steno

/** Сериализация строго по формату из презентации: {"transcript":[{speaker_id,start_ms,end_ms,text}]}. */
object TranscriptJson {
    fun build(utterances: List<Utterance>): String {
        val sorted = utterances.sortedBy { it.startMs }
        val sb = StringBuilder()
        sb.append("{\n  \"transcript\": [")
        sorted.forEachIndexed { i, u ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    { \"speaker_id\": ").append(quote(u.speakerId))
            sb.append(", \"start_ms\": ").append(u.startMs)
            sb.append(", \"end_ms\": ").append(u.endMs)
            sb.append(", \"text\": ").append(quote(u.text)).append(" }")
        }
        sb.append(if (sorted.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
        return sb.toString()
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(json: String): List<Utterance> {
        val arr = org.json.JSONObject(json).getJSONArray("transcript")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Utterance(it.getString("speaker_id"), it.getLong("start_ms"), it.getLong("end_ms"), it.getString("text"))
        }
    }

    /** Имя экспортируемого файла: <имя_аудио>.json */
    fun exportName(audioName: String): String {
        val base = audioName.substringBeforeLast('.', audioName).ifBlank { "transcript" }
        return "$base.json"
    }
}
