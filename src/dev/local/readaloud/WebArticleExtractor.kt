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

    /**
     * For client-rendered pages (Angular/React shells whose HTML holds no text, e.g. gridarchitects.co.uk):
     * load the URL in an off-screen WebView so its JavaScript runs, wait for the content to settle,
     * then extract from the rendered DOM. Blocks the calling (background) thread; null on failure.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun fetchRendered(context: android.content.Context, url: String, timeoutMs: Long = 25_000): Result? {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.CountDownLatch(1)
        var html: String? = null
        var webView: android.webkit.WebView? = null
        val deadline = System.currentTimeMillis() + timeoutMs

        fun finish(h: String?) { if (done.count > 0) { html = h; done.countDown() } }

        // Poll the rendered text length until it stops growing (two equal readings), then grab the HTML.
        fun poll(lastLen: Int, stable: Int) {
            val wv = webView
            if (wv == null) { finish(null); return }
            if (System.currentTimeMillis() > deadline) {
                wv.evaluateJavascript("document.documentElement.outerHTML") { finish(decode(it)) }
                return
            }
            wv.evaluateJavascript("document.body ? document.body.innerText.length : 0") { v ->
                val len = v?.trim()?.toIntOrNull() ?: 0
                val st = if (len > 200 && len == lastLen) stable + 1 else 0
                if (st >= 2) wv.evaluateJavascript("document.documentElement.outerHTML") { finish(decode(it)) }
                else main.postDelayed({ poll(len, st) }, 700)
            }
        }

        main.post {
            try {
                val wv = android.webkit.WebView(context.applicationContext)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                        main.postDelayed({ poll(-1, 0) }, 1200)
                    }
                    override fun onReceivedError(view: android.webkit.WebView?, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                        if (req?.isForMainFrame == true) finish(null)
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) { finish(null) }
        }
        done.await(timeoutMs + 5_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        main.post { try { webView?.stopLoading(); webView?.destroy() } catch (_: Exception) {} }
        return html?.let { extract(it) }
    }

    /** evaluateJavascript hands back a JSON string literal. */
    private fun decode(raw: String?): String? {
        if (raw == null) return null
        return try { org.json.JSONTokener(raw).nextValue() as? String } catch (_: Exception) { null }
    }
}
