package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Constrained Shift Classification and Cell-Level OCR Engine.
 *
 * Allowed cell values:
 * - M
 * - E
 * - N
 * - OFF
 * - A
 * - UNKNOWN
 *
 * Normalizes OCR results using a transparent, testable classifier.
 * Preserves the distinction between directly observed, inferred, manual, conflicting, and unresolved.
 */
object ShiftClassifier {

    const val SHIFT_M = "M"
    const val SHIFT_E = "E"
    const val SHIFT_N = "N"
    const val SHIFT_OFF = "OFF"
    const val SHIFT_A = "A"
    const val SHIFT_UNKNOWN = "UNKNOWN"

    val ALLOWED_CLASSIFICATIONS = setOf(SHIFT_M, SHIFT_E, SHIFT_N, SHIFT_OFF, SHIFT_A, SHIFT_UNKNOWN)

    data class ClassificationResult(
        val classification: String,
        val normalizedShift: String?,
        val confidence: Double,
        val isConfirmed: Boolean,
        val reviewReason: String?
    )

    /**
     * Transparent, rule-based classification of raw OCR tokens.
     * No unconstrained fuzzy guessing.
     */
    fun classifyToken(rawToken: String?, pixelAnalysis: PixelDensityAnalysis? = null): ClassificationResult {
        if (rawToken.isNullOrBlank()) {
            if (pixelAnalysis != null && pixelAnalysis.isFaintOrBlank) {
                return ClassificationResult(
                    classification = SHIFT_UNKNOWN,
                    normalizedShift = null,
                    confidence = 0.95,
                    isConfirmed = false,
                    reviewReason = "Confirmed blank cell (no token recognized)"
                )
            }
            return ClassificationResult(
                classification = SHIFT_UNKNOWN,
                normalizedShift = null,
                confidence = 0.0,
                isConfirmed = false,
                reviewReason = "No text token recognized in cell"
            )
        }

        val clean = rawToken.trim().uppercase(Locale.US)
        val alphaOnly = clean.replace(Regex("[^A-Z0-9]"), "")

        // 1. Explicit OFF tokens and common border/digit confusion (0FF, OF)
        if (alphaOnly in setOf("OFF", "0FF", "OFF1", "OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI", "QFF", "OF")) {
            val isExact = alphaOnly == "OFF" || alphaOnly == "0FF"
            return ClassificationResult(
                classification = SHIFT_OFF,
                normalizedShift = "Off",
                confidence = if (isExact) 0.95 else 0.82,
                isConfirmed = isExact,
                reviewReason = if (isExact) null else "Recognized '$rawToken' normalized to Off. Held for review."
            )
        }

        // 2. Non-target Shift 'A' (Leave/Absent) - preserve distinctly with normalizedShift = "A"
        if (alphaOnly in setOf("A", "AA", "AAA", "ABS", "ABSENT")) {
            val isExact = alphaOnly == "A" || alphaOnly == "AA" || alphaOnly == "AAA"
            return ClassificationResult(
                classification = SHIFT_A,
                normalizedShift = "A",
                confidence = if (isExact) 0.95 else 0.85,
                isConfirmed = isExact,
                reviewReason = if (isExact) null else "Observed '$rawToken' normalized to A. Held for review."
            )
        }

        // 3. Morning 'M' (Handles common OCR confusions like W/M and repeated tokens M M)
        if (alphaOnly in setOf("M", "MM", "MMM", "MORNING", "W")) {
            val isExact = alphaOnly in setOf("M", "MM", "MMM", "MORNING")
            return ClassificationResult(
                classification = SHIFT_M,
                normalizedShift = "Morning",
                confidence = if (isExact) 0.95 else 0.70,
                isConfirmed = isExact,
                reviewReason = if (isExact) null else "Recognized '$rawToken' (W/M confusion) normalized to Morning. Held for review."
            )
        }

        // 4. Evening 'E' (Handles common OCR confusions like E/F and repeated tokens E E)
        if (alphaOnly in setOf("E", "EE", "EEE", "EVENING", "F") || clean == "E" || clean == "F") {
            val isExact = alphaOnly in setOf("E", "EE", "EEE", "EVENING") || clean == "E"
            return ClassificationResult(
                classification = SHIFT_E,
                normalizedShift = "Evening",
                confidence = if (isExact) 0.95 else 0.75,
                isConfirmed = isExact,
                reviewReason = if (isExact) null else "Recognized '$rawToken' (E/F confusion) normalized to Evening. Held for review."
            )
        }

        // 5. Night 'N' (Handles common OCR confusions like N/H and repeated tokens N N)
        if (alphaOnly in setOf("N", "NN", "NNN", "NIGHT", "H")) {
            val isExact = alphaOnly in setOf("N", "NN", "NNN", "NIGHT")
            return ClassificationResult(
                classification = SHIFT_N,
                normalizedShift = "Night",
                confidence = if (isExact) 0.95 else 0.75,
                isConfirmed = isExact,
                reviewReason = if (isExact) null else "Recognized '$rawToken' (N/H confusion) normalized to Night. Held for review."
            )
        }

        // 6. Unknown / Unresolved token (Treat ambiguous tokens such as '3', conflicting tokens, or blanks as unresolved)
        return ClassificationResult(
            classification = SHIFT_UNKNOWN,
            normalizedShift = null,
            confidence = 0.20,
            isConfirmed = false,
            reviewReason = "Unrecognized shift token '$rawToken'; held as UNKNOWN for manual review"
        )
    }
}

