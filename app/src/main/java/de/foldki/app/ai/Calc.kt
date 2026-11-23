package de.foldki.app.ai

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Rechner der App (ab 4.9): kleine Modelle verrechnen sich bei mehrstufigen Rechnungen. Die App rechnet selbst:
 * - [hint]: Rechenausdrücke in der Frage („17,5 × 3 + 4“) werden vorher exakt ausgerechnet und dem Modell mitgegeben.
 * - [fixEquations]: Gleichungen in der Antwort („3 × 12,50 € = 36,50 €“) werden nachgerechnet und falsche Ergebnisse ersetzt.
 * Deutsche Zahlen (1.234,56) und Punkt-Dezimalzahlen (12.5) werden verstanden.
 */
object Calc {
    private const val NUM = "(?:\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?)"
    private const val UNIT = "(?:\\s*(?:€|EUR|Euro|%))?"
    private const val OP = "\\s*[-+*×x·/:÷]\\s*"
    private const val TERM = "(?:\\(\\s*)*$NUM$UNIT(?:\\s*\\))*"
    private val EXPR = "$TERM(?:$OP$TERM)+"

    /** „Ausdruck = Ergebnis“ in einer Antwort (Ergebnis optional mit €). */
    private val EQUATION = Regex("(?<![\\d.,])($EXPR)\\s*=\\s*($NUM)(?![\\d])")
    /** Rechenausdruck in einer Frage. */
    private val EXPRESSION = Regex("(?<![\\d.,])($EXPR)(?![\\d.,])")

    /** Wert eines deutschen oder englischen Zahlworts, null wenn keine Zahl. */
    fun parseNumber(raw: String): BigDecimal? {
        val s = raw.trim()
        val t = when {
            s.contains(',') -> s.replace(".", "").replace(',', '.')
            Regex("\\d{1,3}(?:\\.\\d{3})+").matches(s) -> s.replace(".", "")
            else -> s
        }
        return t.toBigDecimalOrNull()
    }

    private sealed class Tok {
        data class N(val v: BigDecimal) : Tok()
        data class O(val c: Char) : Tok()
    }

