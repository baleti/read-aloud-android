package dev.local.readaloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
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
class RedditShareActivity : Activity() {
    companion object {
        private const val TAG = "RedditShareActivity"
        private val URL_PATTERN = Regex("""https?://\S*reddit\.com/\S+""")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = sharedText?.let { URL_PATTERN.find(it)?.value }
        if (url == null) {
            Toast.makeText(this, "No Reddit link found in what was shared", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, "Fetching thread…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                fetchAndSpeak(url)
            } catch (e: Exception) {
                Log.e(TAG, "failed to fetch/read $url", e)
                mainHandler.post {
                    Toast.makeText(applicationContext, "Couldn't fetch thread: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; name = "RedditRssFetch"; start() }

        finish()
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
