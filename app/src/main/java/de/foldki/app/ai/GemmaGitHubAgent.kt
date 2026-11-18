package de.foldki.app.ai

import de.foldki.app.github.GitHubApi
import de.foldki.app.github.GitHubTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * GitHub with Gemma 4 E2B on the phone (no Claude, no other key): Gemma asks for a tool with one line
 * "WERKZEUG: {json}", the app runs it (changes only after the user's approval) and sends the result back.
 */
class GemmaGitHubAgent(private val gemma: LocalEngine, token: String) {
    private val tools = GitHubTools(GitHubApi(token))

    companion object {
        private const val MAX_STEPS = 25
        private const val RESULT_CHARS = 1500 // Gemma's context is small: keep each result short

        fun system(base: String): String = buildString {
            append(base.trim().take(600)).append("\n\n")
            append(
                """
Du hast Zugang zum GitHub-Konto des Nutzers über Werkzeuge. So benutzt du ein Werkzeug:
Schreibe eine eigene Zeile, die mit WERKZEUG: beginnt, danach ein JSON-Objekt mit "name" und den Angaben, z. B.
WERKZEUG: {"name":"list_my_repositories"}
WERKZEUG: {"name":"read_file","owner":"agmaz-dev","repo":"Test","path":"README.md"}
Danach hörst du auf zu schreiben. Du bekommst das ERGEBNIS und machst dann weiter. Immer nur ein Werkzeug auf einmal.
Wenn du fertig bist, antworte ohne WERKZEUG-Zeile mit einer kurzen Zusammenfassung auf Deutsch.
Änderungen (Anlegen, Schreiben, Mergen, Workflow starten) zeigt die App dem Nutzer vorher zur Freigabe.
Behaupte nie, etwas sei erledigt, ohne dass ein ERGEBNIS es bestätigt. Erfinde keine Dateiinhalte.
Werkzeuge:
""".trimIndent(),
            )
            GitHubTools.SPECS.forEach { s ->
                append("\n- ").append(s.name)
                if (s.params.isNotEmpty()) append(" (").append(s.params.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }).append(")")
                append(": ").append(s.description.take(110))
            }
            append("\nwrite_files: \"files\" ist eine Liste von {\"path\":…, \"content\":…}.")
        }

        /** The JSON after "WERKZEUG:" (first '{' to its matching '}'), or null. */
        fun parseCall(text: String): JSONObject? {
            val at = text.indexOf("WERKZEUG:").takeIf { it >= 0 } ?: return null
            val start = text.indexOf('{', at).takeIf { it >= 0 } ?: return null
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull() }
                }
            }
            return null
        }
    }

    suspend fun run(
        baseSystem: String,
        question: String,
        onText: suspend (String) -> Unit,
        approve: suspend (title: String, details: String) -> Boolean,
    ) {
        val steps = gemma.session(system(baseSystem), question, MAX_STEPS) { answer ->
            val call = parseCall(answer)
            val before = (if (call != null) answer.substringBefore("WERKZEUG:") else answer).trim()
            if (before.isNotBlank()) onText(before + "\n\n")
            if (call == null) return@session null
            val name = call.optString("name")
            val result = try {
                when {
                    name.isBlank() -> "Fehler: \"name\" fehlt."
                    name == "wait" -> {
                        val s = call.optInt("seconds", 30).coerceIn(10, 120)
                        onText(GitHubTools.progress("wait", call) + "\n"); delay(s * 1000L); "$s Sekunden gewartet."
                    }
                    name in GitHubTools.WRITE_NAMES -> {
                        val (title, details) = GitHubTools.describe(name, call)
                        if (approve(title, details)) {
                            onText("✅ Freigegeben: $title\n")
                            withContext(Dispatchers.IO) { tools.run(name, call) }
                        } else {
                            onText("⛔ Abgelehnt: $title\n")
                            "Der Nutzer hat das NICHT freigegeben. Nicht ausführen. Frag ihn kurz, was er anders möchte, und hör dann auf."
                        }
                    }
                    GitHubTools.SPECS.none { it.name == name } -> "Fehler: Werkzeug \"$name\" gibt es nicht."
                    else -> {
                        onText(GitHubTools.progress(name, call) + "\n")
                        withContext(Dispatchers.IO) { tools.run(name, call) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
            "ERGEBNIS:\n" + GitHubTools.cap(result, RESULT_CHARS)
        }
        if (steps >= MAX_STEPS) onText("\n(Nach $MAX_STEPS Schritten angehalten – sag „weiter“, dann mache ich weiter.)")
    }
}
