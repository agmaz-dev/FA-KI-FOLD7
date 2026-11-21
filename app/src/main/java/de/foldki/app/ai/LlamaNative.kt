package de.foldki.app.ai

/** JNI bridge to llama.cpp (app/src/main/cpp/foldki_llm.cpp, ported from Jarvis-Android). */
object LlamaNative {
    fun interface TokenCallback { fun onToken(utf8: ByteArray) }

    /** null = loaded, else the error text of the native library. */
    @Volatile var loadError: String? = null
        private set

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("foldki_llm") }.onFailure { loadError = it.message ?: it.javaClass.simpleName }.isSuccess
    }

    /** Loads the compute parts (CPU, GPU via OpenCL) once; without OpenCL on the phone only the CPU part is there. */
    @JvmStatic external fun nativeInit(nativeLibDir: String)
    /** gpuPercent: share of the model layers on the GPU (0 = CPU only). Falls back to CPU only if the GPU fails. */
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, gpuPercent: Int): Boolean
    /** Short German text: GPU name and how many layers run on it, or "Nur Prozessor …". */
    @JvmStatic external fun nativeBackendInfo(): String
    @JvmStatic external fun nativeError(): String
    @JvmStatic external fun nativeContextSize(): Int
    @JvmStatic external fun nativeThinkOpen(): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeUnload()
    @JvmStatic external fun nativeGenerate(
        roles: Array<String>, contents: Array<String>, maxTokens: Int, temp: Float, topK: Int, topP: Float,
        think: Boolean, thinkBudget: Int, cb: TokenCallback,
    ): String
}
