package de.foldki.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FkChip("Suche", active = !web) { web = false }
            FkChip("huggingface.co", active = web) { web = true }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (web) WebMode(vm, onOpen = { repo -> detail = repo; web = false })
            else SearchMode(vm, ui, detail, onDetail = { detail = it })
        }
    }
}

// ---------------------------------------------------------------- Suche

@Composable
private fun SearchMode(vm: ChatViewModel, ui: UiState, detail: String?, onDetail: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("downloads") }
    var repos by remember { mutableStateOf<List<HfModels.Repo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(sort, reload) {
        error = null; repos = null
        runCatching { withContext(Dispatchers.IO) { HfModels.fetchOfficial(sort) } }
            .onSuccess { repos = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val installed = ui.localModels.map { it.file }.toSet()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val list: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                FkField("Suchen (z. B. qwen, gemma, deepseek)", query, { query = it })
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HfModels.SORTS.forEach { (k, label) -> FkChip(label, active = sort == k) { sort = k } }
                }
                val all = repos
                when {
                    error != null -> Column(Modifier.padding(18.dp)) {
                        Text("Liste konnte nicht geladen werden: $error", color = ERR, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        FkButton("Erneut versuchen", ghost = true) { reload++ }
                    }
                    all == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FK.Accent) }
                    else -> {
                        val q = query.trim().lowercase()
                        val shown = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
                        Note(
                            "${shown.size} Modelle von Googles litert-community – passen zur App. Funktionen sind aus den Schlagworten geschätzt.",
                            Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(160.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.id }) { r ->
                                RepoCard(r, have = r.usableFiles.any { it.substringAfterLast('/') in installed }, active = r.id == detail) { onDetail(r.id) }
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
                else Note("Wähle links ein Modell, um Größen, Funktionen und Beschreibung zu sehen.", Modifier.padding(24.dp))
            }
        } else {
            list(Modifier.fillMaxSize())
            if (detail != null) Box(Modifier.fillMaxSize().background(FK.Bg)) { Detail(vm, ui, detail, onClose = { onDetail(null) }) }
        }
        if (!wide && detail != null) BackHandler { onDetail(null) }
    }
}

@Composable
private fun RepoCard(r: HfModels.Repo, have: Boolean, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 118.dp).background(if (active) FK.AccentSoft else FK.Surface, shape)
            .border(1.dp, if (active || have) FK.Accent else FK.Line, shape).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(
            fmtNum(r.downloads) + " Downloads" + (if (r.parameter.isNotEmpty()) " · ${r.parameter}" else ""),
            fontSize = 11.sp, color = FK.Muted, maxLines = 1,
        )
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (have) Tag("installiert", on = true)
            r.features.forEach { Tag(it) }
        }
    }
}

// ---------------------------------------------------------------- Detail