    private fun tokenize(expr: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        val num = Regex(NUM)
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> {
                    val m = num.find(expr, i)?.takeIf { it.range.first == i } ?: return null
                    var v = parseNumber(m.value) ?: return null
                    i = m.range.last + 1
                    // Einheit überspringen; % = durch 100
                    val rest = expr.substring(i)
                    val unit = Regex("^\\s*(€|EUR|Euro|%)").find(rest)
                    if (unit != null) {
                        if (unit.groupValues[1] == "%") v = v.divide(BigDecimal(100))
                        i += unit.value.length
                    }
                    out += Tok.N(v)
                }
                c == '(' || c == ')' -> { out += Tok.O(c); i++ }
                c in "+-" -> { out += Tok.O(c); i++ }
                c in "*×x·" -> { out += Tok.O('*'); i++ }
                c in "/:÷" -> { out += Tok.O('/'); i++ }
                else -> return null
            }
        }
        return out
    }

    /** Rechnet einen Ausdruck aus (Punkt vor Strich, Klammern). null = kein gültiger Ausdruck. */
    fun eval(expr: String): BigDecimal? {
        val toks = tokenize(expr) ?: return null
        if (toks.count { it is Tok.N } < 2) return null
        var p = 0
        fun peek(): Tok? = toks.getOrNull(p)
        lateinit var sum: () -> BigDecimal?
        fun atom(): BigDecimal? {
            val t = peek() ?: return null
            return when {
                t is Tok.N -> { p++; t.v }
                t is Tok.O && t.c == '(' -> { p++; val v = sum() ?: return null; if ((peek() as? Tok.O)?.c != ')') return null; p++; v }
                t is Tok.O && t.c == '-' -> { p++; atom()?.negate() }
                else -> null
            }
        }
        fun product(): BigDecimal? {
            var v = atom() ?: return null
            while (true) {
                val o = (peek() as? Tok.O)?.c ?: break
                if (o != '*' && o != '/') break
                p++
                val r = atom() ?: return null
                v = if (o == '*') v.multiply(r) else { if (r.signum() == 0) return null; v.divide(r, 10, RoundingMode.HALF_UP) }
            }
            return v
        }
        sum = {
            var v = product()
            while (v != null) {
                val o = (peek() as? Tok.O)?.c ?: break
                if (o != '+' && o != '-') break
                p++
                val r = product()
                v = if (r == null) null else if (o == '+') v.add(r) else v.subtract(r)
            }
            v
        }
        val v = sum() ?: return null
        return if (p == toks.size) v else null
    }

    /** Deutsche Schreibweise: ganze Zahl ohne Nachkomma, sonst 2 Nachkommastellen („37,50“). */
    fun format(v: BigDecimal, decimals: Int = -1): String {
        val r = v.setScale(2, RoundingMode.HALF_UP)
        val whole = r.stripTrailingZeros().scale() <= 0
        val d = if (decimals >= 0) decimals else if (whole) 0 else 2
        return String.format(Locale.GERMANY, "%,.${d}f", r.setScale(d, RoundingMode.HALF_UP))
    }

    /** Ausdrücke mit Datum, Uhrzeit oder Telefonnummer sind keine Rechnungen. */
    private fun looksLikeMath(expr: String): Boolean {
        val ops = Regex(OP).findAll(expr).count()
        if (ops == 0) return false
        if (Regex("\\d+:\\d{2}(?!\\d)").containsMatchIn(expr) && !expr.contains(Regex("[*×·+]"))) return false // 12:30
        if (Regex("^\\s*\\d{2,5}\\s*-\\s*\\d{3,}\\s*$").matches(expr)) return false // 030-123456, 2020-2024
        return true
    }

    /**
     * Rechnet die Gleichungen einer Antwort nach und ersetzt falsche Ergebnisse durch die richtigen.
     * Gleichungen mit Prozent und Plus/Minus („100 + 19 %“) bleiben, weil sie mehrdeutig sind.
     */
    fun fixEquations(text: String): String {
        if (!text.contains('=')) return text
        return EQUATION.replace(text) { m ->
            val expr = m.groupValues[1]
            val stated = parseNumber(m.groupValues[2])
            if (!looksLikeMath(expr) || stated == null) return@replace m.value
            if (expr.contains('%') && Regex("[-+]").containsMatchIn(expr.replace(Regex("\\d+[.,]\\d+"), "1"))) return@replace m.value
            val v = eval(expr) ?: return@replace m.value
            val tol = BigDecimal("0.01").max(v.abs().multiply(BigDecimal("0.00001")))
            if (v.subtract(stated).abs() <= tol) return@replace m.value
            val raw = m.groupValues[2]
            val dec = if (raw.contains(',') || (raw.contains('.') && !Regex("\\d{1,3}(?:\\.\\d{3})+").matches(raw))) 2 else -1
            m.value.substring(0, m.groups[2]!!.range.first - m.range.first) + format(v, dec)
        }
    }

    /** Ausgerechnete Ausdrücke aus der Frage als Hinweis fürs Modell, "" wenn keine Rechnung darin ist. */
    fun hint(question: String): String {
        // Only real calculations: an operator like * × ÷ + or words like "rechne", "Summe", "insgesamt".
        if (!Regex("[*×÷+=]|\\b(rechne|berechne|summe|insgesamt|zusammen|gesamt|prozent|mal|plus|minus|geteilt|ergebnis|wie viel)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)) return ""
        val found = EXPRESSION.findAll(question).map { it.groupValues[1].trim() }
            .filter { looksLikeMath(it) }
            .mapNotNull { e -> eval(e)?.let { e to it } }
            .distinctBy { it.first }.take(6).toList()
        if (found.isEmpty()) return ""
        return "Von der App exakt ausgerechnet (diese Ergebnisse verwenden, nicht selbst neu rechnen):\n" +
            found.joinToString("\n") { (e, v) -> "$e = ${format(v)}" }
    }
}
