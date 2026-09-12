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

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> =
        AccessibilityTree.collectText(root)
}
