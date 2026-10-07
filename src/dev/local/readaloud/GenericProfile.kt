package dev.local.readaloud

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The fallback for every app without a dedicated profile: just read
 * whatever the accessibility tree exposes, in on-screen order, trusting
 * whatever the app itself chose to expose (see AccessibilityTree.collectText's
 * own doc for the content-desc-vs-children rule). No scrolling, no
 * clicking -- MVP scope is "read what's on screen right now" for the
 * unknown-app case; an app that needs more than that earns its own
 * profile (see RedditProfile) rather than every unknown app risking
 * unbounded, possibly-wrong automated interaction.
 *
 * Confirmed live 2026-09-12 that this alone is already enough for Gmail's
 * inbox list (each row's content-desc IS the exact string a screen reader
 * would speak) with no per-app code at all -- GmailProfile exists only for
 * the open-email-body footer noise, not the inbox.
 */
object GenericProfile : AppProfile {
    override val packageName: String = "*generic*" // never registered under this key -- see AppProfileRegistry

    /** One screenful. Reads only the main scrollable area when there is one that fills most
     * of the screen (so toolbars, tab bars and bottom navigation aren't read every screen),
     * and falls back to the whole window otherwise. */
    override fun screenLines(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> =
        AccessibilityTree.collectText(mainScope(root))

    /** Any visible, collapsed accordion/section the app exposes as expandable (web `aria-expanded`
     * headings such as Wikipedia mobile's collapsed sections, expandable list rows). One per call. */
    override fun expandVisible(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val node = AccessibilityTree.findNode(mainScope(root)) { n ->
            // Only section headings (Wikipedia-style collapsible sections): a bare expandable button
            // can be a menu (account / overflow), and opening menus is never what a read wants.
            n.isVisibleToUser && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND } &&
                (n.isHeading || n.parent?.isHeading == true)
        } ?: return false
        if (!node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) return false
        Thread.sleep(350) // let the expanded content render before the screen is re-read
        return true
    }

    fun mainScope(root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val rootBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val main = AccessibilityTree.largestScrollable(root)
        return if (main != null) {
            val r = android.graphics.Rect().also { main.getBoundsInScreen(it) }
            if (r.width().toLong() * r.height() >= rootBounds.width().toLong() * rootBounds.height() * 0.4) main else root
        } else root
    }

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> =
        AccessibilityTree.collectText(root).let { lines ->
            // A tree with almost no text (canvas/game/Compose-without-semantics/
            // image-only screens): last resort is OCR of the screen itself.
            if (lines.sumOf { it.length } >= 40) lines
            else service.ocrScreenshot().lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { lines }
        }
}
