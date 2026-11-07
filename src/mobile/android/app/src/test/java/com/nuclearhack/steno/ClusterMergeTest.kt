package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMergeTest {
    private val segs = listOf(
        Seg(0, 40_000, 0), Seg(40_000, 70_000, 1), Seg(70_000, 71_500, 2), Seg(71_500, 100_000, 0),
    )

    @Test fun smallClusterDetectedByDuration() {
        assertEquals(setOf(2), ClusterMerge.small(ClusterMerge.totals(segs)))
    }

    @Test fun smallByShare() {
        val t = mapOf(0 to 100_000L, 1 to 2_500L) // 2.4% речи
        assertEquals(setOf(1), ClusterMerge.small(t))
    }

    @Test fun allSmallMeansNoMerge() {
        assertTrue(ClusterMerge.small(mapOf(0 to 1000L, 1 to 1500L)).isEmpty())
    }

    @Test fun mapsToNearestByCosine() {
        val emb = mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f), 2 to floatArrayOf(0.1f, 1f))
        assertEquals(mapOf(2 to 1), ClusterMerge.mapping(setOf(2), emb))
    }

    @Test fun applyRenamesSpeakers() {
        val r = ClusterMerge.apply(segs, mapOf(2 to 1))
        assertEquals(listOf(0, 1, 1, 0), r.map { it.speaker })
    }
}
