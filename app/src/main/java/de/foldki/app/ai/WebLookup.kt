package de.foldki.app.ai

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet lookup for Gemini Nano (which cannot browse by itself):
 * Wikipedia articles for background knowledge + Google News RSS for current headlines.
 * Both are free and need no API key. The results are put into Nano's prompt.
 */
object WebLookup {
    private const val UA = "FoldKI (Android chat app)" // no version number that goes stale

    suspend fun search(query: String): String = searchWithSources(query).first

    /**
     * Ab 4.9: Wikipedia, Google News and a general web search (DuckDuckGo, free, no key – finds shops and prices too).
     * Returns the text for the model and the sources (title, link) that are listed under the answer.
     */
    suspend fun searchWithSources(query: String): Pair<String, List<Pair<String, String>>> = coroutineScope {
        val wiki = async { runCatching { wikipedia(query) }.getOrDefault("" to emptyList()) }
        val news = async { runCatching { news(query) }.getOrDefault("" to emptyList()) }
        val webR = async { runCatching { duck(query) }.getOrDefault("" to emptyList()) }
        val w = wiki.await(); val n = news.await(); val d = webR.await()
        val text = buildString {
            if (d.first.isNotBlank()) append("WEB:\n").append(d.first).append("\n")
            if (w.first.isNotBlank()) append("WIKIPEDIA:\n").append(w.first).append("\n")
            if (n.first.isNotBlank()) append("AKTUELLE NACHRICHTEN:\n").append(n.first)
        }.trim()
        text to (d.second.take(3) + w.second.take(1) + n.second.take(2)).distinctBy { it.second }
    }

    /** General web results (title, short text, link) from DuckDuckGo's HTML page. */
    private suspend fun duck(query: String): Pair<String, List<Pair<String, String>>> {
        val html = get("https://html.duckduckgo.com/html/?kl=de-de&q=" + enc(query), browser = true)
        val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { strip(it.groupValues[1]) }.toList()
        val out = StringBuilder()
        val src = ArrayList<Pair<String, String>>()
        links.take(6).forEachIndexed { i, m ->
            var url = m.groupValues[1].replace("&amp;", "&")
            Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            if (url.contains("duckduckgo.com/y.js")) return@forEachIndexed // ads
            val title = strip(m.groupValues[2])
            out.append("• ").append(title).append(": ").append(snippets.getOrNull(i).orEmpty()).append(" (").append(url).append(")\n")
            src += title to url
        }
        return out.toString() to src
    }

    private fun strip(html: String): String = html.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    private suspend fun get(url: String, browser: Boolean = false): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", if (browser) "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36" else UA)
        if (browser) c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun wikipedia(query: String): Pair<String, List<Pair<String, String>>> {
        val search = JSONObject(get(
            "https://de.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + enc(query)
        )).getJSONObject("query").getJSONArray("search")
        if (search.length() == 0) return "" to emptyList()
        val titles = (0 until search.length()).map { search.getJSONObject(it).getString("title") }
        val pages = JSONObject(get(
            "https://de.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exintro=1&exchars=1200&format=json&titles=" +
                enc(titles.joinToString("|"))
        )).getJSONObject("query").getJSONObject("pages")
        val src = ArrayList<Pair<String, String>>()
        val text = buildString {
            pages.keys().forEach { k ->
                val p = pages.getJSONObject(k)
                val text = p.optString("extract").trim()
                val title = p.optString("title")
                if (text.isNotEmpty()) {
                    append("• ").append(title).append(": ").append(text).append("\n")
                    src += "Wikipedia: $title" to "https://de.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("%2F", "/")
                }
            }
        }
        return text to src
    }

    private suspend fun news(query: String): Pair<String, List<Pair<String, String>>> {
        val xml = get("https://news.google.com/rss/search?hl=de&gl=DE&ceid=DE:de&q=" + enc(query))
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val out = StringBuilder()
        var count = 0
        var inItem = false
        var title = ""; var date = ""; var link = ""
        val src = ArrayList<Pair<String, String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && count < 6) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> { inItem = true; title = ""; date = ""; link = "" }
                    "link" -> if (inItem) link = parser.nextText()
                    "title" -> if (inItem) title = parser.nextText()
                    "pubDate" -> if (inItem) date = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
                if (title.isNotBlank()) {
                    out.append("• ").append(title).append(" (").append(date.take(16)).append(")\n"); count++
                    if (link.isNotBlank()) src += title to link
                }
            }
            event = parser.next()
        }
        return out.toString() to src
    }
}
