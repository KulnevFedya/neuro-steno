package com.nuclearhack.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryLogicTest {
    private val words = { s: String -> s.split(' ').size }

    @Test fun detectsLanguage() {
        assertTrue(SummaryLogic.isRussian(listOf(Utterance("speaker_1", 0, 1, "Добрый день, коллеги."))))
        assertTrue(!SummaryLogic.isRussian(listOf(Utterance("speaker_1", 0, 1, "Good morning, everyone."))))
    }

    @Test fun chunksRespectBudget() {
        val lines = List(10) { "один два три четыре пять" } // 5 «токенов» + 1 на перевод строки = 6
        val ch = SummaryLogic.chunk(lines, 20, words)
        assertTrue(ch.all { it.lines().sumOf { l -> words(l) + 1 } <= 20 })
        assertEquals(10, ch.sumOf { it.lines().size })
        assertEquals(4, ch.size) // по 3 строки (18) -> 3+3+3+1
    }

    @Test fun longLineIsSplitByWords() {
        val ch = SummaryLogic.chunk(listOf("а б в г д е ж з и к"), 4, words)
        assertTrue(ch.size >= 3)
        assertEquals("а б в г д е ж з и к", ch.joinToString(" ") { it.trim() })
    }

    @Test fun parsesSections() {
        val s = SummaryLogic.parse(
            "Краткое содержание: Обсудили релиз. Срок перенесли.\nРешения:\n- Перенести релиз на пятницу\n- Нанять тестировщика\nЗадачи:\n- Иван готовит отчёт\n"
        )
        assertEquals("Обсудили релиз. Срок перенесли.", s.summary)
        assertEquals(listOf("Перенести релиз на пятницу", "Нанять тестировщика"), s.decisions)
        assertEquals(listOf("Иван готовит отчёт"), s.tasks)
    }

    @Test fun parsesNoneAndEnglish() {
        val s = SummaryLogic.parse("Summary: They agreed.\nDecisions:\n- none\nTasks:\n- Bob sends the file")
        assertEquals("They agreed.", s.summary)
        assertTrue(s.decisions.isEmpty())
        assertEquals(listOf("Bob sends the file"), s.tasks)
    }

    @Test fun freeTextFallsIntoSummary() {
        assertEquals("просто текст без формата", SummaryLogic.parse("просто текст без формата").summary)
    }

    @Test fun jsonIsEscaped() {
        val j = SummaryLogic.toJson(SummaryLogic.Summary("a \"b\"", listOf("x"), emptyList()), "ru", 2, 5)
        assertTrue(j.contains("\"summary\": \"a \\\"b\\\"\""))
        assertEquals("видео.summary.json", SummaryLogic.summaryName("видео.mp3"))
    }
}
