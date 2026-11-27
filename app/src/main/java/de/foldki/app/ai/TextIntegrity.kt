package de.foldki.app.ai

/** Plain chat text must not lose literal syntax. Document export has its own formatting. */
object TextIntegrity {
    const val PROBE = "FA_TEXT_DIAG"
    fun isProbe(text: String): Boolean = text.lineSequence().firstOrNull()?.trim() == PROBE
    fun preserve(text: String): Boolean = CodeText.hasCode(text) ||
        text.any { it == '*' || it == '_' || it == '\u0060' || it == '\\' || it == '~' }
    fun chat(text: String): String = if (preserve(text)) text else de.foldki.app.export.DocumentParser.display(text)
    fun copy(text: String): String = text
}
