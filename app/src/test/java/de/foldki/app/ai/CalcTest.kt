package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {
    @Test fun fixesWrongResults() {
        assertEquals("3 × 12,50 € = 37,50 €", Calc.fixEquations("3 × 12,50 € = 36,50 €"))
        assertEquals("(10 + 5) * 2 = 30", Calc.fixEquations("(10 + 5) * 2 = 25"))
        assertEquals("1.250 € / 5 = 250 €", Calc.fixEquations("1.250 € / 5 = 260 €"))
        assertEquals("Zwischensumme 450 - 30 = 420", Calc.fixEquations("Zwischensumme 450 - 30 = 400"))
    }

    @Test fun keepsCorrectAndNonMath() {
        assertEquals("Summe: 120 + 85,40 + 39,99 = 245,39 €", Calc.fixEquations("Summe: 120 + 85,40 + 39,99 = 245,39 €"))
        assertEquals("2 x 7 = 14", Calc.fixEquations("2 x 7 = 14"))
        assertEquals("Termin am 23.11.2026 um 12:30", Calc.fixEquations("Termin am 23.11.2026 um 12:30"))
        assertEquals("Tel 030-123456 = Büro", Calc.fixEquations("Tel 030-123456 = Büro"))
    }

    @Test fun hintComputesQuestion() {
        val h = Calc.hint("Was ist 17,5 * 3 + 4?")
        assertTrue(h, h.contains("17,5 * 3 + 4 = 56,50"))
        assertEquals("", Calc.hint("Ich habe am 23.11. um 12:30 einen Termin"))
    }

    @Test fun cutsInventedResultAfterToolCall() {
        val a = "Ich schaue nach.\nWERKZEUG: {\"name\":\"list_my_repositories\"}\nERGEBNIS: [Repo 1], [Repo 2]"
        assertEquals("Ich schaue nach.\nWERKZEUG: {\"name\":\"list_my_repositories\"}", GemmaGitHubAgent.cutAtCall(a))
        assertTrue(GemmaGitHubAgent.relevantTools("Zeig mir den letzten Build meiner App").contains("list_workflow_runs"))
    }
}
