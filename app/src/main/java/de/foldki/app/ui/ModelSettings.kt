package de.foldki.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.LocalModelManager.State
import de.foldki.app.data.Settings

/**
 * Page "KI-Modelle": choose the model that answers (downloaded .litertlm models or Gemini Nano), download from
 * Hugging Face, import own files, delete, CPU test switch. Only the chosen model answers – nothing jumps in.
 * Stacked, full width – works folded (cover screen) and unfolded.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ModelSettings(vm: ChatViewModel, ui: UiState, openBrowser: () -> Unit, openNano: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<LocalModelManager.LocalModel?>(null) }
    var bigWarning by remember { mutableStateOf<HfModels.LmFile?>(null) }
    val st = ui.modelOp
    val free = remember(st, ui.localModels) { vm.models.freeBytes() }
    val ram = remember { vm.models.totalRam() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val busy = st is State.Downloading || st is State.Verifying || st is State.Importing

    PageAction { Cta("Importieren", ghost = true, enabled = !busy) { vm.clearModelError(); pick.launch(arrayOf("*/*")) } }
    // Exactly like mock-up 3 (design/3-modelle.png): memory card, switch, model cards.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val avail = remember(ui.localModels, ui.backend, ui.activeModel) {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        }.getOrDefault(0L)
    }
    Card(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Arbeitsspeicher", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.weight(1f))
            val ok = avail > 3_000_000_000L
            Pill(if (ok) "Gut" else "Knapp", if (ok) FK.Ok else FK.Warn)
        }
        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FK.Surface3)) {
            if (ram > 0) Box(
                Modifier.fillMaxWidth((1f - avail.toFloat() / ram).coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(FK.Ok, androidx.compose.ui.graphics.Color(0xFF34C759)))),
            )
        }
        Row {
            Text("Frei: ", fontSize = 13.sp, color = FK.Muted)
            Text(LocalModelManager.gb(avail), fontSize = 13.sp, color = FK.Ok, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (ram > 0) Text("${"%.0f".format(java.util.Locale.GERMANY, ram / 1e9)} GB gesamt", fontSize = 13.sp, color = FK.Muted)
        }
    }
    Segmented(listOf("Auf dem Handy", "Erkunden"), 0, Modifier.padding(bottom = 14.dp)) { if (it == 1) openBrowser() }

    // Download / import in progress (only while something happens).
    when (st) {
        is State.Downloading -> Card(Modifier.padding(bottom = 12.dp)) {
            Text("Lädt ${HfModels.displayName(st.name)} … ${st.pct} %", fontSize = 15.sp, color = FK.Text)
            LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(), color = FK.Accent, trackColor = FK.Surface3)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${LocalModelManager.gb(st.bytes)} von ${LocalModelManager.gb(st.total)}", fontSize = 13.sp, color = FK.Muted, modifier = Modifier.weight(1f))
                Cta("Abbrechen", ghost = true) { vm.cancelModelOp() }
            }
        }
        is State.Verifying -> Card(Modifier.padding(bottom = 12.dp)) { Text("Prüfe die Datei (Größe, SHA-256) … ${st.pct} %", fontSize = 15.sp, color = FK.Text) }
        is State.Importing -> Card(Modifier.padding(bottom = 12.dp)) {
            Text("Importiere ${st.name} … ${st.pct} %", fontSize = 15.sp, color = FK.Text)
            Row(Modifier.padding(top = 10.dp)) { Spacer(Modifier.weight(1f)); Cta("Abbrechen", ghost = true) { vm.cancelModelOp() } }
        }
        is State.Failed -> Card(Modifier.padding(bottom = 12.dp)) { Text(st.message, fontSize = 14.sp, color = FK.Danger) }
        State.Idle -> Unit
    }

    // NPU (Hexagon V79 im Snapdragon 8 Elite) für GGUF-Modelle: alle Schichten auf den KI-Chip statt GPU-Anteil.
    val npuBuilt = remember { de.foldki.app.ai.GgufEngine.npuBuilt(ctx) }
    var npu by remember { mutableStateOf(vm.settings.npu) }
    val npuOn = npu && npuBuilt && vm.settings.gemmaBackend != "CPU"

    ui.localModels.forEach { m ->
        val active = ui.activeModel == m.file
        val gguf = HfModels.isGguf(m.file)
        val gemma = m.file.lowercase().startsWith("gemma")
        val gpuOk = !gguf && (gemma || HfModels.isGpuFile(m.file))
        val thinking = HfModels.isThinking(m.file)
        val crashed = vm.settings.llmCrashed == m.file
        Card(
            Modifier.padding(bottom = 12.dp).combinedClickable(onClick = { vm.selectModel(m.file) }, onLongClick = { confirmDelete = m }, onLongClickLabel = "Löschen"),
            border = if (active) FK.Ok else null,
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(m.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.weight(1f), maxLines = 2)
                when {
                    active -> Pill("Aktiv", FK.Ok)
                    gguf -> Pill("GGUF", FK.Warn)
                    gpuOk -> Pill("GPU", FK.Accent)
                    else -> Pill("Prozessor", FK.Muted)
                }
            }
            Text(
                when {
                    m.file == LocalModelManager.GEMMA_FILE -> "Google · schnell und sparsam"
                    gguf -> (if (m.source == "Import") "Importiert" else m.source) + " · llama.cpp"
                    thinking -> "Denkt nach vor der Antwort"
                    else -> HfModels.vendorOf(m.file) + " · " + m.source
                },
                fontSize = 13.sp, color = FK.Muted, modifier = Modifier.padding(top = 4.dp), maxLines = 1,
            )
            val tags = buildList {
                if (active && gpuOk) add("GPU" to FK.Accent)
                if (gemma) { add("Bilder" to FK.Muted); add("Werkzeuge" to FK.Muted) }
                if (thinking) add("Denken" to FK.Purple)
                if (crashed) add("hat die App beendet" to FK.Danger)
                if (vm.settings.gemmaBackend == "GPU" && de.foldki.app.ai.GpuGuard.blocked(m.file)) add("auf GPU gesperrt" to FK.Danger)
                if (!gguf) add("LiteRT" to FK.Muted)
            }
            if (tags.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { tags.forEach { (t, c) -> Pill(t, c) } }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    LocalModelManager.gb(m.size) + if (gguf) (if (npuOn) " · NPU" else " · GPU-Anteil ${vm.settings.gpuPercent} %")
                        else " · braucht ~${"%.0f".format(java.util.Locale.GERMANY, kotlin.math.ceil(m.size * 1.15 / 1e9))} GB RAM",
                    fontSize = 13.sp, color = FK.Muted, modifier = Modifier.weight(1f),
                )
                if (active) Cta("Löschen", ghost = true) { confirmDelete = m } else Cta("Laden") { vm.selectModel(m.file) }
            }
        }
    }
    // Gemini Nano: built into the phone, same card style.
    Card(Modifier.padding(bottom = 12.dp).clickable { vm.selectModel(Settings.NANO); if (!ui.nanoReady) openNano() }, border = if (ui.usesNano) FK.Ok else null) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Gemini Nano", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = FK.Text, modifier = Modifier.weight(1f))
            if (ui.usesNano) Pill("Aktiv", FK.Ok) else Pill("AICore", FK.Muted)
        }
        Text("Google · im Handy eingebaut", fontSize = 13.sp, color = FK.Muted, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (ui.usesNano) ui.nanoStatus else "kein Download nötig", fontSize = 13.sp, color = FK.Muted, modifier = Modifier.weight(1f), maxLines = 2)
            if (!ui.usesNano) Cta("Laden") { vm.selectModel(Settings.NANO); if (!ui.nanoReady) openNano() }
        }
    }
    if (ui.localModels.isEmpty()) Sub("Noch kein Modell auf dem Handy – unter „Erkunden“ eins laden oder oben rechts importieren.")

    SectionLabel("NPU (Hexagon) für GGUF")
    Group {
        SettingRow(
            "Auf der NPU rechnen",
            if (npuBuilt) "Alle Schichten auf den KI-Chip statt GPU-Anteil. Experimentell, schnell nur mit Q4_0-Dateien."
            else "Der NPU-Teil fehlt in dieser APK.",
            leading = { Tile(Icons.Default.Memory, FK.T7) },
        ) {
            FkToggle(npu && npuBuilt, { on ->
                npu = on
                vm.settings.npu = on
                vm.setGpuPercent(vm.settings.gpuPercent) // gibt das GGUF-Modell frei, es lädt neu mit NPU bzw. GPU
            }, enabled = npuBuilt)
        }
    }
    if (npu && npuBuilt) Sub("Klappt die NPU nicht, rechnet das Modell auf dem Prozessor; der Grund steht unten und im Fehlerbericht. Mit „Rechnen auf → CPU“ ist die NPU aus.")
    val npuReport = remember(ui.backend) { de.foldki.app.ai.GgufEngine.lastNpuReport }
    if (npuReport.isNotBlank()) {
        SectionLabel("Letzter NPU-Start")
        Card(Modifier.padding(bottom = 12.dp)) { Note(npuReport) }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = FK.Surface,
            title = { Text("Modell löschen?", color = FK.Text) },
            text = { Text("${m.name} (${LocalModelManager.gb(m.size)}) wird vom Handy entfernt. Du kannst es später erneut herunterladen.", color = FK.Muted) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteModel(m.file) }) { Text("Löschen", color = FK.Accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen", color = FK.Muted) } },
        )
    }
    bigWarning?.let { f -> BigModelWarning(f, ram, onDismiss = { bigWarning = null }) { bigWarning = null; vm.downloadModel(f) } }
}

/** Warning before a model that is probably too big for the phone's RAM (Galaxy Z Fold 7: 12 GB). */
@Composable
fun BigModelWarning(f: HfModels.LmFile, ram: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FK.Surface,
        title = { Text("Sehr großes Modell", color = FK.Text) },
        text = {
            Text(
                "${f.fileName} hat ${LocalModelManager.gb(f.size)}. Das Handy hat ${"%.0f".format(java.util.Locale.GERMANY, ram / 1e9)} GB Arbeitsspeicher – " +
                    "das Modell wird wahrscheinlich sehr langsam oder die App wird beendet. Trotzdem herunterladen?",
                color = FK.Muted,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Trotzdem laden", color = FK.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = FK.Muted) } },
    )
}
