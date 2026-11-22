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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.draw.shadow
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
    // Open page of the menu: null = chat, "" = Einstellungen, else a page key (models, hf, voice, history, instructions, tools …).
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val showSettings = page != null

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
            onSender = { page = "sender" },
        )
    }

    val brief by vm.brief.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(FK.Bg)) {
        val wide = maxWidth >= 600.dp // Fold7 unfolded / tablets: two panes with a draggable divider
        if (wide) {
            // Mock-up 18: chat list left, chat right. The ⚙ in the model card at the bottom opens the menu (mock-up 2).
            var wideMenu by rememberSaveable { mutableStateOf(false) }
            val openPage: (String) -> Unit = { p -> split.expandFirst(); page = p }
            val paneScope = rememberCoroutineScope()
            val sidebarClosed by remember { derivedStateOf { split.collapse == PaneCollapse.FIRST } }
            SplitPane(
                state = split,
                modifier = Modifier.fillMaxSize(),
                onSettled = { f, c -> vm.settings.splitFraction = f; vm.settings.splitCollapse = c.name },
                first = {
                    Surface(color = FK.Surface, modifier = Modifier.fillMaxSize()) {
                        when {
                            showSettings -> SettingsScreen(vm, ui, startPage = page!!.ifEmpty { null }, onBack = { page = null }, onMenu = { page = null; wideMenu = true })
                            wideMenu -> MenuPanel(ui, vm, onPage = { wideMenu = false; openPage(it) }, onPicked = { wideMenu = false }, onClose = { wideMenu = false })
                            else -> ChatListPane(ui, vm, onMenu = { wideMenu = true }, onCollapse = { split.toggleFirst(paneScope) })
                        }
                    }
                },
                second = {
                    preview?.let { previewPane(it) } ?: ChatScreen(
                        ui, vm, showMenu = false, onMenu = {}, onSettings = { openPage("models") }, onInstructions = { openPage("instructions") }, onVoiceSettings = { openPage("voice") },
                        sidebar = SidebarToggle(open = !sidebarClosed, toggle = { split.toggleFirst(paneScope) }),
                    )
                },
            )
        } else {
            val width = LocalConfiguration.current.screenWidthDp.dp - 70.dp
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = androidx.compose.ui.graphics.Color(0x8C000000),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = androidx.compose.ui.graphics.Color(0xEB1C1C1E), drawerShape = androidx.compose.ui.graphics.RectangleShape,
                        modifier = Modifier.width(width), windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        MenuPanel(ui, vm, onPage = { page = it }, onPicked = { scope.launch { drawer.close() } }, onClose = null)
                    }
                },
            ) {
                ChatScreen(ui, vm, showMenu = true, onMenu = { scope.launch { drawer.open() } }, onSettings = { page = "models" }, onInstructions = { page = "instructions" }, onVoiceSettings = { page = "voice" })
            }
        }

        // Folded: pages slide in full-screen; ☰ goes back to the menu, ‹ / back gesture to the chat.
        AnimatedVisibility(
            visible = showSettings && !wide,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SettingsScreen(vm, ui, startPage = page?.ifEmpty { null }, onBack = { page = null }, onMenu = { page = null; scope.launch { drawer.open() } })
        }

        // Tagesstart (mock-up 17) as its own page in the morning.
        if (page == null && preview == null) brief?.let { b -> BriefPage(b, onClose = vm::closeBrief, onToggle = vm::toggleOpenItem) }

        // Folded: the PDF preview is a full-screen page on top.
        if (!wide && page == null) preview?.let { previewPane(it) }
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

