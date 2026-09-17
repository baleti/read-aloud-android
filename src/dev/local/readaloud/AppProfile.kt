package dev.local.readaloud

import android.view.accessibility.AccessibilityNodeInfo

/**
 * One file per app, keyed by package name -- the Firejail-profile /
 * Full-Text-RSS-site-config shape this project is deliberately copying
 * (see docs/design.md): a generic engine that does something reasonable
 * for any app, plus a small registry of per-app overrides for the ones
 * that need real work. Add a new object implementing this interface and
 * register it in AppProfileRegistry.byPackage below -- nothing else
 * changes.
 */
interface AppProfile {
    companion object {
        const val DEFAULT_MODE = "default"
    }

    data class Mode(val id: String, val label: String)

    /** Package this profile handles -- must match a key it's registered
     * under, just kept alongside the implementation for readability. */
    val packageName: String

    /** True for a profile that needs Compose's lazily-attached semantics to
     * actually populate -- confirmed live 2026-09-12 against Reddit that a
     * plain dispatchGesture() tap does NOT trigger this (a passive read
     * stayed empty even after synthesized taps), but real touch-exploration
     * mode does (manually enabling TalkBack and tapping a paragraph made it
     * speak). ReadAloudAccessibilityService toggles system touch-exploration
     * mode on for the duration of this profile's extract() call ONLY when
     * this is true -- see its own doc for why that's an acceptable,
     * narrowly-scoped cost rather than the permanent hijack TalkBack causes. */
    val needsTouchExploration: Boolean get() = false

    /** The choices to offer for the CURRENT screen state (asked for
     * explicitly 2026-09-13: Gmail should offer "this email" / "onwards"
     * / "backwards" when an email is open, but jump straight into reading
     * the whole inbox from the top with no menu at all when invoked from
     * the list, since there's nothing to choose between there). A single-
     * entry result means ReadAloudAccessibilityService skips the chooser
     * activity entirely and just runs that one mode - the default for
     * every profile that doesn't override this, preserving today's
     * one-tap behavior everywhere except Gmail. */
    fun modes(root: AccessibilityNodeInfo): List<Mode> = listOf(Mode(DEFAULT_MODE, "Read"))

    /** Returns the text to read, in the order it should be spoken.
     * `service` is provided so a profile can drive the screen (click,
     * scroll) via its gesture helpers when the content it wants isn't
     * sitting in the tree yet -- GenericProfile never needs it,
     * RedditProfile always does. `root` is a fresh root each call; a
     * profile that scrolls/clicks must re-fetch its own working root via
     * `service.foregroundRoot()` afterward rather than continuing to walk
     * the now-stale node it was first given. */
    fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String = DEFAULT_MODE): List<String>

    /** For a mode that needs to run a whole scripted sequence itself
     * (Gmail's "read the inbox from the top": open an email, read it,
     * navigate back, open the next, repeat) rather than a single
     * extract-then-speak - handles everything including calling
     * TtsSpeaker.speak() as many times as it needs to. Returns true if it
     * handled `mode` completely; false falls back to the normal single
     * extract() + one TtsSpeaker.speak() call. Default false, since most
     * modes (including every non-Gmail profile's only mode) are the
     * simple case. */
    fun runMode(service: ReadAloudAccessibilityService, mode: String, label: String): Boolean = false
}

object AppProfileRegistry {
    private val profiles: Map<String, AppProfile> = listOf(
        GmailProfile,
        RedditProfile,
        OutlookProfile,
        WhatsAppProfile,
    ).associateBy { it.packageName }

    /** Never returns null -- GenericProfile is the fallback for every
     * package without a dedicated entry above, which is the whole point
     * of the two-layer design: something reasonable everywhere, better
     * where it's been worth the work. */
    fun forPackage(packageName: String): AppProfile = profiles[packageName] ?: GenericProfile
}
