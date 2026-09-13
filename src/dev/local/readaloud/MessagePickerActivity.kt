package dev.local.readaloud

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Multi-select follow-up to ModeChooserActivity's "Read selected" option -
 * asked for explicitly 2026-09-13, for a Gmail thread Gmail itself groups
 * multiple messages into one conversation entry ("Michael, 6 messages...").
 * Lets the user pick WHICH of the thread's messages actually get read,
 * rather than always reading every one once a thread has more than one
 * (that's "Read all", handled directly by GmailProfile without this
 * Activity at all). A separate Activity rather than reusing
 * ModeChooserActivity's own layout: that one's rows each fire immediately
 * on tap (single-select-and-dismiss) - this one needs to accumulate a
 * selection across multiple taps before one confirm action.
 */
class MessagePickerActivity : Activity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABELS = "labels"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS)
        if (pkg == null || labels == null || labels.isEmpty()) {
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
            background = Theme.roundedDrawable(Theme.surface, this@MessagePickerActivity, radiusDp = 16, strokeColor = Theme.primary)
            isClickable = true // swallows taps so they don't fall through to root's dismiss-on-click
        }

        val checkboxes = mutableListOf<CheckBox>()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, text) in labels.withIndex()) {
            if (i > 0) {
                val divider = View(this)
                divider.setBackgroundColor(Theme.primary and 0x33FFFFFF.toInt())
                list.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
            val cb = CheckBox(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Theme.onBackground)
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
            checkboxes.add(cb)
            list.addView(cb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420)))

        val divider2 = View(this)
        divider2.setBackgroundColor(Theme.primary and 0x33FFFFFF.toInt())
        card.addView(divider2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val confirmRow = TextView(this).apply {
            text = "Read selected"
            textSize = 16f
            setTextColor(Theme.primary)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            isClickable = true
            background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, this@MessagePickerActivity, radiusDp = 0))
            setOnClickListener {
                val indices = checkboxes.withIndex().filter { it.value.isChecked }.map { it.index }
                if (indices.isEmpty()) {
                    Toast.makeText(this@MessagePickerActivity, "Select at least one message", Toast.LENGTH_SHORT).show()
                } else {
                    ReadAloudAccessibilityService.instance?.startReadingWithMode(pkg, "read_selected:" + indices.joinToString(","))
                    dismiss()
                }
            }
        }
        card.addView(confirmRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cardParams = FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT)
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
