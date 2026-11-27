package de.foldki.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ERR = Color(0xFFF2B8B5)
private val WARN = Color(0xFFE8C07A)

/**
 * "Erkunden" like in FA KI Desktop: switch "Suche | huggingface.co".
 * - Suche: grid of all LiteRT-LM models of litert-community (Google's conversions, built for the runtime in the app),
 *   with search, sort and the features "Bilder / Werkzeuge / Denken". Tap → detail page with the download table.
 *   Unfolded: list left, details right. Folded: the detail page opens over the list.
 * - huggingface.co: the real web page. "In Fold KI laden" opens the detail page of the shown model; a tap on a
 *   .litertlm file downloads it directly.
 */
@Composable
fun HfBrowser(vm: ChatViewModel, ui: UiState, onDone: () -> Unit) {
    var web by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    // Top bar like mock-up 9: "huggingface.co" on the right; on the web page ‹ goes back to the search.
    if (web) PageBack({ web = false }, hideTitle = true, hideBar = true)
    else PageAction { Cta("huggingface.co", ghost = true) { web = true } }
    Box(Modifier.fillMaxSize()) {
        if (web) WebMode(vm, onOpen = { repo -> detail = repo; web = false }, onClose = { web = false })
        else SearchMode(vm, ui, detail, onDetail = { detail = it })
    }
}

// ---------------------------------------------------------------- Suche

private val FILTERS = listOf("Beliebt", "Neu", "Klein", "Denken", "Bilder")

/** Ab 4.9: only chat models – embedding, speech, vision-encoder and similar models cannot answer in the chat. */
private val NOT_CHAT = Regex("embed|parakeet|whisper|\\btts\\b|asr|speech|rerank|siglip|clip|mobilenet|efficientnet|yolo|segment|depth|vit-|-vit|wav2vec|moonshine|kokoro|piper|bert", RegexOption.IGNORE_CASE)
private fun chatModel(r: HfModels.Repo): Boolean = !NOT_CHAT.containsMatchIn(r.id)

/** Small model: at most 2 B parameters (from the name). */
private fun small(r: HfModels.Repo): Boolean {
    val p = r.parameter
    val n = p.dropLast(1).toDoubleOrNull() ?: return false
    return if (p.endsWith("M")) true else n <= 2.0
}

