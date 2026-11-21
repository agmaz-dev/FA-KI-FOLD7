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

/** "PC-Server" exactly like mock-up 6: one server (LM Studio or Ollama) in the home WLAN. */
@Composable
fun ServerPage(vm: ChatViewModel, ui: UiState) {
    val start = remember { ui.servers.firstOrNull() ?: PcServer() }
    var s by remember { mutableStateOf(start) }
    var portText by remember { mutableStateOf(start.port.toString()) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun current() = s.copy(port = portText.toIntOrNull() ?: PcServer.defaultPort(s.type))
    val test: () -> Unit = {
        testing = true
        scope.launch {
            runCatching { ServerEngine.models(current()) }
                .onSuccess { (list, ms) -> models = list; ok = true; status = "${list.size} Modelle gefunden · $ms ms"; info = ServerEngine.modelInfo(current()) }
                .onFailure { models = null; ok = false; status = it.message ?: "Nicht erreichbar" }
            testing = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (start.host.isNotBlank()) test() }

    PageAction {
        Cta("Sichern", enabled = s.host.isNotBlank()) {
            val c = current(); vm.saveServer(c)
            if (c.model.isNotBlank()) vm.selectModel(PcServer.PREFIX + c.id)
            vm.notify(if (c.model.isNotBlank()) "Gesichert – ${c.model} antwortet jetzt" else "Gesichert")
        }
    }
    Sub("Große Modelle von deinem PC nutzen – im gleichen WLAN.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("lmstudio", "LM Studio", FK.T7), Triple("ollama", "Ollama", FK.T11)).forEach { (key, label, tone) ->
            val on = s.type == key
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).clip(shape).background(if (on) FK.Accent.copy(alpha = 0.1f) else FK.Surface)
                    .border(1.5.dp, if (on) FK.Accent else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .clickable { s = s.copy(type = key); portText = PcServer.defaultPort(key).toString() }.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Tile(if (key == "lmstudio") Icons.Default.Computer else Icons.Default.Dns, tone, size = 36.dp, radius = 9.dp)
                Spacer(Modifier.height(8.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = FK.Text, textAlign = TextAlign.Center)
            }
        }
    }
    SectionLabel("Verbindung")
    Group {
        FieldRow("Name", s.name, { s = s.copy(name = it) }, hint = "Mein PC")
        FieldDivider()
        FieldRow("Adresse", s.host, { s = s.copy(host = it.trim()) }, hint = "192.168.0.20")
        FieldDivider()
        FieldRow("Port", portText, { portText = it.filter(Char::isDigit).take(5) }, hint = PcServer.defaultPort(s.type).toString(), number = true)
    }
    SectionLabel("Status")
    Group {
        SettingRow(
            if (testing) "Prüfe …" else if (ok) "Verbunden" else if (status != null) "Nicht erreichbar" else "Nicht getestet",
            status ?: "Adresse eintragen und testen",
            leading = {
                Tile(Icons.Default.Dns, (if (ok) FK.Ok else if (status != null) FK.Danger else FK.Muted).copy(alpha = 0.2f) to (if (ok) FK.Ok else if (status != null) FK.Danger else FK.Muted))
            },
        ) {
            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FK.Accent)
            else Cta("Testen", ghost = true, enabled = s.host.isNotBlank()) { test() }
        }
    }
    val list = models ?: if (s.model.isNotBlank()) listOf(s.model) else emptyList()
    if (list.isNotEmpty()) {
        SectionLabel("Modelle auf dem PC")
        Group {
            list.forEachIndexed { i, m ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = FK.Line)
                SettingRow(m, info[m]?.ifBlank { null }, onClick = { s = s.copy(model = m) }) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (s.model == m) Text("✓", color = FK.Accent, fontSize = 20.sp) }
                }
            }
        }
    }
}
