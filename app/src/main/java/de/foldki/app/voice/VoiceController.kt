package de.foldki.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Voice in and out, fully on the phone:
 * - listening (ab 4.9): live dictation with ML Kit (words appear while speaking, no Google window, no click) – or the own
 *   microphone recording + Whisper where live dictation is not available or switched off
 * - speaking (ab 4.9): Phonon with Anton (like FA KI Desktop); Supertonic only while Phonon is not set up
 */
class VoiceController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val supertonic = Supertonic(app)
    val whisper = WhisperStt(app)
    val phonon = Phonon(app)
    val live = LiveStt()

    var supertonicVoice: String = Supertonic.DEFAULT_VOICE
    var phononVoice: String = Phonon.DEFAULT_VOICE
    var rate: Float = 1f
    /** Live dictation wanted (setting); used when ML Kit is available on this phone. */
    var useLive: Boolean = true

    /** ML Kit live dictation is on this phone (checked once at start, again after a download). */
    private val _liveOk = MutableStateFlow(false)
    val liveOk: StateFlow<Boolean> = _liveOk.asStateFlow()
    init { scope.launch { _liveOk.value = runCatching { live.isAvailable() }.getOrDefault(false) } }
    fun recheckLive() { scope.launch { _liveOk.value = runCatching { live.isAvailable() }.getOrDefault(false) } }

    /** Phonon is used as soon as it is set up. */
    val usesPhonon: Boolean get() = phonon.isReady
    /** Something can read aloud. */
    val canSpeak: Boolean get() = phonon.isReady || supertonic.isReady
    /** Something can listen. */
    val canListen: Boolean get() = (useLive && _liveOk.value) || whisper.isReady

    /** Name of the voice that reads aloud (settings overview). */
    val voiceLabel: String get() = if (phonon.isReady) Phonon.voiceOf(phononVoice).name
        else if (supertonic.isReady) (Supertonic.VOICES[supertonicVoice]?.second?.substringBefore(" (") ?: "Tessa") else "nicht eingerichtet"

    /** Both models downloaded (one-time setup). */
    val isReady: Boolean get() = canSpeak && canListen

    /** What the microphone side is doing right now (drives buttons, so nothing can hang). */
    enum class ListenPhase { IDLE, RECORDING, TRANSCRIBING }

    private val _listenPhase = MutableStateFlow(ListenPhase.IDLE)
    val listenPhase: StateFlow<ListenPhase> = _listenPhase.asStateFlow()

    private var listenJob: Job? = null
    /** Every listen() gets a new number; callbacks of an older (stopped) session are dropped. */
    private var session = 0L
    @Volatile private var stopRequested = false
    private var focus: AudioFocusRequest? = null

    /**
     * Records until the user pauses, then recognizes the words with Whisper.
     * No intermediate text is shown anywhere: [onTranscribing] only signals that recognition runs,
     * [onFinal] gets the text (empty = nothing understood). [onEnd] runs exactly once at the end
     * of the session, also after a stop or cancel, so callers can always reset their state.
     * Starting again while a session runs ends the old one first (no double start, the
     * microphone of the old session is released before the new recording begins).
     */
    fun listen(
        onFinal: (String) -> Unit, onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {}, onTranscribing: () -> Unit = {}, onEnd: () -> Unit = {}, onPartial: (String) -> Unit = {},
    ) {
        stopSpeaking()
        val previous = listenJob
        previous?.cancel()
        val id = ++session
        if (useLive && _liveOk.value) {
            stopRequested = false
            _listenPhase.value = ListenPhase.RECORDING
            listenJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    previous?.join()
                    requestFocus()
                    val text = live.listen(
                        level = { l -> scope.launch { if (id == session) onLevel(l) } },
                        onPartial = { t -> if (id == session) onPartial(t) },
                        stopNow = { stopRequested || id != session },
                    )
                    abandonFocus()
                    if (id == session) onFinal(text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Live dictation failed (e.g. no German model): from now on Whisper, if it is set up.
                    android.util.Log.w("FoldKI", "Live-Diktat: ${e.message}")
                    _liveOk.value = false
                    if (id == session) onError(if (whisper.isReady) "Live-Diktat ging nicht – bitte nochmal tippen (jetzt mit Whisper)." else "Spracheingabe fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    abandonFocus()
                    if (id == session) _listenPhase.value = ListenPhase.IDLE
                    onEnd()
                }
            }
            return
        }
        if (!whisper.isReady) {
            _listenPhase.value = ListenPhase.IDLE
            onError("Bitte zuerst die Sprachfunktion einrichten (Einstellungen)."); onEnd(); return
        }
        stopRequested = false
        _listenPhase.value = ListenPhase.RECORDING
        scope.launch { whisper.warmUp() } // load Whisper in parallel to the recording (only the first time takes long)
        // UNDISPATCHED: the try/finally is entered at once, so onEnd runs even if stopped right away.
        listenJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                requestFocus()
                val pcm = whisper.record(
                    level = { l -> scope.launch { if (id == session) onLevel(l) } },
                    stopNow = { stopRequested || id != session },
                )
                abandonFocus()
                if (id != session) return@launch
                if (pcm.isEmpty()) { onFinal(""); return@launch }
                _listenPhase.value = ListenPhase.TRANSCRIBING
                onTranscribing()
                val text = whisper.transcribe(pcm)
                if (id == session) onFinal(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (id == session) onError("Spracheingabe fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                abandonFocus()
                if (id == session) _listenPhase.value = ListenPhase.IDLE
                onEnd()
            }
        }
    }

    /** Ends the recording now; the recorded part is still recognized (like "fertig"). */
    fun stopListening() {
        if (_listenPhase.value == ListenPhase.RECORDING) stopRequested = true
    }

    /** Stops everything at once: recording or recognition, nothing is delivered. */
    fun cancelListening() {
        session++
        stopRequested = true
        listenJob?.cancel(); listenJob = null
        _listenPhase.value = ListenPhase.IDLE
        abandonFocus()
    }

    /** Stop for the round button: ends recording, recognition and speech output immediately. */
    fun stopAll() { cancelListening(); stopSpeaking() }

    /** Reads [text] aloud with Tessa or Caleb; [onDone] runs afterwards. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        stopSpeaking()
        if (text.isBlank() || !canSpeak) { onDone(); return }
        if (phonon.isReady) phonon.speak(text, phononVoice, rate, onDone) { err -> android.util.Log.w("FoldKI", "Phonon: $err") }
        else supertonic.speak(text, supertonicVoice, rate, onDone) { err ->
            android.util.Log.w("FoldKI", "Supertonic: $err")
            onDone()
        }
    }

    // ---- Anruf-Modus (ab 4.9): sentences are spoken while the model still writes ----
    private var queue = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var queueJob: Job? = null
    @Volatile private var queued = 0
    /** Changes with every clearQueue(), so a stopped job does not count down the new queue. */
    @Volatile private var queueGen = 0

    /** Adds one sentence; it is spoken right after the ones before it. */
    fun enqueue(sentence: String) {
        if (sentence.isBlank() || !canSpeak) return
        queued++
        queue.trySend(sentence)
        val myGen = queueGen
        val ch = queue
        if (queueJob?.isActive != true) queueJob = scope.launch {
            for (s in ch) {
                try {
                    if (phonon.isReady) phonon.speakAndWait(s, phononVoice, rate)
                    else kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
                        supertonic.speak(s, supertonicVoice, rate, { if (c.isActive) c.resume(Unit) }) { if (c.isActive) c.resume(Unit) }
                    }
                } finally { if (myGen == queueGen && queued > 0) queued-- }
            }
        }
    }

    /** true while queued sentences are still being spoken. */
    val queueBusy: Boolean get() = queued > 0

    private fun clearQueue() {
        queueGen++
        queueJob?.cancel(); queueJob = null
        queue.close()
        queue = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
        queued = 0
    }

    fun previewPhonon(voice: String, onError: (String) -> Unit) {
        stopSpeaking()
        phonon.speak("Hallo, ich bin ${Phonon.voiceOf(voice).name}. Wie kann ich dir helfen?", voice, rate, {}, onError)
    }

    fun previewSupertonic(voice: String, onError: (String) -> Unit) {
        stopSpeaking()
        val name = Supertonic.VOICES[voice]?.second?.substringBefore(" (") ?: voice
        supertonic.speak("Hallo, ich bin $name. Wie kann ich dir helfen?", voice, rate, {}, onError)
    }

    val isSpeaking: Boolean get() = supertonic.isPlaying || phonon.isPlaying || queued > 0

    fun stopSpeaking() { clearQueue(); supertonic.stop(); phonon.stop() }

    fun release() { stopAll(); whisper.release(); phonon.release(); live.close() }

    /** Keeps Whisper loaded so the first dictation does not wait for the model (~250 MB RAM). */
    fun prewarm() { if (whisper.isReady && !whisper.isLoaded) scope.launch { whisper.warmUp() } }

    /** Android needs memory: free Whisper unless it is in use; it loads again when needed. */
    fun trimMemory() { if (_listenPhase.value == ListenPhase.IDLE) whisper.release(); if (!isSpeaking) phonon.release() }

    /** Pauses music (e.g. Spotify) while listening, like other assistant apps do. */
    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        runCatching { audio.requestAudioFocus(req) }
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }
}
