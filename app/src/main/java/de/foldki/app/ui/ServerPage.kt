package de.foldki.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.PcServer
import de.foldki.app.ai.ServerEngine
import kotlinx.coroutines.launch

/** "PC-Server": use big models on the user's PC (LM Studio, Ollama, OpenAI-compatible) in the same WLAN. */
@Composable
fun ServerPage(vm: ChatViewModel, ui: UiState) {
    var edit by remember { mutableStateOf<PcServer?>(null) }
    edit?.let { e -> ServerEditor(vm, ui, e, onDone = { edit = null }); return }

    Note("Große Modelle von deinem PC nutzen – Handy und PC im gleichen WLAN. Es geht nur an den PC, was du schreibst.",
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    if (ui.servers.isNotEmpty()) {
        SectionLabel("Deine Server")
        Group {
            ui.servers.forEachIndexed { i, s ->
                if (i > 0) GroupDivider()
                val active = ui.activeModel == PcServer.PREFIX + s.id
                SettingRow(s.name, "${s.typeLabel} · ${s.base}" + if (s.model.isNotBlank()) " · ${s.model}" else " · kein Modell gewählt",
                    leading = { Tile(Icons.Default.Computer, if (active) FK.T3 else FK.T6) }, onClick = { edit = s }) {
                    if (active) Tag("Aktiv", on = true) else Chevron()
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    FkButton("＋ Server hinzufügen") { edit = PcServer() }
    Spacer(Modifier.height(10.dp))
    Note("LM Studio: Developer → „Start Server“ und „Serve on Local Network“ einschalten (Port 1234). " +
        "Ollama: am PC OLLAMA_HOST=0.0.0.0 setzen (Port 11434). Die Adresse des PCs steht z. B. in den WLAN-Einstellungen des PCs.",
        Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ServerEditor(vm: ChatViewModel, ui: UiState, start: PcServer, onDone: () -> Unit) {
    var s by remember(start.id) { mutableStateOf(start) }
    var portText by remember(start.id) { mutableStateOf(start.port.toString()) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exists = ui.servers.any { it.id == s.id }

    fun current() = s.copy(port = portText.toIntOrNull() ?: PcServer.defaultPort(s.type))
    val test: () -> Unit = {
        testing = true; status = null
        scope.launch {
            runCatching { ServerEngine.models(current()) }
                .onSuccess { (list, ms) -> models = list; ok = true; status = "Verbunden · ${list.size} Modelle gefunden · $ms ms" }
                .onFailure { models = null; ok = false; status = it.message ?: "Nicht erreichbar" }
            testing = false
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("lmstudio", "LM Studio", Icons.Default.Computer), Triple("ollama", "Ollama", Icons.Default.Dns), Triple("openai", "OpenAI-kompatibel", Icons.Default.Hub))
            .forEach { (key, label, icon) ->
                val on = s.type == key
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.weight(1f).clip(shape).background(if (on) FK.AccentSoft else FK.Surface)
                        .border(1.5.dp, if (on) FK.Accent else FK.Surface, shape)
                        .clickable { s = s.copy(type = key); portText = PcServer.defaultPort(key).toString() }.padding(vertical = 14.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Tile(icon, if (on) FK.T2 else FK.T6, size = 36.dp, radius = 9.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FK.Text, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
    }
    SectionLabel("Verbindung")
    Group {
        FkField("Name", s.name, { s = s.copy(name = it) })
        FkField("Adresse (IP des PCs, z. B. 192.168.0.20)", s.host, { s = s.copy(host = it.trim()) })
        FkField("Port", portText, { portText = it.filter(Char::isDigit).take(5) })
        if (s.type == "openai") FkField("API-Schlüssel (falls nötig)", s.apiKey, { s = s.copy(apiKey = it) }, password = true)
    }
    SectionLabel("Status")
    Group {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = FK.Accent)
                else Text(status ?: "Noch nicht getestet", fontSize = 14.sp, color = if (ok) FK.Ok else if (status != null) FK.Danger else FK.Muted)
            }
            Text("Testen", color = FK.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(FK.Surface2).clickable(enabled = s.host.isNotBlank() && !testing) { test() }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
    val list = models ?: if (s.model.isNotBlank()) listOf(s.model) else emptyList()
    if (list.isNotEmpty()) {
        SectionLabel("Modelle auf dem PC")
        Group {
            list.forEachIndexed { i, m ->
                if (i > 0) GroupDivider()
                SettingRow(m, null, leading = { Tile(Icons.Default.Dns, FK.T7) }, onClick = { s = s.copy(model = m) }) {
                    if (s.model == m) Icon(Icons.Default.Check, "Gewählt", tint = FK.Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FkButton("Abbrechen", Modifier.weight(1f), ghost = true) { onDone() }
        FkButton("Sichern", Modifier.weight(1f), enabled = s.host.isNotBlank()) { vm.saveServer(current()); onDone() }
    }
    if (s.model.isNotBlank() && s.host.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        FkButton("Sichern & im Chat benutzen", ghost = true) { val c = current(); vm.saveServer(c); vm.selectModel(PcServer.PREFIX + c.id); onDone() }
    }
    if (exists) {
        Spacer(Modifier.height(10.dp))
        Text("Server löschen", color = FK.Danger, fontSize = 15.sp, modifier = Modifier.padding(6.dp).clickable { vm.deleteServer(s.id); onDone() }.padding(6.dp))
    }
}
