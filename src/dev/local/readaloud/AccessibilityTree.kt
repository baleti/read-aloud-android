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
        walk(root, out)
        return dedupeAdjacent(out)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (!node.isVisibleToUser) return
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) {
            out.add(desc)
            return // atomic announcement -- see class doc, don't also read the children
        }
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) out.add(text)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out) }
        }
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
     * every profile re-implementing its own tree walk for it. */
    fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun rec(node: AccessibilityNodeInfo) {
            if (predicate(node)) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { rec(it) }
        }
        rec(root)
        return out
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
