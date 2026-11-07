package com.nuclearhack.steno

/** Сегмент речи одного спикера (мс от начала файла). */
data class Seg(var startMs: Long, var endMs: Long, val speaker: Int)

/** Постобработка сегментов диаризации (чистая логика, без Android). */
object Segmenter {
    const val MIN_MS = 300L
    const val GAP_MS = 1000L
    const val PAD_MS = 150L
    const val MAX_ASR_MS = 30_000L

    /** Склейка соседних сегментов одного спикера с паузой < [GAP_MS], сегментов < [MIN_MS] — с ближайшим соседом. */
    fun build(raw: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        fun joinSame(list: List<Seg>) {
            out.clear()
            for (s in list) {
                val last = out.lastOrNull()
                if (last != null && last.speaker == s.speaker && s.startMs - last.endMs < GAP_MS) last.endMs = maxOf(last.endMs, s.endMs)
                else out.add(s.copy())
            }
        }
        joinSame(raw.sortedBy { it.startMs })
        var i = 0
        while (i < out.size) {
            val s = out[i]
            if (s.endMs - s.startMs < MIN_MS && out.size > 1) {
                val prev = out.getOrNull(i - 1)
                val next = out.getOrNull(i + 1)
                val gp = if (prev != null) s.startMs - prev.endMs else Long.MAX_VALUE
                val gn = if (next != null) next.startMs - s.endMs else Long.MAX_VALUE
                val tgt = if (gp <= gn) prev else next
                if (tgt != null && minOf(gp, gn) < GAP_MS) {
                    tgt.startMs = minOf(tgt.startMs, s.startMs)
                    tgt.endMs = maxOf(tgt.endMs, s.endMs)
                    out.removeAt(i)
                    i = maxOf(i - 1, 0)
                    continue
                }
            }
            i++
        }
        joinSame(out.toList())
        return out
    }

    /** Окно для ASR с паддингом, обрезанное по границам файла. */
    fun padded(s: Seg, totalMs: Long): LongRange = maxOf(0L, s.startMs - PAD_MS)..minOf(totalMs, s.endMs + PAD_MS)

    /** speaker_N по порядку первого появления. */
    fun speakerIds(segs: List<Seg>): Map<Int, String> {
        val m = LinkedHashMap<Int, String>()
        for (s in segs) if (s.speaker !in m) m[s.speaker] = "speaker_${m.size + 1}"
        return m
    }

    fun needsVadSplit(w: LongRange) = w.last - w.first > MAX_ASR_MS

    /** Абстракция над Silero VAD (в тестах — заглушка). */
    interface SpeechDetector {
        fun accept(window: FloatArray)
        /** Забирает готовые речевые фрагменты. */
        fun drain(): List<FloatArray>
        fun flush()
        fun reset()
    }

    /** Сегмент > 30 с: прогоняем окнами через VAD, каждый речевой фрагмент — в [recognize]. */
    fun recognizeLong(x: FloatArray, window: Int, vad: SpeechDetector, recognize: (FloatArray) -> String): String {
        val parts = ArrayList<String>()
        fun drain() { for (f in vad.drain()) recognize(f).takeIf { it.isNotBlank() }?.let(parts::add) }
        var off = 0
        while (off + window <= x.size) {
            vad.accept(x.copyOfRange(off, off + window)); off += window
            drain()
        }
        vad.flush(); drain()
        vad.reset()
        return parts.joinToString(" ")
    }
}
