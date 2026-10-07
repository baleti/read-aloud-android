package dev.local.readaloud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Reads like Google Assistant does (asked for 2026-10-07): start from where
 * the screen is NOW, speak what's visible at once, then scroll steadily as
 * the audio plays and keep reading until the content ends - instead of
 * capturing the whole page/thread up front (which made long threads slow to
 * start and brittle). Per profile it also expands collapsed emails / "more
 * replies" as it comes to them.
 *
 * One playback session for the whole read: each screenful is fed through
 * TtsSpeaker.speak(continueSession=true, keepOpen=true); this loop paces
 * itself by the audio buffer (scrolls when the audio is about to run out)
 * so the highlight on screen stays on what's being spoken. Section start
 * times are recorded in ReadAlongState.sections for the skip-section buttons.
 */
object ScrollReader {
    private const val TAG = "ScrollReader"
    private const val MAX_SCREENS = 150
    private const val MAX_WALL_CLOCK_MS = 60 * 60 * 1000L
    private const val SCROLL_WHEN_BUFFER_BELOW_MS = 3000L
    private const val SETTLE_AFTER_SCROLL_MS = 600L
    private const val RECENT_LINES = 300

    fun run(
        service: ReadAloudAccessibilityService, pkg: String, profile: AppProfile, label: String, generation: Int,
        // Gmail's inbox sequence reads email after email: don't return until this one has been SPOKEN,
        // not just scrolled through, or the sequence would navigate away mid-sentence.
        waitUntilDone: Boolean = false,
        // Spoken before the first screenful, like a voice assistant announcing what's next ("Next email.").
        intro: String? = null,
    ) {
        var tts: TtsPlaybackService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { tts = (binder as TtsPlaybackService.LocalBinder).service() }
            override fun onServiceDisconnected(name: ComponentName?) { tts = null }
        }
        service.bindService(Intent(service, TtsPlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
        val filter = profile.newStreamFilter()
        ReadAlongState.streaming = true
        try {
            loop(service, pkg, profile, filter, label, generation, intro) { tts }
            val tail = filter.finish().joinToString("\n").trim()
            if (tail.isNotBlank() && !service.isSuperseded(generation) && tts?.hasActiveSession() == true) {
                TtsSpeaker.speak(service, label, tail, continueSession = true, keepOpen = true)
            }
        } finally {
            ReadAlongState.streaming = false
            try { TtsSpeaker.finishOpenSession(service) } catch (_: Exception) {}
            if (waitUntilDone) {
                var waited = 0L
                while (!service.isSuperseded(generation) && tts?.hasActiveSession() == true && waited < 30 * 60 * 1000L) {
                    Thread.sleep(300); waited += 300
                }
            }
            try { service.unbindService(conn) } catch (_: Exception) {}
        }
    }

    private fun loop(
        service: ReadAloudAccessibilityService, pkg: String, profile: AppProfile, filter: StreamFilter, label: String,
        generation: Int, intro: String?, tts: () -> TtsPlaybackService?,
    ) {
        val recent = LinkedHashSet<String>()
        var first = true
        var stagnant = 0
        var screens = 0
        val started = System.currentTimeMillis()

        while (!service.isSuperseded(generation) && screens < MAX_SCREENS && System.currentTimeMillis() - started < MAX_WALL_CLOCK_MS) {
            var root = service.findForegroundWithRetry(pkg)?.second ?: break
            // Expand whatever collapsed thing is on screen, one at a time (bounded per screen).
            var guard = 0
            while (guard++ < 4 && !service.isSuperseded(generation) && profile.expandVisible(service, root)) {
                root = service.foregroundRoot()?.second ?: break
            }
            val lines = profile.screenLines(service, root)

            // Lines already spoken (the overlap between consecutive screens, sticky headers): drop the leading run.
            var drop = 0
            while (drop < lines.size && lines[drop] in recent) drop++
            val fresh = lines.drop(drop)
            for (l in lines) { recent.remove(l); recent.add(l) }
            while (recent.size > RECENT_LINES) recent.remove(recent.first())

            val toSpeak = filter.feed(fresh)
            var text = toSpeak.joinToString("\n") { it.replace(EmailCleaner.HEADER_MARK, "") }.trim()
            if (first && text.isNotBlank() && intro != null) text = "$intro\n$text"
            Log.i(TAG, "screen $screens: ${lines.size} lines, ${fresh.size} fresh, speaking ${text.length} chars; headers: ${toSpeak.filter { it.startsWith("From: ") || it.startsWith(EmailCleaner.HEADER_MARK) }.joinToString(" | ") { it.replace(EmailCleaner.HEADER_MARK, "").take(60) }}")
            if (fresh.isEmpty()) stagnant++ else stagnant = 0
            if (text.isNotBlank()) {
                if (first) service.toastReading(label)
                TtsSpeaker.speak(service, label, text, continueSession = !first, keepOpen = true)
                first = false
            } else if (first && stagnant >= 2) {
                service.toastNothing()
                return
            }
            if (stagnant >= 3) break
            if (first) { screens++; if (!scrollOnce(service, root)) break; Thread.sleep(SETTLE_AFTER_SCROLL_MS); continue }

            // Pace: scroll only once the audio is nearly out, so the screen stays on what's being spoken.
            while (!service.isSuperseded(generation)) {
                val s = tts() ?: break
                if (!s.hasActiveSession()) return // stopped by the user
                if (s.bufferedAheadMs() <= SCROLL_WHEN_BUFFER_BELOW_MS) break
                Thread.sleep(200)
            }
            if (service.isSuperseded(generation)) return

            val current = service.foregroundRoot()?.second ?: break
            if (!scrollOnce(service, current)) { stagnant++; if (stagnant >= 2) break } else Thread.sleep(SETTLE_AFTER_SCROLL_MS)
            screens++
        }
        Log.i(TAG, "finished: $screens screens, stagnant=$stagnant")
    }

    private fun scrollOnce(service: ReadAloudAccessibilityService, root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val target = AccessibilityTree.largestScrollable(root) ?: return false
        return service.scrollForward(target)
    }
}
