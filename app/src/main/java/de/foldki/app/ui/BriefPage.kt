package de.foldki.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.brief.Briefing

/** "Tagesstart" exactly like mock-up 17: appointments today, open items, notifications. Only on the phone. */
@Composable
fun BriefPage(b: Briefing, onClose: () -> Unit, onToggle: (id: String, done: Boolean) -> Unit) {
    var showNotes by remember { mutableStateOf(false) }
    val de = java.util.Locale.GERMANY
    val time = java.text.SimpleDateFormat("HH:mm", de)
    Box(Modifier.fillMaxSize().background(FK.Bg).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            Row(Modifier.padding(top = 6.dp)) { NavBtn(Icons.Default.Menu, "Schließen", onClick = onClose) }
            LargeTitle("Guten Morgen")
            Sub(java.text.SimpleDateFormat("EEEE, d. MMMM", de).format(java.util.Date()))

            val today = b.events.filter { !it.tomorrow }
            SectionLabel("Termine heute", top = 4.dp)
            Group {
                if (today.isEmpty()) SettingRow("Keine Termine", null) {}
                today.forEachIndexed { i, e ->
                    if (i > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp, 36.dp).clip(RoundedCornerShape(2.dp)).background(if (i % 2 == 0) FK.Accent else FK.Ok))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 1)
                            Text(
                                if (e.allDay) "ganztägig" else time.format(java.util.Date(e.begin)) + if (e.end > e.begin) " – " + time.format(java.util.Date(e.end)) else "",
                                fontSize = 13.sp, color = FK.Muted,
                            )
                        }
                    }
                }
            }

            SectionLabel("Offene Punkte")
            Group {
                if (b.openItems.isEmpty()) SettingRow("Nichts offen", null) {}
                b.openItems.forEachIndexed { i, item ->
                    if (i > 0) GroupDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggle(item.id, !item.done) }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (item.done) "●" else "○", fontSize = 20.sp, color = if (item.done) FK.Ok else FK.Dim)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (item.done) FK.Muted else FK.Text,
                                textDecoration = if (item.done) TextDecoration.LineThrough else null, maxLines = 2)
                            Text("seit " + java.text.SimpleDateFormat("dd.MM.", de).format(java.util.Date(item.created)), fontSize = 13.sp, color = FK.Muted)
                        }
                    }
                }
            }

            SectionLabel("Benachrichtigungen")
            Group {
                val n = b.notifications
                val apps = n.groupBy { it.app }.entries.sortedByDescending { it.value.size }.take(3).joinToString(", ") { "${it.value.size} von ${it.key}" }
                SettingRow(
                    if (!b.notificationsAllowed) "Kein Zugriff" else if (n.isEmpty()) "Keine neuen" else "${n.size} neue Mitteilungen",
                    if (!b.notificationsAllowed) "unter Werkzeuge → Tagesstart erlauben" else apps.ifBlank { null },
                    onClick = if (n.isNotEmpty()) ({ showNotes = !showNotes }) else null,
                ) { if (n.isNotEmpty()) Chevron() }
                if (showNotes) n.forEach { x ->
                    GroupDivider()
                    SettingRow(x.app + " – " + x.sender, x.preview.ifBlank { null }) {}
                }
            }
        }
    }
}
