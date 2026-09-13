package dev.local.readaloud

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Same palette as dictate-android's own Theme.kt (a snapshot of the
 * desktop's generated scheme) - used here only by ModeChooserActivity, to
 * match dictate-android's own action-menu look rather than inventing a
 * second style for what's effectively the same kind of menu, just shown
 * from a different app.
 */
object Theme {
    const val surface = 0xFF2A221E.toInt()
    const val onBackground = 0xFFECE0DA.toInt()
    const val outline = 0xFF9F8D84.toInt()
    const val primary = 0xFFFFB68A.toInt()

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(color: Int, context: Context, radiusDp: Int = 10, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    fun rippleOn(base: GradientDrawable): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(outline and 0x66FFFFFF.toInt()), base, base)
}
