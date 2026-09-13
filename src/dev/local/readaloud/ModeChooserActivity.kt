package dev.local.readaloud

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The chooser shown when the detected app's profile offers more than one
 * mode for the current screen (asked for explicitly 2026-09-13: Gmail
 * should offer "this email"/"onwards"/"backwards" when an email is open).
 * Styled to match dictate-android's own action-menu card exactly (same
 * Theme.kt palette/helpers) - asked for explicitly, since this is
 * effectively a second instance of the same kind of menu, just launched
 * from ReadAloudAccessibilityService itself rather than dictate-android
 * (which has no way to know in advance whether the foreground app's
 * profile offers more than one mode).
 *
 * No enter/exit transition animation, on either this Activity or the one
 * finish() returns to - asked for explicitly ("no animation transitions
 * between these"): ActivityOptions.makeCustomAnimation(0,0) on the
 * startActivity() call in ReadAloudAccessibilityService.launchModeChooser(),
 * and overridePendingTransition(0,0) here right after finish().
 */
class ModeChooserActivity : Activity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE_IDS = "mode_ids"
        const val EXTRA_MODE_LABELS = "mode_labels"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val ids = intent.getStringArrayListExtra(EXTRA_MODE_IDS)
        val labels = intent.getStringArrayListExtra(EXTRA_MODE_LABELS)
        if (pkg == null || ids == null || labels == null || ids.size != labels.size || ids.isEmpty()) {
            finish()
            return
        }

        val dp = { v: Int -> Theme.dp(this, v) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(Theme.surface, this@ModeChooserActivity, radiusDp = 16, strokeColor = Theme.primary)
            isClickable = true // swallows taps so they don't fall through to root's dismiss-on-click
        }
        for ((i, id) in ids.withIndex()) {
            if (i > 0) {
                val divider = View(this)
                divider.setBackgroundColor(Theme.primary and 0x33FFFFFF.toInt())
                card.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(18))
                isClickable = true
                background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, this@ModeChooserActivity, radiusDp = 0))
                setOnClickListener {
                    ReadAloudAccessibilityService.instance?.startReadingWithMode(pkg, id)
                    dismiss()
                }
            }
            val label = TextView(this).apply {
                text = labels[i]
                textSize = 16f
                setTextColor(Theme.onBackground)
            }
            row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        cardParams.gravity = Gravity.CENTER
        root.addView(card, cardParams)
        setContentView(root)
    }

    private fun dismiss() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
