package de.foldki.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves all conversations as one JSON file in the app's private storage. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")

    fun load(): List<Conversation> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val msgs = c.getJSONArray("messages")
                Conversation(
                    id = c.getString("id"),
                    title = c.optString("title", "Chat"),
                    updatedAt = c.optLong("updatedAt"),
                    instruction = c.optString("instruction"),
                    messages = (0 until msgs.length()).map { j ->
                        val m = msgs.getJSONObject(j)
                        ChatMessage(
                            id = m.getString("id"),
                            role = Role.valueOf(m.getString("role")),
                            text = m.getString("text"),
                            engine = m.optString("engine").takeIf { it.isNotEmpty() }
                                ?.let { runCatching { Engine.valueOf(it) }.getOrNull() },
                            isError = m.optBoolean("isError"),
                            web = m.optBoolean("web"),
                            localOnly = m.optBoolean("localOnly"),
                            model = m.optString("model").ifEmpty { null },
                            thoughts = m.optString("thoughts"),
                            tokens = m.optInt("tokens"),
                            answerMs = m.optLong("answerMs"),
                            tokPerSec = m.optDouble("tokPerSec", 0.0).toFloat(),
                            backend = m.optString("backend"),
                            cpuPct = m.optInt("cpuPct", -1),
                            thinkMs = m.optLong("thinkMs"),
                            firstMs = m.optLong("firstMs"),
                            attachments = m.optJSONArray("attachments")?.let { a ->
                                (0 until a.length()).map { k ->
                                    val x = a.getJSONObject(k)
                                    Attachment(
                                        id = x.getString("id"), name = x.getString("name"),
                                        mime = x.getString("mime"), path = x.getString("path"),
                                        text = x.optString("text"),
                                        docDate = x.optString("docDate").ifEmpty { null },
                                        recipient = x.optString("recipient").ifEmpty { null },
                                        refNo = x.optString("refNo").ifEmpty { null },
                                        subject = x.optString("subject").ifEmpty { null },
                                        analysis = x.optString("analysis").ifEmpty { null },
                                    )
                                }
                            } ?: emptyList(),
                        )
                    },
                )
            }.sortedByDescending { it.updatedAt }
        }
    } catch (e: Exception) {
        // Unreadable file: keep a copy instead of overwriting it with an empty list on the next save.
        runCatching { file.copyTo(File(file.parentFile, "chats.corrupt-${System.currentTimeMillis()}.json"), overwrite = false) }
        android.util.Log.e("FoldKI", "chats.json konnte nicht gelesen werden, Sicherung angelegt", e)
        emptyList()
    }

    @Synchronized
    fun save(conversations: List<Conversation>) {
        val arr = JSONArray()
        conversations.filterNot { it.incognito }.forEach { c ->
            val msgs = JSONArray()
            c.messages.forEach { m ->
                msgs.put(JSONObject().apply {
                    put("id", m.id); put("role", m.role.name); put("text", m.text)
                    put("engine", m.engine?.name ?: ""); put("isError", m.isError); put("web", m.web); put("localOnly", m.localOnly)
                    m.model?.let { put("model", it) }
                    if (m.thoughts.isNotEmpty()) put("thoughts", m.thoughts)
                    if (m.tokens > 0) { put("tokens", m.tokens); put("answerMs", m.answerMs); put("tokPerSec", m.tokPerSec.toDouble()) }
                    if (m.backend.isNotEmpty()) put("backend", m.backend)
                    if (m.cpuPct >= 0) put("cpuPct", m.cpuPct)
                    if (m.thinkMs > 0) put("thinkMs", m.thinkMs)
                    if (m.firstMs > 0) put("firstMs", m.firstMs)
                    put("attachments", JSONArray().apply {
                        m.attachments.forEach { a ->
                            put(JSONObject().apply {
                                put("id", a.id); put("name", a.name); put("mime", a.mime)
                                put("path", a.path); put("text", a.text)
                                a.docDate?.let { put("docDate", it) }; a.recipient?.let { put("recipient", it) }
                                a.refNo?.let { put("refNo", it) }; a.subject?.let { put("subject", it) }
                                a.analysis?.let { put("analysis", it) }
                            })
                        }
                    })
                })
            }
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("updatedAt", c.updatedAt)
                if (c.instruction.isNotEmpty()) put("instruction", c.instruction)
                put("messages", msgs)
            })
        }
        val tmp = File(file.parentFile, "chats.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}
