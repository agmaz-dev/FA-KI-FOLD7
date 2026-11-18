package de.foldki.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mini browser for models on Hugging Face. The user scrolls himself; opening a model page (or a file link) shows
 * its model files with sizes from the HF API – only .litertlm files can be downloaded (LiteRT-LM).
 * Only huggingface.co is opened inside; the chosen file is downloaded by [LocalModelManager].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HfBrowser(vm: ChatViewModel, ui: UiState, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<String?>(null) }
    var lastRepo by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = canBack) { web?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FkField("Suche (z. B. litert qwen)", query, { query = it }, modifier = Modifier.weight(1f))
            IconButton(onClick = { web?.loadUrl(HfModels.searchUrl(query)) }) { Icon(Icons.Default.Search, "Suchen", tint = FK.Text) }
        }
        Note(
            "Tippe auf ein Modell, dann zeigt die App seine Dateien. Nur .litertlm geht – am besten 4-Bit. Viele stehen bei „litert-community“.",
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { it.stopLoading(); it.destroy() },
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true // the Hugging Face model list needs it
                    settings.domStorageEnabled = true
                    setBackgroundColor(android.graphics.Color.parseColor("#1C1B1A"))
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            val r = HfModels.repoFromUrl(url)
                            if (r != null && HfModels.fileFromUrl(url) != null) { repo = r; return true }
                            val host = request.url.host?.lowercase().orEmpty()
                            // Stay on Hugging Face (no other sites inside the app).
                            return !(host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co")
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            canBack = view.canGoBack()
                            // HF is a single-page app: a tap on a model changes the address without a new page load.
                            val r = HfModels.repoFromUrl(url)
                            if (r != null && r != lastRepo) { lastRepo = r; repo = r }
                            if (r == null) lastRepo = null
                        }
                    }
                    setDownloadListener { url, _, _, _, _ ->
                        HfModels.repoFromUrl(url)?.let { repo = it }
                    }
                    loadUrl(HfModels.searchUrl(""))
                    web = this
                }
            },
        )
    }
    repo?.let { r -> FilePicker(vm, r, onDismiss = { repo = null }, onStarted = { repo = null; onDone() }) }
}

/** The model files of one repo: size, recommended 4-bit file; other formats shown but not selectable. */
@Composable
private fun FilePicker(vm: ChatViewModel, repo: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    var files by remember(repo) { mutableStateOf<List<HfModels.LmFile>?>(null) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    var big by remember { mutableStateOf<HfModels.LmFile?>(null) }
    val ram = remember { vm.models.totalRam() }
    LaunchedEffect(repo) {
        runCatching { withContext(Dispatchers.IO) { HfModels.fetchFiles(repo) } }
            .onSuccess { files = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val start: (HfModels.LmFile) -> Unit = { f ->
        if (HfModels.tooBigFor(ram, f.size)) big = f else { vm.downloadModel(f); onStarted() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(repo, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val list = files
                when {
                    error != null -> Text("Dateien konnten nicht geladen werden: $error", color = MaterialTheme.colorScheme.error)
                    list == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    list.isEmpty() -> Text("Dieses Modell hat keine .litertlm-Dateien. Bitte ein LiteRT-Modell wählen (oft „litert“ im Namen).")
                    else -> {
                        val rec = HfModels.recommend(list)
                        list.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(thickness = 1.dp, color = FK.Line)
                            val usable = f.usable
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .then(if (usable) Modifier.clickable { start(f) } else Modifier)
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(f.fileName, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    LocalModelManager.gb(f.size) + (if (f.bits.isNotEmpty()) " · ${f.bits}" else "") + when {
                                        !f.usable -> " · anderes Format – geht nicht"
                                        f == rec -> " · empfohlen"
                                        HfModels.tooBigFor(ram, f.size) -> " · zu groß fürs Handy"
                                        else -> ""
                                    },
                                    fontSize = 12.sp,
                                    color = if (f == rec) FK.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
    big?.let { f -> BigModelWarning(f, ram, onDismiss = { big = null }) { big = null; vm.downloadModel(f); onStarted() } }
}