/** Menu exactly like mock-up 2 (design/2-menue.png). */
@Composable
private fun MenuPanel(ui: UiState, vm: ChatViewModel, onPage: (String) -> Unit, onPicked: () -> Unit, onClose: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    val go: (String) -> Unit = { onPage(it); onPicked() }
    Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppLogo(34.dp)
                Spacer(Modifier.width(10.dp))
                Text("Fold KI", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.weight(1f))
                if (onClose != null) NavBtn(Icons.Default.Close, "Menü schließen", onClick = onClose)
            }
            Text(
                "＋ Neuer Chat", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.White).clickable { vm.newChat(); onPicked() }.padding(11.dp),
            )
            MenuSearch(query, { query = it }, Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp))
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                ui.conversations.filter { c -> c.title.lowercase().contains(q) || c.messages.any { it.text.lowercase().contains(q) } }.take(50).forEach { c ->
                    ChatListItem(c, selected = c.id == ui.currentId) { vm.open(c.id); onPicked() }
                }
            } else {
                MenuHead("Chats")
                MenuItem(Icons.Default.ChatBubbleOutline, FK.T2, "Verlauf") { go("history") }
                MenuItem(Icons.Default.StarBorder, FK.T1, "Gemerkte Antworten") { go("saved") }
                MenuItem(Icons.Default.Person, FK.T4, "Meine Anweisungen") { go("instructions") }
                MenuHead("KI")
                MenuItem(Icons.Default.Memory, FK.T3, "Modelle", count = ui.localModels.size) { go("models") }
                MenuItem(Icons.Default.Language, FK.T7, "Erkunden") { go("hf") }
                MenuItem(Icons.AutoMirrored.Filled.VolumeUp, FK.Danger to androidx.compose.ui.graphics.Color.White, "Stimmen") { go("voice") }
                MenuItem(Icons.Default.Dns, FK.T6, "PC-Server") { go("server") }
                MenuItem(Icons.Default.Build, FK.T8, "Werkzeuge") { go("tools") }
                MenuHead("App")
                MenuItem(Icons.Default.CloudDone, FK.T2, "S3-Sicherung") { go("s3") }
                MenuItem(Icons.Default.Settings, FK.T10, "Einstellungen") { go("") }
            }
        }
        ModelFoot(ui, trailing = "⇅", modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 30.dp)) { go("models") }
    }
}

/** Chat list of the unfolded screen exactly like mock-up 18 (left pane). */
@Composable
private fun ChatListPane(ui: UiState, vm: ChatViewModel, onMenu: () -> Unit, onCollapse: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    Column(Modifier.fillMaxHeight().background(FK.Surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(32.dp)
            Spacer(Modifier.width(10.dp))
            Text("Fold KI", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(CircleShape).background(FK.Surface).clickable(onClick = onCollapse), contentAlignment = Alignment.Center) {
                Text("◧", fontSize = 18.sp, color = FK.Text)
            }
        }
        Text(
            "＋ Neuer Chat", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White).clickable { vm.newChat() }.padding(10.dp),
        )
        MenuSearch(query, { query = it }, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
        val q = query.trim().lowercase()
        val shown = ui.conversations.filter { c -> q.isEmpty() || c.title.lowercase().contains(q) || c.messages.any { it.text.lowercase().contains(q) } }
        LazyColumn(Modifier.weight(1f)) {
            var last = ""
            shown.forEach { c ->
                val g = dayGroup(c.updatedAt)
                if (g != last) { last = g; item(key = "h_$g") { MenuHead(g) } }
                item(key = c.id) { ChatListItem(c, selected = c.id == ui.currentId, onLong = { confirmDelete = c }) { vm.open(c.id) } }
            }
        }
        ModelFoot(ui, trailing = "⚙︎", modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 20.dp), onClick = onMenu)
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = FK.Surface,
            title = { Text("Chat löschen?", color = FK.Text) },
            text = { Text("„${c.title}“ wird vom Handy gelöscht.", color = FK.Muted) },
            confirmButton = { TextButton(onClick = { vm.delete(c.id); confirmDelete = null }) { Text("Löschen", color = FK.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen", color = FK.Accent) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(c: Conversation, selected: Boolean, onLong: () -> Unit = {}, onClick: () -> Unit) {
    val model = c.messages.lastOrNull { it.model != null }?.model.orEmpty()
    val de = java.util.Locale.GERMANY
    val t = when (dayGroup(c.updatedAt)) {
        "Heute" -> java.text.SimpleDateFormat("HH:mm", de).format(java.util.Date(c.updatedAt))
        "Älter" -> java.text.SimpleDateFormat("dd.MM.", de).format(java.util.Date(c.updatedAt))
        else -> java.text.SimpleDateFormat("EE", de).format(java.util.Date(c.updatedAt)).removeSuffix(".")
    }
    Column(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) FK.Surface2 else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong, onLongClickLabel = "Löschen").padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(model, t).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = FK.Muted, maxLines = 1)
    }
}

