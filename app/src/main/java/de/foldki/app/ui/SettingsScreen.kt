package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.READY
import de.foldki.app.UiState
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.data.Settings
import de.foldki.app.voice.Supertonic

/**
 * Settings (approved design): an overview in groups, each row opens its own page.
 * Everything is saved when a page or the settings are left.
 */
@Composable
fun SettingsScreen(vm: ChatViewModel, ui: UiState, startPage: String? = null, onBack: () -> Unit, onMenu: () -> Unit = onBack) {
    val chrome = remember { PageChrome() }
    var key by remember { mutableStateOf(vm.settings.apiKey) }
    var model by remember { mutableStateOf(vm.settings.claudeModel) }
    var system by remember { mutableStateOf(vm.settings.systemPrompt) }
    var ghToken by remember { mutableStateOf(vm.githubToken) }
    var rate by remember { mutableFloatStateOf(vm.settings.ttsRate) }
    var stVoice by remember { mutableStateOf(vm.settings.stVoice) }
    var page by rememberSaveable(startPage) { mutableStateOf(startPage) }
    var editProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val stStatus by vm.stStatus.collectAsStateWithLifecycle()
    val stBusy by vm.stBusy.collectAsStateWithLifecycle()
    val stReady = stStatus == READY || vm.voice.isReady

    fun saveAll() { vm.saveSettings(key, model, system); vm.saveVoice(stVoice, rate); vm.saveGitHubToken(ghToken) }

    editProfile?.let { id ->
        SenderProfileScreen(vm, id, onBack = { editProfile = null })
        return
    }
    DisposableEffect(Unit) { onDispose { vm.voice.stopSpeaking() } }

    // Opened directly from the menu: back closes the page; inside the settings: back to the overview.
    val back: () -> Unit = {
        saveAll()
        when {
            page == startPage -> onBack()
            page == "hf" && startPage != "hf" -> page = "models"
            page != null -> page = startPage
            else -> onBack()
        }
    }
    BackHandler { chrome.back?.invoke() ?: back() }

    val title = when (page) {
        "nano" -> "Gemini Nano"; "models" -> "Modelle"; "hf" -> "Erkunden"; "claude" -> "Claude (online)"; "github" -> "GitHub"
        "sender" -> "Absender & Unterschrift"; "voice" -> "Stimmen"; "brief" -> "Tagesstart"
        "system" -> "Systemanweisung"; "memory" -> "Gedächtnis"; "report" -> "Fehlerbericht"
        "server" -> "PC-Server"; "history" -> "Verlauf"; "saved" -> "Verlauf"; "s3" -> "S3-Sicherung"; "compute" -> "Rechnen auf"; "gpu" -> "GPU-Anteil"; "instructions" -> "Meine Anweisungen"; "tools" -> "Werkzeuge"; "mcp" -> "MCP-Werkzeuge"
        else -> "Einstellungen"
    }

    Surface(color = FK.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar like the mock-ups: ☰ on pages from the menu, ‹ on sub pages, ✕ on "PC-Server"; the page's action on the right.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val override = chrome.back
                when {
                    override != null -> NavBtn(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", onClick = override)
                    page == "server" && page == startPage -> NavBtn(Icons.Default.Close, "Schließen") { back() }
                    page == startPage -> NavBtn(Icons.Default.Menu, "Menü") { saveAll(); onMenu() }
                    else -> NavBtn(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") { back() }
                }
                Spacer(Modifier.weight(1f))
                chrome.action?.invoke()
            }
            if (!chrome.hideTitle) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) {
                LargeTitle(title, Modifier.widthIn(max = 720.dp).fillMaxWidth())
            }
            // The Hugging Face browser needs the whole height (a web page scrolls itself).
            if (page == "hf") Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalPageChrome provides chrome) { HfBrowser(vm, ui, onDone = { page = "models" }) }
            }
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 30.dp),
                ) {
                  androidx.compose.runtime.CompositionLocalProvider(LocalPageChrome provides chrome) {
                    when (page) {
                        null -> Overview(vm, ui, key, model, ghToken, stReady, stVoice, rate, system) { page = it }
                        "nano" -> NanoPage(vm, ui)
                        "models" -> ModelSettings(vm, ui, openBrowser = { page = "hf" }, openNano = { page = "nano" })
                                                "github" -> GitHubPage(vm, ghToken, { ghToken = it })
                        "sender" -> Group { Column(Modifier.padding(12.dp)) { SenderProfilesList(vm, onEdit = { editProfile = it }) } }
                        "voice" -> VoicePage(vm, stStatus, stBusy, stReady, stVoice, { stVoice = it }, rate, { rate = it })
                        "brief" -> Group { Column(Modifier.padding(16.dp)) { BriefSettings(vm) } }
                        "system" -> SystemPage(system, { system = it })
                        "memory" -> MemoryPage(vm)
                        "report" -> ReportPage(vm)
                        "history" -> HistoryPage(vm, ui, saved = false, onOpen = onBack)
                        "saved" -> HistoryPage(vm, ui, saved = true, onOpen = onBack)
                        "instructions" -> InstructionsPage(vm, ui)
                        "server" -> ServerPage(vm, ui)
                        "tools" -> ToolsPage(vm, ui, ghToken) { page = it }
                        "mcp" -> McpPage(vm)
                        "s3" -> S3Page(vm)
                        "compute" -> ComputePage(vm, ui)
                        "gpu" -> GpuPage(vm)
                    }
                  }
                }
            }
        }
    }
}

