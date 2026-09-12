package dev.local.readaloud

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Gmail needs no special extraction logic for its inbox list -- confirmed
 * live 2026-09-12, each row's content-desc is already the complete
 * "Unread, Sender, Subject, Snippet" string GenericProfile's rule picks
 * up whole. The one gap GenericProfile has is an OPEN email's body: it's
 * a WebView bridged into the accessibility tree as a flat sequence of
 * per-line text nodes with no structure at all, and the sender's own
 * marketing footer (social share icons, "Unsubscribe"/legal text) lands
 * in that same flat sequence right after the real signature, read with
 * no distinction from the actual message (confirmed live against a real
 * OVHcloud support email: "...Regards, Victor R., The OVHcloud team" was
 * immediately followed by "Share your experience... Twitter... YouTube...
 * LinkedIn... Legal notices... OVHcloud is hiring" with nothing marking
 * the boundary).
 *
 * STOP_MARKERS is a known-imperfect heuristic, not a real boundary
 * detector -- it only catches footers built from these specific common
 * patterns (social platform names as their own node, "Legal notices",
 * "Unsubscribe" appearing a SECOND time after already appearing near the
 * header). A sender whose footer doesn't match any of these still reads
 * through in full. This is exactly the kind of thing a profile file is
 * for refining over time (see docs/design.md) -- add a marker here as a
 * new false negative turns up, rather than trying to solve "detect any
 * email footer" in one pass.
 */
object GmailProfile : AppProfile {
    override val packageName: String = "com.google.android.gm"

    private val STOP_MARKERS = listOf(
        "legal notices", "unsubscribe", "twitter", "facebook", "instagram",
        "linkedin", "youtube", "help centre", "guides & faq", "community forum",
        "system status information", "manage your email preferences", "privacy policy",
    )

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> {
        val all = AccessibilityTree.collectText(root)
        // "Unsubscribe" legitimately appears once near the top (its own
        // header button, confirmed live) -- only a SECOND occurrence, or
        // any of the platform-name/legal markers at all, counts as the
        // footer starting.
        var seenUnsubscribeOnce = false
        val out = mutableListOf<String>()
        for (line in all) {
            val lower = line.lowercase()
            val isFooterMarker = STOP_MARKERS.any { marker ->
                if (marker == "unsubscribe") {
                    val hit = lower.contains(marker) && seenUnsubscribeOnce
                    if (lower.contains(marker)) seenUnsubscribeOnce = true
                    hit
                } else {
                    lower == marker || lower.startsWith("$marker ") || lower.contains(" $marker ")
                }
            }
            if (isFooterMarker) break
            out.add(line)
        }
        return out
    }
}