/** Active model at the bottom of the menu / chat list (".foot" of the mock-ups). */
@Composable
private fun ModelFoot(ui: UiState, trailing: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FK.Surface2).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(Icons.Default.Memory, FK.Ok.copy(alpha = 0.2f) to FK.Ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.activeName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ui.modelReady) FK.Ok else FK.Dim))
                Spacer(Modifier.width(4.dp))
                Text(
                    listOfNotNull(
                        when { ui.usesNano -> "AICore"; ui.activeServer != null -> "PC"; else -> ui.backend.ifBlank { "GPU" } },
                        ui.activeLocal?.let { LocalModelManager.gb(it.size) },
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = FK.Muted,
                )
            }
        }
        Text(trailing, fontSize = 18.sp, color = FK.Dim)
    }
}

/** Grey search field of the menu (".search"): g2, radius 10, 15 sp. */
@Composable
private fun MenuSearch(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FK.Surface2).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔍", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Chats durchsuchen", color = FK.Dim, fontSize = 15.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = FK.Text),
                cursorBrush = SolidColor(FK.Accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Text("✕", color = FK.Dim, fontSize = 14.sp, modifier = Modifier.clickable { onChange("") })
    }
}

/** App logo: FA-KI-Logo (aus FA KI Desktop, src/logo.svg). */
@Composable
private fun AppLogo(size: Dp) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(de.foldki.app.R.drawable.logo_fa_ki), "Fold KI",
        Modifier.size(size),
    )
}

@Composable
private fun MenuHead(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FK.Muted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun MenuItem(icon: ImageVector, tone: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, title: String, count: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(icon, tone, size = 28.dp, radius = 7.dp, iconSize = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 16.sp, color = FK.Text, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", fontSize = 15.sp, color = FK.Muted)
    }
}

// ---------------------------------------------------------------- Top bar + model sheet (image 2)

/** Unfolded only: button that closes/opens the chat list pane. */
private class SidebarToggle(val open: Boolean, val toggle: () -> Unit)

private fun modelName(ui: UiState): String = ui.activeName

private fun modelReady(ui: UiState): Boolean = ui.modelReady

/** Top bar exactly like mock-ups 1 and 11: ☰ left, model in the middle, new chat (empty chat) or ⋮ (chat with messages) right. */
@Composable
private fun TopBar(ui: UiState, showMenu: Boolean, onMenu: () -> Unit, sidebar: SidebarToggle?, onModel: () -> Unit, onNew: () -> Unit, onOptions: () -> Unit) {
    val empty = ui.current?.messages.isNullOrEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp)) {
            if (showMenu) NavBtn(Icons.Default.Menu, "Menü", onClick = onMenu)
            else if (sidebar != null && !sidebar.open) NavBtn(Icons.Default.Menu, "Seitenleiste öffnen", onClick = sidebar.toggle)
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onModel).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(modelName(ui) + " ⌄", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.incognito) Text("🕶 Inkognito", fontSize = 12.sp, color = FK.Muted)
                else {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (modelReady(ui)) FK.Ok else FK.Dim))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        listOf(
                            when { ui.usesNano -> "AICore"; ui.activeServer != null -> "PC"; else -> ui.backend.ifBlank { "GPU" } },
                            if (ui.webSearch) "Web" else if (ui.activeServer != null) "WLAN" else "offline",
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = FK.Muted,
                    )
                }
            }
        }
        Box(Modifier.size(36.dp)) {
            // Immer ⋮ (Chat-Optionen), auch im leeren Chat – z. B. um „Denken“ vor der ersten Frage einzustellen. Neuer Chat: Menü.
            NavBtn(Icons.Default.MoreVert, "Chat-Einstellungen", onClick = onOptions)
        }
    }
}

