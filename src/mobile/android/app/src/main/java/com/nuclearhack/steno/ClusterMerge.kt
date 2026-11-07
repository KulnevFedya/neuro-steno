package com.nuclearhack.steno

import kotlin.math.sqrt

/** Переприсвоение мелких кластеров ближайшему крупному (чистая логика; эмбеддинги приходят снаружи). */
object ClusterMerge {
    const val MIN_SHARE = 0.03
    const val MIN_TOTAL_MS = 2000L

    fun totals(segs: List<Seg>): Map<Int, Long> =
        segs.groupBy { it.speaker }.mapValues { (_, l) -> l.sumOf { it.endMs - it.startMs } }

    /** Мелкие: < 3% речи или < 2 с суммарно. Если крупных нет — пусто (ничего не склеиваем). */
    fun small(totals: Map<Int, Long>): Set<Int> {
        val all = totals.values.sum().coerceAtLeast(1)
        val s = totals.filter { it.value < MIN_TOTAL_MS || it.value.toDouble() / all < MIN_SHARE }.keys
        return if (s.size == totals.size) emptySet() else s
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return d / (sqrt(na) * sqrt(nb) + 1e-9f)
    }

    /** Отображение мелкий кластер → ближайший крупный по косинусу средних эмбеддингов. */
    fun mapping(small: Set<Int>, emb: Map<Int, FloatArray>): Map<Int, Int> {
        val large = emb.keys - small
        if (large.isEmpty()) return emptyMap()
        return small.filter { it in emb }.associateWith { s -> large.maxBy { cosine(emb.getValue(s), emb.getValue(it)) } }
    }

    fun apply(segs: List<Seg>, map: Map<Int, Int>): List<Seg> = segs.map { map[it.speaker]?.let { t -> it.copy(speaker = t) } ?: it }
}
