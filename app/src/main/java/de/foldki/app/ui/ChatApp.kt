package de.foldki.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.data.Attachment
import de.foldki.app.data.ChatMessage
import de.foldki.app.data.Conversation
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.data.Settings
import de.foldki.app.export.DocumentParser
import de.foldki.app.export.PdfExporter
import de.foldki.app.voice.VoiceController
import kotlinx.coroutines.launch

@Composable
fun ChatApp(vm: ChatViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Daily briefing: checked each time the app comes to the foreground (only 5–11 o'clock, once a day).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.onAppResumed(); onPauseOrDispose { } }
    var showSettings by remember { mutableStateOf(false) }

    // Divider position for the unfolded layout, restored from the last session (stored on the device only).
    val split = rememberSplitPaneState(
        when (vm.settings.splitCollapse) {
            PaneCollapse.FIRST.name -> 0f
            PaneCollapse.SECOND.name -> 1f
            else -> vm.settings.splitFraction
        }
    )

    val preview by vm.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // "Speichern" in the preview: the user picks the place, the previewed file is copied unchanged.
    var saveFile by rememberSaveable { mutableStateOf<String?>(null) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = saveFile?.let { java.io.File(it) }
        saveFile = null
        if (uri != null && f != null) {
            runCatching { PdfExporter.copyTo(context, f, uri) }
                .onSuccess { vm.notify("PDF gespeichert") }
                .onFailure { vm.notify("PDF konnte nicht gespeichert werden: ${it.message}") }
        }
    }
    val previewPane: @Composable (ChatViewModel.PdfPreviewState) -> Unit = { st ->
        PdfPreview(
            vm, st,
            onSave = { f, name -> saveFile = f.absolutePath; runCatching { savePdf.launch(name) }.onFailure { vm.notify("Speichern nicht möglich.") } },
            onShare = { f -> runCatching { PdfExporter.shareFile(context, f) }.onFailure { vm.notify("Teilen fehlgeschlagen: ${it.message}") } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(FK.Bg)) {
        val wide = maxWidth >= 600.dp // Fold7 unfolded / tablets: two panes with a draggable divider
        if (wide) {
            val openSettings = { split.expandFirst(); showSettings = true }
            val paneScope = rememberCoroutineScope()
            val sidebarClosed by remember { derivedStateOf { split.collapse == PaneCollapse.FIRST } }
            SplitPane(
                state = split,
                modifier = Modifier.fillMaxSize(),
                onSettled = { f, c -> vm.settings.splitFraction = f; vm.settings.splitCollapse = c.name },
                first = {
                    Surface(color = FK.Side, modifier = Modifier.fillMaxSize()) {
                        if (showSettings) SettingsScreen(vm, ui, onBack = { showSettings = false })
                        else Sidebar(ui, vm, onSettings = openSettings, onPicked = {}, onCollapse = { split.toggleFirst(paneScope) })
                    }
                },
                second = {
                    preview?.let { previewPane(it) } ?: ChatScreen(
                        ui, vm, showMenu = false, onMenu = {}, onSettings = openSettings,
                        sidebar = SidebarToggle(open = !sidebarClosed, toggle = { split.toggleFirst(paneScope) }),
                    )
                },
            )
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = FK.Side, modifier = Modifier.widthIn(max = 320.dp)) {
                        Sidebar(ui, vm, onSettings = { showSettings = true }, onPicked = { scope.launch { drawer.close() } }, onCollapse = null)
                    }
                },
            ) {
                ChatScreen(ui, vm, showMenu = true, onMenu = { scope.launch { drawer.open() } }, onSettings = { showSettings = true })
            }
        }

        // Folded: settings slide in as a full-screen page; back arrow or back gesture returns to the chat.
        AnimatedVisibility(
            visible = showSettings && !wide,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SettingsScreen(vm, ui, onBack = { showSettings = false })
        }

        // Folded: the PDF preview is a full-screen page on top.
        if (!wide) preview?.let { previewPane(it) }
    }
}

// ---------------------------------------------------------------- Sidebar (image 6)

private enum class ChatKind { LETTER, DOC, CODE, TALK }

