package dev.local.readaloud

/**
 * What PlayerActivity needs to show a read-along view: the full text of
 * the current read plus where playback currently is. Written by
 * TtsSpeaker (start of a read, and from its HighlightListener), read by
 * PlayerActivity's poll - the service has only one listener slot and
 * TtsSpeaker owns it, so this is the shared hand-off instead.
 */
object ReadAlongState {
    @Volatile var fullText: String = ""
    @Volatile var sentence: String = ""
    @Volatile var words: List<WordTiming> = emptyList()
    @Volatile var wordIdx: Int = -1

    /** Start (ms, service timeline) of each streamed chunk/"section", for skip-section controls. */
    val sections: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    /** Streaming reads feed one screenful at a time; the player's full text grows with them. */
    fun append(text: String) {
        fullText = if (fullText.isEmpty()) text else fullText + "\n\n" + text
    }

    fun begin(text: String) {
        sections.clear()
        fullText = text
        sentence = ""
        words = emptyList()
        wordIdx = -1
    }
}
