package dev.local.readaloud

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Browsers (Vanadium, Chrome, Brave, Firefox, Edge, DuckDuckGo): read what's on screen from where
 * the page is currently scrolled and scroll as it goes (ScrollReader), like every other app. Only
 * the web content is read, not the toolbar/tab bar, because GenericProfile.screenLines() scopes to
 * the main scrollable area. (An earlier version fetched the page's URL and read it from the top;
 * that route still exists for Share -> Read Aloud, see ShareReadActivity.)
 */
object BrowserProfile : AppProfile {
    override val packageName = "*browser*"

    val PACKAGES = listOf(
        "app.vanadium.browser", "com.android.chrome", "com.brave.browser",
        "org.mozilla.firefox", "org.mozilla.fenix", "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
    )

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> =
        GenericProfile.extract(service, root, mode)
}
