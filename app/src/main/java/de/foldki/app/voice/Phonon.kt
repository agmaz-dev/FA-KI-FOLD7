package de.foldki.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Phonon (ab 4.9): the voice "Anton" like in FA KI Desktop – gradium/phonon-40m-multilingual (q8, about 75 MB), fully
 * on the phone through libptts_ffi.so (gradium-ai/xn-ptts v0.7.1, built by GitHub Actions, see phonon/build-phonon.sh).
 * The model is gated on Hugging Face: accept its terms once and enter a Hugging Face token; the voices come from
 * gradium/phonon-voices. Licence of the model: CC BY-NC 4.0 (private, non-commercial use).
 */
class Phonon(context: Context) {
    companion object {
        private const val MODEL_REPO = "https://huggingface.co/gradium/phonon-40m-multilingual/resolve/main/"
        private const val VOICE_REPO = "https://huggingface.co/gradium/phonon-voices/resolve/main/voices/"
        private val MODEL_FILES = listOf(
            "config.json", "generation_config.json", "processor_config.json", "tokenizer.json", "tokenizer.5.0.0.json",
            "tokenizer.model", "tokenizer_config.json", "chat_template.jinja", "default-voice.safetensors", "model.q8.gguf",
        )

        /** German voices of the catalogue (key, name, catalogue id, female). Default: Anton. */
        val VOICES = listOf(
            Voice("anton", "Anton", "3ZKKapPOvuWFcw9f", false),
            Voice("nele", "Nele", "2A2fFf8SkDEJ8nzx", true),
            Voice("mats", "Mats", "Kf5m22mROozoMWj3", false),
            Voice("henrik", "Henrik", "yyS1KYWs6mXoEw7D", false),
            Voice("leon", "Leon", "20zdyYrQPzKlCwkk", false),
            Voice("jette", "Jette", "4Mn9VfG2wsLLEzi5", true),
            Voice("annika", "Annika", "p6Uutkyi3j2iNAUu", true),
            Voice("lotta", "Lotta", "MOrDPn052KUlnKXk", true),
        )
        const val DEFAULT_VOICE = "anton"
        const val APPROX = "75 MB"

        fun voiceOf(key: String): Voice = VOICES.firstOrNull { it.key == key } ?: VOICES.first()

        /** true = libptts_ffi.so is in this APK and loads (checked once). */
        val libAvailable: Boolean by lazy { runCatching { Lib.get(); true }.getOrDefault(false) }
    }

    data class Voice(val key: String, val name: String, val id: String, val female: Boolean)

