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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    fun show(text: String) {
        mainHandler.post {
            val service = ReadAloudAccessibilityService.instance ?: return@post
            val existing = view
            if (existing != null) {
                existing.text = text
                return@post
            }
            val tv = TextView(service).apply {
                this.text = text
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
            } catch (e: Throwable) {
                Log.e(TAG, "couldn't add overlay", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val service = ReadAloudAccessibilityService.instance
            val existing = view ?: return@post
            view = null
            if (service == null) return@post
            try {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(existing)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't remove overlay: ${e.message}")
            }
        }
    }
}
