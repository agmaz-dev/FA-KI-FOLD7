package de.foldki.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.foldki.app.ChatViewModel

/** "S3-Sicherung" exactly like mock-up 8: end-to-end encrypted backup to the user's own S3 storage. */
@Composable
fun S3Page(vm: ChatViewModel) {
    val st = vm.settings
    var endpoint by remember { mutableStateOf(st.s3Endpoint) }
    var bucket by remember { mutableStateOf(st.s3Bucket) }
    var region by remember { mutableStateOf(st.s3Region) }
    var prefix by remember { mutableStateOf(st.s3Prefix) }
    var access by remember { mutableStateOf(st.s3AccessKey) }
    var secret by remember { mutableStateOf(st.s3SecretKey) }
    var pass by remember { mutableStateOf(st.s3Password) }
    var pass2 by remember { mutableStateOf(st.s3Password) }
    var auto by remember { mutableStateOf(st.s3Auto) }
    val busy by vm.s3Busy.collectAsStateWithLifecycle()
    val last by vm.s3Last.collectAsStateWithLifecycle()

    fun save() {
        st.s3Endpoint = endpoint.trim(); st.s3Bucket = bucket.trim(); st.s3Region = region.trim(); st.s3Prefix = prefix.trim()
        st.s3AccessKey = access.trim(); st.s3SecretKey = secret.trim(); if (pass == pass2) st.s3Password = pass
    }
    DisposableEffect(Unit) { onDispose { save() } }
    val ready = bucket.isNotBlank() && access.isNotBlank() && secret.isNotBlank() && pass.isNotBlank() && pass == pass2

    PageAction { Cta(if (busy) "Sichert …" else "Jetzt sichern", enabled = ready && !busy) { save(); vm.s3BackupNow() } }

    Card(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(Icons.Default.Lock, FK.Accent.copy(alpha = 0.2f) to FK.Accent, size = 40.dp, radius = 10.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ende-zu-Ende verschlüsselt", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text)
                Text("Chats, Anweisungen und Einstellungen auf deinen eigenen S3-Speicher", fontSize = 13.sp, color = FK.Muted)
            }
        }
    }
    Group {
        SettingRow("Zuletzt gesichert", null) {
            RowValue(if (last == 0L) "noch nie" else java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(last)))
        }
        GroupDivider()
        SettingRow("Automatisch sichern", null) { FkToggle(auto, { on -> auto = on; st.s3Auto = on }) }
    }
    SectionLabel("Speicher")
    Group {
        FieldRow("Adresse", endpoint, { endpoint = it }, hint = "https://s3.amazonaws.com"); FieldDivider()
        FieldRow("Bucket", bucket, { bucket = it }, hint = "Pflicht"); FieldDivider()
        FieldRow("Region", region, { region = it }, hint = "eu-central-1"); FieldDivider()
        FieldRow("Ordner", prefix, { prefix = it }, hint = "foldki")
    }
    SectionLabel("Zugang")
    Group {
        FieldRow("Access Key", access, { access = it }, hint = "Pflicht"); FieldDivider()
        FieldRow("Secret Key", secret, { secret = it }, hint = "Pflicht", password = true)
    }
    SectionLabel("Verschlüsselung")
    Group {
        FieldRow("Passwort", pass, { pass = it }, hint = "Pflicht", password = true); FieldDivider()
        FieldRow("Wiederholen", pass2, { pass2 = it }, hint = "Pflicht", password = true)
    }
    if (pass.isNotEmpty() && pass2.isNotEmpty() && pass != pass2) Sub("Die Passwörter sind nicht gleich.")
}
