package dev.local.readaloud

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Deliberately plain (no Theme.kt, unlike the sibling apps) -- this is a
 * once-in-a-while settings/status screen, not something meant to be
 * opened often; the real everyday entry point is digital-assistant-android's
 * long-press-power "Read Aloud" action, which never shows this activity
 * at all.
 */
class MainActivity : Activity() {
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var engineField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        root.addView(TextView(this).apply { text = "Read Aloud"; textSize = 22f })
        root.addView(
            TextView(this).apply {
                text = "Share text, a link, a PDF, DOCX or PPTX to Read Aloud from any app's share sheet."
                setPadding(0, dp(8), 0, dp(16))
            },
        )

        root.addView(TextView(this).apply { text = "TTS server (shared with News Digest)"; setPadding(0, dp(24), 0, dp(4)) })
        hostField = EditText(this).apply { hint = "host"; setText(Settings.getHost(this@MainActivity)) }
        root.addView(hostField)
        portField = EditText(this).apply {
            hint = "port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.getTtsPort(this@MainActivity).toString())
        }
        root.addView(portField)
        engineField = EditText(this).apply { hint = "engine (kokoro / chatterbox)"; setText(Settings.getTtsEngine(this@MainActivity)) }
        root.addView(engineField)

        if (ReadAlongState.hasSaved(this)) root.addView(
            Button(this).apply {
                text = "Open last document"
                setOnClickListener { PlayerActivity.launch(this@MainActivity) }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Save"
                setPadding(0, dp(8), 0, 0)
                setOnClickListener {
                    Settings.setHost(this@MainActivity, hostField.text.toString().trim())
                    portField.text.toString().trim().toIntOrNull()?.let { Settings.setTtsPort(this@MainActivity, it) }
                    Settings.setTtsEngine(this@MainActivity, engineField.text.toString().trim().ifBlank { Settings.DEFAULT_ENGINE })
                    Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                }
            },
        )

        setContentView(root)
    }
}
