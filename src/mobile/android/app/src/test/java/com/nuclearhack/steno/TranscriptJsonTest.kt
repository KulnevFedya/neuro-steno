package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {
    @Test fun formatMatchesSpec() {
        val json = TranscriptJson.build(listOf(Utterance("speaker_1", 1250, 4380, "Добрый день!")))
        assertEquals(
            "{\n  \"transcript\": [\n    { \"speaker_id\": \"speaker_1\", \"start_ms\": 1250, \"end_ms\": 4380, \"text\": \"Добрый день!\" }\n  ]\n}\n",
            json,
        )
    }

    @Test fun sortedByStart() {
        val json = TranscriptJson.build(listOf(Utterance("speaker_1", 5000, 6000, "b"), Utterance("speaker_1", 1000, 2000, "a")))
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"b\""))
    }

    @Test fun escapes() {
        assertEquals("\"a\\\"b\\\\c\\n\"", TranscriptJson.quote("a\"b\\c\n"))
    }

    @Test fun emptyTranscript() {
        assertEquals("{\n  \"transcript\": []\n}\n", TranscriptJson.build(emptyList()))
    }

    @Test fun exportName() {
        assertEquals("call.json", TranscriptJson.exportName("call.m4a"))
        assertEquals("a.b.json", TranscriptJson.exportName("a.b.wav"))
        assertEquals("rec.json", TranscriptJson.exportName("rec"))
    }

    @Test fun resamplerRatio() {
        val r = LinearResampler(48000, 16000)
        var total = 0
        repeat(10) { total += r.process(FloatArray(4800)).size }
        assertTrue(total in 15990..16000)
    }
}
