package de.foldki.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

/** [GEMMA] = any downloaded LiteRT-LM model (name in [ChatMessage.model]); the name stays for stored chats. */
enum class Engine(val label: String) { NANO("Gemini Nano (offline)"), CLAUDE("Claude (online)"), GEMMA("Lokales Modell (offline)") }

/** A photo or PDF copied into the app's private storage. [text] is extracted text (PDF text / OCR). */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val path: String,
    val text: String = "",
    /** Read from the first page of an attached letter (never from PDF metadata or the file name). */
    val docDate: String? = null,
    /** Sender of the original letter = recipient of a reply (multi-line). */
    val recipient: String? = null,
    val refNo: String? = null,
    val subject: String? = null,
    /** FA-PDF style reading of the letter by the AI (KURZ:/BRIEFDATUM:/ABSENDER:/VORSCHLAG: … lines), once per file. */
    val analysis: String? = null,
) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf"
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val engine: Engine? = null,
    val isError: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val web: Boolean = false,
    /** Data that must stay on the device (daily briefing): never sent to Claude. */
    val localOnly: Boolean = false,
    /** Name of the model that wrote this answer. */
    val model: String? = null,
    /** Reasoning of a thinking model (text before </think>): shown collapsed as "Gedanken", never exported or spoken. */
    val thoughts: String = "",
    /** Generated pieces (≈ tokens, thinking included); 0 = unknown. Only shown, never exported. */
    val tokens: Int = 0,
    /** From sending the question until the answer was complete (ms). */
    val answerMs: Long = 0,
    /** Writing speed after the first piece (tokens per second). */
    val tokPerSec: Float = 0f,
    /** "GPU", "CPU" or "AICore" (Nano). */
    val backend: String = "",
    /** Average CPU load of the app while answering (%), -1 = not measured. */
    val cpuPct: Int = -1,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)
