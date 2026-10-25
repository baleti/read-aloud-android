package dev.local.readaloud

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream

/** PPTX -> text: each slide's paragraphs (a:t runs of ppt/slides/slideN.xml) in slide order, then its speaker notes. */
object PptxReader {
    private val SLIDE = Regex("""ppt/slides/slide(\d+)\.xml""")
    private val NOTES = Regex("""ppt/notesSlides/notesSlide(\d+)\.xml""")
    private val TOKEN = Regex("""<a:t(?:\s[^>]*)?>([^<]*)</a:t>|<a:br\s*/>|</a:p>""")

    fun extract(context: Context, uri: Uri): String {
        val slides = sortedMapOf<Int, String>()
        val notes = sortedMapOf<Int, String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val slide = SLIDE.matchEntire(e.name)?.groupValues?.get(1)?.toInt()
                    val note = NOTES.matchEntire(e.name)?.groupValues?.get(1)?.toInt()
                    if (slide != null) slides[slide] = paragraphs(zip.readBytes().toString(Charsets.UTF_8))
                    else if (note != null) notes[note] = paragraphs(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        } ?: return ""
        val sb = StringBuilder()
        for ((n, text) in slides) {
            // Notes slide numbers normally mirror slide numbers; a notes page's slide-number placeholder is a lone digit line.
            val note = notes[n]?.lines()?.filter { it.isNotBlank() && !it.trim().matches(Regex("\\d+")) }?.joinToString("\n")
            if (text.isBlank() && note.isNullOrBlank()) continue
            sb.append("Slide ").append(n).append(".\n").append(text)
            if (!note.isNullOrBlank()) sb.append("\n\nNotes: ").append(note)
            sb.append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun paragraphs(xml: String): String {
        val sb = StringBuilder()
        for (m in TOKEN.findAll(xml)) {
            when {
                m.value.startsWith("<a:t") && !m.value.startsWith("<a:tab") -> sb.append(unescape(m.groupValues[1]))
                m.value.startsWith("<a:br") -> sb.append('\n')
                else -> sb.append('\n')
            }
        }
        return sb.toString().replace(Regex("\n{2,}"), "\n").trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
