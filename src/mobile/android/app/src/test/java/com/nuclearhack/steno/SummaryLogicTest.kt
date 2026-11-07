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

    @Test fun summaryStopsAtBlankLineOrHeading() {
        assertEquals("Участники обсудили релиз. Срок перенесли.", SummaryLogic.parseSummary("Участники обсудили релиз.\nСрок перенесли.\n\nРешения:\n- х"))
        assertEquals("Участники обсудили релиз.", SummaryLogic.parseSummary("Участники обсудили релиз.\nРешения:\n- х"))
    }

    @Test fun itemsDropCopiedLinesAndNone() {
        val t = "- Перенести релиз на пятницу\n- Спикер 1: Предлагаю по очереди.\n• Нанять тестировщика\n- нет\n- Перенести релиз на пятницу\nЗадачи:"
        assertEquals(listOf("Перенести релиз на пятницу", "Нанять тестировщика"), SummaryLogic.parseItems(t))
        assertTrue(SummaryLogic.parseItems("- none").isEmpty())
    }

    @Test fun itemsAreLimitedToFive() {
        assertEquals(5, SummaryLogic.parseItems((1..9).joinToString("\n") { "- пункт $it" }).size)
    }

    @Test fun promptsEndWithPrefill() {
        for (k in SummaryLogic.Kind.values()) for (ru in listOf(true, false)) {
            val p = SummaryLogic.finalPrompt(k, "заметки", ru)
            assertTrue(p.contains("<|im_start|>assistant\n"))
            assertTrue(!p.endsWith("<|im_end|>\n"))
        }
    }

    @Test fun jsonIsEscaped() {
        val j = SummaryLogic.toJson(SummaryLogic.Summary("a \"b\"", listOf("x"), emptyList()), "ru", 2, 5)
        assertTrue(j.contains("\"summary\": \"a \\\"b\\\"\""))
        assertEquals("видео.summary.json", SummaryLogic.summaryName("видео.mp3"))
    }
}

class NotesCleanTest {
    @Test fun dropsInstructionEchoAndCopiedLines() {
        val raw = "- Обсудили закупку корма\n- Каждый пункт с новой строки\n- Спикер 2: Отлично.\n- Запиши конспект фрагмента короткими пунктами: что обсуждали, какие решения приняты, кто что должен сделать.\n- Иван готовит отчёт"
        val clean = SummaryLogic.cleanNotes(raw)
        assertEquals("- Обсудили закупку корма\n- Каждый пункт с новой строки\n- Иван готовит отчёт", clean)
    }

    @Test fun itemsDropEchoOfInstructions() {
        val items = SummaryLogic.parseItems("- Если решений не было, напиши «- нет».\n- Перенести релиз")
        assertEquals(listOf("Перенести релиз"), items)
    }
}
