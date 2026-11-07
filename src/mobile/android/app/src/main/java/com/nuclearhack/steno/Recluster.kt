package com.nuclearhack.steno

import kotlin.math.sqrt

/**
 * Пересчёт кластеров поверх sherpa-onnx на эмбеддингах сегментов (чистая логика; = eval/tune_diar.py).
 * Эмбеддинг — по центральной части сегмента (без краёв по 0.2 с, до 10 с), только для сегментов >= 1 с.
 * Шаги: слияние центроидов с cos > [MERGE_COS] (и обязательно, пока кластеров > kmax) → мелкие кластеры
 * (< 8% речи или < 4 с) к ближайшему → один кластер делим на 2 → куски VAD, не покрытые диаризацией
 * ([FILL], >= 2 с), к ближайшему центроиду → сегмент к чужому центроиду, если тот ближе на > [REASSIGN_MARGIN] →
 * короткие сегменты: метка своего кластера, а без неё — соседа по времени.
 */
object Recluster {
    const val FILL = -1
    const val MIN_EMB_MS = 1000L
    const val EDGE_MS = 200L
    const val MAX_EMB_MS = 10_000L
    const val FILL_MIN_MS = 500L
    /** Добор VAD короче — без эмбеддинга, метка соседа по времени (обычно это хвост соседнего сегмента). */
    const val FILL_EMB_MS = 2000L
    const val MERGE_COS = 0.68f
    const val REASSIGN_MARGIN = 0.1f
    private const val MAX_SPLIT_ITEMS = 200

    data class Params(val merge: Float, val minShare: Double, val minTotalMs: Long, val kmin: Int, val kmax: Int)

    /** Авто: 2..6 спикеров. */
    val AUTO = Params(MERGE_COS, 0.08, 4000L, 2, 6)

    /** Заданное число спикеров: сливаем до [n]; мелкие поглощаем, только пока кластеров больше [n]. */
    fun fixed(n: Int, absorbSmall: Boolean = true) =
        Params(Float.MAX_VALUE, if (absorbSmall) AUTO.minShare else 0.0, if (absorbSmall) AUTO.minTotalMs else 0L, n, n)

    fun embWindow(s: Seg): LongRange? {
        if (s.endMs - s.startMs < (if (s.speaker == FILL) FILL_EMB_MS else MIN_EMB_MS)) return null
        var a = s.startMs + EDGE_MS
        var b = s.endMs - EDGE_MS
        if (b - a > MAX_EMB_MS) {
            val c = (a + b) / 2
            a = c - MAX_EMB_MS / 2; b = c + MAX_EMB_MS / 2
        }
        return a..b
    }

    /**
     * Куски VAD-речи, не покрытые сегментами диаризации, длиной >= [minMs] — с меткой [FILL]. «Мостики» между
     * двумя сегментами диаризации короче [FILL_EMB_MS] пропускаем: это пауза на смене реплик, склеенная VAD
     * (иначе реплика тянется до начала следующей — хуже и DER, и таймкоды).
     */
    fun uncovered(vad: List<LongRange>, raw: List<Seg>, minMs: Long = FILL_MIN_MS): List<Seg> {
        val cov = raw.sortedBy { it.startMs }
        val res = ArrayList<Seg>()
        for (v in vad) {
            var cur = v.first
            var bounded = false
            for (s in cov) {
                if (s.endMs <= cur || s.startMs >= v.last) continue
                val gap = s.startMs - cur
                if (gap >= minMs && (!bounded || gap >= FILL_EMB_MS)) res.add(Seg(cur, s.startMs, FILL))
                cur = maxOf(cur, s.endMs)
                bounded = true
            }
            if (v.last - cur >= minMs) res.add(Seg(cur, v.last, FILL))
        }
        return res
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return d / (sqrt(na) * sqrt(nb) + 1e-9f)
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat() + 1e-9f
        return FloatArray(v.size) { v[it] / n }
    }

    private class Cl(val sum: FloatArray, var dur: Long)

