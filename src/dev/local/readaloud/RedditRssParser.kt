package dev.local.readaloud

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parses the Atom feed at `<permalink>.rss` -- confirmed live 2026-09-13
 * that this endpoint (unlike `.json`, blocked outright, 403, even with a
 * real browser User-Agent) still works, and returns real comment bodies
 * with author attribution: entry ids starting `t3_` are the post itself
 * (usually appearing twice -- Reddit's own feed duplicates it, deduped
 * below by id), `t1_` are comments. No parent/child nesting is present at
 * all -- this is a flat list, and empirically capped at roughly the top
 * ~10 comments regardless of `?limit=`/`?sort=` query params (tested
 * live: both came back with the identical entry count). Good enough for
 * "read me the gist of this thread," not a substitute for the real
 * (blocked) nested-comment JSON tree.
 */
object RedditRssParser {
    data class Entry(val id: String, val author: String?, val content: String)

    fun parse(xml: String): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val out = mutableListOf<Entry>()
        val seenIds = HashSet<String>()
        var inEntry = false
        var currentTag: String? = null
        var id: String? = null
        var author: String? = null
        var contentHtml: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "entry") {
                        inEntry = true
                        id = null; author = null; contentHtml = null
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inEntry) {
                        when (currentTag) {
                            "id" -> id = parser.text
                            "name" -> if (author == null) author = parser.text
                            "content" -> contentHtml = parser.text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry") {
                        inEntry = false
                        val entryId = id
                        val html = contentHtml
                        if (entryId != null && html != null && seenIds.add(entryId)) {
                            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            if (plain.isNotBlank()) {
                                out.add(Entry(entryId, author?.removePrefix("/u/"), plain))
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return out
    }

    /** The post itself (id starts `t3_`) read plainly; each comment (`t1_`)
     * prefixed with its author so the listener knows whose words they're
     * hearing -- there's no tone/formatting cue otherwise the way font/
     * indentation gives one visually. */
    fun toReadableText(entries: List<Entry>): String {
        val sb = StringBuilder()
        for (e in entries) {
            if (e.id.startsWith("t3_")) {
                sb.append(e.content).append("\n\n")
            } else {
                sb.append(e.author ?: "someone").append(" says: ").append(e.content).append("\n\n")
            }
        }
        return sb.toString().trim()
    }
}
