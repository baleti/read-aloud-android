package dev.local.readaloud

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Caches the text extracted from PDF / DOCX / HTML files, keyed by a SHA-256 of the file's bytes
 * (the same file arrives under different content:// URIs, and an edited file hashes differently,
 * so a stale entry is never served). Only extractions that took at least [MIN_COST_MS] are stored:
 * anything faster is cheaper to redo than to look up. Reading position is a separate concern, see
 * [ReadHistory] (keyed by the text, so a cache hit resumes just like a fresh extraction).
 */
object DocCache {
    private const val MIN_COST_MS = 3_000L
    private const val MAX_FILES = 100

    private fun dir(ctx: Context) = File(ctx.applicationContext.cacheDir, "doctext").apply { mkdirs() }

    fun checksum(ctx: Context, uri: Uri): String? {
        return try {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { null }
    }

    fun get(ctx: Context, sum: String): String? {
        val f = File(dir(ctx), "$sum.txt")
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return f.readText()
    }

    fun put(ctx: Context, sum: String, text: String) {
        File(dir(ctx), "$sum.txt").writeText(text)
        dir(ctx).listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_FILES)?.forEach { it.delete() }
    }

    /** Runs [extract] unless [uri]'s content was extracted before; stores the result if it was slow. */
    fun getOrExtract(ctx: Context, uri: Uri, extract: () -> String): String {
        val sum = checksum(ctx, uri) ?: return extract()
        get(ctx, sum)?.let { return it }
        val t0 = System.currentTimeMillis()
        val text = extract()
        if (text.isNotBlank() && System.currentTimeMillis() - t0 >= MIN_COST_MS) {
            put(ctx, sum, text)
        }
        return text
    }
}
