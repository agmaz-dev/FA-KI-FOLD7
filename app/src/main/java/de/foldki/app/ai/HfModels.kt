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

    /** One model repo in the grid (like "Erkunden" in FA KI Desktop). */
    data class Repo(
        val id: String,
        val downloads: Long,
        val likes: Long,
        val files: List<String>,
        val tags: List<String> = emptyList(),
        val pipeline: String = "",
        val updated: String = "",
    ) {
        val name: String get() = id.substringAfter('/')
        val author: String get() = id.substringBefore('/')
        val official: Boolean get() = author.equals(OFFICIAL, ignoreCase = true)
        val usableFiles: List<String> get() = files.filter { LmFile("", it, 0, null).usable }
        val thinking: Boolean get() = isThinking(name)
        /** "Bilder", "Werkzeuge", "Denken" – estimated from the tags and the name (as in FA KI Desktop). */
        val features: List<String> get() = featuresOf(id, tags, pipeline)
        val parameter: String get() = parameterOf(id)
    }

    /** Google's own conversions for LiteRT-LM – these are built for the runtime in the app. */
    const val OFFICIAL = "litert-community"

    /** Sort keys of the HF API with German labels. */
    val SORTS = listOf(
        "downloads" to "Meiste Downloads", "likes" to "Meiste Likes", "trendingScore" to "Im Trend",
        "lastModified" to "Zuletzt aktualisiert", "createdAt" to "Neueste",
    )

    fun officialUrl(sort: String = "downloads"): String =
        "https://huggingface.co/api/models?author=$OFFICIAL&full=true&sort=$sort&direction=-1&limit=500"

    /** Same estimate as FA KI Desktop (lib/hf.js featuresOf). */
    fun featuresOf(id: String, tags: List<String>, pipeline: String): List<String> {
        val t = tags.map { it.lowercase() }.toSet()
        val n = id.lowercase()
        val f = ArrayList<String>()
        if (pipeline == "image-text-to-text" || "image-text-to-text" in t || "vision" in t || "multimodal" in t ||
            Regex("vl\\b|-vl-|vision|gemma-?3n|gemma-?4").containsMatchIn(n)) f += "Bilder"
        if ("tool-use" in t || "function-calling" in t || "tools" in t || "agent" in t ||
            Regex("instruct|-it\\b|-it-|gpt-oss|qwen3|qwen2\\.5|llama-3\\.[123]|mistral|hermes|gemma-?4|functiongemma").containsMatchIn(n)) f += "Werkzeuge"
        if ("reasoning" in t || "thinking" in t || Regex("think|reason|r1\\b|qwq|gpt-oss|qwen3").containsMatchIn(n)) f += "Denken"
        return f
    }

    fun parameterOf(id: String): String =
        Regex("(?:^|[^A-Za-z0-9.])(\\d+(?:\\.\\d+)?\\s?[BM])(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.uppercase().orEmpty()

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it) }

    private fun repoOf(o: org.json.JSONObject): Repo? {
        val id = o.optString("id").ifBlank { o.optString("modelId") }
        if (id.isBlank()) return null
        val sib = o.optJSONArray("siblings")
        val files = (0 until (sib?.length() ?: 0)).mapNotNull { sib?.optJSONObject(it)?.optString("rfilename") }
        return Repo(id, o.optLong("downloads"), o.optLong("likes"), files, strings(o.optJSONArray("tags")),
            o.optString("pipeline_tag"), o.optString("lastModified"))
    }

    /** Repos with at least one usable .litertlm file, in the order of the API (from `/api/models?author=…&full=true`). */
    fun parseRepos(json: String, keepOrder: Boolean = false): List<Repo> {
        val arr = JSONArray(json)
        val out = ArrayList<Repo>()
        for (i in 0 until arr.length()) {
            val r = repoOf(arr.optJSONObject(i) ?: continue) ?: continue
            if (r.usableFiles.isNotEmpty()) out += r
        }
        return if (keepOrder) out else out.sortedByDescending { it.downloads }
    }

    /** All usable LiteRT-LM models of litert-community, sorted by [sort] (network; call off the main thread). */
    fun fetchOfficial(sort: String = "downloads"): List<Repo> = get(officialUrl(sort)) { parseRepos(it, keepOrder = true) }

    /** Info of any repo (also from other uploaders) for the detail page. */
    fun fetchRepo(id: String): Repo = get("https://huggingface.co/api/models/$id") { repoOf(org.json.JSONObject(it)) ?: throw IllegalStateException("Modell nicht gefunden.") }

    /** Description of the model (README without the YAML block), at most [max] characters; "" if there is none. */
    fun fetchReadme(id: String, max: Int = 8000): String = runCatching {
        get("https://huggingface.co/$id/raw/main/README.md") { stripFrontMatter(it).trim().take(max) }
    }.getOrDefault("")

    fun stripFrontMatter(md: String): String {
        val t = md.trimStart()
        if (!t.startsWith("---")) return md
        val end = t.indexOf("\n---", 3)
        return if (end < 0) md else t.substring(end + 4)
    }

    /** Fits the phone? Model + ~1 GB for context next to Android and the app. */
    fun fitText(totalRamBytes: Long, fileSize: Long): Pair<String, Int> = when {
        totalRamBytes <= 0 || fileSize <= 0 -> "" to 0
        fileSize + 1_000_000_000L <= totalRamBytes * 0.45 -> "Passt aufs Handy" to 1
        fileSize + 1_000_000_000L <= totalRamBytes * 0.6 -> "Knapp – evtl. langsam" to 2
        else -> "Zu groß fürs Handy" to 3
    }

    private fun <T> get(url: String, parse: (String) -> T): T {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Dieses Modell ist geschützt (Anmeldung bei Hugging Face nötig).")
            if (code == 404) throw IllegalStateException("Nicht gefunden (HTTP 404).")
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
