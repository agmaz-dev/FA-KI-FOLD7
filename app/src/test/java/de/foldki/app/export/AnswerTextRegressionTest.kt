package de.foldki.app.export

import de.foldki.app.ai.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerTextRegressionTest {
    private val operators = listOf("A*B", "A**B", "A***B", "A__B", "A\\_\\_B", "A_B", "A+B", "A-B", "A==B", "A!=B", "A/B", "A//B")
    private val fence = "\u0060\u0060\u0060"

    @Test fun operatorsSurviveModelDisplayAndClipboard() {
        val raw = operators.joinToString("\n")
        val answer = ThinkParser.answerOnly(raw)
        assertEquals(raw, answer)
        assertEquals(raw, DocumentParser.display(answer))
        assertEquals(raw, DocumentParser.copyText(answer))
    }

    @Test fun pythonCodeIsLiteralIncludingWhitespaceAndMarkers() {
        val raw = fence + "python\n    x = A**B  \n    __name__ = '*'\n\n\n    print(\"</think>\")\n---DOKUMENT---\n---ENDE---\n" + fence + "\n"
        val answer = ThinkParser.answerOnly(raw)
        assertEquals(raw, answer)
        assertEquals(raw, DocumentParser.display(answer))
        assertEquals(raw, DocumentParser.copyText(answer))
        assertNull(DocumentParser.parse(answer).document)
    }

    @Test fun inlineTildeAndIndentedCodeAreLiteral() {
        for (raw in listOf("Use \u0060A**B\u0060 or \u0060__name__\u0060.", "~~~python\nx = 2**3\n~~~", "    x = 2**3  \n    # comment\n")) {
            val answer = ThinkParser.answerOnly(raw)
            assertEquals(raw, answer)
            assertEquals(raw, DocumentParser.display(answer))
            assertEquals(raw, DocumentParser.copyText(answer))
        }
    }

    @Test fun streamingChunksKeepOperatorsAndUnclosedCode() {
        val raw = fence + "python\nA**B\nA***B\nA__B\n</think>\n" + fence
        val buffer = StringBuilder()
        for (ch in raw) {
            buffer.append(ch)
            val expected = buffer.toString()
            val answer = ThinkParser.split(expected, thinkOpen = false, finished = false).answer
            assertEquals(expected, answer)
            assertEquals(expected, DocumentParser.display(answer))
        }
    }

    @Test fun actualThinkingTagsStillSplitAndLiteralTagsStay() {
        val raw = fence + "python\nprint(\"<think>literal</think>\")\n" + fence
        val split = ThinkParser.split("<think>reason</think>\n" + raw, true, true)
        assertEquals("reason", split.thoughts)
        assertEquals(raw, split.answer)
    }

    @Test fun normalMarkdownDocumentStillWorksAndCopyIsRaw() {
        val raw = "**Frist:** 30 Tage\n* Punkt *eins*"
        assertEquals("Frist: 30 Tage\n• Punkt eins", DocumentParser.display(raw))
        assertEquals(raw, DocumentParser.copyText(raw))
    }
}