@Composable
private fun CircleBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(FK.Surface).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = FK.Text, modifier = Modifier.size(20.dp))
    }
}

/** Sheet "Dieser Chat" exactly like mock-up 12. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatOptionsSheet(ui: UiState, vm: ChatViewModel, onDismiss: () -> Unit, onInstructions: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickInstruction by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = FK.Surface, scrimColor = FK.Scrim,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 12.dp).size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(FK.Surface3)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 34.dp).navigationBarsPadding()) {
            Text("Dieser Chat", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            Group {
                SettingRow("Inkognito", "wird nicht gespeichert", leading = {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(FK.T6.first), contentAlignment = Alignment.Center) { Text("🕶", fontSize = 15.sp) }
                }) { FkToggle(ui.incognito, { vm.setIncognito(it) }) }
                GroupDivider()
                SettingRow("Anweisung", null, leading = { Tile(Icons.Default.Person, FK.T4) }, onClick = { pickInstruction = !pickInstruction }) {
                    RowValue(ui.chatInstructionName.let { if (it == "Standard") "Keine" else it }); Chevron()
                }
                if (pickInstruction) {
                    (listOf("" to "Keine") + ui.instructions.map { it.id to (it.emoji + " " + it.name) }).forEach { (id, name) ->
                        GroupDivider()
                        SettingRow(name, null, leading = { Spacer(Modifier.width(30.dp)) }, onClick = { vm.setChatInstruction(id); pickInstruction = false }) {
                            if (ui.chatInstruction == id) Text("✓", color = FK.Accent, fontSize = 18.sp)
                        }
                    }
                    GroupDivider()
                    SettingRow("Anweisungen bearbeiten …", null, leading = { Spacer(Modifier.width(30.dp)) }, onClick = { onDismiss(); onInstructions() })
                }
                GroupDivider()
                SettingRow("Websuche", null, leading = { Tile(Icons.Default.Language, FK.T7) }) { FkToggle(ui.webSearch, { vm.setWeb(it) }) }
            }
            SectionLabel("Antwort")
            Group {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
                    val cs = listOf(de.foldki.app.data.Creativity.PRECISE, de.foldki.app.data.Creativity.STANDARD, de.foldki.app.data.Creativity.BALANCED, de.foldki.app.data.Creativity.CREATIVE)
                    val cLabels = listOf("genau", "normal", "ausgewogen", "kreativ")
                    var c by remember { mutableStateOf(cs.indexOf(ui.creativity).coerceAtLeast(0).toFloat()) }
                    Row(Modifier.padding(top = 8.dp)) {
                        Text("Kreativität", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, modifier = Modifier.weight(1f))
                        RowValue(cLabels[c.toInt().coerceIn(0, 3)])
                    }
                    FkSlider(c, { c = it }, 0f..3f, steps = 2, onDone = { vm.setCreativity(cs[c.toInt().coerceIn(0, 3)]) })
                    val ls = listOf(de.foldki.app.data.AnswerLength.SHORT, de.foldki.app.data.AnswerLength.NORMAL, de.foldki.app.data.AnswerLength.LONG, de.foldki.app.data.AnswerLength.FREE)
                    var l by remember { mutableStateOf(ls.indexOf(ui.answerLength).coerceAtLeast(0).toFloat()) }
                    Row(Modifier.padding(top = 8.dp)) {
                        Text("Länge", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, modifier = Modifier.weight(1f))
                        val cur = ls[l.toInt().coerceIn(0, 3)]
                        RowValue(if (cur.tokens == 0) "frei" else "bis ${cur.tokens} Tokens")
                    }
                    FkSlider(l, { l = it }, 0f..3f, steps = 2, onDone = { vm.setAnswerLength(ls[l.toInt().coerceIn(0, 3)]) })
                    ThinkSetting()
                }
            }
            SectionLabel("Ansicht")
            Group {
                SettingRow("Gedanken anzeigen", null) { FkToggle(ui.showThoughts, { vm.setShowThoughts(it) }) }
                GroupDivider()
                SettingRow("Statistik unter Antworten", null) { FkToggle(ui.showStats, { vm.setShowStats(it) }) }
            }
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
                ModelOption(Icons.Default.Diamond, FK.T3, m.name, LocalModelManager.gb(m.size) + if (de.foldki.app.ai.HfModels.isGguf(m.file)) " · GGUF · llama.cpp" else " · LiteRT · GPU", ui.activeModel == m.file) {
                    vm.selectModel(m.file); onDismiss()
                }
            }
            ui.servers.forEach { sv ->
                ModelOption(Icons.Default.Computer, FK.T6, sv.model.ifBlank { sv.name }, "PC-Server · ${sv.typeLabel} · ${sv.base}",
                    ui.activeModel == de.foldki.app.ai.PcServer.PREFIX + sv.id) {
                    vm.selectModel(de.foldki.app.ai.PcServer.PREFIX + sv.id); onDismiss()
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
private const val P_IMPROVE = "Verbessere diesen Text (kürzer, klarer, höflicher):\n"

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { h < 11 -> "Guten Morgen"; h < 18 -> "Guten Tag"; else -> "Guten Abend" }
}

/** Start screen exactly like mock-up 1: blue orb, "Wie kann ich helfen?", "Alles bleibt auf deinem Fold.", two cards. */
@Composable
private fun StartScreen(ui: UiState, vm: ChatViewModel, onSettings: () -> Unit, onCard: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.padding(bottom = 26.dp).size(92.dp)
                    .shadow(30.dp, RoundedCornerShape(24.dp), ambientColor = androidx.compose.ui.graphics.Color(0xFF6C5CFF), spotColor = androidx.compose.ui.graphics.Color(0xFF6C5CFF)),
            ) { AppLogo(92.dp) }
            Text("Wie kann ich helfen?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FK.Text, letterSpacing = (-0.4).sp, textAlign = TextAlign.Center, lineHeight = 32.sp)
            Text(
                if (ui.incognito) "Inkognito – dieser Chat wird nicht gespeichert." else "Alles bleibt auf deinem Fold.",
                fontSize = 15.sp, color = FK.Muted, textAlign = TextAlign.Center, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp),
            )
            if (ui.usesNano && !ui.nanoReady) {
                Spacer(Modifier.height(8.dp))
                Note(ui.nanoStatus)
                if (ui.nanoDownloadable) TextButton(onClick = { vm.downloadNano() }) { Text("Gemini Nano herunterladen", color = FK.Accent) }
                else TextButton(onClick = { vm.checkNano() }) { Text("Erneut prüfen", color = FK.Accent) }
            } else if (!ui.modelReady) {
                TextButton(onClick = onSettings) { Text("Modell wählen oder herunterladen", color = FK.Accent) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StartChip("📄 Brief beantworten", "PDF oder Foto anhängen") { onCard(P_REPLY, true) }
            StartChip("✍️ Text verbessern", "kürzer, klarer, höflicher") { onCard(P_IMPROVE, false) }
        }
    }
}

