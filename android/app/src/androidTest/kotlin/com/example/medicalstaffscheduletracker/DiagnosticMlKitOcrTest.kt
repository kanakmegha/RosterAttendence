package com.example.medicalstaffscheduletracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.medicalstaffscheduletracker.engine.LocalTableReconstructor
import com.example.medicalstaffscheduletracker.engine.OcrWord
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticMlKitOcrTest {

    companion object {
        private const val TAG = "MLKitDiagnostic"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun loadAssetBitmap(name: String): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().context
        val stream: InputStream = context.assets.open(name)
        return stream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Failed to decode asset $name")
    }

    private fun runOcrOnBitmap(bitmap: Bitmap, label: String): List<OcrWord> {
        val startTime = System.currentTimeMillis()
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = Tasks.await(recognizer.process(image))
        val elapsed = System.currentTimeMillis() - startTime

        val words = mutableListOf<OcrWord>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox
                    val text = element.text.trim()
                    if (box != null && text.isNotEmpty()) {
                        words.add(
                            OcrWord(
                                text = text,
                                x = box.left.toFloat(),
                                y = box.top.toFloat(),
                                width = box.width().toFloat(),
                                height = box.height().toFloat(),
                                confidence = element.confidence ?: 1.0f
                            )
                        )
                    }
                }
            }
        }

        Log.i(TAG, "[$label] Dimensions: ${bitmap.width}x${bitmap.height}, Duration: ${elapsed}ms, Words extracted: ${words.size}")
        Log.i(TAG, "[$label] Sample first 20 tokens: ${words.take(20).joinToString { it.text }}")
        return words
    }

    private fun createHighContrastGrayscale(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        // High contrast matrix
        val contrast = 1.6f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(ColorMatrix(contrastMatrix))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    @Test
    fun benchmarkMlKitAcrossResolutionsAndPreprocessing() {
        val originalBitmap = loadAssetBitmap("roster.jpeg")
        Log.i(TAG, "Loaded original roster asset: ${originalBitmap.width}x${originalBitmap.height}")

        // 1. Test 617x357 (matching user phone capture)
        val tinyBitmap = Bitmap.createScaledBitmap(originalBitmap, 617, 357, true)
        val words617 = runOcrOnBitmap(tinyBitmap, "TINY_617x357")

        // 2. Test original 1131x653 natural resolution
        val wordsOriginal = runOcrOnBitmap(originalBitmap, "ORIGINAL_1131x653")

        // 3. Test 1.8x upscale of original (2035x1175) to provide optimal ML Kit character size
        val upscaledOriginal = scaleBitmap(originalBitmap, 1.8f)
        val wordsUpscaled = runOcrOnBitmap(upscaledOriginal, "UPSCALED_ORIGINAL_2035x1175")

        // 4. Test High-Contrast Grayscale on 1.8x upscaled
        val contrastUpscaled = createHighContrastGrayscale(upscaledOriginal)
        val wordsContrast = runOcrOnBitmap(contrastUpscaled, "CONTRAST_UPSCALED_1.8x")

        Log.i(TAG, "=== SUMMARY COMPARISON ===")
        Log.i(TAG, "1. 617x357 (User Device Resolution): ${words617.size} words")
        Log.i(TAG, "2. 1131x653 (Original Resolution): ${wordsOriginal.size} words")
        Log.i(TAG, "3. 2035x1175 (1.8x Supersampled): ${wordsUpscaled.size} words")
        Log.i(TAG, "4. 2035x1175 High Contrast: ${wordsContrast.size} words")

        // Reconstruct table on the best local OCR output
        val bestWords = if (wordsUpscaled.size >= wordsOriginal.size) wordsUpscaled else wordsOriginal
        val reconstructor = LocalTableReconstructor(words = bestWords)
        val metadata = reconstructor.analyzeRosterStructure()

        Log.i(TAG, "Reconstruction from best ML Kit output: ${metadata.detectedStaff.size} staff members detected")
        Log.i(TAG, "Detected staff list: ${metadata.detectedStaff.map { "${it.rawId} (${it.name})" }}")

        // Check target Akash
        val akashResult = reconstructor.reconstructForStaff(metadata, "ABHL341")
        Log.i(TAG, "Akash search result: matched=${akashResult.matchedStaffId}, name=${akashResult.staffName}, status=${akashResult.status}, confusable=${akashResult.isConfusableId}")

        assertTrue("Original or upscaled resolution must extract substantially more words than the 617x357 capture", bestWords.size > words617.size)
        assertTrue("Supersampled resolution must extract at least 800 words on real roster", wordsUpscaled.size >= 800)
        assertTrue("Reconstruction must find at least 30 staff rows on real roster", metadata.detectedStaff.size >= 30)
        assertTrue("Target staff Akash must be matched via exact or confusable ID", akashResult.matchedStaffId != null)
        assertTrue("Matched staff name must be AKASH", akashResult.staffName?.uppercase(java.util.Locale.US)?.contains("AKASH") == true)
        assertTrue("Partial/truncated ID must be flagged for review", akashResult.isConfusableId)
        assertTrue("Candidate row diagnostics must be captured", metadata.candidateRows.isNotEmpty())
    }
}
