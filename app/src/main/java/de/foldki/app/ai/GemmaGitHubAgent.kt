package de.foldki.app.ai

import de.foldki.app.github.GitHubApi
import de.foldki.app.github.GitHubTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One multi-step conversation of a model: the model answers, onTurn gets the answer and returns the next message or null. */
typealias SessionFn = suspend (system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?) -> Int

/**
 * Tools for every model on the phone and on the PC server (GitHub and the own MCP server; not Gemini Nano): the model asks
 * for a tool with one line "WERKZEUG: {json}", the app runs it (changes only after the user's approval) and sends the
 * result back. [session] comes from the engine that answers (LiteRT, GGUF/llama.cpp or PC server).
 */
class GemmaGitHubAgent(
    private val session: SessionFn,
    githubToken: String,
    private val mcp: McpClient? = null,
    private val mcpTools: List<McpClient.Tool> = emptyList(),
    private val mcpName: String = "",
) {
    private val tools: GitHubTools? = githubToken.takeIf { it.isNotBlank() }?.let { GitHubTools(GitHubApi(it)) }

    companion object {
        private const val MAX_STEPS = 25
        private const val RESULT_CHARS = 1500 // small models: keep each result short

        /** Instruction with the tool list; [github] = GitHub tools, [mcpTools] = tools of the own server. */
        fun system(base: String, github: Boolean = true, mcpTools: List<McpClient.Tool> = emptyList(), mcpName: String = ""): String = buildString {
            if (base.isNotBlank()) append(base.trim().take(2400)).append("\n\n")
            append(
                """
    Du hast Werkzeuge. So benutzt du ein Werkzeug:
    Schreibe eine eigene Zeile, die mit WERKZEUG: beginnt, danach ein JSON-Objekt mit "name" und den Angaben, z. B.
    WERKZEUG: {"name":"list_my_repositories"}
    Danach hörst du auf zu schreiben. Du bekommst das ERGEBNIS und machst dann weiter. Immer nur ein Werkzeug auf einmal.
    Wenn du fertig bist, antworte ohne WERKZEUG-Zeile mit einer kurzen Zusammenfassung auf Deutsch.
    Änderungen zeigt die App dem Nutzer vorher zur Freigabe.
    Behaupte nie, etwas sei erledigt, ohne dass ein ERGEBNIS es bestätigt. Erfinde keine Inhalte.
    """.trimIndent(),
            )
            if (github) {
                append("\n\nGitHub-Werkzeuge (Konto des Nutzers):")
                GitHubTools.SPECS.forEach { s ->
                    append("\n- ").append(s.name)
                    if (s.params.isNotEmpty()) append(" (").append(s.params.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }).append(")")
                    append(": ").append(s.description.take(110))
                }
                append("\nwrite_files: \"files\" ist eine Liste von {\"path\":…, \"content\":…}.")
            }
            if (mcpTools.isNotEmpty()) {
                append("\n\nWerkzeuge vom eigenen Server").append(if (mcpName.isBlank()) "" else " „$mcpName“").append(" (Angaben direkt neben \"name\"):")
                mcpTools.take(40).forEach { t ->
                    append("\n- ").append(McpClient.PREFIX).append(t.name)
                    if (t.params.isNotEmpty()) append(" (").append(t.params.joinToString(", ") { p -> p + if (p in t.required) "" else "?" }).append(")")
                    append(": ").append(t.description.replace("\n", " ").take(110))
                }
            }
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
        val steps = session(system(baseSystem, tools != null, mcpTools, mcpName), question, MAX_STEPS) { answer ->
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
                    name.startsWith(McpClient.PREFIX) -> runMcp(name.removePrefix(McpClient.PREFIX), call, onText, approve)
                    tools == null -> "Fehler: GitHub ist nicht verbunden (Einstellungen → GitHub)."
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

    /** A tool of the own MCP server: read-only tools run at once, all others only after "Freigeben". */
    private suspend fun runMcp(
        tool: String, call: JSONObject, onText: suspend (String) -> Unit,
        approve: suspend (title: String, details: String) -> Boolean,
    ): String {
        val client = mcp ?: return "Fehler: Kein MCP-Server verbunden."
        val spec = mcpTools.firstOrNull { it.name == tool } ?: return "Fehler: Werkzeug \"$tool\" gibt es auf dem Server nicht."
        val args = JSONObject()
        call.keys().forEach { k -> if (k != "name") args.put(k, call.get(k)) }
        if (!spec.readOnly) {
            val title = "Werkzeug „$tool“" + if (mcpName.isBlank()) "" else " ($mcpName)"
            if (!approve(title, GitHubTools.cap(args.toString(2), 4000))) {
                onText("⛔ Abgelehnt: $title\n")
                return "Der Nutzer hat das NICHT freigegeben. Nicht ausführen. Frag ihn kurz, was er anders möchte, und hör dann auf."
            }
            onText("✅ Freigegeben: $title\n")
        } else onText("🧰 $tool\n")
        return withContext(Dispatchers.IO) { client.call(tool, args) }
    }
}
