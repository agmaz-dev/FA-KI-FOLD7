package de.foldki.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One instruction written by the user ("Meine Anweisungen"). Nothing is built in. */
data class Instruction(val id: String = UUID.randomUUID().toString(), val name: String, val emoji: String = "💬", val text: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("emoji", emoji).put("text", text)

    companion object {
        fun listToJson(list: List<Instruction>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(raw: String?): List<Instruction> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Instruction(o.optString("id").ifEmpty { UUID.randomUUID().toString() }, o.optString("name"), o.optString("emoji", "💬"), o.optString("text"))
            }
        }.getOrDefault(emptyList())
    }
}

/** An answer the user tapped "Merken" on. */
data class SavedAnswer(val id: String = UUID.randomUUID().toString(), val text: String, val model: String, val chat: String, val time: Long = System.currentTimeMillis())

/** "Gemerkte Antworten" as one JSON file in the app's private storage. */
class SavedStore(context: Context) {
    private val file = File(context.filesDir, "saved.json")

    fun load(): List<SavedAnswer> = runCatching {
        val a = JSONArray(if (file.exists()) file.readText() else "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SavedAnswer(o.getString("id"), o.getString("text"), o.optString("model"), o.optString("chat"), o.optLong("time"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<SavedAnswer>) {
        val a = JSONArray()
        list.forEach { s -> a.put(JSONObject().put("id", s.id).put("text", s.text).put("model", s.model).put("chat", s.chat).put("time", s.time)) }
        file.writeText(a.toString())
    }
}

/** Answer style for the chat ("Kreativität"): null sampler = the model's own default. */
enum class Creativity(val label: String, val temperature: Double?) {
    STANDARD("Standard", null), PRECISE("Genau", 0.2), BALANCED("Ausgewogen", 0.7), CREATIVE("Kreativ", 1.0)
}

/** Maximum answer length ("Länge") in generated pieces (≈ tokens); 0 = no limit. */
enum class AnswerLength(val label: String, val tokens: Int) { SHORT("Kurz", 400), NORMAL("Normal", 1500), LONG("Lang", 4000), FREE("Frei", 0) }
