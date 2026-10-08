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

    /** Track the spoken sentence's position so playback can resume there. */
    fun noteSentence(s: String) {
        if (!persisting) return
        val needle = s.trim().split(Regex("\\s+")).take(8).filter { it.isNotEmpty() }
        if (needle.isEmpty()) return
        val full = fullText
        // whitespace-tolerant search from the last known offset
        val re = Regex(needle.joinToString("\\s+") { Regex.escape(it) })
        val m = re.find(full, offset.coerceAtMost(full.length)) ?: re.find(full) ?: return
        offset = m.range.first
        prefs?.edit()?.putInt("offset", offset)?.apply()
    }

    fun begin(text: String) {
        sections.clear()
        fullText = text
        sentence = ""
        words = emptyList()
        wordIdx = -1
        persisting = false
    }
}
