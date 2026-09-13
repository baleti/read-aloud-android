package dev.local.readaloud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The universal base layer (see docs/design.md): on-demand access to
 * whatever window is currently in front, plus the two ways an AppProfile
 * can drive it (tap/click a node, scroll a container) when the content it
 * wants isn't sitting in the tree yet. Deliberately does NOT request
 * touch-exploration mode (unlike TalkBack) -- confirmed live 2026-09-12
 * that's what hijacks normal tap-to-activate into focus+double-tap
 * system-wide; this service stays completely inert until asked to read.
 *
 * Also owns the whole extraction-and-speak orchestration (startReading(),
 * below), rather than that living in its own separate Service the way an
 * earlier version of this had it: confirmed live 2026-09-12 that a
 * standalone ReadAloudService, started via startForegroundService() from
 * TriggerReceiver.onReceive(), never actually got its process attached
 * (ServiceRecord stuck at app=null, startForegroundCount=0, for minutes)
 * -- Android's foreground-service background-start restriction silently
 * blocking it, since a freshly-triggered BroadcastReceiver carries none of
 * the exemptions a real foreground context has. This service is already
 * alive and system-bound the entire time it's enabled (same as TalkBack,
 * same as dictate-android's own DictateAccessibilityService), so running
 * the work directly on a background thread here needs no new component
 * startup at all, and only the final handoff to TtsPlaybackService (a
 * real foreground service, for legitimate continuous media playback)
 * needs startForegroundService -- called from this already-running
 * process rather than a cold broadcast receiver, which is the one part
 * still to be confirmed live rather than assumed.
 */
class ReadAloudAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ReadAloudA11y"
        @Volatile var instance: ReadAloudAccessibilityService? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Entry point from TriggerReceiver -- runs entirely on a background
     * thread since it blocks (gesture waits, sleeps between scroll passes,
     * the websocket read loop) well past what either a BroadcastReceiver
     * or the main thread should ever be held up for. */
    fun startReading() {
        Thread {
            try {
                runExtraction()
            } catch (e: Throwable) {
                Log.e(TAG, "extraction crashed", e)
                toast("Read Aloud hit an error - see logcat")
            }
        }.apply { isDaemon = true; name = "ReadAloudExtract"; start() }
    }

    private fun runExtraction() {
        // The overlay that triggered this (dictate-android's AssistActivity)
        // may not have finished handing focus back to the real app yet --
        // same race DictateAccessibilityService's own doc already found and
        // solved the same way: a short retry loop rather than one fixed delay.
        var found: Pair<String, AccessibilityNodeInfo>? = null
        repeat(8) {
            found = foregroundRoot()
            if (found != null) return@repeat
            Thread.sleep(150)
        }
        val (pkg, root) = found ?: run { toast("Couldn't find a screen to read"); return }

        val profile = AppProfileRegistry.forPackage(pkg)
        val lines = try {
            if (profile.needsTouchExploration) {
                withTouchExplorationMode { profile.extract(this, root) }
            } else {
                profile.extract(this, root)
            }
        } catch (e: Exception) {
            Log.e(TAG, "profile ${profile.javaClass.simpleName} crashed, falling back to generic", e)
            try { GenericProfile.extract(this, root) } catch (_: Exception) { emptyList() }
        }
        val text = lines.joinToString("\n").trim()
        Log.i(TAG, "extracted ${text.length} chars: ${text.take(300)}${if (text.length > 300) "…" else ""}")
        if (text.isBlank()) { toast("Nothing readable found on screen"); return }
        toast("Reading ${labelFor(pkg)}…")
        TtsSpeaker.speak(this, labelFor(pkg), text)
    }

    /** Turns Android's system-wide touch-exploration mode on for the
     * duration of `block`, then off again -- see AppProfile.needsTouchExploration's
     * doc for why this is needed at all (Compose apparently only computes
     * full accessibility semantics while some service has this on) and why
     * it's scoped this narrowly rather than left on permanently the way
     * enabling TalkBack in Settings does. The capability
     * (canRequestTouchExplorationMode) must already be declared in
     * accessibility_service_config.xml for setServiceInfo() to actually
     * apply the flag -- declaring it there does NOT turn it on by itself,
     * only calling this does. */
    private fun <T> withTouchExplorationMode(block: () -> T): T {
        val info = serviceInfo
        val original = info.flags
        info.flags = original or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = info
        Thread.sleep(300) // let the system actually apply the mode change before the caller starts relying on it
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        Log.i(TAG, "touch exploration requested; system reports isTouchExplorationEnabled=${am?.isTouchExplorationEnabled}, serviceInfo.flags=${serviceInfo.flags}")
        try {
            return block()
        } finally {
            val restore = serviceInfo
            restore.flags = original
            serviceInfo = restore
        }
    }

    /** The vision-fallback hook (see RedditProfile's class doc): captures
     * the current screen via the platform screenshot API (needs
     * canTakeScreenshot, already declared) and returns it as a Bitmap.
     * Confirmed live 2026-09-12 that this succeeds even on the exact
     * Reddit screen whose accessibility tree came back completely empty
     * -- proving the capability is wired correctly. What ISN'T built yet
     * is anything that reads text OUT of the bitmap: that needs either an
     * on-device OCR/vision model or a new host3 endpoint (a real
     * infrastructure decision -- which model, latency, cost -- better made
     * with the user than assumed here, see docs/design.md's open
     * questions). This method exists so that decision has a tested,
     * working capture step to build on rather than being blocked on
     * whether screenshot capture itself even works from this service. */
    fun captureScreenshot(): android.graphics.Bitmap? {
        val latch = CountDownLatch(1)
        var result: android.graphics.Bitmap? = null
        mainHandler.post {
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            result = android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            screenshot.hardwareBuffer.close()
                            latch.countDown()
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "takeScreenshot threw", e)
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /** captureScreenshot() + MlKitOcr.recognize() in one call - the actual
     * last-resort fallback any profile can reach for when the
     * accessibility tree comes back empty (not just RedditProfile;
     * whatever unknown app hits this same wall next doesn't need its own
     * profile written first just to get this far). Returns "" on any
     * failure, same as MlKitOcr.recognize() itself. */
    fun ocrScreenshot(): String {
        val bitmap = captureScreenshot() ?: run {
            Log.w(TAG, "ocrScreenshot: screenshot capture failed")
            return ""
        }
        return MlKitOcr.recognize(bitmap)
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun toast(message: String) {
        Log.i(TAG, message)
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    /** The window ReadAloudService should read: whichever currently-visible
     * window does NOT belong to this app itself (our own trigger has
     * already finished by the time this runs, but a stale reference to it
     * is exactly the bug DictateAccessibilityService's own doc already
     * found here -- same fix, same reasoning: rootInActiveWindow lags
     * reality for a stretch right after an overlay activity finishes, so
     * this falls back to scanning every window). Returns the foreground
     * package name alongside the node so a profile can be selected by it. */
    fun foregroundRoot(): Pair<String, AccessibilityNodeInfo>? {
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() != packageName) return root.packageName.toString() to root
        }
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            return pkg to root
        }
        return null
    }

    /** ACTION_CLICK on the node itself when it's marked clickable (the
     * reliable path -- survives layout shifts, doesn't depend on screen
     * coordinates); falls back to a synthesized tap at the node's on-screen
     * center for nodes that are visually tappable but don't expose a
     * click action (confirmed necessary against Reddit's flattened
     * Compose tree, see RedditProfile). */
    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** ACTION_SCROLL_FORWARD on a scrollable node; falls back to a
     * synthesized upward swipe across the node's own bounds for containers
     * that are visually scrollable but don't implement the action (same
     * Reddit-shaped fallback as click() above). Returns false when neither
     * worked, which a profile treats as "reached the end". */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.height() < 100) return false
        val x = bounds.exactCenterX()
        val y1 = bounds.top + bounds.height() * 0.75f
        val y2 = bounds.top + bounds.height() * 0.25f
        return swipe(x, y1, x, y2)
    }

    /** Blocking (caller must be off the main thread -- ReadAloudService
     * always drives extraction from a background thread): synthesizes a
     * single tap and waits for it to actually complete before returning,
     * so a profile's subsequent tree re-query sees the result rather than
     * racing the gesture. */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchAndWait(gesture)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
            .build()
        return dispatchAndWait(gesture)
    }

    private fun dispatchAndWait(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        mainHandler.post {
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        latch.countDown()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                },
                mainHandler,
            )
            if (!ok) latch.countDown()
        }
        return try {
            latch.await(2, TimeUnit.SECONDS) && completed
        } catch (e: InterruptedException) {
            Log.w(TAG, "gesture wait interrupted", e)
            false
        }
    }
}