data class CellCropExtraction(
    val dayNumber: Int,
    val dateStr: String,
    val weekday: String,
    val leftBound: Float,
    val rightBound: Float,
    val topBound: Float,
    val bottomBound: Float,
    val rawToken: String?,
    val confidence: Double,
    val classification: String,
    val normalizedShift: String?,
    val reviewStatus: String,
    val reviewReason: String?,
    val ocrEngine: String,
    val preprocessingVariant: String,
    val pixelAnalysis: PixelDensityAnalysis
)

/**
 * Executes cell-level OCR with vertical margin preservation and variant fallbacks.
 */
class CellOcrProcessor(
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) {

    private val TAG = "MedicalScheduleTracker"

    suspend fun processDailyCell(
        sourceBitmap: Bitmap,
        dayNumber: Int,
        dateStr: String,
        weekday: String,
        leftBound: Float,
        rightBound: Float,
        rowCenterY: Float,
        rowHeight: Float,
        verticalMargin: Float = 0.5f,
        useCloudFallback: Boolean = false,
        context: Context? = null,
        topBoundLimit: Float? = null,
        bottomBoundLimit: Float? = null,
        sourceWidthHint: Int = 0,
        sourceHeightHint: Int = 0
    ): CellCropExtraction = withContext(Dispatchers.IO) {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        // 1. Boundary Coordinate Mapping & Invariant Check:
        // Ensure coordinates never mismatch bitmap scaling space
        val scaleX = if (sourceWidthHint > 0 && sourceWidthHint != srcW) {
            val sX = srcW.toFloat() / sourceWidthHint.toFloat()
            Log.w(TAG, "Source bitmap width ($srcW) differs from metadata width hint ($sourceWidthHint). Applying scaleX=$sX")
            sX
        } else 1.0f

        val scaleY = if (sourceHeightHint > 0 && sourceHeightHint != srcH) {
            val sY = srcH.toFloat() / sourceHeightHint.toFloat()
            Log.w(TAG, "Source bitmap height ($srcH) differs from metadata height hint ($sourceHeightHint). Applying scaleY=$sY")
            sY
        } else 1.0f

        val effectiveLeft = leftBound * scaleX
        val effectiveRight = rightBound * scaleX
        val effectiveRowY = rowCenterY * scaleY
        val effectiveRowH = rowHeight * scaleY
        val effectiveMargin = verticalMargin * scaleY

        require(effectiveLeft >= 0f && effectiveLeft < srcW) {
            "Invalid crop leftBound=$leftBound (effective: $effectiveLeft) exceeds bitmap width $srcW"
        }
        require(effectiveRight > effectiveLeft) {
            "Invalid crop rightBound=$rightBound <= leftBound=$leftBound"
        }
        require(effectiveRowY >= 0f && effectiveRowY < srcH) {
            "Invalid crop rowCenterY=$rowCenterY (effective: $effectiveRowY) exceeds bitmap height $srcH"
        }

        // Horizontal boundaries with 1px inset to avoid vertical gridline bleed
        val rawX0 = effectiveLeft.toInt().coerceIn(0, srcW - 2)
        val rawX1 = effectiveRight.toInt().coerceIn(rawX0 + 1, srcW)
        val x0 = if (rawX1 - rawX0 > 10) (rawX0 + 1).coerceAtMost(srcW - 2) else rawX0
        val x1 = if (rawX1 - rawX0 > 10) (rawX1 - 1).coerceAtLeast(x0 + 1) else rawX1
        val cellW = (x1 - x0).coerceAtLeast(2)

        // Vertical boundaries strictly constrained to prevent adjacent row bleed
        val halfH = (effectiveRowH / 2.0f) + effectiveMargin
        val unconstrainedY0 = effectiveRowY - halfH
        val unconstrainedY1 = effectiveRowY + halfH

        val topLimit = if (topBoundLimit != null) topBoundLimit * scaleY else 0f
        val bottomLimit = if (bottomBoundLimit != null) bottomBoundLimit * scaleY else srcH.toFloat()

        val y0 = maxOf(unconstrainedY0, topLimit).toInt().coerceIn(0, srcH - 2)
        val y1 = minOf(unconstrainedY1, bottomLimit).toInt().coerceIn(y0 + 1, srcH)
        val cellH = (y1 - y0).coerceAtLeast(2)

        require(cellH <= (effectiveRowH * 1.5f).toInt() + 4) {
            "Cell crop height $cellH exceeds maximum allowable threshold for row height $effectiveRowH"
        }

        val rawCrop = Bitmap.createBitmap(sourceBitmap, x0, y0, cellW, cellH)
        val pixelAnalysis = DocumentImageProcessor.analyzePixelDensity(rawCrop)

        // If the cell is faint/blank, don't run heavy OCR
        if (pixelAnalysis.isFaintOrBlank && pixelAnalysis.darkPixelRatio < 0.04f) {
            val classified = ShiftClassifier.classifyToken(null, pixelAnalysis)
            return@withContext CellCropExtraction(
                dayNumber = dayNumber,
                dateStr = dateStr,
                weekday = weekday,
                leftBound = x0.toFloat(),
                rightBound = x1.toFloat(),
                topBound = y0.toFloat(),
                bottomBound = y1.toFloat(),
                rawToken = null,
                confidence = classified.confidence,
                classification = classified.classification,
                normalizedShift = classified.normalizedShift,
                reviewStatus = "missing",
                reviewReason = classified.reviewReason,
                ocrEngine = "Google ML Kit (Cell Precheck)",
                preprocessingVariant = "raw",
                pixelAnalysis = pixelAnalysis
            )
        }

        // 1. Primary Variant: 4.0x Upscale with 16px Padding
        val upscaledCrop = DocumentImageProcessor.createPaddedUpscaledCrop(rawCrop, 4.0f, borderPadding = 16)
        var bestToken: String? = null
        var bestConf = 0.0
        var bestVariant = "upscale_4x"
        var bestClassification = ShiftClassifier.classifyToken(null)

        suspend fun runMlKitOnVariant(cropBmp: Bitmap): Pair<String?, ShiftClassifier.ClassificationResult> {
            return try {
                val img = InputImage.fromBitmap(cropBmp, 0)
                val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                    textRecognizer.process(img)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                val recognized = result.text.trim()
                if (recognized.isNotEmpty()) {
                    val classification = ShiftClassifier.classifyToken(recognized, pixelAnalysis)
                    recognized to classification
                } else {
                    null to ShiftClassifier.classifyToken(null, pixelAnalysis)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit recognition error for Day $dayNumber: ${e.message}")
                null to ShiftClassifier.classifyToken(null, pixelAnalysis)
            }
        }

        val (token1, classif1) = runMlKitOnVariant(upscaledCrop)
        if (token1 != null) {
            bestToken = token1
            bestClassification = classif1
            bestConf = classif1.confidence
            bestVariant = "upscale_4x"
        }

        // 2. If Primary is unconfirmed, unknown (e.g. '3'), or blank, try Border-Cleaned Enhanced Variant
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val borderCleanedCrop = DocumentImageProcessor.createBorderCleanedEnhancedVariant(rawCrop, 4.0f, borderPadding = 16)
            val (token2, classif2) = runMlKitOnVariant(borderCleanedCrop)
            if (token2 != null && classif2.isConfirmed && classif2.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token2
                bestClassification = classif2
                bestConf = classif2.confidence
                bestVariant = "border_cleaned"
            } else if (bestToken == null && token2 != null) {
                bestToken = token2
                bestClassification = classif2
                bestConf = classif2.confidence
                bestVariant = "border_cleaned"
            }
        }

        // 3. Fallback: Contrast-enhanced variant if still unconfirmed/unknown
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val contrastCrop = DocumentImageProcessor.createContrastEnhancedVariant(rawCrop, 4.0f, borderPadding = 16)
            val (token3, classif3) = runMlKitOnVariant(contrastCrop)
            if (token3 != null && classif3.isConfirmed && classif3.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token3
                bestClassification = classif3
                bestConf = classif3.confidence
                bestVariant = "contrast_enhanced"
            } else if (bestToken == null && token3 != null) {
                bestToken = token3
                bestClassification = classif3
                bestConf = classif3.confidence
                bestVariant = "contrast_enhanced"
            }
        }

        // 4. Fallback: Binarized variant if still unconfirmed/unknown
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val binCrop = DocumentImageProcessor.createBinarizedVariant(rawCrop, 4.0f, borderPadding = 16)
            val (token4, classif4) = runMlKitOnVariant(binCrop)
            if (token4 != null && classif4.isConfirmed && classif4.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token4
                bestClassification = classif4
                bestConf = classif4.confidence
                bestVariant = "binarized"
            } else if (bestToken == null && token4 != null) {
                bestToken = token4
                bestClassification = classif4
                bestConf = classif4.confidence
                bestVariant = "binarized"
            }
        }

        // 5. Fallback: Sharp upscaled variant (nearest-neighbor). Bilinear blur can erase thin 1-2px
        // shift glyph strokes; nearest-neighbor preserves them for faint cells (e.g. small 'E' glyphs).
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val sharpCrop = DocumentImageProcessor.createSharpUpscaledCrop(rawCrop, 4.0f, borderPadding = 16)
            val (token5, classif5) = runMlKitOnVariant(sharpCrop)
            if (token5 != null && classif5.isConfirmed && classif5.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token5
                bestClassification = classif5
                bestConf = classif5.confidence
                bestVariant = "sharp_upscale_4x"
            } else if (bestToken == null && token5 != null) {
                bestToken = token5
                bestClassification = classif5
                bestConf = classif5.confidence
                bestVariant = "sharp_upscale_4x"
            }
        }

        // 6. Fallback: Contrast stretch + nearest-neighbor upscale. Maps the cell's darkest pixel to
        // black and lightest to white, recovering faint glyphs on coloured cell backgrounds without
        // the blur that bilinear interpolation introduces.
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val contrastSharpCrop = DocumentImageProcessor.createContrastSharpUpscaledCrop(rawCrop, 4.0f, borderPadding = 16)
            val (token6, classif6) = runMlKitOnVariant(contrastSharpCrop)
            if (token6 != null && classif6.isConfirmed && classif6.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token6
                bestClassification = classif6
                bestConf = classif6.confidence
                bestVariant = "contrast_sharp_4x"
            } else if (bestToken == null && token6 != null) {
                bestToken = token6
                bestClassification = classif6
                bestConf = classif6.confidence
                bestVariant = "contrast_sharp_4x"
            }
        }

        // 7. Fallback: Contrast stretch at original 1x scale (no interpolation). For very faint glyphs
        // where upscaling smears the stroke, min-max contrast stretch alone can recover the glyph
        // (e.g. Day 4's faint 'E'). Only reached when every other variant is still unresolved.
        if (!bestClassification.isConfirmed || bestClassification.classification == ShiftClassifier.SHIFT_UNKNOWN) {
            val contrastOnlyCrop = DocumentImageProcessor.createContrastOnlyCrop(rawCrop, borderPadding = 12)
            val (token7, classif7) = runMlKitOnVariant(contrastOnlyCrop)
            if (token7 != null && classif7.isConfirmed && classif7.classification != ShiftClassifier.SHIFT_UNKNOWN) {
                bestToken = token7
                bestClassification = classif7
                bestConf = classif7.confidence
                bestVariant = "contrast_only_1x"
            } else if (bestToken == null && token7 != null) {
                bestToken = token7
                bestClassification = classif7
                bestConf = classif7.confidence
                bestVariant = "contrast_only_1x"
            }
        }

        // 4. Optional Cloud Fallback (OCR.space Engine 2) with explicit user consent
        var engineUsed = "Google ML Kit On-Device (Latin)"
        if (bestToken.isNullOrBlank() && useCloudFallback && context != null) {
            try {
                val cloudRes = OcrSpaceClient.parseBitmap(
                    context = context,
                    bitmap = upscaledCrop,
                    fileName = "day_${dayNumber}_cell.jpg",
                    engine = "2"
                )
                if (cloudRes.isSuccess) {
                    val ext = cloudRes.getOrThrow()
                    val token = ext.words.firstOrNull()?.text ?: ext.fullText.trim()
                    if (token.isNotEmpty()) {
                        bestToken = token
                        bestConf = 0.88
                        bestVariant = "cloud_upscale"
                        engineUsed = "OCR.space Engine 2 (Cloud Fallback)"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OCR.space fallback notice for Day $dayNumber: ${e.message}")
            }
        }

        val classification = ShiftClassifier.classifyToken(bestToken, pixelAnalysis)

        CellCropExtraction(
            dayNumber = dayNumber,
            dateStr = dateStr,
            weekday = weekday,
            leftBound = x0.toFloat(),
            rightBound = x1.toFloat(),
            topBound = y0.toFloat(),
            bottomBound = y1.toFloat(),
            rawToken = bestToken,
            confidence = if (bestToken != null) bestConf else classification.confidence,
            classification = classification.classification,
            normalizedShift = classification.normalizedShift,
            reviewStatus = if (bestToken != null) (if (classification.isConfirmed) "confirmed" else "needs_review") else "missing",
            reviewReason = classification.reviewReason,
            ocrEngine = engineUsed,
            preprocessingVariant = bestVariant,
            pixelAnalysis = pixelAnalysis
        )
    }
}