@Composable
private fun Detail(vm: ChatViewModel, ui: UiState, id: String, onClose: (() -> Unit)?) {
    var repo by remember(id) { mutableStateOf<HfModels.Repo?>(null) }
    var files by remember(id) { mutableStateOf<List<HfModels.LmFile>?>(null) }
    var readme by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
        if (onClose != null) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zur Liste", tint = FK.Text) }
            Text("Zurück zur Liste", fontSize = 13.sp, color = FK.Muted)
        }
        Text(id.substringAfter('/'), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = FK.Text)
        Text(id, fontSize = 12.sp, color = FK.Muted)
        val r = repo
        if (error != null) { Spacer(Modifier.height(12.dp)); Text("Details nicht ladbar: $error", color = ERR, fontSize = 13.sp); return@Column }
        if (r == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FK.Accent) }; return@Column }

        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Downloads", fmtNum(r.downloads)); Stat("Likes", fmtNum(r.likes))
            ago(r.updated).takeIf { it.isNotEmpty() }?.let { Stat("Aktualisiert", it) }
        }
        if (!r.official) Text(
            "Nicht von Google (litert-community) umgewandelt – läuft in der App vielleicht nicht.",
            color = WARN, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
        )

        SectionLabel("Download-Optionen")
        when (val st = ui.modelOp) {
            is LocalModelManager.State.Downloading -> {
                Text("Lädt ${HfModels.displayName(st.name)}… ${st.pct} %", fontSize = 13.sp, color = FK.Text)
                LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = FK.Accent, trackColor = FK.Line)
            }
            is LocalModelManager.State.Verifying -> Text("Prüfe ${HfModels.displayName(st.name)}… ${st.pct} %", fontSize = 13.sp, color = FK.Text)
            is LocalModelManager.State.Failed -> Text(st.message, fontSize = 13.sp, color = ERR)
            else -> Unit
        }
        val all = files.orEmpty()
        val usable = all.filter { it.usable }
        val rec = HfModels.recommend(usable)
        if (usable.isEmpty()) Note(
            if (all.isEmpty()) "In diesem Repo liegen keine Modelldateien. Fold KI lädt nur .litertlm-Dateien (LiteRT-LM)."
            else "Keine passende .litertlm-Datei – die Dateien hier sind für andere Programme (z. B. .task, .tflite, .gguf oder Webbrowser).",
            Modifier.padding(vertical = 6.dp),
        )
        Group {
            all.forEachIndexed { i, f ->
                if (i > 0) GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(f.fileName, fontSize = 13.sp, color = if (f.usable) FK.Text else FK.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(LocalModelManager.gb(f.size), fontSize = 12.sp, color = FK.Muted)
                        if (f.bits.isNotEmpty()) Tag(f.bits)
                        if (f.usable) {
                            val (fit, level) = HfModels.fitText(ram, f.size)
                            if (fit.isNotEmpty()) Text(fit, fontSize = 11.sp, color = when (level) { 1 -> FK.Ok; 2 -> WARN; else -> ERR })
                            if (f == rec) Tag("Empfohlen", on = true)
                        } else Text("anderes Format – geht nicht", fontSize = 11.sp, color = FK.Muted)
                    }
                    if (f.usable) Box(Modifier.padding(top = 8.dp)) {
                        if (f.fileName in installed) Tag("Installiert", on = true)
                        else FkButton("Laden", ghost = f != rec, enabled = !vm.models.busy) { start(f) }
                    }
                }
            }
        }

        SectionLabel("Details")
        Group {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.parameter.isNotEmpty()) KV("Parameter") { Tag(r.parameter) }
                KV("Format") { Tag(if (usable.isNotEmpty()) "LiteRT-LM (.litertlm)" else "kein .litertlm") }
                if (r.pipeline.isNotEmpty()) KV("Art") { Tag(r.pipeline) }
                KV("Funktionen") {
                    if (r.features.isEmpty()) Text("–", fontSize = 13.sp, color = FK.Muted) else r.features.forEach { Tag(it) }
                }
                Note("Funktionen sind nur aus den Schlagworten geschätzt. Fold KI gibt den Modellen bisher keine Bilder und – außer GitHub – keine Werkzeuge.")
            }
        }

        SectionLabel("Beschreibung")
        when (val md = readme) {
            null -> Note("Lädt …")
            "" -> Note("Keine Beschreibung vorhanden.")
            else -> Group { SelectionContainer { Text(md, fontSize = 12.sp, lineHeight = 18.sp, color = FK.Muted, modifier = Modifier.padding(14.dp)) } }
        }
        Spacer(Modifier.height(24.dp))
    }
    big?.let { f -> BigModelWarning(f, ram, onDismiss = { big = null }) { big = null; vm.downloadModel(f) } }
}

@Composable
private fun Stat(k: String, v: String) {
    Column(Modifier.background(FK.Surface, RoundedCornerShape(12.dp)).border(1.dp, FK.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(k, fontSize = 10.sp, color = FK.Muted)
        Text(v, fontSize = 14.sp, color = FK.Text, fontWeight = FontWeight.Medium)
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
private fun WebMode(vm: ChatViewModel, onOpen: (String) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf("huggingface.co") }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = canBack) { view?.goBack() }

    // A tap on a .litertlm file: look up size + SHA-256 and download it.
    val fileTapped: (String, String) -> Unit = { r, path ->
        scope.launch {
            val f = runCatching { withContext(Dispatchers.IO) { HfModels.fetchFiles(r) } }.getOrNull()?.firstOrNull { it.path == path }
            when {
                f == null -> vm.notify("Datei nicht gefunden – öffne das Modell mit „In Fold KI laden“.")
                !f.usable -> vm.notify("„${f.fileName}“ passt nicht zur App (nur .litertlm, nicht für Webbrowser).")
                else -> { vm.downloadModel(f); vm.notify("Download gestartet: ${f.fileName} – Stand unter KI-Modelle.") }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { view?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = if (canBack) FK.Text else FK.Dim) }
            IconButton(onClick = { view?.goForward() }, enabled = canFwd) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Vorwärts", tint = if (canFwd) FK.Text else FK.Dim) }
            IconButton(onClick = { view?.reload() }) { Icon(Icons.Default.Refresh, "Neu laden", tint = FK.Text) }
            Text(url, fontSize = 12.sp, color = FK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        FkButton(
            repo?.let { "„${it.substringAfter('/')}“ in Fold KI laden" } ?: "In Fold KI laden (Modellseite öffnen)",
            Modifier.padding(horizontal = 14.dp, vertical = 4.dp), enabled = repo != null,
        ) { repo?.let(onOpen) }
        Note(
            "Auf einer Modellseite „In Fold KI laden“ tippen, oder unter „Files“ direkt auf eine .litertlm-Datei.",
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
        )
        HorizontalDivider(color = FK.Line)
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { it.stopLoading(); it.destroy() },
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true // the Hugging Face pages need it
                    settings.domStorageEnabled = true
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
                            canBack = v.canGoBack(); canFwd = v.canGoForward()
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
    }
}