@Composable
private fun StartChip(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 160.dp).clip(RoundedCornerShape(16.dp)).background(FK.Surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, fontSize = 14.sp, color = FK.Text, maxLines = 1)
        Text(sub, fontSize = 12.sp, color = FK.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------- Chat screen

@Composable
private fun ChatScreen(
    ui: UiState, vm: ChatViewModel, showMenu: Boolean, onMenu: () -> Unit, onSettings: () -> Unit,
    sidebar: SidebarToggle? = null, onInstructions: () -> Unit = onSettings, onVoiceSettings: () -> Unit = onSettings,
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
        save = { m -> vm.saveAnswer(m) },
        retry = { m -> vm.regenerate(m.id) },
        pdf = ui.pdfEnabled,
    )

    if (voiceMode) {
        VoiceOverlay(ui, vm, voice, onClose = { voiceMode = false }, onSettings = { voiceMode = false; onVoiceSettings() })
        return
    }

    var text by rememberSaveable { mutableStateOf("") }
    var modelOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(FK.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(ui, showMenu, onMenu, sidebar, onModel = { modelOpen = true }, onNew = { vm.newChat() }, onOptions = { optionsOpen = true })
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        val typing = ui.generating && m === messages.last() && m.role == Role.ASSISTANT
                        MessageRow(m, typing = typing, actions = actions, liveCpu = if (typing) ui.liveCpu else -1,
                            showThoughts = ui.showThoughts, showStats = ui.showStats)
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
    if (optionsOpen) ChatOptionsSheet(ui, vm, onDismiss = { optionsOpen = false }, onInstructions = onInstructions)
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
    val save: (ChatMessage) -> Unit = {},
    val retry: (ChatMessage) -> Unit = {},
    val pdf: Boolean = true,
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

/** One message exactly like mock-up 11: files as cards, blue bubble, "Gedanken" pill, answer, stats line, text actions. */
@Composable
private fun MessageRow(m: ChatMessage, typing: Boolean, actions: MessageActions, liveCpu: Int = -1, showThoughts: Boolean = true, showStats: Boolean = true) {
    if (m.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            m.attachments.forEach { a -> FileCard(a, actions) }
            if (m.text.isNotBlank()) Box(Modifier.fillMaxWidth(0.78f), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp))
                        .background(FK.Accent).padding(horizontal = 14.dp, vertical = 10.dp),
                ) { SelectionContainer { Text(m.text, fontSize = 15.sp, lineHeight = 20.sp, color = androidx.compose.ui.graphics.Color.White) } }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            val parts = remember(m.text) { DocumentParser.parse(m.text) }
            val clean = remember(m.text) { DocumentParser.display(m.text) }
            val doc = parts.document
            val textColor = if (m.isError) FK.Danger else androidx.compose.ui.graphics.Color(0xFFF2F2F7)
            val stillThinking = typing && m.text.isEmpty() && m.thoughts.isNotEmpty()
            if (m.thoughts.isNotEmpty() && (showThoughts || stillThinking)) Thoughts(m.thoughts, stillThinking, liveCpu, m.thinkMs)
            if (doc == null) {
                if (!stillThinking) AnswerText(textColor, if (m.text.isEmpty() && typing) (if (m.web) "Suche im Internet …" else "…") else clean)
            } else {
                if (parts.before.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.before))
                if (!typing && actions.pdf) DocLine("Schreiben.pdf", if (parts.complete) "DIN 5008" else "unvollständig") { actions.savePdf(doc) }
                else SelectionContainer { Text(doc, fontSize = 15.sp, lineHeight = 22.sp, color = textColor) }
                if (parts.after.isNotBlank()) AnswerText(textColor, DocumentParser.clean(parts.after))
            }
            m.attachments.forEach { a -> DocCard(a, actions) }
            if (showStats && !typing && (m.tokens > 0 || m.answerMs > 0)) AnswerStats(m)
            if (!typing && !m.isError && m.text.isNotBlank()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextAction("⧉ Kopieren") { actions.copy(clean) }
                    TextAction(if (actions.speakingId == m.id) "■ Stopp" else "🔊 Vorlesen") { actions.speak(m.id, DocumentParser.spoken(m.text)) }
                    TextAction("☆ Merken") { actions.save(m) }
                    TextAction("↻") { actions.retry(m) }
                }
            }
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 13.sp, color = FK.Muted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp))
}

