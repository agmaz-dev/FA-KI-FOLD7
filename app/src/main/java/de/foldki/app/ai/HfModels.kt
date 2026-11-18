package de.foldki.app.ai

import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Hugging Face helpers for the model browser (pure functions, unit-tested). Fold KI runs models with
 * LiteRT-LM, so only `.litertlm` files can be downloaded; `.task`/`.tflite`/`.gguf` files are shown as not usable.
 */
object HfModels {
    data class LmFile(
        val repo: String,
        val path: String,
        val size: Long,
        val sha256: String?,
        /** Git revision for the download URL ("main" or a fixed commit). */
        val revision: String = "main",
    ) {
        val fileName: String get() = path.substringAfterLast('/')
        /** Only .litertlm for the phone runtime; "web" files are built for browsers (WebGPU) and do not load here. */
        val usable: Boolean get() = fileName.lowercase().let { it.endsWith(EXT) && !Regex("[_.-]web").containsMatchIn(it) }
        /** Short hint from the file name: "4-Bit", "8-Bit", "16-Bit", "32-Bit" or "". */
        val bits: String get() = bitsOf(fileName)
        val url: String get() = resolveUrl(repo, path, revision)
    }

    const val EXT = ".litertlm"
    private val OTHER_EXT = listOf(".task", ".tflite", ".gguf", ".bin")

    /** First path segments that are HF pages, not "owner/model". */
    private val RESERVED = setOf(
        "models", "datasets", "spaces", "docs", "blog", "pricing", "login", "join", "settings", "organizations",
        "papers", "collections", "tasks", "learn", "posts", "enterprise", "api", "chat", "search", "notifications",
        "new", "new-space", "new-dataset", "inference-endpoints", "huggingchat", "terms-of-service", "privacy", "oauth",
    )

    /** Model list on Hugging Face; without a query it shows LiteRT models ("litert" is in their names). */
    fun searchUrl(query: String): String =
        "https://huggingface.co/models?sort=trending&search=" + URLEncoder.encode(query.trim().ifBlank { "litert" }, "UTF-8")

    fun treeUrl(repo: String): String = "https://huggingface.co/api/models/$repo/tree/main?recursive=true"