@Composable
private fun Overview(
    vm: ChatViewModel, ui: UiState, key: String, model: String, ghToken: String, stReady: Boolean,
    stVoice: String, rate: Float, system: String, open: (String) -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    vm.sigVersion.collectAsStateWithLifecycle().value
    val (list, activeId) = profiles
    val active = list.firstOrNull { it.id == activeId }
    var brief by remember { mutableStateOf(vm.settings.dailyBriefEnabled) }
    val login by vm.githubLogin.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val mem by vm.memories.collectAsStateWithLifecycle()

    // Exactly like mock-up 15 (design/15-einstellungen.png).
    SectionLabel("KI")
    Group {
        SettingRow("Systemanweisung", null, leading = { Tile(Icons.Default.Person, FK.T4) }, onClick = { open("system") }) {
            RowValue(if (system.isBlank()) "leer" else "eigene"); Chevron()
        }
        GroupDivider()
        SettingRow("Rechnen auf", null, leading = { Tile(Icons.Default.Memory, FK.T3) }, onClick = { open("compute") }) {
            RowValue(vm.settings.gemmaBackend); Chevron()
        }
        GroupDivider()
        SettingRow("GPU-Anteil (GGUF)", null, leading = { Tile(Icons.Default.Memory, FK.T1) }, onClick = { open("gpu") }) {
            RowValue("${vm.settings.gpuPercent} %"); Chevron()
        }
    }
    SectionLabel("Briefe")
    Group {
        SettingRow("Absenderprofile", null, leading = { Tile(Icons.Default.Description, FK.T1) }, onClick = { open("sender") }) {
            RowValue("${list.size}"); Chevron()
        }
        GroupDivider()
        SettingRow("Unterschrift", null, leading = { Tile(Icons.Default.Edit, FK.T8) }, onClick = { open("sender") }) { Chevron() }
    }
    SectionLabel("Verbindungen")
    Group {
        SettingRow("GitHub", null, leading = { Tile(Icons.Default.Code, FK.T9) }, onClick = { open("github") }) {
            RowValue(if (ghToken.isBlank()) "nicht verbunden" else "verbunden"); Chevron()
        }
        GroupDivider()
        SettingRow("S3-Sicherung", null, leading = { Tile(Icons.Default.Cloud, FK.T2) }, onClick = { open("s3") }) {
            RowValue(if (vm.settings.s3Auto) "an" else "aus"); Chevron()
        }
    }
    SectionLabel("Hilfe")
    Group {
        SettingRow("Fehlerbericht", null, leading = { Tile(Icons.Default.Build, FK.T5) }, onClick = { open("report") }) { Chevron() }
        GroupDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Version", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, modifier = Modifier.weight(1f))
            RowValue(version)
        }
    }
}

