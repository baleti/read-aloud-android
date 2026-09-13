package dev.local.readaloud

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
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
 *
 * Modes (added 2026-09-13): open a specific email and Read Aloud offers
 * "This email" / continue "onwards" or "backwards" through the inbox;
 * invoke it from the inbox LIST instead and it jumps straight into
 * reading from the top, no menu at all (there's nothing to choose
 * between there). The onwards/backwards/top sequence actually opens each
 * email in turn (not just reading the list's own summary rows) so the
 * screen shows which one is currently being read, same as asked for
 * explicitly. Gmail exposes no "next conversation" control in its
 * accessibility tree at all (checked two real dumps) - navigation is
 * GLOBAL_ACTION_BACK to the list, then a fresh tap on the row at the
 * current index, tracked as a plain integer the loop increments/
 * decrements itself. That sidesteps a real ambiguity confirmed live in
 * this exact inbox: two separate threads can share both sender AND
 * subject prefix ("Rejane Salgado" / "Re: St. Regis - 50% SD
 * Submission" appeared on two different rows, differing only in
 * snippet) - matching by content on every step would risk picking the
 * wrong one. Content-matching is still needed for exactly one step:
 * turning "onwards from the email that's currently open" into a
 * starting index, since there's no index to already know at that point.
 */
object GmailProfile : AppProfile {
    override val packageName: String = "com.google.android.gm"
    private const val TAG = "GmailProfile"

    // Both are defense-in-depth, independent of the generation-supersede
    // check - added 2026-09-13 after a real incident (see docs/design.md
    // and TtsSpeaker's own doc) where the actual bug was elsewhere
    // (TtsSpeaker never closing a superseded websocket) but a hard cap on
    // this loop specifically costs nothing and bounds the damage if
    // anything like it happens again, from this loop or a future one.
    private const val MAX_SEQUENCE_EMAILS = 15
    private const val MAX_SEQUENCE_WALL_CLOCK_MS = 20 * 60 * 1000

    // Confirmed live 2026-09-13: `subject_and_folder_view` bakes the
    // folder/category chips directly onto the end of the subject's own
    // .text, no separator - "...vps-af1b0c30.vps.ovh.net Inbox primary"
    // for an email in both the Inbox location and Primary category tab.
    // Not a separate node this project's usual isChrome() filtering can
    // catch (see AccessibilityTree.kt) - it's baked into the same string
    // as real subject content, so this strips a trailing run of Gmail's
    // own known category/location words instead. Known-imperfect: a
    // subject that genuinely ends in one of these exact words (rare)
    // would get over-trimmed - same class of tradeoff as STOP_MARKERS
    // below, refine as real cases turn up.
    private val KNOWN_LABELS = setOf(
        "inbox", "primary", "social", "promotions", "updates", "forums",
        "starred", "important", "sent", "drafts", "spam", "trash", "personal",
    )

    private fun stripTrailingLabels(subjectLine: String): String {
        val words = subjectLine.trim().split(Regex("\\s+")).toMutableList()
        while (words.isNotEmpty() && words.last().lowercase().trim(',', '.') in KNOWN_LABELS) {
            words.removeAt(words.size - 1)
        }
        return words.joinToString(" ")
    }

    private val STOP_MARKERS = listOf(
        "legal notices", "unsubscribe", "twitter", "facebook", "instagram",
        "linkedin", "youtube", "help centre", "guides & faq", "community forum",
        "system status information", "manage your email preferences", "privacy policy",
    )

    // Only these two open-email fields need a spoken label at all -- the
    // inbox list's rows already read fine as one atomic content-desc
    // ("Unread, Sender, Subject, Snippet") and are untouched by this,
    // since that content-desc branch returns before resource-id lookup
    // ever runs (see AccessibilityTree.collectTextWithLabels's own doc).
    private val FIELD_LABELS = mapOf(
        "subject_and_folder_view" to "Subject",
        "sender_name" to "From",
    )

    private fun isOpenEmailScreen(root: AccessibilityNodeInfo): Boolean =
        AccessibilityTree.findNode(root) { it.viewIdResourceName?.endsWith("subject_and_folder_view") == true } != null

    // Each inbox row is an otherwise-unlabeled clickable FrameLayout whose
    // own .text is "Sender, Subject, Snippet" all run together (confirmed
    // live against a real 8-email inbox) - sorted by on-screen vertical
    // position since that's the one thing guaranteed to match reading
    // order regardless of document-tree quirks.
    private fun listRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        AccessibilityTree.findAllNodes(root) {
            it.className == "android.widget.FrameLayout" && it.isClickable && (it.text?.length ?: 0) > 20
        }.sortedBy {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top
        }

    override fun modes(root: AccessibilityNodeInfo): List<AppProfile.Mode> {
        return if (isOpenEmailScreen(root)) {
            listOf(
                AppProfile.Mode("this_email", "This email"),
                AppProfile.Mode("onwards", "Continue onwards through inbox"),
                AppProfile.Mode("backwards", "Continue backwards through inbox"),
            )
        } else {
            listOf(AppProfile.Mode("inbox_top", "Read inbox from the top"))
        }
    }

    override fun runMode(service: ReadAloudAccessibilityService, mode: String, label: String): Boolean {
        // Called from ReadAloudAccessibilityService.readWithMode(), which
        // is already running on its own background thread - no need for
        // another one here.
        when (mode) {
            "inbox_top" -> runInboxSequence(service, startIndex = 0, direction = 1, label)
            "onwards", "backwards" -> {
                val direction = if (mode == "onwards") 1 else -1
                // ModeChooserActivity may not have finished closing and
                // handing focus back to Gmail yet - same race
                // ReadAloudAccessibilityService's own doc already covers
                // for the corner-swipe path (findForegroundWithRetry's
                // whole reason for existing), missed here on the first
                // pass: reported live 2026-09-13 as "No email is open"
                // firing every time onwards/backwards was picked from the
                // chooser, immediately after it closed.
                val emailRoot = service.findForegroundWithRetry()?.second
                if (emailRoot == null || !isOpenEmailScreen(emailRoot)) {
                    service.toast("No email is open")
                    return true
                }
                val currentIndex = findCurrentRowIndex(service, emailRoot)
                if (currentIndex == null) {
                    service.toast("Couldn't locate this email in the inbox list")
                    return true
                }
                runInboxSequence(service, startIndex = currentIndex + direction, direction = direction, label)
            }
            else -> return false
        }
        return true
    }

    /** Reads the CURRENTLY open email's subject/sender, backs out to the
     * list, and finds the one matching row - the one place this feature
     * still needs content-matching rather than a plain index (see class
     * doc for the real duplicate-subject case that makes this imperfect).
     * Short prefixes (subject: 20 chars, sender: 15) rather than exact
     * matches, since the list truncates snippets/subjects differently
     * than the open view - confirmed live the list's own text can cut a
     * subject off earlier than the full one shown when open. */
    private fun findCurrentRowIndex(service: ReadAloudAccessibilityService, emailRoot: AccessibilityNodeInfo): Int? {
        val lines = extract(service, emailRoot, "this_email")
        val subject = lines.firstOrNull { it.startsWith("Subject: ") }?.removePrefix("Subject: ")?.trim()
        val sender = lines.firstOrNull { it.startsWith("From: ") }?.removePrefix("From: ")?.trim()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        val listRoot = service.findForegroundWithRetry()?.second ?: return null
        val rows = listRows(listRoot)
        val idx = rows.indexOfFirst { row ->
            val t = (row.text?.toString() ?: "").lowercase()
            val subjectOk = subject.isNullOrBlank() || t.contains(subject.take(20).lowercase())
            val senderOk = sender.isNullOrBlank() || t.contains(sender.take(15).lowercase())
            subjectOk && senderOk
        }
        return idx.takeIf { it >= 0 }
    }

    /** The actual loop: make sure we're on the list, tap the row at
     * `index`, wait for it to open, read it (blocking until playback
     * finishes - must not open the next email while this one is still
     * being spoken), step `index` by `direction`, repeat. Stops on
     * reaching either end of the list, MAX_SEQUENCE_EMAILS, or a fresh
     * Read Aloud invocation superseding this one (checked via
     * isSuperseded() before and after every blocking step - the whole
     * reason that generation counter exists). */
    private fun runInboxSequence(service: ReadAloudAccessibilityService, startIndex: Int, direction: Int, label: String) {
        val generation = service.currentGeneration()
        val startedAtMs = System.currentTimeMillis()
        var index = startIndex
        var steps = 0
        while (steps < MAX_SEQUENCE_EMAILS) {
            if (service.isSuperseded(generation)) return
            if (System.currentTimeMillis() - startedAtMs > MAX_SEQUENCE_WALL_CLOCK_MS) {
                service.toast("Stopped - inbox read ran too long")
                return
            }
            steps++

            var root = service.findForegroundWithRetry()?.second ?: return
            if (isOpenEmailScreen(root)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(500)
                root = service.findForegroundWithRetry()?.second ?: return
            }
            if (isOpenEmailScreen(root)) {
                service.toast("Couldn't get back to the inbox list")
                return
            }

            val rows = listRows(root)
            Log.i(TAG, "runInboxSequence: rows=${rows.size} index=$index step=$steps")
            if (index !in rows.indices) {
                service.toast(if (index < 0) "Reached the top of the inbox" else "Reached the end of the inbox")
                return
            }
            if (!service.click(rows[index])) {
                service.toast("Couldn't open the next email")
                return
            }

            var opened: AccessibilityNodeInfo? = null
            repeat(10) {
                if (opened != null) return@repeat
                Thread.sleep(300)
                val r = service.foregroundRoot()?.second ?: return@repeat
                if (isOpenEmailScreen(r)) opened = r
            }
            val emailRoot = opened ?: run { service.toast("The next email didn't open in time"); return }
            if (service.isSuperseded(generation)) return

            val lines = try { extract(service, emailRoot, "this_email") } catch (e: Exception) { emptyList() }
            val text = lines.joinToString("\n").trim()
            Log.i(TAG, "runInboxSequence: email $steps, ${text.length} chars")
            if (text.isNotBlank()) {
                service.toast("Reading email ${steps} (${label})")
                TtsSpeaker.speak(service, label, text, waitUntilPlaybackDone = true)
            }

            index += direction
        }
        service.toast("Stopped after $MAX_SEQUENCE_EMAILS emails")
    }

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> {
        val all = AccessibilityTree.collectTextWithLabels(root, FIELD_LABELS)
        // "Unsubscribe" legitimately appears once near the top (its own
        // header button, confirmed live) -- only a SECOND occurrence, or
        // any of the platform-name/legal markers at all, counts as the
        // footer starting.
        var seenUnsubscribeOnce = false
        val out = mutableListOf<String>()
        for (rawLine in all) {
            val line = if (rawLine.startsWith("Subject: ")) {
                "Subject: " + stripTrailingLabels(rawLine.removePrefix("Subject: "))
            } else {
                rawLine
            }
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