    fun resolveUrl(repo: String, path: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** "owner/model" of a model page or file URL on huggingface.co, else null. */
    fun repoFromUrl(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (host != "huggingface.co" && host != "www.huggingface.co" && host != "hf.co") return null
        val seg = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        if (seg.size < 2 || seg[0] in RESERVED) return null
        return seg[0] + "/" + seg[1]
    }

    /** File path inside the repo if the URL points to one model file (…/resolve|blob/<rev>/<path>), else null. */
    fun fileFromUrl(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val seg = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        if (seg.size < 5 || (seg[2] != "resolve" && seg[2] != "blob")) return null
        val path = seg.drop(4).joinToString("/")
        val low = path.lowercase()
        return path.takeIf { low.endsWith(EXT) || OTHER_EXT.any { low.endsWith(it) } }
    }

    /** Model files from the HF API tree (`/api/models/{id}/tree/main`): .litertlm plus other formats (not usable). */
    fun parseTree(repo: String, json: String): List<LmFile> {
        val arr = JSONArray(json)
        val out = ArrayList<LmFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "file") continue
            val path = o.optString("path")
            val low = path.lowercase()
            if (!low.endsWith(EXT) && OTHER_EXT.none { low.endsWith(it) }) continue
            val lfs = o.optJSONObject("lfs")
            val size = lfs?.optLong("size", -1L)?.takeIf { it > 0 } ?: o.optLong("size", 0L)
            val sha = lfs?.optString("oid")?.takeIf { it.length == 64 }
            out += LmFile(repo, path, size, sha)
        }
        return sort(out)
    }

    /** Usable files first, recommended on top, then by size. */
    fun sort(files: List<LmFile>): List<LmFile> {
        val rec = recommend(files)
        return files.sortedWith(compareBy<LmFile>({ !it.usable }, { it != rec }, { it.size }))
    }

    /** Proposal: the smallest 4-bit .litertlm, else the smallest .litertlm. */
    fun recommend(files: List<LmFile>): LmFile? {
        val usable = files.filter { it.usable }
        return usable.filter { it.bits == "4-Bit" }.minByOrNull { it.size } ?: usable.minByOrNull { it.size }
    }

    fun bitsOf(fileName: String): String {
        val n = fileName.lowercase()
        return when {
            Regex("int4|q4|wi4|4bit").containsMatchIn(n) -> "4-Bit"
            Regex("int8|q8|wi8|8bit").containsMatchIn(n) -> "8-Bit"
            Regex("f16|fp16|bf16").containsMatchIn(n) -> "16-Bit"
            Regex("f32|fp32").containsMatchIn(n) -> "32-Bit"
            else -> ""
        }
    }

    /** Too big for the phone's RAM? (model + ~1 GB context/app must fit into ~60 % of the RAM) */
    fun tooBigFor(totalRamBytes: Long, fileSize: Long): Boolean = totalRamBytes > 0 && fileSize + 1_000_000_000L > totalRamBytes * 0.6

    /** Display name for a downloaded file, e.g. "Qwen3-4B-Thinking-2507 · 4-Bit". */
    fun displayName(fileName: String): String {
        if (fileName == LocalModelManager.GEMMA_FILE) return "Gemma 4 E2B"
        val base = fileName.removeSuffix(EXT).removeSuffix(EXT.uppercase())
        val b = bitsOf(base)
        return if (b.isNotEmpty()) "$base · $b" else base
    }

    /** Thinking models write "Gedanken" first (shown collapsed); recognised by the file name. */
    fun isThinking(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains("think") || n.contains("deepseek-r1") || n.contains("-r1-") || n.contains("qwq")
    }

    /** One model repo of litert-community in the grid. */
    data class Repo(val id: String, val downloads: Long, val likes: Long, val files: List<String>) {
        val name: String get() = id.substringAfter('/')
        val usableFiles: List<String> get() = files.filter { LmFile("", it, 0, null).usable }
        val thinking: Boolean get() = isThinking(name)
    }

    /** Google's own conversions for LiteRT-LM – these are built for the runtime in the app. */
    const val OFFICIAL = "litert-community"

    fun officialUrl(): String = "https://huggingface.co/api/models?author=$OFFICIAL&full=true&sort=downloads&direction=-1&limit=500"

    /** Repos with at least one usable .litertlm file, most downloaded first (from `/api/models?author=…&full=true`). */
    fun parseRepos(json: String): List<Repo> {
        val arr = JSONArray(json)
        val out = ArrayList<Repo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("modelId") }
            if (id.isBlank()) continue
            val sib = o.optJSONArray("siblings")
            val files = (0 until (sib?.length() ?: 0)).mapNotNull { sib?.optJSONObject(it)?.optString("rfilename") }
            val r = Repo(id, o.optLong("downloads"), o.optLong("likes"), files)
            if (r.usableFiles.isNotEmpty()) out += r
        }
        return out.sortedByDescending { it.downloads }
    }

    /** All usable LiteRT-LM models of litert-community (network; call off the main thread). */
    fun fetchOfficial(): List<Repo> = get(officialUrl()) { parseRepos(it) }

    private fun <T> get(url: String, parse: (String) -> T): T {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("Hugging Face antwortet mit HTTP $code.")
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** Lists the model files of a repo via the HF API (network; call off the main thread). */
    fun fetchFiles(repo: String): List<LmFile> {
        val c = java.net.URL(treeUrl(repo)).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Dieses Modell ist geschützt (Anmeldung bei Hugging Face nötig).")
            if (code != 200) throw IllegalStateException("Hugging Face antwortet mit HTTP $code.")
            return parseTree(repo, c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
}
