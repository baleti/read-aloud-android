package dev.local.readaloud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * The TTS handoff, extracted out of ReadAloudAccessibilityService so
 * RedditShareActivity can reuse it too (see that class's own doc for why
 * Reddit needs a second, non-accessibility entry point at all) without
 * duplicating the /tts/stream protocol handling in two places.
 */
object TtsSpeaker {
    private const val TAG = "TtsSpeaker"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Blocks until the whole text has been queued for playback -- call
     * from a background thread, same as ReadAloudAccessibilityService's
     * own callers already do. */
    fun speak(context: Context, title: String, text: String) {
        var ttsService: TtsPlaybackService? = null
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                ttsService = (binder as TtsPlaybackService.LocalBinder).service()
                bound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ttsService = null
                bound = false
            }
        }

        context.bindService(Intent(context, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        var waitedMs = 0
        while (!bound && waitedMs < 3000) { Thread.sleep(50); waitedMs += 50 }
        val svc = ttsService
        if (svc == null) {
            Log.e(TAG, "couldn't reach TtsPlaybackService")
            try { context.unbindService(connection) } catch (_: Exception) {}
            return
        }

        try {
            context.startForegroundService(Intent(context, TtsPlaybackService::class.java))
        } catch (e: Throwable) {
            Log.e(TAG, "startForegroundService(TtsPlaybackService) failed", e)
            try { context.unbindService(connection) } catch (_: Exception) {}
            return
        }

        // "Generating audio..." overlay while nothing is queued to play --
        // asked for explicitly 2026-09-13: the first sentence plays almost
        // instantly but a later one can take ~10s to synthesize, which
        // reads as the app having silently frozen with no indication
        // anything is still happening. Shown from ReadAloudAccessibilityService's
        // own context (the only one of this app's contexts allowed to add
        // a TYPE_ACCESSIBILITY_OVERLAY window) regardless of whether THIS
        // particular read was triggered from there or from
        // RedditShareActivity -- the service singleton is always available
        // whenever the accessibility service is enabled at all, which is
        // already a precondition for anything in this app to work.
        OverlayIndicator.show("Read Aloud: generating audio…")
        svc.setListener(object : TtsPlaybackService.HighlightListener {
            override fun onSentenceStart(text: String, words: List<WordTiming>, startMs: Long) {
                OverlayIndicator.hide()
            }
            override fun onSentenceEnd() {
                OverlayIndicator.show("Read Aloud: generating audio…")
            }
            override fun onQueueIdle() {
                OverlayIndicator.hide()
            }
        })

        svc.startSession(title)
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        svc.setEstimatedDuration((wordCount / (160.0 / 60.0) * 1000).toLong())

        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val ws = WebSocketClient(
            Settings.getHost(context),
            Settings.getTtsPort(context),
            "/tts/stream",
            mapOf("X-Peer-Agent" to "1"),
        )

        // Tells the server how far playback has actually gotten, every
        // 500ms, so it knows how far ahead of that it's safe to keep
        // synthesizing (see server.py's TTS_LOOKAHEAD_CAP_MS) -- asked for
        // explicitly 2026-09-13 ("smooth it out so subsequent sentences
        // keep playing while the rest gets synthesized"). Missing
        // entirely until now: this object only ever sent the initial
        // request and consumed sentences as they arrived, with no
        // position feedback loop -- newsdigest-android's own
        // ReadAloudController (this protocol's original implementation)
        // has always needed this same loop, just never got ported over
        // when this file split the handoff out. Without it the server has
        // no way to know playback is progressing at all and caps its own
        // lookahead near zero, which is exactly the "first sentence
        // instant, second sentence a 10s wait" symptom this was reported
        // against -- not something a client-side buffer trick can paper
        // over, since the server itself was the one holding back.
        val streamStartPositionMs = svc.getPositionMs()
        fun reportPosition() {
            if (!active.get()) return
            val playedMs = (svc.getPositionMs() - streamStartPositionMs).coerceAtLeast(0)
            try {
                ws.sendText(JSONObject().apply { put("type", "position"); put("played_ms", playedMs) }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed(::reportPosition, 500)
        }

        ws.connect(object : WebSocketClient.Listener {
            private var pendingMeta: JSONObject? = null

            override fun onOpen() {
                ws.sendText(
                    JSONObject().apply {
                        put("text", text)
                        put("engine", Settings.getTtsEngine(context))
                        Settings.getTtsVoice(context)?.let { put("voice", it) }
                    }.toString(),
                )
                mainHandler.post(::reportPosition)
            }

            override fun onText(msg: String) {
                val obj = JSONObject(msg)
                when (obj.optString("type")) {
                    "sentence" -> pendingMeta = obj
                    "done" -> { active.set(false); svc.endSession(); ws.close() }
                    "error" -> {
                        Log.e(TAG, "server error: ${obj.optString("message")}")
                        active.set(false)
                        svc.endSession()
                        ws.close()
                    }
                }
            }

            override fun onBinary(data: ByteArray) {
                val meta = pendingMeta ?: return
                val words = mutableListOf<WordTiming>()
                meta.optJSONArray("words")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i)
                        words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                    }
                }
                svc.enqueueSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
            }

            override fun onFailure(error: Throwable) {
                active.set(false)
                Log.e(TAG, "websocket failed", error)
                OverlayIndicator.hide()
            }
            override fun onClosed() { active.set(false) }
        })

        try { context.unbindService(connection) } catch (_: Exception) {}
    }
}
