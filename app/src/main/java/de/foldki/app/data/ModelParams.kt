package de.foldki.app.data

import org.json.JSONObject

/**
 * Einstellungen pro Modell (ab 4.9, Modelle → Modell → Einstellungen), wie in Google AI Edge Gallery.
 * 0 / -1 / "" = Standard (die App oder das Modell entscheidet).
 */
data class ModelParams(
    /** Höchstens so viele Tokens pro Antwort (0 = ohne Grenze). */
    val maxTokens: Int = 0,
    val topK: Int = 0,
    val topP: Float = 0f,
    /** -1 = Standard (Schieber „Kreativität“ im Chat). */
    val temperature: Float = -1f,
    /** "" = Standard, "off" = aus, "on" = an (nur Denk-Modelle). */
    val think: String = "",
    /** Kontext in Tokens für LiteRT-Modelle (0 = wie in der Datei gebaut). */
    val context: Int = 0,
) {
    val isDefault: Boolean get() = this == ModelParams()

    fun toJson(): JSONObject = JSONObject().put("maxTokens", maxTokens).put("topK", topK).put("topP", topP.toDouble())
        .put("temperature", temperature.toDouble()).put("think", think).put("context", context)

    companion object {
        fun fromJson(o: JSONObject?): ModelParams = if (o == null) ModelParams() else ModelParams(
            maxTokens = o.optInt("maxTokens", 0), topK = o.optInt("topK", 0), topP = o.optDouble("topP", 0.0).toFloat(),
            temperature = o.optDouble("temperature", -1.0).toFloat(), think = o.optString("think", ""), context = o.optInt("context", 0),
        )

        /**
         * Empfohlene Werte, solange der Nutzer nichts eingestellt hat: Qwen-Modelle laut Modellkarte (Top-K 20, Top-P 0,8,
         * Temperatur 0,7) und ohne Denken – Denken macht sie auf dem Handy langsam und heiß.
         */
        fun recommended(file: String): ModelParams {
            val f = file.lowercase()
            return if (f.contains("qwen")) ModelParams(topK = 20, topP = 0.8f, temperature = 0.7f, think = "off") else ModelParams()
        }
    }
}
