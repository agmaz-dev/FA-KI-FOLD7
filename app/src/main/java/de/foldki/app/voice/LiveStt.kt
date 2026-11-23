package de.foldki.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live dictation (ab 4.9): ML Kit GenAI speech recognition on the phone, without Google's speech window, without its
 * banner and without a click sound. The app records the microphone itself and feeds the sound to the recognizer as it
 * comes, so the words appear while the user is still speaking ([onPartial]). Recording ends after a short pause.
 * If the recognizer is not on this phone (or not for German), the caller uses Whisper as before.
 */
class LiveStt {
    private var recognizer: SpeechRecognizer? = null

    private fun client(): SpeechRecognizer = recognizer ?: SpeechRecognition.getClient(
        speechRecognizerOptions {
            locale = Locale.GERMANY
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        },
    ).also { recognizer = it }

    /** "bereit", "lädt …" or the reason why live dictation is not possible here. */
    suspend fun status(): Int = runCatching { client().checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)

    suspend fun isAvailable(): Boolean = status() == FeatureStatus.AVAILABLE

    /** Asks Android to download the German recognition model (once). Returns true when it is there. */
    suspend fun download(onLine: (String) -> Unit): Boolean {
        val st = status()
        if (st == FeatureStatus.AVAILABLE) return true
        if (st == FeatureStatus.UNAVAILABLE) { onLine("Live-Diktat gibt es auf diesem Handy nicht – Whisper wird benutzt."); return false }
        var ok = true
        runCatching {
            client().download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadProgress -> onLine("Lade Spracherkennung … ${s.totalBytesDownloaded / 1_000_000} MB")
                    is DownloadStatus.DownloadFailed -> { ok = false; onLine("Download fehlgeschlagen: ${s.e.message}") }
                    else -> Unit
                }
            }
        }.onFailure { ok = false; onLine("Download fehlgeschlagen: ${it.message}") }
        return ok && status() == FeatureStatus.AVAILABLE
    }

    /**
     * Records until the user pauses ([endSilenceMs]) or [stopNow] says so; [onPartial] gets the text so far.
     * Returns the recognized text ("" = nothing understood).
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(
        level: (Float) -> Unit, onPartial: (String) -> Unit, stopNow: () -> Boolean, endSilenceMs: Int = 1100,
    ): String = coroutineScope {
        val rec = client()
        val pipe = ParcelFileDescriptor.createPipe()
        val finals = StringBuilder()
        var partial = ""
        val writer = launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
            try {
                if (mic.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Mikrofon nicht verfügbar.")
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    mic.startRecording()
                    val frame = RATE * 30 / 1000
                    val buf = ShortArray(frame)
                    val bytes = ByteArray(frame * 2)
                    var noise = 0.0; var noiseFrames = 0
                    var speech = false; var silentMs = 0; var waitedMs = 0; var totalMs = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (stopNow()) break
                        val r = mic.read(buf, 0, frame)
                        if (r <= 0) continue
                        var sum = 0.0
                        for (i in 0 until r) {
                            val s = buf[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte(); bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val v = s / 32768.0; sum += v * v
                        }
                        out.write(bytes, 0, r * 2)
                        totalMs += 30
                        val rms = sqrt(sum / r)
                        if (noiseFrames < 10) { noise += rms; noiseFrames++; continue }
                        val threshold = max(min(noise / noiseFrames, 0.02) * 2.5, 0.01)
                        level(((rms / (threshold * 4)).toFloat()).coerceIn(0f, 1f))
                        if (rms > threshold) { speech = true; silentMs = 0 } else if (speech) silentMs += 30 else waitedMs += 30
                        if (speech && silentMs >= endSilenceMs) break
                        if (!speech && waitedMs >= 8000) break
                        if (totalMs >= 60_000) break
                    }
                }
            } finally {
                runCatching { mic.stop() }; mic.release(); level(0f)
            }
            // End of sound: ask for the final text.
            runCatching { kotlinx.coroutines.delay(400); rec.stopRecognition() }
        }
        try {
            rec.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(pipe[0]) }).takeWhile { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        partial = response.text.trim()
                        onPartial(listOf(finals.toString(), partial).filter { it.isNotBlank() }.joinToString(" "))
                        true
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val t = response.text.trim()
                        if (t.isNotEmpty()) { if (finals.isNotEmpty()) finals.append(' '); finals.append(t) }
                        partial = ""
                        onPartial(finals.toString())
                        true
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> false
                    is SpeechRecognizerResponse.ErrorResponse -> throw IllegalStateException(response.e.message ?: "Spracherkennung fehlgeschlagen")
                    else -> true
                }
            }.collect { }
        } finally {
            writer.cancel()
            runCatching { pipe[0].close() }
        }
        listOf(finals.toString(), partial).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    fun close() { runCatching { recognizer?.close() }; recognizer = null }

    private companion object { const val RATE = 16000 }
}
