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
 * When all of that still comes back empty, this falls back to
 * ReadAloudAccessibilityService.ocrScreenshot() -- the same
 * play-services-mlkit-text-recognition library TalkBack itself uses for
 * exactly this situation (its own `UNLABELLED_VIEW` caption case,
 * confirmed by reading TalkBack's open-source `OcrController.java` - see
 * docs/design.md). On-device, no host3 round-trip, nothing leaves the
 * phone. This is why the project needed Gradle at all: that library's
 * real transitive dependency graph (Firebase + AndroidX, 15-25+ AARs)
 * couldn't be hand-integrated into the old aapt2/kotlinc/d8 pipeline
 * safely.
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

    // Confirmed live 2026-09-20 that a single ocrScreenshot() call only
    // ever captures whatever fits on the CURRENT screen - on a real
    // comments thread with more than a couple of comments, everything
    // below the fold was silently never read at all, same shape of gap
    // Gmail/WhatsApp/the Reddit feed itself already had scroll-and-
    // accumulate fixes for.
    private const val MAX_OCR_SCROLLS = 8

    // Ad cards interleaved in a real feed/comments screen (confirmed live
    // - "sharkninjauk Ad", "itv.com Ad") read aloud indistinguishably from
    // real content, which is real, noticeable noise for "reads naturally".
    // Known-imperfect, conservative heuristic - only the clearest, most
    // reliably-tagged signals (the platform's own "Ad" badge text ending a
    // line, a bare sponsor domain, or a standard ad CTA button) are
    // dropped; an ad's own body copy is NOT filtered (indistinguishable
    // from real content without per-block bounding boxes MlKitOcr doesn't
    // currently expose - a bigger project, not a quick heuristic).
    private val AD_CTA_TEXT = setOf("shop now", "watch now", "learn more", "install now", "sign up", "download", "get app")
    private val AD_DOMAIN = Regex("""^[\w.-]+\.(com|co\.uk|net|org|io|app)$""", RegexOption.IGNORE_CASE)

    private fun isLikelyAdNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed == "Ad" || trimmed.endsWith(" Ad")) return true
        if (trimmed.lowercase() in AD_CTA_TEXT) return true
        if (AD_DOMAIN.matches(trimmed)) return true
        return false
    }

    /** OCR-fallback equivalent of extractThreadScrolling()/RedditProfile's
     * own feed-scrolling loop: captures the current screen, scrolls
     * forward, captures again, dedupes via LinkedHashSet (same tradeoff
     * accepted everywhere else this pattern is used), stops after two
     * consecutive scrolls add nothing new. `service.scrollForward(root)`
     * works even though the Compose content itself exposes no real
     * scrollable node (see class doc's finding #1, a single opaque
     * childCount=0 leaf) - passing the whole-screen `root` falls through
     * to a bounds-based swipe gesture regardless of node structure, since
     * this only needs to move the SCREEN, not query anything about it. */
    private fun ocrScrollAndAccumulate(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> {
        val seen = LinkedHashSet<String>()
        fun captureCurrent() {
            service.ocrScreenshot().split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() && !isLikelyAdNoise(it) }
                .forEach { seen.add(it) }
        }
        captureCurrent()
        var current = root
        var scrolls = 0
        var stagnantPasses = 0
        while (scrolls < MAX_OCR_SCROLLS && stagnantPasses < 2) {
            val beforeSize = seen.size
            val scrolled = service.scrollForward(current)
            Log.i(TAG, "ocrScrollAndAccumulate: scrollForward[$scrolls] ok=$scrolled")
            if (!scrolled) break
            scrolls++
            Thread.sleep(500)
            current = service.foregroundRoot()?.second ?: break
            captureCurrent()
            stagnantPasses = if (seen.size == beforeSize) stagnantPasses + 1 else 0
        }
        return seen.toList()
    }

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> {
        val seen = LinkedHashSet<String>()

        Log.i(TAG, "extract: root class=${root.className} childCount=${root.childCount}")
        var current = AccessibilityTree.collectText(root)
        Log.i(TAG, "initial read: ${current.sumOf { it.length }} chars")

        // REMOVED 2026-09-20 a "tap fallback" that used to fire three blind
        // dispatchGesture() taps at fixed screen-height fractions, hoping
        // one would land on real content and wake up Compose's semantics.
        // Tested exhaustively (with zero settle time, then with 600ms
        // after each tap - see the accessibility-focus fallback's own doc
        // for the settle-time results) and NEVER ONCE surfaced real Reddit
        // content in any test this session. It DID, once, land squarely on
        // a sponsored post's "Shop Now" button and navigate the user's
        // Reddit app to an external shopping site (sharkninja.co.uk, with
        // a real cookie-consent dialog and "Add to cart" visible) -
        // confirmed live, screenshotted, and closed immediately. A blind
        // tap can activate whatever real, clickable element happens to sit
        // at that screen fraction (an ad's CTA, a video player, anything)
        // - a real, demonstrated safety issue on the user's own daily-
        // driver phone, not just a quality/noise problem, for a technique
        // that had already shown zero benefit across every test. Removed
        // outright rather than trying to make tap coordinates "safer" -
        // there's no coordinate this profile can pick in advance that's
        // guaranteed not to be some other post's real, clickable content.
        // The accessibility-focus fallback below is side-effect-free (it
        // requests a11y focus, not a real click) and stays as the one
        // remaining tree-based attempt before OCR.

        if (current.sumOf { it.length } < MIN_CHARS_BEFORE_FALLBACKS) {
            val deepest = AccessibilityTree.findNode(root) { it.childCount == 0 } ?: root
            deepest.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            Thread.sleep(400)
            current = service.foregroundRoot()?.second?.let { AccessibilityTree.collectText(it) } ?: current
            Log.i(TAG, "after direct accessibility-focus fallback: ${current.sumOf { it.length }} chars")
        }

        if (current.sumOf { it.length } < MIN_CHARS_BEFORE_FALLBACKS) {
            // Every tree-based option exhausted -- see class doc. The
            // same fallback TalkBack itself uses for this exact
            // situation (UNLABELLED_VIEW -> OCR a screenshot crop),
            // confirmed via its own open-source code.
            //
            // Turn touch exploration back off first - confirmed live it
            // silently breaks scrollForward()'s swipe-based scrolling (see
            // disableTouchExplorationNow()'s own doc), and OCR doesn't
            // read the accessibility tree at all so there's nothing left
            // that still needs it active.
            service.disableTouchExplorationNow()
            val ocrLines = ocrScrollAndAccumulate(service, root)
            Log.i(TAG, "tree extraction empty; OCR scroll-accumulate got ${ocrLines.sumOf { it.length }} chars, ${ocrLines.size} lines")
            return ocrLines
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
