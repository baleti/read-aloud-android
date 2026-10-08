package dev.local.readaloud

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Same palette as digital-assistant-android's own Theme.kt (a snapshot of the
 * desktop's generated scheme) - used here only by ModeChooserActivity, to
 * match digital-assistant-android's own action-menu look rather than inventing a
 * second style for what's effectively the same kind of menu, just shown
 * from a different app.
 */
object Theme {
    const val surface = 0xFF2A221E.toInt()
    const val onBackground = 0xFFECE0DA.toInt()
    const val outline = 0xFF9F8D84.toInt()
    const val surfaceContainer = 0xFF3A2E28.toInt()
    const val outlineVariant = 0xFF52443C.toInt()
    const val muted = 0xFFB8A89F.toInt()
    const val onPrimary = 0xFF2A1700.toInt()
    const val bg = 0xFF1C1512.toInt()
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

    fun stylePrimaryButton(view: android.view.View, context: Context) {
        view.background = rippleOn(roundedDrawable(primary, context))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }
}
