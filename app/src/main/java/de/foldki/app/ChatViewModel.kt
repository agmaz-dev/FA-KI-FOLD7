package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.GemmaGitHubAgent
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalEngine
import de.foldki.app.ai.ServerEngine
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.NanoEngine
import de.foldki.app.ai.ThinkParser
import de.foldki.app.ai.WebLookup
import de.foldki.app.data.Attachment
import de.foldki.app.data.Attachments
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.ChatStore
import de.foldki.app.data.Conversation
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.data.Settings
import de.foldki.app.export.DocumentParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import de.foldki.app.ai.McpClient
import de.foldki.app.ai.SessionFn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
    val generating: Boolean = false,
    val nanoStatus: String = "Gemini Nano nicht geprüft",
    val nanoReady: Boolean = false,
    val nanoDownloadable: Boolean = false,
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val notice: String? = null,
    val webSearch: Boolean = false,
    /** Model that answers: [Settings.NANO], a .litertlm file name or "" (none chosen). Nothing answers in its place. */
    val activeModel: String = "",
    val localModels: List<LocalModelManager.LocalModel> = emptyList(),
    val modelOp: LocalModelManager.State = LocalModelManager.State.Idle,
    /** Switch "Briefe": letter/PDF rules and today's date go to the model. */
    val letters: Boolean = false,
    /** The model is writing its thoughts right now ("Denkt nach…"). */
    val thinking: Boolean = false,
    /** CPU load of the app while answering (%), -1 = not answering. */
    val liveCpu: Int = -1,
    /** "GPU"/"CPU" of the loaded local model, "" = not loaded. */
    val backend: String = "",
    /** A GitHub change waiting for the user's "Freigeben" / "Ablehnen". */
    val approval: Approval? = null,
    val githubSet: Boolean = false,
    /** Inkognito for the current / next chat: not written to the phone's storage. */
    val incognito: Boolean = false,
    val instructions: List<de.foldki.app.data.Instruction> = emptyList(),
    val saved: List<de.foldki.app.data.SavedAnswer> = emptyList(),
    val creativity: de.foldki.app.data.Creativity = de.foldki.app.data.Creativity.STANDARD,
    val answerLength: de.foldki.app.data.AnswerLength = de.foldki.app.data.AnswerLength.FREE,
    val showThoughts: Boolean = true,
    val showStats: Boolean = true,
    /** Instruction id for a new chat. */
    val newInstruction: String = "",
    val pdfEnabled: Boolean = true,
    val autoSpeak: Boolean = false,
    /** PC-Server (LM Studio / Ollama). */
    val servers: List<de.foldki.app.ai.PcServer> = emptyList(),
) {
    val activeServer: de.foldki.app.ai.PcServer? get() =
        if (activeModel.startsWith(de.foldki.app.ai.PcServer.PREFIX)) servers.firstOrNull { it.id == activeModel.removePrefix(de.foldki.app.ai.PcServer.PREFIX) } else null
    val usesNano: Boolean get() = activeModel == Settings.NANO
    val activeLocal: LocalModelManager.LocalModel? get() = localModels.firstOrNull { it.file == activeModel }
    val activeName: String get() = when {
        usesNano -> "Gemini Nano"
        activeServer != null -> activeServer!!.model.ifBlank { activeServer!!.name }
        activeLocal != null -> activeLocal!!.name
        else -> "Kein Modell gewählt"
    }
    val modelReady: Boolean get() = if (usesNano) nanoReady else if (activeServer != null) activeServer!!.model.isNotBlank() else activeLocal != null
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
    /** Instruction of the shown chat (or of the next new chat). */
    val chatInstruction: String get() = current?.instruction ?: newInstruction
    val chatInstructionName: String get() = instructions.firstOrNull { it.id == chatInstruction }?.name ?: "Standard"
}

data class Approval(val title: String, val details: String)

/** Clearly a GitHub task (without a token the app explains how to connect). */
private val GITHUB_STRONG = Regex("github|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bapk\\b", RegexOption.IGNORE_CASE)

/** GitHub tasks (go to the local model with GitHub tools when a token is set). */
private val GITHUB_WORDS = Regex(
    "git ?hub|gitup|git-hub|\\bre+p+o+s?\\b|\\bre+posts?\\b|\\bripos?\\b|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bbranch|workflow|\\bapk\\b|programmier|quellcode|\\bcode\\b|" +
        "\\bbuild\\b|bauen? (die|eine|mir eine) app|app bauen|kotlin|gradle",
    RegexOption.IGNORE_CASE,
)

/** Requests for a document other than a letter (list, summary, PDF …). */
private val DOC_WORDS = Regex("pdf|dokument|liste|zusammenfassung|fass .* zusammen|protokoll|vertrag|lebenslauf|aufstellung|tabelle|notiz", RegexOption.IGNORE_CASE)

/** Words that mean "please write me a letter" (only used with the switch "Briefe"). */
private val LETTER_WORDS = Regex(
    "brief|antwortschreiben|gegenschreiben|widerspruch|einspruch|kündigung|anschreiben|bewerbung|mahnung|beschwerde|" +
        "ein schreiben|das schreiben|formelles schreiben|schreib (ihm|ihr|ihnen|denen|zurück)|antworte (ihm|ihr|ihnen|denen|darauf)|" +
        "formulier|gegengeschreib|gegen-schreiben|antwortbrief|stellungnahme|fertig ?machen|schreiben fertig|fertigstellen|" +
        "schreib (eine|einen|das|die) (antwort|widerspruch|stellungnahme)",
    RegexOption.IGNORE_CASE,
)

/** With a letter in the chat, these mean "write the reply" (rejecting, objecting, not paying …). */
private val REPLY_INTENT = Regex("(nicht (be)?zahl|nicht begleich|lehne .* ab|ablehn|widersprech|antwort|zurückschreib|schreib)", RegexOption.IGNORE_CASE)
private val JUST_READ = Regex("(was steht|zusammenfass|fass .* zusammen|erklär|worum geht|was bedeutet|was heißt)", RegexOption.IGNORE_CASE)

/** Short requests like "als PDF bitte": the app makes the PDF itself from the last answer (the model cannot). */
private val PDF_WORD = Regex("\\bpdf\\b", RegexOption.IGNORE_CASE)
/** "als PDF erstellen", "erstelle es als PDF", "mach eine PDF". */
private val PDF_MAKE = Regex("(als|eine|die|ein) pdf|pdf (erstell|mach|generier)|(erstell|mach|generier)\\w* [^.?!]{0,30}pdf", RegexOption.IGNORE_CASE)
/** Short follow-ups that ask for a file of the last letter/document ("so erstelle", "mach daraus eine Datei", "zum Drucken"). */
private val PDF_FOLLOW = Regex(
    "^(so|ok|ja|gut)?,? ?(erstell|mach|generier|exportier|speicher|druck)\\w*( (es|das|ihn|sie|daraus|davon|mir|bitte|jetzt|fertig|eine datei|ein dokument|als datei))*[ .!]*$|" +
        "als (datei|dokument|ausdruck)|zum (drucken|ausdrucken|speichern)|ausdrucken|druckfertig",
    RegexOption.IGNORE_CASE,
)
/** The model claims it made a PDF/file (it cannot – only the app can). */
private val PDF_CLAIM = Regex("(pdf|datei|dokument)[^.\\n]{0,50}(erstellt|generiert|angehängt|gespeichert|exportiert|bereit|fertig)|(erstellt|generiert|exportiert)[^.\\n]{0,30}\\bpdf\\b", RegexOption.IGNORE_CASE)
/** Greetings and thanks: never searched in the internet. */
private val SMALL_TALK = Regex("^(hallo|hi|hey|moin|servus|danke|vielen dank|guten (morgen|tag|abend)|gute nacht|tschüss|ok|okay|super|cool|alles klar)\\b", RegexOption.IGNORE_CASE)
/** Follow-ups that need the earlier question for a web search ("ist an", "guck mal dort", "und der Preis?"). */
private val REFERS_BACK = Regex("\\b(dort|da|das|dazu|davon|darüber|ist an|nochmal|weiter|und der|und die|und das|wie viel|was kostet)\\b", RegexOption.IGNORE_CASE)
/** Ab 4.9.1: questions that clearly need current facts from the internet. */
private val WEB_STRONG = Regex("aktuell|heute|gestern|morgen|neueste|neuigkeit|news|nachrichten|preis|kostet|kosten|wetter|öffnungszeit|geöffnet|\\bkurs\\b|ergebnis|spielstand|tabelle|wer ist|wer war|wer hat|wann ist|wann war|wann kommt|wo ist|wo liegt|wo gibt|adresse|telefonnummer|fahrplan|abfahrt|verkehr|stau|\\b20[2-3]\\d\\b|kaufen|angebot|shop|test(bericht|sieger)?\\b|vergleich|bewertung", RegexOption.IGNORE_CASE)
/** An explicit search command ("such mal …", "google …", "schau im Internet nach …"). */
private val SEARCH_CMD = Regex("^(such|google|googel|recherchier)|\\b(such|schau|guck)\\w* (mal )?(im internet|online|im netz|nach)\\b|im internet (nach|suchen|recherchieren)", RegexOption.IGNORE_CASE)
/** Questions about the AI itself or the chat ("Was ist mit dir los?", "Warum hast du …") – never searched. */
private val ABOUT_AI = Regex("\\b(du|dir|dich|dein\\w*)\\b|\\b(deine|die|eure) antwort|was hast du|vorhin|eben gesagt|oben geschrieben", RegexOption.IGNORE_CASE)
/** Writing, translating and calculating tasks: the model does them itself, no search. */
private val OWN_TASK = Regex("^(bitte )?(schreib|formulier|dicht|erfind|übersetz|rechne|korrigier|verbesser|kürz|umformulier)", RegexOption.IGNORE_CASE)
/** Text in front of web results: the model uses them only if they fit and does not talk about the search. */
private const val WEB_INTRO = "Hintergrund aus dem Internet (nur verwenden, wenn es zur Frage passt; nicht erwähnen, dass gesucht wurde):\n"
/** Filler words removed from a web query. */
private val FILLER = Regex("\\b(bitte|kannst du|könntest du|kann man|mal|mir|uns|such(e|en)?|im internet|online|im netz|google|googel|schau|guck|nach|nachsehen|nachschauen|für mich|ich möchte|ich will|wissen)\\b", RegexOption.IGNORE_CASE)
/** Placeholders in a letter for the user's own data. */
private val NAME_PH = Regex("\\[\\s*(ihr|dein|mein|ihre|deine)?\\s*(vor- und nach|voll|vollständiger )?name\\s*\\]", RegexOption.IGNORE_CASE)
private val STREET_PH = Regex("\\[\\s*(ihre|deine|meine)?\\s*(adresse|anschrift|straße( und hausnummer)?|strasse( und hausnummer)?)\\s*\\]", RegexOption.IGNORE_CASE)
private val ZIP_PH = Regex("\\[\\s*(plz( und ort| ort|, ort)?|postleitzahl( und ort)?)\\s*\\]", RegexOption.IGNORE_CASE)
private val CITY_PH = Regex("\\[\\s*(ort|stadt|wohnort)\\s*\\]", RegexOption.IGNORE_CASE)
private val PHONE_PH = Regex("\\[\\s*(ihre |deine )?(telefon(nummer)?|tel\\.?)\\s*\\]", RegexOption.IGNORE_CASE)
private val MAIL_PH = Regex("\\[\\s*(ihre |deine )?(e-?mail(-adresse)?)\\s*\\]", RegexOption.IGNORE_CASE)
private val INFO_QUESTION = Regex("^(was|wie|warum|wieso|weshalb|welche[rs]?|wo|wann)\\b", RegexOption.IGNORE_CASE)
/** Placeholder the model sometimes leaves instead of a date. */
private val DATE_PLACEHOLDER = Regex("\\[\\s*(aktuelles |heutiges )?datum[^\\]\\n]{0,50}\\]", RegexOption.IGNORE_CASE)
/** Start of the app's own answer to a PDF request (never itself turned into a PDF). */
private const val PDF_REPLY = "📄 PDF-Vorschau geöffnet"

