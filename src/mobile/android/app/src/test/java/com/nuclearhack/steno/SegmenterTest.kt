package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterTest {
    @Test fun mergesSameSpeakerWithShortGap() {
        val r = Segmenter.build(listOf(Seg(0, 2000, 0), Seg(2500, 4000, 0), Seg(5600, 7000, 1)))
        assertEquals(listOf(Seg(0, 4000, 0), Seg(5600, 7000, 1)), r)
    }

    @Test fun keepsSameSpeakerAfterLongPause() {
        val r = Segmenter.build(listOf(Seg(0, 2000, 0), Seg(3500, 4000, 0)))
        assertEquals(2, r.size)
    }

    @Test fun shortSegmentGoesToNearestNeighbour() {
        val r = Segmenter.build(listOf(Seg(0, 2000, 0), Seg(2100, 2300, 1), Seg(3000, 5000, 1)))
        assertEquals(listOf(Seg(0, 2000, 0), Seg(2100, 5000, 1)), r)
    }

    @Test fun speakerIdsByFirstAppearance() {
        val ids = Segmenter.speakerIds(listOf(Seg(0, 1, 2), Seg(1, 2, 0), Seg(2, 3, 2)))
        assertEquals(mapOf(2 to "speaker_1", 0 to "speaker_2"), ids)
    }

    @Test fun paddingClamped() {
        assertEquals(0L..1150L, Segmenter.padded(Seg(100, 1000, 0), 1100 + 50))
        assertEquals(0L..1100L, Segmenter.padded(Seg(100, 1000, 0), 1100))
    }
}
