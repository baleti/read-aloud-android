package dev.local.readaloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Deliberately plain (no Theme.kt, unlike the sibling apps) -- this is a
 * once-in-a-while settings/status screen, not something meant to be
 * opened often; the real everyday entry point is dictate-android's
 * long-press-power "Read Aloud" action, which never shows this activity
 * at all.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
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
                text = "Reads whatever screen is in front when invoked from Dictate's " +
                    "long-press-power menu. Reddit and Gmail get extra handling; every " +
                    "other app gets a best-effort generic read of the accessibility tree."
                setPadding(0, dp(8), 0, dp(16))
            },
        )

        statusView = TextView(this).apply { textSize = 14f }
        root.addView(statusView)

        root.addView(
            Button(this).apply {
                text = "Open Accessibility settings"
                setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Test: read this screen now"
                setPadding(0, dp(8), 0, 0)
                setOnClickListener {
                    // A real smoke test of the whole pipeline (accessibility
                    // query -> profile -> websocket -> playback) even though
                    // reading THIS app's own settings screen back is a silly
                    // thing to actually want -- the everyday path is always
                    // via TriggerReceiver from dictate-android instead.
                    val service = ReadAloudAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(this@MainActivity, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                    } else {
                        service.startReading()
                        Toast.makeText(this@MainActivity, "Reading…", Toast.LENGTH_SHORT).show()
                    }
                }
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

    override fun onResume() {
        super.onResume()
        val enabled = ReadAloudAccessibilityService.instance != null
        statusView.apply {
            text = if (enabled) "Accessibility service: ON" else "Accessibility service: OFF - tap below to enable it"
            gravity = Gravity.START
        }
    }
}
