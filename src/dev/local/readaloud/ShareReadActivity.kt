package dev.local.readaloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "Share → Read Aloud" sink for ANY app (added 2026-10-07): a Reddit
 * link takes the RSS route below; any other http(s) link is fetched and
 * reduced to its article text by WebArticleExtractor; plain shared text
 * with no link is just spoken as-is. The Reddit notes that follow still
 * apply to that first case.
 *
 * Reddit's second entry point into this app -- necessary because, unlike
 * every other app here, its accessibility tree is too empty to ever read
 * a post's URL off the screen (see RedditProfile's class doc), so the
 * generic corner-swipe "read current screen" trigger has no way to know
 * what to fetch. Instead: tap Share on a Reddit post/comment, pick "Read
 * Aloud" from the share sheet, and this fetches the real comment text via
 * RedditRssParser instead of fighting the app's UI at all.
 *
 * No UI of its own -- shows a brief Toast and finishes immediately, same
 * "invisible, does its job, gets out of the way" shape as the accessibility-
 * triggered path.
 */
class ShareReadActivity : Activity() {
    companion object {
        private const val TAG = "ShareReadActivity"
        private val ANY_URL = Regex("""https?://\S+""")
        private const val LINK_SHARE_MAX_EXTRA_CHARS = 200
        private val URL_PATTERN = Regex("""https?://\S*reddit\.com/\S+""")
        /** Bumped per document read; a streaming PDF read that sees a newer value has been superseded. */
        @Volatile private var readGeneration = 0
        private val PDF_DONE = String()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Text files arrive as a stream/data URI ("Open with" = VIEW data,
        // file-manager "Share" = EXTRA_STREAM) rather than EXTRA_TEXT.
        val fileUri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            else -> @Suppress("DEPRECATION") intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        var fileName: String? = null
        val isPdf = fileUri != null && (intent?.type == "application/pdf" ||
            contentResolver.getType(fileUri) == "application/pdf" || fileUri.lastPathSegment?.endsWith(".pdf", true) == true)
        val docName = fileUri?.let { displayNameOf(it) }.orEmpty()
        val mime = fileUri?.let { intent?.type ?: contentResolver.getType(it) }.orEmpty()
        val isDocx = fileUri != null && (mime.contains("wordprocessingml") || docName.endsWith(".docx", true))
        val isPptx = fileUri != null && (mime.contains("presentationml.presentation") || docName.endsWith(".pptx", true))
        val isHtml = fileUri != null && !isPdf && !isDocx && !isPptx && (mime == "text/html" || docName.endsWith(".html", true) || docName.endsWith(".htm", true))
        if (fileUri != null && (isPdf || isDocx || isPptx || isHtml)) {
            readDocument(fileUri, if (isPdf) "pdf" else if (isDocx) "docx" else if (isPptx) "pptx" else "html")
            finish()
            return
        }
        val sharedText = if (fileUri != null && intent?.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()) {
            fileName = displayNameOf(fileUri)
            try {
                contentResolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.trim()
            } catch (e: Exception) {
                Log.e(TAG, "couldn't open $fileUri", e); null
            }
        } else intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (sharedText.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to read in what was shared", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val isFile = fileUri != null && fileName != null
        // Only a share that is essentially a link ("Title https://...") is fetched. A long text that merely
        // contains links (a copied ChatGPT answer with citations) is the content itself and is read as-is.
        val isLinkShare = !isFile && sharedText.replace(ANY_URL, "").trim().length <= LINK_SHARE_MAX_EXTRA_CHARS
        val redditUrl = if (isLinkShare) URL_PATTERN.find(sharedText)?.value else null
        val anyUrl = if (isLinkShare) ANY_URL.find(sharedText)?.value else null
        // Signal-style content URIs end in an opaque id ("…/attachment/5287"), so a file with no real name
        // gets a title from its first line instead.
        val subject = fileName?.takeIf { !it.matches(Regex("\\d+")) }
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent?.getStringExtra(Intent.EXTRA_TITLE)
            ?: if (isFile) firstLineTitle(sharedText) else null

        Toast.makeText(this, if (anyUrl != null) "Fetching page…" else "Reading…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                // A new share replaces whatever is playing right away, not only once the new page has
                // been fetched/rendered (which can take many seconds, leaving the old read going).
                TtsSpeaker.stopCurrent(applicationContext)
                when {
                    redditUrl != null -> fetchAndSpeak(redditUrl)
                    anyUrl != null -> fetchPageAndSpeak(anyUrl)
                    else -> TtsSpeaker.speak(applicationContext, subject ?: "Shared text", sharedText, highlightScreen = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to fetch/read", e)
                mainHandler.post {
                    Toast.makeText(applicationContext, "Couldn't read that: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; name = "ShareFetch"; start() }

        finish()
    }

    /** PDF (OCR), DOCX, PPTX or HTML file -> text, then speaks it. Extraction is cached by file checksum (DocCache). */
    private fun readDocument(uri: Uri, kind: String) {
        val title = (displayNameOf(uri) ?: kind.uppercase()).replace(Regex("\\.(pdf|docx|pptx|html?)$", RegexOption.IGNORE_CASE), "")
        Toast.makeText(this, if (kind == "pdf") "Reading PDF (starts after the first page)…" else "Reading…", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val gen = ++readGeneration
                TtsSpeaker.stopCurrent(applicationContext)
                if (kind == "pdf" && streamPdf(uri, title, gen)) return@Thread
                var lastToast = 0L
                val text = DocCache.getOrExtract(applicationContext, uri) {
                    when (kind) {
                        "pdf" -> PdfReader.extract(applicationContext, uri) { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastToast > 8000 && done < total) {
                                lastToast = now
                                mainHandler.post { Toast.makeText(applicationContext, "PDF: page $done of $total…", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        "docx" -> DocxReader.extract(applicationContext, uri)
                        "pptx" -> PptxReader.extract(applicationContext, uri)
                        else -> contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?.let { WebArticleExtractor.extract(it).text }.orEmpty()
                    }
                }
                if (text.length < 20) {
                    mainHandler.post { Toast.makeText(applicationContext, "No readable text found in that $kind file", Toast.LENGTH_LONG).show() }
                } else TtsSpeaker.speak(applicationContext, title, text, highlightScreen = false)
            } catch (e: Exception) {
                Log.e(TAG, "$kind read failed", e)
                mainHandler.post { Toast.makeText(applicationContext, "Couldn't read that file: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; name = "DocRead"; start() }
    }

    /**
     * Uncached PDF: OCR pages on a background thread and speak page 1 as soon as it is ready, appending
     * each later page to the same playback session. Returns false (nothing spoken) when the PDF is already
     * cached, so the caller takes the normal whole-text path.
     */
    private fun streamPdf(uri: Uri, title: String, gen: Int): Boolean {
        val ctx = applicationContext
        val sum = DocCache.checksum(ctx, uri)
        if (sum != null && DocCache.get(ctx, sum) != null) return false

        val pages = java.util.concurrent.LinkedBlockingQueue<String>()
        val failure = arrayOfNulls<Exception>(1)
        val t0 = System.currentTimeMillis()
        Thread {
            try {
                PdfReader.extractPages(ctx, uri, onPage = { pages.put(it); gen == readGeneration })
            } catch (e: Exception) { failure[0] = e } finally { pages.put(PDF_DONE) }
        }.apply { isDaemon = true; name = "PdfOcr"; start() }

        val all = StringBuilder()
        var started = false
        try {
            while (gen == readGeneration) {
                val page = pages.take()
                if (page === PDF_DONE) break
                if (!started) {
                    started = true
                    ReadAlongState.streaming = true
                    all.append(page)
                    TtsSpeaker.speak(ctx, title, page, highlightScreen = false, keepOpen = true)
                } else {
                    // The user stopped playback from the player: stop OCRing the rest.
                    if (!TtsSpeaker.sessionActive(ctx)) { readGeneration++; break }
                    all.append("\n\n").append(page)
                    TtsSpeaker.speak(ctx, title, page, continueSession = true, keepOpen = true, highlightScreen = false)
                }
            }
        } finally {
            if (gen == readGeneration) {
                ReadAlongState.streaming = false
                if (started) TtsSpeaker.finishOpenSession(ctx)
            } else ReadAlongState.streaming = false
        }
        if (gen == readGeneration) {
            if (!started) {
                val msg = failure[0]?.let { "Couldn't read that file: ${it.message}" } ?: "No readable text found in that pdf file"
                mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            } else if (sum != null && failure[0] == null && System.currentTimeMillis() - t0 >= 3_000L) {
                DocCache.put(ctx, sum, all.toString())
            }
        }
        return true
    }

    /** The provider's real file name (OpenableColumns.DISPLAY_NAME), minus a .txt extension; the URI's last segment as a fallback. */
    private fun displayNameOf(uri: Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it.removeSuffix(".txt") }
            }
        } catch (e: Exception) { Log.w(TAG, "no display name for $uri: ${e.message}") }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun firstLineTitle(text: String): String {
        val line = text.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }.firstOrNull { it.length >= 3 } ?: return "Shared text"
        return if (line.length > 70) line.take(70).trimEnd() + "…" else line
    }

    private fun fetchPageAndSpeak(url: String) {
        var fetched = WebArticleExtractor.fetch(url)
        // A plain fetch of a JavaScript-rendered site returns an empty shell: render it in a WebView instead.
        if (fetched == null || fetched.text.length < 200) {
            mainHandler.post { Toast.makeText(applicationContext, "Rendering page…", Toast.LENGTH_SHORT).show() }
            WebArticleExtractor.fetchRendered(applicationContext, url)?.let { fetched = it }
        }
        val page = fetched
        if (page == null || page.text.length < 40) {
            mainHandler.post { Toast.makeText(applicationContext, "Couldn't get readable text from that page", Toast.LENGTH_LONG).show() }
            return
        }
        val text = if (page.title.isNotBlank() && !page.text.startsWith(page.title)) page.title + ".\n\n" + page.text else page.text
        TtsSpeaker.speak(applicationContext, page.title.ifBlank { url }, text, highlightScreen = false)
    }

    private fun fetchAndSpeak(rawUrl: String) {
        val rssUrl = rawUrl.substringBefore("?").substringBefore("#").trimEnd('/') + "/.rss"
        val conn = URL(rssUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        )
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = conn.responseCode
        if (code != 200) {
            mainHandler.post { Toast.makeText(applicationContext, "Reddit returned $code fetching that thread", Toast.LENGTH_LONG).show() }
            return
        }
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        val entries = RedditRssParser.parse(xml)
        val text = RedditRssParser.toReadableText(entries)
        if (text.isBlank()) {
            mainHandler.post { Toast.makeText(applicationContext, "Nothing readable in that thread's feed", Toast.LENGTH_LONG).show() }
            return
        }
        TtsSpeaker.speak(applicationContext, "Reddit thread", text, highlightScreen = false)
    }
}
