package dev.local.readaloud

import android.view.accessibility.AccessibilityNodeInfo

/**
 * UNTESTED -- Outlook is not installed on the phone this project was
 * built and tested against (only Gmail and K-9 Mail are), so this is a
 * best-effort port of GmailProfile's approach rather than something
 * verified against a real dump the way Gmail's and Reddit's profiles
 * were. Reuses GenericProfile's tree walk plus the same kind of footer-
 * marker cutoff, on the reasoning that Outlook's message list is likely
 * also a native, reasonably-labeled view (corporate mail clients tend to
 * be, same as Gmail) and its open-message body is likely also a WebView
 * with the same email-footer-boilerplate shape as any other HTML email.
 *
 * First real thing to check once this is actually run against Outlook:
 * dump its accessibility tree the same way the desktop side of this
 * project dumped Reddit and Gmail (`adb shell uiautomator dump`) before
 * assuming any of this holds -- corporate mail apps built on Office's own
 * cross-platform UI toolkit are exactly the kind of thing that could turn
 * out closer to Reddit's opaque-canvas case than Gmail's clean one.
 */
object OutlookProfile : AppProfile {
    override val packageName: String = "com.microsoft.office.outlook"

    private val STOP_MARKERS = listOf(
        "legal notices", "unsubscribe", "twitter", "facebook", "instagram",
        "linkedin", "youtube", "privacy statement", "privacy policy",
        "manage your subscription", "view this email in your browser",
    )

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> {
        val all = AccessibilityTree.collectText(root)
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
