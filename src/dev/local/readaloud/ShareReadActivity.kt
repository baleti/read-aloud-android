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
        private val URL_PATTERN = Regex("""https?://\S*reddit\.com/\S+""")
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
        val sharedText = if (fileUri != null && intent?.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()) {
            fileName = fileUri.lastPathSegment?.substringAfterLast('/')
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
        val redditUrl = if (isFile) null else URL_PATTERN.find(sharedText)?.value
        val anyUrl = if (isFile) null else ANY_URL.find(sharedText)?.value
        val subject = fileName ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)

        Toast.makeText(this, if (anyUrl != null) "Fetching page…" else "Reading…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                when {
                    redditUrl != null -> fetchAndSpeak(redditUrl)
                    anyUrl != null -> fetchPageAndSpeak(anyUrl)
                    else -> TtsSpeaker.speak(applicationContext, subject ?: "Shared text", sharedText)
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

    private fun fetchPageAndSpeak(url: String) {
        val page = WebArticleExtractor.fetch(url)
        if (page == null || page.text.length < 40) {
            mainHandler.post { Toast.makeText(applicationContext, "Couldn't get readable text from that page", Toast.LENGTH_LONG).show() }
            return
        }
        val text = if (page.title.isNotBlank() && !page.text.startsWith(page.title)) page.title + ".\n\n" + page.text else page.text
        TtsSpeaker.speak(applicationContext, page.title.ifBlank { url }, text)
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
        TtsSpeaker.speak(applicationContext, "Reddit thread", text)
    }
}
