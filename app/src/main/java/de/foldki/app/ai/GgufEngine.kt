package de.foldki.app.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * GGUF models (also the files of LM Studio) via llama.cpp, taken from FA Fold KI. The GPU (Adreno, OpenCL) gets
 * [gpuPercent] of the layers; if the GPU fails, llama.cpp falls back to the CPU natively. Nothing is added to what the
 * model gets: the user's own instruction (may be empty), the chat and the question.
 */
class GgufEngine(context: Context) {
    private val app = context.applicationContext
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadMutex = Mutex()
    private val genMutex = Mutex()
    private var backendsReady = false
    private var loading: Deferred<String>? = null

    @Volatile var loadedPath: String? = null
        private set
    @Volatile var loadedGpu: Int = -1
        private set
    @Volatile var loadedNpu: Boolean = false
        private set
    /** "GPU 75 %", "NPU" or "CPU" of the loaded model. */
    @Volatile var backendUsed: String? = null
        private set
    @Volatile var contextTokens: Int = N_CTX
        private set

    /** The chat template opened a thinking block (Qwen3 & co.); valid after the first piece. */
    val thinkOpen: Boolean get() = runCatching { LlamaNative.nativeThinkOpen() }.getOrDefault(false)

    /** Characters of input that safely fit next to an answer. */
    val inputBudgetChars: Int get() = ((contextTokens - MAX_ANSWER_TOKENS / 2) * 2.5).toInt().coerceAtLeast(1000)

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Crash guard for the NPU: the app died while a model loaded on the NPU -> NPU off (GPU share / CPU stay usable).
        if (prefs.getBoolean(KEY_NPU_LOADING, false)) {
            prefs.edit().putBoolean(KEY_NPU_LOADING, false).putBoolean("npu", false).commit()
            de.foldki.app.data.DebugLog.write("NPU: App wurde beim Laden auf der NPU beendet – NPU ausgeschaltet")
        }
    }

    /**
     * Loads [path] with [gpuPercent] % of the layers on the GPU. If "Auf der NPU rechnen" is on (Settings.npu, not with
     * "Rechnen auf → CPU") and the NPU part is in this APK, all layers go to the NPU instead. Returns "GPU x %", "NPU" or "CPU".
     */
    fun loadAsync(path: String, gpuPercent: Int): Deferred<String> = synchronized(this) {
        loading?.takeIf { it.isActive }?.let { return it }
        val npu = prefs.getBoolean("npu", false) && prefs.getString("gemma_backend", "GPU") != "CPU" && npuBuilt(app)
        loadScope.async {
            loadMutex.withLock {
                if (loadedPath == path && loadedGpu == gpuPercent && loadedNpu == npu) return@withLock backendUsed ?: "CPU"
                if (!LlamaNative.available) throw IllegalStateException("llama.cpp fehlt in dieser APK: ${LlamaNative.loadError}")
                if (!backendsReady) { LlamaNative.nativeInit(app.applicationInfo.nativeLibraryDir.orEmpty()); backendsReady = true }
                genMutex.withLock {
                    if (loadedPath != null) { LlamaNative.nativeUnload(); loadedPath = null; loadedGpu = -1; loadedNpu = false; backendUsed = null }
                    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                    if (npu) { de.foldki.app.data.DebugLog.write("LADE auf NPU (Hexagon HTP0)"); prefs.edit().putBoolean(KEY_NPU_LOADING, true).commit() }
                    val ok = LlamaNative.nativeLoad(path, N_CTX, threads, gpuPercent.coerceIn(0, 100), npu)
                    if (npu) prefs.edit().putBoolean(KEY_NPU_LOADING, false).commit()
                    if (!ok) {
                        if (npu) { lastNpuReport = LlamaNative.nativeNpuReport().trim(); de.foldki.app.data.DebugLog.write("NPU-Bericht:\n$lastNpuReport") }
                        throw IllegalStateException(LlamaNative.nativeError().ifBlank { "GGUF-Modell konnte nicht geladen werden." })
                    }
                    contextTokens = LlamaNative.nativeContextSize().takeIf { it > 0 } ?: N_CTX
                    loadedPath = path
                    loadedGpu = gpuPercent
                    loadedNpu = npu
                    val info = LlamaNative.nativeBackendInfo()
                    backendUsed = when {
                        info.startsWith("NPU") -> "NPU"
                        info.startsWith("Nur Prozessor") || (!npu && gpuPercent == 0) -> "CPU"
                        else -> "GPU $gpuPercent %"
                    }
                    de.foldki.app.data.DebugLog.write("GGUF geladen: ${path.substringAfterLast('/')} · $info")
                    if (npu) {
                        lastNpuReport = LlamaNative.nativeNpuReport().trim()
                        de.foldki.app.data.DebugLog.write("NPU-Bericht:\n$lastNpuReport")
                    }
                    backendUsed!!
                }
            }
        }.also { loading = it }
    }

    /**
     * Chat answer, raw text (thinking included). [system] blank = no system message. [maxTokens] 0 = [MAX_ANSWER_TOKENS].
     * [temperature] null = llama.cpp default of FA Fold KI (0.6).
     */
    fun chat(system: String, earlier: List<Pair<Boolean, String>>, question: String, temperature: Double? = null, maxTokens: Int = 0): Flow<String> =
        generate(
            buildList {
                if (system.isNotBlank()) add("system" to system)
                earlier.forEach { (user, text) -> add((if (user) "user" else "assistant") to text) }
                add("user" to question)
            },
            if (maxTokens > 0) maxTokens else MAX_ANSWER_TOKENS, temperature?.toFloat() ?: 0.6f, think = true,
        )

    /** Multi-step conversation (GitHub tasks). */
    suspend fun session(system: String, first: String, maxSteps: Int, onTurn: suspend (String) -> String?): Int {
        val msgs = ArrayList<Pair<String, String>>()
        msgs += "user" to first
        var step = 0
        var next: String? = first
        while (next != null && step < maxSteps) {
            step++
            while (msgs.size > 3 && msgs.sumOf { it.second.length } > 16_000) { msgs.removeAt(1); msgs.removeAt(1) }
            val sb = StringBuilder()
            generate((if (system.isBlank()) emptyList() else listOf("system" to system)) + msgs, MAX_ANSWER_TOKENS, 0.3f, think = true)
                .collect { sb.append(it) }
            val answer = ThinkParser.answerOnly(sb.toString(), LlamaNative.nativeThinkOpen())
            de.foldki.app.data.DebugLog.write("GGUF GitHub Schritt $step: " + answer.replace("\n", "⏎").take(800))
            msgs += "assistant" to answer
            next = onTurn(answer)
            if (next != null) msgs += "user" to next
        }
        return step
    }

    private fun generate(messages: List<Pair<String, String>>, maxTokens: Int, temp: Float, think: Boolean): Flow<String> = callbackFlow {
        if (loadedPath == null) throw IllegalStateException("Kein GGUF-Modell geladen.")
        val job = launch(Dispatchers.Default) {
            genMutex.withLock {
                val stats = LlamaNative.nativeGenerate(
                    messages.map { it.first }.toTypedArray(), messages.map { it.second }.toTypedArray(),
                    maxTokens, temp, 20, 0.95f, think, if (think) THINK_BUDGET else 0,
                ) { bytes -> trySend(String(bytes, Charsets.UTF_8)) }
                val err = runCatching { JSONObject(stats).optString("error") }.getOrDefault("")
                if (err.isNotBlank()) close(IllegalStateException(err)) else close()
            }
        }
        awaitClose { if (job.isActive) LlamaNative.nativeStop() }
    }.flowOn(Dispatchers.Default)

    fun close() {
        synchronized(this) { loading?.cancel(); loading = null }
        loadScope.launch {
            genMutex.withLock { if (loadedPath != null) { runCatching { LlamaNative.nativeUnload() }; loadedPath = null; loadedGpu = -1; loadedNpu = false; backendUsed = null } }
        }
    }

    companion object {
        private const val KEY_NPU_LOADING = "npu_loading"

        /** Report of the last load with NPU ("Hexagon Arch version v79", rejected operations) for the Modelle page. */
        @Volatile var lastNpuReport: String = ""
            private set

        /** true = the NPU part (Hexagon) is in this APK. */
        fun npuBuilt(context: Context): Boolean = LlamaNative.available &&
            runCatching { LlamaNative.nativeNpuBuilt(context.applicationInfo.nativeLibraryDir.orEmpty()) }.getOrDefault(false)

        const val N_CTX = 8192
        const val MAX_ANSWER_TOKENS = 4000
        const val THINK_BUDGET = 1200
    }
}
