package de.foldki.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ai.GgufEngine

/**
 * „Denken“ in the chat options (⋮): how long GGUF thinking models (e.g. Qwen3.5) think before answering.
 * Aus = no thinking, Kurz = at most 256 tokens, Normal = at most 1200 tokens. Applies from the next answer.
 */
@Composable
fun ThinkSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val keys = listOf(GgufEngine.THINK_OFF, GgufEngine.THINK_SHORT, GgufEngine.THINK_NORMAL)
    val labels = listOf("Aus", "Kurz", "Normal")
    var think by remember { mutableStateOf(prefs.getString(GgufEngine.KEY_THINK, GgufEngine.THINK_NORMAL) ?: GgufEngine.THINK_NORMAL) }
    val i = keys.indexOf(think).coerceAtLeast(0)
    Row(Modifier.padding(top = 14.dp, bottom = 8.dp)) {
        Text("Denken", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, modifier = Modifier.weight(1f))
        RowValue(listOf("sofort antworten", "bis 256 Tokens", "bis 1200 Tokens")[i])
    }
    Segmented(labels, i) { n ->
        think = keys[n]
        prefs.edit().putString(GgufEngine.KEY_THINK, think).apply()
    }
}
