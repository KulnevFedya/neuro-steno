package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptTextTest {
    private val us = listOf(
        Utterance("speaker_2", 4000, 6500, "Да, конечно."),
        Utterance("speaker_1", 1250, 3800, "Добрый день!"),
    )

    @Test fun defaultAndCustomNames() {
        assertEquals("Спикер 1", TranscriptText.speakerName("speaker_1", emptyMap()))
        assertEquals("Анна", TranscriptText.speakerName("speaker_1", mapOf("speaker_1" to " Анна ")))
        assertEquals("Спикер 2", TranscriptText.speakerName("speaker_2", mapOf("speaker_2" to "  ")))
    }

    @Test fun txtSortedWithNames() {
        val txt = TranscriptText.build(us, mapOf("speaker_1" to "Анна"))
        assertEquals("[00:01.2 – 00:03.8] Анна:\nДобрый день!\n\n[00:04.0 – 00:06.5] Спикер 2:\nДа, конечно.\n", txt)
    }

    @Test fun renameDoesNotTouchJson() {
        val json = TranscriptJson.build(us)
        assertFalse(json.contains("Анна"))
        assertEquals(us.sortedBy { it.startMs }, TranscriptJson.parse(json))
    }

    @Test fun timeFormat() {
        assertEquals("00:00.0", TranscriptText.time(0))
        assertEquals("1:01:05.5", TranscriptText.time(3_665_500))
    }

    @Test fun currentIndex() {
        val s = us.sortedBy { it.startMs }
        assertEquals(-1, TranscriptText.currentIndex(s, 100))
        assertEquals(0, TranscriptText.currentIndex(s, 1250))
        assertEquals(0, TranscriptText.currentIndex(s, 3900))
        assertEquals(1, TranscriptText.currentIndex(s, 9000))
    }

    @Test fun txtName() = assertEquals("call.txt", TranscriptText.exportName("call.m4a"))
}