@Composable
private fun SearchMode(vm: ChatViewModel, ui: UiState, detail: String?, onDetail: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var repos by remember { mutableStateOf<List<HfModels.Repo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // 0 = LiteRT (litert-community, GPU), 1 = GGUF (llama.cpp, also LM Studio files; searched on the server)
    var fmt by rememberSaveable { mutableIntStateOf(0) }
    val sort = if (filter == 1) "lastModified" else "downloads"
    LaunchedEffect(sort, reload, fmt, if (fmt == 1) query else "") {
        if (fmt == 1) kotlinx.coroutines.delay(400)
        error = null; repos = null
        runCatching { withContext(Dispatchers.IO) { if (fmt == 1) HfModels.fetchGguf(sort, query) else HfModels.fetchOfficial(sort) } }
            .onSuccess { repos = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val installed = ui.localModels.map { it.file }.toSet()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val list: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                SearchBox(query, { query = it }, "Modelle suchen", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                Segmented(listOf("LiteRT · GPU", "GGUF"), fmt, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) { fmt = it }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILTERS.forEachIndexed { i, label -> FkChip(label, active = filter == i) { filter = i } }
                }
                val all = repos
                when {
                    error != null -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Liste konnte nicht geladen werden: $error", color = ERR, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Cta("Erneut versuchen", ghost = true) { reload++ }
                    }
                    all == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FK.Accent) }
                    else -> {
                        val q = query.trim().lowercase()
                        val shown = all.filter { r ->
                            chatModel(r) && (q.isEmpty() || fmt == 1 || r.name.lowercase().contains(q)) && when (filter) {
                                2 -> small(r); 3 -> "Denken" in r.features; 4 -> "Bilder" in r.features; else -> true
                            }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(160.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.id }) { r ->
                                RepoCard(r, have = r.usableFiles.any { it.substringAfterLast('/') in installed }, active = wide && r.id == detail) { onDetail(r.id) }
                            }
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f).fillMaxHeight())
            VerticalDivider(color = FK.Line)
            Box(Modifier.weight(1.2f).fillMaxHeight()) {
                if (detail != null) Detail(vm, ui, detail, onClose = null)
                else Sub("Wähle links ein Modell.")
            }
        } else {
            list(Modifier.fillMaxSize())
            if (detail != null) {
                PageBack({ onDetail(null) }, hideTitle = true)
                Box(Modifier.fillMaxSize().background(FK.Bg)) { Detail(vm, ui, detail, onClose = { onDetail(null) }) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepoCard(r: HfModels.Repo, have: Boolean, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(FK.Surface)
            .then(if (active) Modifier.border(1.5.dp, FK.Accent, shape) else Modifier).clickable(onClick = onClick).padding(14.dp),
    ) {
        ModelLogo(HfModels.vendorOf(r.id), 36.dp)
        Text(HfModels.prettyName(r.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        Text(HfModels.vendorOf(r.id), fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(fmtNum(r.downloads) + " Downloads", fontSize = 12.sp, color = FK.Muted, maxLines = 1)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (have) Pill("Installiert", FK.Ok)
            if (gpuRepo(r)) Pill("GPU", FK.Accent)
            if (r.usableFiles.any { HfModels.isGguf(it) }) Pill("GGUF", FK.Warn)
            r.features.forEach { f -> Pill(f, if (f == "Denken") FK.Purple else FK.Muted) }
        }
    }
}

private fun gpuRepo(r: HfModels.Repo): Boolean =
    r.usableFiles.any { HfModels.isGpuFile(it) } || (r.official && r.name.lowercase().startsWith("gemma") && r.usableFiles.isNotEmpty())

// ---------------------------------------------------------------- Detail

/** Detail page exactly like mock-up 10: logo + title, pills, numbers, files with "Laden", about the model. */
@Composable
private fun Detail(vm: ChatViewModel, ui: UiState, id: String, onClose: (() -> Unit)?) {
    var repo by remember(id) { mutableStateOf<HfModels.Repo?>(null) }
    var files by remember(id) { mutableStateOf<List<HfModels.LmFile>?>(null) }
    var readme by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var more by remember(id) { mutableStateOf(false) }
    var big by remember { mutableStateOf<HfModels.LmFile?>(null) }
    val ram = remember { vm.models.totalRam() }
    LaunchedEffect(id) {
        runCatching {
            withContext(Dispatchers.IO) { HfModels.fetchRepo(id) to HfModels.fetchFiles(id) }
        }.onSuccess { (r, f) -> repo = r; files = f }.onFailure { error = it.message ?: it.javaClass.simpleName }
        readme = withContext(Dispatchers.IO) { HfModels.fetchReadme(id) }
    }
    val installed = ui.localModels.map { it.file }.toSet()
    val start: (HfModels.LmFile) -> Unit = { f -> if (HfModels.tooBigFor(ram, f.size)) big = f else vm.downloadModel(f) }
    val name = id.substringAfter('/')

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp)) {
            ModelLogo(HfModels.vendorOf(id), 60.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(HfModels.prettyName(name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = FK.Text, lineHeight = 30.sp)
                Text(HfModels.vendorOf(id) + " · " + id.substringBefore('/'), fontSize = 13.sp, color = FK.Muted)
            }
        }
        val r = repo
        if (error != null) { Sub("Details nicht ladbar: $error"); return@Column }
        if (r == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FK.Accent) }; return@Column }
        val all = files.orEmpty()
        val usable = all.filter { it.usable }
        val rec = HfModels.recommend(usable)

        Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (usable.any { it.forGpu } || gpuRepo(r)) Pill("GPU", FK.Accent)
            if (usable.any { HfModels.isGguf(it.fileName) }) Pill("GGUF", FK.Warn)
            r.features.forEach { f -> Pill(f, if (f == "Denken") FK.Purple else FK.Muted) }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FK.Surface).padding(vertical = 12.dp, horizontal = 6.dp)) {
            Stat("Downloads", fmtNum(r.downloads), Modifier.weight(1f))
            Stat("Parameter", r.parameter.ifEmpty { "–" }, Modifier.weight(1f))
            val ctx = rec?.let { if (HfModels.isGguf(it.fileName)) de.foldki.app.ai.GgufEngine.N_CTX else de.foldki.app.ai.LocalEngine.contextFor(it.fileName) }
            Stat("Kontext", ctx?.toString() ?: "–", Modifier.weight(1f))
        }

        SectionLabel("Dateien")
        when (val st = ui.modelOp) {
            is LocalModelManager.State.Downloading -> Card(Modifier.padding(bottom = 10.dp)) {
                Text("Lädt ${HfModels.displayName(st.name)} … ${st.pct} %", fontSize = 15.sp, color = FK.Text)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = FK.Accent, trackColor = FK.Surface3)
            }
            is LocalModelManager.State.Verifying -> Card(Modifier.padding(bottom = 10.dp)) { Text("Prüfe ${HfModels.displayName(st.name)} … ${st.pct} %", fontSize = 15.sp, color = FK.Text) }
            is LocalModelManager.State.Failed -> Card(Modifier.padding(bottom = 10.dp)) { Text(st.message, fontSize = 14.sp, color = ERR) }
            else -> Unit
        }
        if (usable.isEmpty()) Sub("In diesem Repo liegt keine Datei, die Fold KI laden kann (.litertlm oder einzelne .gguf).")
        else Group {
            usable.forEachIndexed { i, f ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = FK.Line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(shortFile(name, f.fileName), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val (fit, level) = HfModels.fitText(ram, f.size)
                        Row {
                            Text(listOf(LocalModelManager.gb(f.size), f.bits).filter { it.isNotEmpty() }.joinToString(" · ") + if (fit.isNotEmpty()) " · " else "",
                                fontSize = 13.sp, color = FK.Muted)
                            if (fit.isNotEmpty()) Text(if (level == 1) "passt aufs Handy" else if (level == 2) "knapp" else "zu groß", fontSize = 13.sp,
                                color = when (level) { 1 -> FK.Ok; 2 -> FK.Warn; else -> ERR })
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (f.forGpu) Pill("GPU", FK.Accent)
                            else if (!HfModels.isGguf(f.fileName) && usable.any { it.forGpu }) Pill("nur Prozessor", FK.Muted)
                            if (f == rec) Pill("Empfohlen", FK.Ok)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    if (f.fileName in installed) Pill("Installiert", FK.Ok)
                    else Cta("Laden", ghost = f != rec, enabled = !vm.models.busy) { start(f) }
                }
            }
        }

        SectionLabel("Über das Modell")
        Card {
            when (val md = readme) {
                null -> Text("Lädt …", fontSize = 14.sp, color = FK.Muted)
                "" -> Text("Keine Beschreibung vorhanden.", fontSize = 14.sp, color = FK.Muted)
                else -> {
                    val plain = remember(md) { md.replace(Regex("[#*`>|_]+"), "").replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1").replace(Regex("\\n{3,}"), "\n\n").trim() }
                    SelectionContainer { Text(if (more) plain else plain.take(280) + if (plain.length > 280) " …" else "", fontSize = 14.sp, lineHeight = 20.sp, color = FK.Muted) }
                    if (plain.length > 280) Text(if (more) "Weniger" else "Mehr lesen", color = FK.Accent, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp).clickable { more = !more })
                }
            }
        }
    }
    big?.let { f -> BigModelWarning(f, ram, onDismiss = { big = null }) { big = null; vm.downloadModel(f) } }
}

