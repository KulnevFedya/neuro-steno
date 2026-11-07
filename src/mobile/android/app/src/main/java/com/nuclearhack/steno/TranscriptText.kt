package com.nuclearhack.steno

/** Человекочитаемая расшифровка (TXT) и отображаемые имена спикеров. В JSON всегда speaker_id. */
object TranscriptText {
    /** Имя для UI/TXT: пользовательское, если задано, иначе «Спикер N». */
    fun speakerName(id: String, names: Map<String, String>): String =
        names[id]?.trim()?.takeIf { it.isNotEmpty() } ?: "Спикер ${id.substringAfter('_')}"

    fun build(utterances: List<Utterance>, names: Map<String, String>): String =
        utterances.sortedBy { it.startMs }.joinToString("\n\n", postfix = "\n") {
            "[${time(it.startMs)} – ${time(it.endMs)}] ${speakerName(it.speakerId, names)}:\n${it.text}"
        }

    /** mm:ss.d, для записей от часа — h:mm:ss.d */
    fun time(ms: Long): String {
        val s = ms / 1000
        val d = (ms % 1000) / 100
        return if (s >= 3600) "%d:%02d:%02d.%d".format(s / 3600, s / 60 % 60, s % 60, d)
        else "%02d:%02d.%d".format(s / 60, s % 60, d)
    }

    fun exportName(audioName: String): String =
        "${audioName.substringBeforeLast('.', audioName).ifBlank { "transcript" }}.txt"

    /** Индекс реплики, которая звучит в момент [posMs] (или последней начавшейся до него), -1 — до первой. */
    fun currentIndex(sorted: List<Utterance>, posMs: Long): Int {
        var lo = 0; var hi = sorted.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid].startMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Ошибка, текст которой можно показать пользователю как есть. */
class UserFacingException(message: String) : Exception(message)
