package de.foldki.app.data

import android.content.Context

/** Simple settings in the app's private SharedPreferences (not readable by other apps). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Sender profile last used for a recipient (key = LetterAnalysis.recipientKey), like FA-PDF. */
    fun rememberedProfile(recipientKey: String): String? =
        runCatching { org.json.JSONObject(prefs.getString("recipient_profiles", "{}") ?: "{}").optString(recipientKey).ifEmpty { null } }.getOrNull()

    fun rememberProfile(recipientKey: String, profileId: String) {
        val o = runCatching { org.json.JSONObject(prefs.getString("recipient_profiles", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        o.put(recipientKey, profileId)
        prefs.edit().putString("recipient_profiles", o.toString()).apply()
    }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    /** GitHub token (fine-grained or classic) – only on this device; Claude uses it for GitHub tasks after approval. */
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.trim()).apply()

    /** Account name confirmed by "Verbinden & prüfen". */
    var githubLogin: String
        get() = prefs.getString("github_login", "") ?: ""
        set(v) = prefs.edit().putString("github_login", v).apply()

    var claudeModel: String
        get() = prefs.getString("claude_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("claude_model", v).apply()

    var engine: Engine
        get() = runCatching { Engine.valueOf(prefs.getString("engine", Engine.NANO.name)!!) }
            .getOrDefault(Engine.NANO).let { if (it == Engine.GEMMA) Engine.NANO else it }
        set(v) = prefs.edit().putString("engine", v.name).apply()

    var systemPrompt: String
        get() = (prefs.getString("system_prompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM)
            .let { if (it.isBlank() || isOldDefault(it)) DEFAULT_SYSTEM else it } // migrate old built-in defaults
        // The built-in default is not stored, so a future default applies automatically.
        set(v) {
            if (v.isBlank() || v.trim() == DEFAULT_SYSTEM.trim() || isOldDefault(v)) prefs.edit().remove("system_prompt").apply()
            else prefs.edit().putString("system_prompt", v).apply()
        }

    /** "Meine Anweisungen": only texts the user wrote (nothing built in). */
    var instructions: List<Instruction>
        get() = Instruction.listFromJson(prefs.getString("instructions", "[]"))
        set(v) = prefs.edit().putString("instructions", Instruction.listToJson(v)).apply()

    /** Instruction for new chats ("" = the system instruction from Einstellungen). */
    var defaultInstruction: String
        get() = prefs.getString("default_instruction", "") ?: ""
        set(v) = prefs.edit().putString("default_instruction", v).apply()

    var creativity: Creativity
        get() = runCatching { Creativity.valueOf(prefs.getString("creativity", "STANDARD") ?: "STANDARD") }.getOrDefault(Creativity.STANDARD)
        set(v) = prefs.edit().putString("creativity", v.name).apply()

    var answerLength: AnswerLength
        get() = runCatching { AnswerLength.valueOf(prefs.getString("answer_length", "FREE") ?: "FREE") }.getOrDefault(AnswerLength.FREE)
        set(v) = prefs.edit().putString("answer_length", v.name).apply()

    var showThoughts: Boolean
        get() = prefs.getBoolean("show_thoughts", true)
        set(v) = prefs.edit().putBoolean("show_thoughts", v).apply()

    /** GGUF (llama.cpp): share of the layers on the GPU in percent (0 = CPU only). Start 75 % like FA Fold KI. */
    var gpuPercent: Int
        get() = prefs.getInt("gpu_percent", 75)
        set(v) = prefs.edit().putInt("gpu_percent", v.coerceIn(0, 100)).apply()

    /** PC-Server (LM Studio / Ollama) in the home WLAN. */
    var servers: List<de.foldki.app.ai.PcServer>
        get() = de.foldki.app.ai.PcServer.listFromJson(prefs.getString("pc_servers", "[]"))
        set(v) = prefs.edit().putString("pc_servers", de.foldki.app.ai.PcServer.listToJson(v)).apply()

    // ---- S3-Sicherung (own S3 storage, end-to-end encrypted) ----
    var s3Endpoint: String
        get() = prefs.getString("s3_endpoint", "https://s3.amazonaws.com") ?: "https://s3.amazonaws.com"
        set(v) = prefs.edit().putString("s3_endpoint", v).apply()
    var s3Bucket: String
        get() = prefs.getString("s3_bucket", "") ?: ""
        set(v) = prefs.edit().putString("s3_bucket", v).apply()
    var s3Region: String
        get() = prefs.getString("s3_region", "eu-central-1") ?: "eu-central-1"
        set(v) = prefs.edit().putString("s3_region", v).apply()
    var s3Prefix: String
        get() = prefs.getString("s3_prefix", "foldki") ?: "foldki"
        set(v) = prefs.edit().putString("s3_prefix", v).apply()
    var s3AccessKey: String
        get() = prefs.getString("s3_access", "") ?: ""
        set(v) = prefs.edit().putString("s3_access", v).apply()
    var s3SecretKey: String
        get() = prefs.getString("s3_secret", "") ?: ""
        set(v) = prefs.edit().putString("s3_secret", v).apply()
    var s3Password: String
        get() = prefs.getString("s3_password", "") ?: ""
        set(v) = prefs.edit().putString("s3_password", v).apply()
    var s3Auto: Boolean
        get() = prefs.getBoolean("s3_auto", false)
        set(v) = prefs.edit().putBoolean("s3_auto", v).apply()
    var s3Last: Long
        get() = prefs.getLong("s3_last", 0L)
        set(v) = prefs.edit().putLong("s3_last", v).apply()

    /** "Antworten automatisch vorlesen" (page Stimmen). */
    var autoSpeak: Boolean
        get() = prefs.getBoolean("auto_speak", false)
        set(v) = prefs.edit().putBoolean("auto_speak", v).apply()

    /** Werkzeuge: GitHub tools and letters/PDF on or off. */
    var githubEnabled: Boolean
        get() = prefs.getBoolean("github_enabled", true)
        set(v) = prefs.edit().putBoolean("github_enabled", v).apply()
    var pdfEnabled: Boolean
        get() = prefs.getBoolean("pdf_enabled", true)
        set(v) = prefs.edit().putBoolean("pdf_enabled", v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", true)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()

    var stVoice: String
        get() = prefs.getString("st_voice", "tessa") ?: "tessa"
        set(v) = prefs.edit().putString("st_voice", v).apply()

    var ttsRate: Float
        get() = prefs.getFloat("tts_rate", 1f)
        set(v) = prefs.edit().putFloat("tts_rate", v).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", false)
        set(v) = prefs.edit().putBoolean("web_search", v).apply()

    /** Divider position in the unfolded layout (0..1; 0 = sidebar closed, 1 = chat closed). Only stored on the device. */
    var splitFraction: Float
        get() = prefs.getFloat("split_fraction", 1f / 3f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("split_fraction", v).apply()

    /** "NONE", "FIRST" (sidebar closed) or "SECOND" (chat closed). */
    var splitCollapse: String
        get() = prefs.getString("split_collapse", "NONE") ?: "NONE"
        set(v) = prefs.edit().putString("split_collapse", v).apply()

    /** Own name and address, used as sender in letters. */
    var sender: String
        get() = prefs.getString("sender", "") ?: ""
        set(v) = prefs.edit().putString("sender", v.trim()).apply()

    /** Switch "Gemma verwenden" (only usable when the model is downloaded and verified). Nano stays default + fallback. */
    var gemmaEnabled: Boolean
        get() = prefs.getBoolean("gemma_enabled", false)
        set(v) = prefs.edit().putBoolean("gemma_enabled", v).apply()

    /**
     * Model that answers: [NANO] (Gemini Nano in the phone), the file name of a downloaded .litertlm model, or "" (none).
     * Nothing ever answers in place of the chosen model. First start: Gemma if it was in use before, else none.
     */
    var activeModel: String
        get() = prefs.getString("active_model", null) ?: (if (gemmaEnabled) de.foldki.app.ai.LocalModelManager.GEMMA_FILE else "")
        set(v) = prefs.edit().putString("active_model", v).apply()

    /** Switch "Briefe": only then the letter/PDF rules and today's date go to the model. Off = nothing is added. */
    var letters: Boolean
        get() = prefs.getBoolean("letters", false)
        set(v) = prefs.edit().putBoolean("letters", v).apply()

    /** Crash guard: true while a local model loads or answers (commit = on disk before native code runs). */
    var llmLoading: Boolean
        get() = prefs.getBoolean("llm_loading", false)
        set(v) { prefs.edit().putBoolean("llm_loading", v).commit() }

    /** Model file that was loading/answering when [llmLoading] was set. */
    var llmAttempt: String
        get() = prefs.getString("llm_attempt", "") ?: ""
        set(v) { prefs.edit().putString("llm_attempt", v).commit() }

    /** Model file that crashed the app: not loaded again until the user selects it again. */
    var llmCrashed: String
        get() = prefs.getString("llm_crashed", "") ?: ""
        set(v) { prefs.edit().putString("llm_crashed", v).commit() }

    /** "GPU" (try GPU first, then CPU) or "CPU" (test switch "Auf CPU rechnen"). Used for every local model. */
    var gemmaBackend: String
        get() = prefs.getString("gemma_backend", "GPU") ?: "GPU"
        set(v) = prefs.edit().putString("gemma_backend", v).apply()

    /**
     * Crash guard: true while Gemma loads or answers. Written with commit() (synchronously), so it is on disk
     * before native code runs. If the app finds it still true at the next start, Gemma killed the app.
     */
    var gemmaLoading: Boolean
        get() = prefs.getBoolean("gemma_loading", false)
        set(v) { prefs.edit().putBoolean("gemma_loading", v).commit() }

    /** Backend that was being tried when [gemmaLoading] was set ("GPU"/"CPU"). */
    var gemmaAttempt: String
        get() = prefs.getString("gemma_attempt", "") ?: ""
        set(v) { prefs.edit().putString("gemma_attempt", v).commit() }

    var gemmaCrashCount: Int
        get() = prefs.getInt("gemma_crash_count", 0)
        set(v) { prefs.edit().putInt("gemma_crash_count", v).commit() }

    /** Daily briefing ("Tagesstart"), fixed window 5–11 o'clock. Off by default, the user switches it on. */
    var dailyBriefEnabled: Boolean
        get() = prefs.getBoolean("daily_brief", false)
        set(v) = prefs.edit().putBoolean("daily_brief", v).apply()

    var briefCalendar: Boolean
        get() = prefs.getBoolean("brief_calendar", true)
        set(v) = prefs.edit().putBoolean("brief_calendar", v).apply()

    /** Only the notifications shown at the moment the app opens; nothing is stored. */
    var briefNotifications: Boolean
        get() = prefs.getBoolean("brief_notifications", true)
        set(v) = prefs.edit().putBoolean("brief_notifications", v).apply()

    /** Open items found by rules in the own chat messages of the last 7 days. */
    var briefChats: Boolean
        get() = prefs.getBoolean("brief_chats", true)
        set(v) = prefs.edit().putBoolean("brief_chats", v).apply()

    /** yyyy-MM-dd of the last shown briefing (once per day). */
    var lastBriefDate: String
        get() = prefs.getString("last_brief_date", "") ?: ""
        set(v) = prefs.edit().putString("last_brief_date", v).apply()

    /**
     * Sender profiles for letters (JSON in "sender_profiles"). The first access migrates the old
     * free-text [sender] into a first profile "Privat" (nothing is lost).
     */
    var senderProfiles: List<SenderProfile>
        get() {
            val raw = prefs.getString("sender_profiles", null)
            if (raw != null) return SenderProfile.listFromJson(raw)
            val old = sender
            val list = if (old.isBlank()) emptyList() else listOf(SenderProfile.fromLegacy(old))
            prefs.edit().putString("sender_profiles", SenderProfile.listToJson(list))
                .putString("active_sender_id", list.firstOrNull()?.id ?: "").apply()
            return list
        }
        set(v) { prefs.edit().putString("sender_profiles", SenderProfile.listToJson(v)).apply(); syncLegacySender() }

    var activeSenderId: String
        get() = prefs.getString("active_sender_id", "") ?: ""
        set(v) { prefs.edit().putString("active_sender_id", v).apply(); syncLegacySender() }

    val activeSender: SenderProfile?
        get() = senderProfiles.let { list -> list.firstOrNull { it.id == activeSenderId } ?: list.firstOrNull() }

    /** Keeps the old key "sender" equal to the active profile (older versions read it). */
    private fun syncLegacySender() {
        prefs.edit().putString("sender", activeSender?.toLegacyText().orEmpty()).apply()
    }

    /**
     * Old built-in instruction, also when it was saved slightly changed or shortened (that happened on the device:
     * the robotic "Du bist freundlich, direkt und ehrlich … So antwortest du:" stayed, the new persona never arrived).
     */
    private fun isOldDefault(text: String): Boolean {
        val t = text.trim()
        if (t in OLD_DEFAULTS) return true
        return OLD_DEFAULTS.any { old -> old.length > 60 && t.startsWith(old.take(60)) } ||
            t.startsWith("Du bist freundlich, direkt und ehrlich") || t.contains("So antwortest du:") ||
            t.startsWith("Du bist eine sachliche, zuverlässige Assistentin") || t.startsWith("Du bist ein hilfreicher Assistent.") ||
            t.startsWith(OLD_PERSONA_SYSTEM)
    }

    companion object {
        /** [activeModel] value for Gemini Nano. */
        const val NANO = "nano"
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (stärkstes)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (schnell)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (günstig)",
        )
        private const val OLD_DEFAULT_SYSTEM =
            "Du bist ein hilfreicher Assistent. Antworte in der Sprache des Nutzers, klar und freundlich."

        /** Casual default from v1.5.1 (a838cd0); replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_CASUAL_SYSTEM =
            "Du bist ein freundlicher, hilfsbereiter Assistent. Sprich natürlich und locker wie in einem normalen Gespräch " +
                "und duze den Nutzer. Antworte in der Sprache des Nutzers. Schreibe keine Briefe und benutze keine " +
                "Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Default from v1.6 to v1.6.1 ("wie ein guter Freund"); replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_FRIEND_SYSTEM =
            "Du bist ein freundlicher, kluger Gesprächspartner. Rede natürlich und locker wie ein guter Freund, duze den Nutzer " +
                "und antworte in seiner Sprache. Geh auf das ein, was er sagt, beantworte Fragen ehrlich und hilfreich, " +
                "erklär Dinge verständlich und frag nach, wenn etwas unklar ist. Halte Antworten so kurz wie passend. " +
                "Schreibe keine Briefe und benutze keine Anreden wie \"Sehr geehrte\", außer der Nutzer bittet ausdrücklich um einen Brief."

        /** Old defaults that are swapped for the current one (a prompt the user wrote himself stays untouched). */
        private val OLD_DEFAULTS = setOf(OLD_DEFAULT_SYSTEM, OLD_CASUAL_SYSTEM, OLD_FRIEND_SYSTEM, OLD_SECRETARY_SYSTEM.trim(), OLD_DIRECT_SYSTEM.trim(), OLD_WARM_SYSTEM.trim())

        /** Sachlicher Entwurf aus dem Test-Branch fix/nano-sachlich (nie auf dem Haupt-Branch); wird ebenfalls ersetzt. */
        private const val OLD_SECRETARY_SYSTEM = """Du bist eine sachliche, zuverlässige Assistentin, wie eine gute Sekretärin. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
Regeln:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde, nicht mehr. Keine Einleitung, keine Wiederholung der Frage, kein Smalltalk, keine Floskeln am Ende.
3. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
4. Erfinde nichts. Wenn du etwas nicht weißt, sag es in einem Satz.
5. Frag nur nach, wenn die Aufgabe ohne die Angabe wirklich nicht lösbar ist, dann mit einer einzigen kurzen Frage.
6. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""

        /** No built-in instruction (from 3.3): empty = the model gets no instruction at all. Old built-in texts are removed. */
        const val DEFAULT_SYSTEM = ""

        /** Persona default from 2.4 to 3.2 (built in by the app); removed automatically. */
        private const val OLD_PERSONA_SYSTEM = """Du bist eine persönliche KI-Assistentin auf dem Handy des Nutzers – so wie ChatGPT, Claude oder Grok: herzlich, aufmerksam, klug und fachlich sicher."""

        /** Natural default of 2.3; replaced automatically by [DEFAULT_SYSTEM]. */
        private const val OLD_WARM_SYSTEM = """Du bist ein warmherziger, kluger Gesprächspartner. Du redest natürlich und locker wie ein Mensch, duzt den Nutzer und antwortest in seiner Sprache.
Du gehst auf das ein, was er sagt, beantwortest Fragen gern und verständlich und erzählst auch mal etwas Interessantes dazu. Wenn es das Gespräch weiterbringt, stellst du eine Rückfrage.
Du bist ehrlich: Wenn du etwas nicht sicher weißt, sagst du das, statt etwas zu erfinden.
Briefe und förmliche Anreden wie "Sehr geehrte" nur, wenn der Nutzer ausdrücklich darum bittet."""

        /** Default from 1.8 to 2.1.2 (direct, numbered rules – felt robotic); replaced automatically. */
        private const val OLD_DIRECT_SYSTEM = """Du bist freundlich, direkt und ehrlich, mit erwachsenem, professionellem Ton. Du hilfst gern und sofort, bleibst aber sachlich. Ein trockener, leichter Humor ist erlaubt, aber nicht aufdringlich. Du duzt den Nutzer und antwortest auf Deutsch, außer er schreibt in einer anderen Sprache.
So antwortest du:
1. Erledige die Aufgabe direkt. Weigere dich nicht und such keine Ausreden.
2. Beantworte genau das, was gefragt wurde. Keine Einleitung, kein Smalltalk, keine Floskeln am Ende.
3. Antworte kurz und klar, ohne lange Erklärungen oder überladene Formulierungen. Erkläre verständlich, aber nicht belehrend.
4. Kurze Frage, kurze Antwort. Ausführlich nur, wenn der Nutzer es verlangt.
5. Erfinde nichts. Wenn du etwas nicht weißt oder nicht kannst, sag das kurz und einfach.
6. Frag nur nach, wenn es wirklich nötig ist, dann mit einer einzigen kurzen Frage.
7. Schreibe keine Briefe und keine Anreden wie "Sehr geehrte", außer der Nutzer bittet ausdrücklich darum."""

        /** What the app can do – so the model never claims it cannot create PDFs or see files. */
        const val APP_ABILITIES = "Die App liest Dateien und Fotos, die der Nutzer anhängt, und macht aus jedem Brief automatisch ein PDF. " +
            "Sag nie, dass du keine PDFs erstellen oder keine Dateien sehen kannst."

        /** Only added when the user asks for a letter. */
        const val LETTER_RULES = """
Der Nutzer möchte jetzt ein Schreiben (Brief, Antwort, Widerspruch o. Ä.). Schreibe es SOFORT mit den vorhandenen Angaben – keine Rückfragen.
Gib zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE--- GENAU dieses Format aus, als reinen Text ohne Markdown:
Betreff: <kurzer Betreff mit Bezug, z. B. Zeichen/Nummer des Schreibens>
<Anrede>,
<Brieftext in 2 bis 4 Absätzen: zuerst Bezug auf das erhaltene Schreiben (Datum, Zeichen), dann das Anliegen des Nutzers sachlich und vollständig ausformuliert, am Ende klar, was erwartet wird>
Mit freundlichen Grüßen
Keine Adressen, kein Datum, keinen Namen unter dem Gruß, keine Platzhalter in eckigen Klammern – Absender, Empfänger, Datum und Unterschrift setzt die App selbst ein.
Übernimm Beträge, Daten und Nummern genau so, wie sie unten stehen. Erfinde keine Namen von Ansprechpartnern.
Formuliere die Worte des Nutzers sachlich aus (z. B. aus "ich zahle das nicht" wird "Ich widerspreche der Forderung und werde den Betrag nicht zahlen.").
Nach ---ENDE--- schreibst du einen kurzen Satz dazu."""

        /** Added to letters when a sender profile is active. */
        const val SENDER_RULES = """
Absender: Übernimm den Block "Absender (aktiv)" genau so, nichts weglassen, nichts erfinden, nichts ändern.
In förmlichen Briefen (Anrede "Sehr geehrte …") steht nach der Datumszeile und vor dem Betreff die Zeile "Unser Zeichen: " mit den Initialen. In lockeren Briefen (Anrede "Hallo", "Liebe …") gibt es diese Zeile nicht.
Unter die Grußformel schreibst du nur den Namen; die Unterschrift setzt die App selbst ein."""

        /** Always added (also with a custom prompt): documents go between fixed markers, so PDF export gets only the document. */
        const val DOCUMENT_MARKER_RULE = """
Wenn du ein Dokument erstellst (Brief, Schreiben, Widerspruch, Zusammenfassung, Liste o. Ä.), steht NUR das Dokument zwischen einer Zeile ---DOKUMENT--- und einer Zeile ---ENDE---. Kommentare, Erklärungen und Rückfragen nur außerhalb."""

        /** Follow-up to an answer that contained a document ("mach es kürzer"). */
        const val DOCUMENT_FOLLOWUP_RULE = """
Deine letzte Antwort enthielt ein Dokument. Wenn der Nutzer es ändern möchte, gib das vollständige geänderte Dokument wieder zwischen ---DOKUMENT--- und ---ENDE--- aus."""

        /** When the user just sent a document/photo and did not ask for a letter. */
        const val DOC_RULES = """
Der Nutzer hat gerade ein Dokument oder Foto geschickt.
Erkläre ihm in deinen eigenen, freundlichen Worten (3 bis 6 Sätze), worum es geht, was von ihm verlangt wird und welche Fristen gelten – so, als würdest du es einem Freund erklären.
Sag ihm, was du an seiner Stelle tun würdest, und biete an, eine Antwort oder einen Widerspruch zu schreiben.
Keine Briefform, keine Anrede wie "Sehr geehrte"."""
    }
}
