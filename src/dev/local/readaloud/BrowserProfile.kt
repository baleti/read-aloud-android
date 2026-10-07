package dev.local.readaloud

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Browsers (Vanadium, Chrome, Brave, Firefox, Edge, DuckDuckGo): the
 * accessibility tree of a web page is the DOM cut into per-span nodes,
 * in whatever order the page's CSS/DOM produced, so reading it direct
 * came out choppy and out of order (reported 2026-10-07, Vanadium).
 * Instead read the address bar's URL off the toolbar and run the page
 * through WebArticleExtractor - the same route as Share -> Read Aloud.
 * The tree stays as the fallback, and as a sanity check: a logged-in or
 * JS-rendered page whose plain fetch comes back far thinner than what's
 * on screen (login wall, empty shell) is read from the screen instead.
 */
object BrowserProfile : AppProfile {
    override val packageName = "*browser*"

    val PACKAGES = listOf(
        "app.vanadium.browser", "com.android.chrome", "com.brave.browser",
        "org.mozilla.firefox", "org.mozilla.fenix", "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
    )

    private val URL_ID_HINTS = listOf("url_bar", "url_view", "omnibartextinput", "mozac_browser_toolbar_url")
    private val LOOKS_LIKE_URL = Regex("""^(https?://)?[\w-]+(\.[\w-]+)+(:\d+)?(/\S*)?$""")

    private fun currentUrl(root: AccessibilityNodeInfo): String? {
        val node = AccessibilityTree.findNode(root) { n ->
            val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase()
            id != null && URL_ID_HINTS.any { id.contains(it) } && !n.text.isNullOrBlank()
        } ?: return null
        val raw = node.text.toString().trim()
        if (!LOOKS_LIKE_URL.matches(raw)) return null // search terms typed in, "Search or type URL" hint, etc.
        return if (raw.startsWith("http")) raw else "https://$raw"
    }

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> {
        val onScreen = GenericProfile.extract(service, root, mode)
        val url = currentUrl(root) ?: return onScreen
        val page = WebArticleExtractor.fetch(url)
        val screenChars = onScreen.sumOf { it.length }
        if (page == null || page.text.length < 200 || page.text.length < screenChars / 3) {
            Log.i("BrowserProfile", "fetch of $url unusable (${page?.text?.length}), reading the screen (${screenChars} chars)")
            return onScreen
        }
        Log.i("BrowserProfile", "read $url via fetch (${page.text.length} chars vs ${screenChars} on screen)")
        return listOfNotNull(page.title.takeIf { it.isNotBlank() && !page.text.startsWith(it) }, page.text)
    }
}
