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
fun VoiceOverlay(ui: UiState, vm: ChatViewModel, voice: VoiceController, onClose: () -> Unit) {
    var phase by remember { mutableStateOf(Phase.PAUSED) }
    var info by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    // Every start/stop gets a new turn; late callbacks of an older turn are ignored (no double start).
    var turn by remember { mutableIntStateOf(0) }

    fun startListening() {
        val my = ++turn
        info = ""; level = 0f; phase = Phase.LISTENING
        voice.listen(
            onFinal = { t ->
                if (turn == my) {
                    level = 0f
                    if (t.isBlank()) { phase = Phase.PAUSED; info = "Nichts verstanden – tippe zum Sprechen." }
                    else { phase = Phase.THINKING; vm.send(t, voice = true) }
                }
            },
            onError = { msg -> if (turn == my) { level = 0f; phase = Phase.PAUSED; info = msg } },
            onLevel = { if (turn == my) level = it },
            onTranscribing = { if (turn == my) { level = 0f; phase = Phase.TRANSCRIBING } },
            onEnd = { if (turn == my) level = 0f },
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

    // When the answer is complete, read it aloud and then listen again.
    LaunchedEffect(ui.generating) {
        if (!ui.generating && phase == Phase.THINKING) {
            val last = ui.current?.messages?.lastOrNull()
            if (last != null && last.role == Role.ASSISTANT && !last.isError) {
                phase = Phase.SPEAKING
                val my = turn
                voice.speak(DocumentParser.spoken(last.text)) { if (turn == my && phase == Phase.SPEAKING) startListening() }
            } else {
                phase = Phase.PAUSED; info = last?.text ?: "Keine Antwort."
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

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Sprachmodus · " + ui.activeName,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(200.dp).scale(scale).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (phase == Phase.PAUSED) 0.4f else 0.9f))
                    .clickable {
                        when (phase) {
                            Phase.LISTENING -> voice.stopListening() // done speaking: recognize and send
                            Phase.SPEAKING -> { turn++; voice.stopSpeaking(); startListening() }
                            Phase.PAUSED -> startListening()
                            Phase.TRANSCRIBING, Phase.THINKING -> {}
                        }
                    },
            )
            Spacer(Modifier.height(32.dp))
            Text(
                when (phase) {
                    Phase.LISTENING -> "Ich höre zu…"
                    Phase.TRANSCRIBING, Phase.THINKING -> "Denke nach…"
                    Phase.SPEAKING -> "Spreche – tippe zum Unterbrechen"
                    Phase.PAUSED -> "Tippe auf den Kreis zum Sprechen"
                },
                fontSize = 18.sp, fontFamily = FontFamily.Serif,
            )
            Spacer(Modifier.height(12.dp))
            // No transcript in voice mode: neither the recognized words nor the answer as text
            // (both stay in the chat). Only hints and errors are shown here.
            Text(
                info, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { if (phase == Phase.PAUSED) startListening() else stopAll() },
                    modifier = Modifier.size(64.dp),
                ) {
                    if (phase == Phase.PAUSED) Icon(Icons.Default.Mic, "Sprechen")
                    else Icon(Icons.Default.Stop, "Stopp")
                }
                FilledTonalIconButton(onClick = { stopAll(); onClose() }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Close, "Sprachmodus beenden")
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