/** "Qwen3.5-4B_mixed_int4_gpu_composite.litertlm" in repo "Qwen3.5-4B" → "mixed_int4_gpu_composite". */
private fun shortFile(repoName: String, file: String): String {
    var f = file.removeSuffix(HfModels.EXT).removeSuffix(HfModels.GGUF)
    val base = repoName.removeSuffix("-GGUF").removeSuffix("-gguf").removeSuffix("-litert-lm")
    if (f.startsWith(base, ignoreCase = true)) f = f.substring(base.length).trimStart('_', '-', '.')
    return f.ifBlank { file }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, fontSize = 17.sp, color = FK.Text, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(k, fontSize = 12.sp, color = FK.Muted, maxLines = 1)
    }
}

@Composable
private fun KV(k: String, v: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = FK.Muted, modifier = Modifier.width(100.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { v() }
    }
}

private fun fmtNum(n: Long): String = when {
    n >= 1_000_000 -> "%.1f Mio.".format(java.util.Locale.GERMANY, n / 1e6)
    n >= 1_000 -> "%.1f Tsd.".format(java.util.Locale.GERMANY, n / 1e3)
    else -> n.toString()
}

private fun ago(iso: String): String {
    val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return ""
    val d = ((System.currentTimeMillis() - t) / 86_400_000L).coerceAtLeast(0)
    return when {
        d < 1 -> "heute"
        d == 1L -> "vor 1 Tag"
        d < 60 -> "vor $d Tagen"
        d < 730 -> "vor ${d / 30} Monaten"
        else -> "vor ${d / 365} Jahren"
    }
}

