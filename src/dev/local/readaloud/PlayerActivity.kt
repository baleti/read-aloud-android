package dev.local.readaloud

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Simple music-player screen for whatever Read Aloud is currently reading
 * (asked for 2026-10-07, "like the news digest / claude agents apps"):
 * title, the sentence being spoken, a scrubber with elapsed/total, rewind
 * and forward 15s, play/pause, a speed slider and stop. Opened by
 * TtsSpeaker.speak() whenever a read starts; it only binds to the shared
 * TtsPlaybackService and polls it twice a second (the service's single
 * HighlightListener slot belongs to TtsSpeaker's overlay logic, so this
 * deliberately doesn't take it). Icons are res/drawable PNGs shared with
 * the sibling apps, tinted at load time like PlayerControlBar does.
 */
class PlayerActivity : Activity() {
    companion object {
        private const val TAG = "PlayerActivity"
        private const val SKIP_MS = 15_000L

        /** Best effort: a background launch can be refused, which must never break the read itself. */
        fun launch(context: Context) {
            try {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "couldn't open player: ${e.message}")
            }
        }
    }

    private var svc: TtsPlaybackService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var idleTicks = 0
    private var everActive = false
    private var restoredScrolled = false

    // Read-along: full text with the current sentence/word highlighted.
    private var shownText: String? = null
    private var spannable: Spannable? = null
    private var norm = ""
    private var normMap = IntArray(0)
    private var cursorNorm = 0
    private var shownSentence = ""
    private var shownWordIdx = -2
    private var sentStart = -1
    private var sentEnd = -1
    private val sentenceSpans = arrayOf<Any>(BackgroundColorSpan(0xFF4A3A30.toInt()), ForegroundColorSpan(Theme.onBackground))
    private val wordSpans = arrayOf<Any>(BackgroundColorSpan(Theme.primary), ForegroundColorSpan(Theme.onPrimary))
    private var lastUserScrollMs = 0L

    private lateinit var titleView: TextView
    private lateinit var sentenceView: TextView
    private lateinit var scroll: android.widget.ScrollView
    private lateinit var statusView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var playPause: ImageView
    private lateinit var speedBtn: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as TtsPlaybackService.LocalBinder).service()
            handler.post(tick)
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        val dp = { v: Int -> Theme.dp(this, v) }

        titleView = TextView(this).apply {
            textSize = 20f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER
            maxLines = 3; text = "Read Aloud"
        }
        sentenceView = TextView(this).apply {
            textSize = 17f; setTextColor(Theme.muted); gravity = Gravity.START
            setLineSpacing(0f, 1.25f)
        }
        statusView = TextView(this).apply {
            textSize = 12f; setTextColor(Theme.primary); gravity = Gravity.CENTER; visibility = View.GONE
        }
        scroll = android.widget.ScrollView(this).apply {
            addView(sentenceView)
            val taps = android.view.GestureDetector(this@PlayerActivity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean { seekToTap(e.x, e.y); return false }
            })
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) lastUserScrollMs = System.currentTimeMillis()
                taps.onTouchEvent(ev)
                false
            }
        }

        posView = TextView(this).apply { textSize = 12f; setTextColor(Theme.muted); text = "0:00" }
        durView = TextView(this).apply { textSize = 12f; setTextColor(Theme.muted); text = "0:00" }
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Theme.primary)
            thumbTintList = ColorStateList.valueOf(Theme.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) posView.text = fmt(progress.toLong() * duration() / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    svc?.seekTo(sb.progress.toLong() * duration() / 1000)
                }
            })
        }
        val scrubber = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(posView)
            addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            addView(durView)
        }

        playPause = icon("ic_play", 56) {
            svc?.let { if (it.hasActiveSession()) { if (it.isPlaying()) it.pause() else it.resume() } else resumeSaved() }
        }
        speedBtn = TextView(this).apply {
            text = "1x"; textSize = 16f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER
            minHeight = dp(48); minWidth = dp(48)
            setOnClickListener { showSpeedPicker(this) }
        }
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val w = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(speedBtn, w())
            addView(icon("ic_rewind", 40) { seekBy(-SKIP_MS) }, w())
            addView(playPause, w())
            addView(icon("ic_forward", 40) { seekBy(SKIP_MS) }, w())
            addView(icon("ic_stop", 32) { stopAndClose() }, w())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            setPadding(dp(24), dp(48), dp(24), dp(32))
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(24); bottomMargin = dp(24) })
            addView(scrubber, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        setContentView(root)
        // Reopened after the process died / reboot: show the saved document, ready to resume.
        if (ReadAlongState.restore(this)) titleView.text = ReadAlongState.title.ifBlank { "Read Aloud" }
        bindService(Intent(this, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() { super.onResume(); OverlayIndicator.suppress(true); ReadOverlay.setSuppressed(true) }
    override fun onPause() { OverlayIndicator.suppress(false); ReadOverlay.setSuppressed(false); super.onPause() }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unbindService(connection) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Tap on a word in a shared document: restart playback from that word. */
    private fun seekToTap(x: Float, y: Float) {
        if (!ReadAlongState.persisting && svc?.hasActiveSession() == true) return // screen reads can't be restarted from text
        val layout = sentenceView.layout ?: return
        val full = ReadAlongState.fullText
        if (full.isBlank()) return
        val ty = y + scroll.scrollY - sentenceView.top
        val line = layout.getLineForVertical(ty.toInt())
        var off = layout.getOffsetForHorizontal(line, x).coerceIn(0, full.length - 1)
        while (off > 0 && !full[off - 1].isWhitespace()) off--
        while (off < full.length - 1 && full[off].isWhitespace()) off++
        ReadAlongState.offset = off
        resumeSaved()
    }

    /** No live session: speak the saved document from where it was last left. */
    private fun resumeSaved() {
        val full = ReadAlongState.fullText
        if (full.isBlank()) return
        val off = ReadAlongState.offset.coerceIn(0, full.length)
        val title = ReadAlongState.title.ifBlank { "Shared text" }
        val ctx = applicationContext
        Thread {
            TtsSpeaker.speak(ctx, title, full.substring(off), highlightScreen = false, readAlongPrefix = full.substring(0, off))
        }.apply { isDaemon = true; start() }
    }

    private fun duration(): Long = svc?.getDisplayDurationMs()?.coerceAtLeast(1) ?: 1

    private fun seekBy(deltaMs: Long) {
        val s = svc ?: return
        s.seekTo((s.getPositionMs() + deltaMs).coerceIn(0, duration()))
    }

    private fun stopAndClose() {
        Thread { TtsSpeaker.stopCurrent(applicationContext) }.apply { isDaemon = true; start() }
        finish()
    }

    private fun refresh() {
        val s = svc ?: return
        val active = s.hasActiveSession()
        if (active) everActive = true
        if (!active && !everActive && ReadAlongState.fullText.isNotEmpty()) {
            // Viewing a restored document: stay open, show where it was left, play resumes it.
            setIcon(playPause, "ic_play")
            updateReadAlong()
            if (!restoredScrolled) {
                restoredScrolled = true
                sentenceView.post {
                    val layout = sentenceView.layout ?: return@post
                    val line = layout.getLineForOffset(ReadAlongState.offset.coerceIn(0, ReadAlongState.fullText.length))
                    scroll.scrollTo(0, (layout.getLineTop(line) - scroll.height / 3).coerceAtLeast(0))
                }
            }
            return
        }
        if (!active) {
            // Session over (finished or stopped elsewhere): show it, linger a bit, then close.
            setIcon(playPause, "ic_play")
            if (++idleTicks > 4) finish()
            return
        }
        idleTicks = 0
        OverlayIndicator.statusLine().let { statusView.text = it ?: ""; statusView.visibility = if (it == null) View.GONE else View.VISIBLE }
        titleView.text = s.currentTitle()
        updateReadAlong()
        setIcon(playPause, if (s.isPlaying()) "ic_pause" else "ic_play")
        speedBtn.text = formatSpeed(s.currentSpeed())
        if (!dragging) {
            val pos = s.getPositionMs(); val dur = duration()
            seekBar.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
            posView.text = fmt(pos); durView.text = fmt(dur) + (if (ReadAlongState.streaming) "+" else "")
        }
    }

    private fun collapse(t: String): Pair<String, IntArray> {
        val sb = StringBuilder(t.length)
        val map = IntArray(t.length + 1)
        var lastSpace = true
        for (i in t.indices) {
            val c = t[i]
            if (c.isWhitespace()) {
                if (!lastSpace) { map[sb.length] = i; sb.append(' '); lastSpace = true }
            } else { map[sb.length] = i; sb.append(c); lastSpace = false }
        }
        return sb.toString() to map
    }

    private fun updateReadAlong() {
        val full = ReadAlongState.fullText
        if (full.isEmpty()) return
        if (full !== shownText) {
            shownText = full
            spannable = SpannableString(full)
            sentenceView.setText(spannable, TextView.BufferType.SPANNABLE)
            spannable = sentenceView.text as Spannable // TextView keeps its own copy; spans must go on that one
            val (n, m) = collapse(full)
            norm = n; normMap = m; cursorNorm = 0
            shownSentence = ""; shownWordIdx = -2; sentStart = -1; sentEnd = -1
        }
        val sp = spannable ?: return
        val sentence = ReadAlongState.sentence
        if (sentence != shownSentence) {
            shownSentence = sentence; shownWordIdx = -2
            for (span in sentenceSpans) sp.removeSpan(span)
            for (span in wordSpans) sp.removeSpan(span)
            sentStart = -1; sentEnd = -1
            val (ns, _) = collapse(sentence)
            val needle = ns.trim()
            if (needle.isNotEmpty()) {
                var at = norm.indexOf(needle, cursorNorm)
                if (at < 0) at = norm.indexOf(needle)
                if (at < 0 && needle.length > 30) at = norm.indexOf(needle.take(30))
                if (at >= 0) {
                    cursorNorm = at
                    sentStart = normMap[at]
                    val lastN = (at + needle.length - 1).coerceAtMost(norm.length - 1)
                    sentEnd = (normMap[lastN] + 1).coerceAtMost(full.length)
                    for (span in sentenceSpans) sp.setSpan(span, sentStart, sentEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    scrollToSentence()
                }
            }
        }
        val wi = ReadAlongState.wordIdx
        if (wi != shownWordIdx && sentStart >= 0) {
            shownWordIdx = wi
            for (span in wordSpans) sp.removeSpan(span)
            val words = ReadAlongState.words
            if (wi in words.indices) {
                // words are the sentence's tokens in order; walk to the wi-th one
                val text = full.substring(sentStart, sentEnd)
                var pos = 0
                var range: IntRange? = null
                for (i in 0..wi) {
                    val w = words[i].word.trim()
                    if (w.isEmpty()) continue
                    val at = text.indexOf(w, pos)
                    if (at < 0) { range = null; continue }
                    range = at until (at + w.length)
                    pos = at + w.length
                }
                range?.let { r ->
                    for (span in wordSpans) sp.setSpan(span, sentStart + r.first, sentStart + r.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun scrollToSentence() {
        if (System.currentTimeMillis() - lastUserScrollMs < 5000) return
        sentenceView.post {
            val layout = sentenceView.layout ?: return@post
            val line = layout.getLineForOffset(sentStart.coerceAtLeast(0))
            scroll.smoothScrollTo(0, (layout.getLineTop(line) - scroll.height / 3).coerceAtLeast(0))
        }
    }

    private fun showSpeedPicker(anchor: View) {
        val s = svc ?: return
        val min = 0.5f; val step = 0.1f; val steps = 25
        val label = TextView(this).apply { textSize = 16f; setTextColor(Theme.onBackground); gravity = Gravity.CENTER; text = formatSpeed(s.currentSpeed()) }
        val bar = SeekBar(this).apply {
            max = steps
            progress = Math.round((s.currentSpeed() - min) / step).coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(Theme.primary)
            thumbTintList = ColorStateList.valueOf(Theme.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    label.text = formatSpeed(v)
                    if (fromUser) s.setPlaybackSpeed(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(Theme.surfaceContainer, this@PlayerActivity, strokeColor = Theme.outlineVariant)
            setPadding(Theme.dp(this@PlayerActivity, 20), Theme.dp(this@PlayerActivity, 16), Theme.dp(this@PlayerActivity, 20), Theme.dp(this@PlayerActivity, 12))
            addView(label)
            addView(bar, LinearLayout.LayoutParams(Theme.dp(this@PlayerActivity, 220), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
            .apply { elevation = Theme.dp(this@PlayerActivity, 8).toFloat() }
            .showAsDropDown(anchor, 0, -Theme.dp(this, 8) - Theme.dp(this, 120))
    }

    private fun icon(name: String, sizeDp: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        maxWidth = Theme.dp(this@PlayerActivity, sizeDp); maxHeight = Theme.dp(this@PlayerActivity, sizeDp)
        minimumHeight = Theme.dp(this@PlayerActivity, 56)
        isClickable = true
        background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, this@PlayerActivity, radiusDp = 28))
        setIcon(this, name)
        setOnClickListener { onClick() }
    }

    private fun setIcon(iv: ImageView, name: String) {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id != 0) {
            iv.setImageDrawable(getDrawable(id))
            iv.setColorFilter(Theme.onBackground, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun formatSpeed(speed: Float): String {
        val r = Math.round(speed * 100) / 100f
        return (if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()) + "x"
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
    }
}
