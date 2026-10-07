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

    /**
     * The same rules for a streamed read (ScrollReader): lines arrive a screenful at a time and a
     * message's quote/signature can straddle screens, so state is carried across calls.
     * Ambiguous openers are held back until the next few lines settle them: a "From: " line (new
     * message, or Outlook's quoted header?), an "On ..." line (start of a wrapped attribution?), and the
     * short block after a closing like "Kind regards," (signature, or more of the email?).
     */
    class Stream : StreamFilter {
        private enum class Kind { NONE, FROM, ATTR, SIG }
        private var inQuote = false
        private var inSig = false
        private var sawContent = false
        private var kind = Kind.NONE
        private val pending = ArrayList<String>()

        override fun feed(lines: List<String>): List<String> {
            val out = ArrayList<String>()
            for (l in lines) process(l, out)
            return out
        }

        /** End of the read: a held "From:"/"On ..." opener was ordinary content after all; a held
         * signature block is dropped. */
        override fun finish(): List<String> {
            val out = ArrayList<String>()
            val held = ArrayList(pending); val k = kind
            pending.clear(); kind = Kind.NONE
            when (k) {
                Kind.FROM -> reprocess(held, out, true, false)
                Kind.ATTR -> reprocess(held, out, false, true)
                else -> {}
            }
            return out
        }

        private fun newMessage() { inQuote = false; inSig = false; sawContent = false }

        private fun reprocess(held: List<String>, out: MutableList<String>, firstIsMessageStart: Boolean, firstIsPlain: Boolean) {
            for ((i, l) in held.withIndex()) {
                when {
                    i == 0 && firstIsMessageStart -> { newMessage(); out.add(l) }
                    i == 0 && firstIsPlain -> { out.add(l); sawContent = true }
                    else -> process(l, out)
                }
            }
        }

        private fun process(line: String, out: MutableList<String>) {
            val t = line.trim()
            if (kind != Kind.NONE) {
                pending.add(line)
                when (kind) {
                    Kind.FROM -> when {
                        line.startsWith("From: ") -> {
                            // a newer "From:" settles the older one as a real message start
                            val held = ArrayList(pending.dropLast(1)); pending.clear(); kind = Kind.NONE
                            reprocess(held, out, true, false)
                            kind = Kind.FROM; pending.add(line)
                        }
                        OUTLOOK_HEADER_NEXT.containsMatchIn(t) -> { pending.clear(); kind = Kind.NONE; inQuote = true }
                        pending.size >= 4 -> { val held = ArrayList(pending); pending.clear(); kind = Kind.NONE; reprocess(held, out, true, false) }
                    }
                    Kind.ATTR -> {
                        val joined = pending.joinToString(" ") { it.trim() }
                        when {
                            ATTRIBUTION.matches(joined) -> { pending.clear(); kind = Kind.NONE; inQuote = true }
                            pending.size >= 3 -> { val held = ArrayList(pending); pending.clear(); kind = Kind.NONE; reprocess(held, out, false, true) }
                        }
                    }
                    Kind.SIG -> {
                        val isBoundary = line.startsWith("From: ") || t.startsWith(">") || ATTRIBUTION.matches(t) || ORIGINAL_MESSAGE.matches(t)
                        when {
                            isBoundary || SIG_DELIMITER.matches(t) || SENT_FROM.containsMatchIn(t) -> {
                                pending.removeAt(pending.size - 1); pending.clear(); kind = Kind.NONE; inSig = true
                                process(line, out)
                            }
                            line.length > MAX_SIGNATURE_LINE_CHARS || pending.size > MAX_SIGNATURE_LINES -> {
                                val held = ArrayList(pending); pending.clear(); kind = Kind.NONE
                                for (l in held) process(l, out) // not a signature after all: it was content
                            }
                        }
                    }
                    else -> {}
                }
                return
            }
            if (line.startsWith("From: ")) { kind = Kind.FROM; pending.add(line); return }
            if (inQuote || inSig) return
            if (t.startsWith(">") || ORIGINAL_MESSAGE.matches(t) || ATTRIBUTION.matches(t)) { inQuote = true; return }
            if (Regex("^(On|Le|Am|El)\\s", RegexOption.IGNORE_CASE).containsMatchIn(t)) { kind = Kind.ATTR; pending.add(line); return }
            if (sawContent && (SIG_DELIMITER.matches(t) || SENT_FROM.containsMatchIn(t))) { inSig = true; return }
            out.add(line)
            if (!line.startsWith("Subject: ")) sawContent = true
            if (sawContent && CLOSER.matches(t)) kind = Kind.SIG
        }
    }
}
