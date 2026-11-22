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
    /**
     * gpuPercent: share of the model layers on the GPU (0 = CPU only). npu = all layers on the NPU (Hexagon HTP0)
     * instead of the GPU. Falls back to CPU only if the GPU/NPU fails.
     */
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, gpuPercent: Int, npu: Boolean): Boolean
    /** Report of the last NPU load: Hexagon messages (arch version, errors) and the operations the NPU rejected. */
    @JvmStatic external fun nativeNpuReport(): String
    /** true = libggml-hexagon.so and libggml-htp-v79.so are in this APK (in [nativeLibDir]). */
    @JvmStatic external fun nativeNpuBuilt(nativeLibDir: String): Boolean
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
