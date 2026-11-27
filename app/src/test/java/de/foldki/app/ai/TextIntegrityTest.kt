package de.foldki.app.ai

import de.foldki.app.export.DocumentParser
import org.junit.Assert.*
import org.junit.Test

class TextIntegrityTest {
    private val fence = "\u0060\u0060\u0060"
    private val samples = listOf(
        "A*B", "A**B", "A***B", "A__B", "A\\_\\_B", "***", "__", "___",
        "__name__", "a*b*c", "  A**B  ", "**literal**", "\u0060", "\u0060\u0060",
        fence, fence + "python\n    def test(x):\n        return x ** 2  \n\n" + fence,
        "A*B A**B A***B A__B",
        "~~~python\nx = A**B\n~~~", "print('\\u0060')"
    )
    @Test fun allLiteralSymbolsSurviveActualChatAndCopyMethods() {
        for (raw in samples) {
            val answer = ThinkParser.answerOnly(raw)
            assertEquals(raw, answer)
            assertEquals(raw, TextIntegrity.chat(answer))
            assertEquals(raw, TextIntegrity.copy(answer))
        }
    }
    @Test fun allPrefixesOfDiagnosticStreamStayUnchanged() {
        val raw = samples.joinToString("\n")
        for (end in 1..raw.length) {
            val prefix = raw.substring(0, end)
            val answer = ThinkParser.split(prefix, false, false).answer
            assertEquals(prefix, answer)
            if (TextIntegrity.preserve(prefix)) assertEquals(prefix, TextIntegrity.chat(answer))
            assertEquals(prefix, TextIntegrity.copy(answer))
        }
    }
    @Test fun documentFormattingAndProbeScopeStillWork() {
        assertTrue(TextIntegrity.isProbe("FA_TEXT_DIAG\nA*B"))
        assertFalse(TextIntegrity.isProbe("normal prompt FA_TEXT_DIAG"))
        assertEquals("Frist: 30 Tage", DocumentParser.clean("**Frist:** 30 Tage"))
        assertEquals("normal", TextIntegrity.chat("normal"))
    }
}
