package de.foldki.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
@Composable
fun ModelSettings(vm: ChatViewModel, ui: UiState, openBrowser: () -> Unit, openNano: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<LocalModelManager.LocalModel?>(null) }
    var bigWarning by remember { mutableStateOf<HfModels.LmFile?>(null) }
    val st = ui.modelOp
    val free = remember(st, ui.localModels) { vm.models.freeBytes() }
    val ram = remember { vm.models.totalRam() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val busy = st is State.Downloading || st is State.Verifying || st is State.Importing

    // Memory card (like the mock-up): free RAM decides whether a model can start.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val avail = remember(ui.localModels, ui.backend) {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        }.getOrDefault(0L)
    }
    Card {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Arbeitsspeicher", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = FK.Text, modifier = Modifier.weight(1f))
            val ok = avail > 3_000_000_000L
            Pill(if (ok) "Gut" else "Knapp", if (ok) FK.Ok else FK.Warn)
        }
        if (ram > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (1f - avail.toFloat() / ram).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp),
                color = FK.Ok, trackColor = FK.Surface3,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text("Frei: ", fontSize = 13.sp, color = FK.Muted)
            Text(LocalModelManager.gb(avail), fontSize = 13.sp, color = FK.Ok, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (ram > 0) Text("${"%.0f".format(java.util.Locale.GERMANY, ram / 1e9)} GB gesamt · Speicher frei ${LocalModelManager.gb(free)}", fontSize = 13.sp, color = FK.Muted)
        }
    }
    Note("Es antwortet nur das gewählte Modell – kein anderes springt ein.", Modifier.padding(horizontal = 6.dp, vertical = 8.dp))

    SectionLabel("Gewähltes Modell")
    Group {
        ui.localModels.forEachIndexed { i, m ->
            if (i > 0) GroupDivider()
            val crashed = vm.settings.llmCrashed == m.file
            SettingRow(
                m.name,
                LocalModelManager.gb(m.size) + " · " + (if (HfModels.isGguf(m.file)) "GGUF" else "LiteRT") + " · " + m.source + if (crashed) " · hat die App beendet – tippen zum erneuten Versuch" else "",
                leading = { Tile(Icons.Default.Diamond, FK.T3) }, onClick = { vm.selectModel(m.file) },
            ) {
                if (ui.activeModel == m.file) Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(22.dp))
                else Text("Löschen", color = FK.Muted, fontSize = 13.sp, modifier = Modifier.clickable { confirmDelete = m }.padding(8.dp))
            }
        }
        if (ui.localModels.isNotEmpty()) GroupDivider()
        SettingRow(
            "Gemini Nano", if (ui.usesNano) "im Handy eingebaut · " + ui.nanoStatus else "im Handy eingebaut (AICore) · tippen zum Auswählen",
            leading = { Tile(Icons.Default.PhoneAndroid, FK.T1) }, onClick = { vm.selectModel(Settings.NANO); if (!ui.nanoReady) openNano() },
        ) {
            if (ui.usesNano) Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(22.dp))
        }
    }
    ui.activeLocal?.let { m ->
        Text(
            "Löschen von ${m.name}", color = FK.Muted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp).clickable { confirmDelete = m }.padding(6.dp),
        )
    }

    SectionLabel("Herunterladen")
    Group {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (st) {
                is State.Downloading -> {
                    Text("Lädt ${HfModels.displayName(st.name)}… ${st.pct} % (${LocalModelManager.gb(st.bytes)} von ${LocalModelManager.gb(st.total)})", fontSize = 13.sp, color = FK.Text)
                    LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth(), color = FK.Accent, trackColor = FK.Line)
                    FkButton("Abbrechen", ghost = true) { vm.cancelModelOp() }
                    Note("Geht über WLAN und Mobilfunk. Der Download läuft auch weiter, wenn die App zu ist.")
                }
                is State.Verifying -> {
                    Text("Prüfe die Datei (Größe, SHA-256)… ${st.pct} %", fontSize = 13.sp, color = FK.Text)
                    LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth(), color = FK.Accent, trackColor = FK.Line)
                }
                is State.Importing -> {
                    Text("Importiere ${st.name}… ${st.pct} %", fontSize = 13.sp, color = FK.Text)
                    LinearProgressIndicator(progress = { st.pct / 100f }, modifier = Modifier.fillMaxWidth(), color = FK.Accent, trackColor = FK.Line)
                    FkButton("Abbrechen", ghost = true) { vm.cancelModelOp() }
                }
                is State.Failed -> Text(st.message, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFF2B8B5))
                State.Idle -> Unit
            }
            if (!busy) {
                if (ui.localModels.none { it.file == LocalModelManager.GEMMA_FILE }) {
                    FkButton("Gemma 4 E2B laden (${LocalModelManager.gb(LocalModelManager.GEMMA_SIZE)})") {
                        vm.clearModelError(); vm.downloadModel(LocalModelManager.GEMMA)
                    }
                }
                FkButton("Modelle durchsuchen (passend zur App)", ghost = true) { vm.clearModelError(); openBrowser() }
                FkButton("Eigene .litertlm importieren", ghost = true) { vm.clearModelError(); pick.launch(arrayOf("*/*")) }
                Note("Es laufen .litertlm (LiteRT-LM, GPU) und .gguf (llama.cpp, auch Dateien aus LM Studio). .task geht nicht. Empfohlen: 4-Bit bis ca. 3–4 GB.")
            }
        }
    }

    if (ui.localModels.any { HfModels.isGguf(it.file) }) {
        SectionLabel("GGUF (llama.cpp)")
        Group {
            var pct by remember { mutableStateOf(vm.settings.gpuPercent.toFloat()) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Text("GPU-Anteil", fontSize = 16.sp, color = FK.Text, modifier = Modifier.weight(1f))
                    Text(if (pct.toInt() == 0) "nur Prozessor" else "${pct.toInt()} %", fontSize = 15.sp, color = FK.Muted)
                }
                androidx.compose.material3.Slider(
                    value = pct, onValueChange = { pct = (it / 5).toInt() * 5f }, valueRange = 0f..100f,
                    onValueChangeFinished = { vm.setGpuPercent(pct.toInt()) },
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = FK.Accent, inactiveTrackColor = FK.Surface3),
                )
                Note("Anteil der Modell-Schichten auf dem Adreno-Grafikprozessor (OpenCL). Startwert 75 %.")
            }
        }
    }

    SectionLabel("Rechenart")
    Group {
        var cpu by remember { mutableStateOf(vm.settings.gemmaBackend == "CPU") }
        SettingRow(
            "Auf CPU rechnen (Test)",
            if (cpu) "An: Modelle rechnen auf dem Prozessor" else "Aus: zuerst GPU, nur wenn das Modell dort nicht startet die CPU" +
                (ui.backend.ifBlank { null }?.let { " · läuft gerade: $it" } ?: ""),
            leading = { Tile(Icons.Default.Memory, FK.T6) },
        ) { FkToggle(cpu, { cpu = it; vm.setCpu(it) }) }
    }
    Spacer(Modifier.height(8.dp))

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
