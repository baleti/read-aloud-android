package dev.local.readaloud

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The exact same library TalkBack itself uses for its `UNLABELLED_VIEW`
 * caption case (`com.google.android.gms:play-services-mlkit-text-
 * recognition`, confirmed by reading TalkBack's own open-source
 * `OcrController.java` - see docs/design.md's Reddit section) - on-device,
 * private (nothing leaves the phone), and the reason Gradle exists in
 * this project at all, since its real transitive dependency graph
 * (Firebase + AndroidX, 15-25+ AARs) needed real dependency resolution
 * the old hand-rolled build couldn't do safely.
 */
object MlKitOcr {
    private const val TAG = "MlKitOcr"
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Blocks until recognition completes or times out - callers
     * (RedditProfile's extract()) are already on a dedicated background
     * thread, same as every other blocking call there. Returns "" on
     * failure or timeout rather than throwing, so a profile can just
     * check blankness the same way it already does for the tree-based
     * attempts before this one. */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 8000): String {
        val latch = CountDownLatch(1)
        var result = ""
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    result = visionText.text
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "recognition failed", e)
                    latch.countDown()
                }
        } catch (e: Throwable) {
            Log.e(TAG, "recognize() threw before dispatch", e)
            return ""
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }
}
