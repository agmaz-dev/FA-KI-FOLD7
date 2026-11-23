package de.foldki.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.data.Engine
import de.foldki.app.data.Role
import de.foldki.app.export.PdfExporter
import de.foldki.app.export.DocumentParser
import de.foldki.app.voice.VoiceController

private enum class Phase { LISTENING, TRANSCRIBING, THINKING, SPEAKING, PAUSED }

/** Full-screen voice conversation: listen → ask the AI → read the answer aloud → listen again. */
@Composable
fun VoiceOverlay(ui: UiState, vm: ChatViewModel, voice: VoiceController, onClose: () -> Unit, onSettings: () -> Unit = {}) {
    var phase by remember { mutableStateOf(Phase.PAUSED) }
    var heard by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    // Every start/stop gets a new turn; late callbacks of an older turn are ignored (no double start).
    var turn by remember { mutableIntStateOf(0) }
    // Characters of the current answer already handed to the voice (Anruf-Modus).
    var spoken by remember { mutableIntStateOf(0) }

    fun startListening() {
        val my = ++turn
        info = ""; level = 0f; phase = Phase.LISTENING
        voice.listen(
            onFinal = { t ->
                if (turn == my) {
                    level = 0f
                    if (t.isBlank()) { phase = Phase.PAUSED; info = "Nichts verstanden – tippe zum Sprechen." }
                    else { heard = t; spoken = 0; phase = Phase.THINKING; vm.send(t, voice = true) }
                }
            },
            onError = { msg -> if (turn == my) { level = 0f; phase = Phase.PAUSED; info = msg } },
            onLevel = { if (turn == my) level = it },
            onTranscribing = { if (turn == my) { level = 0f; phase = Phase.TRANSCRIBING } },
            onEnd = { if (turn == my) level = 0f },
            onPartial = { t -> if (turn == my && t.isNotBlank()) heard = t },
        )
    }

    /** Round stop button: ends recording, recognition, the answer and speech output at once. */
    fun stopAll() {
        turn++
        voice.stopAll()
        if (ui.generating) vm.stop()
        level = 0f; phase = Phase.PAUSED
    }

    LaunchedEffect(Unit) { startListening() }
    DisposableEffect(Unit) { onDispose { voice.stopAll() } }
    // App goes to the background (or the phone is locked): release microphone and speaker.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (phase != Phase.PAUSED) stopAll() }

    // Ab 4.9 (Anruf-Modus): every finished sentence is spoken while the model still writes; then listen again.
    val lastAnswer = ui.current?.messages?.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }
    LaunchedEffect(lastAnswer?.text, ui.generating, phase) {
        if (phase != Phase.THINKING && phase != Phase.SPEAKING) return@LaunchedEffect
        val a = lastAnswer ?: return@LaunchedEffect
        if (a.isError) { if (!ui.generating) { phase = Phase.PAUSED; info = a.text }; return@LaunchedEffect }
        val text = DocumentParser.spoken(a.text)
        // Up to the last sentence end (while writing) or everything (when done).
        val upTo = if (ui.generating) Regex("[.!?:](\\s|$)").findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0 else text.length
        if (upTo > spoken && upTo <= text.length) {
            val part = text.substring(spoken, upTo).trim()
            spoken = upTo
            if (part.isNotEmpty()) { phase = Phase.SPEAKING; voice.enqueue(part) }
        }
        if (!ui.generating && spoken >= text.length) {
            val my = turn
            // Wait until the queue has been spoken, then listen again.
            while (voice.queueBusy && turn == my) kotlinx.coroutines.delay(150)
            if (turn == my && (phase == Phase.SPEAKING || phase == Phase.THINKING)) {
                if (text.isBlank()) { phase = Phase.PAUSED; info = "Keine Antwort." } else startListening()
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(
        0.92f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe",
    )
    val target = when (phase) {
        Phase.LISTENING -> 1f + level * 0.35f
        Phase.TRANSCRIBING, Phase.THINKING, Phase.SPEAKING -> breathe
        Phase.PAUSED -> 0.85f
    }
    val scale by animateFloatAsState(target, label = "scale")

    // Exactly like mock-up 14: ✕ / voice + model / ⚙ on top, pink orb in the middle, 🎙 and red ✕ below.
    val voiceName = voice.voiceLabel
    Box(
        Modifier.fillMaxSize().background(FK.Bg)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(androidx.compose.ui.graphics.Color(0x59FF375F), androidx.compose.ui.graphics.Color.Transparent),
                center = androidx.compose.ui.geometry.Offset.Unspecified, radius = 900f,
            )),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NavBtn(Icons.Default.Close, "Sprachmodus beenden") { stopAll(); onClose() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(voiceName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FK.Text)
                    Text(ui.activeName + " · " + if (ui.activeServer != null) "WLAN" else "offline", fontSize = 12.sp, color = FK.Muted, maxLines = 1)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(FK.Surface).clickable { stopAll(); onSettings() }, contentAlignment = Alignment.Center) {
                    Text("⚙︎", fontSize = 18.sp, color = FK.Text)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(180.dp).scale(scale)
                    .shadow(60.dp, CircleShape, ambientColor = androidx.compose.ui.graphics.Color(0xFFFF375F), spotColor = androidx.compose.ui.graphics.Color(0xFFFF375F))
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(
                        0f to androidx.compose.ui.graphics.Color(0xFFFFB3C6), 0.45f to androidx.compose.ui.graphics.Color(0xFFFF375F), 0.9f to FK.Purple,
                        center = androidx.compose.ui.geometry.Offset(170f, 140f), radius = 520f,
                    ))
                    .clickable {
                        when (phase) {
                            Phase.LISTENING -> voice.stopListening() // done speaking: recognize and send
                            Phase.SPEAKING -> { turn++; voice.stopSpeaking(); startListening() }
                            Phase.PAUSED -> startListening()
                            Phase.TRANSCRIBING, Phase.THINKING -> {}
                        }
                    },
            )
            Text(
                when (phase) {
                    Phase.LISTENING -> "Ich höre zu …"
                    Phase.TRANSCRIBING -> "Erkenne …"
                    Phase.THINKING -> "Denke nach …"
                    Phase.SPEAKING -> "Spreche – tippe zum Unterbrechen"
                    Phase.PAUSED -> "Tippe auf den Kreis zum Sprechen"
                },
                fontSize = 17.sp, color = FK.Muted, modifier = Modifier.padding(top = 44.dp),
            )
            val line = if (info.isNotBlank()) info else if (heard.isNotBlank()) "„$heard“" else ""
            if (line.isNotBlank()) Text(
                line, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = FK.Text, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, start = 30.dp, end = 30.dp).widthIn(max = 560.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.padding(bottom = 60.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(FK.Surface2).clickable { if (phase == Phase.PAUSED) startListening() else stopAll() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (phase == Phase.PAUSED || phase == Phase.LISTENING) "🎙" else "■", fontSize = 24.sp, color = FK.Text) }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(FK.Danger).clickable { stopAll(); onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 24.sp, color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}
