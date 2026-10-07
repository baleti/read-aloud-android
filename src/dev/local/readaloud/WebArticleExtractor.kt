package dev.local.readaloud

import android.text.Html

/**
 * Turns a fetched web page into the text worth reading aloud, in DOM
 * order - which is the reading order the author intended, unlike the
 * accessibility-tree route (CSS-reordered pages come out shuffled there).
 * Deliberately dependency-free (regex, no Jsoup): drops script/style/nav/
 * header/footer/aside/form blocks, prefers the <article> (else <main>)
 * region, then keeps headings, paragraphs and list items.
 */
object WebArticleExtractor {
    class Result(val title: String, val text: String)

    /** Fetches `url` and extracts it; null on any HTTP/network failure. Throws
     * nothing - callers fall back to another route when this returns null. */
    fun fetch(url: String): Result? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        )
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (conn.responseCode != 200) null
        else extract(conn.inputStream.bufferedReader().use { it.readText() })
    } catch (_: Exception) { null }

    private val NOISE = Regex(
        """<(script|style|noscript|svg|nav|header|footer|aside|form|template|iframe|button|sup|table)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK = Regex("""<(h[1-6]|p|li|blockquote)\b[^>]*>(.*?)</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("""<[^>]+>""")

    private fun region(html: String, tag: String): String? =
        Regex("""<$tag\b[^>]*>(.*)</$tag\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)

    private fun plain(fragment: String): String =
        Html.fromHtml(TAG.replace(fragment, ""), Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    fun extract(html: String): Result {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let(::plain).orEmpty()
        val cleaned = COMMENT.replace(NOISE.replace(html, " "), " ")
        val body = region(cleaned, "article") ?: region(cleaned, "main") ?: region(cleaned, "body") ?: cleaned
        val lines = BLOCK.findAll(body)
            .map { plain(it.groupValues[2]) }
            .filter { it.length >= 2 }
            .toList()
        // A page that's all <div>s with no <p> at all: fall back to the stripped text.
        val text = if (lines.sumOf { it.length } >= 200) lines.joinToString("\n\n") else plain(body)
        return Result(title, text)
    }
}
