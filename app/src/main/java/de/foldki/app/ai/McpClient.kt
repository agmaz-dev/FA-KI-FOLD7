package de.foldki.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal MCP client for one own tool server ("MCP-Werkzeuge", Streamable HTTP transport): initialize, tools/list,
 * tools/call. Answers may come as plain JSON or as a short event stream. Blocking calls – run them on Dispatchers.IO.
 * The token (optional) stays on the phone and is only sent to this server.
 */
class McpClient(private val url: String, private val token: String = "") {
    data class Tool(val name: String, val description: String, val params: List<String>, val required: Set<String>, val readOnly: Boolean)

    private var sessionId: String? = null
    private var protocol: String? = null
    private var nextId = 1
    private var ready = false

    private fun post(body: JSONObject, expectAnswer: Boolean): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            protocol?.let { setRequestProperty("MCP-Protocol-Version", it) }
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IllegalStateException("MCP-Server Fehler $code: ${err.take(300)}")
            }
            if (!expectAnswer) return null
            val type = c.contentType.orEmpty().lowercase()
            val wanted = body.opt("id")
            if (type.contains("text/event-stream")) {
                // Event stream: the answer is the "data:" message with our id (other messages are notifications).
                c.inputStream.bufferedReader().useLines { lines ->
                    val data = StringBuilder()
                    for (line in lines) {
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.isBlank() && data.isNotEmpty() -> {
                                val o = runCatching { JSONObject(data.toString()) }.getOrNull()
                                data.setLength(0)
                                if (o != null && o.opt("id")?.toString() == wanted?.toString()) return o
                            }
                        }
                    }
                    if (data.isNotEmpty()) runCatching { JSONObject(data.toString()) }.getOrNull()?.let { return it }
                }
                throw IllegalStateException("MCP-Server hat nicht geantwortet.")
            }
            val text = c.inputStream.bufferedReader().readText().trim()
            if (text.startsWith("[")) {
                val a = JSONArray(text)
                for (k in 0 until a.length()) { val o = a.optJSONObject(k); if (o?.opt("id")?.toString() == wanted?.toString()) return o }
                throw IllegalStateException("MCP-Server: Antwort ohne passende Nummer.")
            }
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun rpc(method: String, params: JSONObject?): JSONObject {
        val id = nextId++
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) body.put("params", params)
        val ans = post(body, expectAnswer = true) ?: throw IllegalStateException("MCP-Server hat nicht geantwortet.")
        ans.optJSONObject("error")?.let { throw IllegalStateException("MCP: ${it.optString("message").ifBlank { it.toString() }}") }
        return ans.optJSONObject("result") ?: JSONObject()
    }

    /** Opens the session once (initialize + notifications/initialized). */
    fun connect() {
        if (ready) return
        val res = rpc(
            "initialize",
            JSONObject().put("protocolVersion", PROTOCOL)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Fold KI").put("version", "4.8")),
        )
        protocol = res.optString("protocolVersion").ifBlank { PROTOCOL }
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectAnswer = false)
        ready = true
    }

    /** All tools of the server (follows "nextCursor"). */
    fun listTools(): List<Tool> {
        connect()
        val out = ArrayList<Tool>()
        var cursor: String? = null
        var pages = 0
        do {
            val res = rpc("tools/list", cursor?.let { JSONObject().put("cursor", it) })
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val t = arr.optJSONObject(k) ?: continue
                val schema = t.optJSONObject("inputSchema")
                val props = schema?.optJSONObject("properties")
                val names = props?.keys()?.asSequence()?.toList().orEmpty()
                val req = schema?.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.optString(it) }.toSet() }.orEmpty()
                val ro = t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false
                out += Tool(t.optString("name"), t.optString("description"), names, req, ro)
            }
            cursor = res.optString("nextCursor").ifBlank { null }
            pages++
        } while (cursor != null && pages < 10)
        return out.filter { it.name.isNotBlank() }
    }

    /** Runs a tool; returns its text content (errors marked with "Fehler:"). */
    fun call(name: String, args: JSONObject): String {
        connect()
        val res = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        val sb = StringBuilder()
        val content = res.optJSONArray("content") ?: JSONArray()
        for (k in 0 until content.length()) {
            val c = content.optJSONObject(k) ?: continue
            when (c.optString("type")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "resource" -> sb.append(c.optJSONObject("resource")?.optString("text").orEmpty()).append('\n')
                else -> sb.append("(").append(c.optString("type")).append(")\n")
            }
        }
        if (sb.isBlank()) res.optJSONObject("structuredContent")?.let { sb.append(it.toString()) }
        val text = sb.toString().trim()
        return if (res.optBoolean("isError", false)) "Fehler: $text" else text.ifBlank { "(kein Ergebnis)" }
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        /** Prefix of MCP tools in the model's tool list (keeps them apart from the GitHub tools). */
        const val PREFIX = "mcp_"
    }
}
