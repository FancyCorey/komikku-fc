package exh.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// KMK --> OCR v0.1.1

data class OcrImageInput(val stream: InputStream, val pageIdentity: String)

data class OcrPageText(
    val rawText: String,
    val isLimited: Boolean = false,
)

class OcrResourceLimitException : IllegalStateException()

object OcrResourcePolicy {
    const val MAX_INPUT_BYTES = 25 * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L

    fun isPixelCountAllowed(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_PIXELS
}

internal class OcrRecognitionCallbackGate {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isOpen(): Boolean = !cancelled.get()
}

interface OcrTextRecognizer {
    suspend fun recognizeText(input: OcrImageInput): OcrPageText
    fun close() = Unit
    val engineKey: String
    val engineVersion: String
}

class MlKitLatinOcrTextRecognizer(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : OcrTextRecognizer {

    override val engineKey: String get() = ENGINE_KEY
    override val engineVersion: String get() = ENGINE_VERSION

    companion object {
        const val ENGINE_KEY = "mlkit-latin"
        // Version includes preprocessing variant so old v0.1.0 rows (engine_version="16.0.1")
        // are not treated as current — all pages will be re-indexed with the improved tiling.
        const val ENGINE_VERSION = "16.0.1-tiled-v2"
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognizeText(input: OcrImageInput): OcrPageText {
        val bytes = try {
            input.stream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > OcrResourcePolicy.MAX_INPUT_BYTES) {
                        return OcrPageText(rawText = "", isLimited = true)
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            return OcrPageText(rawText = "", isLimited = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OcrPageText(rawText = "")
        }
        if (bytes.isEmpty()) return OcrPageText(rawText = "")

        // Decode bounds only
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origWidth = boundsOpts.outWidth
        val origHeight = boundsOpts.outHeight
        if (origWidth <= 0 || origHeight <= 0) return OcrPageText(rawText = "")
        if (!OcrResourcePolicy.isPixelCountAllowed(origWidth, origHeight)) {
            return OcrPageText(rawText = "", isLimited = true)
        }

        // Width-based sample size only — height does not determine downsampling.
        // This prevents long webtoon/manhwa pages (e.g. 900 x 12000) from being
        // shrunk to an unreadably narrow width.
        val sampleSize = OcrTilePlanner.widthSampleSize(origWidth)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val fullBitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (e: OutOfMemoryError) {
            return OcrPageText(rawText = "")
        } ?: return OcrPageText(rawText = "")

        val tiles = OcrTilePlanner.planTiles(fullBitmap.height)
        if (tiles.size == 1) {
            // The recognition request owns this bitmap until its task reaches a terminal state.
            val text = runMlKit(fullBitmap)
            return OcrPageText(rawText = text)
        }

        return try {
            val textParts = mutableListOf<String>()
            for (tile in tiles) {
                val tileBitmap = try {
                    Bitmap.createBitmap(fullBitmap, 0, tile.y, fullBitmap.width, tile.height)
                } catch (e: OutOfMemoryError) {
                    break
                }
                val tileText = runMlKit(tileBitmap)
                if (tileText.isNotBlank()) textParts.add(tileText)
            }
            OcrPageText(rawText = textParts.joinToString("\n"))
        } finally {
            fullBitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }

    private suspend fun runMlKit(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val callbackGate = OcrRecognitionCallbackGate()
        val released = AtomicBoolean(false)
        val releaseBitmap = {
            if (released.compareAndSet(false, true) && !bitmap.isRecycled) bitmap.recycle()
        }
        val task = try {
            recognizer.process(image)
        } catch (e: Exception) {
            releaseBitmap()
            throw e
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                callbackGate.cancel()
                task.addOnCompleteListener { releaseBitmap() }
            }
            task
                .addOnSuccessListener { visionText ->
                    releaseBitmap()
                    if (callbackGate.isOpen()) cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    releaseBitmap()
                    if (callbackGate.isOpen()) cont.resumeWithException(e)
                }
                .addOnCanceledListener {
                    releaseBitmap()
                    if (callbackGate.isOpen()) cont.cancel()
                }
        }
    }
}

// KMK <--
