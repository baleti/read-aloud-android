package dev.local.readaloud

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The profile that justified building a per-app plugin system at all (see
 * docs/design.md), and the one genuinely unsolved gap in this MVP -- three
 * findings, all confirmed live 2026-09-12 against a real post/comments
 * screen on this exact app:
 *
 * 1. A cold `uiautomator dump` came back as 17 structural nodes bottoming
 *    out in one `androidx.compose.ui.viewinterop.ViewFactoryHolder` leaf
 *    reporting `childCount=0` -- Reddit's Compose content lives entirely
 *    behind that boundary and the standard AccessibilityNodeInfo tree
 *    cannot see into it at all, not merely "hidden" or unlabeled content.
 * 2. Manually enabling TalkBack and TAPPING a paragraph made it speak,
 *    which looked at first like lazy semantics attaching on touch -- but
 *    this profile tried every equivalent available to an app (a
 *    dispatchGesture() tap at the same coordinates, toggling system touch-
 *    exploration mode on via setServiceInfo() first, and a direct
 *    ACTION_ACCESSIBILITY_FOCUS call on the deepest node) and every one of
 *    them still returned a completely empty tree afterward. Whatever real
 *    touch-exploration does to make Compose populate this happens at an
 *    input-interception layer this profile has no access to reproduce.
 * 3. Collapsed replies and off-screen comments are separately known to be
 *    genuinely absent from the tree (not just hidden) until expanded/
 *    scrolled -- this profile's expand-and-scroll loop (steps 2+3 below)
 *    handles that correctly for apps where the tree is otherwise readable,
 *    but it has never had real content to work with on THIS app to prove
 *    it end to end.
 *
 * When all of that still comes back empty, the last thing this does is
 * call ReadAloudAccessibilityService.captureScreenshot() -- confirmed live
 * to succeed even on this exact screen -- so the capability is proven and
 * ready for whatever reads text out of a bitmap gets built next (an
 * on-device OCR/vision model, or a new host3 endpoint over the same
 * WireGuard tunnel /tts/stream already uses). Deliberately not decided
 * here: which of those is worth the latency/cost is a real design
 * question for the user, not something to guess at 2am (see docs/design.md).
 */
object RedditProfile : AppProfile {
    override val packageName: String = "com.reddit.frontpage"
    override val needsTouchExploration: Boolean get() = true

    private const val TAG = "RedditProfile"
    private const val MAX_EXPAND_CLICKS = 6
    private const val MAX_SCROLLS = 12
    private const val MIN_CHARS_BEFORE_FALLBACKS = 40
    private val MORE_REPLIES = Regex("""\d+\s+more repl""", RegexOption.IGNORE_CASE)
    private val MORE_COMMENTS = Regex("""view more comments""", RegexOption.IGNORE_CASE)

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> {
        val seen = LinkedHashSet<String>()

        var current = AccessibilityTree.collectText(root)
        Log.i(TAG, "initial read: ${current.sumOf { it.length }} chars")

        if (current.sumOf { it.length } < MIN_CHARS_BEFORE_FALLBACKS) {
            val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
            for (fraction in listOf(0.3f, 0.5f, 0.7f)) {
                service.tapAt(bounds.exactCenterX(), bounds.top + bounds.height() * fraction)
            }
            current = service.foregroundRoot()?.second?.let { AccessibilityTree.collectText(it) } ?: current
            Log.i(TAG, "after tap fallback: ${current.sumOf { it.length }} chars")
        }

        if (current.sumOf { it.length } < MIN_CHARS_BEFORE_FALLBACKS) {
            val deepest = AccessibilityTree.findNode(root) { it.childCount == 0 } ?: root
            deepest.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            Thread.sleep(400)
            current = service.foregroundRoot()?.second?.let { AccessibilityTree.collectText(it) } ?: current
            Log.i(TAG, "after direct accessibility-focus fallback: ${current.sumOf { it.length }} chars")
        }

        if (current.sumOf { it.length } < MIN_CHARS_BEFORE_FALLBACKS) {
            // Every tree-based option exhausted -- see class doc. Prove the
            // vision-fallback capture path works so the next session can
            // build straight on it rather than re-discovering it works.
            val screenshot = service.captureScreenshot()
            Log.w(TAG, "tree extraction empty; screenshot fallback captured=${screenshot != null} (${screenshot?.width}x${screenshot?.height}) - no OCR/vision wired up yet, see class doc")
            return emptyList()
        }

        seen.addAll(current)

        // Expand any "more replies"/"view more comments" button currently
        // visible, then scroll, repeating until a pass adds nothing new or
        // either cap is hit. Expansion is re-attempted every iteration
        // (not just once) because scrolling reveals new buttons further
        // down the thread that weren't on screen yet.
        var expandClicks = 0
        var scrolls = 0
        var stagnantPasses = 0
        while (scrolls < MAX_SCROLLS && stagnantPasses < 2) {
            val beforeSize = seen.size

            val expandRoot = service.foregroundRoot()?.second
            if (expandRoot != null && expandClicks < MAX_EXPAND_CLICKS) {
                val moreNode = AccessibilityTree.findNode(expandRoot) { n ->
                    n.isClickable && (MORE_REPLIES.containsMatchIn(AccessibilityTree.textOf(n)) || MORE_COMMENTS.containsMatchIn(AccessibilityTree.textOf(n)))
                }
                if (moreNode != null && service.click(moreNode)) {
                    expandClicks++
                    Thread.sleep(700) // a real network fetch behind this, not a local UI change - see class doc, finding 3
                    service.foregroundRoot()?.second?.let { seen.addAll(AccessibilityTree.collectText(it)) }
                }
            }

            val scrollRoot = service.foregroundRoot()?.second ?: break
            val scrollable = AccessibilityTree.largestScrollable(scrollRoot) ?: scrollRoot
            val scrolled = service.scrollForward(scrollable)
            scrolls++
            if (!scrolled) break
            Thread.sleep(350) // let the lazy list actually compose new rows before re-querying
            service.foregroundRoot()?.second?.let { seen.addAll(AccessibilityTree.collectText(it)) }

            stagnantPasses = if (seen.size == beforeSize) stagnantPasses + 1 else 0
        }

        return seen.toList()
    }
}
