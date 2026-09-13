package dev.local.readaloud

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shared tree-walking primitives every AppProfile builds on -- the
 * "universal base layer" from docs/design.md. The one nontrivial call is
 * collectText()'s rule for content-desc vs children: confirmed live
 * 2026-09-12 against Gmail's own inbox dump that a labeled container
 * (content-desc = "Unread, OVHcloud, Invoice available in the OVH Control
 * Panel...", combining fields its own child TextViews only have
 * separately and incompletely) should be read as ONE atomic announcement
 * and NOT also have its children visited afterward -- otherwise the
 * sender/subject/snippet get read a second time right after the fuller
 * combined string. This is the same rule TalkBack itself uses (a node
 * with an explicit contentDescription is one spoken unit; its subtree
 * isn't separately announced).
 */
object AccessibilityTree {

    /** Depth-first, in on-screen order (children are already returned by
     * getChild() in that order for every app tested so far -- Gmail and
     * Reddit both). Skips anything not currently visible (off-screen
     * virtualized-list items, content behind another window) since it
     * isn't actually there to read. */
    fun collectText(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        walk(root, emptyMap(), out)
        return dedupeAdjacent(out)
    }

    /** Same walk, but a leaf's own text gets prefixed "Label: " when its
     * resource-id (last path segment, e.g. "sender_name" for
     * "com.google.android.gm:id/sender_name") is a key in `labelsByResId`
     * - asked for explicitly for Gmail's open-email screen, where
     * "Subject"/"From" aren't otherwise announced as such (confirmed
     * live: `subject_and_folder_view` and `sender_name` are genuinely
     * separate leaf nodes there, unlike the inbox list's rows, which
     * already read fine as one atomic content-desc and are untouched by
     * this - a label with no match just falls through to plain text,
     * same as collectText()). One shared walk() for both entry points
     * deliberately - confirmed live 2026-09-13 that having this as a
     * separate near-duplicate function meant a fix to one (the chrome
     * filter below) silently didn't apply to the other, since
     * GmailProfile calls this one, not collectText(). */
    fun collectTextWithLabels(root: AccessibilityNodeInfo, labelsByResId: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        walk(root, labelsByResId, out)
        return dedupeAdjacent(out)
    }

    private fun walk(node: AccessibilityNodeInfo, labels: Map<String, String>, out: MutableList<String>) {
        if (!node.isVisibleToUser) return
        if (isChrome(node)) return
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) {
            out.add(desc)
            return // atomic announcement -- see class doc, don't also read the children
        }
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && !isBareListMarker(text)) {
            val resId = node.viewIdResourceName?.substringAfterLast('/')
            val label = resId?.let { labels[it] }
            out.add(if (label != null) "$label: $text" else text)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, labels, out) }
        }
    }

    // Class names common to icon-only action buttons (a toolbar's Reply/
    // Archive/Star/overflow icons).
    private val CONTROL_CLASSES = setOf(
        "android.widget.Button", "android.widget.ImageButton", "android.widget.ImageView",
        "android.widget.FrameLayout", "android.widget.ImageSwitcher", "android.widget.CheckBox",
        "android.widget.Switch", "android.widget.ToggleButton", "android.widget.LinearLayout",
    )

    /** Reported live 2026-09-13: reading an open Gmail email spoke toolbar
     * button labels ("emoji reaction", "forward", "share") right alongside
     * the actual message. Confirmed against a real dump of that exact
     * screen (docs/design.md) that these buttons come in two shapes,
     * needing two independent rules - either alone is enough:
     *
     * 1. A resource-id containing "button" (`reply_button`,
     *    `reply_all_button_text`, `forward_button_text`, ...) -
     *    deliberately NOT gated on isClickable: confirmed live that
     *    "Reply all"/"Forward" are TextView labels with clickable=false
     *    sitting inside a separately-clickable container, so requiring
     *    the label node itself to be clickable would miss them.
     * 2. A clickable node with no visible .text, whose only label is a
     *    short (<=4 word) content-desc, on one of the common icon-control
     *    classes above (catches "Navigate up", "More options", "Add
     *    star", "Add emoji reaction", "Archive", "Delete" - icon-only
     *    buttons with no separate text at all).
     *
     * A node matching either is skipped entirely, children included - a
     * button's children are never meaningful content of their own.
     * Known-imperfect heuristic, not a certainty - refine as false
     * positives/negatives turn up, same as GmailProfile's own
     * STOP_MARKERS. */
    // Resource-id naming conventions that mean "this is a UI control, not
    // content" regardless of word count - added "badge" 2026-09-13 after
    // "Show contact information for Rejane Salgado" (Gmail's
    // contact_badge icon) leaked through: its content-desc template
    // appends the sender's name, which defeats a raw word-count cutoff
    // once the name itself is multiple words.
    private val CHROME_ID_SUBSTRINGS = listOf("button", "badge")

    // Confirmed live 2026-09-13 against Feeder (an RSS reader, tested
    // with zero app-specific profile - GenericProfile alone) reading a
    // changelog article: each bulleted line is TWO leaf nodes, a bare
    // marker glyph on its own plus the actual item text right after -
    // reading the marker as its own line ("bullet point, bullet point...")
    // ahead of every single item is real noise on any list-heavy content,
    // not just this one app.
    private val BARE_LIST_MARKERS = setOf("•", "◦", "‣", "·", "-", "*")

    private fun isBareListMarker(text: String): Boolean = text in BARE_LIST_MARKERS

    private fun isChrome(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName?.substringAfterLast('/')?.lowercase()
        if (resId != null && CHROME_ID_SUBSTRINGS.any { resId.contains(it) }) return true
        if (!node.isClickable) return false
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (text.isNullOrBlank() && !desc.isNullOrBlank() && node.className in CONTROL_CLASSES) {
            val wordCount = desc.trim().split(Regex("\\s+")).size
            if (wordCount <= 4) return true
        }
        return false
    }

    /** Some apps (Gmail's WebView email body among them) repeat the exact
     * same string on two adjacent nodes -- a link's visible text plus a
     * separate content-desc node carrying the identical string right next
     * to it (confirmed live in the OVHcloud email dump: "OVHcloud
     * Twitter" appeared three times in a row across a View/Image/another
     * View for the one icon). Collapsing only ADJACENT duplicates (not a
     * global dedupe) is deliberate -- the same word can legitimately
     * appear twice far apart in real content (e.g. a comment quoting the
     * post title). */
    private fun dedupeAdjacent(items: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (s in items) {
            if (out.isNotEmpty() && out.last() == s) continue
            out.add(s)
        }
        return out
    }

    /** First node (depth-first) whose own text or content-desc matches
     * `predicate` -- used to find "N more replies"-shaped buttons whose
     * exact resource-id/wording is app-specific (see RedditProfile) without
     * every profile re-implementing its own tree walk for it.
     *
     * Skips a subtree that's either not `isVisibleToUser` or has a real
     * (non-empty) rect entirely to one side of `root`'s own bounds - see
     * isOffScreenPage()'s own doc for what each half of that catches and
     * why BOTH are needed. Fixes a confirmed-live bug 2026-09-13: Gmail's
     * conversation screen is a ViewPager that keeps the adjacent
     * (previous/next) conversation's page fully instantiated for swipe
     * performance, so a plain "walk the whole tree" search - unlike
     * walk() (used by collectText()/collectTextWithLabels()), which
     * already filtered on visibility - could match nodes belonging to
     * whatever thread the user had open BEFORE this one. Confirmed via
     * added logging: opening a genuinely single-message email right
     * after a real 6-message thread consistently (not intermittently -
     * ruling out a timing race, which an earlier attempt at this fix
     * wrongly assumed) counted 5 message headers instead of 1, every one
     * of the 4 extra reporting `isVisibleToUser=false` with a degenerate
     * (inverted, e.g. left=0/right=-21) rect - which is also, by
     * Android's own `Rect.isEmpty()` definition, "empty", so a
     * bounds-only check missed them entirely on its own; visibility
     * alone was the fix for THIS case. The bounds half stays anyway as
     * defense-in-depth against a differently-behaved app whose off-page
     * siblings report visible=true with real, shifted bounds instead -
     * not observed here, but a plain OR of both signals costs nothing
     * extra when only one of them is ever actually triggered. */
    fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        return findNode(root, rootBounds, predicate)
    }

    private fun findNode(node: AccessibilityNodeInfo, rootBounds: Rect, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isOffScreenPage(node, rootBounds)) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, rootBounds, predicate)?.let { return it }
        }
        return null
    }

    fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun rec(node: AccessibilityNodeInfo) {
            if (isOffScreenPage(node, rootBounds)) return
            if (predicate(node)) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { rec(it) }
        }
        rec(root)
        return out
    }

    /** True for a node that belongs to some OTHER on-screen page rather
     * than the one `rootBounds` describes - see findNode()/
     * findAllNodes()'s own doc for the real bug this catches and why it
     * takes two signals, not one: not `isVisibleToUser` (the actual
     * culprit found live), OR a real, non-empty rect entirely to one
     * side of `rootBounds` (a hypothetical second shape of the same
     * problem, kept as defense-in-depth). A same-page item merely
     * awaiting a scroll reports an EMPTY rect (not a shifted real one)
     * and IS `isVisibleToUser` in every case seen live, so it passes
     * through untouched either way. */
    private fun isOffScreenPage(node: AccessibilityNodeInfo, rootBounds: Rect): Boolean {
        if (!node.isVisibleToUser) return true
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.isEmpty) return false
        return b.right <= rootBounds.left || b.left >= rootBounds.right
    }

    /** The largest (by screen area) scrollable node in the tree -- a
     * reasonable generic guess for "the main content list" without
     * knowing any app-specific resource-id, used by GenericProfile and as
     * RedditProfile's fallback when it can't find a more specific
     * container. */
    fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = findAllNodes(root) { it.isScrollable }
        return candidates.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width().toLong() * r.height().toLong()
        }
    }

    fun textOf(node: AccessibilityNodeInfo): String =
        (node.contentDescription?.toString() ?: node.text?.toString() ?: "").trim()
}
