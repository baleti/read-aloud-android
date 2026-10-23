package dev.local.readaloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log

/**
 * PDF -> text for the Share / "Open with" sinks. PdfRenderer only draws pages (no text API), and
 * many PDFs (scanned, or fonts with a broken text layer) have no usable embedded text anyway, so
 * every page is rendered and run through the same on-device ML Kit OCR the Reddit profile uses.
 * Slow-ish (~1s a page) but uniform; nothing leaves the phone.
 */
object PdfReader {
    private const val TAG = "PdfReader"
    private const val TARGET_WIDTH = 1600
    private const val MAX_PAGES = 400

    /** Blocking; call off the main thread. [onProgress] gets (pagesDone, pageCount). */
    fun extract(context: Context, uri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val sb = StringBuilder()
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val n = minOf(renderer.pageCount, MAX_PAGES)
                for (i in 0 until n) {
                    renderer.openPage(i).use { page ->
                        val scale = TARGET_WIDTH.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(TARGET_WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = MlKitOcr.recognize(bmp, 20_000).trim()
                        bmp.recycle()
                        if (text.isNotEmpty()) sb.append(text).append("\n\n")
                    }
                    onProgress(i + 1, n)
                    Log.d(TAG, "page ${i + 1}/$n")
                }
            }
        }
        return sb.toString().trim()
    }
}
