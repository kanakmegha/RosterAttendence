package com.example.medicalstaffscheduletracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.medicalstaffscheduletracker.engine.CellCropExtraction
import com.example.medicalstaffscheduletracker.engine.CellOcrProcessor
import com.example.medicalstaffscheduletracker.engine.DocumentImageProcessor
import com.example.medicalstaffscheduletracker.engine.IdMatcher
import com.example.medicalstaffscheduletracker.engine.OnDeviceRosterProcessor
import com.example.medicalstaffscheduletracker.engine.ShiftClassifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class ActualRosterCellOcrDiagnosticTest {

    companion object {
        private const val TAG = "CELL_OCR_DIAGNOSTIC"
    }

    // Manually verified ground truth shifts for Akash (ABH1341 / Row #17, September 2026)
    // Verified against the actual roster image:
    // Days 1, 2, 4, 6, 7, 8, 10: E (Yellow background, black 'E') - 7 days
    // Days 5, 12, 19, 26: OFF (Red background, white 'OFF') - 4 days
    // Days 11, 13, 14, 15, 16, 17, 18, 20: N (Orange background, black 'N') - 8 days
    // Days 21, 22, 24, 25, 27, 28, 30: M (Pink background, black 'M') - 7 days
    // Days 3, 9, 23, 29: A (Cyan background, black 'A') - 4 days
    // Total = 7 + 4 + 8 + 7 + 4 = 30 days
    private val verifiedGroundTruth = mapOf(
        1 to "E", 2 to "E", 3 to "A", 4 to "E", 5 to "OFF",
        6 to "E", 7 to "E", 8 to "E", 9 to "A", 10 to "E",
        11 to "N", 12 to "OFF", 13 to "N", 14 to "N", 15 to "N",
        16 to "N", 17 to "N", 18 to "N", 19 to "OFF", 20 to "N",
        21 to "M", 22 to "M", 23 to "A", 24 to "M", 25 to "M",
        26 to "OFF", 27 to "M", 28 to "M", 29 to "A", 30 to "M"
    )

    private val expectedNormalizedShift = mapOf(
        "E" to "Evening",
        "N" to "Night",
        "M" to "Morning",
        "OFF" to "Off",
        "A" to "A"
    )

    private suspend fun runMlKitOnBitmap(recognizer: TextRecognizer, bmp: Bitmap): String? {
        return try {
            val img = InputImage.fromBitmap(bmp, 0)
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(img)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val t = result.text.trim()
            if (t.isEmpty()) null else t
        } catch (e: Exception) {
            null
        }
    }

    private fun isRawTokenMatching(rawToken: String?, gtCode: String): Boolean {
        if (rawToken.isNullOrBlank()) return false
        val clean = rawToken.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        return when (gtCode) {
            "E" -> clean in setOf("E", "EE", "EEE", "EVENING")
            "N" -> clean in setOf("N", "NN", "NNN", "NIGHT")
            "M" -> clean in setOf("M", "MM", "MMM", "MORNING")
            "OFF" -> clean in setOf("OFF", "0FF", "OF", "OFF1", "OFFI", "OFE", "OFFN")
            "A" -> clean in setOf("A", "AA", "AAA", "ABS", "ABSENT")
            else -> false
        }
    }

    @Test
    fun executeRealCellLevelOcrOnActualRoster() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Ensure active provider is Google ML Kit Local On-Device
        com.example.medicalstaffscheduletracker.engine.OcrKeyManager.setActiveProvider(
            appContext,
            com.example.medicalstaffscheduletracker.engine.OcrProviderType.ML_KIT
        )

        // 1. Copy real roster image asset to app cache
        val assetFile = File(appContext.cacheDir, "diagnostic_roster.jpeg")
        testContext.assets.open("roster.jpeg").use { input ->
            FileOutputStream(assetFile).use { output ->
                input.copyTo(output)
            }
        }
        assertTrue("Roster image file must exist", assetFile.exists() && assetFile.length() > 0)
        Log.i(TAG, "Copied asset to cache: ${assetFile.absolutePath} (${assetFile.length()} bytes)")

        val rosterUri = Uri.fromFile(assetFile)
        val onDeviceProcessor = OnDeviceRosterProcessor(appContext)

        // 2. Run real metadata & staff detection on the actual roster image
        Log.i(TAG, "Executing onDeviceProcessor.analyzeRosterFile() on real roster image...")
        val metadataResult = onDeviceProcessor.analyzeRosterFile(rosterUri)
        assertTrue("Metadata detection must succeed on real roster: ${metadataResult.exceptionOrNull()?.message}", metadataResult.isSuccess)

        val metadata = metadataResult.getOrThrow()
        Log.i(TAG, "Detected Month: ${metadata.monthInfo.displayName}, Total Staff Detected: ${metadata.detectedStaff.size}")
        Log.i(TAG, "Detected Day Interval Count: ${metadata.dayIntervals.size}")
        Log.i(TAG, "Canonical Dimensions: ${metadata.sourceImageWidth}x${metadata.sourceImageHeight}, System: ${metadata.coordinateSystem}")

        // 3. Locate Akash (ABH1341 / ABHL341) row
        val akashStaff = metadata.detectedStaff.find {
            it.rawId == "ABH1341" || it.rawId == "ABHL341" ||
                    IdMatcher.normalizeId(it.rawId) == "ABH1341" ||
                    it.name.contains("AKASH", ignoreCase = true)
        }
        val targetRowY = akashStaff?.rowY ?: 296.86f
        val rowHeight = akashStaff?.rowHeight ?: 12.36f
        val topBoundLimit = akashStaff?.topBound
        val bottomBoundLimit = akashStaff?.bottomBound
        val targetStaffId = akashStaff?.rawId ?: "ABH1341"

        Log.i(TAG, "Target Staff for Cell OCR: ID=$targetStaffId, Name=${akashStaff?.name}, RowY=$targetRowY, Height=$rowHeight")
        Log.i(TAG, "Target Row Bounds: topBound=$topBoundLimit, bottomBound=$bottomBoundLimit")

        // 4. Execute the EXACT production code path
        Log.i(TAG, "Executing refineRowWithCellLevelOcr with real Google ML Kit TextRecognizer...")
        val cellOcrResult = onDeviceProcessor.refineRowWithCellLevelOcr(
            context = appContext,
            metadata = metadata,
            selectedStaffId = targetStaffId,
            targetRowY = targetRowY,
            rowHeight = rowHeight,
            useCloudFallback = false
        )
        assertTrue("Cell-level OCR refinement must succeed: ${cellOcrResult.exceptionOrNull()?.message}", cellOcrResult.isSuccess)
        val draftSchedule = cellOcrResult.getOrThrow()
        Log.i(TAG, "Refinement returned DraftSchedule with ${draftSchedule.entries.size} entries for ${draftSchedule.staffId}")

        // 5. Direct individual cell crops & inspections for detailed reporting
        val srcBitmap = BitmapFactory.decodeFile(assetFile.absolutePath)
        assertNotNull("Source bitmap must not be null", srcBitmap)
        Log.i(TAG, "Source Bitmap for cell crop evaluation: ${srcBitmap.width}x${srcBitmap.height}")

        val cropsDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "cell_crops")
        cropsDir.mkdirs()

        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val directCellProcessor = CellOcrProcessor(textRecognizer)

        val jsonReport = JSONObject()
        jsonReport.put("staffId", targetStaffId)
        jsonReport.put("staffName", akashStaff?.name ?: "AKASH")
        jsonReport.put("rowY", targetRowY)
        jsonReport.put("rowHeight", rowHeight)
        jsonReport.put("topBound", topBoundLimit)
        jsonReport.put("bottomBound", bottomBoundLimit)
        jsonReport.put("sourceWidth", srcBitmap.width)
        jsonReport.put("sourceHeight", srcBitmap.height)

        val cellsArray = JSONArray()
        val allExtractions = mutableListOf<CellCropExtraction>()

        val cal = Calendar.getInstance()
        cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, 1)
        val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        var totalCells = 0
        var rawOcrMatches = 0
        var normalizedShiftMatches = 0
        var correctlyResolvedCells = 0
        var unresolvedCount = 0
        var falseConfidentCount = 0

        var baselineMatches = 0
        var upscaleMatches = 0
        var contrastMatches = 0
        var binarizedMatches = 0
        var borderCleanedMatches = 0
        var sharpUpscaleMatches = 0
        var contrastSharpMatches = 0
        var contrastOnlyMatches = 0
        var binarizedSharpMatches = 0

        // Per-shift confusion matrix counters
        val shiftCodes = listOf("E", "OFF", "N", "M", "A")
        val tpMap = mutableMapOf<String, Int>()
        val fpMap = mutableMapOf<String, Int>()
        val fnMap = mutableMapOf<String, Int>()
        shiftCodes.forEach {
            tpMap[it] = 0
            fpMap[it] = 0
            fnMap[it] = 0
        }

        Log.i(TAG, "====================================================================================================================================")
        Log.i(TAG, "30-DAY ISOLATED CELL PREPROCESSING & RECOGNITION BENCHMARK (all variants run on the isolated crop only)")
        Log.i(TAG, "Tags: B=baseline1x U4=upscale4x C4=contrast4x Bi4=binarized4x BC4=borderCleaned4x S4=sharpUpscale4x CS4=contrastSharp4x C1=contrastOnly1x B3=binarizedSharp3x")
        Log.i(TAG, "Day | Expected |                       Raw OCR output per variant                        | Selected Variant | Normalized | Match")
        Log.i(TAG, "---------------------------------------------------------------------------------------------------------------------------------------------------------------------------")

        for (d in 1..numDays) {
            val (leftBound, rightBound) = metadata.dayIntervals[d] ?: (0f to 0f)
            cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, d)
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", metadata.monthInfo.year, metadata.monthInfo.month, d)
            val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: ""

            // Strict geometry bounds for isolated crop
            val effectiveLeft = leftBound
            val effectiveRight = rightBound
            val rawX0 = effectiveLeft.toInt().coerceIn(0, srcBitmap.width - 2)
            val rawX1 = effectiveRight.toInt().coerceIn(rawX0 + 1, srcBitmap.width)
            val cropX = if (rawX1 - rawX0 > 10) (rawX0 + 1).coerceAtMost(srcBitmap.width - 2) else rawX0
            val cropX1 = if (rawX1 - rawX0 > 10) (rawX1 - 1).coerceAtLeast(cropX + 1) else rawX1
            val cropW = (cropX1 - cropX).coerceAtLeast(2)

            val halfH = (rowHeight / 2.0f) + 0.5f
            val topLimit = topBoundLimit ?: 0f
            val bottomLimit = bottomBoundLimit ?: srcBitmap.height.toFloat()
            val cropY = maxOf(targetRowY - halfH, topLimit).toInt().coerceIn(0, srcBitmap.height - 2)
            val cropY1 = minOf(targetRowY + halfH, bottomLimit).toInt().coerceIn(cropY + 1, srcBitmap.height)
            val cropH = (cropY1 - cropY).coerceAtLeast(2)

            val rawCrop = Bitmap.createBitmap(srcBitmap, cropX, cropY, cropW, cropH)
            val cropFile = File(cropsDir, "day_${d}.png")
            FileOutputStream(cropFile).use { fos ->
                rawCrop.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }

            // Generate Preprocessing Variants for empirical measurement.
            // Crop coordinates are unchanged: variants only resample/preprocess the isolated crop
            // with white padding (never pixels from adjacent rows or columns).
            val v1Baseline = DocumentImageProcessor.createPaddedBaselineCrop(rawCrop, borderPadding = 12)
            val v2Upscaled = DocumentImageProcessor.createPaddedUpscaledCrop(rawCrop, scale = 4.0f, borderPadding = 16)
            val v3Contrast = DocumentImageProcessor.createContrastEnhancedVariant(rawCrop, scale = 4.0f, borderPadding = 16)
            val v4Binarized = DocumentImageProcessor.createBinarizedVariant(rawCrop, scale = 4.0f, borderPadding = 16)
            val v5BorderCleaned = DocumentImageProcessor.createBorderCleanedEnhancedVariant(rawCrop, scale = 4.0f, borderPadding = 16)
            val v6SharpUpscale = DocumentImageProcessor.createSharpUpscaledCrop(rawCrop, scale = 4.0f, borderPadding = 16)
            val v7ContrastSharp = DocumentImageProcessor.createContrastSharpUpscaledCrop(rawCrop, scale = 4.0f, borderPadding = 16)
            val v8ContrastOnly = DocumentImageProcessor.createContrastOnlyCrop(rawCrop, borderPadding = 12)
            val v9BinarizedSharp = DocumentImageProcessor.createBinarizedSharpVariant(rawCrop, scale = 3.0f, borderPadding = 16)

            val tBaseline = runMlKitOnBitmap(textRecognizer, v1Baseline)
            val tUpscaled = runMlKitOnBitmap(textRecognizer, v2Upscaled)
            val tContrast = runMlKitOnBitmap(textRecognizer, v3Contrast)
            val tBinarized = runMlKitOnBitmap(textRecognizer, v4Binarized)
            val tBorderCleaned = runMlKitOnBitmap(textRecognizer, v5BorderCleaned)
            val tSharpUpscale = runMlKitOnBitmap(textRecognizer, v6SharpUpscale)
            val tContrastSharp = runMlKitOnBitmap(textRecognizer, v7ContrastSharp)
            val tContrastOnly = runMlKitOnBitmap(textRecognizer, v8ContrastOnly)
            val tBinarizedSharp = runMlKitOnBitmap(textRecognizer, v9BinarizedSharp)

            val gtCode = verifiedGroundTruth[d] ?: "?"
            val expectedNorm = expectedNormalizedShift[gtCode]

            val cBaseline = ShiftClassifier.classifyToken(tBaseline)
            val cUpscaled = ShiftClassifier.classifyToken(tUpscaled)
            val cContrast = ShiftClassifier.classifyToken(tContrast)
            val cBinarized = ShiftClassifier.classifyToken(tBinarized)
            val cBorderCleaned = ShiftClassifier.classifyToken(tBorderCleaned)
            val cSharpUpscale = ShiftClassifier.classifyToken(tSharpUpscale)
            val cContrastSharp = ShiftClassifier.classifyToken(tContrastSharp)
            val cContrastOnly = ShiftClassifier.classifyToken(tContrastOnly)
            val cBinarizedSharp = ShiftClassifier.classifyToken(tBinarizedSharp)

            if (cBaseline.normalizedShift == expectedNorm) baselineMatches++
            if (cUpscaled.normalizedShift == expectedNorm) upscaleMatches++
            if (cContrast.normalizedShift == expectedNorm) contrastMatches++
            if (cBinarized.normalizedShift == expectedNorm) binarizedMatches++
            if (cBorderCleaned.normalizedShift == expectedNorm) borderCleanedMatches++
            if (cSharpUpscale.normalizedShift == expectedNorm) sharpUpscaleMatches++
            if (cContrastSharp.normalizedShift == expectedNorm) contrastSharpMatches++
            if (cContrastOnly.normalizedShift == expectedNorm) contrastOnlyMatches++
            if (cBinarizedSharp.normalizedShift == expectedNorm) binarizedSharpMatches++

            // Process with production cellOcrProcessor
            val cellExtraction = directCellProcessor.processDailyCell(
                sourceBitmap = srcBitmap,
                dayNumber = d,
                dateStr = dateStr,
                weekday = weekday,
                leftBound = leftBound,
                rightBound = rightBound,
                rowCenterY = targetRowY,
                rowHeight = rowHeight,
                verticalMargin = 0.5f,
                useCloudFallback = false,
                context = appContext,
                topBoundLimit = topBoundLimit,
                bottomBoundLimit = bottomBoundLimit,
                sourceWidthHint = metadata.sourceImageWidth,
                sourceHeightHint = metadata.sourceImageHeight
            )
            allExtractions.add(cellExtraction)

            val draftEntry = draftSchedule.entries.find { it.dayNumber == d }
            val classificationState = when {
                draftEntry == null -> "unknown"
                draftEntry.isObserved -> "observed"
                draftEntry.isInferred -> "inferred"
                draftEntry.isUnresolved -> "unknown"
                else -> "observed"
            }

            // Metric Evaluations:
            // 1. Raw OCR token match
            val isRawMatch = isRawTokenMatching(cellExtraction.rawToken, gtCode)
            if (isRawMatch) rawOcrMatches++

            // 2. Normalized shift match
            // A cell ONLY counts as a match when its final normalized shift equals the manually verified ground truth!
            // Do not count raw "A A" if normalized shift is null!
            val isNormMatch = cellExtraction.normalizedShift != null && cellExtraction.normalizedShift == expectedNorm
            if (isNormMatch) normalizedShiftMatches++

            // 3. Correctly resolved cell
            val isCorrectlyResolved = isNormMatch && classificationState != "unknown"
            if (isCorrectlyResolved) correctlyResolvedCells++

            // 4. Unresolved cell count
            val isUnresolved = cellExtraction.normalizedShift == null ||
                    cellExtraction.classification == ShiftClassifier.SHIFT_UNKNOWN ||
                    classificationState == "unknown"
            if (isUnresolved) unresolvedCount++

            // 5. False confident prediction
            val isFalseConfident = cellExtraction.reviewStatus == "confirmed" && !isNormMatch
            if (isFalseConfident) falseConfidentCount++

            totalCells++

            // Per-shift precision & recall tracking
            val predictedCode = when (cellExtraction.normalizedShift) {
                "Evening" -> "E"
                "Night" -> "N"
                "Morning" -> "M"
                "Off" -> "OFF"
                "A" -> "A"
                else -> null
            }

            shiftCodes.forEach { s ->
                val isActualS = (gtCode == s)
                val isPredS = (predictedCode == s)
                if (isActualS && isPredS) {
                    tpMap[s] = (tpMap[s] ?: 0) + 1
                } else if (!isActualS && isPredS) {
                    fpMap[s] = (fpMap[s] ?: 0) + 1
                } else if (isActualS && !isPredS) {
                    fnMap[s] = (fnMap[s] ?: 0) + 1
                }
            }

            val cellObj = JSONObject()
            cellObj.put("dayNumber", d)
            cellObj.put("dateStr", dateStr)
            cellObj.put("weekday", weekday)
            cellObj.put("cropFile", cropFile.absolutePath)
            cellObj.put("x0", cropX)
            cellObj.put("y0", cropY)
            cellObj.put("width", cropW)
            cellObj.put("height", cropH)
            cellObj.put("groundTruth", gtCode)
            cellObj.put("expectedNormalized", expectedNorm)
            cellObj.put("rawBaseline", tBaseline ?: "")
            cellObj.put("rawUpscale", tUpscaled ?: "")
            cellObj.put("rawContrast", tContrast ?: "")
            cellObj.put("rawBinarized", tBinarized ?: "")
            cellObj.put("rawBorderCleaned", tBorderCleaned ?: "")
            cellObj.put("rawSharpUpscale", tSharpUpscale ?: "")
            cellObj.put("rawContrastSharp", tContrastSharp ?: "")
            cellObj.put("rawContrastOnly", tContrastOnly ?: "")
            cellObj.put("rawBinarizedSharp", tBinarizedSharp ?: "")
            cellObj.put("selectedVariant", cellExtraction.preprocessingVariant)
            cellObj.put("selectedRawToken", cellExtraction.rawToken ?: "")
            cellObj.put("selectedClassification", cellExtraction.classification)
            cellObj.put("finalNormalizedShift", cellExtraction.normalizedShift)
            cellObj.put("confidence", cellExtraction.confidence)
            cellObj.put("classificationState", classificationState)
            cellObj.put("reviewReason", cellExtraction.reviewReason ?: "")
            cellObj.put("reviewStatus", cellExtraction.reviewStatus)
            cellObj.put("isRawMatch", isRawMatch)
            cellObj.put("isNormalizedMatch", isNormMatch)
            cellObj.put("isCorrectlyResolved", isCorrectlyResolved)
            cellObj.put("isUnresolved", isUnresolved)
            cellObj.put("isFalseConfident", isFalseConfident)

            cellsArray.put(cellObj)

            fun fmtTok(t: String?): String {
                val s = (t ?: "<BLANK>").replace("\n", " ").trim()
                return if (s.length > 12) s.substring(0, 12) else s
            }

            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Day %2d | GT %-6s | B:%-9s U4:%-9s C4:%-9s Bi4:%-9s BC4:%-9s S4:%-9s CS4:%-9s C1:%-9s B3:%-9s | Sel %-15s | Norm %-10s | %s",
                    d, gtCode,
                    fmtTok(tBaseline), fmtTok(tUpscaled), fmtTok(tContrast), fmtTok(tBinarized), fmtTok(tBorderCleaned),
                    fmtTok(tSharpUpscale), fmtTok(tContrastSharp), fmtTok(tContrastOnly), fmtTok(tBinarizedSharp),
                    cellExtraction.preprocessingVariant,
                    cellExtraction.normalizedShift ?: "null",
                    if (isNormMatch) "MATCH" else "MISMATCH"
                )
            )
        }

        jsonReport.put("cells", cellsArray)
        jsonReport.put("totalDays", totalCells)
        jsonReport.put("rawOcrMatches", rawOcrMatches)
        jsonReport.put("rawOcrMatchRate", (rawOcrMatches.toDouble() / totalCells) * 100.0)
        jsonReport.put("normalizedShiftMatches", normalizedShiftMatches)
        jsonReport.put("normalizedShiftMatchRate", (normalizedShiftMatches.toDouble() / totalCells) * 100.0)
        jsonReport.put("correctlyResolvedCells", correctlyResolvedCells)
        jsonReport.put("correctlyResolvedRate", (correctlyResolvedCells.toDouble() / totalCells) * 100.0)
        jsonReport.put("unresolvedCount", unresolvedCount)
        jsonReport.put("falseConfidentCount", falseConfidentCount)

        val variantAccuracyObj = JSONObject()
        variantAccuracyObj.put("baseline1x", baselineMatches)
        variantAccuracyObj.put("baseline1xRate", (baselineMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("upscale4x", upscaleMatches)
        variantAccuracyObj.put("upscale4xRate", (upscaleMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("contrast4x", contrastMatches)
        variantAccuracyObj.put("contrast4xRate", (contrastMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("binarized4x", binarizedMatches)
        variantAccuracyObj.put("binarized4xRate", (binarizedMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("borderCleaned4x", borderCleanedMatches)
        variantAccuracyObj.put("borderCleaned4xRate", (borderCleanedMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("sharpUpscale4x", sharpUpscaleMatches)
        variantAccuracyObj.put("sharpUpscale4xRate", (sharpUpscaleMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("contrastSharp4x", contrastSharpMatches)
        variantAccuracyObj.put("contrastSharp4xRate", (contrastSharpMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("contrastOnly1x", contrastOnlyMatches)
        variantAccuracyObj.put("contrastOnly1xRate", (contrastOnlyMatches.toDouble() / totalCells) * 100.0)
        variantAccuracyObj.put("binarizedSharp3x", binarizedSharpMatches)
        variantAccuracyObj.put("binarizedSharp3xRate", (binarizedSharpMatches.toDouble() / totalCells) * 100.0)
        jsonReport.put("variantAccuracy", variantAccuracyObj)

        val prObj = JSONObject()
        Log.i(TAG, "------------------------------------------------------------------------------------------------------------------------------------")
        Log.i(TAG, "PER-SHIFT PRECISION & RECALL:")
        Log.i(TAG, "Shift | Ground Truth | TP | FP | FN | Precision | Recall")
        shiftCodes.forEach { s ->
            val tp = tpMap[s] ?: 0
            val fp = fpMap[s] ?: 0
            val fn = fnMap[s] ?: 0
            val gtCount = tp + fn
            val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 1.0
            val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0

            val shiftPr = JSONObject()
            shiftPr.put("groundTruthCount", gtCount)
            shiftPr.put("truePositives", tp)
            shiftPr.put("falsePositives", fp)
            shiftPr.put("falseNegatives", fn)
            shiftPr.put("precision", precision)
            shiftPr.put("recall", recall)
            prObj.put(s, shiftPr)

            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "%-5s | %12d | %2d | %2d | %2d | %8.1f%% | %6.1f%%",
                    s, gtCount, tp, fp, fn, precision * 100.0, recall * 100.0
                )
            )
        }
        jsonReport.put("perShiftMetrics", prObj)

        // 6. Generate Diagnostic Contact Sheet
        val contactSheet = DocumentImageProcessor.createDiagnosticContactSheet(
            sourceBitmap = srcBitmap,
            targetRowY = targetRowY,
            rowHeight = rowHeight,
            topBound = topBoundLimit ?: (targetRowY - rowHeight / 2f),
            bottomBound = bottomBoundLimit ?: (targetRowY + rowHeight / 2f),
            cells = allExtractions,
            representativeDays = listOf(1, 5, 13, 26, 29),
            title = "DIAGNOSTIC CONTACT SHEET: Akash ($targetStaffId) Row #17"
        )
        val outDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        outDir.mkdirs()
        val contactSheetFile = File(outDir, "diagnostic_contact_sheet.png")
        val savedSheet = DocumentImageProcessor.saveContactSheetToFile(contactSheet, contactSheetFile)
        Log.i(TAG, "Diagnostic Contact Sheet saved to ${contactSheetFile.absolutePath} (success=$savedSheet)")
        jsonReport.put("contactSheetFile", contactSheetFile.absolutePath)

        val reportFile = File(outDir, "actual_cell_ocr_diagnostic.json")
        reportFile.writeText(jsonReport.toString(2))
        Log.i(TAG, "Wrote diagnostic report JSON to: ${reportFile.absolutePath}")

        // Mirror to /sdcard/Download if accessible
        try {
            val downloadDir = File("/sdcard/Download")
            if (downloadDir.exists()) {
                val mirrorSheet = File(downloadDir, "diagnostic_contact_sheet.png")
                DocumentImageProcessor.saveContactSheetToFile(contactSheet, mirrorSheet)
                val mirrorReport = File(downloadDir, "actual_cell_ocr_diagnostic.json")
                mirrorReport.writeText(jsonReport.toString(2))
                Log.i(TAG, "Mirrored diagnostic files to /sdcard/Download")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice mirroring to /sdcard/Download: ${e.message}")
        }

        Log.i(TAG, "====================================================================================================================================")
        Log.i(TAG, "SUMMARY OF MEASURED EVALUATION METRICS (30 Cells):")
        Log.i(TAG, String.format(Locale.US, "1. Raw OCR Token Match Rate:      %.1f%% (%d/%d)", (rawOcrMatches.toDouble() / totalCells) * 100.0, rawOcrMatches, totalCells))
        Log.i(TAG, String.format(Locale.US, "2. Normalized Shift Match Rate:   %.1f%% (%d/%d)", (normalizedShiftMatches.toDouble() / totalCells) * 100.0, normalizedShiftMatches, totalCells))
        Log.i(TAG, String.format(Locale.US, "3. Correctly Resolved Cell Rate:  %.1f%% (%d/%d)", (correctlyResolvedCells.toDouble() / totalCells) * 100.0, correctlyResolvedCells, totalCells))
        Log.i(TAG, String.format(Locale.US, "4. Unresolved Cell Count:         %d", unresolvedCount))
        Log.i(TAG, String.format(Locale.US, "5. False Confident Predictions:   %d", falseConfidentCount))
        Log.i(TAG, "PREPROCESSING VARIANTS ISOLATED MATCH COUNTS:")
        Log.i(TAG, "PREPROCESSING VARIANTS ISOLATED MATCH COUNTS (normalized shift == verified ground truth, 30 cells):")
        Log.i(TAG, String.format(Locale.US, "   - Baseline 1x Padded:          %d/30 (%.1f%%)", baselineMatches, (baselineMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Upscale 4x Padded (bilinear): %d/30 (%.1f%%)", upscaleMatches, (upscaleMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Contrast Enhanced 4x (bilinear): %d/30 (%.1f%%)", contrastMatches, (contrastMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Binarized Otsu 4x (bilinear):   %d/30 (%.1f%%)", binarizedMatches, (binarizedMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Border-Cleaned Enhanced 4x:     %d/30 (%.1f%%)", borderCleanedMatches, (borderCleanedMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Sharp Upscale 4x (nearest-neighbor):   %d/30 (%.1f%%)", sharpUpscaleMatches, (sharpUpscaleMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Contrast+Sharp 4x (nearest-neighbor):  %d/30 (%.1f%%)", contrastSharpMatches, (contrastSharpMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Contrast Only 1x:                    %d/30 (%.1f%%)", contrastOnlyMatches, (contrastOnlyMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, String.format(Locale.US, "   - Binarized Otsu Sharp 3x:             %d/30 (%.1f%%)", binarizedSharpMatches, (binarizedSharpMatches.toDouble() / totalCells) * 100.0))
        Log.i(TAG, "====================================================================================================================================")

        assertTrue("Diagnostic test completed for all 30 days", totalCells == 30)
    }
}
