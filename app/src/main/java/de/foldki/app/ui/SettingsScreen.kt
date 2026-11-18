package de.foldki.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
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
fun SettingsScreen(vm: ChatViewModel, ui: UiState, onBack: () -> Unit) {
    var key by remember { mutableStateOf(vm.settings.apiKey) }
    var model by remember { mutableStateOf(vm.settings.claudeModel) }
    var system by remember { mutableStateOf(vm.settings.systemPrompt) }
    var ghToken by remember { mutableStateOf(vm.githubToken) }
    var rate by remember { mutableFloatStateOf(vm.settings.ttsRate) }
    var stVoice by remember { mutableStateOf(vm.settings.stVoice) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
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

    val back: () -> Unit = { saveAll(); if (page == "hf") page = "models" else if (page != null) page = null else onBack() }
    BackHandler { back() }

    val title = when (page) {
        "nano" -> "Gemini Nano"; "models" -> "KI-Modelle"; "hf" -> "Hugging Face"; "claude" -> "Claude (online)"; "github" -> "GitHub"
        "sender" -> "Absender & Unterschrift"; "voice" -> "Stimme"; "brief" -> "Tagesstart"
        "system" -> "Systemanweisung"; "memory" -> "Gedächtnis"; "report" -> "Fehlerbericht"
        else -> "Einstellungen"
    }

    Surface(color = FK.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = FK.Text) }
                Spacer(Modifier.width(4.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1)
            }
            // The Hugging Face browser needs the whole height (a web page scrolls itself).
            if (page == "hf") Box(Modifier.weight(1f).fillMaxWidth()) { HfBrowser(vm, ui, onDone = { page = "models" }) }
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                ) {
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

    Group {
        SettingRow(
            title = active?.name?.ifBlank { active.title } ?: "Absender anlegen",
            subtitle = if (active == null) "Für Briefe: Name, Adresse, Unterschrift"
                else "Absender aktiv · " + if (vm.signatures.exists(active.id)) "Unterschrift gespeichert" else "keine Unterschrift",
            leading = { Avatar(active?.effectiveInitials ?: "", 34.dp) },
            onClick = { open("sender") },
        )
    }

    SectionLabel("KI-Modelle")
    Group {
        SettingRow(
            "Modell",
            ui.activeName + when {
                ui.modelOp is LocalModelManager.State.Downloading -> " · lädt … ${(ui.modelOp as LocalModelManager.State.Downloading).pct} %"
                ui.localModels.isEmpty() -> " · noch keins geladen"
                else -> " · ${ui.localModels.size} auf dem Handy"
            },
            leading = { Tile(Icons.Default.Diamond, FK.T3) }, onClick = { open("models") },
        ) {
            if (ui.modelReady) Tag("bereit", on = true) else Chevron()
        }
        GroupDivider()
        SettingRow("Gemini Nano", if (ui.usesNano) "gewählt · im Handy eingebaut" else "im Handy eingebaut · nur wenn gewählt",
            leading = { Tile(Icons.Default.PhoneAndroid, FK.T1) }, onClick = { open("nano") })
    }

    SectionLabel("Zugänge")
    Group {
        SettingRow(
            "GitHub",
            when {
                ghToken.isBlank() -> "Nicht verbunden · nur mit Freigabe"
                login.isNotBlank() -> "Verbunden als $login · nur mit Freigabe"
                else -> "Token eingetragen · nur mit Freigabe"
            },
            leading = { Tile(Icons.Default.Code, FK.T6) }, onClick = { open("github") },
        )
    }

    SectionLabel("Briefe & Stimme")
    Group {
        SettingRow(
            "Briefe",
            if (ui.letters) "An: Brief- und PDF-Regeln und das Datum gehen mit" else "Aus: es wird nichts vorgeladen",
            leading = { Tile(Icons.Default.MailOutline, FK.T5) },
        ) { FkToggle(ui.letters, { vm.setLetters(it) }) }
        GroupDivider()
        SettingRow("Absender & Unterschrift", if (list.size == 1) "1 Profil" else "${list.size} Profile",
            leading = { Tile(Icons.Default.Draw, FK.T5) }, onClick = { open("sender") })
        GroupDivider()
        SettingRow(
            "Stimme",
            if (stReady) (Supertonic.VOICES[stVoice]?.second?.substringBefore(" (") ?: stVoice) + " · Tempo " + "%.1f".format(java.util.Locale.GERMANY, rate) + "×"
            else "Noch nicht eingerichtet",
            leading = { Tile(Icons.AutoMirrored.Filled.VolumeUp, FK.T4) }, onClick = { open("voice") },
        )
        GroupDivider()
        SettingRow("Tagesstart", if (brief) "an · morgens 5–11 Uhr" else "aus",
            leading = { Tile(Icons.Default.WbSunny, FK.T1) }, onClick = { open("brief") }) {
            FkToggle(brief, { on -> brief = on; vm.setDailyBrief(on) })
        }
    }

    SectionLabel("Allgemein")
    Group {
        SettingRow("Systemanweisung", if (system.trim() == Settings.DEFAULT_SYSTEM.trim()) "Standard" else "Eigene Anweisung",
            leading = { Tile(Icons.Default.Edit, FK.T6) }, onClick = { open("system") })
        GroupDivider()
        SettingRow("Gedächtnis", if (mem.isEmpty()) "noch nichts gemerkt" else "${mem.size} Einträge",
            leading = { Tile(Icons.Default.Psychology, FK.T6) }, onClick = { open("memory") })
        GroupDivider()
        SettingRow("Fehlerbericht", "Version $version",
            leading = { Tile(Icons.Default.ReportProblem, FK.T6) }, onClick = { open("report") })
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
            "Fold KI arbeitet dafür mit dem gewählten heruntergeladenen Modell auf dem Handy (nicht mit Nano). Kein weiterer Schlüssel nötig.",
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
    )
}