// ---------------------------------------------------------------- huggingface.co

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebMode(vm: ChatViewModel, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf("huggingface.co") }
    // Ab 4.9: own address/search field (Compose) – typing, copying and pasting work here even where the web page's
    // own fields do not show the text. Enter = open the address or search Hugging Face.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(enabled = canBack) { view?.goBack() }

    // A tap on a .litertlm/.gguf file: look up size + SHA-256 and download it.
    val fileTapped: (String, String) -> Unit = { r, path ->
        scope.launch {
            val f = runCatching { withContext(Dispatchers.IO) { HfModels.fetchFiles(r) } }.getOrNull()?.firstOrNull { it.path == path }
            when {
                f == null -> vm.notify("Datei nicht gefunden – öffne das Modell mit „In App laden“.")
                !f.usable -> vm.notify("„${f.fileName}“ passt nicht zur App (nur .litertlm oder einzelne .gguf, nicht für Webbrowser).")
                else -> { vm.downloadModel(f); vm.notify("Download gestartet: ${f.fileName} – Stand unter Modelle.") }
            }
        }
    }
    val go: () -> Unit = {
        val t = field.text.trim()
        if (t.isNotEmpty()) {
            val target = when {
                t.startsWith("http://") || t.startsWith("https://") -> t
                t.startsWith("huggingface.co") || t.startsWith("hf.co") -> "https://$t"
                Regex("^[\\w.-]+/[\\w.-]+$").matches(t) -> "https://huggingface.co/$t"
                else -> HfModels.searchUrl(t)
            }
            view?.loadUrl(target)
        }
        editing = false
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        // One slim row: back, address (with copy / paste), close.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NavBtn(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", bg = FK.Surface) { if (canBack) view?.goBack() else onClose() }
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(FK.Surface)
                    .border(1.5.dp, if (editing) FK.Accent else Color.Transparent, RoundedCornerShape(20.dp)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = if (editing) field else androidx.compose.ui.text.input.TextFieldValue(url),
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = FK.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(FK.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }),
                    modifier = Modifier.weight(1f).onFocusChanged { st ->
                        if (st.isFocused && !editing) { editing = true; field = androidx.compose.ui.text.input.TextFieldValue(url, androidx.compose.ui.text.TextRange(0, url.length)) }
                    },
                )
                Box(Modifier.size(32.dp).clip(CircleShape).clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(if (editing) field.text else "https://$url")); vm.notify("Kopiert") },
                    contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentCopy, "Kopieren", tint = FK.Muted, modifier = Modifier.size(17.dp)) }
                Box(Modifier.size(32.dp).clip(CircleShape).clickable {
                    val t = clipboard.getText()?.text.orEmpty()
                    if (t.isNotBlank()) { editing = true; field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
                }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentPaste, "Einfügen", tint = FK.Muted, modifier = Modifier.size(17.dp)) }
            }
            Spacer(Modifier.width(6.dp))
            if (editing) NavBtn(Icons.AutoMirrored.Filled.ArrowForward, "Öffnen", bg = FK.Accent, tint = Color.White) { go() }
            else NavBtn(Icons.Default.Close, "Schließen", bg = FK.Surface) { onClose() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopLoading(); it.destroy() },
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true // the Hugging Face pages need it
                        settings.domStorageEnabled = true
                        // Fields of the web page get the keyboard and long-press menu (copy / paste).
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isLongClickable = true
                        setOnTouchListener { v, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN && !v.hasFocus()) v.requestFocus(); false }
                        setBackgroundColor(android.graphics.Color.parseColor("#1C1B1A"))
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                val r = HfModels.repoFromUrl(u)
                                val path = HfModels.fileFromUrl(u)
                                if (r != null && path != null) { fileTapped(r, path); return true }
                                val host = request.url.host?.lowercase().orEmpty()
                                // Stay on Hugging Face (no other sites inside the app).
                                return !(host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co")
                            }

                            override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
                                canBack = v.canGoBack()
                                url = u.removePrefix("https://")
                                // HF is a single-page app: a tap on a model changes the address without a new page load.
                                repo = HfModels.repoFromUrl(u)
                            }
                        }
                        setDownloadListener { u, _, _, _, _ ->
                            val r = HfModels.repoFromUrl(u); val p = HfModels.fileFromUrl(u)
                            if (r != null && p != null) fileTapped(r, p)
                        }
                        loadUrl(HfModels.searchUrl(""))
                        view = this
                    }
                },
            )
            // On a model page: a small bar at the bottom instead of the big button at the top.
            val r = repo
            if (r != null) Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FK.Surface2)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Modell erkannt", fontSize = 12.sp, color = FK.Muted)
                    Text(r.substringAfter('/'), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Cta("In App laden") { onOpen(r) }
            }
        }
    }
}
