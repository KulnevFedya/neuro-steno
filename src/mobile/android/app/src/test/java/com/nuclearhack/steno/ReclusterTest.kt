package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReclusterTest {
    private val a = floatArrayOf(1f, 0f, 0f)
    private val a2 = Recluster.normalize(floatArrayOf(0.9f, 0.3f, 0f)) // cos(a, a2) ≈ 0.95
    private val b = floatArrayOf(0f, 1f, 0f)
    private val c = floatArrayOf(0f, 0f, 1f)

    private fun embsOf(segs: List<Seg>, vecs: List<FloatArray?>) =
        vecs.withIndex().filter { it.value != null && Recluster.embWindow(segs[it.index]) != null }.associate { it.index to it.value!! }

    @Test fun embWindowTrimsEdgesAndSkipsShort() {
        assertNull(Recluster.embWindow(Seg(0, 999, 0)))
        assertNull(Recluster.embWindow(Seg(0, 1500, Recluster.FILL)))
        assertEquals(200L..1800L, Recluster.embWindow(Seg(0, 2000, 0)))
        val w = Recluster.embWindow(Seg(0, 30_000, 0))!!
        assertEquals(10_000L, w.last - w.first)
        assertEquals(15_000L, (w.first + w.last) / 2)
    }

    @Test fun mergesCloseCentroids() {
        // sherpa разбил спикера A на кластеры 0 и 2
        val segs = listOf(Seg(0, 20_000, 0), Seg(20_000, 40_000, 1), Seg(40_000, 60_000, 2), Seg(60_000, 80_000, 1))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, b, a2, b)), Recluster.AUTO)
        assertEquals(listOf(0, 1, 0, 1), r.map { it.speaker })
    }

    @Test fun keepsDistinctSpeakers() {
        val segs = listOf(Seg(0, 20_000, 0), Seg(20_000, 40_000, 1), Seg(40_000, 60_000, 2))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, b, c)), Recluster.AUTO)
        assertEquals(3, r.map { it.speaker }.distinct().size)
    }

    @Test fun smallClusterGoesToNearest() {
        // кластер 2: 3 с из 63 (< 4 с) → к ближайшему (1)
        val b2 = Recluster.normalize(floatArrayOf(0.3f, 0.8f, 0.5f)) // cos(b2,c)≈0.5 < MERGE_COS, cos(b2,a)≈0.3
        val segs = listOf(Seg(0, 30_000, 0), Seg(30_000, 60_000, 1), Seg(60_000, 63_000, 2))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, c, b2)), Recluster.AUTO)
        assertEquals(listOf(0, 1, 1), r.map { it.speaker })
    }

    @Test fun capsAtMaxSpeakers() {
        val vecs = (0 until 8).map { k -> FloatArray(8) { if (it == k) 1f else 0f } }
        val segs = (0 until 8).map { Seg(it * 10_000L, (it + 1) * 10_000L, it) }
        val r = Recluster.run(segs, embsOf(segs, vecs), Recluster.AUTO)
        assertEquals(6, r.map { it.speaker }.distinct().size)
    }

    @Test fun fixedNumberMergesDownToN() {
        val segs = listOf(Seg(0, 20_000, 0), Seg(20_000, 40_000, 1), Seg(40_000, 60_000, 2))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, b, a2)), Recluster.fixed(2))
        assertEquals(listOf(0, 1, 0), r.map { it.speaker })
    }

    @Test fun singleClusterIsSplitInTwo() {
        val segs = listOf(Seg(0, 5000, 0), Seg(5000, 10_000, 0), Seg(10_000, 15_000, 0), Seg(15_000, 20_000, 0))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, b, a2, b)), Recluster.AUTO)
        val l = r.map { it.speaker }
        assertEquals(l[0], l[2]); assertEquals(l[1], l[3]); assert(l[0] != l[1])
    }

    @Test fun shortSegmentKeepsOwnClusterAndFillTakesNearest() {
        val segs = listOf(
            Seg(0, 20_000, 0), Seg(20_000, 20_500, 1), Seg(21_000, 40_000, 1),
            Seg(40_000, 45_000, Recluster.FILL), Seg(45_000, 45_400, Recluster.FILL),
        )
        val r = Recluster.run(segs, embsOf(segs, listOf(a, null, b, a2, null)), Recluster.AUTO)
        assertEquals(listOf(0, 1, 1, 0, 0), r.map { it.speaker })
    }

    @Test fun reassignsSegmentCloserToOtherCentroid() {
        // кластер 1 нечистый: третий сегмент на самом деле A
        val segs = listOf(Seg(0, 20_000, 0), Seg(20_000, 40_000, 1), Seg(40_000, 45_000, 1), Seg(45_000, 65_000, 1))
        val r = Recluster.run(segs, embsOf(segs, listOf(a, b, a2, b)), Recluster.AUTO)
        assertEquals(listOf(0, 1, 0, 1), r.map { it.speaker })
    }

    @Test fun uncoveredReturnsGapsInsideVad() {
        val raw = listOf(Seg(1000, 3000, 0), Seg(5000, 6000, 1))
        val f = Recluster.uncovered(listOf(0L..8000L, 9000L..9300L), raw)
        assertEquals(listOf(0L to 1000L, 3000L to 5000L, 6000L to 8000L), f.map { it.startMs to it.endMs })
        assert(f.all { it.speaker == Recluster.FILL })
    }

    @Test fun shortBridgeBetweenSegmentsIsSkipped() {
        val raw = listOf(Seg(1000, 3000, 0), Seg(4000, 6000, 1))
        val f = Recluster.uncovered(listOf(1000L..6800L), raw)
        assertEquals(listOf(6000L to 6800L), f.map { it.startMs to it.endMs })
    }

    @Test fun splitByAverageLinkage() {
        val g = Recluster.split(listOf(a, b, a2, b), 2).map { it.sorted() }.sortedBy { it[0] }
        assertEquals(listOf(listOf(0, 2), listOf(1, 3)), g)
    }
}
