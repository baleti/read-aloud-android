package dev.local.readaloud

import android.content.Context
import java.security.MessageDigest

/**
 * Remembers how far each document got, so opening the same file (or link, or
 * pasted text) again later resumes where the last read ended. Documents are
 * identified by a hash of their text rather than by URI: the same file arrives
 * under different content:// URIs from different apps. One preference per
 * document, "h_<sha1>" = "offset|length|lastReadMillis|title"; the least
 * recently read entries are pruned past [MAX_ENTRIES].
 */
object ReadHistory {
    private const val MAX_ENTRIES = 300
    /** Resuming this close to the end counts as finished: the next read starts over. */
    private const val FINISHED_TAIL = 200

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("readaloud_history", Context.MODE_PRIVATE)

    fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Char offset to resume [text] from, or 0 to start at the top (never read, or read to the end). */
    fun resumeOffset(ctx: Context, text: String): Int {
        val v = prefs(ctx).getString("h_" + key(text), null) ?: return 0
        val off = v.substringBefore('|').toIntOrNull() ?: return 0
        return if (off <= 0 || off >= text.length - FINISHED_TAIL) 0 else off
    }

    fun record(ctx: Context, key: String, offset: Int, length: Int, title: String) {
        prefs(ctx).edit().putString("h_$key", "$offset|$length|${System.currentTimeMillis()}|$title").apply()
    }

    /** A document was (re)started: stamp it as recently read and drop the oldest entries beyond the cap. */
    fun touch(ctx: Context, key: String, title: String, length: Int) {
        val p = prefs(ctx)
        val prev = p.getString("h_$key", null)?.substringBefore('|')?.toIntOrNull() ?: 0
        record(ctx, key, prev, length, title)
        val all = p.all.filterKeys { it.startsWith("h_") }
        if (all.size <= MAX_ENTRIES) return
        val stale = all.entries.sortedBy { (it.value as? String)?.split('|')?.getOrNull(2)?.toLongOrNull() ?: 0L }
            .take(all.size - MAX_ENTRIES)
        p.edit().apply { stale.forEach { remove(it.key) } }.apply()
    }
}
