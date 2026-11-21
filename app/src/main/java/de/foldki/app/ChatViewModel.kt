package de.foldki.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.foldki.app.ai.GemmaGitHubAgent
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalEngine
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
) {
    val usesNano: Boolean get() = activeModel == Settings.NANO
    val activeLocal: LocalModelManager.LocalModel? get() = localModels.firstOrNull { it.file == activeModel }
    val activeName: String get() = when {
        usesNano -> "Gemini Nano"
        activeLocal != null -> activeLocal!!.name
        else -> "Kein Modell gewählt"
    }
    val modelReady: Boolean get() = if (usesNano) nanoReady else activeLocal != null
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

data class Approval(val title: String, val details: String)

/** Clearly a GitHub task (without a token the app explains how to connect). */
private val GITHUB_STRONG = Regex("github|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bapk\\b", RegexOption.IGNORE_CASE)

/** GitHub tasks (go to the local model with GitHub tools when a token is set). */
private val GITHUB_WORDS = Regex(
    "github|\\brepo(s|sitor(y|ys|ies|ium|ien))?\\b|pull ?request|\\bcommit|\\bbranch|workflow|\\bapk\\b|programmier|quellcode|\\bcode\\b|" +
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

const val READY = "Sprachfunktion ist eingerichtet."

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)
    private val nano = NanoEngine()
    val models = LocalModelManager(app)
    private val llm = LocalEngine(app)
    private val llmBusy = AtomicInteger(0)
    @Volatile private var llmGenerating = false
    private var idleJob: Job? = null
    val attachments = Attachments(app)
    val voice = de.foldki.app.voice.VoiceController(app).apply {
        rate = settings.ttsRate; supertonicVoice = settings.stVoice
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

    fun saveVoice(stVoice: String, rate: Float) {
        settings.stVoice = stVoice; settings.ttsRate = rate
        voice.supertonicVoice = stVoice; voice.rate = rate
    }

    override fun onCleared() { voice.release(); llm.close() }

    private var job: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
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
        models.beforeDelete = { f -> if (llm.loadedPath == f.absolutePath) { if (llmGenerating) stop(); llm.close() } }
        models.onAdded = { file -> selectModel(file); notify("${models.nameOf(file)} ist bereit und ausgewählt") }
        viewModelScope.launch { models.state.collect { st -> _ui.update { it.copy(modelOp = st) } } }
        viewModelScope.launch {
            models.models.collect { list ->
                _ui.update { it.copy(localModels = list) }
                val act = settings.activeModel
                // The chosen model was deleted: no model is chosen (nothing else answers in its place).
                if (act.isNotBlank() && act != Settings.NANO && list.none { it.file == act }) {
                    settings.activeModel = ""; _ui.update { it.copy(activeModel = "") }
                }
            }
        }
        // Too hot: free the model's memory (it loads again with the next question).
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            power.addThermalStatusListener(app.mainExecutor) { status ->
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && !llmGenerating) { llm.close(); _ui.update { it.copy(backend = "") } }
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
        settings.llmCrashed = f
        return "${HfModels.displayName(f)} hat die App beendet – gesperrt, bis du es unter Einstellungen → KI-Modelle wieder auswählst"
    }

    private fun tooHot(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        runCatching { power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)

    // ---- Models ----
    /** Chooses the model that answers ([Settings.NANO] or a file name). */
    fun selectModel(id: String) {
        if (llmGenerating) stop()
        if (settings.llmCrashed == id) settings.llmCrashed = "" // the user picked it again on purpose
        settings.activeModel = id
        _ui.update { it.copy(activeModel = id) }
        if (id == Settings.NANO) { checkNano(); if (llm.loadedPath != null) { llm.close(); _ui.update { it.copy(backend = "") } } } // free the memory for Nano
        else if (llm.loadedPath != null && llm.loadedPath != models.fileOf(id).absolutePath) { llm.close(); _ui.update { it.copy(backend = "") } }
    }

    fun downloadModel(f: HfModels.LmFile) { models.download(f)?.let { notify(it) } }
    fun importModel(uri: android.net.Uri) = models.import(uri)
    fun cancelModelOp() { if (models.state.value is LocalModelManager.State.Importing) models.cancelImport() else models.cancel() }
    fun clearModelError() = models.clearError()
    fun deleteModel(file: String) {
        if (llmGenerating && llm.loadedPath == models.fileOf(file).absolutePath) stop()
        models.delete(file)
    }

    /** Test switch: local models on the CPU instead of the GPU. */
    fun setCpu(cpu: Boolean) {
        if (llmGenerating) stop()
        settings.gemmaBackend = if (cpu) "CPU" else "GPU"
        llm.close() // next answer loads with the chosen backend
        _ui.update { it.copy(backend = "") }
        de.foldki.app.data.DebugLog.write("Rechenart umgestellt auf ${settings.gemmaBackend}")
    }

    fun setLetters(on: Boolean) { settings.letters = on; _ui.update { it.copy(letters = on) } }

    // ---- Long-term memory (on-device only; not sent to the model while all built-in instructions are off) ----
    val memory = de.foldki.app.data.MemoryStore(app)
    private val _memories = MutableStateFlow(memory.all())
    val memories: StateFlow<List<String>> = _memories.asStateFlow()
    fun addMemory(fact: String) { memory.add(fact); _memories.value = memory.all() }
    fun removeMemory(index: Int) { memory.remove(index); _memories.value = memory.all() }

    /** App in the background or memory is low: free the model's memory (it loads again on the next question). */
    fun onBackground() { if (!llmGenerating) { llm.close(); _ui.update { it.copy(backend = "") } } }

    /** Android is short of memory (app in the background): also free the speech recognition model. */
    fun onLowMemory() { voice.trimMemory() }

    private fun llmBegin() { if (llmBusy.incrementAndGet() == 1) { settings.llmAttempt = settings.activeModel; settings.llmLoading = true } }
    private fun llmEnd() { if (llmBusy.decrementAndGet() <= 0) { llmBusy.set(0); settings.llmLoading = false } }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch { delay(5 * 60_000L); if (!llmGenerating) { llm.close(); _ui.update { it.copy(backend = "") } } }
    }

    private fun freeRamMb(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_000_000
    }.getOrDefault(-1L)

    private fun noModelText(): String =
        "Es ist kein Modell gewählt. Bitte unter Einstellungen → KI-Modelle ein Modell herunterladen oder auswählen."

    /** Loads the chosen local model if needed (max. 3 min). null = ready, else the reason (German). */
    private suspend fun ensureLoaded(): String? {
        val file = settings.activeModel
        if (file.isBlank() || file == Settings.NANO) return noModelText()
        if (!models.exists(file)) return "Das gewählte Modell ist nicht mehr auf dem Handy. " + noModelText()
        val f = models.fileOf(file)
        if (llm.loadedPath == f.absolutePath) return null
        if (settings.llmCrashed == file) return "${models.nameOf(file)} hat die App beim letzten Mal beendet. " +
            "Wähle es unter Einstellungen → KI-Modelle erneut aus, um es nochmal zu versuchen." +
            (if (settings.gemmaBackend == "GPU" && !HfModels.isGpuFile(file) && !file.lowercase().startsWith("gemma")) " Tipp: Diese Datei ist nicht für den Grafikprozessor gebaut. " +
                "Lade unter Erkunden die Variante mit „gpu“ im Dateinamen." else "")
        voice.trimMemory() // speech model out of memory before a big load
        de.foldki.app.data.DebugLog.write("LADE $file · ${settings.gemmaBackend} · ${if (HfModels.isGpuFile(file)) "GPU-Datei" else "keine GPU-Datei"} · " +
            "${LocalModelManager.gb(f.length())} · frei RAM ${freeRamMb()} MB")
        val t0 = System.currentTimeMillis()
        val d = llm.loadAsync(f.absolutePath, settings.gemmaBackend == "GPU")
        llmBegin(); d.invokeOnCompletion { llmEnd() } // stays guarded until the native load really ends
        return try {
            val b = withTimeoutOrNull(180_000) { d.await() } ?: return "Das Modell lädt zu lange (über 3 Minuten). Bitte nochmal fragen."
            de.foldki.app.data.DebugLog.write("Modell geladen: $file · $b · ${(System.currentTimeMillis() - t0) / 1000} s · frei RAM danach ${freeRamMb()} MB")
            _ui.update { it.copy(backend = b) }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            de.foldki.app.data.DebugLog.write("Ladefehler: ${e.stackTraceToString().take(1500)}")
            e.message ?: "Das Modell konnte nicht geladen werden."
        }
    }

    /** Runs [block] with the chosen local model loaded and the crash guard set. Errors are thrown (no other model answers). */
    private suspend fun <T> withLocal(block: suspend () -> T): T {
        if (tooHot()) throw IllegalStateException("Das Handy ist gerade zu heiß. Bitte kurz abkühlen lassen und nochmal fragen.")
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
        // follow-ups ("ja, mach weiter") in a chat that already worked on GitHub
        return history.takeLast(4).any { m ->
            m.role == Role.ASSISTANT && listOf("✅ Freigegeben", "⛔ Abgelehnt", "🔎 Suche:", "📄 Lese ", "📁 ", "⚙️ Build-Status").any { m.text.contains(it) }
        }
    }

    fun saveSettings(apiKey: String, model: String, system: String) {
        settings.apiKey = apiKey
        settings.claudeModel = model
        settings.systemPrompt = system
    }

    fun newChat() { stop(); _ui.update { it.copy(currentId = null) } }

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

    fun stop() { job?.cancel(); job = null; _ui.update { it.copy(generating = false, thinking = false, liveCpu = -1) } }

    /**
     * Sends a question to the chosen model. Without the switch "Briefe" the model gets only the user's own instruction
     * (Einstellungen → Systemanweisung), the chat and the question (plus text of attached files) – no built-in rules.
     * [voice] = asked in voice mode (only used for the error report).
     */
    fun send(text: String, voice: Boolean = false) {
        val pendingFiles = _ui.value.pending
        val prompt = text.trim().ifEmpty { if (pendingFiles.isNotEmpty()) "Was steht in diesem Dokument? Fasse es kurz zusammen." else "" }
        if (prompt.isEmpty() || _ui.value.generating || _ui.value.importing) return
        val web = _ui.value.webSearch
        val act = settings.activeModel
        val useNano = act == Settings.NANO
        val modelName = _ui.value.activeName
        val engine = if (useNano) Engine.NANO else Engine.GEMMA

        // Create conversation if needed and append user + empty assistant message.
        var conv = _ui.value.current ?: Conversation(title = prompt.take(40))
        val userMsg = ChatMessage(role = Role.USER, text = prompt, attachments = pendingFiles)
        val botMsg = ChatMessage(role = Role.ASSISTANT, text = "", engine = engine, web = web, model = modelName)
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
                val system = buildSystem(letters, wantsLetter, followUpDoc = lastDoc != null, history = history)
                logSystem = system
                logHistory = history.size
                if (isGitHubTask(prompt, history)) {
                    // GitHub only with a downloaded model on the phone (Nano cannot use tools).
                    if (useNano || act.isBlank()) {
                        editMessage(conv.id, botMsg.id) {
                            it.copy(text = "Für GitHub-Aufgaben bitte unter Einstellungen → KI-Modelle ein heruntergeladenes Modell wählen " +
                                "(z. B. Gemma 4 E2B). Gemini Nano kann keine Werkzeuge benutzen.")
                        }
                    } else withLocal {
                        GemmaGitHubAgent(llm, settings.githubToken).run(
                            baseSystem = settings.systemPrompt, question = prompt,
                            onText = { t -> editMessage(conv.id, botMsg.id) { it.copy(text = it.text + t) } },
                            approve = { title, details -> askApproval(title, details) },
                        )
                    }
                    finished = true
                    return@launch
                }
                if (settings.githubToken.isBlank() && GITHUB_STRONG.containsMatchIn(prompt)) {
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
                val thinkOpen = !useNano && HfModels.isThinking(act)
                val raw = StringBuilder()
                var pieces = 0
                var tFirst = 0L
                val onPiece: (String) -> Unit = { d ->
                    if (tFirst == 0L) tFirst = System.currentTimeMillis()
                    pieces++
                    raw.append(d)
                    val sp = ThinkParser.split(raw.toString(), thinkOpen, finished = false)
                    if (sp.thinking != _ui.value.thinking) _ui.update { it.copy(thinking = sp.thinking) }
                    editMessage(conv.id, botMsg.id) { it.copy(text = sp.answer, thoughts = sp.thoughts) }
                }
                if (useNano) {
                    if (!_ui.value.nanoReady) checkNano().join()
                    if (!_ui.value.nanoReady) throw IllegalStateException("Gemini Nano ist nicht bereit: ${_ui.value.nanoStatus}")
                    nano.reply(system, history, web).collect { onPiece(it) }
                } else withLocal {
                    // Everything must fit into the model's context – too much input closes the whole app natively.
                    val budget = llm.inputBudgetChars - system.length
                    val webText = if (web) webFor(prompt).take(budget / 4) else ""
                    val turns = history.filter { !it.isError && it.text.isNotBlank() }
                    val last = turns.lastOrNull { it.role == Role.USER } ?: return@withLocal
                    val earlier = turns.subList(0, turns.lastIndexOf(last)).map { turnOf(it, budget / 3) }
                    val room = (budget * 0.8).toInt() - webText.length - last.text.length
                    val own = turnOf(last, room.coerceAtLeast(0))
                    if (last.attachments.any { it.text.length > room.coerceAtLeast(0) })
                        notify("Die Datei ist zu lang für dieses Modell – es liest nur den Anfang (ca. ${room.coerceAtLeast(0)} Zeichen).")
                    val question = (if (webText.isBlank()) "" else "Internetsuche:\n$webText\n\n") + own.text
                    // For the error report: what goes to the model (a native crash is only seen at the next start).
                    de.foldki.app.data.DebugLog.write(
                        "START ${models.nameOf(act)} · ${llm.backendUsed} · Kontext ${llm.contextTokens} Tokens · " +
                            "Platz ${llm.inputBudgetChars} Zeichen · Anweisung ${system.length} · Frage ${question.length} · " +
                            "Verlauf ${earlier.size} Nachrichten (${earlier.sumOf { it.text.length }} Zeichen)",
                    )
                    llm.chat(conv.id, system, earlier, question, last.id, botMsg.id).collect { onPiece(it) }
                }
                val end = ThinkParser.split(raw.toString(), thinkOpen, finished = true)
                val now = System.currentTimeMillis()
                editMessage(conv.id, botMsg.id) {
                    it.copy(
                        text = end.answer, thoughts = end.thoughts,
                        tokens = if (useNano) 0 else pieces, answerMs = now - t0,
                        tokPerSec = if (!useNano && pieces > 1 && now > tFirst) (pieces - 1) * 1000f / (now - tFirst) else 0f,
                        backend = if (useNano) "AICore" else (llm.backendUsed ?: ""),
                        cpuPct = if (useNano) -1 else cpu.average(),
                    )
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
                // Only the still-current job may end "generating" (a stopped job must not end the next one).
                if (job == null || job === coroutineContext[Job]) _ui.update { it.copy(generating = false, thinking = false, liveCpu = -1) }
                persist()
            }
        }
    }

    /** One chat turn for the local model: a question with the text of its attached files, or an earlier answer. */
    private fun turnOf(m: ChatMessage, fileChars: Int): LocalEngine.Turn {
        if (m.role != Role.USER) return LocalEngine.Turn(m.id, false, DocumentParser.display(m.text).take(2000))
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

    /** Web results for the local model (search terms by the model itself, then Wikipedia + news). */
    private suspend fun webFor(question: String): String {
        // No prompt for search terms (from 3.3): the question itself is searched.
        return WebLookup.search(question.take(100))
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

    /** Text that goes into a PDF: letters without a date line get today's date (safety net, no duplicates). */
    fun pdfText(doc: String): String = DocumentParser.ensureDateLine(doc, today(), place = settings.activeSender?.city)

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
