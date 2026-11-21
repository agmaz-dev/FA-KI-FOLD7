package de.foldki.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One sender for letters ("Privat", "Firma" …). Stored only on the device. The signature image is kept separately ([SignatureStore]). */
data class SenderProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val name: String = "",
    val street: String = "",
    val zipCity: String = "",
    val phone: String = "",
    val email: String = "",
    /** e.g. customer or tax number, at most 200 characters when typed in. */
    val extra: String = "",
    /** Only used when [initialsManual]; otherwise they follow the name. */
    val initials: String = "",
    val initialsManual: Boolean = false,
) {
    val effectiveInitials: String get() = if (initialsManual && initials.isNotBlank()) initials.trim() else initialsOf(name)

    /** City for the date line ("Berlin, 16.11.2026"). */
    val city: String get() = zipCity.trim().replace(Regex("^(D-\\s?)?\\d{4,5}\\s*"), "").trim()

    val title: String get() = label.ifBlank { name.ifBlank { "Profil" } }

    /** The old single text field "Mein Absender" (kept in sync for older code paths). */
    fun toLegacyText(): String = listOf(name, street, zipCity, phone, email, extra).filter { it.isNotBlank() }.joinToString("\n")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("name", name).put("street", street).put("zipCity", zipCity)
        .put("phone", phone).put("email", email).put("extra", extra).put("initials", initials).put("initialsManual", initialsManual)

    companion object {
        const val EXTRA_MAX = 200
        private val TITLES = setOf("dr", "prof", "dipl", "ing", "med", "herr", "frau", "mag", "von", "van", "de", "der", "zu")

        /** "Paula Fischer" -> "PF", "Dr. Max von Muster" -> "MM", "Muster GmbH" -> "MG". */
        fun initialsOf(name: String): String {
            val words = name.trim().split(Regex("[\\s,]+")).map { it.trim('.', '-', '(', ')') }
                .filter { it.isNotEmpty() && it.lowercase().trimEnd('.') !in TITLES && it.first().isLetter() }
            return when {
                words.isEmpty() -> ""
                words.size == 1 -> words[0].take(2).uppercase()
                else -> (words.first().first().toString() + words.last().first()).uppercase()
            }
        }

        fun fromJson(o: JSONObject) = SenderProfile(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            label = o.optString("label"), name = o.optString("name"), street = o.optString("street"),
            zipCity = o.optString("zipCity"), phone = o.optString("phone"), email = o.optString("email"),
            extra = o.optString("extra"), initials = o.optString("initials"), initialsManual = o.optBoolean("initialsManual"),
        )

        fun listToJson(list: List<SenderProfile>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String?): List<SenderProfile> = runCatching {
            if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        }.getOrDefault(emptyList())

        private val ZIP = Regex("^(D-\\s?)?\\d{5}\\s+\\S.*")
        private val STREET = Regex(".*(straße|strasse|str\\.|weg|platz|allee|gasse|ring|damm|ufer|chaussee|steig|pfad|hof|postfach).*\\d.*|.*\\D\\s\\d{1,4}\\s?[a-zA-Z]?$", RegexOption.IGNORE_CASE)
        private val PHONE = Regex("^(tel\\.?|telefon|mobil|handy|fon)?\\s*:?\\s*[+0][\\d /()\\-]{6,}$", RegexOption.IGNORE_CASE)
        private val EMAIL = Regex("^(e-?mail\\s*:?\\s*)?[\\w.+\\-]+@[\\w\\-]+\\.[\\w.\\-]+$", RegexOption.IGNORE_CASE)

        /** One-time migration of the old free-text sender. Nothing is lost: lines that fit no field go to [extra]. */
        fun fromLegacy(text: String): SenderProfile {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            var name = ""; var street = ""; var zip = ""; var phone = ""; var email = ""
            val rest = mutableListOf<String>()
            lines.forEachIndexed { i, l ->
                when {
                    i == 0 -> name = l
                    zip.isEmpty() && ZIP.matches(l) -> zip = l
                    street.isEmpty() && zip.isEmpty() && STREET.matches(l) -> street = l
                    phone.isEmpty() && PHONE.matches(l) -> phone = l
                    email.isEmpty() && EMAIL.matches(l) -> email = l
                    else -> rest += l
                }
            }
            return SenderProfile(label = "Privat", name = name, street = street, zipCity = zip, phone = phone, email = email, extra = rest.joinToString("\n"))
        }

        /** Block for Nano/Claude when a letter is written; empty fields are left out. Never contains the signature image. */
        fun promptBlock(p: SenderProfile): String = buildString {
            append("Absender (aktiv):\n")
            listOf(p.name, p.street, p.zipCity, p.phone, p.email, p.extra).filter { it.isNotBlank() }.forEach { append(it.trim()).append("\n") }
            if (p.effectiveInitials.isNotBlank()) append("Initialen: ").append(p.effectiveInitials).append("\n")
        }.trimEnd()
    }
}