    /**
     * [segs] — отсортированы по началу, speaker — метка sherpa или [FILL]; [embs] — индекс → единичный
     * эмбеддинг (для сегментов с [embWindow]). Возвращает сегменты с новыми метками (без потерянных FILL).
     */
    fun run(segs: List<Seg>, embs: Map<Int, FloatArray>, p: Params): List<Seg> {
        val dur = HashMap<Int, Long>()
        for (s in segs) if (s.speaker != FILL) dur.merge(s.speaker, s.endMs - s.startMs, Long::plus)
        val cl = LinkedHashMap<Int, Cl>()
        for ((i, v) in embs.toSortedMap()) {
            val k = segs[i].speaker
            if (k == FILL) continue
            val c = cl.getOrPut(k) { Cl(FloatArray(v.size), dur.getValue(k)) }
            val w = (segs[i].endMs - segs[i].startMs).toFloat()
            for (j in v.indices) c.sum[j] += v[j] * w
        }
        if (cl.isEmpty()) return segs.filter { it.speaker != FILL }
        val parent = HashMap<Int, Int>().apply { for (k in cl.keys) put(k, k) }

        fun cos(a: Int, b: Int) = cosine(cl.getValue(a).sum, cl.getValue(b).sum)
        fun join(a: Int, b: Int) { // b -> a
            val ca = cl.getValue(a); val cb = cl.remove(b)!!
            for (j in ca.sum.indices) ca.sum[j] += cb.sum[j]
            ca.dur += cb.dur
            for (e in parent.entries) if (e.value == b) e.setValue(a)
        }

        // 1. слияние близких центроидов (обязательно, пока > kmax)
        while (cl.size > p.kmin) {
            val keys = cl.keys.toList()
            var best = Float.NEGATIVE_INFINITY; var ba = 0; var bb = 0
            for (x in keys.indices) for (y in x + 1 until keys.size) {
                val c = cos(keys[x], keys[y])
                if (c > best) { best = c; ba = keys[x]; bb = keys[y] }
            }
            if (cl.size <= p.kmax && best <= p.merge) break
            if (cl.getValue(ba).dur >= cl.getValue(bb).dur) join(ba, bb) else join(bb, ba)
        }
        // 2. мелкие кластеры → к ближайшему
        while (cl.size > p.kmin) {
            val total = cl.values.sumOf { it.dur }
            val k = cl.entries.filter { it.value.dur < p.minTotalMs || it.value.dur < p.minShare * total }
                .minByOrNull { it.value.dur }?.key ?: break
            join(cl.keys.filter { it != k }.maxBy { cos(k, it) }, k)
        }
        val lab = HashMap<Int, Int>()
        for (i in embs.keys) if (segs[i].speaker != FILL) lab[i] = parent.getValue(segs[i].speaker)
        var cen = cl.mapValues { normalize(it.value.sum) }
        // 3. один кластер → делим на 2 (средняя связь по самым длинным сегментам)
        var split = false
        if (cl.size < p.kmin && lab.size >= 2) {
            val items = lab.keys.sortedByDescending { segs[it].endMs - segs[it].startMs }.take(MAX_SPLIT_ITEMS)
            val groups = split(items.map { embs.getValue(it) }, p.kmin)
            cen = groups.withIndex().associate { (g, grp) ->
                SPLIT_BASE + g to normalize(FloatArray(embs.getValue(items[0]).size) { j -> grp.sumOf { embs.getValue(items[it])[j].toDouble() }.toFloat() })
            }
            for (i in lab.keys) lab[i] = cen.maxBy { dot(embs.getValue(i), it.value) }.key
            split = true
        }
        // 4. добор VAD → ближайший центроид; 5. переназначение с запасом
        for ((i, v) in embs) {
            if (segs[i].speaker == FILL) lab[i] = cen.maxBy { dot(v, it.value) }.key
            else if (!split) {
                val own = lab.getValue(i)
                val b = cen.maxBy { dot(v, it.value) }.key
                if (dot(v, cen.getValue(b)) - dot(v, cen.getValue(own)) > REASSIGN_MARGIN) lab[i] = b
            }
        }
        // 6. короткие: метка своего кластера (если он выжил) или ближайшего по времени размеченного сегмента
        val labeled = lab.keys.sorted()
        return segs.mapIndexed { i, s ->
            val l = lab[i] ?: parent[s.speaker]?.takeIf { !split && it in cen }
                ?: lab.getValue(labeled.minBy { j -> maxOf(segs[j].startMs - s.endMs, s.startMs - segs[j].endMs, 0L) })
            s.copy(speaker = l)
        }
    }

    private const val SPLIT_BASE = 1_000_000

    private fun dot(a: FloatArray, b: FloatArray): Float { var d = 0f; for (j in a.indices) d += a[j] * b[j]; return d }

    /** Агломеративная кластеризация (средняя связь, косинус) до [k] групп; возвращает индексы элементов. */
    fun split(v: List<FloatArray>, k: Int): List<List<Int>> {
        val n = v.size
        val sim = Array(n) { a -> FloatArray(n) { b -> dot(v[a], v[b]) } }
        val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
        val alive = (0 until n).toMutableList()
        while (alive.size > k) {
            var best = Float.NEGATIVE_INFINITY; var ba = 0; var bb = 0
            for (x in alive.indices) for (y in x + 1 until alive.size) {
                val s = sim[alive[x]][alive[y]]
                if (s > best) { best = s; ba = alive[x]; bb = alive[y] }
            }
            val na = groups[ba].size.toFloat(); val nb = groups[bb].size.toFloat()
            for (c in alive) if (c != ba && c != bb) { // Ланс–Уильямс для средней связи
                sim[ba][c] = (na * sim[ba][c] + nb * sim[bb][c]) / (na + nb); sim[c][ba] = sim[ba][c]
            }
            groups[ba].addAll(groups[bb]); alive.remove(bb)
        }
        return alive.map { groups[it] }
    }
}
