package de.foldki.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Gemini Nano on the device, via ML Kit GenAI Prompt API (Android AICore).
 * Works offline once the model is on the phone (e.g. Galaxy Z Fold7).
 */
class NanoEngine {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    enum class State { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    suspend fun state(): State = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> State.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> State.DOWNLOADING
        else -> State.UNAVAILABLE
    }

    /** Downloads the model; emits a human-readable progress line. */
    fun download(): Flow<String> = flow {
        model.download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadCompleted -> emit("Gemini Nano ist bereit.")
                is DownloadStatus.DownloadFailed -> throw IllegalStateException(
                    "Download fehlgeschlagen: ${s.e.message}"
                )
                is DownloadStatus.DownloadProgress ->
                    emit("Lade Gemini Nano… ${s.totalBytesDownloaded / 1_000_000} MB")
                else -> emit("Download gestartet…")
            }
        }
    }

    /**
     * The Prompt API is single-turn, so recent chat history is packed into one prompt.
     * Nano has a small context window, so only the last few turns are kept.
     */
    fun reply(system: String, history: List<ChatMessage>, webSearch: Boolean): Flow<String> = flow {
        var web = ""
        if (webSearch) {
            val question = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
            val terms = firstLineTerms(complete(searchTermsPrompt(question)))
            web = WebLookup.search(terms.ifBlank { question.take(100) })
        }
        val prompt = buildPrompt(system, history, web)
        model.generateContentStream(prompt).collect { chunk ->
            val text = chunk.candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotEmpty()) emit(text)
        }
    }

    /**
     * Daily briefing: own short instruction, no chat history, no web, facts at most 3500 characters.
     * BUSY is retried once after 2 s; other errors (e.g. BACKGROUND_USE_BLOCKED) go to the caller.
     */
    suspend fun brief(facts: String): String {
        val prompt = BRIEF_SYSTEM + "\n\n" + facts.take(3500) + "\n\nTagesstart:"
        return try {
            complete(prompt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (errorCode(e) != com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY) throw e
            kotlinx.coroutines.delay(2000)
            complete(prompt)
        }.trim()
    }

    /** One prompt, whole answer (internal tasks like search terms or reading a letter). */
    suspend fun ask(prompt: String): String = complete(prompt)

    private suspend fun complete(prompt: String): String {
        val sb = StringBuilder()
        model.generateContentStream(prompt).collect { sb.append(it.candidates.firstOrNull()?.text.orEmpty()) }
        return sb.toString()
    }

    companion object {
    const val BRIEF_SYSTEM = """Du schreibst einen kurzen Tagesstart auf Deutsch für den Nutzer (duzen).
Fasse nur die folgenden Fakten in 2 bis 4 kurzen Sätzen zusammen: was heute wichtig ist, welche Termine anstehen, wer geschrieben hat, was offen ist.
Erfinde nichts, keine Listen, keine Begrüßungsfloskeln, kein Markdown."""

    /** ML Kit GenAI error code somewhere in the cause chain, or null. */
    fun errorCode(e: Throwable?): Int? {
        var t = e
        while (t != null) {
            if (t is com.google.mlkit.genai.common.GenAiException) return t.errorCode
            t = t.cause.takeIf { it !== t }
        }
        return null
    }

    fun searchTermsPrompt(question: String) =
        "Gib nur 2 bis 5 Suchbegriffe (ohne Erklärung, eine Zeile) für eine Internetsuche zu dieser Frage aus:\n$question"

    fun firstLineTerms(answer: String): String =
        answer.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*')?.take(100).orEmpty()

    /** Packs system text, attachments, web results and the last turns into one prompt (also used by Gemma). */
    /** [large] = Gemma 4 E2B (32K context): whole documents and longer history instead of Nano's small limits. */
    fun buildPrompt(system: String, history: List<ChatMessage>, web: String = "", large: Boolean = false): String {
        val turns = history.filter { !it.isError }.takeLast(if (large) 12 else 6)
        val lines = turns.map { m ->
            val who = if (m.role == Role.USER) "Nutzer" else "Assistent"
            // Own earlier answers short and without markers, so the model does not just repeat them.
            val t = if (m.role == Role.USER) m.text.take(if (large) 6000 else 1500)
                else de.foldki.app.export.DocumentParser.display(m.text).take(if (large) 1500 else 400)
            "$who: $t"
        }.toMutableList()
        // Keep the prompt within the model's context: drop the oldest turns first.
        while (lines.size > 1 && lines.sumOf { it.length } > (if (large) 14000 else 3000)) lines.removeAt(0)

        // Text of attached documents/photos (latest ones first), limited to fit Nano.
        val docs = StringBuilder()
        // With web results in the prompt, documents get less room so Nano's small input limit is not exceeded.
        var budget = if (large) (if (web.isNotBlank()) 16000 else 24000) else if (web.isNotBlank()) 2500 else 4500
        // Only files from the last 3 questions: older attachments must not take over every answer.
        val latest = history.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }
        (listOfNotNull(latest) + history.filter { it.role == Role.USER }.takeLast(3).asReversed())
            .flatMap { it.attachments }.distinctBy { it.id }.forEach { a ->
            if (budget <= 0 || a.text.isBlank()) return@forEach
            val t = a.text.take(budget)
            budget -= t.length
            docs.append("--- Anhang: ${a.name} ---\n").append(t).append("\n\n")
        }
        val noText = history.flatMap { it.attachments }.filter { it.text.isBlank() }

        return buildString {
            if (system.isNotBlank()) append(system).append("\n\n")
            if (docs.isNotEmpty()) append("Inhalt der angehängten Dateien:\n").append(docs)
            if (web.isNotBlank()) append("Internetsuche:\n")
                .append(web.take(if (large) 6000 else 3000)).append("\n\n")
            if (noText.isNotEmpty()) append("(Aus ${noText.joinToString { it.name }} konnte kein Text gelesen werden.)\n\n")
            lines.forEach { append(it).append("\n") }
            append("Assistent:")
        }
    }
}
}
