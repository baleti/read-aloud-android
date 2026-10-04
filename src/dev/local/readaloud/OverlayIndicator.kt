package dev.local.readaloud

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small floating banner ("Read Aloud: generating audio...") shown
 * whenever nothing is currently queued to play, so a synthesis gap (the
 * server can take several seconds per sentence, worse with Chatterbox
 * than Kokoro - see server.py) reads as "still working" instead of "the
 * app silently froze" (reported live 2026-09-13: the first sentence plays
 * almost instantly, a later one can take ~10s, with nothing on screen to
 * explain the wait).
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which needs a context that IS a live,
 * bound AccessibilityService - not the app's own Activity/Service
 * contexts, which would get a SecurityException. Routed through
 * ReadAloudAccessibilityService.instance specifically so this works
 * identically whichever entry point triggered the read
 * (TriggerReceiver's corner-swipe path, or RedditShareActivity's Share
 * path) - the accessibility service singleton is available either way,
 * since it being enabled is already a precondition for the app to do
 * anything at all.
 */
object OverlayIndicator {
    private const val TAG = "OverlayIndicator"
    // Anti-flicker, asked for explicitly 2026-10-04: only appear once the
    // wait has lasted SHOW_DELAY_MS (the split-second gaps between
    // sentences mid-read never show it), and once shown stay at least
    // MIN_VISIBLE_MS.
    private const val SHOW_DELAY_MS = 1200L
    private const val MIN_VISIBLE_MS = 1500L
    private const val MAX_LINES = 4

    private class Step(val text: String, val atNanos: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var baseText = ""
    private var waiting = false
    private var visibleSinceNanos = 0L
    // Steps of the ONE sentence playback is blocked on (server forwards only
    // that one): all but the last are finished, the last is in progress.
    private val steps = ArrayList<Step>()
    private var sentence = 0
    private var sentenceCount = 0

    private val showRunnable = Runnable { if (waiting) createView() }
    private val hideRunnable = Runnable { removeView() }
    private val tick = object : Runnable {
        override fun run() {
            if (view == null) return
            render()
            mainHandler.postDelayed(this, 500)
        }
    }

    /** The server's step-by-step `status` events (see server.py's
     * _progress). Kept even while the overlay is hidden, so it already has
     * the recent history when it appears. */
    fun addStatus(message: String, sentenceNo: Int = 0, of: Int = 0) {
        mainHandler.post {
            if (sentenceNo != sentence || sentenceNo == 0) steps.clear()
            sentence = sentenceNo
            sentenceCount = of
            if (steps.lastOrNull()?.text != message) steps.add(Step(message, System.nanoTime()))
            if (view != null) render()
        }
    }

    private fun render() {
        val tv = view ?: return
        val now = System.nanoTime()
        val sb = StringBuilder(baseText)
        if (sentence > 0) sb.append(" (sentence ").append(sentence).append(" of ").append(sentenceCount).append(')')
        val shown = steps.takeLast(MAX_LINES)
        for ((i, step) in shown.withIndex()) {
            val current = i == shown.lastIndex
            sb.append('\n').append(if (current) "▸ " else "✓ ").append(step.text)
            if (current) {
                val sec = (now - step.atNanos) / 1_000_000_000
                if (sec >= 2) sb.append(" (").append(sec).append("s)")
            }
        }
        tv.text = sb.toString()
    }

    fun show(text: String) {
        mainHandler.post {
            baseText = text
            mainHandler.removeCallbacks(hideRunnable)
            if (view != null) { waiting = true; render(); return@post }
            if (!waiting) mainHandler.postDelayed(showRunnable, SHOW_DELAY_MS)
            waiting = true
        }
    }

    private fun createView() {
        val service = ReadAloudAccessibilityService.instance ?: return
        if (view != null) return
        val tv = TextView(service).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC202020.toInt())
            textSize = 13f
            setPadding(28, 16, 28, 16)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 100
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(tv, params)
            view = tv
            visibleSinceNanos = System.nanoTime()
            render()
            mainHandler.postDelayed(tick, 500)
        } catch (e: Throwable) {
            Log.e(TAG, "couldn't add overlay", e)
        }
    }

    fun hide() {
        mainHandler.post {
            waiting = false
            mainHandler.removeCallbacks(showRunnable)
            if (view == null) return@post
            val visibleMs = (System.nanoTime() - visibleSinceNanos) / 1_000_000
            mainHandler.removeCallbacks(hideRunnable)
            if (visibleMs >= MIN_VISIBLE_MS) removeView()
            else mainHandler.postDelayed(hideRunnable, MIN_VISIBLE_MS - visibleMs)
        }
    }

    private fun removeView() {
        mainHandler.removeCallbacks(tick)
        val service = ReadAloudAccessibilityService.instance
        val existing = view ?: return
        view = null
        if (service == null) return
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(existing)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't remove overlay: ${e.message}")
        }
    }
}
