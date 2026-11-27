package de.foldki.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Supertonic 3 – fast, natural on-device voice (ONNX Runtime, CPU only).
 * Ported from the FA-KI-Desktop implementation (based on Supertone's official example:
 * code MIT, model OpenRAIL-M). Model files (~250 MB) are downloaded once into app storage.
 */
class Supertonic(context: Context) {
    companion object {
        private const val REV = "aafc6e32416a594460b32413efc49d7fe4ce6d46"
        private const val REPO = "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/"

        /** The two voices used in all of the user's apps: Tessa and Caleb (style ids as on the desktop). */
        val VOICES = linkedMapOf(
            "tessa" to ("F5" to "Tessa (weiblich)"),
            "caleb" to ("M2" to "Caleb (männlich)"),
        )
        const val DEFAULT_VOICE = "tessa"

        private val MODEL_FILES = listOf(
            "onnx/tts.json",
            "onnx/unicode_indexer.json",
            "onnx/duration_predictor.onnx",
            "onnx/text_encoder.onnx",
            "onnx/vector_estimator.onnx",
            "onnx/vocoder.onnx",
        )
        private fun voiceFile(id: String) = "voice_styles/$id.json"
    }

    private val dir = File(context.filesDir, "supertonic")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private val styles = HashMap<String, Style>()
    private var playJob: Job? = null
    private var track: AudioTrack? = null

    private class Engine(
        val env: OrtEnvironment,
        val indexer: IntArray,
        val sampleRate: Int, val chunkSize: Int, val latentDim: Int,
        val dp: OrtSession, val enc: OrtSession, val vec: OrtSession, val voc: OrtSession,
    )
    private class Style(val ttl: OnnxTensor, val dp: OnnxTensor)

    private fun file(rel: String) = File(dir, rel)

    val isReady: Boolean
        get() = MODEL_FILES.all { file(it).exists() } && VOICES.values.any { file(voiceFile(it.first)).exists() }

    val isPlaying: Boolean get() = playJob?.isActive == true

    // ---------------------------------------------------------------- download

    /** Downloads all missing files; [progress] gets a German status line. */
    suspend fun prepare(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val rels = (MODEL_FILES + VOICES.values.map { voiceFile(it.first) }).filter { !file(it).exists() }
        rels.forEachIndexed { i, rel ->
            val label = "Supertonic: Datei ${i + 1} von ${rels.size} (${rel.substringAfterLast('/')})"
            var lastErr: Exception? = null
            for (rev in listOf(REV, "main")) {
                try {
                    download(REPO + rev + "/" + rel, file(rel)) { got, total ->
                        val pct = if (total > 0) " – ${got * 100 / total} %" else " – ${got / 1_000_000} MB"
                        progress(label + pct)
                    }
                    lastErr = null
                    break
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    lastErr = e
                }
            }
            lastErr?.let { throw IllegalStateException("${rel.substringAfterLast('/')} konnte nicht geladen werden (${it.message}).") }
        }
        engine = null
        progress("Supertonic ist eingerichtet.")
    }

