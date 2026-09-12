package dev.local.readaloud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
        svc.startSession(title)
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        svc.setEstimatedDuration((wordCount / (160.0 / 60.0) * 1000).toLong())

        val ws = WebSocketClient(
            Settings.getHost(context),
            Settings.getTtsPort(context),
            "/tts/stream",
            mapOf("X-Peer-Agent" to "1"),
        )
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
            }

            override fun onText(msg: String) {
                val obj = JSONObject(msg)
                when (obj.optString("type")) {
                    "sentence" -> pendingMeta = obj
                    "done" -> { svc.endSession(); ws.close() }
                    "error" -> {
                        Log.e(TAG, "server error: ${obj.optString("message")}")
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

            override fun onFailure(error: Throwable) { Log.e(TAG, "websocket failed", error) }
            override fun onClosed() {}
        })

        try { context.unbindService(connection) } catch (_: Exception) {}
    }
}