/** "Rechnen auf": GPU (Standard) or CPU for the models on the phone. */
@Composable
private fun ComputePage(vm: ChatViewModel, ui: UiState) {
    var b by remember { mutableStateOf(vm.settings.gemmaBackend) }
    Sub("Wo die Modelle auf dem Handy rechnen. GPU ist schneller und bleibt kühler.")
    Group {
        listOf("GPU" to "Grafikprozessor (Adreno)", "CPU" to "Prozessor – nur zum Testen").forEachIndexed { i, (k, d) ->
            if (i > 0) GroupDivider()
            SettingRow(k, d, leading = { Tile(Icons.Default.Memory, if (k == "GPU") FK.T3 else FK.T6) }, onClick = { b = k; vm.setCpu(k == "CPU") }) {
                if (b == k) Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** "GPU-Anteil (GGUF)": share of the layers of GGUF models on the GPU. */
@Composable
private fun GpuPage(vm: ChatViewModel) {
    var pct by remember { mutableStateOf(vm.settings.gpuPercent.toFloat()) }
    Sub("Anteil der Modell-Schichten von GGUF-Modellen (llama.cpp) auf dem Grafikprozessor. Startwert 75 %.")
    Group {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row {
                Text("GPU-Anteil", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, modifier = Modifier.weight(1f))
                RowValue(if (pct.toInt() == 0) "nur Prozessor" else "${pct.toInt()} %")
            }
            FkSlider(pct, { pct = (it / 5).toInt() * 5f }, 0f..100f, onDone = { vm.setGpuPercent(pct.toInt()) })
        }
    }
}

/** "Verlauf" (all chats) or "Gemerkt" (saved answers) – exactly like mock-up 16. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun HistoryPage(vm: ChatViewModel, ui: UiState, saved: Boolean, onOpen: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (saved) 1 else 0) }
    var confirmDelete by remember { mutableStateOf<de.foldki.app.data.Conversation?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    confirmDelete?.let { c -> DeleteChatDialog(c, onDelete = { vm.delete(c.id); confirmDelete = null }, onDismiss = { confirmDelete = null }) }
    if (confirmAll) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmAll = false },
        containerColor = FK.Surface,
        title = { Text("Alle Chats löschen?", color = FK.Text) },
        text = { Text("Alle ${ui.conversations.size} Chats und ihre Dateien werden vom Handy gelöscht. Das lässt sich nicht rückgängig machen. Gemerkte Antworten bleiben.", color = FK.Muted) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.deleteAll(); confirmAll = false }) { Text("Alle löschen", color = FK.Danger) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAll = false }) { Text("Abbrechen", color = FK.Accent) } },
    )
    var q by rememberSaveable { mutableStateOf("") }
    Segmented(listOf("Chats", "Gemerkt ☆"), tab, Modifier.padding(bottom = 14.dp)) { tab = it }
    SearchBox(q, { q = it }, if (tab == 0) "Chats durchsuchen" else "Gemerktes durchsuchen", Modifier.padding(bottom = 12.dp))
    val needle = q.trim().lowercase()
    if (tab == 0) {
        val chats = ui.conversations.filter { c -> needle.isEmpty() || c.title.lowercase().contains(needle) || c.messages.any { it.text.lowercase().contains(needle) } }
        if (chats.isEmpty()) Sub("Noch keine Chats.")
        chats.groupBy { dayLabel(it.updatedAt) }.entries.forEachIndexed { gi, (day, list) ->
            SectionLabel(day, top = if (gi == 0) 4.dp else 18.dp)
            Group {
                list.forEachIndexed { i, c ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = FK.Line)
                    val model = c.messages.lastOrNull { it.model != null }?.model.orEmpty()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .combinedClickable(onClick = { vm.open(c.id); onOpen() }, onLongClick = { confirmDelete = c }, onLongClickLabel = "Löschen")
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(listOf(model, timeLabel(c.updatedAt)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = FK.Muted, maxLines = 1)
                        }
                        Spacer(Modifier.width(12.dp))
                        Chevron()
                    }
                }
            }
        }
        if (ui.conversations.isNotEmpty()) {
            Note("Lange auf einen Chat drücken, um ihn zu löschen.", Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp))
            Spacer(Modifier.height(14.dp))
            Cta("Alle Chats löschen", ghost = true, color = FK.Danger) { confirmAll = true }
        }
    } else {
        val list = ui.saved.filter { needle.isEmpty() || it.text.lowercase().contains(needle) }
        if (list.isEmpty()) Sub("Noch nichts gemerkt. Tippe unter einer Antwort auf ☆ Merken.")
        list.forEach { a ->
            Card(Modifier.padding(bottom = 12.dp)) {
                Text(a.text.take(600), fontSize = 15.sp, color = FK.Text, lineHeight = 22.sp)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOf(a.model, timeLabel(a.time)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = FK.Muted, modifier = Modifier.weight(1f), maxLines = 1)
                    Cta("Kopieren", ghost = true) {
                        (vm.getApplication<android.app.Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Text", a.text))
                        vm.notify("Kopiert")
                    }
                    Spacer(Modifier.width(8.dp))
                    Cta("Löschen", ghost = true, color = FK.Danger) { vm.removeSaved(a.id) }
                }
            }
        }
    }
}

/** "17:40" today, "Di" this week, else "12.11.". */
private fun timeLabel(t: Long): String {
    val label = dayLabel(t)
    val de = java.util.Locale.GERMANY
    return when (label) {
        "Heute" -> java.text.SimpleDateFormat("HH:mm", de).format(java.util.Date(t))
        "Gestern", "Diese Woche" -> java.text.SimpleDateFormat("EE", de).format(java.util.Date(t)).removeSuffix(".")
        else -> java.text.SimpleDateFormat("dd.MM.", de).format(java.util.Date(t))
    }
}

private fun dayLabel(t: Long): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val day = 24L * 3600 * 1000
    return when { t >= cal.timeInMillis -> "Heute"; t >= cal.timeInMillis - day -> "Gestern"; t >= cal.timeInMillis - 6 * day -> "Diese Woche"; else -> "Älter" }
}

/** "Meine Anweisungen" exactly like mock-up 5. Tap = use it, long press = edit, + (top right) = new. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InstructionsPage(vm: ChatViewModel, ui: UiState) {
    var edit by remember { mutableStateOf<de.foldki.app.data.Instruction?>(null) }
    edit?.let { e -> InstructionEditor(vm, ui, e) { edit = null }; return }
    PageAction { NavBtn(Icons.Default.Add, "Neue Anweisung", bg = FK.Accent, tint = androidx.compose.ui.graphics.Color.White) { edit = de.foldki.app.data.Instruction(name = "", text = "") } }
    var appCtx by remember { mutableStateOf(vm.settings.appContext) }
    Sub("Deine eigenen Anweisungen – pro Chat eine wählen. Dazu gibt die App auf Wunsch das heutige Datum, Deutsch als Sprache und ihre Werkzeuge mit.")
    Group {
        SettingRow("Datum & App-Wissen mitgeben", "heutiges Datum, Deutsch, was die App kann (PDF, Websuche, GitHub, MCP)", leading = { Tile(Icons.Default.Psychology, FK.T4) }) {
            FkToggle(appCtx, { on -> appCtx = on; vm.settings.appContext = on })
        }
    }
    Spacer(Modifier.height(14.dp))
    if (ui.instructions.isNotEmpty()) Group {
        ui.instructions.forEachIndexed { i, ins ->
            if (i > 0) GroupDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(onClick = { vm.setChatInstruction(ins.id) }, onLongClick = { edit = ins }, onLongClickLabel = "Bearbeiten")
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(FK.Surface2), contentAlignment = Alignment.Center) {
                    Text(ins.emoji, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ins.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1)
                    Text("„" + ins.text.replace("\n", " ").take(40) + (if (ins.text.length > 40) " …“" else "“"), fontSize = 13.sp, color = FK.Muted, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                if (ui.newInstruction == ins.id) Pill("Aktiv", FK.Ok) else Chevron()
            }
        }
    }
    SectionLabel("Ohne Anweisung")
    Group {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.setChatInstruction("") }.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keine", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text)
                Text(if (appCtx) "Das Modell bekommt deine Frage und das App-Wissen" else "Das Modell bekommt nur deine Frage", fontSize = 13.sp, color = FK.Muted)
            }
            if (ui.newInstruction.isEmpty()) Pill("Aktiv", FK.Ok) else Chevron()
        }
    }
}

@Composable
private fun InstructionEditor(vm: ChatViewModel, ui: UiState, e: de.foldki.app.data.Instruction, onDone: () -> Unit) {
    var name by remember(e.id) { mutableStateOf(e.name) }
    var emoji by remember(e.id) { mutableStateOf(e.emoji) }
    var text by remember(e.id) { mutableStateOf(e.text) }
    val exists = ui.instructions.any { it.id == e.id }
    PageBack(onDone)
    PageAction { Cta("Sichern", enabled = name.isNotBlank() && text.isNotBlank()) { vm.saveInstruction(e.copy(name = name.trim(), emoji = emoji.ifBlank { "💬" }, text = text.trim())); onDone() } }
    Sub(if (exists) "Anweisung bearbeiten." else "Neue Anweisung. Nur dieser Text geht an die KI.")
    Group {
        FieldRow("Name", name, { name = it }, hint = "z. B. Briefe")
        FieldDivider()
        FieldRow("Symbol", emoji, { emoji = it.take(4) }, hint = "💬")
    }
    SectionLabel("Anweisung an die KI")
    Group { FkField("Text", text, { text = it }, minLines = 6) }
    if (exists) {
        Spacer(Modifier.height(16.dp))
        Cta("Löschen", ghost = true, color = FK.Danger) { vm.deleteInstruction(e.id); onDone() }
    }
}

/** "Werkzeuge" exactly like mock-up 7. Tap on a row opens its settings, the switch turns the tool on/off. */
@Composable
private fun ToolsPage(vm: ChatViewModel, ui: UiState, ghToken: String, open: (String) -> Unit) {
    var brief by remember { mutableStateOf(vm.settings.dailyBriefEnabled) }
    var gh by remember { mutableStateOf(vm.settings.githubEnabled && ghToken.isNotBlank()) }
    var pdf by remember { mutableStateOf(vm.settings.pdfEnabled) }
    Sub("Was die KI für dich tun darf. Änderungen nur nach deiner Freigabe.")
    Group {
        SettingRow("Websuche", "sucht mit deiner Frage", leading = { Tile(Icons.Default.Language, FK.T7) }) { FkToggle(ui.webSearch, { vm.setWeb(it) }) }
        GroupDivider()
        SettingRow("GitHub", "lesen sofort · ändern mit Freigabe", leading = { Tile(Icons.Default.Code, FK.T9) }, onClick = { open("github") }) {
            FkToggle(gh, { on -> if (on && ghToken.isBlank()) open("github") else { gh = on; vm.settings.githubEnabled = on } })
        }
        GroupDivider()
        SettingRow("Briefe & PDF", "Vorschau, Unterschrift, Absender", leading = { Tile(Icons.Default.Description, FK.T1) }, onClick = { open("sender") }) {
            FkToggle(pdf, { on -> pdf = on; vm.setPdfEnabled(on) })
        }
        GroupDivider()
        SettingRow("Tagesstart", "Kalender und offene Punkte", leading = { Tile(Icons.Default.StarBorder, FK.T5) }, onClick = { open("brief") }) {
            FkToggle(brief, { on -> brief = on; vm.setDailyBrief(on) })
        }
    }
    SectionLabel("Erweitert")
    Group {
        val mcpStatus by vm.mcpStatus.collectAsStateWithLifecycle()
        var mcpOn by remember { mutableStateOf(vm.settings.mcpEnabled && vm.settings.mcpUrl.isNotBlank()) }
        SettingRow("MCP-Werkzeuge", mcpStatus, leading = { Tile(Icons.Default.Build, FK.T8) }, onClick = { open("mcp") }) {
            FkToggle(mcpOn, { on -> if (on && vm.settings.mcpUrl.isBlank()) open("mcp") else { mcpOn = on; vm.setMcpEnabled(on) } })
        }
    }
}

/** Big centred status block at the top of a page (like the GitHub page). */
@Composable
private fun PageHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, status: String, note: String) {
    Group {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tile(icon, tone, size = 56.dp, radius = 16.dp, iconSize = 26.dp)
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Note(note, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth())
        }
    }
}