    private suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var got = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport > 1_000_000) { lastReport = got; onProgress(got, total) }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("Speichern fehlgeschlagen")
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- inference

    private fun load(): Engine {
        engine?.let { return it }
        if (!MODEL_FILES.all { file(it).exists() }) throw IllegalStateException("Supertonic ist noch nicht eingerichtet.")
        val env = OrtEnvironment.getEnvironment()
        val cfg = JSONObject(file("onnx/tts.json").readText())
        val ae = cfg.getJSONObject("ae"); val ttl = cfg.getJSONObject("ttl")
        val idx = JSONArray(file("onnx/unicode_indexer.json").readText())
        val indexer = IntArray(idx.length()) { idx.optInt(it, -1) }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
        }
        fun mk(n: String) = env.createSession(file("onnx/$n.onnx").absolutePath, opts)
        val compress = ttl.getInt("chunk_compress_factor")
        return Engine(
            env, indexer,
            sampleRate = ae.getInt("sample_rate"),
            chunkSize = ae.getInt("base_chunk_size") * compress,
            latentDim = ttl.getInt("latent_dim") * compress,
            dp = mk("duration_predictor"), enc = mk("text_encoder"), vec = mk("vector_estimator"), voc = mk("vocoder"),
        ).also { engine = it }
    }

    private fun style(eng: Engine, name: String): Style {
        val key = if (VOICES.containsKey(name)) name else DEFAULT_VOICE
        styles[key]?.let { return it }
        val (id, label) = VOICES.getValue(key)
        val f = file(voiceFile(id))
        if (!f.exists()) throw IllegalStateException("Stimme „$label“ ist noch nicht geladen.")
        val j = JSONObject(f.readText())
        fun tensor(o: JSONObject): OnnxTensor {
            val dimsJ = o.getJSONArray("dims")
            val dims = LongArray(dimsJ.length()) { dimsJ.getLong(it) }
            val flat = ArrayList<Float>()
            fun walk(a: Any) { if (a is JSONArray) for (i in 0 until a.length()) walk(a.get(i)) else flat += (a as Number).toFloat() }
            walk(o.getJSONArray("data"))
            return OnnxTensor.createTensor(eng.env, FloatBuffer.wrap(flat.toFloatArray()), dims)
        }
        return Style(tensor(j.getJSONObject("style_ttl")), tensor(j.getJSONObject("style_dp"))).also { styles[key] = it }
    }

    private fun randNormal(): Float {
        val u1 = max(1e-10, Random.nextDouble()); val u2 = Random.nextDouble()
        return (sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)).toFloat()
    }

    private suspend fun infer(eng: Engine, chunk: String, st: Style, steps: Int, speed: Float): FloatArray {
        val text = preprocess(chunk, "de")
        val ids = ArrayList<Long>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val v = if (cp < eng.indexer.size) eng.indexer[cp] else -1
            if (v >= 0) ids += v.toLong()
            i += Character.charCount(cp)
        }
        val n = ids.size
        if (n == 0) return FloatArray(0)
        val env = eng.env
        return OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, n.toLong())).use { textIds ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { 1f }), longArrayOf(1, 1, n.toLong())).use { textMask ->
            val dur = eng.dp.run(mapOf("text_ids" to textIds, "style_dp" to st.dp, "text_mask" to textMask)).use { r ->
                (r.get("duration").get() as OnnxTensor).floatBuffer.get(0) / speed
            }
            eng.enc.run(mapOf("text_ids" to textIds, "style_ttl" to st.ttl, "text_mask" to textMask)).use { encRes ->
                val textEmb = encRes.get("text_emb").get() as OnnxTensor
                val sr = eng.sampleRate
                val wavLen = (dur * sr).toInt()
                val latentLen = max(1, ((dur * sr + eng.chunkSize - 1) / eng.chunkSize).toInt())
                val validLen = (wavLen + eng.chunkSize - 1) / eng.chunkSize
                val dim = eng.latentDim
                var cur = FloatArray(dim * latentLen) { k -> if (k % latentLen < validLen) randNormal() else 0f }
                val shape = longArrayOf(1, dim.toLong(), latentLen.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(latentLen) { if (it < validLen) 1f else 0f }), longArrayOf(1, 1, latentLen.toLong())).use { latentMask ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1)).use { totalStep ->
                    for (s in 0 until steps) {
                        coroutineContext.ensureActive()
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(cur), shape).use { noisy ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(s.toFloat())), longArrayOf(1)).use { curStep ->
                            eng.vec.run(mapOf(
                                "noisy_latent" to noisy, "text_emb" to textEmb, "style_ttl" to st.ttl,
                                "text_mask" to textMask, "latent_mask" to latentMask,
                                "total_step" to totalStep, "current_step" to curStep,
                            )).use { r ->
                                val fb = (r.get("denoised_latent").get() as OnnxTensor).floatBuffer
                                cur = FloatArray(fb.remaining()).also { fb.get(it) }
                            }
                        } }
                    }
                } }
                coroutineContext.ensureActive()
                OnnxTensor.createTensor(env, FloatBuffer.wrap(cur), shape).use { lat ->
                    eng.voc.run(mapOf("latent" to lat)).use { r ->
                        val fb = (r.get("wav_tts").get() as OnnxTensor).floatBuffer
                        val all = FloatArray(fb.remaining()).also { fb.get(it) }
                        if (all.size > wavLen) all.copyOf(wavLen) else all
                    }
                }
            }
        } }
    }

    // ---------------------------------------------------------------- playback

    /**
     * Speaks [text]: sentences are synthesized one after another and played while the next
     * one is being computed. [onDone] runs on the main thread; [onError] if synthesis fails.
     */
    fun speak(text: String, voice: String, speed: Float, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        playJob = scope.launch {
            var t: AudioTrack? = null
            try {
                val eng = load()
                val st = style(eng, voice)
                val sp = min(1.6f, max(0.6f, 1.05f * speed))
                val chunks = chunkText(text, 220)
                val gap = FloatArray((0.25 * eng.sampleRate).toInt())
                val audio = Channel<FloatArray>(capacity = 2)
                launch {
                    try {
                        for ((i, c) in chunks.withIndex()) {
                            if (i > 0) audio.send(gap)
                            audio.send(infer(eng, c, st, 8, sp))
                        }
                        audio.close()
                    } catch (e: Throwable) {
                        audio.close(e)
                    }
                }
                val minBuf = AudioTrack.getMinBufferSize(eng.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(eng.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(minBuf, eng.sampleRate * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.play()
                var frames = 0L
                withContext(Dispatchers.IO) {
                    for (pcm in audio) {
                        ensureActive()
                        var off = 0
                        while (off < pcm.size && isActive) {
                            // Small slices, so a stop is noticed quickly.
                            val w = t.write(pcm, off, min(4096, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) break
                            off += w
                        }
                        frames += pcm.size
                    }
                    // Wait until the last samples have actually been played.
                    while (isActive && (t.playbackHeadPosition.toLong() and 0xffffffffL) < frames) delay(50)
                }
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: e.javaClass.simpleName) }
            } finally {
                runCatching { t?.stop() }; runCatching { t?.release() }
                if (track === t) track = null
            }
        }
    }

    /**
     * Stops speaking at once: silence (pause + flush), then stop() wakes a write that is still
     * blocking, so the playback coroutine ends and releases the AudioTrack. onDone is not called.
     */
    fun stop() {
        playJob?.cancel(); playJob = null
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    // ---------------------------------------------------------------- text

    /** Prepares text the same way as Supertone's example (and the desktop app). */
    private fun preprocess(input: String, lang: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
        text = text.replace(Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F7FF}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}]+"), "")
        val rep = mapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"", "”" to "\"", "„" to "\"",
            "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ",
            "#" to " ", "→" to " ", "←" to " ", "•" to " ", "*" to " ",
        )
        for ((k, v) in rep) text = text.replace(k, v)
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
        text = text.replace("@", " at ")
        for (p in listOf(",", ".", "!", "?", ";", ":", "'")) text = text.replace(" $p", p)
        while (text.contains("\"\"")) text = text.replace("\"\"", "\"")
        while (text.contains("''")) text = text.replace("''", "'")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (!Regex("[.!?;:,'\")\\]}…。」』】〉》›»]$").containsMatchIn(text)) text += "."
        val result = "<$lang>$text</$lang>"
        de.foldki.app.data.TextTrace.speech(input, result)
        return result
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        val out = mutableListOf<String>()
        text.trim().split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
            val sentences = para.split(Regex("(?<=[.!?])\\s+"))
            var cur = ""
            for (s in sentences) {
                if (cur.length + s.length + 1 <= maxLen) cur += (if (cur.isEmpty()) "" else " ") + s
                else { if (cur.isNotBlank()) out += cur.trim(); cur = s.take(maxLen * 2) }
            }
            if (cur.isNotBlank()) out += cur.trim()
        }
        return out
    }
}
