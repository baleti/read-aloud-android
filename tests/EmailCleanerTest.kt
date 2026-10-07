// Run: kotlinc src/dev/local/readaloud/EmailCleaner.kt tests/EmailCleanerTest.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
import dev.local.readaloud.EmailCleaner
fun check(name: String, input: List<String>, expected: List<String>) {
    val got = EmailCleaner.clean(input)
    println((if (got == expected) "PASS " else "FAIL ") + name + (if (got == expected) "" else "\n  got:      $got\n  expected: $expected"))
    // The streamed version (state carried across screens) must agree however the lines are split.
    for (split in 0..input.size) {
        val st = EmailCleaner.Stream()
        val sgot = st.feed(input.subList(0, split)) + st.feed(input.subList(split, input.size)) + st.finish()
        // lines still held back at the very end (e.g. a trailing signature) are dropped, as they should be
        if (sgot != expected) println("FAIL stream[$name] split=$split\n  got:      $sgot\n  expected: $expected")
    }
}
fun main() {
    check("quote attribution",
        listOf("Subject: Re: Plan", "From: Ann", "Sounds good, see you Monday.", "On Tue, 6 Oct 2026 at 10:00, Bob <b@x.com> wrote:", "> earlier text", "more earlier"),
        listOf("Subject: Re: Plan", "From: Ann", "Sounds good, see you Monday."))
    check("wrapped attribution",
        listOf("From: Ann", "Yes.", "On Tue, 6 Oct 2026 at 10:00, Bob", "<b@x.com> wrote:", "old"),
        listOf("From: Ann", "Yes."))
    check("signature closer",
        listOf("From: Ann", "Please send the drawings.", "Kind regards,", "Ann Smith", "Senior Architect", "Acme Ltd", "+44 1234 567890"),
        listOf("From: Ann", "Please send the drawings.", "Kind regards,"))
    check("closer mid-email kept",
        listOf("From: Ann", "Thanks,", "one more thing: " + "x".repeat(200), "and another long line " + "y".repeat(200), "l3","l4","l5","l6","l7","l8","l9","l10"),
        listOf("From: Ann", "Thanks,", "one more thing: " + "x".repeat(200), "and another long line " + "y".repeat(200), "l3","l4","l5","l6","l7","l8","l9","l10"))
    check("sig delimiter + sent from",
        listOf("From: Ann", "Ok.", "-- ", "Ann", "Acme"), listOf("From: Ann", "Ok."))
    check("sent from iphone", listOf("From: Ann", "Ok will do", "Sent from my iPhone", "x"), listOf("From: Ann", "Ok will do"))
    check("outlook quote",
        listOf("From: Ann", "Agreed.", "From: Bob <b@x.com>", "Sent: 06 October 2026 10:00", "To: Ann", "Subject: Plan", "old body"),
        listOf("From: Ann", "Agreed."))
    check("thread keeps each message",
        listOf("Subject: Re: X", "From: Ann", "First reply.", "On Mon, Bob wrote:", "q1", "From: Bob", "Second reply.", "Regards,", "Bob", "Acme", "From: Cy", "Third."),
        listOf("Subject: Re: X", "From: Ann", "First reply.", "From: Bob", "Second reply.", "Regards,", "From: Cy", "Third."))
    check("plain email untouched", listOf("Subject: Hi", "From: Ann", "Just a note.", "Another line."), listOf("Subject: Hi", "From: Ann", "Just a note.", "Another line."))
    check("original message", listOf("From: Ann", "Fwd please", "-----Original Message-----", "From: Bob", "Sent: x", "old"), listOf("From: Ann", "Fwd please"))
}