/** Input field of the design: small label above, value below, dark background, thin border. */
@Composable
fun FkField(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, minLines: Int = 1, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            .background(FK.Bg, RoundedCornerShape(14.dp)).border(1.dp, FK.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = FK.Muted)
        BasicTextField(
            value = value, onValueChange = onChange,
            singleLine = minLines == 1, minLines = minLines,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = FK.Text, letterSpacing = if (password) 2.sp else 0.sp),
            cursorBrush = SolidColor(FK.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun NanoPage(vm: ChatViewModel, ui: UiState) {
    PageHeader(
        Icons.Default.PhoneAndroid, FK.T1, if (ui.nanoReady) "Bereit" else ui.nanoStatus,
        "Gemini Nano steckt im Handy (AICore). Es antwortet nur, wenn du es auswählst – es springt nie für ein anderes Modell ein.",
    )
    Spacer(Modifier.height(14.dp))
    if (!ui.usesNano) FkButton("Gemini Nano auswählen") { vm.selectModel(Settings.NANO) }
    else if (ui.nanoReady) Note("Gemini Nano ist ausgewählt.", Modifier.padding(horizontal = 6.dp))
    if (ui.usesNano && !ui.nanoReady) {
        Spacer(Modifier.height(14.dp))
        if (ui.nanoDownloadable) FkButton("Gemini Nano herunterladen") { vm.downloadNano() }
        else FkButton("Erneut prüfen", ghost = true) { vm.checkNano() }
    }
}

@Composable
private fun ClaudePage(key: String, setKey: (String) -> Unit, model: String, setModel: (String) -> Unit) {
    PageHeader(
        Icons.Default.Cloud, FK.T2, if (key.isBlank()) "Kein API-Schlüssel" else "Schlüssel eingetragen",
        "Für Briefe, Code und GitHub über das Internet. Der Schlüssel bleibt nur auf diesem Gerät.",
    )
    SectionLabel("Zugang")
    Group {
        FkField("API-Schlüssel", key, setKey, password = true)
        Note("Erhältlich unter console.anthropic.com.", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
    }
    SectionLabel("Modell")
    Group {
        Settings.MODELS.forEachIndexed { i, (id, label) ->
            if (i > 0) GroupDivider()
            SettingRow(label.substringBefore(" ("), label.substringAfter("(").removeSuffix(")"),
                leading = { Tile(Icons.Default.Cloud, FK.T2) }, onClick = { setModel(id) }) {
                if (model == id) Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** "MCP-Werkzeuge": one own tool server (Streamable HTTP). Read-only tools run at once, all others need "Freigeben". */
@Composable
private fun McpPage(vm: ChatViewModel) {
    val status by vm.mcpStatus.collectAsStateWithLifecycle()
    val checking by vm.mcpChecking.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(vm.settings.mcpName) }
    var url by remember { mutableStateOf(vm.settings.mcpUrl) }
    var token by remember { mutableStateOf(vm.settings.mcpToken) }
    var on by remember { mutableStateOf(vm.settings.mcpEnabled) }
    PageHeader(
        Icons.Default.Build, FK.T8, status,
        "Ein eigener Werkzeug-Server (MCP, Streamable HTTP), z. B. auf deinem PC im WLAN. Das gewählte Modell (nicht Gemini Nano) " +
            "benutzt seine Werkzeuge, wenn du „Werkzeug“, „MCP“ oder den Namen des Servers schreibst.",
    )
    SectionLabel("Server")
    Group {
        FkField("Name (z. B. Mein PC)", name, { name = it })
        FkField("Adresse (z. B. http://192.168.0.10:8000/mcp)", url, { url = it })
        FkField("Token (nur wenn der Server einen braucht)", token, { token = it }, password = true)
        if (checking) Box(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = FK.Accent)
        } else FkButton("Verbinden & prüfen", Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), enabled = url.isNotBlank()) {
            vm.checkMcp(name, url, token); on = true
        }
    }
    SectionLabel("Was darf Fold KI?")
    Group {
        SettingRow("MCP-Werkzeuge benutzen", "an = Werkzeuge stehen dem Modell zur Verfügung", leading = { Tile(Icons.Default.Build, FK.T8) }) {
            FkToggle(on, { v -> if (v && url.isBlank()) vm.notify("Bitte zuerst die Adresse eintragen.") else { on = v; vm.setMcpEnabled(v) } })
        }
        GroupDivider()
        SettingRow("Lesende Werkzeuge", "sofort, ohne Nachfrage", leading = { Tile(Icons.Default.Visibility, FK.T3) }) { FkToggle(true, {}, enabled = false) }
        GroupDivider()
        SettingRow("Alle anderen Werkzeuge", "immer mit deiner Freigabe", leading = { Tile(Icons.Default.Check, FK.T1) }) { FkToggle(true, {}, enabled = false) }
    }
    Note(
        "Adresse und Token bleiben nur auf diesem Handy und gehen nur an diesen Server. Unterstützt wird MCP über HTTP " +
            "(Streamable HTTP); Server, die nur über die Kommandozeile (stdio) laufen, gehen auf dem Handy nicht.",
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
    )
}

@Composable
private fun GitHubPage(vm: ChatViewModel, token: String, setToken: (String) -> Unit) {
    val login by vm.githubLogin.collectAsStateWithLifecycle()
    val checking by vm.githubChecking.collectAsStateWithLifecycle()
    PageHeader(
        Icons.Default.Code, FK.T6,
        when { token.isBlank() -> "Nicht verbunden"; login.isNotBlank() -> "Verbunden als $login"; else -> "Token eingetragen – noch nicht geprüft" },
        "Das gewählte Modell kann auf deinem Handy suchen, Code lesen, Repos anlegen, committen und Builds starten. Jede Änderung zeigt die App dir vorher.",
    )
    SectionLabel("Zugang")
    Group {
        FkField("GitHub-Token", token, setToken, password = true)
        if (checking) Box(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = FK.Accent)
        } else FkButton("Verbinden & prüfen", Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), enabled = token.isNotBlank()) {
            vm.checkGitHub(token)
        }
    }
    SectionLabel("Was darf Fold KI?")
    Group {
        SettingRow("Lesen & suchen", "sofort, ohne Nachfrage", leading = { Tile(Icons.Default.Visibility, FK.T3) }) {
            FkToggle(true, {}, enabled = false)
        }
        GroupDivider()
        SettingRow("Änderungen", "immer mit deiner Freigabe", leading = { Tile(Icons.Default.Check, FK.T1) }) {
            FkToggle(true, { vm.notify("Änderungen brauchen immer deine Freigabe.") })
        }
    }
    Note(
        "Token anlegen: github.com → Settings → Developer settings → Personal access tokens. Rechte: Contents, Pull requests, " +
            "Issues, Actions, Workflows (Lesen und Schreiben), Administration (zum Anlegen von Repos). " +
            "Fold KI arbeitet dafür mit dem gewählten Modell – LiteRT, GGUF (auch auf der NPU) oder dem PC-Server, nicht mit Gemini Nano. Kein weiterer Schlüssel nötig.",
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
    )
}

/** "Stimmen" exactly like mock-up 4 (Supertonic 3: Tessa & Caleb only, Whisper for input). */
@Composable
private fun VoicePage(
    vm: ChatViewModel, stStatus: String, stBusy: Boolean, stReady: Boolean,
    stVoice: String, setVoice: (String) -> Unit, rate: Float, setRate: (Float) -> Unit,
) {
    var showRate by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(vm.settings.autoSpeak) }
    Sub("Vorlesen mit Supertonic 3 – offline auf dem Handy.")
    Group {
        Supertonic.VOICES.entries.forEachIndexed { i, (id, v) ->
            if (i > 0) GroupDivider()
            val female = id == "tessa"
            Row(
                Modifier.fillMaxWidth().clickable { setVoice(id) }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            if (female) listOf(androidx.compose.ui.graphics.Color(0xFFFF375F), FK.Warn) else listOf(FK.Accent, FK.T7.first),
                        ),
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text(if (female) "T" else "C", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (female) "Tessa" else "Caleb", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text)
                    Text(if (female) "weiblich · Standard" else "männlich", fontSize = 13.sp, color = FK.Muted)
                }
                Box(
                    Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(FK.Surface2)
                        .clickable(enabled = vm.voice.supertonic.isReady && !stBusy) { vm.voice.rate = rate; vm.voice.previewSupertonic(id) { vm.notify("Stimme: $it") } },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, "Probehören", tint = FK.Accent, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (stVoice == id) Text("✓", color = FK.Accent, fontSize = 20.sp) }
            }
        }
    }
    SectionLabel("Sprechen")
    Group {
        SettingRow("Tempo", null, onClick = { showRate = !showRate }) {
            RowValue("%.1f".format(java.util.Locale.GERMANY, rate) + "×"); Chevron()
        }
        if (showRate) Box(Modifier.padding(horizontal = 16.dp)) { FkSlider(rate, setRate, 0.6f..1.6f) }
        GroupDivider()
        SettingRow("Antworten automatisch vorlesen", null) { FkToggle(auto, { on -> auto = on; vm.setAutoSpeak(on) }) }
    }
    SectionLabel("Spracheingabe")
    Group {
        SettingRow("Whisper small", "Erkennung auf dem Handy", leading = { Tile(Icons.Default.Mic, FK.Danger to androidx.compose.ui.graphics.Color.White) }) {
            if (stReady) Pill("Installiert", FK.Ok) else Pill("Fehlt", FK.Warn)
        }
    }
    SectionLabel("Modell")
    Group {
        SettingRow("Supertonic 3", if (stReady) "250 MB · eingerichtet" else if (stBusy) stStatus else "noch nicht eingerichtet") {
            when {
                stBusy -> { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FK.Accent); Spacer(Modifier.width(8.dp)); Cta("Abbrechen", ghost = true) { vm.cancelVoiceSetup() } }
                stReady -> Pill("Bereit", FK.Ok)
                else -> Cta("Einrichten") { vm.setupVoice() }
            }
        }
    }
}