    private val dir = File(context.filesDir, "phonon/model")
    private val voiceDir = File(dir, "voices")
    private val exec = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + exec)
    private var tts: Tts? = null
    private var ttsVoice = ""
    /** Each speak() gets a new number; stop() or a newer speak() ends the older one. */
    @Volatile private var gen = 0
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    val modelReady: Boolean get() = MODEL_FILES.all { File(dir, it).length() > 0 } && VOICES.all { File(voiceDir, it.key + ".safetensors").length() > 0 }
    val isReady: Boolean get() = libAvailable && modelReady
    val isPlaying: Boolean get() = job?.isActive == true

    /** Downloads model and voices once (needs the Hugging Face token because the model is gated). */
    suspend fun prepare(token: String, onLine: (String) -> Unit) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!libAvailable) throw IllegalStateException("Der Phonon-Teil fehlt in dieser APK. Bitte die neueste Version installieren.")
        dir.mkdirs(); voiceDir.mkdirs()
        val jobs = MODEL_FILES.map { Triple(MODEL_REPO + it, File(dir, it), true) } +
            VOICES.map { Triple(VOICE_REPO + it.id + ".safetensors", File(voiceDir, it.key + ".safetensors"), false) }
        jobs.forEachIndexed { i, (url, f, auth) ->
            if (f.length() > 0) return@forEachIndexed
            onLine("Phonon: Datei ${i + 1} von ${jobs.size} …")
            download(url, f, if (auth) token else "") { mb -> onLine("Phonon: Datei ${i + 1} von ${jobs.size} · $mb MB") }
        }
        onLine("Phonon ist bereit.")
    }

    private fun download(url: String, dest: File, token: String, progress: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "FoldKI")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            when (val code = c.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw IllegalStateException(
                    "Hugging Face verweigert Phonon ($code). Bitte einmal auf huggingface.co/gradium/phonon-40m-multilingual anmelden, " +
                        "die Bedingungen akzeptieren und unten deinen Hugging-Face-Token eintragen.",
                )
                else -> throw IllegalStateException("Hugging Face: Fehler $code bei ${dest.name}")
            }
            var done = 0L; var shown = -1L
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val mb = done / 1_000_000
                        if (mb != shown && mb % 5 == 0L) { shown = mb; progress(mb) }
                    }
                }
            }
            if (done == 0L) throw IllegalStateException("Leere Datei: ${dest.name}")
            if (!part.renameTo(dest)) throw IllegalStateException("Datei konnte nicht gespeichert werden: ${dest.name}")
        } finally { c.disconnect(); part.delete() }
    }

    /** Removes model and voices (Einstellungen → Stimme → Entfernen). */
    fun remove() { stop(); release(); dir.deleteRecursively() }

    /** Speaks [text] with [voice]; [onDone] runs afterwards (also after an error). */
    fun speak(text: String, voice: String, rate: Float, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        val my = ++gen
        job = scope.launch {
            try { play(text, voice, rate, my) } catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) onError(e.message ?: e.javaClass.simpleName) }
            finally { onDone() }
        }
    }

    /** Speaks [text] and returns when it has been played (call mode: one sentence after the other). */
    suspend fun speakAndWait(text: String, voice: String, rate: Float) = suspendCancellableCoroutine { cont ->
        speak(text, voice, rate, onDone = { if (cont.isActive) cont.resume(Unit) }, onError = {})
        cont.invokeOnCancellation { stop() }
    }

    private fun play(text: String, voice: String, rate: Float, my: Int) {
        val stopped: () -> Boolean = { gen != my }
        val t = tts ?: run {
            android.system.Os.setenv("RAYON_NUM_THREADS", "4", true)
            Tts(dir.path, "de").also { tts = it }
        }
        if (ttsVoice != voice) { t.setVoice(voiceOf(voice).key); ttsVoice = voice }
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(24000 * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = tr
        try {
            if (rate != 1f) runCatching { tr.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2f)) }
            tr.play()
            var frames = 0L
            for (chunk in chunks(text)) {
                if (stopped()) break
                t.speak(chunk) { pcm ->
                    if (stopped()) false else { tr.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING); frames += pcm.size; true }
                }
            }
            if (!stopped()) {
                // Let the last piece play out before the track is released.
                val end = System.currentTimeMillis() + 15_000
                while (!stopped() && tr.playbackHeadPosition < frames && System.currentTimeMillis() < end) Thread.sleep(30)
            }
        } finally {
            runCatching { tr.pause(); tr.flush(); tr.stop() }
            tr.release()
            if (track === tr) track = null
        }
    }

    /** Stops speaking at once. */
    fun stop() {
        gen++
        track?.let { runCatching { it.pause(); it.flush() } }
        job?.cancel(); job = null
    }

    /** Frees the model's memory (it loads again with the next sentence). */
    fun release() { scope.launch { runCatching { tts?.close() }; tts = null; ttsVoice = "" } }

    /** Sentences of at most ~200 characters (Phonon reads 200 tokens per call). */
    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        text.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
            var cur = ""
            for (s in para.split(Regex("(?<=[.!?:;])\\s+"))) {
                if (cur.length + s.length + 1 <= 200) cur += (if (cur.isEmpty()) "" else " ") + s
                else { if (cur.isNotBlank()) out += cur; cur = s.take(400) }
            }
            if (cur.isNotBlank()) out += cur
        }
        return out
    }

    /** One loaded model: the calls of ptts.h (wrapper after gradium-ai/xn-ptts android/PhononTTS.kt). */
    private class Tts(modelDir: String, lang: String) : AutoCloseable {
        private var handle: Pointer? = Lib.get().ptts_new(modelDir, 0, lang) ?: throw IllegalStateException(Lib.get().ptts_last_error(null) ?: "Phonon konnte nicht laden.")
        private var onAudio: (FloatArray) -> Boolean = { true }
        private var thrown: Throwable? = null
        // A field, so the callback outlives every native call that gets it.
        private val onFrame = Lib.FrameFn { pcm, n, _ ->
            try { onAudio(pcm.getFloatArray(0, n.toInt())) } catch (t: Throwable) { thrown = t; false }
        }

        fun setVoice(name: String) = check(Lib.get().ptts_set_voice(open(), name))

        fun speak(text: String, onAudio: (FloatArray) -> Boolean) {
            this.onAudio = onAudio
            thrown = null
            val ok = Lib.get().ptts_speak(open(), text, onFrame, null, Result())
            thrown?.let { thrown = null; throw it }
            check(ok)
        }

        override fun close() { handle?.let { Lib.get().ptts_free(it) }; handle = null }

        private fun open() = handle ?: throw IllegalStateException("Phonon ist geschlossen.")

        private fun check(ok: Byte) { if (ok.toInt() == 0) throw IllegalStateException(Lib.get().ptts_last_error(handle) ?: "Phonon-Fehler") }
    }

    /** PttsResult of ptts.h (size_t = Long on 64 bit). */
    @Structure.FieldOrder("frames", "samples", "ttfaMs", "totalMs")
    class Result : Structure() {
        @JvmField var frames: Int = 0
        @JvmField var samples: Long = 0
        @JvmField var ttfaMs: Double = 0.0
        @JvmField var totalMs: Double = 0.0
    }

    /** ptts.h. A C bool is one byte (JNA's Boolean would be four). */
    @Suppress("FunctionName")
    interface Lib : Library {
        fun ptts_new(dir: String, unit: Int, lang: String): Pointer?
        fun ptts_speak(h: Pointer, text: String, cb: FrameFn, user: Pointer?, out: Result): Byte
        fun ptts_set_voice(h: Pointer, name: String): Byte
        fun ptts_last_error(h: Pointer?): String?
        fun ptts_free(h: Pointer)

        fun interface FrameFn : Callback {
            fun invoke(pcm: Pointer, n: Long, user: Pointer?): Boolean
        }

        companion object {
            @Volatile private var lib: Lib? = null
            fun get(): Lib = lib ?: synchronized(this) { lib ?: Native.load("ptts_ffi", Lib::class.java).also { lib = it } }
        }
    }
}
