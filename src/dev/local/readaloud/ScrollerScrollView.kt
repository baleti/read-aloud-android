package dev.local.readaloud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

// ScrollView with a draggable scroll thumb on its right edge - a port of the
// Claude Agents app's ScrollerListView so both apps scroll the same way.
// Never acts on DOWN: it engages only once the finger has moved past touch
// slop and mostly vertically, or on a genuine tap (UP with no real
// movement), so the start of an edge back-swipe (gesture navigation) doesn't
// scroll the text. Touches starting in the strip are consumed here, so they
// never reach the tap-to-seek handling either. The thumb shows while
// scrolling and fades out ~1.2s later.
class ScrollerScrollView(context: Context) : ScrollView(context) {
    private val density = context.resources.displayMetrics.density
    private val stripPx = 28 * density
    private val thumbW = 5 * density
    private val thumbMinH = 40 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var swallowing = false
    private var grabOffset = 0f
    private var visibleUntil = 0L
    private val hideTick = Runnable { invalidate() }

    init { isVerticalScrollBarEnabled = false }

    private fun contentH(): Int = getChildAt(0)?.height ?: 0
    private fun range(): Int = (contentH() - height).coerceAtLeast(0)
    private fun scrollable() = range() > 0

    private fun poke() {
        visibleUntil = System.currentTimeMillis() + 1200
        removeCallbacks(hideTick)
        postDelayed(hideTick, 1250)
        invalidate()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (scrollable()) poke()
    }

    private fun fraction(): Float = if (range() <= 0) 0f else (scrollY.toFloat() / range()).coerceIn(0f, 1f)

    private fun thumbH(): Float =
        (height.toFloat() * height / contentH().coerceAtLeast(1))
            .coerceIn(thumbMinH.coerceAtMost(height.toFloat()), height.toFloat())

    private fun thumbTop(): Float = fraction() * (height - thumbH())

    private fun fractionForThumbTop(top: Float): Float {
        val room = height - thumbH()
        return if (room <= 0f) 0f else (top / room).coerceIn(0f, 1f)
    }

    private fun scrollToFraction(f: Float) = scrollTo(0, (f.coerceIn(0f, 1f) * range()).toInt())

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowing = false
                tracking = scrollable() && ev.x >= width - stripPx
                dragging = false
                if (tracking) { downX = ev.x; downY = ev.y; return true }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        dragging = true
                        val top = thumbTop()
                        grabOffset = if (downY in top..(top + thumbH())) downY - top else thumbH() / 2
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        tracking = false // horizontal: a back swipe, not ours
                        swallowing = true
                    }
                }
                if (dragging) { scrollToFraction(fractionForThumbTop(ev.y - grabOffset)); poke() }
                return true
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                if (!dragging && abs(ev.y - downY) <= slop && abs(ev.x - downX) <= slop) {
                    scrollToFraction(fractionForThumbTop(ev.y - thumbH() / 2)); poke()
                }
                tracking = false; dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (tracking) { tracking = false; dragging = false; return true }
        }
        return if (!tracking && ev.actionMasked != MotionEvent.ACTION_DOWN && swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            true
        } else super.dispatchTouchEvent(ev)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!scrollable()) return
        if (!dragging && System.currentTimeMillis() > visibleUntil) return
        val top = thumbTop() // draw() is in the view's own (unscrolled) coordinates
        val right = width - 2 * density
        rect.set(right - thumbW, top, right, top + thumbH())
        paint.color = if (dragging) Theme.primary else Theme.outline
        paint.alpha = if (dragging) 230 else 160
        canvas.drawRoundRect(rect, thumbW / 2, thumbW / 2, paint)
    }
}
