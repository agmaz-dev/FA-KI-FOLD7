package de.foldki.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Any downloaded LiteRT-LM model (.litertlm: Gemma, Qwen, DeepSeek …) on the phone. GPU first, CPU only if the
 * GPU cannot start this model. Every failure is thrown to the caller – nothing answers in its place.
 *
 * Chat: the model gets exactly what it is given (no sampler values, no extra text). The conversation stays open
 * between questions of the same chat, so a follow-up only processes the new question instead of re-reading
 * everything (this is what made answers slow before).
 */
class LocalEngine(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: Engine? = null
    @Volatile var loadedPath: String? = null
        private set
    @Volatile var backendUsed: String? = null
        private set
    private var loading: Deferred<String>? = null

    /** One turn of the chat history (id = ChatMessage.id, used to recognise the open conversation). */
    data class Turn(val id: String, val user: Boolean, val text: String)

    // The open chat conversation (only one conversation exists at a time).
    private var kept: Conversation? = null
    private var keptKey = ""
    private var keptSystem = ""
    private var keptIds: List<String> = emptyList()
    private var keptTurns = 0
    private var keptChars = 0

    /** Context (tokens) of the loaded model: from "ekv1280"/"ekv4096" in the file name, at most [MAX_TOKENS]. */
    @Volatile var contextTokens: Int = MAX_TOKENS
        private set

    /**
     * Characters of input (instruction + chat + question) that safely fit next to an answer. Too much input
     * makes LiteRT-LM abort natively (the whole app closes), so every input is cut to this size first.
     */
    val inputBudgetChars: Int get() = ((contextTokens - maxOf(512, contextTokens * 3 / 10)) * CHARS_PER_TOKEN).toInt().coerceAtLeast(800)

    val isLoaded: Boolean get() = engine != null

    /** Loads [modelPath] (another loaded model is closed first). Returns "GPU" or "CPU". */
    fun loadAsync(modelPath: String, preferGpu: Boolean, onAttempt: (String) -> Unit = {}): Deferred<String> = synchronized(this) {
        loading?.takeIf { it.isActive }?.let { return it }
        loadScope.async {
            mutex.withLock {
                if (engine != null && loadedPath == modelPath) return@withLock backendUsed ?: "CPU"
                closeNow()
                val ctx = contextFor(modelPath)
                var lastError: Throwable? = null
                val order = if (preferGpu) listOf("GPU", "CPU") else listOf("CPU")
                for (b in order) {
                    onAttempt(b)
                    val e = try {
                        Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = if (b == "GPU") Backend.GPU() else Backend.CPU(),
                                maxNumTokens = ctx,
                                cacheDir = app.cacheDir.path,
                            )
                        )
                    } catch (t: Throwable) { lastError = t; continue }
                    try {
                        e.initialize()
                        engine = e; backendUsed = b; loadedPath = modelPath; contextTokens = ctx
                        return@withLock b
                    } catch (t: Throwable) { // incl. UnsatisfiedLinkError / missing OpenCL
                        lastError = t
                        runCatching { e.close() }
                    }
                }
                throw IllegalStateException("Modell konnte nicht geladen werden: ${lastError?.message ?: lastError?.javaClass?.simpleName}", lastError)
            }
        }.also { loading = it }
    }

    /**
     * Chat answer, raw text (thinking included). [system] may be empty (then no system instruction at all).
     * [earlier] = all earlier turns of this chat; [key] = chat id. Emits text pieces (≈ one per token).
     */
    fun chat(key: String, system: String, earlier: List<Turn>, question: String, questionId: String, answerId: String): Flow<String> = flow {
        val e = engine ?: throw IllegalStateException("Modell ist nicht geladen.")
        val ids = earlier.map { it.id }
        val budget = inputBudgetChars
        // Reuse the open conversation only while everything still fits into the model's context.
        val reuse = kept != null && keptKey == key && keptSystem == system && keptIds == ids && keptTurns < MAX_KEPT_TURNS &&
            system.length + keptChars + question.length <= budget
        var conv = if (reuse) kept!! else open(e, system, earlier, budget - system.length - question.length)
            .also { kept = it; keptKey = key; keptSystem = system; keptTurns = 0 }
        keptIds = emptyList() // only valid again after a complete answer
        val sb = StringBuilder()
        var done = false
        try {
            try {
                conv.sendMessageAsync(question).collect { m -> val t = text(m); if (t.isNotEmpty()) { sb.append(t); emit(t) } }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || !reuse || sb.isNotEmpty()) throw t
                // The open conversation is full or broken: start it fresh once.
                dropKept()
                conv = open(e, system, earlier, budget - system.length - question.length).also { kept = it; keptKey = key; keptSystem = system; keptTurns = 0 }
                conv.sendMessageAsync(question).collect { m -> val x = text(m); if (x.isNotEmpty()) { sb.append(x); emit(x) } }
            }
            done = true
            keptIds = ids + questionId + answerId
            keptTurns += 2
            keptChars += question.length + sb.length
        } finally {
            if (!done) { runCatching { conv.cancelProcess() }; dropKept() }
        }
    }.flowOn(Dispatchers.Default)

    /** New conversation with the newest earlier turns that fit into [room] characters. */
    private fun open(e: Engine, system: String, earlier: List<Turn>, room: Int): Conversation {
        dropKept()
        val picked = ArrayList<Turn>()
        var used = 0
        for (t in earlier.takeLast(10).asReversed()) {
            if (used + t.text.length > room) break
            used += t.text.length; picked.add(0, t)
        }
        val fit = picked.dropWhile { !it.user }
        keptChars = fit.sumOf { it.text.length }
        val initial = fit.map { if (it.user) Message.user(it.text) else Message.model(it.text) }
        return if (system.isBlank()) e.createConversation(ConversationConfig(initialMessages = initial))
        else e.createConversation(ConversationConfig(systemInstruction = Contents.of(system), initialMessages = initial))
    }

    /** Short internal task (search terms, reading a letter, daily briefing). Returns the raw text. */
    suspend fun complete(system: String, user: String, precise: Boolean = true): String = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val e = engine ?: throw IllegalStateException("Modell ist nicht geladen.")
        dropKept()
        val sampler = if (precise) SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2) else SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        val conv = if (system.isBlank()) e.createConversation(ConversationConfig(samplerConfig = sampler))
            else e.createConversation(ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = sampler))
        val sb = StringBuilder()
        var done = false
        try {
            conv.sendMessageAsync(user).collect { sb.append(text(it)) }
            done = true
        } finally {
            if (!done) runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
        }
        sb.toString()
    }

    /**
     * Multi-step conversation (GitHub tasks): the model answers, [onTurn] gets the answer and returns the next
     * message (e.g. a tool result) or null to stop. One conversation, so the model keeps the earlier steps.
     */
    suspend fun session(system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?) =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val e = engine ?: throw IllegalStateException("Modell ist nicht geladen.")
            dropKept()
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                ),
            )
            try {
                var msg: String? = first
                var step = 0
                while (msg != null && step < maxSteps) {
                    step++
                    val sb = StringBuilder()
                    conv.sendMessageAsync(msg).collect { m -> sb.append(text(m)) }
                    val answer = ThinkParser.answerOnly(sb.toString(), HfModels.isThinking(loadedPath.orEmpty()))
                    de.foldki.app.data.DebugLog.write("Lokal GitHub Schritt $step: " + answer.replace("\n", "⏎").take(800))
                    msg = onTurn(answer)
                }
                step
            } finally {
                runCatching { conv.close() }
            }
        }

    private fun text(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun dropKept() {
        val c = kept
        kept = null; keptIds = emptyList(); keptKey = ""; keptSystem = ""; keptTurns = 0; keptChars = 0
        runCatching { c?.close() }
    }

    private fun closeNow() {
        dropKept()
        val e = engine
        engine = null; backendUsed = null; loadedPath = null
        runCatching { e?.close() }
    }

    /** Frees the model memory. Safe to call any time; the model loads again on the next question. */
    fun close() {
        synchronized(this) { loading?.cancel(); loading = null }
        closeNow()
    }

    companion object {
        /** Context of the engine (prompt + answer). Most .litertlm files are built for 4096. */
        const val MAX_TOKENS = 4096
        /** After this many turns in one open conversation it starts fresh with the last turns (context stays small). */
        const val MAX_KEPT_TURNS = 16
        /** Conservative: German text is about 2.5–4 characters per token. */
        const val CHARS_PER_TOKEN = 2.2

        /** "…_ekv1280…" → 1280 (at most [MAX_TOKENS]); without that hint [MAX_TOKENS]. */
        fun contextFor(path: String): Int {
            val n = Regex("ekv(\\d{3,6})", RegexOption.IGNORE_CASE).find(path.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
            return (n ?: MAX_TOKENS).coerceIn(512, MAX_TOKENS)
        }
    }
}