const val READY = "Sprachfunktion ist eingerichtet."

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    private val nano = NanoEngine()
    val models = LocalModelManager(app)
    private val llm = LocalEngine(app)
    /** GGUF models (llama.cpp); only one of the two engines holds a model at a time. */
    private val gguf = de.foldki.app.ai.GgufEngine(app)

    private fun freeModels() { llm.close(); gguf.close() }
    private fun loadedPathAny(): String? = llm.loadedPath ?: gguf.loadedPath

    // ---- PC-Server ----
    fun saveServer(s: de.foldki.app.ai.PcServer) {
        val list = settings.servers.let { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }
        settings.servers = list
        _ui.update { it.copy(servers = list) }
    }

    fun deleteServer(id: String) {
        val list = settings.servers.filterNot { it.id == id }
        settings.servers = list
        _ui.update { it.copy(servers = list) }
        if (settings.activeModel == de.foldki.app.ai.PcServer.PREFIX + id) { settings.activeModel = ""; _ui.update { it.copy(activeModel = "") } }
    }

    fun setGpuPercent(p: Int) {
        if (llmGenerating) stop()
        settings.gpuPercent = p
        gguf.close(); _ui.update { it.copy(backend = "") }
        de.foldki.app.data.DebugLog.write("GGUF GPU-Anteil: $p %")
    }
    private val llmBusy = AtomicInteger(0)
    @Volatile private var llmGenerating = false
    private var idleJob: Job? = null
    val attachments = Attachments(app)
    val voice = de.foldki.app.voice.VoiceController(app).apply {
        rate = settings.ttsRate; supertonicVoice = settings.stVoice; phononVoice = settings.phononVoice; useLive = settings.liveDictation
    }

    // One-time setup of the voice function: Tessa & Caleb (Supertonic) + speech recognition (Whisper).
    private val _stStatus = MutableStateFlow(if (voice.isReady) READY else "Noch nicht eingerichtet.")
    val stStatus: StateFlow<String> = _stStatus.asStateFlow()
    private val _stBusy = MutableStateFlow(false)
    val stBusy: StateFlow<Boolean> = _stBusy.asStateFlow()
    private var stJob: Job? = null

    fun setupVoice() {
        if (_stBusy.value) return
        _stBusy.value = true
        stJob = viewModelScope.launch {
            try {
                voice.supertonic.prepare { line -> _stStatus.value = line }
                voice.whisper.prepare { line -> _stStatus.value = line }
                _stStatus.value = READY
            } catch (e: kotlinx.coroutines.CancellationException) {
                _stStatus.value = "Abgebrochen."
            } catch (e: Throwable) {
                _stStatus.value = "Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _stBusy.value = false
            }
        }
    }

    fun cancelVoiceSetup() { stJob?.cancel() }

    /** Ab 4.9: Phonon (Anton) once from Hugging Face – the model is gated, so the user's Hugging Face token is needed. */
    private val _phStatus = MutableStateFlow(if (voice.phonon.isReady) "Bereit" else if (!de.foldki.app.voice.Phonon.libAvailable) "fehlt in dieser APK" else "Noch nicht eingerichtet")
    val phStatus: StateFlow<String> = _phStatus.asStateFlow()
    private val _phBusy = MutableStateFlow(false)
    val phBusy: StateFlow<Boolean> = _phBusy.asStateFlow()
    private var phJob: Job? = null

    fun setupPhonon(token: String) {
        if (_phBusy.value) return
        settings.hfToken = token
        _phBusy.value = true
        phJob = viewModelScope.launch {
            try {
                voice.phonon.prepare(token.trim()) { line -> _phStatus.value = line }
                _phStatus.value = "Bereit"
                voice.previewPhonon(settings.phononVoice) { notify("Phonon: $it") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _phStatus.value = "Abgebrochen"
            } catch (e: Throwable) {
                _phStatus.value = e.message ?: e.javaClass.simpleName
            } finally { _phBusy.value = false }
        }
    }

    fun cancelPhonon() { phJob?.cancel() }

    fun removePhonon() { voice.phonon.remove(); _phStatus.value = "Noch nicht eingerichtet" }

    fun setPhononVoice(key: String) { settings.phononVoice = key; voice.phononVoice = key }

    fun setLiveDictation(on: Boolean) { settings.liveDictation = on; voice.useLive = on; if (on) voice.recheckLive() }

    /** Live dictation: lets Android download the German recognition model (once). */
    fun downloadLive() {
        viewModelScope.launch {
            val ok = voice.live.download { line -> notify(line) }
            voice.recheckLive()
            notify(if (ok) "Live-Diktat ist bereit" else "Live-Diktat nicht möglich – Whisper wird benutzt")
        }
    }

    /** Whisper only (fallback for dictation where live dictation does not work). */
    fun setupWhisper() {
        if (_stBusy.value) return
        _stBusy.value = true
        stJob = viewModelScope.launch {
            try { voice.whisper.prepare { line -> _stStatus.value = line }; _stStatus.value = READY }
            catch (e: kotlinx.coroutines.CancellationException) { _stStatus.value = "Abgebrochen." }
            catch (e: Throwable) { _stStatus.value = "Fehler: ${e.message ?: e.javaClass.simpleName}" }
            finally { _stBusy.value = false }
        }
    }

    fun saveVoice(stVoice: String, rate: Float) {
        settings.stVoice = stVoice; settings.ttsRate = rate
        voice.supertonicVoice = stVoice; voice.rate = rate
    }

    override fun onCleared() { voice.release(); freeModels() }

    private var job: Job? = null

    private val savedStore = de.foldki.app.data.SavedStore(app)

    private val _ui = MutableStateFlow(
        UiState(
            instructions = settings.instructions,
            saved = savedStore.load(),
            creativity = settings.creativity,
            answerLength = settings.answerLength,
            showThoughts = settings.showThoughts,
            showStats = settings.showStats,
            newInstruction = settings.defaultInstruction,
            servers = settings.servers,
            pdfEnabled = settings.pdfEnabled,
            autoSpeak = settings.autoSpeak,
            conversations = store.load(),
            githubSet = settings.githubToken.isNotBlank(),
            webSearch = settings.webSearch,
            activeModel = settings.activeModel,
            letters = settings.letters,
            notice = llmCrashCheck(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        settings.gemmaLoading = false // old crash flag of 3.x, replaced by llmLoading
        if (settings.activeModel == Settings.NANO) checkNano() // Nano is only asked when it is the chosen model
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { de.foldki.app.export.PdfExporter.cleanup(app) } }
        models.beforeDelete = { f -> if (loadedPathAny() == f.absolutePath) { if (llmGenerating) stop(); freeModels() } }
        models.onAdded = { file -> selectModel(file); notify("${models.nameOf(file)} ist bereit und ausgewählt") }
        viewModelScope.launch { models.state.collect { st -> _ui.update { it.copy(modelOp = st) } } }
        viewModelScope.launch {
            models.models.collect { list ->
                _ui.update { it.copy(localModels = list) }
                val act = settings.activeModel
                // The chosen model was deleted: no model is chosen (nothing else answers in its place).
                if (act.isNotBlank() && act != Settings.NANO && !act.startsWith(de.foldki.app.ai.PcServer.PREFIX) && list.none { it.file == act }) {
                    settings.activeModel = ""; _ui.update { it.copy(activeModel = "") }
                }
            }
        }
        // Too hot: free the model's memory (it loads again with the next question).
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            power.addThermalStatusListener(app.mainExecutor) { status ->
                if (status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && !llmGenerating) { freeModels(); _ui.update { it.copy(backend = "") } }
            }
        }
    }

    private val power get() = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager

    /** Crash guard: a model was still loading/answering when the app died last time -> it is locked until chosen again. */
    private fun llmCrashCheck(): String? {
        if (!settings.llmLoading) return null
        settings.llmLoading = false
        val f = settings.llmAttempt
        if (f.isBlank()) return null
        // GGUF crashed while the NPU was on: NPU off, the model stays usable (GPU share / CPU).
        if (HfModels.isGguf(f) && settings.npu && settings.gemmaBackend != "CPU") {
            settings.npu = false
            de.foldki.app.data.DebugLog.write("Absturz mit NPU (${HfModels.displayName(f)}) – NPU ausgeschaltet")
            return "${HfModels.displayName(f)} hat die App auf der NPU beendet – die NPU ist jetzt aus (GPU-Anteil/Prozessor). Unter Modelle → NPU wieder einschalten."
        }
        // GGUF crashed with GPU share: like FA Fold KI the GPU share goes to 0 % and the model stays usable (CPU).
        if (HfModels.isGguf(f) && settings.gpuPercent > 0 && settings.gemmaBackend != "CPU") {
            settings.gpuPercent = 0
            return "${HfModels.displayName(f)} hat die App mit GPU beendet – GPU-Anteil steht jetzt auf 0 % (nur Prozessor). Unter Modelle wieder erhöhen."
        }
        settings.llmCrashed = f
        return "${HfModels.displayName(f)} hat die App beendet – gesperrt, bis du es unter Einstellungen → KI-Modelle wieder auswählst"
    }

    private fun thermal(): Int = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { power.currentThermalStatus }.getOrDefault(0) else 0
    /** Ab 4.9: a pause only from "critical" on – "severe" right after an answer is normal and only logged. */
    private fun tooHot(): Boolean = thermal() >= android.os.PowerManager.THERMAL_STATUS_CRITICAL

    /**
     * Phone hot ("critical"): wait up to 90 s for it to cool down instead of refusing at once. Only "emergency" or worse
     * still stops the answer. "Severe" is only written to the error report.
     */
    private suspend fun coolDown(onHot: (String) -> Unit) {
        val t = thermal()
        if (t == android.os.PowerManager.THERMAL_STATUS_SEVERE) de.foldki.app.data.DebugLog.write("Handy warm (Status $t) – keine Pause")
        if (!tooHot()) return
        de.foldki.app.data.DebugLog.write("Handy heiß (Status $t) – warte bis zu 90 s")
        onHot("Das Handy ist heiß – kurz abkühlen lassen …")
        var waited = 0
        while (tooHot() && waited < 90) { delay(3000); waited += 3 }
        if (thermal() >= android.os.PowerManager.THERMAL_STATUS_EMERGENCY)
            throw IllegalStateException("Das Handy ist zu heiß. Bitte ein paar Minuten abkühlen lassen und nochmal fragen.")
        de.foldki.app.data.DebugLog.write("Weiter nach $waited s (Status ${thermal()})")
        onHot("")
    }

    // ---- Models ----
    /** Chooses the model that answers ([Settings.NANO] or a file name). */
    fun selectModel(id: String) {
        if (llmGenerating) stop()
        if (settings.llmCrashed == id) settings.llmCrashed = "" // the user picked it again on purpose
        settings.activeModel = id
        _ui.update { it.copy(activeModel = id) }
        if (id == Settings.NANO) { checkNano(); if (loadedPathAny() != null) { freeModels(); _ui.update { it.copy(backend = "") } } } // free the memory for Nano
        else if (loadedPathAny() != null && loadedPathAny() != models.fileOf(id).absolutePath) { freeModels(); _ui.update { it.copy(backend = "") } }
    }

    fun downloadModel(f: HfModels.LmFile) { models.download(f)?.let { notify(it) } }
    fun importModel(uri: android.net.Uri) = models.import(uri)
    fun cancelModelOp() { if (models.state.value is LocalModelManager.State.Importing) models.cancelImport() else models.cancel() }
    fun clearModelError() = models.clearError()
    fun deleteModel(file: String) {
        if (llmGenerating && loadedPathAny() == models.fileOf(file).absolutePath) stop()
        models.delete(file)
    }

    /** Test switch: local models on the CPU instead of the GPU. */
    fun setCpu(cpu: Boolean) {
        if (llmGenerating) stop()
        settings.gemmaBackend = if (cpu) "CPU" else "GPU"
        freeModels() // next answer loads with the chosen backend
        _ui.update { it.copy(backend = "") }
        de.foldki.app.data.DebugLog.write("Rechenart umgestellt auf ${settings.gemmaBackend}")
    }

    fun setLetters(on: Boolean) { settings.letters = on; _ui.update { it.copy(letters = on) } }

    /** Ab 4.9: settings of one model (null = recommended values). The model loads again with the next question. */
    fun saveModelParams(file: String, p: de.foldki.app.data.ModelParams?) {
        settings.setModelParams(file, p)
        if (loadedPathAny() == models.fileOf(file).absolutePath) { if (llmGenerating) stop(); freeModels(); _ui.update { it.copy(backend = "") } }
        notify("Einstellungen für ${models.nameOf(file)} gespeichert")
    }

    /** NPU power (Sparsam / Ausgewogen / Maximal); applies from the next app start (llama.cpp reads it once). */
    fun setNpuPower(mode: String) { settings.npuPower = mode; notify("NPU-Leistung gilt ab dem nächsten Start der App") }

    // ---- Long-term memory (on-device only; not sent to the model while all built-in instructions are off) ----
    val memory = de.foldki.app.data.MemoryStore(app)
    private val _memories = MutableStateFlow(memory.all())
    val memories: StateFlow<List<String>> = _memories.asStateFlow()
    fun addMemory(fact: String) { memory.add(fact); _memories.value = memory.all() }
    fun removeMemory(index: Int) { memory.remove(index); _memories.value = memory.all() }

    /** App in the background or memory is low: free the model's memory (it loads again on the next question). */
    private var bgJob: Job? = null

    fun onBackground() {
        // Ab 4.9: the model stays loaded for 2 minutes in the background (e.g. to copy something and come back),
        // then its memory is freed. Low memory frees it at once (onLowMemory).
        bgJob?.cancel()
        bgJob = viewModelScope.launch { delay(120_000); if (!llmGenerating) { freeModels(); _ui.update { it.copy(backend = "") } } }
        // "Automatisch sichern": when the app goes to the background, at most every 30 minutes.
        if (settings.s3Auto && System.currentTimeMillis() - settings.s3Last > 30 * 60_000L) s3BackupNow(quiet = true)
    }

    /** Android is short of memory (app in the background): also free the speech recognition model. */
    fun onLowMemory() { voice.trimMemory(); if (!llmGenerating) { freeModels(); _ui.update { it.copy(backend = "") } } }

    private fun llmBegin() { if (llmBusy.incrementAndGet() == 1) { settings.llmAttempt = settings.activeModel; settings.llmLoading = true } }
    private fun llmEnd() { if (llmBusy.decrementAndGet() <= 0) { llmBusy.set(0); settings.llmLoading = false } }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch { delay(5 * 60_000L); if (!llmGenerating) { freeModels(); _ui.update { it.copy(backend = "") } } }
    }

    private fun freeRamMb(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_000_000
    }.getOrDefault(-1L)

    private fun noModelText(): String =
        "Es ist kein Modell gewählt. Bitte unter Einstellungen → KI-Modelle ein Modell herunterladen oder auswählen."

    /** Loads the chosen local model if needed (max. 10 min; the first GPU start compiles and can take long). null = ready, else the reason (German). */
    private suspend fun ensureLoaded(): String? {
        val file = settings.activeModel
        if (file.isBlank() || file == Settings.NANO) return noModelText()
        if (!models.exists(file)) return "Das gewählte Modell ist nicht mehr auf dem Handy. " + noModelText()
        val f = models.fileOf(file)
        val isGguf = HfModels.isGguf(file)
        if (!isGguf && llm.loadedPath == f.absolutePath) return null
        // GGUF: "Auf CPU rechnen" = 0 % on the GPU; the same share as loaded = nothing to do.
        val ggufPct = if (settings.gemmaBackend == "CPU") 0 else settings.gpuPercent
        if (isGguf && gguf.loadedPath == f.absolutePath && gguf.loadedGpu == ggufPct) return null
        if (settings.llmCrashed == file) return "${models.nameOf(file)} hat die App beim letzten Mal beendet. " +
            "Wähle es unter Einstellungen → KI-Modelle erneut aus, um es nochmal zu versuchen." +
            (if (!isGguf && settings.gemmaBackend == "GPU" && !HfModels.isGpuFile(file) && !file.lowercase().startsWith("gemma")) " Tipp: Diese Datei ist nicht für den Grafikprozessor gebaut. " +
                "Lade unter Erkunden die Variante mit „gpu“ im Dateinamen." else "")
        voice.trimMemory() // speech model out of memory before a big load
        // Ab 4.9: warn before a load that leaves too little memory (Android then ends the app).
        val needMb = f.length() / 1_000_000 * (if (isGguf) 11 else 12) / 10
        val freeMb = freeRamMb()
        if (freeMb in 1 until needMb / 2) {
            de.foldki.app.data.DebugLog.write("Speicher knapp vor dem Laden: frei $freeMb MB, Modell braucht ca. $needMb MB")
            notify("Speicher knapp: frei ${"%.1f".format(java.util.Locale.GERMANY, freeMb / 1000.0)} GB, das Modell braucht ca. " +
                "${"%.1f".format(java.util.Locale.GERMANY, needMb / 1000.0)} GB. Andere Apps schließen, sonst beendet Android die App.")
        }
        // NPU power (Hexagon): read by llama.cpp when the NPU part starts (once per app start).
        if (isGguf) runCatching { android.system.Os.setenv("GGML_HEXAGON_POWER_MODE", settings.npuPower, true) }
        de.foldki.app.data.DebugLog.write("LADE $file · " + (if (isGguf) "GGUF · GPU-Anteil ${settings.gpuPercent} %" else
            "${settings.gemmaBackend} · ${if (HfModels.isGpuFile(file)) "GPU-Datei" else "keine GPU-Datei (GPU ohne Cache)"}") + " · " +
            "${LocalModelManager.gb(f.length())} · frei RAM ${freeRamMb()} MB")
        val t0 = System.currentTimeMillis()
        // Only one engine holds a model: free the other one first.
        if (isGguf) llm.close() else gguf.close()
        val d = if (isGguf) gguf.loadAsync(f.absolutePath, ggufPct)
            else llm.loadAsync(f.absolutePath, settings.gemmaBackend == "GPU", context = settings.modelParams(file).context)
        llmBegin(); d.invokeOnCompletion { llmEnd() } // stays guarded until the native load really ends
        return try {
            val b = withTimeoutOrNull(600_000) { d.await() } ?: return "Das Modell lädt zu lange (über 10 Minuten). Bitte nochmal fragen."
            de.foldki.app.data.DebugLog.write("Modell geladen: $file · $b · ${(System.currentTimeMillis() - t0) / 1000} s · frei RAM danach ${freeRamMb()} MB")
            _ui.update { it.copy(backend = b) }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            de.foldki.app.data.DebugLog.write("Ladefehler: ${e.stackTraceToString().take(1500)}")
            e.message ?: "Das Modell konnte nicht geladen werden."
        }
    }

    /** Runs [block] with the chosen local model loaded and the crash guard set. Errors are thrown (no other model answers). */
    private suspend fun <T> withLocal(onHot: (String) -> Unit = {}, block: suspend () -> T): T {
        coolDown(onHot)
        idleJob?.cancel()
        llmBegin()
        llmGenerating = true
        try {
            ensureLoaded()?.let { throw IllegalStateException(it) }
            return block()
        } finally {
            llmGenerating = false
            llmEnd()
            scheduleIdleUnload()
        }
    }

    /** Short internal task (search terms, letter reading, briefing) with the chosen model; only the answer part. */
    private suspend fun askModel(system: String, user: String, precise: Boolean = true): String = when {
        settings.activeModel == Settings.NANO -> nano.ask(if (system.isBlank()) user else system + "\n\n" + user).trim()
        else -> withLocal {
            ThinkParser.answerOnly(llm.complete(system, user, precise), HfModels.isThinking(settings.activeModel)).trim()
        }
    }

    fun checkNano() = viewModelScope.launch {
        val (text, ready, downloadable) = try {
            when (nano.state()) {
                NanoEngine.State.AVAILABLE -> Triple("Gemini Nano bereit (offline)", true, false)
                NanoEngine.State.DOWNLOADABLE -> Triple("Gemini Nano kann heruntergeladen werden", false, true)
                NanoEngine.State.DOWNLOADING -> Triple("Gemini Nano wird geladen…", false, false)
                NanoEngine.State.UNAVAILABLE -> Triple("Gemini Nano ist auf diesem Gerät nicht verfügbar", false, false)
            }
        } catch (e: Throwable) {
            Triple("Gemini Nano nicht verfügbar: ${e.message ?: e.javaClass.simpleName}", false, false)
        }
        _ui.update { it.copy(nanoStatus = text, nanoReady = ready, nanoDownloadable = downloadable) }
    }

    fun downloadNano() = viewModelScope.launch {
        _ui.update { it.copy(nanoDownloadable = false, nanoStatus = "Download gestartet…") }
        try {
            nano.download().collect { line -> _ui.update { it.copy(nanoStatus = line) } }
        } catch (e: Throwable) {
            _ui.update { it.copy(nanoStatus = e.message ?: "Download fehlgeschlagen") }
        }
        checkNano()
    }

    // ---- Tagesstart (daily briefing), only on the device ----
    private val openItems = de.foldki.app.brief.OpenItemsStore(app)
    private val _brief = MutableStateFlow<de.foldki.app.brief.Briefing?>(null)
    val brief: StateFlow<de.foldki.app.brief.Briefing?> = _brief.asStateFlow()
    private var briefJob: Job? = null
    /** Nano refused because the app was not in the foreground yet: try the summary again on the next open. */
    private var briefRetryAi = false

    /** Called when the app comes to the foreground (from the launcher or another app). */
    fun onAppResumed() {
        bgJob?.cancel(); bgJob = null
        voice.prewarm() // speech input: model ready before the first tap
        if (!settings.dailyBriefEnabled || briefJob?.isActive == true) return
        val current = _brief.value
        if (current != null) { if (briefRetryAi) summarizeBrief(current); return }
        if (!de.foldki.app.brief.DailyBriefing.inWindow()) return
        if (settings.lastBriefDate == de.foldki.app.brief.DailyBriefing.today()) return
        startBrief()
    }

    /** "Heute erneut zeigen" in the settings: shows the briefing right away. */
    fun showBriefAgain() {
        if (!settings.dailyBriefEnabled) return
        briefJob?.cancel(); _brief.value = null
        newChat() // the card is shown on the empty chat page
        startBrief()
    }

    private fun startBrief() {
        val app = getApplication<Application>()
        briefJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val events = if (settings.briefCalendar) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                de.foldki.app.brief.CalendarSource.read(app, now)
            } else emptyList()
            val notifAccess = de.foldki.app.brief.NotificationCollector.isEnabled(app)
            val notifs = if (settings.briefNotifications) de.foldki.app.brief.NotificationCollector.current(app) else emptyList()
            val items = if (settings.briefChats) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val msgs = _ui.value.conversations.flatMap { c ->
                    c.messages.filter { it.role == Role.USER && !it.localOnly }.map { de.foldki.app.brief.TaskExtractor.Message(it.text, c.updatedAt) }
                }
                openItems.merge(de.foldki.app.brief.TaskExtractor.extract(msgs, now), now)
            } else emptyList()
            val b = de.foldki.app.brief.Briefing(
                date = de.foldki.app.brief.DailyBriefing.dateLabel(now),
                events = events, notifications = notifs, openItems = items,
                loading = true, notificationsAllowed = !settings.briefNotifications || notifAccess,
            )
            settings.lastBriefDate = de.foldki.app.brief.DailyBriefing.today(now)
            _brief.value = b
            summarize(b)
        }
    }

    private fun summarizeBrief(b: de.foldki.app.brief.Briefing) {
        briefJob = viewModelScope.launch { summarize(b.copy(loading = true).also { _brief.value = it }) }
    }

    /** No built-in prompt (from 3.3): the briefing is the plain list, no model is asked. */
    private fun summarize(b: de.foldki.app.brief.Briefing) {
        briefRetryAi = false
        _brief.update { cur -> (cur ?: b).copy(summary = null, loading = false) }
    }

    fun closeBrief() { briefJob?.cancel(); briefRetryAi = false; _brief.value = null }

    fun toggleOpenItem(id: String, done: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { openItems.setDone(id, done) }
        _brief.update { b -> b?.copy(openItems = b.openItems.map { if (it.id == id) it.copy(done = done) else it }) }
    }

    /** "Als Chat öffnen": the briefing is marked local (stays on the phone). */
    fun openBriefAsChat() {
        val b = _brief.value ?: return
        stop()
        val conv = Conversation(
            title = "Tagesstart " + b.date,
            messages = listOf(ChatMessage(
                role = Role.ASSISTANT, text = de.foldki.app.brief.DailyBriefing.asText(b),
                engine = if (_ui.value.usesNano) Engine.NANO else Engine.GEMMA, model = _ui.value.activeName, localOnly = true,
            )),
        )
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id) }
        closeBrief()
    }

    fun setDailyBrief(on: Boolean) {
        settings.dailyBriefEnabled = on
        if (!on) {
            closeBrief()
            openItems.clear()
            de.foldki.app.brief.NotificationCollector.release()
        } else if (settings.briefNotifications) de.foldki.app.brief.NotificationCollector.rebind(getApplication())
    }

    fun setBriefSources(calendar: Boolean, notifications: Boolean, chats: Boolean) {
        settings.briefCalendar = calendar
        settings.briefChats = chats
        if (!chats) openItems.clear()
        if (notifications != settings.briefNotifications) {
            settings.briefNotifications = notifications
            if (notifications && settings.dailyBriefEnabled) de.foldki.app.brief.NotificationCollector.rebind(getApplication())
            else de.foldki.app.brief.NotificationCollector.release()
        }
    }

    // ---- PDF preview (before saving or sharing) ----
    data class PdfPreviewState(
        val text: String,
        val fileName: String,
        val file: java.io.File? = null,
        val busy: Boolean = true,
        val editing: Boolean = false,
        /** Shared once: the temp file stays until the cleanup at a later start (the receiver still reads it). */
        val shared: Boolean = false,
    )
    private val _preview = MutableStateFlow<PdfPreviewState?>(null)
    val preview: StateFlow<PdfPreviewState?> = _preview.asStateFlow()
    private var previewJob: Job? = null

    /** Every PDF/share button goes through here: build the PDF (with date line + signature) and show it. */
    fun openPreview(doc: String) {
        val old = _preview.value
        if (old?.file != null && !old.shared) de.foldki.app.export.PdfExporter.discard(old.file)
        val text = pdfText(doc)
        _preview.value = PdfPreviewState(text = text, fileName = de.foldki.app.export.PdfExporter.fileName(text))
        renderPreview()
    }

    private fun renderPreview() {
        val st = _preview.value ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val file = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    de.foldki.app.export.PdfExporter.writeToCache(getApplication(), st.text, st.fileName, activeSignature())
                }
            }.getOrElse {
                notify("PDF konnte nicht erstellt werden: ${it.message ?: it.javaClass.simpleName}")
                _preview.value = null
                return@launch
            }
            _preview.update { cur -> if (cur == null) { de.foldki.app.export.PdfExporter.discard(file); null } else cur.copy(file = file, busy = false, shared = false) }
        }
    }

    fun editPreview(on: Boolean) = _preview.update { it?.copy(editing = on) }

    /** "Text ändern" -> new PDF from the edited text, shown again. */
    fun applyPreviewText(text: String) {
        val st = _preview.value ?: return
        if (st.file != null && !st.shared) de.foldki.app.export.PdfExporter.discard(st.file)
        _preview.value = st.copy(text = DocumentParser.ensureDateLine(text.trim(), today(), settings.activeSender?.city), file = null, busy = true, editing = false)
        renderPreview()
    }

    fun markPreviewShared() = _preview.update { it?.copy(shared = true) }

    /** "Verwerfen" / back gesture. */
    fun closePreview() {
        previewJob?.cancel()
        val st = _preview.value ?: return
        if (st.file != null && !st.shared) de.foldki.app.export.PdfExporter.discard(st.file)
        _preview.value = null
    }

    fun toggleWeb() {
        val on = !_ui.value.webSearch
        settings.webSearch = on
        _ui.update { it.copy(webSearch = on, notice = if (on) "Websuche an" else "Websuche aus") }
    }

    fun addAttachment(uri: android.net.Uri, deleteAfter: java.io.File? = null) = viewModelScope.launch {
        _ui.update { it.copy(importing = true) }
        try {
            val a = attachments.import(uri, ownNames())
            _ui.update { it.copy(pending = it.pending + a) }
        } catch (e: Throwable) {
            _ui.update { it.copy(notice = "Datei konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            deleteAfter?.let { runCatching { it.delete() } } // camera original, the import made its own copy
            _ui.update { it.copy(importing = false) }
        }
    }

    fun removePending(id: String) = _ui.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    fun notify(text: String?) = _ui.update { it.copy(notice = text) }

    // ---- GitHub (the local model with tools; every change needs the user's approval) ----
    val githubToken: String get() = settings.githubToken
    fun saveGitHubToken(token: String) {
        if (token.trim() != settings.githubToken) { settings.githubLogin = ""; _githubLogin.value = "" }
        settings.githubToken = token
        _ui.update { it.copy(githubSet = token.isNotBlank()) }
    }

    /** GitHub account name after "Verbinden & prüfen" ("" = not checked / not connected). */
    private val _githubLogin = MutableStateFlow(settings.githubLogin)
    val githubLogin: StateFlow<String> = _githubLogin.asStateFlow()
    private val _githubChecking = MutableStateFlow(false)
    val githubChecking: StateFlow<Boolean> = _githubChecking.asStateFlow()

    /** Saves the token and asks GitHub who it belongs to. */
    fun checkGitHub(token: String) {
        saveGitHubToken(token)
        if (token.isBlank()) { _githubLogin.value = ""; return }
        _githubChecking.value = true
        viewModelScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.json.JSONObject(de.foldki.app.github.GitHubApi(token.trim()).get("/user")).getString("login")
                }
            }
            _githubChecking.value = false
            r.onSuccess { login -> settings.githubLogin = login; _githubLogin.value = login; notify("Verbunden als $login") }
                .onFailure { e -> settings.githubLogin = ""; _githubLogin.value = ""; notify("GitHub: Token wurde nicht angenommen (${e.message?.take(80)})") }
        }
    }

    private var approvalAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** Shows the change and waits for "Freigeben" (true) or "Ablehnen" (false). Stopping the answer counts as no. */
    private suspend fun askApproval(title: String, details: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalAnswer = d
        _ui.update { it.copy(approval = Approval(title, details)) }
        return try { d.await() } finally {
            approvalAnswer = null
            _ui.update { it.copy(approval = null) }
        }
    }

    fun answerApproval(ok: Boolean) { approvalAnswer?.complete(ok) }

    /** GitHub task: the local model with the token's tools, if a token is set. */
    private fun isGitHubTask(prompt: String, history: List<ChatMessage>): Boolean {
        if (settings.githubToken.isBlank()) return false
        if (GITHUB_WORDS.containsMatchIn(prompt)) return true
        // follow-ups ("ja, mach weiter", "ist verbunden") in a chat that already worked on GitHub
        if (history.takeLast(6).any { m ->
            m.role == Role.ASSISTANT && listOf("✅ Freigegeben", "⛔ Abgelehnt", "🔎 Suche:", "📄 Lese ", "📁 ", "⚙️ Build-Status").any { m.text.contains(it) }
        }) return true
        // Ab 4.9: a short follow-up right after a GitHub question stays with GitHub (also dictated ones with recognition errors).
        val earlierUser = history.dropLast(1).filter { it.role == Role.USER }.takeLast(2)
        return prompt.length <= 80 && earlierUser.any { GITHUB_WORDS.containsMatchIn(it.text) }
    }

    fun saveSettings(apiKey: String, model: String, system: String) {
        settings.apiKey = apiKey
        settings.claudeModel = model
        settings.systemPrompt = system
    }

    fun newChat() { stop(); _ui.update { it.copy(currentId = null, incognito = false) } }

    // ---- Chat options (sheet "Dieser Chat") ----
    fun setIncognito(on: Boolean) {
        val cur = _ui.value.current
        _ui.update { s -> s.copy(incognito = on, conversations = s.conversations.map { if (it.id == cur?.id) it.copy(incognito = on) else it }) }
        persist() // an incognito chat disappears from the stored file
    }

    fun setChatInstruction(id: String) {
        settings.defaultInstruction = id
        val cur = _ui.value.current
        _ui.update { s -> s.copy(newInstruction = id, conversations = s.conversations.map { if (it.id == cur?.id) it.copy(instruction = id) else it }) }
        persist()
    }

    fun saveInstruction(i: de.foldki.app.data.Instruction) {
        val list = settings.instructions.let { l -> if (l.any { it.id == i.id }) l.map { if (it.id == i.id) i else it } else l + i }
        settings.instructions = list
        _ui.update { it.copy(instructions = list) }
    }

    fun deleteInstruction(id: String) {
        val list = settings.instructions.filterNot { it.id == id }
        settings.instructions = list
        if (settings.defaultInstruction == id) settings.defaultInstruction = ""
        _ui.update { s -> s.copy(instructions = list, newInstruction = if (s.newInstruction == id) "" else s.newInstruction) }
    }

    fun setCreativity(c: de.foldki.app.data.Creativity) { settings.creativity = c; _ui.update { it.copy(creativity = c) } }
    fun setAnswerLength(l: de.foldki.app.data.AnswerLength) { settings.answerLength = l; _ui.update { it.copy(answerLength = l) } }
    fun setShowThoughts(on: Boolean) { settings.showThoughts = on; _ui.update { it.copy(showThoughts = on) } }
    fun setShowStats(on: Boolean) { settings.showStats = on; _ui.update { it.copy(showStats = on) } }
    // ---- S3-Sicherung ----
    private val _s3Busy = MutableStateFlow(false)
    val s3Busy: StateFlow<Boolean> = _s3Busy.asStateFlow()
    private val _s3Last = MutableStateFlow(settings.s3Last)
    val s3Last: StateFlow<Long> = _s3Last.asStateFlow()

    /** Encrypts and uploads the backup now (button "Jetzt sichern" and automatic backups). */
    fun s3BackupNow(quiet: Boolean = false) {
        if (_s3Busy.value) return
        val s = settings
        if (s.s3Bucket.isBlank() || s.s3AccessKey.isBlank() || s.s3SecretKey.isBlank() || s.s3Password.isBlank()) {
            if (!quiet) notify("S3: Bucket, Zugang und Passwort eintragen."); return
        }
        _s3Busy.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val data = de.foldki.app.data.S3Backup.encrypt(de.foldki.app.data.S3Backup.pack(getApplication(), s), s.s3Password)
                de.foldki.app.data.S3Backup.upload(s, data)
                data.size
            }.onSuccess { n ->
                val t = System.currentTimeMillis(); s.s3Last = t; _s3Last.value = t
                if (!quiet) notify("Gesichert (${"%.1f".format(java.util.Locale.GERMANY, n / 1024.0)} KB, verschlüsselt)")
            }.onFailure { e ->
                de.foldki.app.data.DebugLog.write("S3-Sicherung fehlgeschlagen: ${e.message}")
                if (!quiet) notify("S3-Sicherung fehlgeschlagen: ${e.message}")
            }
            _s3Busy.value = false
        }
    }

    fun setPdfEnabled(on: Boolean) { settings.pdfEnabled = on; _ui.update { it.copy(pdfEnabled = on) } }
    fun setAutoSpeak(on: Boolean) { settings.autoSpeak = on; _ui.update { it.copy(autoSpeak = on) } }
    fun setWeb(on: Boolean) { settings.webSearch = on; _ui.update { it.copy(webSearch = on) } }

    // ---- Gemerkte Antworten ----
    fun saveAnswer(m: ChatMessage) {
        if (_ui.value.saved.any { it.text == m.text }) { notify("Schon gemerkt"); return }
        val list = listOf(de.foldki.app.data.SavedAnswer(text = m.text, model = m.model.orEmpty(), chat = _ui.value.current?.title.orEmpty())) + _ui.value.saved
        _ui.update { it.copy(saved = list) }
        viewModelScope.launch(saveDispatcher) { runCatching { savedStore.save(list) } }
        notify("Gemerkt")
    }

    /** "↻": the answer [answerId] is written again – the question before it is asked once more (with its files). */
    fun regenerate(answerId: String) {
        if (_ui.value.generating) return
        val conv = _ui.value.current ?: return
        val i = conv.messages.indexOfFirst { it.id == answerId }
        if (i <= 0) return
        val q = conv.messages[i - 1].takeIf { it.role == Role.USER } ?: return
        upsert(conv.copy(messages = conv.messages.subList(0, i - 1)))
        _ui.update { it.copy(pending = q.attachments) }
        send(q.text)
    }

    fun removeSaved(id: String) {
        val list = _ui.value.saved.filterNot { it.id == id }
        _ui.update { it.copy(saved = list) }
        viewModelScope.launch(saveDispatcher) { runCatching { savedStore.save(list) } }
    }

    fun open(id: String) { stop(); _ui.update { it.copy(currentId = id) } }

    fun delete(id: String) {
        _ui.value.conversations.firstOrNull { it.id == id }?.messages?.flatMap { it.attachments }
            ?.forEach { runCatching { java.io.File(it.path).delete() } }
        _ui.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.id == id },
                currentId = if (s.currentId == id) null else s.currentId,
            )
        }
        persist()
    }

    /** "Alle Chats löschen" (Verlauf): every chat and its files. */
    fun deleteAll() {
        stop()
        _ui.value.conversations.flatMap { it.messages }.flatMap { it.attachments }.forEach { runCatching { java.io.File(it.path).delete() } }
        _ui.update { it.copy(conversations = emptyList(), currentId = null) }
        persist()
    }

    /** „Weiterschreiben“ under a cut answer: the model continues without the length limit. */
    fun continueAnswer() = send("Schreib genau an der Stelle weiter, an der du aufgehört hast. Wiederhole nichts.", noLimit = true)

    fun stop() { job?.cancel(); job = null; _ui.update { it.copy(generating = false, thinking = false, liveCpu = -1) } }

    /**
     * Sends a question to the chosen model. Without the switch "Briefe" the model gets only the user's own instruction
     * (Einstellungen → Systemanweisung), the chat and the question (plus text of attached files) – no built-in rules.
     * [voice] = asked in voice mode (only used for the error report).
     */
    fun send(text: String, voice: Boolean = false, noLimit: Boolean = false) {
        val pendingFiles = _ui.value.pending
        val prompt = (if (de.foldki.app.ai.TextIntegrity.preserve(text)) text else text.trim()).ifBlank { if (pendingFiles.isNotEmpty()) "Was steht in diesem Dokument? Fasse es kurz zusammen." else "" }
        if (prompt.isEmpty() || _ui.value.generating || _ui.value.importing) return
        // "als PDF bitte": the app makes the PDF from the last answer itself (no model needed).
        if (pendingFiles.isEmpty() && (prompt.length <= 80 || (prompt.length <= 160 && PDF_MAKE.containsMatchIn(prompt))) && !INFO_QUESTION.containsMatchIn(prompt)) {
            val lastAnswer = _ui.value.current?.messages?.lastOrNull { it.role == Role.ASSISTANT && !it.isError && it.text.isNotBlank() && !it.text.startsWith(PDF_REPLY) }
            // "als PDF" always; "so erstelle", "mach daraus eine Datei", "zum Drucken" only after a letter/document.
            val isDoc = lastAnswer != null && (DocumentParser.parse(lastAnswer.text).document != null || LETTER_WORDS.containsMatchIn(lastAnswer.text.take(300)))
            if (lastAnswer != null && (PDF_WORD.containsMatchIn(prompt) || (isDoc && PDF_FOLLOW.containsMatchIn(prompt.trim())))) { pdfFromChat(prompt, lastAnswer); return }
        }
        // „Länge“: letters and documents are always written in full; else the model is told the length and ends the sentence.
        val docTask = LETTER_WORDS.containsMatchIn(prompt) || DOC_WORDS.containsMatchIn(prompt)
        val diagnostic = de.foldki.app.ai.TextIntegrity.isProbe(prompt) && !(_ui.value.current?.incognito ?: _ui.value.incognito)
        val answerLimit = if (noLimit || docTask || diagnostic) 0 else settings.answerLength.tokens
        val lengthHint = when {
            voice && !docTask -> "Das ist ein Gespräch mit Sprache: antworte kurz in 1 bis 3 Sätzen, wie gesprochen, ohne Listen, Tabellen oder Markdown."
            answerLimit in 1..500 -> "Antworte kurz: höchstens etwa 200 Wörter. Beende immer den letzten Satz."
            answerLimit in 501..2000 -> "Antworte in höchstens etwa 800 Wörtern. Beende immer den letzten Satz."
            else -> ""
        }
        // What the engine may write at most (thinking included): room to end the sentence after the limit.
        val act = settings.activeModel
        // Ab 4.9: settings per model (Modelle → Einstellungen); letters and documents are written more calmly.
        val params0 = if (act.isBlank() || act == Settings.NANO || act.startsWith(de.foldki.app.ai.PcServer.PREFIX)) de.foldki.app.data.ModelParams() else settings.modelParams(act)
        // Anruf-Modus: no thinking – the answer should start at once.
        val params = if (voice && !docTask) params0.copy(think = "off") else params0
        val baseTemp: Double? = if (params.temperature >= 0f) params.temperature.toDouble() else settings.creativity.temperature
        val temp: Double? = if (docTask) minOf(baseTemp ?: 0.3, 0.3) else baseTemp
        val engineMax = when {
            answerLimit > 0 -> (answerLimit * 3 / 2 + de.foldki.app.ai.GgufEngine.THINK_BUDGET).coerceAtMost(de.foldki.app.ai.GgufEngine.MAX_ANSWER_TOKENS)
            params.maxTokens > 0 && !docTask -> params.maxTokens
            else -> 0
        }
        val ghLikely = settings.githubEnabled && settings.githubToken.isNotBlank() && GITHUB_WORDS.containsMatchIn(prompt)
        // Ab 4.9: no internet search for greetings, letters, documents, attached files, GitHub/tool tasks or empty follow-ups.
        val web = !diagnostic && _ui.value.webSearch && wantsWeb(prompt, docTask, pendingFiles.isNotEmpty(), ghLikely)
        val useNano = act == Settings.NANO
        val modelName = _ui.value.activeName
        val engine = if (useNano) Engine.NANO else Engine.GEMMA

        // Create conversation if needed and append user + empty assistant message.
        var conv = _ui.value.current ?: Conversation(title = prompt.take(40), instruction = _ui.value.newInstruction, incognito = _ui.value.incognito)
        val userMsg = ChatMessage(role = Role.USER, text = prompt, attachments = pendingFiles)
        val botMsg = ChatMessage(role = Role.ASSISTANT, text = "", engine = engine, web = web, model = modelName)
        de.foldki.app.data.TextTrace.begin(botMsg.id, text, conv.incognito)
        de.foldki.app.data.TextTrace.stage(botMsg.id, "02 PROMPT_STORED", prompt)
        conv = conv.copy(messages = conv.messages + userMsg + botMsg, updatedAt = System.currentTimeMillis())
        upsert(conv)
        _ui.update { it.copy(currentId = conv.id, generating = true, pending = emptyList()) }

        val history = conv.messages.dropLast(1)
        val letters = false // no built-in letter rules (from 3.3)
        // Letter logic only with the switch "Briefe".
        val lastDoc = if (!letters) null else history.lastOrNull { it.role == Role.ASSISTANT && !it.isError }
            ?.let { DocumentParser.parse(it.text) }?.takeIf { it.document != null }
        val hasLetterInChat = history.any { m -> m.attachments.any { it.analysis != null || it.docDate != null || it.recipient != null } }
        val wantsLetter = letters && ((history.filter { it.role == Role.USER }.takeLast(2).map { it.text })
            .any { LETTER_WORDS.containsMatchIn(it) } || lastDoc?.isLetter == true ||
            (hasLetterInChat && REPLY_INTENT.containsMatchIn(prompt) && !JUST_READ.containsMatchIn(prompt)))
        // "Merk dir …" is still kept on the phone (Einstellungen → Gedächtnis), but not sent to the model.
        val learned = de.foldki.app.data.MemoryStore.factsIn(prompt).filter { memory.add(it) }
        if (learned.isNotEmpty()) {
            _memories.value = memory.all()
            _ui.update { it.copy(notice = "Gemerkt: ${learned.joinToString("; ")}") }
        }
        var finished = false
        val t0 = System.currentTimeMillis()
        var logSystem = ""
        var logHistory = 0
        var logError: String? = null
        job = viewModelScope.launch {
            val cpu = de.foldki.app.ai.CpuMeter()
            val liveJob = launch { while (true) { delay(1000); val c = cpu.sample(); _ui.update { it.copy(liveCpu = c) } } }
            try {
                if (letters) analyzeDocs(conv.id, history.flatMap { it.attachments })
                val history = _ui.value.conversations.firstOrNull { it.id == conv.id }?.messages?.dropLast(1) ?: history
                val calcHint = if (diagnostic || de.foldki.app.ai.CodeText.hasCode(prompt)) "" else de.foldki.app.ai.Calc.hint(prompt)
                val system = (if (useNano) nanoSystem(conv.instruction, lengthHint) else systemWithContext(conv.instruction, lengthHint)) +
                    (if (calcHint.isBlank()) "" else "\n\n" + calcHint)
                logSystem = system
                logHistory = history.size
                val onHot: (String) -> Unit = { t -> editMessage(conv.id, botMsg.id) { it.copy(text = t) } }
                val ghTask = !diagnostic && settings.githubEnabled && isGitHubTask(prompt, history)
                val mcpTask = !diagnostic && mcpReady() && isMcpTask(prompt, history)
                if (ghTask || mcpTask) {
                    // Tools (GitHub, own MCP server) with every model except Gemini Nano: LiteRT, GGUF and the PC server.
                    if (useNano || act.isBlank()) {
                        editMessage(conv.id, botMsg.id) {
                            it.copy(text = "Für Werkzeuge (GitHub, MCP) bitte unter Menü → Modelle ein heruntergeladenes Modell oder den PC-Server wählen. " +
                                "Gemini Nano kann keine Werkzeuge benutzen.")
                        }
                    } else {
                        val mcp = if (!mcpReady()) null else runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val c = McpClient(settings.mcpUrl, settings.mcpToken)
                                c to c.listTools()
                            }
                        }.getOrElse { e ->
                            editMessage(conv.id, botMsg.id) { it.copy(text = it.text + "MCP-Server nicht erreichbar: ${e.message ?: e.javaClass.simpleName}\n\n") }
                            null
                        }
                        val runAgent: suspend (SessionFn) -> Unit = { fn ->
                            GemmaGitHubAgent(fn, if (settings.githubEnabled) settings.githubToken else "", mcp?.first, mcp?.second.orEmpty(), settings.mcpName).run(
                                baseSystem = systemWithContext(conv.instruction), question = prompt,
                                onText = { t -> editMessage(conv.id, botMsg.id) { it.copy(text = it.text + t) } },
                                approve = { title, details -> askApproval(title, details) },
                            )
                        }
                        val pc = _ui.value.activeServer
                        when {
                            pc != null -> runAgent { sys, first, max, onTurn -> ServerEngine.session(pc, sys, first, max, onTurn) }
                            HfModels.isGguf(act) -> withLocal(onHot) { runAgent { sys, first, max, onTurn -> gguf.session(sys, first, max, onTurn) } }
                            else -> withLocal(onHot) { runAgent { sys, first, max, onTurn -> llm.session(sys, first, max, onTurn) } }
                        }
                    }
                    finished = true
                    return@launch
                }
                if (!diagnostic && settings.githubToken.isBlank() && GITHUB_STRONG.containsMatchIn(prompt)) {
                    editMessage(conv.id, botMsg.id) {
                        it.copy(text = "Für GitHub-Aufgaben trag bitte unter Einstellungen → GitHub deinen Token ein und tippe auf „Verbinden & prüfen“. " +
                            "Danach arbeitet das gewählte Modell auf dem Handy mit deinem GitHub – jede Änderung nur mit deiner Freigabe.")
                    }
                    finished = true
                    return@launch
                }
                if (act.isBlank()) {
                    editMessage(conv.id, botMsg.id) { it.copy(text = noModelText(), model = null) }
                    finished = true
                    return@launch
                }
                var ggufThink = false
                var tAnswer = 0L
                val thinkOpenName = !useNano && HfModels.isThinking(act)
                val raw = StringBuilder()
                var pieces = 0
                var tFirst = 0L
                var answerPieces = 0
                var stopNow = false
                var cutNow = false
                var looped = false
                val onPiece: (String) -> Unit = { d ->
                    if (tFirst == 0L) tFirst = System.currentTimeMillis()
                    pieces++
                    raw.append(d)
                    val sp = ThinkParser.split(raw.toString(), thinkOpenName || ggufThink, finished = false)
                    if (diagnostic) {
                        de.foldki.app.data.TextTrace.stage(botMsg.id, "04 STREAM_CHUNK_" + pieces, d)
                        if (pieces <= 8 || pieces % 20 == 0) de.foldki.app.data.TextTrace.stage(botMsg.id, "05 STREAM_PARSED_" + pieces, sp.answer)
                    }
                    if (!sp.thinking && sp.answer.isNotEmpty()) answerPieces++
                    // Length limit: stop at the end of a sentence; only far over the limit is the answer cut.
                    if (answerLimit > 0 && !stopNow) {
                        if (answerPieces >= answerLimit && endsSentence(sp.answer)) stopNow = true
                        else if (answerPieces >= answerLimit * 3 / 2) { stopNow = true; cutNow = true }
                    }
                    // Ab 4.9: loop guard – a model that writes the same block again and again is stopped.
                    if (!stopNow && answerPieces > 0 && answerPieces % 30 == 0 && !de.foldki.app.ai.CodeText.hasCode(sp.answer) && repeats(sp.answer)) { stopNow = true; looped = true }
                    if (tAnswer == 0L && sp.answer.isNotBlank() && sp.thoughts.isNotEmpty()) tAnswer = System.currentTimeMillis()
                    if (sp.thinking != _ui.value.thinking) _ui.update { it.copy(thinking = sp.thinking) }
                    editMessage(conv.id, botMsg.id) { it.copy(text = sp.answer, thoughts = sp.thoughts) }
                }
                val server = _ui.value.activeServer
                if (server != null) {
                    val webText = if (web) webFor(prompt, history).take(4000) else ""
                    val turns = history.filter { !it.isError && it.text.isNotBlank() }
                    val last = turns.lastOrNull { it.role == Role.USER }
                    if (last != null) {
                        val earlier = turns.subList(0, turns.lastIndexOf(last)).takeLast(20).map { turnOf(it, 6000) }.map { it.user to it.text }
                        val question = (if (webText.isBlank()) "" else WEB_INTRO + webText + "\n\n") + turnOf(last, 20000).text
                        de.foldki.app.data.DebugLog.write("START PC-Server ${server.typeLabel} ${server.base} · ${server.model} · Frage ${question.length}")
                        ServerEngine.chat(server, system, earlier, question, temp, engineMax).takeWhile { !stopNow }.collect { onPiece(it) }
                    }
                } else if (useNano) {
                    if (!_ui.value.nanoReady) checkNano().join()
                    if (!_ui.value.nanoReady) throw IllegalStateException("Gemini Nano ist nicht bereit: ${_ui.value.nanoStatus}")
                    nano.reply(system, history, if (web) webFor(prompt, history) else "").let { f -> if (engineMax > 0) f.take(engineMax) else f }.takeWhile { !stopNow }.collect { onPiece(it) }
                } else if (HfModels.isGguf(act)) withLocal(onHot) {
                    val budget = gguf.inputBudgetChars - system.length
                    val webText = if (web) webFor(prompt, history).take(budget / 4) else ""
                    val turns = history.filter { !it.isError && it.text.isNotBlank() }
                    val last = turns.lastOrNull { it.role == Role.USER } ?: return@withLocal
                    val earlier = compress(turns.subList(0, turns.lastIndexOf(last)).takeLast(10).map { turnOf(it, budget / 3) }, budget)
                        .map { (it.user) to it.text }
                    val room = (budget * 0.8).toInt() - webText.length - last.text.length
                    val own = turnOf(last, room.coerceAtLeast(0))
                    val question = (if (webText.isBlank()) "" else WEB_INTRO + webText + "\n\n") + own.text
                    de.foldki.app.data.DebugLog.write("START GGUF ${models.nameOf(act)} · ${gguf.backendUsed} · Kontext ${gguf.contextTokens} · Frage ${question.length}")
                    var first = true
                    gguf.chat(system, earlier, question, temp, engineMax, params.topK, params.topP, params.think).takeWhile { !stopNow }.collect {
                        if (first) { first = false; ggufThink = gguf.thinkOpen }
                        onPiece(it)
                    }
                } else withLocal(onHot) {
                    // Everything must fit into the model's context – too much input closes the whole app natively.
                    val budget = llm.inputBudgetChars - system.length
                    val webText = if (web) webFor(prompt, history).take(budget / 4) else ""
                    val turns = history.filter { !it.isError && it.text.isNotBlank() }
                    val last = turns.lastOrNull { it.role == Role.USER } ?: return@withLocal
                    val earlier = compress(turns.subList(0, turns.lastIndexOf(last)).map { turnOf(it, budget / 3) }, budget)
                    val room = (budget * 0.8).toInt() - webText.length - last.text.length
                    val own = turnOf(last, room.coerceAtLeast(0))
                    if (last.attachments.any { it.text.length > room.coerceAtLeast(0) })
                        notify("Die Datei ist zu lang für dieses Modell – es liest nur den Anfang (ca. ${room.coerceAtLeast(0)} Zeichen).")
                    // Qwen thinking off (soft switch of the Qwen chat template).
                    val noThink = if (params.think == "off" && act.lowercase().contains("qwen")) " /no_think" else ""
                    val question = (if (webText.isBlank()) "" else WEB_INTRO + webText + "\n\n") + own.text + noThink
                    // For the error report: what goes to the model (a native crash is only seen at the next start).
                    de.foldki.app.data.DebugLog.write(
                        "START ${models.nameOf(act)} · ${llm.backendUsed} · Kontext ${llm.contextTokens} Tokens · " +
                            "Platz ${llm.inputBudgetChars} Zeichen · Anweisung ${system.length} · Frage ${question.length} · " +
                            "Verlauf ${earlier.size} Nachrichten (${earlier.sumOf { it.text.length }} Zeichen)",
                    )
                    llm.chat(conv.id, system, earlier, question, last.id, botMsg.id, temp, params.topK, params.topP)
                        .let { f -> if (engineMax > 0) f.take(engineMax) else f }.takeWhile { !stopNow }.collect { onPiece(it) }
                }
                de.foldki.app.data.TextTrace.stage(botMsg.id, "05 STREAM_RAW_COMPLETE", raw.toString())
                val end = ThinkParser.split(raw.toString(), thinkOpenName || ggufThink, finished = true)
                de.foldki.app.data.TextTrace.stage(botMsg.id, "06 THINK_PARSER_OUTPUT", end.answer)
                val now = System.currentTimeMillis()
                // Ab 4.9: the app checks the answer – sums recomputed, own data instead of placeholders, loops cut, sources.
                val literalCode = diagnostic || de.foldki.app.ai.TextIntegrity.preserve(end.answer)
                var finalText = end.answer
                if (!literalCode) {
                    finalText = appExtrasRemoved(finalText)
                    if (looped) finalText = cutRepeats(finalText) + "\n\n(Wiederholung erkannt – angehalten.)"
                    finalText = de.foldki.app.ai.Calc.fixEquations(finalText)
                    finalText = fillPlaceholders(finalText)
                }
                de.foldki.app.data.DebugLog.write(
                    "TEXT-INTEGRITÄT · Modellantwort=" + end.answer.length +
                        " · gespeichert identisch=" + (end.answer == finalText) +
                        " · Anzeige identisch=" + (end.answer == DocumentParser.display(finalText)) +
                        " · Kopie identisch=" + (end.answer == DocumentParser.copyText(finalText))
                )
                if (web && lastSources.isNotEmpty() && finalText.isNotBlank())
                    finalText += "\n\nQuellen:\n" + lastSources.take(5).joinToString("\n") { (t, u) -> "• $t – $u" }
                de.foldki.app.data.TextTrace.stored(botMsg.id, finalText)
                val claimsPdf = !diagnostic && settings.pdfEnabled && PDF_CLAIM.containsMatchIn(finalText)
                editMessage(conv.id, botMsg.id) {
                    it.copy(
                        text = finalText, thoughts = end.thoughts, firstMs = if (tFirst > 0) tFirst - t0 else 0,
                        tokens = if (useNano) 0 else pieces, answerMs = now - t0,
                        tokPerSec = if (!useNano && pieces > 1 && now > tFirst) (pieces - 1) * 1000f / (now - tFirst) else 0f,
                        backend = if (useNano) "AICore" else if (_ui.value.activeServer != null) "PC" else if (HfModels.isGguf(act)) (gguf.backendUsed ?: "") else (llm.backendUsed ?: ""),
                        cpuPct = if (useNano || _ui.value.activeServer != null) -1 else cpu.average(),
                        thinkMs = if (end.thoughts.isNotEmpty() && tFirst > 0) ((if (tAnswer > 0) tAnswer else now) - tFirst).coerceAtLeast(0) else 0,
                        cut = cutNow || (answerLimit > 0 && answerPieces >= answerLimit && !endsSentence(end.answer)),
                    )
                }
                // The header shows the engine that just answered (not "nicht geladen").
                if (!useNano && server == null) (if (HfModels.isGguf(act)) gguf.backendUsed else llm.backendUsed)?.let { b -> _ui.update { it.copy(backend = b) } }
                // The model claims it made a PDF – only the app can: it makes it now from the letter in the chat.
                if (claimsPdf && !voice) {
                    val doc = DocumentParser.parse(finalText).document
                        ?: _ui.value.conversations.firstOrNull { it.id == conv.id }?.messages?.lastOrNull { m ->
                            m.role == Role.ASSISTANT && !m.isError && DocumentParser.parse(m.text).document != null
                        }?.let { DocumentParser.parse(it.text).document }
                    if (doc != null) {
                        editMessage(conv.id, botMsg.id) { it.copy(text = it.text + "\n\n$PDF_REPLY – dort speichern, teilen oder den Text ändern.") }
                        openPreview(doc)
                    }
                }
                finished = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError = e.stackTraceToString().take(2000)
                editMessage(conv.id, botMsg.id) {
                    it.copy(
                        text = (if (it.text.isBlank()) "" else it.text + "\n\n") +
                            "Fehler: ${e.message ?: e.javaClass.simpleName}",
                        isError = it.text.isBlank(),
                    )
                }
            } finally {
                liveJob.cancel()
                // Error report (stays on the phone until the user shares it)
                val answer = _ui.value.conversations.firstOrNull { it.id == conv.id }?.messages?.firstOrNull { it.id == botMsg.id }
                de.foldki.app.data.DebugLog.request(
                    engine = modelName + (if (voice) " · Sprachmodus" else "") + (if (web) " · Web" else "") +
                        (answer?.backend?.ifBlank { null }?.let { " · $it" } ?: "") +
                        (if (!finished && logError == null) " · abgebrochen" else ""),
                    system = logSystem, historyCount = logHistory, question = prompt, answer = answer?.text.orEmpty(),
                    firstTokenMs = null, totalMs = System.currentTimeMillis() - t0, error = logError,
                )
                // No PDF from a half-written letter (stopped or failed); only with the switch "Briefe".
                if (finished && letters) attachDocumentPdf(conv.id, botMsg.id, wantsLetter, openPreview = !voice)
                // "Antworten automatisch vorlesen" (page Stimmen); in voice mode the overlay speaks itself.
                if (finished && !voice && settings.autoSpeak && this@ChatViewModel.voice.canSpeak) {
                    answer?.text?.takeIf { it.isNotBlank() }?.let { this@ChatViewModel.voice.speak(DocumentParser.spoken(it)) }
                }
                // Only the still-current job may end "generating" (a stopped job must not end the next one).
                if (job == null || job === coroutineContext[Job]) _ui.update { it.copy(generating = false, thinking = false, liveCpu = -1) }
                persist()
            }
        }
    }

    /** One chat turn for the local model: a question with the text of its attached files, or an earlier answer. */
    private fun turnOf(m: ChatMessage, fileChars: Int): LocalEngine.Turn {
        if (m.role != Role.USER) return LocalEngine.Turn(m.id, false, (if (de.foldki.app.ai.CodeText.hasCode(m.text)) m.text else appExtrasRemoved(m.text)).take(2000))
        val files = StringBuilder()
        var budget = fileChars
        m.attachments.forEach { a ->
            if (budget <= 0) return@forEach
            if (a.text.isBlank()) { files.append("(Aus ").append(a.name).append(" konnte kein Text gelesen werden.)\n\n"); return@forEach }
            val t = a.text.take(budget); budget -= t.length
            files.append("Datei ").append(a.name).append(":\n").append(t).append("\n\n")
        }
        return LocalEngine.Turn(m.id, true, files.toString() + m.text)
    }

    /** Ab 4.9.1: the app's own additions (source list, PDF note) are not part of the model's answer. */
    private fun appExtrasRemoved(t: String): String =
        t.replace(Regex("\\n*Quellen:\\n(•[^\\n]*\\n?)+"), "\n").replace(Regex("\\n*📄 PDF-Vorschau geöffnet[^\\n]*"), "").trim()

    /** Sources (title, link) of the last internet search; listed under the answer. */
    private var lastSources: List<Pair<String, String>> = emptyList()

    /** Ab 4.9: is an internet search useful for this question? */
    private fun wantsWeb(prompt: String, docTask: Boolean, hasFiles: Boolean, toolTask: Boolean): Boolean {
        val p = prompt.trim()
        return when {
            p.length < 4 || docTask || hasFiles || toolTask -> false
            // Ab 4.9.1: one or two words ("jetzt", "und?", "habe ich") are no search question.
            p.split(Regex("\\s+")).size < 3 && !WEB_STRONG.containsMatchIn(p) -> false
            SMALL_TALK.containsMatchIn(p) && p.length < 40 -> false
            PDF_WORD.containsMatchIn(p) || PDF_FOLLOW.containsMatchIn(p) -> false
            // Ab 4.9.1: clear need for current facts or an explicit search command -> search.
            WEB_STRONG.containsMatchIn(p) || SEARCH_CMD.containsMatchIn(p) -> true
            // Questions to the AI itself or about the chat: no search ("Was ist mit dir los?").
            ABOUT_AI.containsMatchIn(p) -> false
            OWN_TASK.containsMatchIn(p) -> false
            else -> true
        }
    }

    /** Search words from the question: filler removed; a short follow-up is combined with the earlier question. */
    private fun webQuery(prompt: String, history: List<ChatMessage>): String {
        val earlier = history.dropLast(1).lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val base = if ((prompt.length < 30 || REFERS_BACK.containsMatchIn(prompt)) && earlier.isNotBlank()) "$earlier $prompt" else prompt
        val q = base.replace(FILLER, " ").replace(Regex("[?!.,;:\"„“]"), " ").replace(Regex("\\s+"), " ").trim()
        return q.ifBlank { prompt }.take(100)
    }

    /** Web results for the model (Wikipedia, news, web search) – searched with words from the question and the chat. */
    private suspend fun webFor(question: String, history: List<ChatMessage> = emptyList()): String {
        val q = webQuery(question, history)
        de.foldki.app.data.DebugLog.write("Websuche: „$q“")
        val r = WebLookup.searchWithSources(q)
        lastSources = r.second
        return r.first
    }

    /** Ab 4.9: older turns are shortened when the chat fills more than 75 % of the model's room. */
    private fun compress(turns: List<LocalEngine.Turn>, budget: Int): List<LocalEngine.Turn> {
        if (turns.sumOf { it.text.length } <= budget * 3 / 4 || turns.size <= 4) return turns
        val keep = turns.size - 4
        return turns.mapIndexed { i, t -> if (i < keep && t.text.length > 400) t.copy(text = t.text.take(400) + " …") else t }
    }

    /** true = the end of [text] is the same block written three times in a row (a loop). */
    private fun repeats(text: String): Boolean {
        val t = text.trimEnd()
        if (t.length < 90) return false
        for (len in 20..minOf(400, t.length / 3)) {
            val a = t.substring(t.length - len)
            if (t.endsWith(a + a + a)) return true
        }
        // the same line again and again
        val lines = t.lines().map { it.trim() }.filter { it.length > 15 }
        return lines.size >= 4 && lines.takeLast(4).distinct().size == 1
    }

    /** Removes the repeated blocks at the end, keeps one. */
    private fun cutRepeats(text: String): String {
        var t = text.trimEnd()
        for (len in 20..minOf(400, t.length / 2)) {
            val a = t.substring(t.length - len)
            while (t.length >= 2 * len && t.endsWith(a + a)) t = t.substring(0, t.length - len)
        }
        val lines = t.lines()
        val out = ArrayList<String>()
        for (l in lines) if (out.isEmpty() || l.trim().length <= 15 || l.trim() != out.last().trim()) out += l
        return out.joinToString("\n").trimEnd()
    }

    /** Ab 4.9: placeholders for the user's own data ("[Ihr Name]") get the data of the active sender profile. */
    private fun fillPlaceholders(text: String): String {
        if (!text.contains('[')) return text
        val p = settings.activeSender ?: return text.replace(DATE_PLACEHOLDER, today())
        var t = text.replace(DATE_PLACEHOLDER, today())
        if (p.name.isNotBlank()) t = t.replace(NAME_PH) { p.name }
        if (p.street.isNotBlank()) t = t.replace(STREET_PH) { p.street }
        if (p.zipCity.isNotBlank()) t = t.replace(ZIP_PH) { p.zipCity }
        if (p.city.isNotBlank()) t = t.replace(CITY_PH) { p.city }
        if (p.phone.isNotBlank()) t = t.replace(PHONE_PH) { p.phone }
        if (p.email.isNotBlank()) t = t.replace(MAIL_PH) { p.email }
        return t
    }

    /** Gemini Nano: only date, language and the own instruction (its input is small; long texts made it repeat itself). */
    private fun nanoSystem(instructionId: String, lengthHint: String): String {
        val day = java.text.SimpleDateFormat("EEEE, d. MMMM yyyy", java.util.Locale.GERMANY).format(java.util.Date())
        val ctx = if (settings.appContext) "Heute ist $day. Antworte auf Deutsch." else ""
        return listOf(ctx, lengthHint, systemFor(instructionId)).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** true = the text ends a sentence (or a line), so stopping here does not cut a word or sentence. */
    private fun endsSentence(t: String): Boolean {
        val s = t.trimEnd()
        if (s.isEmpty()) return false
        return t.endsWith("\n") || s.last() in ".!?:)\"»“”"
    }

    /** "als PDF bitte": opens the PDF preview of the last answer and notes it in the chat. */
    private fun pdfFromChat(prompt: String, answer: ChatMessage) {
        val conv0 = _ui.value.current ?: return
        val reply = if (settings.pdfEnabled) "$PDF_REPLY – dort speichern, teilen oder den Text ändern."
            else "„Briefe & PDF“ ist unter Menü → Werkzeuge ausgeschaltet. Bitte dort einschalten, dann geht es."
        upsert(conv0.copy(
            messages = conv0.messages + ChatMessage(role = Role.USER, text = prompt) + ChatMessage(role = Role.ASSISTANT, text = reply, model = answer.model),
            updatedAt = System.currentTimeMillis(),
        ))
        if (settings.pdfEnabled) openPreview(DocumentParser.parse(answer.text).document ?: DocumentParser.display(answer.text))
    }

    // ---- MCP-Werkzeuge (own tool server) ----
    private fun mcpReady(): Boolean = settings.mcpEnabled && settings.mcpUrl.isNotBlank()

    /** MCP task: the user names the server, "MCP", "Werkzeug"/"Tool" – or the chat is already working with it. */
    private fun isMcpTask(prompt: String, history: List<ChatMessage>): Boolean {
        val p = prompt.lowercase()
        if (Regex("\\b(mcp|werkzeuge?|tools?)\\b").containsMatchIn(p)) return true
        val name = settings.mcpName.trim().lowercase()
        if (name.length >= 3 && p.contains(name)) return true
        return history.takeLast(4).any { m -> m.role == Role.ASSISTANT && (m.text.contains("🧰 ") || m.text.contains("Freigegeben: Werkzeug")) }
    }

    private val _mcpChecking = MutableStateFlow(false)
    val mcpChecking: StateFlow<Boolean> = _mcpChecking.asStateFlow()
    private val _mcpStatus = MutableStateFlow(mcpStatusText())
    val mcpStatus: StateFlow<String> = _mcpStatus.asStateFlow()

    private fun mcpStatusText(): String = when {
        settings.mcpUrl.isBlank() -> "Nicht eingerichtet"
        settings.mcpToolCount >= 0 -> "Verbunden – ${settings.mcpToolCount} Werkzeuge" + if (settings.mcpEnabled) "" else " (ausgeschaltet)"
        else -> "Adresse eingetragen – noch nicht geprüft"
    }

    fun setMcpEnabled(on: Boolean) { settings.mcpEnabled = on; _mcpStatus.value = mcpStatusText() }

    /** Saves name, address and token and asks the server for its tools. */
    fun checkMcp(name: String, url: String, token: String) {
        settings.mcpName = name; settings.mcpUrl = url; settings.mcpToken = token
        settings.mcpToolCount = -1
        _mcpStatus.value = mcpStatusText()
        if (url.isBlank()) return
        _mcpChecking.value = true
        viewModelScope.launch {
            val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { McpClient(url.trim(), token.trim()).listTools() } }
            _mcpChecking.value = false
            r.onSuccess { tools ->
                settings.mcpToolCount = tools.size
                settings.mcpEnabled = true
                _mcpStatus.value = mcpStatusText()
                notify("MCP verbunden: ${tools.size} Werkzeuge")
            }.onFailure { e ->
                _mcpStatus.value = "Nicht erreichbar: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                notify("MCP-Server nicht erreichbar")
            }
        }
    }

    /** Puts a finished letter (or a document the user asked for as letter) into the chat as a PDF. */
    private fun attachDocumentPdf(convId: String, msgId: String, wantsLetter: Boolean, openPreview: Boolean = false) {
        val msg = _ui.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == msgId } ?: return
        if (msg.isError) return
        val parts = DocumentParser.parse(msg.text)
        val rawDoc = parts.document ?: return
        if (!parts.complete || !(wantsLetter || parts.isLetter)) return
        // FA-PDF: fill leftover placeholders from the letter's data, end a cut-off text cleanly.
        val original = _ui.value.conversations.firstOrNull { it.id == convId }?.messages
            ?.lastOrNull { it.role == Role.USER && it.attachments.isNotEmpty() }?.attachments?.lastOrNull()
        val facts = original?.analysis?.let { de.foldki.app.export.LetterAnalysis.parse(it) }
        val ref = original?.refNo ?: facts?.reference
        val fixed = de.foldki.app.export.LetterAnalysis.repairNumbers(
            de.foldki.app.export.LetterAnalysis.fixPlaceholders(rawDoc, original?.docDate, ref, facts?.amount, facts?.deadline, facts?.contact),
            amount = facts?.amount,
            dates = listOfNotNull(original?.docDate ?: facts?.date?.ifBlank { null }, facts?.deadlineDate?.ifBlank { null }, today()),
            reference = ref,
        )
        val recipient = de.foldki.app.export.LetterMeta.completeRecipient(
            original?.recipient ?: facts?.sender?.ifBlank { null }, original?.text.orEmpty(), facts?.sender,
        )
        val profile = settings.activeSender
        // Like FA-PDF: the model wrote subject + body, the app sets the letter head and the name.
        val doc = de.foldki.app.export.LetterAnalysis.assemble(
            fixed,
            sender = profile?.toLegacyText()?.ifBlank { null },
            recipient = recipient,
            placeDate = listOfNotNull(profile?.city?.ifBlank { null }, today()).joinToString(", "),
            name = profile?.name?.ifBlank { null },
            refs = listOfNotNull(
                original?.docDate?.ifBlank { null }?.let { "Ihr Schreiben vom: $it" },
                de.foldki.app.export.LetterAnalysis.cleanRef(ref).ifBlank { null }?.let { "Ihr Zeichen: $it" },
            ),
        )
        if (doc != rawDoc) editMessage(convId, msgId) { it.copy(text = it.text.replace(rawDoc, doc)) }
        de.foldki.app.export.LetterAnalysis.recipientKey(original?.recipient)?.let { key ->
            settings.activeSender?.id?.let { settings.rememberProfile(key, it) }
        }
        val pdf = runCatching { attachments.createPdf(pdfText(doc), activeSignature()) }.getOrNull() ?: return
        editMessage(convId, msgId) { it.copy(attachments = it.attachments + pdf) }
        // A finished letter opens in the preview right away (check, save, share or change the text).
        if (openPreview && (parts.isLetter || wantsLetter)) openPreview(doc)
    }

    /**
     * FA-PDF style reading (only with the switch "Briefe"): every not yet read letter/photo is read once by the chosen
     * model. The result fills gaps of the rule-based LetterMeta and is stored on the attachment.
     */
    private suspend fun analyzeDocs(convId: String, files: List<Attachment>) {
        if (settings.activeModel.isBlank()) return
        val todo = files.filter { it.analysis == null && it.text.isNotBlank() }
        for (a in todo) {
            _ui.update { it.copy(notice = "Lese das Schreiben …") }
            val raw = withTimeoutOrNull(180_000) { analyzeOne(a) }.orEmpty()
            val r = de.foldki.app.export.LetterAnalysis.parse(raw)
            val updated = a.copy(
                analysis = raw.ifBlank { "-" }, // "-" = tried, do not read again
                docDate = a.docDate ?: r.date.ifBlank { null },
                recipient = a.recipient ?: r.sender.ifBlank { null },
                refNo = a.refNo ?: r.reference.ifBlank { null },
            )
            _ui.update { st -> st.copy(conversations = st.conversations.map { c ->
                if (c.id != convId) c else c.copy(messages = c.messages.map { m ->
                    m.copy(attachments = m.attachments.map { x -> if (x.id == a.id) updated else x })
                })
            }) }
            // Same recipient as before -> the sender profile used last time (FA-PDF).
            de.foldki.app.export.LetterAnalysis.recipientKey(updated.recipient)?.let { key ->
                val pid = settings.rememberedProfile(key)
                val p = settings.senderProfiles.firstOrNull { it.id == pid }
                if (p != null && p.id != settings.activeSender?.id) {
                    setActiveProfile(p.id)
                    _ui.update { it.copy(notice = "Absender: ${p.label.ifBlank { p.name }} (wie beim letzten Mal)") }
                }
            }
        }
        if (todo.isNotEmpty()) persist()
    }

    private suspend fun analyzeOne(a: Attachment): String = runCatching {
        askModel(
            de.foldki.app.export.LetterAnalysis.ANALYZE,
            "Lies das angehängte Schreiben und antworte im verlangten Zeilenformat.\n\n--- ${a.name} ---\n" + a.text.take(8000),
        )
    }.getOrDefault("")

    /** The user's own names (never taken as recipient of a reply). */
    private fun ownNames(): List<String> = settings.senderProfiles.map { it.name.trim() }.filter { it.isNotEmpty() }

    private fun today(): String = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date())

    /**
     * What goes to the model as instruction: the user's own text from the settings – and only with the switch "Briefe"
     * also today's date and the letter/PDF rules. No other built-in rules (they made thinking models slow).
     */
    private fun buildSystem(letters: Boolean, letter: Boolean, followUpDoc: Boolean, history: List<ChatMessage> = emptyList()): String =
        settings.systemPrompt.trim() // only the user's own text – nothing from the app

    /** Instruction of a chat: the chosen own instruction, else the system instruction – both only the user's own text. */
    private fun systemFor(instructionId: String): String =
        settings.instructions.firstOrNull { it.id == instructionId }?.text?.trim() ?: settings.systemPrompt.trim()

    /**
     * "Datum & App-Wissen mitgeben" (on by default, page Meine Anweisungen): today's date, German, the length wish and
     * what the app can do. Kept short – long built-in rules made thinking models slow. The user's own instruction follows it.
     */
    private fun appContext(lengthHint: String): String {
        if (!settings.appContext) return lengthHint
        val day = java.text.SimpleDateFormat("EEEE, d. MMMM yyyy", java.util.Locale.GERMANY).format(java.util.Date())
        return buildString {
            append("Heute ist ").append(day).append(".\n")
            append("Antworte auf Deutsch, außer der Nutzer möchte eine andere Sprache.\n")
            append("Du bist die KI der App „Fold KI“ auf dem Handy des Nutzers. Die App kann:")
            if (settings.pdfEnabled) append("\n- aus Briefen und Schreiben eine PDF machen (der Nutzer schreibt „als PDF“ oder tippt auf die PDF-Karte). Schreib Briefe vollständig, mit dem heutigen Datum statt Platzhaltern. Behaupte nie, du hättest selbst eine PDF erstellt – das macht die App.")
            settings.activeSender?.takeIf { it.name.isNotBlank() }?.let { sp ->
                append("\n- Absender des Nutzers für Briefe: ").append(listOf(sp.name, sp.street, sp.zipCity).filter { it.isNotBlank() }.joinToString(", "))
                    .append(". Setze diese Angaben ein, keine Platzhalter wie [Ihr Name].")
            }
            append("\n- genau rechnen: Rechnungen rechnet die App nach.")
            append("\n- im Internet suchen, wenn der Schalter „Websuche“ an ist (gerade ").append(if (settings.webSearch) "an" else "aus").append(").")
            if (settings.githubEnabled && settings.githubToken.isNotBlank()) append("\n- auf GitHub arbeiten (Repos lesen, Code ändern, Builds prüfen), wenn der Nutzer GitHub, ein Repo oder Code erwähnt.")
            if (mcpReady()) append("\n- Werkzeuge vom eigenen Server „").append(settings.mcpName.ifBlank { "MCP" }).append("“ benutzen, wenn der Nutzer Werkzeuge oder den Server erwähnt.")
            append("\n- Antworten vorlesen und Sprache erkennen.")
            append("\nSag nie, dass du etwas nicht kannst, was die App kann.")
            if (lengthHint.isNotBlank()) append("\n").append(lengthHint)
        }
    }

    /** App context + the chat's own instruction (the user's text comes last and wins). */
    private fun systemWithContext(instructionId: String, lengthHint: String = ""): String =
        listOf(appContext(lengthHint), systemFor(instructionId)).filter { it.isNotBlank() }.joinToString("\n\n")

    /** Text that goes into a PDF: date placeholders get today's date; letters without a date line get one (no duplicates). */
    fun pdfText(doc: String): String = DocumentParser.ensureDateLine(doc.replace(DATE_PLACEHOLDER, today()), today(), place = settings.activeSender?.city)

    /** Signature of the active profile; always drawn into letter PDFs (never sent to any AI). */
    fun activeSignature(): android.graphics.Bitmap? = settings.activeSender?.let { signatures.load(it.id) }

    // ---- Sender profiles (letters) ----
    val signatures = de.foldki.app.data.SignatureStore(app)
    private val _profiles = MutableStateFlow(settings.senderProfiles to settings.activeSender?.id.orEmpty())
    /** All profiles + id of the active one. */
    val profiles: StateFlow<Pair<List<de.foldki.app.data.SenderProfile>, String>> = _profiles.asStateFlow()
    private val _sigVersion = MutableStateFlow(0)
    val sigVersion: StateFlow<Int> = _sigVersion.asStateFlow()

    private fun publishProfiles() { _profiles.value = settings.senderProfiles to (settings.activeSender?.id.orEmpty()) }

    fun addProfile(): de.foldki.app.data.SenderProfile {
        val list = settings.senderProfiles
        val p = de.foldki.app.data.SenderProfile(label = if (list.isEmpty()) "Privat" else "Profil ${list.size + 1}")
        settings.senderProfiles = list + p
        if (list.isEmpty()) settings.activeSenderId = p.id
        publishProfiles()
        return p
    }

    fun updateProfile(p: de.foldki.app.data.SenderProfile) {
        settings.senderProfiles = settings.senderProfiles.map { if (it.id == p.id) p else it }
        publishProfiles()
    }

    fun setActiveProfile(id: String) { settings.activeSenderId = id; publishProfiles() }

    /** Deletes the profile and its signature image. */
    fun deleteProfile(id: String) {
        val rest = settings.senderProfiles.filterNot { it.id == id }
        settings.senderProfiles = rest
        if (settings.activeSenderId == id) settings.activeSenderId = rest.firstOrNull()?.id.orEmpty()
        signatures.delete(id)
        _sigVersion.value++
        publishProfiles()
    }

    fun saveSignature(id: String, drawn: android.graphics.Bitmap): Boolean =
        signatures.save(id, drawn).also { _sigVersion.value++; notify(if (it) "Unterschrift gespeichert" else "Keine Unterschrift erkannt") }

    fun deleteSignature(id: String) { signatures.delete(id); _sigVersion.value++ }

    private fun editMessage(convId: String, msgId: String, f: (ChatMessage) -> ChatMessage) {
        _ui.update { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c else c.copy(messages = c.messages.map { if (it.id == msgId) f(it) else it })
            })
        }
    }

    private fun upsert(conv: Conversation) {
        _ui.update { s -> s.copy(conversations = listOf(conv) + s.conversations.filterNot { it.id == conv.id }) }
        persist()
    }

    /** One writer at a time, in order, so an older snapshot can never overwrite a newer one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    private fun persist() {
        val snapshot = _ui.value.conversations
        viewModelScope.launch(saveDispatcher) { runCatching { store.save(snapshot) } }
    }
}
