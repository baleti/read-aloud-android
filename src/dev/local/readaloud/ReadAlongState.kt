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
    /** Bumped every time a sentence starts playing (even if its text repeats). */
    @Volatile var sentenceSeq: Int = 0
    @Volatile var words: List<WordTiming> = emptyList()
    @Volatile var wordIdx: Int = -1
    /** A ScrollReader read is still adding content, so the total length shown is only what's known so far. */
    @Volatile var streaming: Boolean = false

    /** Start (ms, service timeline) of each streamed chunk/"section", for skip-section controls. */
    val sections: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    /** Streaming reads feed one screenful at a time; the player's full text grows with them. */
    fun append(text: String) {
        fullText = if (fullText.isEmpty()) text else fullText + "\n\n" + text
        if (persisting) saveText()
    }

    // --- Last-document persistence (2026-10-08): the in-memory text above dies with the
    // process, so a shared document is mirrored to disk and its spoken position to prefs.
    @Volatile var title: String = ""
    /** Char offset in [fullText] where the current audio session's first sentence starts. */
    @Volatile var sessionBase: Int = 0
    /** Char offset into [fullText] of the sentence last spoken. */
    @Volatile var offset: Int = 0
    @Volatile var persisting = false
        private set
    private var appCtx: android.content.Context? = null
    private val prefs get() = appCtx?.getSharedPreferences("readaloud_last_doc", android.content.Context.MODE_PRIVATE)
    private fun docFile() = java.io.File(appCtx!!.filesDir, "last_doc.txt")

    private fun saveText() {
        if (appCtx == null) return
        val t = fullText
        Thread {
            try {
                val f = docFile(); val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(t); tmp.renameTo(f)
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    /** Called when a new non-screen read starts; replaces the saved document. */
    fun persistNew(ctx: android.content.Context, title: String) {
        appCtx = ctx.applicationContext
        persisting = true
        this.title = title; offset = 0
        prefs?.edit()?.putString("title", title)?.putInt("offset", 0)?.apply()
        saveText()
    }

    /** Load the saved document into memory if nothing is loaded. Returns true if a document is available. */
    @Synchronized fun restore(ctx: android.content.Context): Boolean {
        appCtx = ctx.applicationContext
        if (fullText.isNotEmpty()) return true
        return try {
            val f = docFile()
            if (!f.exists()) return false
            val t = f.readText()
            if (t.isBlank()) return false
            fullText = t
            title = prefs?.getString("title", "") ?: ""
            offset = (prefs?.getInt("offset", 0) ?: 0).coerceIn(0, t.length)
            true
        } catch (_: Exception) { false }
    }

    fun hasSaved(ctx: android.content.Context): Boolean =
        java.io.File(ctx.applicationContext.filesDir, "last_doc.txt").let { it.exists() && it.length() > 0 }

    /** A word of the document that has audio: char range in [fullText] and its start (ms, seekTo timeline). */
    class KnownWord(val charStart: Int, val charEnd: Int, val ms: Long)
    val knownWords: MutableList<KnownWord> = java.util.Collections.synchronizedList(ArrayList())
    private var sentenceCursor = 0

    fun resetKnownWords(from: Int) { knownWords.clear(); sentenceCursor = from }

    /**
     * A sentence started playing: locate it in [fullText], record each word's char range and
     * start time (the tap-to-seek table, same idea as the News Digest app's knownWords), and,
     * for saved documents, remember where we are for resume.
     */
    fun noteSentence(sentence: String, words: List<WordTiming>, startMs: Long) {
        val full = fullText
        val tokens = sentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val from = sentenceCursor.coerceIn(0, full.length)
        var at = full.indexOf(sentence, from)
        if (at < 0) {
            val re = Regex(tokens.take(8).joinToString("\\s+") { Regex.escape(it) })
            at = (re.find(full, from) ?: re.find(full))?.range?.first ?: return
        }
        sentenceCursor = at + 1
        val limit = (at + sentence.length + 16).coerceAtMost(full.length)
        var pos = at
        for (w in words) {
            val word = w.word.trim()
            if (word.isEmpty()) continue
            val i = full.indexOf(word, pos)
            if (i < 0 || i + word.length > limit) continue
            knownWords.add(KnownWord(i, i + word.length, startMs + w.startMs))
            pos = i + word.length
        }
        if (persisting) {
            offset = at
            prefs?.edit()?.putInt("offset", at)?.apply()
        }
    }

    fun begin(text: String) {
        sections.clear()
        fullText = text
        sentence = ""
        words = emptyList()
        wordIdx = -1
        persisting = false
        sessionBase = 0
        resetKnownWords(0)
    }
}
