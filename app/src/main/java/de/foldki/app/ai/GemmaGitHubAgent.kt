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
        fun system(base: String, github: Boolean = true, mcpTools: List<McpClient.Tool> = emptyList(), mcpName: String = "", question: String = ""): String = buildString {
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
                val names = relevantTools(question)
                GitHubTools.SPECS.filter { it.name in names }.forEach { s ->
                    append("\n- ").append(s.name)
                    if (s.params.isNotEmpty()) append(" (").append(s.params.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }).append(")")
                    append(": ").append(s.description.take(110))
                }
                if ("write_files" in names) append("\nwrite_files: \"files\" ist eine Liste von {\"path\":…, \"content\":…}.")
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
            val end = callEnd(text).takeIf { it > 0 } ?: return null
            val start = text.indexOf('{', text.indexOf("WERKZEUG:"))
            return runCatching { JSONObject(text.substring(start, end)) }.getOrNull()
        }

        /** Index right after the closing '}' of the first complete WERKZEUG call, or -1 (no complete call yet). */
        fun callEnd(text: String): Int {
            val at = text.indexOf("WERKZEUG:").takeIf { it >= 0 } ?: return -1
            val start = text.indexOf('{', at).takeIf { it >= 0 } ?: return -1
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return i + 1 }
                }
            }
            return -1
        }

        /**
         * The model's turn as it is kept in the conversation: everything after the tool call is cut off. Small models
         * often go on and write an invented "ERGEBNIS" themselves – that text must never stay in the chat history.
         */
        fun cutAtCall(answer: String): String { val e = callEnd(answer); return if (e > 0) answer.substring(0, e) else answer }

        private val CORE = setOf("whoami", "list_my_repositories", "get_repository", "list_directory", "read_file", "search_repositories", "list_branches", "list_commits")
        private val BUILD = setOf("list_workflows", "list_workflow_runs", "get_run_jobs", "get_job_log", "list_releases", "wait", "run_workflow", "rerun_workflow")
        private val ISSUES = setOf("search_issues", "list_issues", "list_pull_requests", "get_pull_request", "create_pull_request", "merge_pull_request", "create_issue", "comment")
        private val WRITE = setOf("create_repository", "fork_repository", "create_branch", "write_files")

        /**
         * Only the tools the question needs (ab 4.9): a long tool list makes small models slow and confused.
         * Short follow-ups ("ja, mach") get all tools.
         */
        fun relevantTools(question: String): Set<String> {
            val q = question.lowercase()
            val out = HashSet(CORE)
            var hit = false
            if (Regex("build|apk|workflow|action|release|fehler|log|läuft|lauf|bau|kompil|status").containsMatchIn(q)) { out += BUILD; hit = true }
            if (Regex("issue|pull|\\bpr\\b|merge|kommentar|zusammenführ").containsMatchIn(q)) { out += ISSUES; hit = true }
            if (Regex("such|find|code|funktion|klasse").containsMatchIn(q)) { out += "search_code"; hit = true }
            if (Regex("änder|aender|schreib|commit|anleg|erstell|neu|fork|branch|datei|lösch|füg|ersetz|korrigier|repar|fix|update").containsMatchIn(q)) { out += WRITE; hit = true }
            if (!hit && q.length < 40) return GitHubTools.SPECS.map { it.name }.toSet()
            return out
        }

        /** Placeholders a model writes instead of real data ("[Repo-Name]", "[Ihr Name]"). */
        private val PLACEHOLDER = Regex("\\[[^\\]\\n]{2,40}\\]")
        /** Questions that need real data from a tool. */
        private val NEEDS_DATA = Regex("zeig|liste|welche|was ist|wie viele|status|build|lies|öffne|repo|datei|commit|branch|release|issue|pull|letzte|neueste|such|find|prüf|check|wer bin|konto", RegexOption.IGNORE_CASE)
    }

    suspend fun run(
        baseSystem: String,
        question: String,
        onText: suspend (String) -> Unit,
        approve: suspend (title: String, details: String) -> Boolean,
    ) {
        var used = 0
        var forced = false
        val seen = HashMap<String, Int>()
        var stop = false
        val steps = session(system(baseSystem, tools != null, mcpTools, mcpName, question), question, MAX_STEPS) { answer ->
            if (stop) return@session null
            val call = parseCall(answer)
            val before = (if (call != null) answer.substringBefore("WERKZEUG:") else answer).trim()
            // Force a tool once: the model answered without one although the question needs real data (or wrote placeholders).
            if (call == null && used == 0 && !forced && (PLACEHOLDER.containsMatchIn(answer) || NEEDS_DATA.containsMatchIn(question))) {
                forced = true
                de.foldki.app.data.DebugLog.write("Werkzeug erzwungen: Modell antwortete ohne Werkzeug")
                return@session "Du hast kein Werkzeug benutzt und darfst keine Daten erfinden. Benutze jetzt das passende Werkzeug: " +
                    "schreibe nur eine Zeile WERKZEUG: {\"name\":…} und hör dann auf."
            }
            if (before.isNotBlank()) onText(before + "\n\n")
            if (call == null) return@session null
            val name = call.optString("name")
            // Loop guard: the same call again and again.
            val key = call.toString()
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            if (n >= 3) {
                de.foldki.app.data.DebugLog.write("Werkzeug-Schleife angehalten: $name")
                onText("(Angehalten: dasselbe Werkzeug wurde dreimal gleich aufgerufen.)\n")
                stop = true
                return@session null
            }
            if (n == 2) return@session "ERGEBNIS:\nDiesen Aufruf hast du schon gemacht – das Ergebnis steht weiter oben. Antworte jetzt mit diesem Ergebnis, ohne Werkzeug."
            used++
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
            // The real result goes into the error report, so it is visible what the model got.
            de.foldki.app.data.DebugLog.write("Werkzeug $name → " + result.replace("\n", "⏎").take(700))
            "ERGEBNIS (echte Daten von der App):\n" + GitHubTools.cap(result, RESULT_CHARS) +
                "\n\nNutze nur diese Daten. Nächstes Werkzeug oder fertige Antwort auf Deutsch."
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
