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
    //
    // The identifying check used to be `(it.text?.length ?: 0) > 20` - a
    // guess at "long enough to be a real row, not a promo card/FAB button".
    // Root-caused live 2026-09-14 as the actual explanation for a real
    // report ("some emails were being skipped altogether, not read at
    // all"): a genuine inbox row with a short sender+subject+snippet
    // combination (a terse automated notification, or an unread thread
    // with no snippet text at all) can land under 20 characters, which
    // silently drops it from this list with no error - it's not
    // "shorter", it's ABSENT, which shifts every later index down by one
    // and permanently removes that email from onwards/backwards/top
    // reading sequences. Replaced with a structural check instead: every
    // real row (confirmed against a live 4-row inbox dump, exactly the 4
    // real emails and none of the 2 other clickable FrameLayouts - the
    // Meet FAB and the account-switcher disc) wraps a
    // `viewified_conversation_item_view` descendant, regardless of how
    // short its own text is. Falls back to the old length heuristic only
    // if that id isn't found at all (a different Gmail build/layout that
    // doesn't use it) rather than assuming every future Gmail version
    // keeps this exact id forever.
    private fun listRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val structural = AccessibilityTree.findAllNodes(root) {
            it.className == "android.widget.FrameLayout" && it.isClickable &&
                AccessibilityTree.findNode(it) { c -> c.viewIdResourceName?.endsWith("viewified_conversation_item_view") == true } != null
        }
        val rows = structural.ifEmpty {
            AccessibilityTree.findAllNodes(root) {
                it.className == "android.widget.FrameLayout" && it.isClickable && (it.text?.length ?: 0) > 20
            }
        }
        return rows.sortedBy {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top
        }
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
        if (mode.startsWith("read_selected:")) {
            val indices = mode.removePrefix("read_selected:").split(",").mapNotNull { it.toIntOrNull() }
            readSelectedMessages(service, indices, label)
            return true
        }
        when (mode) {
            "inbox_top" -> runInboxSequence(service, startIndex = 0, direction = 1, label)
            // Gmail can group several messages into one conversation entry
            // ("Michael, 6 messages...") - asked for explicitly 2026-09-13
            // after spotting this exact thread does it. Only pops the
            // super_collapsed_block (cheap - see expandSuperCollapsedBlocksOnly's
            // own doc) to get a true message count before deciding: a
            // single-message thread falls through to the unchanged generic
            // path below (return false), a real multi-message one gets a
            // second-level chooser instead of reading immediately.
            "this_email" -> {
                val root = service.findForegroundWithRetry(packageName)?.second ?: return false
                if (countMessages(root) <= 1) return false
                service.launchModeChooser(
                    packageName,
                    listOf(
                        AppProfile.Mode("read_all", "Read all"),
                        AppProfile.Mode("read_selected", "Read selected"),
                    ),
                )
            }
            "read_all" -> {
                val root = service.findForegroundWithRetry(packageName)?.second
                if (root == null || !isOpenEmailScreen(root)) { service.toast("No email is open"); return true }
                val fullyExpanded = expandAllMessages(service, root)
                val lines = try { extractThreadScrolling(service, fullyExpanded) } catch (e: Exception) { emptyList() }
                val text = lines.joinToString("\n").trim()
                Log.i(TAG, "read_all: ${text.length} chars, ${lines.count { it.startsWith("From: ") }} From: lines")
                if (text.isBlank()) { service.toast("Nothing readable found on screen"); return true }
                service.toast("Reading $label…")
                TtsSpeaker.speak(service, label, text)
            }
            "read_selected" -> {
                val root = service.findForegroundWithRetry(packageName)?.second
                if (root == null || !isOpenEmailScreen(root)) { service.toast("No email is open"); return true }
                val fullyExpanded = expandAllMessages(service, root)
                val lines = try { extractThreadScrolling(service, fullyExpanded) } catch (e: Exception) { emptyList() }
                val fromIndices = lines.withIndex().filter { it.value.startsWith("From: ") }.map { it.index }
                if (fromIndices.isEmpty()) { service.toast("No messages found"); return true }
                // Label each message "Sender - Date" straight from extract()'s
                // own output (the line right after "From: ", unlabeled but
                // reliably the date - see extract()'s own doc) rather than
                // walking sender_name/upper_date nodes directly - see
                // countMessages' own doc for why per-message id/structure
                // lookups turned out not to be trustworthy across sessions.
                val labels = fromIndices.mapIndexed { i, lineIdx ->
                    val sender = lines[lineIdx].removePrefix("From: ")
                    val date = lines.getOrNull(lineIdx + 1)?.takeIf { !it.startsWith("to ") }
                    listOfNotNull(sender, date).joinToString(" - ").ifBlank { "Message ${i + 1}" }
                }
                service.launchMessagePicker(packageName, labels)
            }
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
                val emailRoot = service.findForegroundWithRetry(packageName)?.second
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
     * Short prefix (20 chars) rather than an exact match, since the list
     * truncates subjects differently than the open view - confirmed live
     * the list's own text can cut a subject off earlier than the full one
     * shown when open.
     *
     * Sender is used ONLY to disambiguate multiple subject matches, never
     * as a required condition - confirmed live 2026-09-13 this breaks
     * real, unambiguous matches otherwise: a multi-message thread's list
     * row shows a single participant name Gmail itself picks (here
     * "Marcos" for a thread whose first message's "From:" - what
     * `extract(..., "this_email")` returns - was "Michael Gifford"),
     * which is neither the first nor necessarily the latest message's
     * sender. Requiring it as a hard AND condition alongside subject
     * turned a real, correct, unique subject match into "Couldn't locate
     * this email in the inbox list" every time. */
    private fun findCurrentRowIndex(service: ReadAloudAccessibilityService, emailRoot: AccessibilityNodeInfo): Int? {
        val lines = extract(service, emailRoot, "this_email")
        val subject = lines.firstOrNull { it.startsWith("Subject: ") }?.removePrefix("Subject: ")?.trim()
        val sender = lines.firstOrNull { it.startsWith("From: ") }?.removePrefix("From: ")?.trim()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        val listRoot = service.findForegroundWithRetry(packageName)?.second ?: return null
        val rows = listRows(listRoot)
        if (subject.isNullOrBlank()) return null
        val subjectMatches = rows.withIndex().filter { (_, row) ->
            (row.text?.toString() ?: "").lowercase().contains(subject.take(20).lowercase())
        }
        val chosen = when {
            subjectMatches.isEmpty() -> return null
            subjectMatches.size == 1 -> subjectMatches.first()
            else -> subjectMatches.firstOrNull { (_, row) ->
                sender != null && (row.text?.toString() ?: "").lowercase().contains(sender.take(15).lowercase())
            } ?: subjectMatches.first()
        }
        return chosen.index
    }

    private const val MAX_INBOX_SCROLLS = 15

    /** Finds the row to open next. The very first step (`lastRowText ==
     * null`) uses `pendingIndex` (a plain position within whatever's
     * currently rendered) exactly like before - safe because that row is
     * guaranteed already on screen at that point (either it's literally
     * row 0 of a freshly-opened inbox, or - for onwards/backwards - the
     * just-closed email's own row, which findCurrentRowIndex() just
     * located in the very listRows() snapshot taken right after backing
     * out of it).
     *
     * Every step after that anchors on the PREVIOUSLY opened row's own
     * text instead of a numeric position - root-caused live 2026-09-14 as
     * the real explanation for a report of emails being "skipped
     * altogether, not read at all": listRows() only ever sees whatever
     * Gmail's RecyclerView currently has rendered (a handful of rows), and
     * this loop never scrolled it at all, so a plain `index += direction`
     * ran off the end of the ON-SCREEN rows - not the real inbox - the
     * moment there were more emails than fit on one screen, reporting
     * "Reached the end of the inbox" while most of it was never even
     * looked at. Re-finding the last row by its own text and scrolling
     * (same scroll-and-retry shape as expandAllMessages()/
     * extractThreadScrolling() elsewhere in this file) survives however
     * many rows have scrolled past, since it never depends on how many
     * rows happen to be simultaneously rendered. */
    private fun locateNextRow(
        service: ReadAloudAccessibilityService,
        root: AccessibilityNodeInfo,
        lastRowText: String?,
        pendingIndex: Int?,
        direction: Int,
    ): AccessibilityNodeInfo? {
        if (lastRowText == null) return listRows(root).getOrNull(pendingIndex ?: 0)
        var current = root
        repeat(MAX_INBOX_SCROLLS) {
            val rows = listRows(current)
            val anchorIdx = rows.indexOfFirst { (it.text?.toString() ?: "") == lastRowText }
            if (anchorIdx != -1) {
                val neighbor = rows.getOrNull(anchorIdx + direction)
                if (neighbor != null) {
                    val b = Rect()
                    neighbor.getBoundsInScreen(b)
                    if (!b.isEmpty) return neighbor
                }
            }
            val listView = AccessibilityTree.largestScrollable(current) ?: return null
            val scrolled = if (direction > 0) service.scrollForward(listView) else service.scrollBackward(listView)
            if (!scrolled) return null
            Thread.sleep(300)
            current = service.foregroundRoot()?.second ?: return null
        }
        return null
    }

    /** The actual loop: make sure we're on the list, tap the next row (see
     * locateNextRow's own doc for how "next" is found without a raw
     * index), wait for it to open, read it (blocking until playback
     * finishes - must not open the next email while this one is still
     * being spoken), repeat. Stops on reaching either end of the list,
     * MAX_SEQUENCE_EMAILS, or a fresh Read Aloud invocation superseding
     * this one (checked via isSuperseded() before and after every blocking
     * step - the whole reason that generation counter exists). */
    private fun runInboxSequence(service: ReadAloudAccessibilityService, startIndex: Int, direction: Int, label: String) {
        val generation = service.currentGeneration()
        val startedAtMs = System.currentTimeMillis()
        var pendingIndex: Int? = startIndex
        var lastRowText: String? = null
        var steps = 0
        while (steps < MAX_SEQUENCE_EMAILS) {
            if (service.isSuperseded(generation)) return
            if (System.currentTimeMillis() - startedAtMs > MAX_SEQUENCE_WALL_CLOCK_MS) {
                service.toast("Stopped - inbox read ran too long")
                return
            }
            steps++

            var root = service.findForegroundWithRetry(packageName)?.second ?: return
            if (isOpenEmailScreen(root)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(500)
                root = service.findForegroundWithRetry(packageName)?.second ?: return
            }
            if (isOpenEmailScreen(root)) {
                service.toast("Couldn't get back to the inbox list")
                return
            }

            val target = locateNextRow(service, root, lastRowText, pendingIndex, direction)
            pendingIndex = null
            Log.i(TAG, "runInboxSequence: step=$steps target=${target != null}")
            if (target == null) {
                service.toast(if (direction < 0) "Reached the top of the inbox" else "Reached the end of the inbox")
                return
            }
            val targetText = target.text?.toString() ?: ""
            if (!service.click(target)) {
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

            lastRowText = targetText
        }
        service.toast("Stopped after $MAX_SEQUENCE_EMAILS emails")
    }

    // Confirmed live 2026-09-13 against a real GitHub-notification thread
    // with 5 messages: Gmail auto-expands only the LATEST message, showing
    // every earlier one as a collapsed card (sender/date/one-line
    // `email_snippet`, not the real body) - reading as-is gives an
    // incomplete impression of the thread, exactly what was reported
    // ("reading whats visible which is bit of semi-collapse text").
    // Real mechanism, found by walking the actual tree:
    //   - `super_collapsed_block` (content-desc "Expand N older messages")
    //     is a stub standing in for messages not rendered AT ALL yet -
    //     tapping it materializes them (as more collapsed cards, or
    //     occasionally another super_collapsed_block if there were even
    //     more - looped below, not assumed to resolve in one tap).
    //   - Each individual collapsed card's clickable ancestor is
    //     `upper_header` (NOT the card's own sender_name/snippet text,
    //     which aren't independently clickable) - tapping it expands that
    //     one message. A collapsed card is told apart from an already-
    //     expanded one by containing an `email_snippet` descendant at all
    //     (an expanded message has `recipient_summary` in that position
    //     instead) - tapping an ALREADY-expanded message's header would
    //     just re-collapse it, so this check matters, not just a
    //     convenience.
    private const val MAX_EXPAND_ITERATIONS = 8

    // Whether a thread's per-message containers carry a stable, globally
    // unique id ("m#msg-f:<big number>-header") turns out to be
    // inconsistent - confirmed live 2026-09-13 against the SAME thread
    // across different app-process lifetimes: sometimes every message
    // gets that wrapper, sometimes (a genuinely fresh Gmail process,
    // right after force-stop) NONE of them do and only the plain,
    // non-unique `upper_header`/`email_snippet`/`recipient_summary` ids
    // are present at all. An earlier version of this counted/enumerated
    // messages by that unique id and got a silent, wrong answer (a real
    // 6-message thread counted as 0 or mislabeled every picker row
    // "Message N") the moment the id scheme it assumed wasn't the one
    // actually present. Replaced with two things that don't depend on
    // any particular id scheme at all:
    //   - countMessages() below, for the cheap "does this thread need a
    //     Read all/Read selected chooser at all" decision, using
    //     `upper_header` (present for every visible message regardless of
    //     id scheme) plus `super_collapsed_text`'s own digit (Gmail's own
    //     count of how many messages a block is hiding) - needs no
    //     clicking/expanding at all to be accurate.
    //   - readSelectedMessages() below builds its per-message labels
    //     straight from extract()'s own already-working "From: "/date
    //     line output instead of walking node containers for
    //     sender_name/upper_date, for the same reason.
    private fun countMessages(root: AccessibilityNodeInfo): Int {
        val visible = AccessibilityTree.findAllNodes(root) { it.viewIdResourceName?.endsWith("upper_header") == true }.size
        val hidden = AccessibilityTree.findAllNodes(root) { it.viewIdResourceName?.endsWith("super_collapsed_text") == true }
            .sumOf { it.text?.toString()?.trim()?.toIntOrNull() ?: 0 }
        return visible + hidden
    }

    /** Expands EVERY message in an open thread for real "read all"/"read
     * selected" content - confirmed live 2026-09-13 against a real
     * 6-message thread that expandCollapsedMessages() (below, kept for
     * the single-message extract() path) silently drops messages a
     * super_collapsed_block materializes: those can render with a
     * per-message unique id ("m#msg-f:<id>-header") that has NO text,
     * content-desc, or children at all until clicked - completely unlike
     * the ORIGINAL two visible collapsed cards, which expose a real
     * `email_snippet` expandCollapsedMessages() knows to look for. Since
     * that function's loop only ever searches for `email_snippet`, it
     * finds nothing left once those are gone and stops - even though 3 of
     * this thread's 6 messages were never expanded at all, with no error
     * or signal that anything was missed. This loop instead checks for
     * EITHER collapsed signal every iteration (unaffected by which one a
     * given thread/session happens to use - see countMessages' own doc
     * for why relying on one specific id scheme already broke once) and
     * keeps going until neither is found anywhere in the tree.
     *
     * Also confirmed live: a message further down a long thread can have
     * empty on-screen bounds (Gmail's RecyclerView-style lazy layout
     * hasn't measured an off-screen item yet, same shape of problem
     * RedditProfile's own scroll-then-retry already solves for its feed)
     * - scrolls the thread's own vertical ScrollView and retries rather
     * than treating a zero-size node as "nothing left to expand". Found
     * the hard way: the first attempt at this scrolled `item_pager`
     * instead, which looks like a plausible scroll container but is
     * actually the HORIZONTAL pager for swiping to the next/previous
     * CONVERSATION in the inbox - calling ACTION_SCROLL_FORWARD on it
     * silently navigated clean away to a different email entirely, with
     * no error, no crash, just a picker built from the wrong thread's
     * (much shorter) message list. The real vertical scroll container
     * has no resource id at all, only a stable className
     * ("android.widget.ScrollView") - id lookups are useless here, has
     * to be a class match. */
    private const val MAX_FULL_EXPAND_ITERATIONS = 24

    /** Total count of still-collapsed messages by EITHER signal (see
     * expandAllMessages' own doc for what Path 1/Path 2 mean) - the real
     * "are we done" and "did that click actually do anything" measure,
     * used instead of the target node's own on-screen bounds. Bounds
     * looked like a reasonable progress signal at first (clicking the
     * exact same bounds twice in a row certainly means nothing changed)
     * but produced a false-positive "stuck" abort live: after a message
     * expands, everything below it reflows, and a completely DIFFERENT
     * still-collapsed message can land at the exact bounds the previous
     * one just occupied - aborting a genuinely still-progressing loop
     * after only 3 of 6 messages. A plain remaining-count, unaffected by
     * where things happen to be drawn, doesn't have this problem. */
    // Path 2's target id, precisely - NOT just "anything ending in
    // -header": confirmed live 2026-09-13 that `endsWith("-header")`
    // alone also matches "conversation-header" (the THREAD's own
    // decorative header, a single node with no message behind it at
    // all) - which, sitting first in document order, got PICKED as the
    // click target every single iteration instead of the real stuck
    // message, since it satisfies every other Path 2 condition too
    // (empty text, no children, no matching "-content" sibling - it has
    // none of those by nature, not because it's an unexpanded message).
    // Clicking it does nothing, so the loop looked "stuck" forever on
    // what was actually a real, resolvable message right behind it.
    private val EMPTY_HEADER_REGEX = Regex("""^m#msg-f:\d+-header$""")

    /** The container to scroll for "reveal more of THIS thread" - never
     * a fixed class name: confirmed live 2026-09-14 that whether a
     * thread's vertical scroll container is a plain `ScrollView` or
     * something else (a message body rendered as a WebView reported
     * `scrollable=true` too, in a state where no ScrollView existed in
     * the tree at all) varies with what's actually expanded/rendered at
     * the moment - a hardcoded className check silently found nothing at
     * all in that state. `AccessibilityTree.largestScrollable()` (used
     * elsewhere for exactly this "guess the main scrollable area"
     * purpose) isn't safe to reuse as-is here, though: `item_pager` (the
     * HORIZONTAL conversation-to-conversation pager - see
     * expandAllMessages()'s own doc for why scrolling THAT one is a real,
     * previously-hit bug) is almost always the single largest-by-area
     * scrollable node on this screen, so a plain largest-by-area pick
     * would choose it every time. Explicitly excluded by id instead. */
    private fun threadScrollContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        AccessibilityTree.findAllNodes(root) { it.isScrollable && it.viewIdResourceName?.endsWith("item_pager") != true }
            .maxByOrNull { node ->
                val r = Rect(); node.getBoundsInScreen(r)
                r.width().toLong() * r.height().toLong()
            }

    private fun collapsedMessageTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Path 1: a still-collapsed card with a real `email_snippet` -
        // walk up to its `upper_header` (the snippet/sender text itself
        // isn't independently clickable).
        val snippet = AccessibilityTree.findNode(root) { it.viewIdResourceName?.endsWith("email_snippet") == true }
        if (snippet != null) {
            var header: AccessibilityNodeInfo? = snippet
            while (header != null && header.viewIdResourceName?.endsWith("upper_header") != true) header = header.parent
            return header ?: snippet
        }
        // Path 2: a message materialized from a super_collapsed_block
        // that never got the real upper_header/email_snippet structure
        // at all - just an empty per-message-unique "-header" node,
        // clickable only via a bounds tap. Its own emptiness persists
        // even AFTER expanding (confirmed live - the real populated
        // content shows up as a separate "<same base id>-content"
        // sibling, the "-header" node itself never changes), so
        // emptiness alone can't tell "still collapsed" from "already
        // expanded" - checking for that sibling's absence is what
        // actually distinguishes them, same idea as
        // email_snippet-vs-recipient_summary in Path 1 above.
        return AccessibilityTree.findNode(root) {
            val id = it.viewIdResourceName ?: return@findNode false
            if (!EMPTY_HEADER_REGEX.matches(id)) return@findNode false
            if (!(it.text.isNullOrEmpty() && it.childCount == 0)) return@findNode false
            val baseId = id.removeSuffix("-header")
            AccessibilityTree.findNode(root) { n -> n.viewIdResourceName == "$baseId-content" } == null
        }
    }

    private fun countCollapsedTargets(root: AccessibilityNodeInfo): Int {
        val snippets = AccessibilityTree.findAllNodes(root) { it.viewIdResourceName?.endsWith("email_snippet") == true }.size
        val emptyHeaders = AccessibilityTree.findAllNodes(root) {
            val id = it.viewIdResourceName ?: return@findAllNodes false
            if (!EMPTY_HEADER_REGEX.matches(id)) return@findAllNodes false
            if (!(it.text.isNullOrEmpty() && it.childCount == 0)) return@findAllNodes false
            val baseId = id.removeSuffix("-header")
            AccessibilityTree.findNode(root) { n -> n.viewIdResourceName == "$baseId-content" } == null
        }.size
        return snippets + emptyHeaders
    }

    private fun expandAllMessages(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = root
        repeat(MAX_FULL_EXPAND_ITERATIONS) { iteration ->
            val superCollapsed = AccessibilityTree.findNode(current) {
                it.viewIdResourceName?.endsWith("super_collapsed_block") == true
            }
            if (superCollapsed != null) {
                Log.i(TAG, "expandAllMessages[$iteration]: popping super_collapsed_block")
                if (!service.click(superCollapsed)) return current
                Thread.sleep(500)
                current = service.foregroundRoot()?.second ?: return current
                return@repeat
            }
            val before = countCollapsedTargets(current)
            if (before == 0) {
                Log.i(TAG, "expandAllMessages[$iteration]: nothing left to expand")
                return current
            }
            val target = collapsedMessageTarget(current) ?: return current
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            if (bounds.isEmpty) {
                Log.i(TAG, "expandAllMessages[$iteration]: next message off-screen, scrolling")
                val scrollView = threadScrollContainer(current)
                if (scrollView == null || !service.scrollForward(scrollView)) return current
                Thread.sleep(400)
                current = service.foregroundRoot()?.second ?: return current
                return@repeat
            }
            Log.i(TAG, "expandAllMessages[$iteration]: expanding a message ($before remaining)")
            if (!service.click(target)) return current
            Thread.sleep(700)
            var next = service.foregroundRoot()?.second ?: return current
            // Might just be Gmail's expand animation not settled yet
            // rather than a real stuck state - a few longer retries
            // before giving up (see this function's own doc for why a
            // real repeat wait, not just one, was needed here - the
            // emulator's rendering can lag well past the first 700ms on
            // a long thread with several messages already expanded).
            var retriesLeft = 3
            var extraWaitMs = 500
            while (countCollapsedTargets(next) >= before && retriesLeft > 0) {
                Thread.sleep(extraWaitMs.toLong())
                next = service.foregroundRoot()?.second ?: return current
                retriesLeft--
                extraWaitMs += 300
            }
            if (countCollapsedTargets(next) >= before) {
                Log.w(TAG, "expandAllMessages[$iteration]: no progress after retries, stopping")
                return next
            }
            current = next
        }
        return current
    }

    private const val MAX_THREAD_SCROLLS = 20

    /** Reads a long, multi-message thread's real content top-to-bottom by
     * scrolling and accumulating, not one single tree walk - confirmed
     * live 2026-09-14 that a single extract() call on an already-fully-
     * (or mostly-)expanded thread still only captured 2 of 6 messages'
     * worth of text, unchanged by the STOP_MARKERS footer-trim fix from
     * the night before (which turned out not to be the cause at all).
     * The real reason: `collectText()`/`collectTextWithLabels()` have
     * ALWAYS - correctly, by original design - skipped anything not
     * currently `isVisibleToUser`. `expandAllMessages()` getting a
     * message's real content into the DOM doesn't mean that content is
     * still on SCREEN by the time extraction runs - it's usually been
     * pushed well below the fold by everything expanded above it, and
     * `expandAllMessages()` never scrolls back afterward. Same shape of
     * fix as RedditProfile's own scroll-and-accumulate loop for its feed:
     * scroll to the top first (expandAllMessages leaves the scroll
     * position wherever its last click/scroll landed, not at the start),
     * then repeatedly capture the currently-visible labeled text and
     * scroll forward, deduping via a LinkedHashSet (same tradeoff Reddit's
     * version already accepts - a short boilerplate line like "to X, Y,
     * Z" repeating verbatim across messages collapses to one occurrence,
     * which costs far less than the alternative of not deduping real
     * overlap between consecutive scroll steps at all). */
    private fun extractThreadScrolling(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): List<String> {
        var current = root
        run {
            var tries = 0
            var lastFingerprint: String? = null
            var stagnant = 0
            while (tries < MAX_THREAD_SCROLLS && stagnant < 2) {
                val sv = threadScrollContainer(current) ?: break
                if (!service.scrollBackward(sv)) break
                Thread.sleep(300)
                current = service.foregroundRoot()?.second ?: break
                tries++
                // Same idea as the forward pass' stagnant-pass check below,
                // just using the raw joined text as a cheap fingerprint
                // (no LinkedHashSet needed here - this phase only cares
                // "did the screen change at all", not what's on it) -
                // stops as soon as further backward scrolling stops
                // changing anything (genuinely at the top) instead of
                // always spending the full MAX_THREAD_SCROLLS budget here
                // even when 2-3 scrolls were actually enough.
                val fingerprint = AccessibilityTree.collectText(current).joinToString("|")
                stagnant = if (fingerprint == lastFingerprint) stagnant + 1 else 0
                lastFingerprint = fingerprint
            }
        }
        val seen = LinkedHashSet<String>()
        fun captureCurrent() {
            AccessibilityTree.collectTextWithLabels(current, FIELD_LABELS).forEach { line ->
                seen.add(
                    if (line.startsWith("Subject: ")) "Subject: " + stripTrailingLabels(line.removePrefix("Subject: "))
                    else line,
                )
            }
        }
        captureCurrent()
        Log.i(TAG, "extractThreadScrolling: initial capture size=${seen.size}")
        var scrolls = 0
        var stagnantPasses = 0
        while (scrolls < MAX_THREAD_SCROLLS && stagnantPasses < 2) {
            val beforeSize = seen.size
            val sv = threadScrollContainer(current) ?: break
            val ok = service.scrollForward(sv)
            Log.i(TAG, "extractThreadScrolling: scrollForward[$scrolls] ok=$ok")
            if (!ok) break
            scrolls++
            Thread.sleep(300)
            current = service.foregroundRoot()?.second ?: break
            captureCurrent()
            Log.i(TAG, "extractThreadScrolling: after scroll $scrolls, size=${seen.size} (was $beforeSize)")
            stagnantPasses = if (seen.size == beforeSize) stagnantPasses + 1 else 0
        }
        return seen.toList()
    }

    /** Reads only the messages the user picked in MessagePickerActivity.
     * Reuses extract()'s own (already-tested) full-thread walk rather
     * than a second, parallel per-container extraction path: each
     * message's segment is just the run of lines from its own "From: "
     * line up to the next one, and container order matches "From: " line
     * order exactly (both come from the same document-order tree walk
     * over the same, by-then-fully-expanded tree). The thread's Subject
     * line is always prepended once, for context, regardless of which
     * messages were picked. */
    private fun readSelectedMessages(service: ReadAloudAccessibilityService, indices: List<Int>, label: String) {
        val root = service.findForegroundWithRetry(packageName)?.second
        if (root == null || !isOpenEmailScreen(root)) { service.toast("No email is open"); return }
        val fullyExpanded = expandAllMessages(service, root)
        val lines = try { extractThreadScrolling(service, fullyExpanded) } catch (e: Exception) { emptyList() }
        if (lines.isEmpty()) { service.toast("Nothing readable found on screen"); return }
        val fromIndices = lines.withIndex().filter { it.value.startsWith("From: ") }.map { it.index }
        if (fromIndices.isEmpty()) { service.toast("Couldn't tell messages apart"); return }
        val subjectLine = lines.firstOrNull { it.startsWith("Subject: ") }
        val segments = fromIndices.mapIndexed { i, start ->
            val end = fromIndices.getOrNull(i + 1) ?: lines.size
            lines.subList(start, end)
        }
        val chosen = indices.mapNotNull { segments.getOrNull(it) }.flatten()
        if (chosen.isEmpty()) { service.toast("No messages selected"); return }
        val text = (listOfNotNull(subjectLine) + chosen).joinToString("\n").trim()
        service.toast("Reading $label…")
        TtsSpeaker.speak(service, label, text)
    }

    private fun expandCollapsedMessages(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = root
        repeat(MAX_EXPAND_ITERATIONS) {
            val superCollapsed = AccessibilityTree.findNode(current) {
                it.viewIdResourceName?.endsWith("super_collapsed_block") == true
            }
            val target = superCollapsed ?: run {
                val snippet = AccessibilityTree.findNode(current) {
                    it.viewIdResourceName?.endsWith("email_snippet") == true
                } ?: return current // nothing left to expand
                var header: AccessibilityNodeInfo? = snippet
                while (header != null && header.viewIdResourceName?.endsWith("upper_header") != true) {
                    header = header.parent
                }
                header ?: snippet
            }
            if (!service.click(target)) return current
            Thread.sleep(400) // let the newly-expanded body actually render before re-querying
            current = service.foregroundRoot()?.second ?: return current
        }
        return current
    }

    override fun extract(service: ReadAloudAccessibilityService, root: AccessibilityNodeInfo, mode: String): List<String> {
        val workingRoot = if (isOpenEmailScreen(root)) expandCollapsedMessages(service, root) else root
        val all = AccessibilityTree.collectTextWithLabels(workingRoot, FIELD_LABELS)
        // STOP_MARKERS assumes a SINGLE email's own footer marks the true
        // end of everything worth reading - confirmed live 2026-09-13
        // this breaks a real multi-message thread badly: the walk hits
        // the FIRST message's own footer ("Unsubscribe"/"view it on
        // GitHub"/...) and stops there, silently discarding every
        // message after it - "read all" on a real 6-message thread kept
        // producing the exact same ~1107 chars regardless of how many
        // messages expandAllMessages() actually expanded underneath,
        // because this break fired on message 1's footer every time.
        // Only trim at all when there's genuinely one message to trim -
        // a little footer noise read aloud per message in a real thread
        // is a far smaller cost than silently losing most of the thread.
        val trimFooters = countMessages(workingRoot) <= 1
        var seenUnsubscribeOnce = false
        val out = mutableListOf<String>()
        for (rawLine in all) {
            val line = if (rawLine.startsWith("Subject: ")) {
                "Subject: " + stripTrailingLabels(rawLine.removePrefix("Subject: "))
            } else {
                rawLine
            }
            if (trimFooters) {
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
            }
            out.add(line)
        }
        return out
    }
}
