package dev.local.readaloud

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream

/** DOCX -> text: paragraphs of word/document.xml (w:t runs, tabs and line breaks), no styling. */
object DocxReader {
    fun extract(context: Context, uri: Uri): String {
        val xml = context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" }
                    ?.let { zip.readBytes().toString(Charsets.UTF_8) }
            }
        } ?: return ""
        val sb = StringBuilder()
        val tok = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab\s*/>|<w:br\s*/>|</w:p>""")
        for (m in tok.findAll(xml)) {
            val s = m.value
            when {
                s.startsWith("<w:t") && !s.startsWith("<w:tab") -> sb.append(unescape(m.groupValues[1]))
                s.startsWith("<w:tab") -> sb.append(' ')
                s.startsWith("<w:br") -> sb.append('\n')
                else -> sb.append("\n\n")
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
