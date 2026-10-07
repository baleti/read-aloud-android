package dev.local.readaloud

/**
 * Rule-based trimming of an email's lines (GmailProfile): drops quoted
 * reply history and signature blocks so a long thread isn't read with the
 * same quoted text over and over. Pure Kotlin, no Android types, so it can
 * be unit-tested off-device. Known-imperfect heuristics, like
 * GmailProfile.STOP_MARKERS - refine as real misses turn up.
 *
 * Works per message: a thread's flat line list is split at each message's
 * own "From: <sender>" label (the one GmailProfile adds from sender_name),
 * but NOT at an Outlook-style quoted header ("From: ..." followed within
 * three lines by "Sent:"/"To:"/"Date:"), which is quote, not a new message.
 */
object EmailCleaner {
    // "On Tue, 6 Oct 2026 at 10:00, X <x@y.z> wrote:" and common translations.
    private val ATTRIBUTION = Regex(
        """^(On|Le|Am|El|Il|Op|Den)\s.{4,200}?\b(wrote|a écrit|schrieb|escribió|ha scritto|schreef|skrev)\s*:?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ORIGINAL_MESSAGE = Regex("""^[-_=\s]*(original message|forwarded message|reply message)[-_=\s]*$""", RegexOption.IGNORE_CASE)
    private val OUTLOOK_HEADER_NEXT = Regex("""^(sent|to|date|cc|subject)\s*:""", RegexOption.IGNORE_CASE)
    private val SIG_DELIMITER = Regex("""^--\s?$""")
    private val SENT_FROM = Regex("""^(sent from my |sent from outlook|sent from mail for|get outlook for|sent via |sent with )""", RegexOption.IGNORE_CASE)
    private val CLOSER = Regex(
        """^(best regards|kind regards|warm regards|regards|best wishes|best|thanks|thank you|many thanks|cheers|sincerely|yours sincerely|yours faithfully|yours|with thanks|thanks again|bw|br)\s*[,!.]*$""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_SIGNATURE_LINES = 8
    private const val MAX_SIGNATURE_LINE_CHARS = 120

    fun clean(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var seg = mutableListOf<String>()
        fun flush() { out.addAll(cleanMessage(seg)); seg = mutableListOf() }
        for ((i, line) in lines.withIndex()) {
            if (isMessageStart(lines, i)) flush()
            seg.add(line)
        }
        flush()
        return out
    }

    private fun isMessageStart(lines: List<String>, i: Int): Boolean {
        if (!lines[i].startsWith("From: ")) return false
        // Outlook-style quoted header block, not a new message
        return lines.subList(i + 1, minOf(lines.size, i + 4)).none { OUTLOOK_HEADER_NEXT.containsMatchIn(it.trim()) }
    }

    private fun cleanMessage(seg: List<String>): List<String> {
        if (seg.isEmpty()) return seg
        // Header lines the profile adds ("From: x", "Subject: y") are never cut.
        var bodyStart = 0
        while (bodyStart < seg.size && (seg[bodyStart].startsWith("From: ") || seg[bodyStart].startsWith("Subject: "))) bodyStart++
        val body = seg.subList(bodyStart, seg.size)

        var end = body.size
        for (i in body.indices) {
            if (i == 0 && body.size > 1) {
                // a message that STARTS with a quote marker has nothing worth keeping before it - still cut
            }
            val t = body[i].trim()
            if (isQuoteStart(body, i, t)) { end = i; break }
        }
        var kept = body.subList(0, end)

        // Signature: delimiter / "Sent from my..." anywhere after some content, or a closer
        // followed by a short block of short lines.
        var cut = kept.size
        for (i in kept.indices) {
            val t = kept[i].trim()
            if (i > 0 && (SIG_DELIMITER.matches(t) || SENT_FROM.containsMatchIn(t))) { cut = i; break }
            if (i > 0 && CLOSER.matches(t)) {
                val tail = kept.subList(i + 1, kept.size)
                if (tail.size <= MAX_SIGNATURE_LINES && tail.all { it.length <= MAX_SIGNATURE_LINE_CHARS }) { cut = i + 1; break }
            }
        }
        kept = kept.subList(0, cut)
        return seg.subList(0, bodyStart) + kept
    }

    private fun isQuoteStart(body: List<String>, i: Int, t: String): Boolean {
        if (t.startsWith(">")) return true
        if (ORIGINAL_MESSAGE.matches(t)) return true
        if (ATTRIBUTION.matches(t)) return true
        // attribution wrapped over two or three nodes
        if (Regex("^(On|Le|Am|El)\\s", RegexOption.IGNORE_CASE).containsMatchIn(t) && i + 1 < body.size) {
            for (n in 2..3) {
                if (i + n > body.size) break
                val joined = body.subList(i, i + n).joinToString(" ") { it.trim() }
                if (ATTRIBUTION.matches(joined)) return true
            }
        }
        // Outlook-style header block: "From: x" then Sent:/To:/Date: within three lines
        if (t.startsWith("From:") && body.subList(i + 1, minOf(body.size, i + 4)).any { OUTLOOK_HEADER_NEXT.containsMatchIn(it.trim()) }) return true
        return false
    }
}
