package dev.local.readaloud

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * WhatsApp needs two things GenericProfile alone doesn't give it:
 *
 * 1. Reading the main chat list aloud would announce several contacts'
 *    private message previews at once from a single, easy-to-trigger-by-
 *    accident gesture - explicitly asked to keep Read Aloud INERT there,
 *    active only inside an open conversation. Detected via the presence
 *    of the message compose box (`entry`) rather than an Activity class
 *    name - ReadAloudAccessibilityService only ever hands profiles a
 *    package+root pair, not the foreground Activity - and `entry` is
 *    definitionally only present on a conversation screen (1:1 or
 *    group), never the chat list, Updates/Communities/Calls tabs, or any
 *    Settings screen.
 *
 * 2. Inside a conversation, read FORWARD from wherever the conversation
 *    is currently scrolled to - not the whole history from the very
 *    first message, which for a long-running chat could be months of
 *    content the user has already read. This is the same idea as
 *    Gmail's onwards/backwards (see GmailProfile's own doc), applied to
 *    a single continuously-scrolling thread instead of a list of
 *    separate screens: whatever's on screen right now IS "the current
 *    position," so extraction starts there with no initial scroll-to-
 *    top phase, then scrolls forward (toward the newest message)
 *    accumulating text until two consecutive scrolls add nothing new
 *    (genuinely at the bottom).
 *
 * Confirmed live 2026-09-14 against a real conversation dump: every
 * message bubble (sent or received) is a plain TextView
 * (`com.whatsapp:id/message_text`) with real `.text`, not a
 * content-desc, so the generic collectText() walk already picks each
 * one up correctly (including a sender-name label, if a group chat adds
 * one as its own TextView - no WhatsApp-specific label mapping needed
 * the way Gmail's Subject/From did). The one real noise source: a
 * delivery-tick ImageView (`status`) sits right next to every SENT
 * message with content-desc "Read"/"Delivered"/"Sent" - excluded via
 * collectText()'s excludeIds so a "Read"/"Delivered" line doesn't get
 * interspersed after nearly every message.
 */
object WhatsAppProfile : AppProfile {
    override val packageName: String = "com.whatsapp"
    private const val TAG = "WhatsAppProfile"
    private const val MAX_SCROLLS = 30

    private val EXCLUDE_IDS = setOf("status")

    private fun isOpenConversation(root: AccessibilityNodeInfo): Boolean =
        AccessibilityTree.findNode(root) { it.viewIdResourceName?.endsWith("com.whatsapp:id/entry") == true } != null

    override fun runMode(service: ReadAloudAccessibilityService, mode: String, label: String): Boolean {
        val root = service.findForegroundWithRetry(packageName)?.second
        if (root == null || !isOpenConversation(root)) {
            service.toast("Open a WhatsApp chat to use Read Aloud")
            return true
        }
        val lines = try { extractForwardFromCurrentPosition(service, root) } catch (e: Exception) {
            Log.e(TAG, "extractForwardFromCurrentPosition crashed", e)
            emptyList()
        }
        val text = lines.joinToString("\n").trim()
        Log.i(TAG, "runMode: ${text.length} chars from ${lines.size} lines")
        if (text.isBlank()) { service.toast("Nothing readable found on screen"); return true }
        service.toast("Reading $label…")
        TtsSpeaker.speak(service, label, text)
        return true
    }

    // Only reachable if runMode() above didn't already handle everything -
    // never actually happens for this profile (runMode always returns
    // true), kept only because AppProfile.extract() has no default and
    // every implementer needs SOME body.
    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> =
        emptyList()

    /** Scrolls the message list forward (toward the newest message) from
     * wherever it currently sits, accumulating text via a LinkedHashSet
     * (same dedup tradeoff GmailProfile/RedditProfile's own scroll-
     * accumulate loops already accept) until two consecutive scrolls add
     * nothing new - genuinely reached the bottom of what's loaded. No
     * initial "scroll to top" phase - see class doc for why starting
     * from the CURRENT position is the whole point here, unlike Gmail's
     * full-thread read. */
    private fun extractForwardFromCurrentPosition(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> {
        var current = root
        val seen = LinkedHashSet<String>()
        fun captureCurrent() {
            AccessibilityTree.collectText(current, EXCLUDE_IDS).forEach { seen.add(it) }
        }
        captureCurrent()
        var scrolls = 0
        var stagnantPasses = 0
        while (scrolls < MAX_SCROLLS && stagnantPasses < 2) {
            val beforeSize = seen.size
            val sv = AccessibilityTree.largestScrollable(current) ?: break
            val ok = service.scrollForward(sv)
            Log.i(TAG, "extractForwardFromCurrentPosition: scrollForward[$scrolls] ok=$ok")
            if (!ok) break
            scrolls++
            Thread.sleep(300)
            current = service.foregroundRoot()?.second ?: break
            captureCurrent()
            stagnantPasses = if (seen.size == beforeSize) stagnantPasses + 1 else 0
        }
        return seen.toList()
    }
}
