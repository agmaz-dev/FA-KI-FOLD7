package de.foldki.app.ai

/**
 * Splits the raw output of a local model into "Gedanken" (reasoning) and the answer.
 *
 * Thinking models (Qwen3 Thinking & Co.) write their reasoning first and then `</think>`; the opening
 * `<think>` is often missing because the chat template already put it into the prompt ([thinkOpen]).
 * Rules (robust for any model):
 * - Text up to the first `</think>` = thoughts (a leading `<think>` is removed), the rest = answer.
 * - While streaming without `</think>`: if the output started with `<think>` or the template opened it,
 *   everything so far counts as thoughts ("Denkt nach…"), otherwise as answer.
 * - Finished without any `</think>`: everything is the answer (no thoughts at all).
 */
object ThinkParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    data class Split(val thoughts: String, val answer: String, val thinking: Boolean)

    fun split(raw: String, thinkOpen: Boolean, finished: Boolean): Split {
        val lead = raw.trimStart()
        val startsWithTag = lead.startsWith(OPEN)
        val body = if (startsWithTag) lead.removePrefix(OPEN) else raw
        val close = body.indexOf(CLOSE)
        if (close >= 0) {
            val thoughts = body.substring(0, close).trim()
            // A second (rare) think block inside the answer is removed as well.
            val answer = stripBlocks(body.substring(close + CLOSE.length)).trimStart()
            return Split(thoughts, answer, thinking = false)
        }
        if (finished) return Split("", clean(body).trim(), thinking = false)
        return if (startsWithTag || thinkOpen) Split(clean(body).trim(), "", thinking = true)
        else Split("", clean(body), thinking = false)
    }

    /** Only the answer part (internal tasks: search terms, letter reading, briefing, GitHub steps). */
    fun answerOnly(raw: String, thinkOpen: Boolean = false): String = split(raw, thinkOpen, finished = true).answer

    private fun stripBlocks(s: String): String {
        var t = s
        while (true) {
            val a = t.indexOf(OPEN)
            if (a < 0) break
            val b = t.indexOf(CLOSE, a)
            t = if (b < 0) t.substring(0, a) else t.substring(0, a) + t.substring(b + CLOSE.length)
        }
        return clean(t)
    }

    /** Partial tags at the end of a stream ("</thi") are not shown. */
    private fun clean(s: String): String {
        var t = s.replace(OPEN, "").replace(CLOSE, "")
        for (k in CLOSE.length - 1 downTo 2) {
            if (t.endsWith(CLOSE.take(k))) { t = t.dropLast(k); break }
            if (t.endsWith(OPEN.take(k))) { t = t.dropLast(k); break }
        }
        return t
    }
}
