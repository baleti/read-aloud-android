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
 *
 * Root-caused a real incident here 2026-09-13: a buggy GmailProfile
 * inbox-sequence loop fired far more TtsSpeaker.speak() calls than
 * intended, and this object had no way to actually ABORT an in-flight
 * one - stopCurrent() only stopped local TtsPlaybackService audio, never
 * closed the underlying websocket, so an old, superseded speak() call
 * kept its connection to the shared newsdigest-server open and kept
 * receiving (and paying synthesis CPU for) sentences nobody would ever
 * hear. Separately, TtsPlaybackService.setListener() only ever holds ONE
 * listener - a second speak() call replacing it silently orphaned the
 * first call's onQueueIdle()-driven "wait until done" latch, which could
 * then hang for its full timeout. Together: 31 leaked threads and an
 * 11-minute, 600% CPU runaway on host3's shared TTS server, serving both
 * this app and Claude Agents/News Digest. Fixed by (1) tracking the one
 * in-flight websocket in `activeWs` and force-closing it at the START of
 * every new speak()/stopCurrent() call, so a new invocation ALWAYS kills
 * the network side of whatever came before, not just local playback, and
 * (2) replacing the listener-callback latch with a plain poll of
 * TtsPlaybackService's own hasActiveSession() state, which self-corrects
 * every 300ms regardless of which listener is currently attached.
 */
object TtsSpeaker {
    private const val TAG = "TtsSpeaker"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var activeWs: WebSocketClient? = null

    // The phone's own TTS, used to bridge the gap before the server's first
    // sentence (cold Chatterbox can take 15-30s) - see speak(). Engine init
    // takes ~1-3s the first time, so call warmUp() early (the accessibility
    // service does at connect time); a speak() before it's ready just runs
    // without the bridge.
    @Volatile private var localTts: LocalTts? = null

    fun warmUp(context: Context) {
        if (localTts == null) localTts = try { LocalTts(context) } catch (e: Exception) { null }
    }

