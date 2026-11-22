package de.foldki.app.ai

/**
 * Qwen .litertlm files without "gpu" in the name crash on the GPU (not enough memory, Android ends the app).
 * They are blocked on the GPU before loading; on the CPU ("Rechnen auf → CPU") they may run.
 */
object GpuGuard {
    fun blocked(fileName: String): Boolean {
        val n = fileName.substringAfterLast('/').lowercase()
        return n.endsWith(HfModels.EXT) && n.contains("qwen") && !HfModels.isGpuFile(n)
    }

    const val TEXT = "Diese Qwen-Datei ist nicht für den Grafikprozessor gebaut und würde die App auf der GPU abstürzen lassen " +
        "(zu wenig Speicher). Lade unter Erkunden die GGUF-Version (Umschalter „GGUF“, Q4_K_M) oder eine LiteRT-Datei mit „gpu“ im Namen. " +
        "Zum Testen geht sie mit Einstellungen → Rechnen auf → CPU."

    const val NOTICE = "Qwen-Datei ohne „gpu“ im Namen: auf der GPU gesperrt (Absturzgefahr). GGUF- oder gpu-Variante laden."
}
