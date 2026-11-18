package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkParserTest {
    @Test fun thoughtsWithoutOpeningTag() {
        val s = ThinkParser.split("Der Nutzer fragt nach 2+2. Das ist 4.\n</think>\n\nDas Ergebnis ist 4.", thinkOpen = true, finished = true)
        assertEquals("Der Nutzer fragt nach 2+2. Das ist 4.", s.thoughts)
        assertEquals("Das Ergebnis ist 4.", s.answer)
        assertFalse(s.thinking)
    }

    @Test fun thoughtsWithOpeningTag() {
        val s = ThinkParser.split("<think>\nÜberlegung\n</think>\nAntwort", thinkOpen = false, finished = true)
        assertEquals("Überlegung", s.thoughts)
        assertEquals("Antwort", s.answer)
    }

    @Test fun streamingWhileThinking() {
        val s = ThinkParser.split("Ich überlege noch", thinkOpen = true, finished = false)
        assertTrue(s.thinking)
        assertEquals("Ich überlege noch", s.thoughts)
        assertEquals("", s.answer)
    }

    @Test fun streamingWithOpenTagOnly() {
        val s = ThinkParser.split("<think>Hmm", thinkOpen = false, finished = false)
        assertTrue(s.thinking)
        assertEquals("Hmm", s.thoughts)
    }

    @Test fun partialClosingTagIsHidden() {
        val s = ThinkParser.split("Gedanke </thi", thinkOpen = true, finished = false)
        assertEquals("Gedanke", s.thoughts)
    }

    @Test fun noThinkAtAllIsAnswer() {
        val s = ThinkParser.split("Hallo, wie kann ich helfen?", thinkOpen = false, finished = false)
        assertFalse(s.thinking)
        assertEquals("Hallo, wie kann ich helfen?", s.answer)
        assertEquals("", s.thoughts)
    }

    @Test fun finishedWithoutClosingTagIsAllAnswer() {
        val s = ThinkParser.split("Langer Gedanke ohne Ende", thinkOpen = true, finished = true)
        assertEquals("", s.thoughts)
        assertEquals("Langer Gedanke ohne Ende", s.answer)
        assertFalse(s.thinking)
    }

    @Test fun closingTagInNonThinkingModel() {
        val s = ThinkParser.split("A\n</think>\nB", thinkOpen = false, finished = false)
        assertEquals("A", s.thoughts)
        assertEquals("B", s.answer)
    }

    @Test fun emptyThinkBlock() {
        val s = ThinkParser.split("<think>\n\n</think>\n\nNur Antwort", thinkOpen = false, finished = true)
        assertEquals("", s.thoughts)
        assertEquals("Nur Antwort", s.answer)
    }

    @Test fun secondBlockInAnswerRemoved() {
        val s = ThinkParser.split("x</think>Teil 1 <think>y</think>Teil 2", thinkOpen = true, finished = true)
        assertEquals("Teil 1 Teil 2", s.answer)
    }

    @Test fun answerOnlyHelper() {
        assertEquals("Suchbegriffe", ThinkParser.answerOnly("denke</think>Suchbegriffe", thinkOpen = true).trim())
        assertEquals("ohne", ThinkParser.answerOnly("ohne"))
    }
}