private fun kindOf(c: Conversation): ChatKind {
    val gh = c.messages.any { m -> m.role == Role.ASSISTANT && listOf("✅ Freigegeben", "🔎 Suche:", "📄 Lese ", "✍️ Commit").any { m.text.contains(it) } }
    if (gh) return ChatKind.CODE
    val letter = c.messages.any { m -> m.role == Role.ASSISTANT && DocumentParser.parse(m.text).document != null }
    if (letter) return ChatKind.LETTER
    if (c.messages.any { it.attachments.isNotEmpty() }) return ChatKind.DOC
    return ChatKind.TALK
}

private fun kindIcon(k: ChatKind): ImageVector = when (k) {
    ChatKind.LETTER -> Icons.Default.MailOutline
    ChatKind.DOC -> Icons.AutoMirrored.Filled.Notes
    ChatKind.CODE -> Icons.Default.Code
    ChatKind.TALK -> Icons.Default.ChatBubbleOutline
}

/** "Heute", "Gestern", "Diese Woche", "Älter" */
private fun dayGroup(t: Long): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val day = 24L * 3600 * 1000
    return when {
        t >= today -> "Heute"
        t >= today - day -> "Gestern"
        t >= today - 6 * day -> "Diese Woche"
        else -> "Älter"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(ui: UiState, vm: ChatViewModel, onSettings: () -> Unit, onPicked: () -> Unit, onCollapse: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active = profiles.first.firstOrNull { it.id == profiles.second }
    val shown = remember(ui.conversations, query) {
        val q = query.trim().lowercase()
        ui.conversations.filter { c -> q.isEmpty() || c.title.lowercase().contains(q) || c.messages.any { it.text.lowercase().contains(q) } }
    }
    Column(Modifier.fillMaxHeight().background(FK.Side).statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fold KI", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, modifier = Modifier.weight(1f))
            if (onCollapse != null) IconButton(onClick = onCollapse) {
                Icon(Icons.AutoMirrored.Filled.MenuOpen, "Seitenleiste schließen", tint = FK.Text)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(FK.Accent)
                .clickable { vm.newChat(); onPicked() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Edit, null, tint = FK.OnAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Neuer Chat", color = FK.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Row(
            Modifier.padding(top = 6.dp, bottom = 10.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(FK.Surface)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = FK.Dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Chats durchsuchen", color = FK.Dim, fontSize = 14.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = FK.Text),
                    cursorBrush = SolidColor(FK.Accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Icon(Icons.Default.Close, "Suche leeren", tint = FK.Dim, modifier = Modifier.size(18.dp).clickable { query = "" })
        }
        LazyColumn(Modifier.weight(1f)) {
            var last = ""
            shown.forEach { c ->
                val g = dayGroup(c.updatedAt)
                if (g != last) {
                    last = g
                    item(key = "h_$g") {
                        Text(
                            g.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = FK.Dim, letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                }
                item(key = c.id) {
                    val selected = c.id == ui.currentId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) FK.Surface else FK.Side)
                            .combinedClickable(
                                onClick = { vm.open(c.id); onPicked() },
                                onLongClick = { confirmDelete = c },
                                onLongClickLabel = "Löschen",
                            )
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kindIcon(kindOf(c)), null, tint = FK.Dim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = FK.Text)
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(if (query.isBlank()) "Noch keine Chats" else "Nichts gefunden", color = FK.Dim, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
        HorizontalDivider(color = FK.Line)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSettings(); onPicked() }
                .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(active?.effectiveInitials ?: "", 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(active?.name?.ifBlank { active.title } ?: "Einstellungen", fontSize = 14.sp, color = FK.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.Settings, "Einstellungen", tint = FK.Dim, modifier = Modifier.size(20.dp))
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = FK.Surface,
            title = { Text("Chat löschen?", color = FK.Text) },
            text = { Text("„${c.title}“ wird vom Handy gelöscht.", color = FK.Muted) },
            confirmButton = { TextButton(onClick = { vm.delete(c.id); confirmDelete = null }) { Text("Löschen", color = FK.Accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen", color = FK.Muted) } },
        )
    }
}

// ---------------------------------------------------------------- Top bar + model sheet (image 2)

/** Unfolded only: button that closes/opens the chat list pane. */
private class SidebarToggle(val open: Boolean, val toggle: () -> Unit)

private fun modelName(ui: UiState): String = ui.activeName

private fun modelReady(ui: UiState): Boolean = ui.modelReady

@Composable
private fun TopBar(ui: UiState, showMenu: Boolean, onMenu: () -> Unit, sidebar: SidebarToggle?, onModel: () -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val right = showMenu || (sidebar != null && !sidebar.open)
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (showMenu) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menü", tint = FK.Text) }
            else if (sidebar != null && !sidebar.open) IconButton(onClick = sidebar.toggle) { Icon(Icons.Default.Menu, "Seitenleiste öffnen", tint = FK.Text) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(FK.Surface).clickable(onClick = onModel)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(modelReady(ui))
                Spacer(Modifier.width(8.dp))
                Text(modelName(ui), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text("▼", fontSize = 11.sp, color = FK.Muted)
            }
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (right) IconButton(onClick = onNew) { Icon(Icons.Default.Edit, "Neuer Chat", tint = FK.Text) }
        }
    }
}

/** Bottom sheet "Modell wählen" (image 2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(ui: UiState, vm: ChatViewModel, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = FK.Surface, scrimColor = FK.Scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), dragHandle = { Grab() },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding()) {
            SheetTitle("Modell wählen", "Alle Modelle laufen offline – alles bleibt auf dem Handy. Es antwortet nur das gewählte Modell.")
            SectionLabel("Auf dem Gerät", Modifier.offset(y = (-14).dp))
            ui.localModels.forEach { m ->
                ModelOption(Icons.Default.Diamond, FK.T3, m.name, LocalModelManager.gb(m.size) + " · LiteRT-LM · GPU", ui.activeModel == m.file) {
                    vm.selectModel(m.file); onDismiss()
                }
            }
            ModelOption(Icons.Default.PhoneAndroid, FK.T1, "Gemini Nano", "im Handy eingebaut (AICore)", ui.usesNano) {
                vm.selectModel(Settings.NANO); onDismiss()
            }
            FkButton("Modelle herunterladen & verwalten", ghost = true) { onDismiss(); onSettings() }
        }
    }
}

@Composable
private fun Grab() {
    Box(Modifier.padding(top = 14.dp, bottom = 14.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(FK.Line))
}

@Composable
private fun SheetTitle(title: String, hint: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, modifier = Modifier.padding(horizontal = 4.dp))
    Text(hint, fontSize = 12.sp, color = FK.Muted, lineHeight = 17.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp))
}

@Composable
private fun ModelOption(
    icon: ImageVector, tone: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>,
    title: String, sub: String, selected: Boolean, tag: String? = null, tagOn: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(shape)
            .background(if (selected) FK.AccentSoft else FK.Bg)
            .border(1.dp, if (selected) FK.Accent else FK.Line, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(icon, tone, size = 42.dp, radius = 12.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FK.Text)
            Text(sub, fontSize = 12.sp, color = FK.Muted)
        }
        if (tag != null) Tag(tag, on = tagOn)
        if (selected) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(20.dp)) }
    }
}

// ---------------------------------------------------------------- Start screen (image 1)

private const val P_REPLY = "Schreibe ein formelles Antwortschreiben (Gegenschreiben) auf das angehängte Schreiben. Gehe auf alle Punkte ein."
private const val P_OBJECT = "Schreibe einen formellen, fristgerechten Widerspruch gegen das angehängte Schreiben mit sachlicher Begründung."
private const val P_SUMMARY = "Fasse das angehängte Dokument verständlich zusammen: Worum geht es, was wird von mir verlangt, welche Fristen gibt es?"
private const val P_GITHUB = "Auf GitHub: "

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { h < 11 -> "Guten Morgen"; h < 18 -> "Guten Tag"; else -> "Guten Abend" }
}

@Composable
private fun StartScreen(ui: UiState, vm: ChatViewModel, onSettings: () -> Unit, onCard: (String, Boolean) -> Unit) {
    val brief by vm.brief.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val first = profiles.first.firstOrNull { it.id == profiles.second }?.name?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxCard = maxHeight * 0.45f
        val topGap = minOf(120.dp, maxHeight * 0.14f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            brief?.let { b ->
                BriefCard(
                    b, onClose = vm::closeBrief, onOpenChat = vm::openBriefAsChat, onToggle = vm::toggleOpenItem,
                    modifier = Modifier.padding(vertical = 8.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxCard),
                )
            }
            Spacer(Modifier.height(if (brief == null) topGap else 16.dp))
            Spark(34.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                if (first.isBlank()) greeting() else "${greeting()}, $first",
                fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, letterSpacing = (-0.4).sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                modelName(ui) + if (ui.activeModel.isBlank()) "" else " · offline auf deinem Fold",
                fontSize = 14.sp, color = FK.Muted, textAlign = TextAlign.Center,
            )
            if (ui.usesNano && !ui.nanoReady) {
                Spacer(Modifier.height(8.dp))
                Note(ui.nanoStatus)
                if (ui.nanoDownloadable) TextButton(onClick = { vm.downloadNano() }) { Text("Gemini Nano herunterladen", color = FK.Accent) }
                else TextButton(onClick = { vm.checkNano() }) { Text("Erneut prüfen", color = FK.Accent) }
            } else if (!ui.modelReady) {
                TextButton(onClick = onSettings) { Text("Modell wählen oder herunterladen", color = FK.Accent) }
            }
            Spacer(Modifier.height(36.dp))
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartCard(Icons.Default.MailOutline, "Brief beantworten", "Schreiben anhängen", Modifier.weight(1f)) { onCard(P_REPLY, true) }
                    StartCard(Icons.Default.Gavel, "Widerspruch", "sachlich & mit Frist", Modifier.weight(1f)) { onCard(P_OBJECT, true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartCard(Icons.AutoMirrored.Filled.Notes, "Zusammenfassen", "PDF oder Foto", Modifier.weight(1f)) { onCard(P_SUMMARY, true) }
                    StartCard(Icons.Default.Code, "GitHub", "Code & APKs bauen", Modifier.weight(1f)) { onCard(P_GITHUB, false) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StartCard(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(FK.Surface).border(1.dp, FK.Line, shape).clickable(onClick = onClick).padding(14.dp),
    ) {
        Tile(icon, FK.AccentSoft to FK.Accent, size = 30.dp, radius = 9.dp, iconSize = 16.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Chat screen

@Composable
private fun ChatScreen(
    ui: UiState, vm: ChatViewModel, showMenu: Boolean, onMenu: () -> Unit, onSettings: () -> Unit,
    sidebar: SidebarToggle? = null,
) {
    val context = LocalContext.current
    val messages = ui.current?.messages.orEmpty()
    val listState = rememberLazyListState()
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(ui.notice) {
        ui.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.notify(null) }
    }
    // GitHub: nothing changes without the user's approval.
    ui.approval?.let { a ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = FK.Surface,
            title = { Text("Freigabe: ${a.title}", color = FK.Text, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("Fold KI möchte auf GitHub Folgendes tun:", fontSize = 13.sp, color = FK.Muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        a.details, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = FK.Text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(FK.Bg).padding(10.dp)
                            .heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.answerApproval(true) }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FK.Accent, contentColor = FK.OnAccent),
                ) { Text("Freigeben", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { vm.answerApproval(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ablehnen", color = FK.Muted) } },
        )
    }

    val voice = vm.voice
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var afterMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGranted?.invoke() else vm.notify("Ohne Mikrofon-Berechtigung geht die Spracheingabe nicht.")
        afterMicGranted = null
    }
    val withMic: (() -> Unit) -> Unit = { action ->
        if (!voice.isReady) { vm.notify("Bitte zuerst unter Einstellungen die Sprachfunktion einrichten (Tessa & Caleb)."); onSettings() }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { afterMicGranted = action; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var speakingId by remember { mutableStateOf<String?>(null) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    vm.sigVersion.collectAsStateWithLifecycle().value
    val hasSignature = profiles.second.isNotBlank() && vm.signatures.exists(profiles.second)

    val actions = MessageActions(
        copy = { text ->
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Text", text))
            vm.notify("Kopiert")
        },
        // Every PDF and share button first shows the preview (save / share / change text / discard there).
        savePdf = { text -> vm.openPreview(text) },
        sharePdf = { text -> vm.openPreview(text) },
        speak = { id, text ->
            if (speakingId == id) { voice.stopSpeaking(); speakingId = null }
            else if (!voice.supertonic.isReady) { vm.notify("Bitte zuerst unter Einstellungen die Sprachfunktion einrichten (Tessa & Caleb)."); onSettings() }
            else { speakingId = id; voice.speak(text) { if (speakingId == id) speakingId = null } }
        },
        speakingId = speakingId,
        open = { a ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(a.path))
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, a.mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure { vm.notify("Keine App zum Öffnen gefunden.") }
        },
        shareFile = { a ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(a.path))
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = a.mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Teilen"))
            }.onFailure { vm.notify("Teilen fehlgeschlagen.") }
        },
        hasSignature = hasSignature,
    )

    if (voiceMode) {
        VoiceOverlay(ui, vm, voice, onClose = { voiceMode = false })
        return
    }

    var text by rememberSaveable { mutableStateOf("") }
    var modelOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(FK.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(ui, showMenu, onMenu, sidebar, onModel = { modelOpen = true }, onNew = { vm.newChat() })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (messages.isEmpty()) {
                StartScreen(ui, vm, onSettings) { prompt, needsFile ->
                    text = prompt
                    if (needsFile && ui.pending.isEmpty()) attachOpen = true
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        val typing = ui.generating && m === messages.last() && m.role == Role.ASSISTANT
                        MessageRow(m, typing = typing, actions = actions, liveCpu = if (typing) ui.liveCpu else -1)
                    }
                }
            }
        }
        InputBar(
            ui, vm, voice, withMic, text, { text = it }, onAttach = { attachOpen = true },
            onVoiceMode = { withMic { voice.stopSpeaking(); speakingId = null; voiceMode = true } },
            onDictate = { voice.stopSpeaking(); speakingId = null },
        )
    }
    if (modelOpen) ModelSheet(ui, vm, onDismiss = { modelOpen = false }, onSettings = onSettings)
    if (attachOpen) AttachSheet(vm, onDismiss = { attachOpen = false }, setPrompt = { text = it })
}

private class MessageActions(
    val copy: (String) -> Unit,
    val savePdf: (String) -> Unit,
    val sharePdf: (String) -> Unit,
    val speak: (id: String, text: String) -> Unit,
    val speakingId: String?,
    val open: (Attachment) -> Unit,
    val shareFile: (Attachment) -> Unit,
    val hasSignature: Boolean,
)

// ---------------------------------------------------------------- Attachment sheet (image 3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(vm: ChatViewModel, onDismiss: () -> Unit, setPrompt: (String) -> Unit) {
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        uris.forEach { vm.addAttachment(it) }; onDismiss()
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addAttachment(it) }; onDismiss()
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let { java.io.File(it) }
        cameraPath = null
        if (ok && f != null && f.length() > 0) vm.addAttachment(android.net.Uri.fromFile(f), deleteAfter = f) else f?.delete()
        onDismiss()
    }
    val camera = {
        val f = vm.attachments.newCameraFile()
        cameraPath = f.absolutePath
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        runCatching { takePhoto.launch(uri) }.onFailure { vm.notify("Keine Kamera-App gefunden") }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = FK.Surface, scrimColor = FK.Scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), dragHandle = { Grab() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding()) {
            SheetTitle("Hinzufügen", "Die App liest Datum, Absender und Aktenzeichen automatisch.")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigTile(Icons.Default.PhotoCamera, "Kamera", Modifier.weight(1f)) { camera() }
                BigTile(Icons.Default.Image, "Galerie", Modifier.weight(1f)) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                BigTile(Icons.Default.Description, "PDF", Modifier.weight(1f)) { pickPdf.launch(arrayOf("application/pdf")) }
            }
            SectionLabel("Schnell starten")
            Group {
                SettingRow("Brief scannen & beantworten", "Foto → Antwortschreiben als PDF",
                    leading = { Tile(Icons.Default.MailOutline, FK.T1) }, onClick = { setPrompt(P_REPLY); camera() })
                GroupDivider()
                SettingRow("Widerspruch schreiben", "mit Frist und Aktenzeichen",
                    leading = { Tile(Icons.Default.Gavel, FK.T5) }, onClick = { setPrompt(P_OBJECT); pickPdf.launch(arrayOf("application/pdf", "image/*")) })
            }
        }
    }
}

@Composable
private fun BigTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(FK.Bg).border(1.dp, FK.Line, shape).clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tile(icon, FK.AccentSoft to FK.Accent, size = 42.dp, radius = 12.dp, iconSize = 20.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 13.sp, color = FK.Text)
    }
}

// ---------------------------------------------------------------- Messages (image 6)

@Composable
private fun MessageRow(m: ChatMessage, typing: Boolean, actions: MessageActions, liveCpu: Int = -1) {
    if (m.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Column(
                Modifier.fillMaxWidth(0.8f).wrapContentWidth(Alignment.End)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp))
                    .background(FK.Surface2).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer { Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, color = FK.Text) }
                m.attachments.forEach { a ->
                    Row(
                        Modifier.padding(top = 8.dp).clickable { actions.open(a) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AttachFile, null, tint = FK.Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(a.name, fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            val parts = remember(m.text) { DocumentParser.parse(m.text) }
            val clean = remember(m.text) { DocumentParser.display(m.text) }
            val doc = parts.document
            val exportText = doc ?: clean
            val textColor = if (m.isError) androidx.compose.ui.graphics.Color(0xFFF2B8B5) else FK.Text
            if (m.engine != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    StatusDot(!m.isError)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (m.engine) {
                            Engine.NANO -> "Gemini Nano · " + if (m.web) "Web" else "offline"
                            Engine.GEMMA -> (m.model ?: "Gemma 4 E2B") + " · " + if (m.web) "Web" else "offline"
                            else -> "Claude · online" + if (m.web) " · Websuche" else ""
                        },
                        fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val stillThinking = typing && m.text.isEmpty() && m.thoughts.isNotEmpty()
            if (m.thoughts.isNotEmpty()) Thoughts(m.thoughts, stillThinking, liveCpu)
            if (doc == null) {
                if (!stillThinking) AnswerText(
                    textColor,
                    if (m.text.isEmpty() && typing) (if (m.web) "Suche im Internet…" else "…" + if (liveCpu >= 0) "  CPU $liveCpu %" else "") else clean,
                )
            } else {
                if (parts.before.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.before))
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.padding(vertical = 10.dp).widthIn(max = 720.dp).fillMaxWidth().clip(shape).background(FK.Surface)
                        .border(1.dp, FK.Line, shape).padding(14.dp),
                ) {
                    SelectionContainer { Text(doc, fontSize = 14.sp, lineHeight = 21.sp, color = FK.Text) }
                    if (!typing) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            if (!parts.complete) Text("Unvollständig", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFF2B8B5))
                            Spacer(Modifier.weight(1f))
                            FkChip("PDF", Icons.Default.PictureAsPdf) { actions.savePdf(doc) }
                        }
                    }
                }
                if (parts.after.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.after))
            }
            m.attachments.forEach { a -> DocCard(a, actions) }
            if (!typing && (m.tokens > 0 || m.answerMs > 0)) AnswerStats(m)
            if (!typing && !m.isError && m.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp).offset(x = (-10).dp)) {
                    SmallAction(Icons.Default.ContentCopy, "Kopieren") { actions.copy(clean) }
                    SmallAction(Icons.Default.PictureAsPdf, "Als PDF") { actions.savePdf(exportText) }
                    SmallAction(if (actions.speakingId == m.id) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        if (actions.speakingId == m.id) "Vorlesen stoppen" else "Vorlesen") { actions.speak(m.id, DocumentParser.spoken(m.text)) }
                    SmallAction(Icons.Default.Share, "Teilen") { actions.sharePdf(exportText) }
                }
            }
        }
    }
}

/** Small line under an answer: where it ran, CPU load, tokens, time, speed (only shown, never exported or spoken). */
@Composable
private fun AnswerStats(m: ChatMessage) {
    val de = java.util.Locale.GERMANY
    val parts = buildList {
        if (m.backend.isNotBlank()) add(m.backend)
        if (m.cpuPct >= 0) add("CPU ${m.cpuPct} %")
        if (m.tokens > 0) add("%,d Tokens".format(de, m.tokens))
        if (m.answerMs > 0) add("%.1f s".format(de, m.answerMs / 1000.0))
        if (m.tokPerSec > 0f) add("%.1f Tokens/s".format(de, m.tokPerSec))
    }
    Text(parts.joinToString(" · "), fontSize = 11.sp, color = FK.Muted, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
}

/** "Gedanken" of a thinking model: collapsed by default, tap to expand (only shown, never exported or spoken). */
@Composable
private fun Thoughts(text: String, thinking: Boolean, liveCpu: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(20.dp)).background(FK.Surface).clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (thinking) "Denkt nach…" else "Gedanken", fontSize = 12.sp, color = FK.Muted)
        if (thinking && liveCpu >= 0) { Spacer(Modifier.width(8.dp)); Text("CPU $liveCpu %", fontSize = 12.sp, color = FK.Accent) }
        Spacer(Modifier.width(8.dp))
        Text(if (open) "▲" else "▼", fontSize = 11.sp, color = FK.Muted)
    }
    if (open) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.padding(bottom = 10.dp).widthIn(max = 720.dp).fillMaxWidth().clip(shape).background(FK.Surface)
                .border(1.dp, FK.Line, shape).padding(14.dp),
        ) {
            SelectionContainer { Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = FK.Muted) }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, label, tint = FK.Dim, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun AnswerText(color: androidx.compose.ui.graphics.Color, text: String) {
    SelectionContainer { Text(text, fontSize = 14.sp, lineHeight = 21.7.sp, color = color) }
}

/** Finished PDF in an answer: card with page count (image 6). Tap = open, long press = share. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(a: Attachment, actions: MessageActions) {
    val pages by produceState(0, a.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(java.io.File(a.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(top = 10.dp).widthIn(max = 720.dp).fillMaxWidth().clip(shape).background(FK.Surface).border(1.dp, FK.Line, shape)
            .combinedClickable(onClick = { actions.open(a) }, onLongClick = { actions.shareFile(a) }, onLongClickLabel = "Teilen")
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(if (a.isPdf) Icons.Default.Description else Icons.Default.Image, FK.AccentSoft to FK.Accent, size = 42.dp, radius = 12.dp, iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (!a.isPdf) "Bild" else listOfNotNull(
                    if (pages > 0) (if (pages == 1) "1 Seite" else "$pages Seiten") else "PDF",
                    if (actions.hasSignature) "mit Unterschrift" else null,
                ).joinToString(" · "),
                fontSize = 12.sp, color = FK.Muted,
            )
        }
        Chevron()
    }
}

// ---------------------------------------------------------------- Composer (image 1 / 6)

@Composable
private fun InputBar(
    ui: UiState, vm: ChatViewModel, voice: VoiceController,
    withMic: (() -> Unit) -> Unit, text: String, setText: (String) -> Unit, onAttach: () -> Unit,
    onVoiceMode: () -> Unit, onDictate: () -> Unit = {},
) {
    var dictating by remember { mutableStateOf(false) }
    val listenPhase by voice.listenPhase.collectAsState()
    val recognizing = dictating && listenPhase == VoiceController.ListenPhase.TRANSCRIBING
    val currentText by rememberUpdatedState(text)
    val dictate = {
        when {
            recognizing -> voice.cancelListening()
            dictating -> voice.stopListening()
            else -> withMic {
                val before = currentText
                onDictate()
                dictating = true
                voice.listen(
                    onFinal = { t -> if (t.isNotBlank()) setText((before + " " + t).trim()) },
                    onError = { msg -> vm.notify(msg) },
                    onEnd = { dictating = false },
                )
            }
        }
    }
    DisposableEffect(Unit) { onDispose { if (dictating) voice.cancelListening() } }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Answer suggestions from the AI's reading of the last letter (like FA-PDF)
        val lastLetter = ui.current?.messages?.lastOrNull { m -> m.role == Role.USER && m.attachments.any { it.analysis != null } }
            ?.attachments?.lastOrNull { it.analysis != null }
        val suggestions = lastLetter?.analysis?.takeIf { it != "-" }
            ?.let { de.foldki.app.export.LetterAnalysis.parse(it).suggestions }
            ?.ifEmpty { de.foldki.app.export.LetterAnalysis.DEFAULT_SUGGESTIONS }
            .orEmpty()
        val chips: List<Pair<String, () -> Unit>> = when {
            ui.generating -> emptyList()
            ui.pending.isNotEmpty() -> listOf(
                "Antwortschreiben" to { vm.send(P_REPLY + extra(text)); setText("") },
                "Widerspruch" to { vm.send(P_OBJECT + extra(text)); setText("") },
                "Zusammenfassen" to { vm.send(P_SUMMARY + extra(text)); setText("") },
            )
            else -> suggestions.map { sug -> sug to { vm.send("Schreib ein Antwortschreiben auf das Schreiben: $sug" + extra(text)); setText("") } }
        }
        if (chips.isNotEmpty()) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { chips.forEach { (label, go) -> FkChip(label, Icons.Default.Description, onClick = go) } }
        }

        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(shape).background(FK.Surface).border(1.dp, FK.Line, shape)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
        ) {
            if (ui.pending.isNotEmpty() || ui.importing) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    ui.pending.forEach { a ->
                        Row(
                            Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(FK.Bg).border(1.dp, FK.Line, RoundedCornerShape(12.dp))
                                .padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (a.isPdf) Icons.Default.Description else Icons.Default.Image, null, tint = FK.Accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(a.name, fontSize = 12.sp, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                            IconButton(onClick = { vm.removePending(a.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Entfernen", tint = FK.Muted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (ui.importing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FK.Accent)
                        Text("Lese Datei…", fontSize = 12.sp, color = FK.Muted)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 14.dp)) {
                if (text.isEmpty()) Text(
                    when {
                        recognizing -> "Erkenne…"; dictating -> "Ich höre zu…"
                        ui.pending.isNotEmpty() -> "Frage zum Anhang …"; ui.webSearch -> "Im Internet nachfragen …"
                        ui.current == null -> "Frag etwas oder häng ein Schreiben an …"
                        else -> "Antworten …"
                    },
                    fontSize = 16.sp, color = FK.Dim,
                )
                BasicTextField(
                    value = text, onValueChange = setText, maxLines = 5,
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = FK.Text, lineHeight = 22.sp),
                    cursorBrush = SolidColor(FK.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundOutline(onAttach) { Icon(Icons.Default.Add, "Hinzufügen", tint = FK.Muted, modifier = Modifier.size(20.dp)) }
                FkChip("Web", Icons.Default.Language, active = ui.webSearch, onClick = vm::toggleWeb)
                Spacer(Modifier.weight(1f))
                if (!ui.generating) RoundOutline(dictate) {
                    if (recognizing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = FK.Accent)
                    else Icon(if (dictating) Icons.Default.MicOff else Icons.Default.Mic, if (dictating) "Diktat beenden" else "Diktieren",
                        tint = if (dictating) FK.Accent else FK.Muted, modifier = Modifier.size(20.dp))
                }
                val canSend = (text.isNotBlank() || ui.pending.isNotEmpty()) && !ui.importing
                SendButton(
                    icon = when {
                        ui.generating -> Icons.Default.Stop
                        text.isBlank() && ui.pending.isEmpty() && !dictating -> Icons.Default.GraphicEq
                        else -> Icons.AutoMirrored.Filled.Send
                    },
                    label = when { ui.generating -> "Stopp"; text.isBlank() && ui.pending.isEmpty() -> "Sprachmodus"; else -> "Senden" },
                    enabled = ui.generating || canSend || (text.isBlank() && ui.pending.isEmpty() && !dictating),
                ) {
                    when {
                        ui.generating -> vm.stop()
                        text.isBlank() && ui.pending.isEmpty() && !dictating -> onVoiceMode()
                        canSend -> { vm.send(text); setText("") }
                    }
                }
            }
        }
    }
}

private fun extra(text: String) = if (text.isNotBlank()) "\nZusätzliche Hinweise: $text" else ""

@Composable
private fun SendButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) FK.Accent else FK.Line).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = FK.OnAccent, modifier = Modifier.size(22.dp)) }
}
