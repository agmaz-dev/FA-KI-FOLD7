package de.foldki.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Model grid: all LiteRT-LM models of litert-community (Google's own conversions, built for the runtime in the app),
 * most downloaded first. Search filters the list. Tap a card → its .litertlm files with size → download.
 * Files of other uploaders often do not load (they are converted differently), so they are not offered here.
 */
@Composable
fun HfBrowser(vm: ChatViewModel, ui: UiState, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<HfModels.Repo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        error = null
        runCatching { withContext(Dispatchers.IO) { HfModels.fetchOfficial() } }
            .onSuccess { repos = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    Column(Modifier.fillMaxSize()) {
        FkField("Suchen (z. B. qwen, gemma, deepseek)", query, { query = it })
        Note(
            "Hier stehen nur Googles geprüfte LiteRT-Modelle (litert-community) – die passen zur App. Tippe auf ein Modell, dann auf eine Datei.",
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
        )
        val list = repos
        when {
            error != null -> Column(Modifier.padding(18.dp)) {
                Text("Liste konnte nicht geladen werden: $error", color = androidx.compose.ui.graphics.Color(0xFFF2B8B5), fontSize = 13.sp)
                Spacer(Modifier.heightIn(min = 10.dp))
                FkButton("Erneut versuchen", ghost = true) { reload++ }
            }
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FK.Accent)
            }
            else -> {
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) list else list.filter { it.name.lowercase().contains(q) }
                if (shown.isEmpty()) Note("Kein Modell gefunden.", Modifier.padding(18.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { r -> RepoCard(r, have = r.usableFiles.any { f -> ui.localModels.any { it.file == f.substringAfterLast('/') } }) { open = r.id } }
                }
            }
        }
    }
    open?.let { r -> FilePicker(vm, r, onDismiss = { open = null }, onStarted = { open = null; onDone() }) }
}

@Composable
private fun RepoCard(r: HfModels.Repo, have: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 110.dp).background(FK.Surface, shape).border(1.dp, if (have) FK.Accent else FK.Line, shape)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.heightIn(min = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%,d".format(java.util.Locale.GERMANY, r.downloads) + " Downloads", fontSize = 11.sp, color = FK.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            if (have) { Tag("geladen", on = true); Spacer(Modifier.width(6.dp)) }
            if (r.thinking) Tag("denkt nach")
            else Tag(if (r.usableFiles.size == 1) "1 Datei" else "${r.usableFiles.size} Dateien")
        }
    }
}

/** The .litertlm files of one repo: size, recommended 4-bit file; other formats shown but not selectable. */
@Composable
private fun FilePicker(vm: ChatViewModel, repo: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    var files by remember(repo) { mutableStateOf<List<HfModels.LmFile>?>(null) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    var big by remember { mutableStateOf<HfModels.LmFile?>(null) }
    val ram = remember { vm.models.totalRam() }
    LaunchedEffect(repo) {
        runCatching { withContext(Dispatchers.IO) { HfModels.fetchFiles(repo) } }
            .onSuccess { files = it.filter { f -> f.usable } }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val start: (HfModels.LmFile) -> Unit = { f ->
        if (HfModels.tooBigFor(ram, f.size)) big = f else { vm.downloadModel(f); onStarted() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FK.Surface,
        title = { Text(repo.substringAfter('/'), color = FK.Text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val list = files
                when {
                    error != null -> Text("Dateien konnten nicht geladen werden: $error", color = androidx.compose.ui.graphics.Color(0xFFF2B8B5))
                    list == null -> CircularProgressIndicator(Modifier.padding(16.dp), color = FK.Accent)
                    list.isEmpty() -> Text("Dieses Modell hat keine passende .litertlm-Datei.", color = FK.Muted)
                    else -> {
                        val rec = HfModels.recommend(list)
                        list.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(thickness = 1.dp, color = FK.Line)
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { start(f) }.padding(vertical = 8.dp)) {
                                Text(f.fileName, fontSize = 14.sp, color = FK.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    LocalModelManager.gb(f.size) + (if (f.bits.isNotEmpty()) " · ${f.bits}" else "") + when {
                                        f == rec -> " · empfohlen"
                                        HfModels.tooBigFor(ram, f.size) -> " · zu groß fürs Handy"
                                        else -> ""
                                    },
                                    fontSize = 12.sp, color = if (f == rec) FK.Accent else FK.Muted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = FK.Muted) } },
    )
    big?.let { f -> BigModelWarning(f, ram, onDismiss = { big = null }) { big = null; vm.downloadModel(f) } }
}
