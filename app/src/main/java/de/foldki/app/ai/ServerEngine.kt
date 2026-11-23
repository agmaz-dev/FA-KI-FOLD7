package de.foldki.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A model server on the user's PC (LM Studio, Ollama or any OpenAI-compatible server), usually in the home WLAN. */
data class PcServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mein PC",
    /** "lmstudio", "ollama" or "openai" */
    val type: String = "lmstudio",
    val host: String = "",
    val port: Int = 1234,
    /** Only for servers that need one (OpenAI-compatible); LM Studio and Ollama do not. Stays on the phone. */
    val apiKey: String = "",
    /** Chosen model on the server. */
    val model: String = "",
) {
    val base: String get() {
        val h = host.trim().removeSuffix("/")
        val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
        return if (Regex(":\\d+$").containsMatchIn(withScheme.substringAfter("://"))) withScheme else "$withScheme:$port"
    }
    val typeLabel: String get() = when (type) { "ollama" -> "Ollama"; "openai" -> "OpenAI-kompatibel"; else -> "LM Studio" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("host", host).put("port", port)
        .put("apiKey", apiKey).put("model", model)

    companion object {
        const val PREFIX = "server:"
        fun defaultPort(type: String) = when (type) { "ollama" -> 11434; "openai" -> 8080; else -> 1234 }
        fun listToJson(l: List<PcServer>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(raw: String?): List<PcServer> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PcServer(o.getString("id"), o.optString("name", "Mein PC"), o.optString("type", "lmstudio"), o.optString("host"),
                    o.optInt("port", 1234), o.optString("apiKey"), o.optString("model"))
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Talks to a [PcServer] over its OpenAI-compatible API (/v1/models, /v1/chat/completions with streaming). LM Studio and
 * Ollama both offer it. The model gets only what the user wrote (own instruction, chat, question) – nothing is added.
 */
object ServerEngine {
    private fun open(url: String, s: PcServer, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            if (s.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.apiKey.trim()}")
        }

    /** Model names on the server; throws with a German message if it cannot be reached. Returns also the round-trip ms. */
    suspend fun models(s: PcServer): Pair<List<String>, Long> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val c = try { open(s.base + "/v1/models", s).apply { readTimeout = 8000 } } catch (e: Exception) {
            throw IllegalStateException("Adresse ungültig: ${e.message}")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("Server antwortet mit Fehler $code")
            val body = c.inputStream.bufferedReader().readText()
            val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() } to (System.currentTimeMillis() - t0)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("PC nicht erreichbar (${s.base}). Läuft der Server und ist das Handy im gleichen WLAN?")
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("Keine Antwort von ${s.base} (Zeitüberschreitung).")
        } finally { c.disconnect() }
    }

    /** Best effort: "GGUF · Q4_K_M" per model name (LM Studio /api/v0/models, Ollama /api/tags); empty if unknown. */
    suspend fun modelInfo(s: PcServer): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (s.type == "ollama") s.base + "/api/tags" else s.base + "/api/v0/models"
            val c = open(url, s).apply { readTimeout = 6000 }
            try {
                if (c.responseCode !in 200..299) return@runCatching emptyMap()
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                val out = HashMap<String, String>()
                if (s.type == "ollama") {
                    val a = o.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val m = a.getJSONObject(i); val d = m.optJSONObject("details")
                        out[m.optString("name")] = listOfNotNull(d?.optString("format")?.uppercase()?.ifBlank { null }, d?.optString("quantization_level")?.ifBlank { null }).joinToString(" · ")
                    }
                } else {
                    val a = o.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val m = a.getJSONObject(i)
                        out[m.optString("id")] = listOfNotNull(m.optString("compatibility_type").uppercase().ifBlank { null }, m.optString("quantization").ifBlank { null }).joinToString(" · ")
                    }
                }
                out
            } finally { c.disconnect() }
        }.getOrDefault(emptyMap())
    }

    /** Multi-step conversation (tools: GitHub, MCP) with the PC server's model. */
    suspend fun session(s: PcServer, system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?): Int {
        val earlier = ArrayList<Pair<Boolean, String>>()
        var next: String? = first
        var step = 0
        while (next != null && step < maxSteps) {
            step++
            val sb = StringBuilder()
            // Ab 4.9: stop as soon as the tool call is complete; an invented result after it is never kept.
            chat(s, system, earlier.toList(), next, 0.3, 0).takeWhile { piece ->
                sb.append(piece)
                !(sb.contains('}') && GemmaGitHubAgent.callEnd(ThinkParser.answerOnly(sb.toString(), false)) > 0)
            }.collect { }
            val answer = GemmaGitHubAgent.cutAtCall(ThinkParser.answerOnly(sb.toString(), false))
            de.foldki.app.data.DebugLog.write("PC-Server Werkzeug-Schritt $step: " + answer.replace("\n", "⏎").take(800))
            earlier += true to next
            earlier += false to answer
            while (earlier.size > 2 && earlier.sumOf { it.second.length } > 40_000) { earlier.removeAt(0); earlier.removeAt(0) }
            next = onTurn(answer)
        }
        return step
    }

    /**
     * Streams the answer. Reasoning sent separately by the server ("reasoning_content") is wrapped in <think>…</think>,
     * so it shows up as "Gedanken" like with the models on the phone.
     */
    fun chat(s: PcServer, system: String, earlier: List<Pair<Boolean, String>>, question: String, temperature: Double?, maxTokens: Int): Flow<String> = flow {
        if (s.model.isBlank()) throw IllegalStateException("Auf dem PC-Server ist noch kein Modell gewählt (Menü → PC-Server).")
        val msgs = JSONArray()
        if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        earlier.forEach { (user, text) -> msgs.put(JSONObject().put("role", if (user) "user" else "assistant").put("content", text)) }
        msgs.put(JSONObject().put("role", "user").put("content", question))
        val req = JSONObject().put("model", s.model).put("messages", msgs).put("stream", true)
        temperature?.let { req.put("temperature", it) }
        if (maxTokens > 0) req.put("max_tokens", maxTokens)
        val c = open(s.base + "/v1/chat/completions", s, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            c.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IllegalStateException("PC-Server Fehler $code: ${err.take(300)}")
            }
            var inThink = false
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    val l = line.trim()
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching { JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta") }.getOrNull() ?: continue
                    val reason = delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") }
                    val content = if (delta.isNull("content")) "" else delta.optString("content")
                    if (reason.isNotEmpty()) { if (!inThink) { emit("<think>"); inThink = true }; emit(reason) }
                    if (content.isNotEmpty()) { if (inThink) { emit("</think>"); inThink = false }; emit(content) }
                }
            }
            if (inThink) emit("</think>")
        } finally { c.disconnect() }
    }.flowOn(Dispatchers.IO)
}
