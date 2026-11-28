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
import kotlinx.coroutines.flow.takeWhile
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
    @Volatile var visionReady: Boolean = false
        private set

    /** One turn of the chat history (id = ChatMessage.id, used to recognise the open conversation). */
    data class Turn(val id: String, val user: Boolean, val text: String)

    // The open chat conversation (only one conversation exists at a time).
    private var kept: Conversation? = null
    private var keptKey = ""
    private var keptSystem = ""
    private var keptTemp: Double? = null
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
    fun loadAsync(modelPath: String, preferGpu: Boolean, onAttempt: (String) -> Unit = {}, context: Int = 0, requireVision: Boolean = false): Deferred<String> = synchronized(this) {
        loading?.takeIf { it.isActive }?.let { return it }
        loadScope.async {
            mutex.withLock {
                if (engine != null && loadedPath == modelPath && (!requireVision || visionReady)) return@withLock backendUsed ?: "CPU"
                closeNow()
                // Qwen files without "gpu" in the name crash on the GPU (memory): blocked before loading.
                if (preferGpu && GpuGuard.blocked(modelPath)) throw IllegalStateException(GpuGuard.TEXT)
                // Ab 4.9: context per model (Modelle → Einstellungen), else as built.
                val ctx = if (context > 0) context.coerceIn(1024, 32768) else contextFor(modelPath)
                var lastError: Throwable? = null
                // GPU builds ("gpu" in the name) only run on the GPU – never silently on the CPU.
                val gpuOnly = preferGpu && HfModels.isGpuFile(modelPath)
                val candidates = if (requireVision) attempts(ctx, preferGpu, gpuOnly).map { it.copy(vision = true) }.distinct() else attempts(ctx, preferGpu, gpuOnly)
                for (a in candidates) {
                    onAttempt(a.backend)
                    val e = try {
                        Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = if (a.backend == "GPU") Backend.GPU() else Backend.CPU(),
                                visionBackend = if (a.vision) Backend.GPU() else null,
                                maxNumTokens = a.ctx,
                                cacheDir = cacheFor(modelPath, a.backend),
                            )
                        )
                    } catch (t: Throwable) { lastError = t; continue }
                    try {
                        e.initialize()
                        engine = e; backendUsed = a.backend; loadedPath = modelPath; contextTokens = a.ctx; visionReady = a.vision
                        if (a != attempts(ctx, preferGpu, gpuOnly).first())
                            de.foldki.app.data.DebugLog.write("Geladen mit Ersatz-Einstellung: ${a.backend} · Kontext ${a.ctx}" + if (a.vision) " · Bildteil GPU" else "")
                        return@withLock a.backend
                    } catch (t: Throwable) { // incl. UnsatisfiedLinkError / missing OpenCL
                        lastError = t
                        de.foldki.app.data.DebugLog.write("Versuch ${a.backend} · Kontext ${a.ctx}${if (a.vision) " · Bildteil GPU" else ""} fehlgeschlagen: ${t.message?.take(300)}")
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
    fun chat(
        key: String, system: String, earlier: List<Turn>, question: String, questionId: String, answerId: String, temperature: Double? = null,
        topK: Int = 0, topP: Float = 0f, imagePaths: List<String> = emptyList(),
    ): Flow<String> = flow {
        samplerTopK = topK; samplerTopP = topP
        val e = engine ?: throw IllegalStateException("Modell ist nicht geladen.")
        val ids = earlier.map { it.id }
        check(imagePaths.isEmpty() || visionReady) { "Bildverarbeitung konnte nicht initialisiert werden." }
        val budget = (inputBudgetChars - imagePaths.size * 768 * 2).coerceAtLeast(0)
        val contents = if (imagePaths.isEmpty()) null else Contents.of(*(imagePaths.map { Content.ImageFile(it) } + Content.Text(question)).toTypedArray())
        fun send(c: Conversation): Flow<Message> = if (contents == null) c.sendMessageAsync(question) else c.sendMessageAsync(contents)
        // Reuse the open conversation only while everything still fits into the model's context.
        val probe = de.foldki.app.data.TextTrace.active(answerId)
        val reuse = imagePaths.isEmpty() && !probe && kept != null && keptKey == key && keptSystem == system && keptTemp == temperature && keptIds == ids && keptTurns < MAX_KEPT_TURNS &&
            system.length + keptChars + question.length <= budget
        var conv = if (reuse) kept!! else open(e, system, if (probe) emptyList() else earlier, budget - system.length - question.length, temperature)
            .also { kept = it; keptKey = key; keptSystem = system; keptTemp = temperature; keptTurns = 0 }
        keptIds = emptyList() // only valid again after a complete answer
        val sb = StringBuilder()
        var done = false
        if (probe) {
            de.foldki.app.data.TextTrace.stage(answerId, "03 SDK_PROMPT", question)
            val f = loadedPath?.let { java.io.File(it) }
            de.foldki.app.data.TextTrace.stage(answerId, "03 SDK_RUNTIME", "LiteRT-LM dependency 0.20.0 | backend=" + backendUsed + " | context=" + contextTokens + " | model=" + f?.name + " | bytes=" + f?.length() + " | modified=" + f?.lastModified() + " | cache=" + loadedPath?.let { cacheFor(it, backendUsed ?: "CPU") } + " | fresh conversation")
        }
        try {
            try {
                send(conv).collect { m -> val t = text(m); if (t.isNotEmpty()) { sb.append(t); emit(t) } }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || !reuse || sb.isNotEmpty()) throw t
                // The open conversation is full or broken: start it fresh once.
                dropKept()
                conv = open(e, system, earlier, budget - system.length - question.length, temperature).also { kept = it; keptKey = key; keptSystem = system; keptTemp = temperature; keptTurns = 0 }
                send(conv).collect { m -> val x = text(m); if (x.isNotEmpty()) { sb.append(x); emit(x) } }
            }
            done = true
            keptIds = ids + questionId + answerId
            keptTurns += 2
            keptChars += question.length + sb.length
        } finally {
            if (probe) de.foldki.app.data.TextTrace.stage(answerId, "04 SDK_CONTENT_TEXT_JOINED", sb.toString())
            if (!done || probe) { if (!done) runCatching { conv.cancelProcess() }; dropKept() }
        }
    }.flowOn(Dispatchers.Default)

    // Ab 4.9: Top-K / Top-P per model (0 = app default 40 / 0.95).
    @Volatile private var samplerTopK = 0
    @Volatile private var samplerTopP = 0f

    /** New conversation with the newest earlier turns that fit into [room] characters. */
    private fun open(e: Engine, system: String, earlier: List<Turn>, room: Int, temperature: Double? = null): Conversation {
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
        // "Kreativität": null = the model's own sampler (nothing added).
        val k = if (samplerTopK > 0) samplerTopK else 40
        val p = if (samplerTopP > 0f) samplerTopP.toDouble() else 0.95
        val sampler = if (temperature != null) SamplerConfig(topK = k, topP = p, temperature = temperature)
            else if (samplerTopK > 0 || samplerTopP > 0f) SamplerConfig(topK = k, topP = p, temperature = 0.8) else null
        return if (system.isBlank()) e.createConversation(ConversationConfig(initialMessages = initial, samplerConfig = sampler))
        else e.createConversation(ConversationConfig(systemInstruction = Contents.of(system), initialMessages = initial, samplerConfig = sampler))
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
     * Multi-step conversation (tools): the model answers, [onTurn] gets the answer and returns the next message (e.g. a
     * tool result) or null to stop. Ab 4.9: the model stops writing as soon as its tool call is complete, and every step
     * starts a fresh conversation from the kept turns – so an invented "ERGEBNIS" after the call never stays in the
     * history (only the real result of the app does).
     */
    suspend fun session(system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?, sampler: SamplerConfig? = null) =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val e = engine ?: throw IllegalStateException("Modell ist nicht geladen.")
            dropKept()
            val turns = ArrayList<Message>()
            val texts = ArrayList<Int>() // length of each turn, to stay inside the context
            val thinking = HfModels.isThinking(loadedPath.orEmpty())
            var msg: String? = first
            var step = 0
            while (msg != null && step < maxSteps) {
                step++
                // Keep the newest turns that fit next to the instruction and the new message.
                val room = inputBudgetChars - system.length - msg.length
                while (turns.size > 2 && texts.sum() > room) { turns.removeAt(0); turns.removeAt(0); texts.removeAt(0); texts.removeAt(0) }
                val conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        initialMessages = turns.toList(),
                        samplerConfig = sampler ?: SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                    ),
                )
                val sb = StringBuilder()
                var cut = false
                try {
                    conv.sendMessageAsync(msg).takeWhile { m ->
                        sb.append(text(m))
                        // Stop as soon as the tool call is complete (after the thoughts of a thinking model).
                        val done = sb.contains('}') && de.foldki.app.ai.GemmaGitHubAgent.callEnd(ThinkParser.answerOnly(sb.toString(), thinking)) > 0
                        if (done) cut = true
                        !done
                    }.collect { }
                } finally {
                    if (cut) runCatching { conv.cancelProcess() }
                    runCatching { conv.close() }
                }
                val answer = de.foldki.app.ai.GemmaGitHubAgent.cutAtCall(ThinkParser.answerOnly(sb.toString(), thinking))
                de.foldki.app.data.DebugLog.write("Lokal Werkzeug-Schritt $step: " + answer.replace("\n", "⏎").take(800))
                turns += Message.user(msg); texts += msg.length
                turns += Message.model(answer); texts += answer.length
                msg = onTurn(answer)
            }
            step
        }

    private fun text(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun dropKept() {
        val c = kept
        kept = null; keptIds = emptyList(); keptKey = ""; keptSystem = ""; keptTemp = null; keptTurns = 0; keptChars = 0
        runCatching { c?.close() }
    }

    private fun closeNow() {
        dropKept()
        val e = engine
        engine = null; backendUsed = null; loadedPath = null; visionReady = false
        runCatching { e?.close() }
    }

    /** Frees the model memory. Safe to call any time; the model loads again on the next question. */
    fun close() {
        synchronized(this) { loading?.cancel(); loading = null }
        closeNow()
    }

    /**
     * Files not built for the GPU (e.g. "Qwen3.5-2B_int8") run on the GPU only without the weight cache: writing the
     * cache converts all weights at once and needed ~8 GB on the Fold 7 (Android killed the app). The model card runs
     * them with "--backend gpu --cache no". GPU builds and the CPU keep the cache (faster second start).
     */
    private fun cacheFor(modelPath: String, backend: String): String =
        if (backend == "GPU" && !HfModels.isGpuFile(modelPath) && !modelPath.substringAfterLast('/').lowercase().startsWith("gemma")) NO_CACHE
        else if (modelPath.substringAfterLast('/').lowercase().let { "gemma" in it && "e4b" in it })
            java.io.File(app.cacheDir, "litertlm-020-e4b-v57").apply { mkdirs() }.path
        else app.cacheDir.path

    /** One way to start the engine. */
    data class Attempt(val backend: String, val ctx: Int, val vision: Boolean = false)

    companion object {
        /**
         * Load attempts in order. On the GPU: as built, then with the image part on the GPU (some multimodal GPU
         * builds need it), then with a smaller context (less GPU memory). The CPU only for files not built for the GPU.
         */
        fun attempts(ctx: Int, preferGpu: Boolean, gpuOnly: Boolean): List<Attempt> {
            val gpu = if (!preferGpu) emptyList() else buildList {
                add(Attempt("GPU", ctx))
                add(Attempt("GPU", ctx, vision = true))
                listOf(2048, 1024).filter { it < ctx }.forEach { add(Attempt("GPU", it)) }
            }
            return if (gpuOnly) gpu else gpu + Attempt("CPU", ctx)
        }

        /** Context of the engine (prompt + answer). Most .litertlm files are built for 4096. */
        const val MAX_TOKENS = 4096
        /** LiteRT-LM sentinel: no weight/program cache. */
        const val NO_CACHE = ":nocache"
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