@Composable
private fun VoicePage(
    vm: ChatViewModel, stStatus: String, stBusy: Boolean, stReady: Boolean,
    stVoice: String, setVoice: (String) -> Unit, rate: Float, setRate: (Float) -> Unit,
) {
    PageHeader(
        Icons.AutoMirrored.Filled.VolumeUp, FK.T4, if (stReady) "Eingerichtet" else "Noch nicht eingerichtet",
        "Sprechen und Zuhören laufen komplett auf dem Handy, ohne Samsung- oder Google-Dienste.",
    )
    if (!stReady || stBusy) {
        Spacer(Modifier.height(14.dp))
        Note(stStatus, Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(10.dp))
        if (stBusy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FK.Accent)
            Spacer(Modifier.width(12.dp))
            FkButton("Abbrechen", ghost = true) { vm.cancelVoiceSetup() }
        } else FkButton("Sprachfunktion einrichten (ca. 500 MB)") { vm.setupVoice() }
    }
    SectionLabel("Stimme")
    Group {
        Supertonic.VOICES.entries.forEachIndexed { i, (id, v) ->
            if (i > 0) GroupDivider()
            SettingRow(v.second, null, leading = {
                RadioButton(selected = stVoice == id, onClick = { setVoice(id) }, colors = RadioButtonDefaults.colors(selectedColor = FK.Accent))
            }, onClick = { setVoice(id) }) {
                IconButton(
                    enabled = vm.voice.supertonic.isReady && !stBusy,
                    onClick = { vm.voice.rate = rate; vm.voice.previewSupertonic(id) { vm.notify("Stimme: $it") } },
                ) { Icon(Icons.Default.PlayArrow, "Probehören", tint = FK.Muted) }
            }
        }
    }
    SectionLabel("Tempo")
    Group {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("%.1f".format(java.util.Locale.GERMANY, rate) + "×", fontSize = 15.sp, color = FK.Text)
            Slider(value = rate, onValueChange = setRate, valueRange = 0.6f..1.6f,
                colors = SliderDefaults.colors(thumbColor = FK.Accent, activeTrackColor = FK.Accent, inactiveTrackColor = FK.Line))
        }
    }
    if (stReady && !stBusy) {
        Spacer(Modifier.height(14.dp))
        FkButton("Neu laden / reparieren", ghost = true) { vm.setupVoice() }
    }
}

@Composable
private fun SystemPage(system: String, setSystem: (String) -> Unit) {
    Group {
        FkField("Anweisung an die KI", system, setSystem, minLines = 8)
        FkButton("Auf Standard zurücksetzen", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), ghost = true) {
            setSystem(Settings.DEFAULT_SYSTEM)
        }
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