    /** Sentence char ranges within `text`, split like server.py's
     * split_sentences so local and server agree where sentences begin. */
    private fun splitLocalSentences(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in text.indices) {
            if (start < 0 && !text[i].isWhitespace()) start = i
            val boundary = start >= 0 && text[i].isWhitespace() && i > 0 &&
                (text[i - 1] in ".!?" || (text[i] == '\n' && i + 1 < text.length && text[i + 1] == '\n'))
            if (boundary) { out.add(start..(i - 1)); start = -1 }
        }
        if (start >= 0) out.add(start..text.trimEnd().length - 1)
        return out.filter { it.last >= it.first }
    }

    /** Same char-proportional word timings server.py's estimate_word_timings makes. */
    private fun estimateWordTimings(sentence: String, durationMs: Double): List<WordTiming> {
        val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val totalChars = words.sumOf { maxOf(it.length, 1) }
        var cursor = 0.0
        return words.map { w ->
            val dur = durationMs * maxOf(w.length, 1) / totalChars
            WordTiming(w, cursor.toInt(), (cursor + dur).toInt()).also { cursor += dur }
        }
    }

    private fun closeActiveWs() {
        activeWs?.let {
            try { it.close() } catch (_: Exception) {}
        }
        activeWs = null
    }

    /** Stops whatever's currently playing/queued AND closes its network
     * connection to the server, if any - called at the start of every
     * fresh Read Aloud invocation
     * (ReadAloudAccessibilityService.cancelCurrentAndBumpGeneration()) so
     * a new read always starts clean rather than layering onto whatever
     * was already going, on the server side as well as locally (see this
     * object's own doc for the incident that made the network half of
     * this matter). A brief bind-just-to-call-stopAll(), not a lasting
     * connection. */
    fun stopCurrent(context: Context) {
        closeActiveWs()
        var svc: TtsPlaybackService? = null
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                svc = (binder as TtsPlaybackService.LocalBinder).service()
                bound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            context.bindService(Intent(context, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
            var waitedMs = 0
            while (!bound && waitedMs < 500) { Thread.sleep(20); waitedMs += 20 }
            if (svc?.hasActiveSession() == true) svc?.stopAll()
        } catch (e: Exception) {
            Log.w(TAG, "stopCurrent: ${e.message}")
        } finally {
            try { context.unbindService(connection) } catch (_: Exception) {}
        }
    }

    /** Blocks until the whole text has been queued for playback -- call
     * from a background thread, same as ReadAloudAccessibilityService's
     * own callers already do. `waitUntilPlaybackDone` additionally blocks
     * (via a plain poll, not a callback latch - see this object's own
     * doc for why) until the audio has actually finished PLAYING, not
     * just been queued - needed by a profile like GmailProfile's inbox
     * sequence, which must not open/read the next email while this one's
     * audio is still going. Capped at 5 minutes per call regardless - no
     * single email/article read should legitimately take longer than
     * that, and a hard cap here is a second, independent backstop against
     * a repeat of the same runaway-loop incident. */
    fun speak(context: Context, title: String, text: String, waitUntilPlaybackDone: Boolean = false) {
        // A new speak() call ALWAYS supersedes whatever was in flight
        // before, on the network side too - see this object's own doc.
        closeActiveWs()

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
        // anything is still happening. Purely cosmetic - NOT used for the
        // wait-until-done logic below anymore (see this object's own doc
        // for why that moved to polling), so a later speak() call
        // replacing this listener before this one's session ends is
        // harmless: this session's own overlay calls simply stop, which
        // is correct anyway since it's been superseded.
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
        activeWs = ws

        // Tells the server how far playback has actually gotten, every
        // 500ms, so it knows how far ahead of that it's safe to keep
        // synthesizing (see server.py's TTS_LOOKAHEAD_CAP_MS) -- asked for
        // explicitly 2026-09-13 ("smooth it out so subsequent sentences
        // keep playing while the rest gets synthesized"). Stops itself
        // the moment this specific stream is superseded (checks `active`,
        // which closeActiveWs()/onClosed()/onFailure() all set false) -
        // this loop continuing to run against a closed/replaced ws was
        // part of the same incident (see this object's own doc).
        val streamStartPositionMs = svc.getPositionMs()
        fun reportPosition() {
            if (!active.get()) return
            val playedMs = (svc.getPositionMs() - streamStartPositionMs).coerceAtLeast(0)
            try {
                ws.sendText(JSONObject().apply { put("type", "position"); put("played_ms", playedMs) }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed(::reportPosition, 500)
        }

        // Local bridge: speak the first sentence on-device at once and ask
        // the server to start AFTER it; keep speaking locally only while the
        // server is behind (local audio nearly spent), stop for good the
        // moment a server sentence lands past what local covered, drop
        // server sentences for text local already spoke. feedLock keeps the
        // two producers in order. Mirrors newsdigest-android's
        // ReadAloudController.streamText().
        val feedLock = Any()
        var serverTookOver = false
        var localEnd = 0
        val local = localTts?.takeIf { it.isReady() }
        val localSentences = if (local != null) splitLocalSentences(text) else emptyList()
        val wsOffset = localSentences.firstOrNull()?.last?.plus(1) ?: 0
        var serverCursor = wsOffset

        if (local != null && localSentences.isNotEmpty()) {
            Thread {
                for ((i, range) in localSentences.withIndex()) {
                    if (!active.get() || serverTookOver) return@Thread
                    while (i > 0 && active.get() && !serverTookOver && svc.bufferedAheadMs() > 700) Thread.sleep(100)
                    if (!active.get() || serverTookOver) return@Thread
                    val sentence = text.substring(range)
                    val audio = local.synthesize(speakableForLocalTts(sentence)) ?: return@Thread
                    synchronized(feedLock) {
                        if (!active.get() || serverTookOver) return@Thread
                        val ms = audio.pcm.size / 2 * 1000.0 / audio.sampleRate
                        svc.enqueueSentence(sentence, estimateWordTimings(sentence, ms), audio.pcm, audio.sampleRate)
                        localEnd = range.last + 1
                    }
                }
                // Local covered everything (server dead or hopelessly slow):
                // end the session ourselves and release the blocking connect().
                val finish = synchronized(feedLock) { active.get() && !serverTookOver }
                if (finish) { active.set(false); svc.endSession(); ws.close() }
            }.apply { isDaemon = true; name = "TtsSpeakerLocal"; start() }
        }

        ws.connect(object : WebSocketClient.Listener {
            private var pendingMeta: JSONObject? = null

            override fun onOpen() {
                ws.sendText(
                    JSONObject().apply {
                        put("text", text.substring(wsOffset).trimStart())
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
                    "status" -> OverlayIndicator.addStatus(obj.optString("message"), obj.optInt("sentence"), obj.optInt("of"))
                    "done" -> {
                        // Everything was either enqueued by the server or
                        // already covered by local audio.
                        active.set(false); svc.endSession(); ws.close()
                    }
                    "error" -> {
                        Log.e(TAG, "server error: ${obj.optString("message")}")
                        if (localSentences.isNotEmpty() && !serverTookOver) return // local TTS keeps reading
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
                val sText = meta.getString("text")
                synchronized(feedLock) {
                    val at = text.indexOf(sText, serverCursor)
                    if (at >= 0) serverCursor = at + sText.length
                    if (at >= 0 && at + sText.length <= localEnd) return // local already spoke it
                    serverTookOver = true
                    svc.enqueueSentence(sText, words, data, meta.getInt("sample_rate"))
                }
            }

            override fun onFailure(error: Throwable) {
                Log.e(TAG, "websocket failed", error)
                if (localSentences.isNotEmpty() && !serverTookOver) return // local TTS keeps reading
                active.set(false)
                OverlayIndicator.hide()
            }
            override fun onClosed() { active.set(false) }
        })
        if (activeWs === ws) activeWs = null

        if (waitUntilPlaybackDone) {
            var waited = 0
            while (svc.hasActiveSession() && waited < 5 * 60 * 1000) {
                Thread.sleep(300)
                waited += 300
            }
        }

        try { context.unbindService(connection) } catch (_: Exception) {}
    }
}
