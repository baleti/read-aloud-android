package dev.local.readaloud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Google-Assistant-style read-along drawn ON TOP of whatever app is being
 * read (asked for 2026-10-07: "a media control overlay on top of the
 * screen rather than a separate screen, and it highlights the portions
 * of text it reads on the existing activity"):
 *
 *  - a compact control bar (scrubber, rewind/play-pause/forward, speed,
 *    expand to the full PlayerActivity, stop), and
 *  - a full-screen, touch-transparent highlight layer that paints the
 *    sentence being spoken (and the current word) over that sentence's
 *    own text in the app underneath.
 *
 * The highlight needs no per-app code: each refresh walks the foreground
 * app's visible accessibility nodes, concatenates their text with all
 * whitespace removed (so "speech" + "." matches "speech."), finds the
 * spoken sentence in that, and paints the covered nodes' bounds - or
 * just the covered characters' bounds, via EXTRA_DATA_TEXT_CHARACTER_
 * LOCATION, where a node holds more than the sentence. If the sentence
 * isn't on screen any more it scrolls the main scrollable one page.
 *
 * Both windows are TYPE_ACCESSIBILITY_OVERLAY, which only this
 * AccessibilityService's own context may add (same as OverlayIndicator).
 */
object ReadOverlay {
    private const val TAG = "ReadOverlay"
    private const val SKIP_MS = 15_000L

    private class Entry(val node: AccessibilityNodeInfo, val text: String, val start: Int, val end: Int)

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("ReadOverlayWorker").apply { start() }
    private val worker = Handler(workerThread.looper)

    private var a11y: ReadAloudAccessibilityService? = null
    private var tts: TtsPlaybackService? = null
    private var bound = false
    private var wm: WindowManager? = null
    private var highlight: HighlightView? = null
    private var controls: LinearLayout? = null
    private var hidden = false
    private var idleTicks = 0
    private var dragging = false

    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: ImageView
    private lateinit var speedBtn: TextView

    // worker-side state
    @Volatile private var refreshPending = false
    @Volatile private var eventSeq = 0
    private var lastSentence = ""
    private var lastWordIdx = -2
    private var lastRefreshMs = 0L
    private var lastFoundMs = 0L
    private var lastScrollMs = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tts = (binder as TtsPlaybackService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) { tts = null }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (controls == null) return
            syncControls()
            requestRefresh()
            main.postDelayed(this, 250)
        }
    }

    // ---- lifecycle ----

    fun show(service: ReadAloudAccessibilityService) {
        main.post {
            if (controls != null) { hidden = false; setVisible(true); return@post }
            a11y = service
            wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                highlight = HighlightView(service)
                wm!!.addView(highlight, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START })
                controls = buildControls(service)
                wm!!.addView(controls, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.BOTTOM; y = Theme.dp(service, 56) })
            } catch (e: Throwable) {
                Log.e(TAG, "couldn't add overlay windows", e)
                hide()
                return@post
            }
            if (!bound) {
                bound = service.bindService(Intent(service, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            idleTicks = 0
            lastSentence = ""; lastWordIdx = -2
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(tick)
            val w = wm
            try { highlight?.let { w?.removeView(it) } } catch (_: Exception) {}
            try { controls?.let { w?.removeView(it) } } catch (_: Exception) {}
            highlight = null; controls = null
            if (bound) { try { a11y?.unbindService(connection) } catch (_: Exception) {}; bound = false }
            tts = null
        }
    }

    /** PlayerActivity (the full-text view) takes over while it's open. */
    fun setSuppressed(suppressed: Boolean) {
        main.post { hidden = suppressed; setVisible(!suppressed) }
    }

    private fun setVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        controls?.visibility = v
        highlight?.visibility = v
    }

    // ---- controls ----

    private fun buildControls(ctx: Context): LinearLayout {
        val dp = { v: Int -> Theme.dp(ctx, v) }
        fun icon(name: String, size: Int, onClick: () -> Unit) = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxWidth = dp(size); maxHeight = dp(size); minimumHeight = dp(48)
            isClickable = true
            background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, ctx, radiusDp = 24))
            setIcon(this, name)
            setOnClickListener { onClick() }
        }
        posView = TextView(ctx).apply { textSize = 11f; setTextColor(Theme.muted); text = "0:00" }
        durView = TextView(ctx).apply { textSize = 11f; setTextColor(Theme.muted); text = "0:00" }
        seekBar = SeekBar(ctx).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) posView.text = fmt(p.toLong() * duration() / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    tts?.seekTo(sb.progress.toLong() * duration() / 1000)
                }
            })
        }
        val scrub = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(posView)
            addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
            addView(durView)
        }
        playPause = icon("ic_play", 36) { tts?.let { if (it.isPlaying()) it.pause() else it.resume() } }
        speedBtn = TextView(ctx).apply {
            text = "1x"; textSize = 15f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER
            minHeight = dp(48); minWidth = dp(44)
            setOnClickListener { showSpeedPicker(ctx, this) }
        }
        val expand = TextView(ctx).apply {
            text = "⤢"; textSize = 20f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER
            minHeight = dp(48); minWidth = dp(44)
            setOnClickListener { PlayerActivity.launch(ctx) }
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            val w = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(speedBtn, w())
            addView(icon("ic_prev_section", 24) { skipSection(-1) }, w())
            addView(icon("ic_rewind", 28) { seekBy(-SKIP_MS) }, w())
            addView(playPause, w())
            addView(icon("ic_forward", 28) { seekBy(SKIP_MS) }, w())
            addView(icon("ic_next_section", 24) { skipSection(1) }, w())
            addView(expand, w())
            addView(icon("ic_stop", 24) { stopAll(ctx) }, w())
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(0xF2201A17.toInt(), ctx, radiusDp = 18, strokeColor = Theme.outlineVariant)
            setPadding(dp(12), dp(8), dp(12), dp(4))
            elevation = dp(8).toFloat()
            addView(scrub)
            addView(row)
        }.also {
            (it.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(dp(12), 0, dp(12), 0)
        }
    }

    private fun stopAll(ctx: Context) {
        Thread { TtsSpeaker.stopCurrent(ctx.applicationContext) }.apply { isDaemon = true; start() }
        hide()
    }

    private fun duration(): Long = tts?.getDisplayDurationMs()?.coerceAtLeast(1) ?: 1
    private fun seekBy(delta: Long) {
        val s = tts ?: return
        s.seekTo((s.getPositionMs() + delta).coerceIn(0, duration()))
    }

    /** Jump to the start of the next/previous streamed section (a screenful, or a message/comment run).
     * Past the last enqueued section it lands on the last sentence, which makes the reader fetch more. */
    private fun skipSection(direction: Int) {
        val s = tts ?: return
        val pos = s.getPositionMs()
        val starts = synchronized(ReadAlongState.sections) { ReadAlongState.sections.sorted() }
        val target = if (direction > 0) {
            starts.firstOrNull { it > pos + 500 } ?: (s.enqueuedEndMs() - 1).coerceAtLeast(0)
        } else {
            val cur = starts.lastOrNull { it <= pos }
            if (cur != null && pos - cur > 3000) cur else starts.lastOrNull { it < (cur ?: pos) } ?: 0L
        }
        s.seekTo(target)
    }

    private fun syncControls() {
        val s = tts ?: return
        if (!s.hasActiveSession()) {
            if (++idleTicks > 6) hide()
            return
        }
        idleTicks = 0
        setIcon(playPause, if (s.isPlaying()) "ic_pause" else "ic_play")
        speedBtn.text = fmtSpeed(s.currentSpeed())
        if (!dragging) {
            val pos = s.getPositionMs(); val dur = duration()
            seekBar.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
            posView.text = fmt(pos); durView.text = fmt(dur) + (if (ReadAlongState.streaming) "+" else "")
        }
    }

    private fun showSpeedPicker(ctx: Context, anchor: View) {
        val s = tts ?: return
        val min = 0.5f; val step = 0.1f; val steps = 25
        val label = TextView(ctx).apply { textSize = 16f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER; text = fmtSpeed(s.currentSpeed()) }
        val bar = SeekBar(ctx).apply {
            max = steps
            progress = Math.round((s.currentSpeed() - min) / step).coerceIn(0, steps)
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    label.text = fmtSpeed(v)
                    if (fromUser) s.setPlaybackSpeed(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(Theme.surfaceContainer, ctx, strokeColor = Theme.outlineVariant)
            setPadding(Theme.dp(ctx, 20), Theme.dp(ctx, 16), Theme.dp(ctx, 20), Theme.dp(ctx, 12))
            addView(label)
            addView(bar, LinearLayout.LayoutParams(Theme.dp(ctx, 220), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
            .apply { elevation = Theme.dp(ctx, 8).toFloat() }
            .showAsDropDown(anchor, 0, -Theme.dp(ctx, 150))
    }

    private fun setIcon(iv: ImageView, name: String) {
        val ctx = iv.context
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id != 0) {
            iv.setImageDrawable(ctx.getDrawable(id))
            iv.setColorFilter(Theme.onBackground, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun fmtSpeed(speed: Float): String {
        val r = Math.round(speed * 100) / 100f
        return (if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()) + "x"
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
    }

    // ---- highlighting ----

    /** From the accessibility service: something changed on screen in the app being read. */
    fun onScreenEvent(type: Int) {
        if (controls == null) return
        val moved = type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val changed = moved || type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!changed) return
        eventSeq++
        main.post {
            // A highlight painted at the old position is wrong the moment the content moves: drop it,
            // then redraw from a fresh tree read once the movement settles.
            if (moved) highlight?.set(emptyList(), emptyList())
            main.removeCallbacks(settledRefresh)
            main.postDelayed(settledRefresh, 120)
        }
    }

    private val settledRefresh = Runnable {
        lastRefreshMs = 0L
        requestRefresh()
    }

    private fun requestRefresh() {
        if (hidden || refreshPending) return
        val sentence = ReadAlongState.sentence
        val wordIdx = ReadAlongState.wordIdx
        val changed = sentence != lastSentence || wordIdx != lastWordIdx
        val now = System.currentTimeMillis()
        // Re-run on a slow timer too, so the highlight follows the user scrolling.
        if (!changed && now - lastRefreshMs < 1000) return
        if (sentence.isBlank()) return
        refreshPending = true
        val seqAtStart = eventSeq
        worker.post {
            try { refresh(sentence, wordIdx) } catch (e: Throwable) { Log.w(TAG, "refresh failed: ${e.message}") }
            lastSentence = sentence; lastWordIdx = wordIdx; lastRefreshMs = System.currentTimeMillis()
            refreshPending = false
            // the screen moved while we were reading it: that result is stale, go again
            if (eventSeq != seqAtStart) main.post(settledRefresh)
        }
    }

    private fun compactOf(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (!c.isWhitespace() && c != ' ') sb.append(c)
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Entry>, sb: StringBuilder) {
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: (if (node.childCount == 0) node.contentDescription?.toString()?.takeIf { it.isNotBlank() } else null)
        if (text != null) {
            val c = compactOf(text)
            if (c.isNotEmpty()) {
                out.add(Entry(node, text, sb.length, sb.length + c.length))
                sb.append(c)
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, sb) }
    }

    private fun refresh(sentence: String, wordIdx: Int) {
        val a = a11y ?: return
        val (_, root) = a.foregroundRoot() ?: run { paint(emptyList(), null); return }
        val entries = ArrayList<Entry>()
        val screen = StringBuilder()
        collect(root, entries, screen)
        val needle = compactOf(sentence)
        if (needle.isEmpty()) return
        val screenStr = screen.toString()
        var at = screenStr.indexOf(needle)
        var matchLen = needle.length
        if (at < 0 && needle.length > 24) { at = screenStr.indexOf(needle.take(24)); matchLen = 24 }
        if (at < 0) {
            paint(emptyList(), null)
            return
        }
        lastFoundMs = System.currentTimeMillis()
        val matchEnd = at + matchLen
        val sentenceRects = ArrayList<RectF>()
        for (e in entries) {
            if (e.end <= at || e.start >= matchEnd) continue
            val from = maxOf(at, e.start) - e.start
            val to = minOf(matchEnd, e.end) - e.start
            val whole = from == 0 && to == e.end - e.start
            sentenceRects.addAll(rectsFor(e, from, to, whole))
        }
        // current word
        var wordRects: List<RectF>? = null
        val words = ReadAlongState.words
        if (wordIdx in words.indices) {
            var pos = 0
            var range: IntRange? = null
            for (i in 0..wordIdx) {
                val w = words[i].word.trim()
                if (w.isEmpty()) continue
                val p = sentence.indexOf(w, pos)
                if (p < 0) { range = null; continue }
                range = p until (p + w.length)
                pos = p + w.length
            }
            range?.let { r ->
                val ws = at + compactOf(sentence.substring(0, r.first)).length
                val we = ws + compactOf(sentence.substring(r.first, r.last + 1)).length
                val rects = ArrayList<RectF>()
                for (e in entries) {
                    if (e.end <= ws || e.start >= we) continue
                    rects.addAll(rectsFor(e, maxOf(ws, e.start) - e.start, minOf(we, e.end) - e.start, false))
                }
                wordRects = rects
            }
        }
        paint(sentenceRects, wordRects)
    }

    /** Bounds for compact characters [from, to) of an entry's text: the whole node
     * when fully covered or when the app can't give per-character bounds. */
    private fun rectsFor(e: Entry, from: Int, to: Int, whole: Boolean): List<RectF> {
        val nodeRect = Rect().also { e.node.getBoundsInScreen(it) }
        if (whole || nodeRect.isEmpty) return listOf(RectF(nodeRect))
        // compact index -> index in the node's own text
        var seen = 0; var origFrom = -1; var origTo = e.text.length
        for ((i, c) in e.text.withIndex()) {
            if (c.isWhitespace() || c == ' ') continue
            if (seen == from && origFrom < 0) origFrom = i
            if (seen == to - 1) { origTo = i + 1; break }
            seen++
        }
        if (origFrom < 0) return listOf(RectF(nodeRect))
        return try {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, origFrom)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, origTo - origFrom)
            }
            if (!e.node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return listOf(RectF(nodeRect))
            val locs = e.node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, RectF::class.java)
                ?: return listOf(RectF(nodeRect))
            mergeLines(locs.filterNotNull())
        } catch (t: Throwable) {
            listOf(RectF(nodeRect))
        }
    }

    private fun mergeLines(rects: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        for (r in rects) {
            if (r.isEmpty) continue
            val last = out.lastOrNull()
            if (last != null && r.top < last.bottom && r.bottom > last.top && r.left <= last.right + 4f) last.union(r)
            else out.add(RectF(r))
        }
        return out
    }

    private fun paint(sentence: List<RectF>, word: List<RectF>?) {
        main.post { highlight?.set(sentence, word ?: emptyList()) }
    }

    private class HighlightView(context: Context) : View(context) {
        private val sentencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (Theme.primary and 0x00FFFFFF) or 0x40000000 }
        private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (Theme.primary and 0x00FFFFFF) or 0x99000000.toInt() }
        private var sentence: List<RectF> = emptyList()
        private var word: List<RectF> = emptyList()
        fun set(s: List<RectF>, w: List<RectF>) { sentence = s; word = w; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            for (r in sentence) canvas.drawRoundRect(r, 8f, 8f, sentencePaint)
            for (r in word) canvas.drawRoundRect(r, 8f, 8f, wordPaint)
        }
    }
}
