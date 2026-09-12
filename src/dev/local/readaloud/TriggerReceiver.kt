package dev.local.readaloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * The entry point dictate-android's AssistActivity calls into (see that
 * app's own action-menu entry for "Read Aloud"). Delegates straight to
 * the already-running accessibility service rather than starting a new
 * component -- see ReadAloudAccessibilityService's class doc for why: a
 * separate Service started from here via startForegroundService() was
 * confirmed live to get silently blocked by Android's foreground-service
 * background-start restriction.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = ReadAloudAccessibilityService.instance
        if (service == null) {
            Toast.makeText(context, "Enable Read Aloud in Settings > Accessibility first", Toast.LENGTH_LONG).show()
            return
        }
        service.startReading()
    }
}
