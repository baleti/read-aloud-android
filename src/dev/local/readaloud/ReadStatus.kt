package dev.local.readaloud

import android.os.Handler
import android.os.Looper

/**
 * "Generating audio..." status shown in PlayerActivity's status line while playback is waiting on
 * the TTS server (a synthesis gap can last several seconds). Replaces the floating accessibility
 * banner that used to live in OverlayIndicator (removed with the screen-reading feature).
 */
object ReadStatus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var baseText = ""
    private var waiting = false
    private var sentence = 0
    private var sentenceCount = 0

    /** The server's `status` events; only the sentence counter is kept. */
    fun addStatus(@Suppress("UNUSED_PARAMETER") message: String, sentenceNo: Int = 0, of: Int = 0) {
        mainHandler.post { sentence = sentenceNo; sentenceCount = of }
    }

    fun show(text: String) { mainHandler.post { baseText = text; waiting = true } }

    fun hide() { mainHandler.post { waiting = false } }

    /** One-line status for PlayerActivity (main thread): null when nothing is being waited on. */
    fun statusLine(): String? {
        if (!waiting) return null
        val sb = StringBuilder(baseText)
        if (sentence > 0) sb.append(" (sentence ").append(sentence).append(" of ").append(sentenceCount).append(')')
        return sb.toString()
    }
}
