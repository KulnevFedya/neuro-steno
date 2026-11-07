package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

class LongSegmentTest {
    @Test fun monologueStaysOneSegmentAndNeedsVad() {
        // один спикер, паузы 0.3 с -> одна склеенная реплика 60 с
        val raw = (0 until 12).map { Seg(it * 5300L, it * 5300L + 5000, 0) }
        val segs = Segmenter.build(raw)
        assertEquals(1, segs.size)
        assertTrue(Segmenter.needsVadSplit(Segmenter.padded(segs[0], 70_000)))
        assertTrue(!Segmenter.needsVadSplit(0L..30_000L))
    }

    /** VAD-заглушка: каждые 4 окна «речи» отдаёт фрагмент; хвост — при flush. */
    private class FakeVad : Segmenter.SpeechDetector {
        val buf = ArrayList<FloatArray>(); val ready = ArrayList<FloatArray>(); var resets = 0
        override fun accept(window: FloatArray) { buf.add(window); if (buf.size == 4) cut() }
        private fun cut() { ready.add(buf.flatMap { it.asList() }.toFloatArray()); buf.clear() }
        override fun drain(): List<FloatArray> = ready.toList().also { ready.clear() }
        override fun flush() { if (buf.isNotEmpty()) cut() }
        override fun reset() { resets++ }
    }

    @Test fun longSegmentIsSplitThroughVadAndJoined() {
        val x = FloatArray(512 * 10) // 10 окон -> 4+4+2
        val vad = FakeVad()
        val sizes = ArrayList<Int>()
        val text = Segmenter.recognizeLong(x, 512, vad) { sizes.add(it.size); "ч${sizes.size}" }
        assertEquals(listOf(2048, 2048, 1024), sizes)
        assertEquals("ч1 ч2 ч3", text)
        assertEquals(1, vad.resets)
    }

    @Test fun trailingPartialWindowIsDropped() {
        val vad = FakeVad(); var calls = 0
        Segmenter.recognizeLong(FloatArray(512 * 2 + 100), 512, vad) { calls++; "x" }
        assertEquals(1, calls) // 2 полных окна -> flush -> один фрагмент
    }
}