/** "GPU · 214 Tokens · 6,1 s · 35 Tokens/s" (only shown, never exported or spoken). */
@Composable
private fun AnswerStats(m: ChatMessage) {
    val de = java.util.Locale.GERMANY
    val parts = buildList {
        if (m.backend.isNotBlank()) add(m.backend)
        if (m.tokens > 0) add("%,d Tokens".format(de, m.tokens))
        if (m.answerMs > 0) add("%.1f s".format(de, m.answerMs / 1000.0))
        if (m.tokPerSec > 0f) add("%.0f Tokens/s".format(de, m.tokPerSec))
    }
    Text(parts.joinToString(" · "), fontSize = 12.sp, color = FK.Dim, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
}

/** "💭 Gedanken · 4 s ⌄": collapsed by default, tap to expand (never exported or spoken). */
@Composable
private fun Thoughts(text: String, thinking: Boolean, liveCpu: Int, thinkMs: Long) {
    var open by rememberSaveable { mutableStateOf(false) }
    val de = java.util.Locale.GERMANY
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FK.Surface).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (thinking) "💭 Denkt nach …" + if (liveCpu >= 0) " · CPU $liveCpu %" else ""
                else "💭 Gedanken" + if (thinkMs > 0) " · " + "%.0f s".format(de, thinkMs / 1000.0) else "",
                fontSize = 13.sp, color = FK.Muted, modifier = Modifier.weight(1f),
            )
            Text(if (open) "⌃" else "⌄", fontSize = 13.sp, color = FK.Muted)
        }
        if (open) SelectionContainer { Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = FK.Muted, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun AnswerText(color: androidx.compose.ui.graphics.Color, text: String) {
    SelectionContainer { Text(text, fontSize = 15.sp, lineHeight = 22.5.sp, color = color) }
}

/** Letter/PDF card in an answer (".doccard"): orange icon, name, kind, "Öffnen". */
@Composable
private fun DocLine(name: String, sub: String, onOpen: () -> Unit) {
    Row(
        Modifier.padding(top = 10.dp).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FK.Surface).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(Icons.Default.Description, FK.T1, size = 40.dp, radius = 10.dp, iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.sp, color = FK.Muted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Cta("Öffnen", onClick = onOpen)
    }
}

@Composable
private fun pageCount(a: Attachment): Int {
    val pages by produceState(0, a.path) {
        value = if (!a.isPdf) 0 else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(java.io.File(a.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }
    return pages
}

/** File the user attached (".att" of mock-up 11): right-aligned card with red icon, name and pages. */
@Composable
private fun FileCard(a: Attachment, actions: MessageActions) {
    val pages = pageCount(a)
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(FK.Surface).clickable { actions.open(a) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(if (a.isPdf) Icons.Default.Description else Icons.Default.Image, FK.Danger to androidx.compose.ui.graphics.Color.White)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Text(if (!a.isPdf) "Bild" else if (pages == 1) "1 Seite" else if (pages > 1) "$pages Seiten" else "PDF", fontSize = 13.sp, color = FK.Muted)
        }
    }
}

/** Finished PDF in an answer: same card as [DocLine]. Tap = open, long press = share. */
@Composable
private fun DocCard(a: Attachment, actions: MessageActions) {
    val pages = pageCount(a)
    DocLine(a.name, listOfNotNull(
        if (a.isPdf) "DIN 5008" else "Bild",
        if (pages == 1) "1 Seite" else if (pages > 1) "$pages Seiten" else null,
        if (actions.hasSignature && a.isPdf) "mit Unterschrift" else null,
    ).joinToString(" · ")) { actions.open(a) }
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

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(shape).background(FK.Surface).border(0.5.dp, FK.Surface3, shape).padding(6.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundOutline(onAttach) { Icon(Icons.Default.Add, "Hinzufügen", tint = FK.Text, modifier = Modifier.size(18.dp)) }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
                    if (text.isEmpty()) Text(
                        when {
                            recognizing -> "Erkenne…"; dictating -> "Ich höre zu…"
                            ui.pending.isNotEmpty() -> "Frage zum Anhang …"; ui.webSearch -> "Im Internet nachfragen …"
                            ui.current == null -> "Frag etwas …"
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
                if (!ui.generating && text.isBlank()) RoundOutline(dictate) {
                    if (recognizing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = FK.Accent)
                    else Icon(if (dictating) Icons.Default.MicOff else Icons.Default.Mic, if (dictating) "Diktat beenden" else "Diktieren",
                        tint = if (dictating) FK.Accent else FK.Text, modifier = Modifier.size(18.dp))
                }
                val canSend = (text.isNotBlank() || ui.pending.isNotEmpty()) && !ui.importing
                SendButton(
                    icon = when {
                        ui.generating -> Icons.Default.Stop
                        text.isBlank() && ui.pending.isEmpty() && !dictating -> Icons.Default.GraphicEq
                        else -> Icons.Default.ArrowUpward
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
private fun SendButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp = 36.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) androidx.compose.ui.graphics.Color.White else FK.Surface3).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.size(18.dp)) }
}
