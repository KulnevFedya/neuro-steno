package com.nuclearhack.steno

/** Одна реплика итоговой расшифровки. Время — миллисекунды от начала файла. */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