@Composable
private fun SystemPage(system: String, setSystem: (String) -> Unit) {
    Group {
        FkField("Anweisung an die KI", system, setSystem, minLines = 8)
        Note("Nur dieser Text geht als Anweisung an die KI – die App fügt nichts hinzu. Leer = keine Anweisung.",
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        FkButton("Leeren", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), ghost = true) { setSystem("") }
    }
}

@Composable
private fun MemoryPage(vm: ChatViewModel) {
    val mem by vm.memories.collectAsStateWithLifecycle()
    Note(
        "Was du der KI gesagt hast („Merk dir: …“, „Ich heiße …“). Bleibt auf dem Handy. Zurzeit wird es nicht an die KI geschickt – es gehen keine eingebauten Texte mit.",
        Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
    )
    if (mem.isNotEmpty()) {
        SectionLabel("Gemerkt")
        Group {
            mem.forEachIndexed { i, f ->
                if (i > 0) GroupDivider()
                SettingRow(f, null, leading = { Tile(Icons.Default.Psychology, FK.T6) }) {
                    Text("Löschen", color = FK.Accent, fontSize = 14.sp, modifier = Modifier.clickable { vm.removeMemory(i) }.padding(8.dp))
                }
            }
        }
    }
    SectionLabel("Selbst hinzufügen")
    var newFact by remember { mutableStateOf("") }
    Group {
        FkField("Neuer Eintrag", newFact, { newFact = it })
        FkButton("Merken", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), enabled = newFact.isNotBlank()) {
            vm.addMemory(newFact); newFact = ""
        }
    }
}

