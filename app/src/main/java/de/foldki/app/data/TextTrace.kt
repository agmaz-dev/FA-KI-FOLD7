package de.foldki.app.data

import de.foldki.app.ai.TextIntegrity
import org.json.JSONObject
import java.security.MessageDigest

/** Explicit synthetic probe only. Never captures an incognito chat or ordinary prompts.
 * Text is escaped for exact comparison. Existing local error report is the only destination.
 */
object TextTrace {
    @Volatile private var id: String? = null
    @Volatile private var answer: String? = null
    private const val LIMIT = 16000
    @Synchronized fun begin(messageId: String, prompt: String, incognito: Boolean) {
        id = if (!incognito && TextIntegrity.isProbe(prompt)) messageId else null
        answer = null
        if (id != null) stage(messageId, "01 INPUT_RAW", prompt)
    }
    fun active(messageId: String): Boolean = id == messageId
    @Synchronized fun stage(messageId: String, name: String, text: String) {
        if (!active(messageId)) return
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        DebugLog.write("TEXT_DIAG " + messageId + " " + name + " len=" + text.length + " sha256=" + digest +
            " stars=" + text.count { it == '*' } + " underscores=" + text.count { it == '_' } +
            " ticks=" + text.count { it == '\u0060' } + " " +
            JSONObject.quote(text.take(LIMIT)) + if (text.length > LIMIT) " [truncated]" else "")
    }
    @Synchronized fun stored(messageId: String, text: String) {
        if (!active(messageId)) return
        answer = text
        stage(messageId, "07 STORED", text)
    }
    @Synchronized fun clipboard(input: String, output: String?) {
        val key = id ?: return
        if (input != answer) return
        stage(key, "09 COPY_INPUT", input)
        if (output != null) stage(key, "10 CLIPBOARD_READBACK", output)
        else DebugLog.write("TEXT_DIAG clipboard readback unavailable")
    }
    @Synchronized fun speech(input: String, output: String) {
        val key = id ?: return
        val a = answer ?: return
        if (input.isBlank() || !a.contains(input)) return
        stage(key, "11 TTS_INPUT", input)
        stage(key, "12 TTS_OUTPUT_ONLY", output)
    }
}
