package com.webtoon.translator.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.webtoon.translator.core.Ids
import com.webtoon.translator.core.TextRegion
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit Text Recognition v2 (bundled models) behind a provider interface.
 * Scripts: Latin / Chinese (Simplified + Traditional) / Japanese / Korean.
 * Fully on-device and offline; ~4 MB per script per ABI.
 *
 * Selection is manual (user picks the script) because auto language detection
 * per image is unreliable for stylized lettering; a future provider could add it.
 */
interface OcrProvider {
    val id: String
    suspend fun recognize(bitmap: Bitmap, imageIndex: Int): OcrOutcome
    fun close()
}

data class OcrOutcome(
    val regions: List<TextRegion>,
    val engine: String,
    val ms: Long,
    /** "ok" | "empty" | "failed" */
    val status: String,
    val error: String = ""
)

class MlKitOcrProvider(private val script: String) : OcrProvider {

    override val id: String = "mlkit-v2-$script"
    private val closed = AtomicBoolean(false)
    private val recognizers = HashMap<String, com.google.mlkit.vision.text.TextRecognizer>()

    @Synchronized
    private fun recognizer(forScript: String): com.google.mlkit.vision.text.TextRecognizer =
        recognizers.getOrPut(forScript) {
            when (forScript) {
                "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }

    override suspend fun recognize(bitmap: Bitmap, imageIndex: Int): OcrOutcome {
        if (closed.get()) return OcrOutcome(emptyList(), id, 0, "failed", "provider closed")
        val t0 = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val scripts = if (script == "auto") listOf("korean", "chinese", "japanese", "latin") else listOf(script)
            var best = emptyList<TextRegion>()
            var bestEngine = "mlkit-v2-$script"
            var lastError = ""
            for (candidate in scripts) {
                if (closed.get()) return OcrOutcome(emptyList(), id, System.currentTimeMillis() - t0, "cancelled", "cancelled")
                try {
                    val text = suspendCancellableCoroutine<Text> { cont ->
                        recognizer(candidate).process(image)
                            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                    }
                    val regions = mapRegions(text, imageIndex)
                    // In auto mode, use the recognizer that found the most useful text.
                    if (regions.sumOf { it.text.length } > best.sumOf { it.text.length }) {
                        best = regions
                        bestEngine = "mlkit-v2-$candidate"
                    }
                    if (script != "auto" && regions.isNotEmpty()) break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (script != "auto") throw e
                }
            }
            val status = if (best.isEmpty()) "empty" else "ok"
            OcrOutcome(best, bestEngine, System.currentTimeMillis() - t0, status,
                if (best.isEmpty()) lastError else "")
        } catch (e: Exception) {
            if (closed.get()) OcrOutcome(emptyList(), id, 0, "cancelled", "cancelled")
            else OcrOutcome(emptyList(), id, System.currentTimeMillis() - t0, "failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun mapRegions(text: Text, imageIndex: Int): List<TextRegion> {
        val out = ArrayList<TextRegion>()
        var order = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                val txt = line.text.trim()
                if (txt.isEmpty()) continue
                val lang = line.recognizedLanguage ?: ""
                val conf = line.confidence ?: -1f
                out.add(TextRegion(
                    imageId = Ids.imageId(imageIndex),
                    regionId = Ids.regionId(imageIndex, order),
                    text = txt,
                    x = bb.left.toFloat(), y = bb.top.toFloat(),
                    w = (bb.right - bb.left).toFloat(), h = (bb.bottom - bb.top).toFloat(),
                    order = order, language = lang, confidence = conf,
                    orientation = "horizontal"
                ))
                order++
            }
        }
        return out
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        recognizers.values.forEach { try { it.close() } catch (_: Exception) {} }
        recognizers.clear()
    }
}

/** Decodes image bytes with a hard cap so huge webtoon strips don't explode memory. */
object ImageDecoder {
    const val MAX_DIM = 2400

    fun decode(bytes: ByteArray): Pair<Bitmap, Int>? {
        // First pass: bounds only.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        var maxSide = maxOf(w, h)
        while (maxSide / sample > MAX_DIM) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
        return Pair(bmp, sample)
    }
}

/** Tiny bounded bitmap cache so retries don't re-download; entries evicted under memory pressure. */
class BitmapCache(maxImages: Int = 4) : LruCache<String, Bitmap>(maxImages) {
    override fun entryRemoved(evicted: Boolean, key: String?, old: Bitmap, new: Bitmap?) {
        if (evicted && !old.isRecycled) old.recycle()
    }
}