@Composable
private fun ReportPage(vm: ChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    PageHeader(
        Icons.Default.ReportProblem, FK.T6, "Version $version",
        "Zeigt dem Entwickler, was die KI bekommen und geantwortet hat, welches Modell lief und wo Fehler waren. " +
            "Bleibt auf dem Handy, bis du ihn teilst. Enthält keinen API-Schlüssel.",
    )
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FkButton("Fehlerbericht kopieren") {
            runCatching { de.foldki.app.data.DebugLog.copy(ctx, version) }
            vm.notify("Fehlerbericht kopiert – jetzt einfach im Chat einfügen")
        }
        FkButton("Als Datei teilen", ghost = true) { runCatching { de.foldki.app.data.DebugLog.share(ctx, version) } }
        FkButton("Bericht leeren", ghost = true) { de.foldki.app.data.DebugLog.clear(); vm.notify("Fehlerbericht geleert") }
    }
}


/** Things a page puts into the top bar of [SettingsScreen] (like the mock-ups): its action on the right, an own back step. */
class PageChrome {
    var action by mutableStateOf<(@Composable () -> Unit)?>(null)
    var back by mutableStateOf<(() -> Unit)?>(null)
    var hideTitle by mutableStateOf(false)
}

val LocalPageChrome = androidx.compose.runtime.staticCompositionLocalOf { PageChrome() }

/** Puts [content] on the right of the top bar while the calling page is shown. */
@Composable
fun PageAction(content: @Composable () -> Unit) {
    val c = LocalPageChrome.current
    androidx.compose.runtime.SideEffect { c.action = content }
    DisposableEffect(Unit) { onDispose { c.action = null } }
}

/** While shown: the ‹ button and the back gesture do [onBack] instead, and the large title is hidden if [hideTitle]. */
@Composable
fun PageBack(onBack: () -> Unit, hideTitle: Boolean = false) {
    val c = LocalPageChrome.current
    androidx.compose.runtime.SideEffect { c.back = onBack; c.hideTitle = hideTitle }
    DisposableEffect(Unit) { onDispose { c.back = null; c.hideTitle = false } }
}
