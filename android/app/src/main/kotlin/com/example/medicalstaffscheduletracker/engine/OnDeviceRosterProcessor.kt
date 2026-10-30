package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

class OnDeviceRosterProcessor(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val inferenceEngine = LocalSectionInferenceEngine()

    companion object {
        private const val TAG = "MedicalScheduleTracker"
    }

    /**
     * Safely decodes an image Uri, reads EXIF orientation metadata, and rotates the bitmap upright.
     */
    private fun decodeBitmapWithExif(uri: Uri): Pair<Bitmap?, Int> {
        val original: Bitmap = try {
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            stream?.use { BitmapFactory.decodeStream(it) } ?: return null to 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode bitmap stream for $uri", e)
            return null to 0
        }

        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            val exifStream = context.contentResolver.openInputStream(uri)
            if (exifStream != null) {
                exifStream.use { s ->
                    val exif = ExifInterface(s)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ExifInterface inspection notice: ${e.message}")
        }

        val matrix = Matrix()
        var needsRotation = true
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> needsRotation = false
        }

        if (!needsRotation) {
            return original to orientation
        }

        return try {
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated != original) {
                original.recycle()
            }
            rotated to orientation
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap rotation failed, using unrotated bitmap: ${e.message}")
            original to orientation
        }
    }

    var lastDiagnosticReport: RosterDiagnosticReport? = null
        private set

    fun updateCloudBenchmarkInReport(summary: CloudBenchmarkSummary) {
        lastDiagnosticReport = lastDiagnosticReport?.copy(cloudBenchmark = summary)
    }

    private fun inspectFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "unknown"
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice inspecting OpenableColumns: ${e.message}")
        }
        if (size <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    size = s.available().toLong()
                }
            } catch (_: Exception) {}
        }
        return name to size
    }

    private fun applyGentleContrastEnhancement(src: Bitmap, contrast: Float = 1.25f): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(dest)
        val paint = android.graphics.Paint()
        val cm = android.graphics.ColorMatrix()
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(android.graphics.ColorMatrix(contrastMatrix))
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    /**
     * Phase 1: Analyzes the selected roster file (Image/PDF), performs on-device OCR,
     * reconstructs adaptive 2D table geometry, extracts detected staff rows, and determines month.
     */
    suspend fun analyzeRosterFile(
        uri: Uri,
        onStageUpdate: (RosterProcessingStage, String) -> Unit = { _, _ -> }
    ): Result<TableMetadata> = withContext(Dispatchers.IO) {
        val startTimeTotal = System.currentTimeMillis()
        try {
            RosterProcessingStage.FILE_SELECTED.log("URI: $uri")
            onStageUpdate(RosterProcessingStage.FILE_SELECTED, "Roster file selected from device storage")

            val (fileName, fileSizeBytes) = inspectFileMetadata(uri)
            val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""
            val isPdf = mimeType.contains("pdf") || uri.toString().lowercase().endsWith(".pdf") || fileName.lowercase().endsWith(".pdf")

            // Inspect encoded dimensions before full decoding
            var encodedWidth = -1
            var encodedHeight = -1
            var encodedMime = mimeType
            if (!isPdf) {
                try {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        BitmapFactory.decodeStream(s, null, boundsOptions)
                    }
                    encodedWidth = boundsOptions.outWidth
                    encodedHeight = boundsOptions.outHeight
                    boundsOptions.outMimeType?.let { encodedMime = it }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice checking image bounds: ${e.message}")
                }
            }

            val isUndersized = !isPdf && (encodedWidth in 1..799 || encodedHeight in 1..499)
            val undersizedWarning = if (isUndersized) {
                "Selected file ($encodedWidth x $encodedHeight px, ${fileSizeBytes / 1024} KB) appears to be an undersized thumbnail, compressed preview, or screenshot. Characters in table rows are only ~3-4px tall, below on-device OCR detection thresholds. Please import the original full-resolution photo or PDF document."
            } else null

            val fileDetails = FileSelectionDetails(
                fileName = fileName,
                mimeType = if (encodedMime.isNotBlank()) encodedMime else mimeType,
                encodedSizeBytes = fileSizeBytes,
                isUndersized = isUndersized,
                undersizedWarning = undersizedWarning
            )

            RosterProcessingStage.FILE_VALIDATED.log("File: $fileName, MIME: $encodedMime, Encoded: ${encodedWidth}x${encodedHeight}, Size: ${fileSizeBytes}B")
            onStageUpdate(RosterProcessingStage.FILE_VALIDATED, "File format validated (${if (isPdf) "PDF document" else "Image ${encodedWidth}x$encodedHeight"})")

            val bitmaps = mutableListOf<Bitmap>()
            var exifOrientation = 1
            var prepDesc = "None (natural resolution)"
            var prepScale = 1.0f
            var prepW = encodedWidth
            var prepH = encodedHeight
            var bitmapConfig = "UNKNOWN"

            var unscaledOriginalBitmap: android.graphics.Bitmap? = null
            if (isPdf) {
                RosterProcessingStage.PDF_RENDERED.log("Rendering PDF pages locally with native PdfRenderer")
                onStageUpdate(RosterProcessingStage.PDF_RENDERED, "Rendering PDF pages into high-resolution bitmaps...")
                val renderedPages = PdfRendererHelper.renderPdfPagesToBitmaps(context, uri, maxPages = 2)
                if (renderedPages.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not render pages from the selected PDF. Please ensure the PDF is not password protected."))
                }
                unscaledOriginalBitmap = renderedPages.firstOrNull()
                bitmaps.addAll(renderedPages)
                prepW = renderedPages.first().width
                prepH = renderedPages.first().height
                bitmapConfig = renderedPages.first().config?.name ?: "ARGB_8888"
                prepDesc = "Native PDF rasterization at 200 DPI"
            } else {
                onStageUpdate(RosterProcessingStage.FILE_VALIDATED, "Decoding image and correcting EXIF orientation...")
                val (decodedBitmap, orientation) = decodeBitmapWithExif(uri)
                if (decodedBitmap == null) {
                    return@withContext Result.failure(Exception("Failed to decode image from the selected file. Please verify file format."))
                }
                unscaledOriginalBitmap = decodedBitmap
                exifOrientation = orientation
                bitmapConfig = decodedBitmap.config?.name ?: "ARGB_8888"
                prepW = decodedBitmap.width
                prepH = decodedBitmap.height

                // Adaptive Resolution-Preserving Preprocessing:
                // If table characters are small (< 8-10px tall, typical when width in 800..1900 or height in 500..1300):
                // Supersample up to 2.2x and enhance contrast so characters trigger ML Kit's 12-16px receptive field.
                val optimalScale = minOf(2.2f, maxOf(1.0f, 2035f / decodedBitmap.width, 1175f / decodedBitmap.height))
                if (optimalScale > 1.10f && !isUndersized) {
                    prepScale = optimalScale
                    val scaledW = (decodedBitmap.width * optimalScale).toInt()
                    val scaledH = (decodedBitmap.height * optimalScale).toInt()
                    val scaled = Bitmap.createScaledBitmap(decodedBitmap, scaledW, scaledH, true)
                    val contrastEnhanced = applyGentleContrastEnhancement(scaled, 1.25f)
                    if (scaled != contrastEnhanced && scaled != decodedBitmap) {
                        scaled.recycle()
                    }
                    bitmaps.add(contrastEnhanced)
                    prepDesc = "Bilinear supersampling (${String.format(Locale.US, "%.2f", optimalScale)}x to ${scaledW}x${scaledH} px) + gentle contrast (+25%)"
                    prepW = scaledW
                    prepH = scaledH
                } else {
                    bitmaps.add(decodedBitmap)
                    prepDesc = if (isUndersized) "Undersized preview decoded without upscaling" else "Natural resolution"
                }
            }

            val decodingDetails = DecodingDetails(
                decodedWidth = if (encodedWidth > 0) encodedWidth else prepW,
                decodedHeight = if (encodedHeight > 0) encodedHeight else prepH,
                bitmapConfig = bitmapConfig,
                exifOrientation = exifOrientation,
                preprocessingApplied = prepDesc,
                preprocessedWidth = prepW,
                preprocessedHeight = prepH,
                scaleFactor = prepScale
            )

            val activeProvider = OcrKeyManager.getActiveProvider(context)
            val selectedEngine = OcrKeyManager.getActiveEngine(context)

            val ocrStartTime = System.currentTimeMillis()
            val allOcrWords = mutableListOf<OcrWord>()
            var fullRecognizedText = ""
            var ocrEngineName = ""
            var ocrDurationMs = 0L

            var useMlKitFallback = activeProvider != OcrProviderType.OCR_SPACE
            if (activeProvider == OcrProviderType.OCR_SPACE) {
                onStageUpdate(RosterProcessingStage.OCR_COMPLETED, "Submitting roster to OCR.space (Engine $selectedEngine)...")
                Log.i(TAG, "Executing primary OCR via OCR.space Engine $selectedEngine")

                val rawFileBytes = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }

                if (rawFileBytes == null || rawFileBytes.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not read binary stream from selected file: $fileName"))
                }

                val ocrResult = OcrSpaceClient.parseFile(
                    context = context,
                    rawFileBytes = rawFileBytes,
                    fileName = fileName,
                    mimeType = if (mimeType.isNotBlank()) mimeType else "image/jpeg",
                    engine = selectedEngine
                )

                if (ocrResult.isSuccess) {
                    val extraction = ocrResult.getOrThrow()
                    ocrEngineName = extraction.engine
                    ocrDurationMs = extraction.durationMs
                    fullRecognizedText = extraction.fullText
                    allOcrWords.addAll(extraction.words)
                    if (allOcrWords.isEmpty()) {
                        Log.w(TAG, "OCR.space returned 0 word boxes; falling back to on-device Google ML Kit")
                        useMlKitFallback = true
                    }
                } else {
                    val err = ocrResult.exceptionOrNull()
                    Log.w(TAG, "OCR.space primary extraction failed (${err?.message}); falling back to on-device Google ML Kit", err)
                    useMlKitFallback = true
                }
            }

            if (useMlKitFallback) {
                // Local On-Device Provider / Fallback: Google ML Kit On-Device
                ocrEngineName = "Google ML Kit (On-Device Latin)"
                for ((pageIdx, bitmap) in bitmaps.withIndex()) {
                    val pageNum = pageIdx + 1
                    onStageUpdate(RosterProcessingStage.OCR_COMPLETED, "Running ML Kit text recognition on-device (Page $pageNum/${bitmaps.size})...")

                    val image = InputImage.fromBitmap(bitmap, 0)
                    val visionText = textRecognizer.process(image).awaitTask()
                    fullRecognizedText += visionText.text + "\n"

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                val box = element.boundingBox
                                val text = element.text.trim()
                                if (box != null && text.isNotEmpty()) {
                                    allOcrWords.add(
                                        OcrWord(
                                            text = text,
                                            x = box.left.toFloat() / prepScale,
                                            y = box.top.toFloat() / prepScale,
                                            width = box.width().toFloat() / prepScale,
                                            height = box.height().toFloat() / prepScale,
                                            confidence = element.confidence
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                ocrDurationMs = System.currentTimeMillis() - ocrStartTime
            }

            val sampleTokens = allOcrWords.take(20).map { it.text }
            val ocrDetails = OcrExecutionDetails(
                engineName = ocrEngineName,
                ocrDurationMs = ocrDurationMs,
                extractedWordCount = allOcrWords.size,
                sampleTokens = sampleTokens
            )

            RosterProcessingStage.OCR_COMPLETED.log("$ocrEngineName extracted ${allOcrWords.size} words in ${ocrDurationMs}ms")
            if (allOcrWords.isEmpty()) {
                val msg = if (isUndersized) {
                    "No readable text recognized. The selected file is low-resolution ($encodedWidth x $encodedHeight). Please select the original document photo or scan."
                } else {
                    "No readable text recognized by $ocrEngineName. Please ensure the document is clear, focused, and right-side up."
                }
                return@withContext Result.failure(Exception(msg))
            }

            onStageUpdate(RosterProcessingStage.DATE_HEADERS_DETECTED, "Reconstructing adaptive 2D table geometry & detecting date columns...")
            val canonicalW = unscaledOriginalBitmap?.width ?: prepW
            val canonicalH = unscaledOriginalBitmap?.height ?: prepH
            val reconstructor = LocalTableReconstructor(words = allOcrWords)
            val baseMetadata = reconstructor.analyzeRosterStructure().copy(
                sourceImageWidth = canonicalW,
                sourceImageHeight = canonicalH,
                coordinateSystem = "CANONICAL_SOURCE_PIXELS"
            )

            RosterProcessingStage.DATE_HEADERS_DETECTED.log("Detected month: ${baseMetadata.monthInfo.displayName} (Confident: ${baseMetadata.monthInfo.isConfident})")
            RosterProcessingStage.STAFF_ROWS_RECONSTRUCTED.log("Reconstructed ${baseMetadata.detectedStaff.size} staff rows")

            // CANONICAL COORDINATE SYSTEM FIX:
            // All coordinates in allOcrWords and baseMetadata are normalized to unscaledOriginalBitmap (e.g. 1131x653).
            // Therefore, reportBitmap MUST ALWAYS be unscaledOriginalBitmap (or first page at 1:1 scale)
            // so that any subsequent row or cell crops map 1:1 with metadata coordinates without scaling mismatch.
            val reportBitmap = unscaledOriginalBitmap ?: bitmaps.firstOrNull()

            val report = RosterDiagnosticReport(
                timestamp = System.currentTimeMillis(),
                provider = activeProvider,
                fileDetails = fileDetails,
                decodingDetails = decodingDetails,
                ocrDetails = ocrDetails,
                fullRecognizedText = fullRecognizedText,
                rawWords = allOcrWords,
                detectedHeaders = baseMetadata.metaBounds,
                dayColumnCount = baseMetadata.dayIntervals.size,
                candidateRows = baseMetadata.candidateRows,
                acceptedStaffRows = baseMetadata.detectedStaff,
                warnings = baseMetadata.warnings + listOfNotNull(undersizedWarning),
                lastDecodedBitmap = reportBitmap
            )
            lastDiagnosticReport = report

            val metadata = baseMetadata.copy(
                rawText = fullRecognizedText,
                providerName = ocrEngineName,
                report = report
            )

            Log.i(
                TAG,
                "Roster Import Summary: Provider='$ocrEngineName', File='${fileDetails.fileName}' (${fileDetails.mimeType}, ${fileDetails.encodedSizeBytes}B), " +
                "Decoded=${decodingDetails.decodedWidth}x${decodingDetails.decodedHeight}, OCR=${ocrDetails.ocrDurationMs}ms (${ocrDetails.extractedWordCount} words), " +
                "StaffRows=${metadata.detectedStaff.size}, Month=${metadata.monthInfo.displayName}"
            )

            onStageUpdate(
                RosterProcessingStage.STAFF_ROWS_RECONSTRUCTED,
                "Found ${metadata.detectedStaff.size} staff members and ${metadata.dayIntervals.size} date columns."
            )

            if (metadata.detectedStaff.isEmpty()) {
                val warningMsg = if (isUndersized) {
                    "Only ${allOcrWords.size} words recognized because the selected file is an undersized preview ($encodedWidth x $encodedHeight px). Please import the original full-resolution document, or use manual row selection."
                } else {
                    "Found ${allOcrWords.size} text words via $ocrEngineName, but no employee table rows could be identified automatically. You can enter your Staff ID and select your row manually."
                }
                Log.w(TAG, warningMsg)
                val updatedWarnings = metadata.warnings + listOf(warningMsg)
                onStageUpdate(RosterProcessingStage.AWAITING_STAFF_SELECTION, "Automatic row detection incomplete. Enter Staff ID or select row manually.")
                return@withContext Result.success(metadata.copy(warnings = updatedWarnings))
            }

            onStageUpdate(RosterProcessingStage.AWAITING_STAFF_SELECTION, "Please select your staff profile from the detected roster list.")
            Result.success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing roster file", e)
            Result.failure(e)
        }
    }

    /**
     * Phase 2: Given the analyzed table metadata and the user's confirmed staff member,
     * extracts shift cells, runs inference, and builds the DraftSchedule.
     * Supports manual row selection even when whole-roster OCR did not detect the entered employee ID.
     */
    suspend fun extractScheduleForStaff(
        metadata: TableMetadata,
        selectedStaffId: String,
        targetRowY: Float? = null,
        matchedRowRawId: String? = null,
        staffNameHint: String? = null,
        onStageUpdate: (RosterProcessingStage, String) -> Unit = { _, _ -> }
    ): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            RosterProcessingStage.STAFF_SELECTED.log("Selected staff: $selectedStaffId")
            onStageUpdate(RosterProcessingStage.STAFF_SELECTED, "Extracting shifts for staff $selectedStaffId...")

            val normalizedSelected = IdMatcher.normalizeId(selectedStaffId)
            val hasDirectRowMatch = metadata.rows.any { row ->
                row.rawId == selectedStaffId ||
                    row.normalizedId == normalizedSelected ||
                    row.name.equals(selectedStaffId, ignoreCase = true) ||
                    IdMatcher.areIdsConfusable(selectedStaffId, row.normalizedId ?: "").first
            }

            val effectiveMetadata = if (!hasDirectRowMatch) {
                val matchedExistingRow = metadata.rows.find { row ->
                    (!matchedRowRawId.isNullOrBlank() && (row.rawId == matchedRowRawId || row.normalizedId == IdMatcher.normalizeId(matchedRowRawId))) ||
                        (targetRowY != null && Math.abs(row.y - targetRowY) <= 18f)
                }
                val synthRow = if (matchedExistingRow != null) {
                    matchedExistingRow.copy(
                        rawId = selectedStaffId,
                        normalizedId = normalizedSelected,
                        name = staffNameHint ?: matchedExistingRow.name ?: "Staff ($selectedStaffId)",
                        y = targetRowY ?: matchedExistingRow.y
                    )
                } else if (targetRowY != null) {
                    val nearbyWords = metadata.report?.rawWords?.filter { Math.abs(it.cy - targetRowY) <= 18f } ?: emptyList()
                    ReconstructedRow(
                        serialNumber = null,
                        rawId = selectedStaffId,
                        normalizedId = normalizedSelected,
                        name = staffNameHint ?: "Staff ($selectedStaffId)",
                        weeklyOffDay = null,
                        floor = null,
                        y = targetRowY,
                        shiftWords = nearbyWords
                    )
                } else null

                if (synthRow != null) {
                    metadata.copy(rows = metadata.rows + synthRow)
                } else {
                    metadata
                }
            } else {
                metadata
            }

            val reconstructor = LocalTableReconstructor(words = emptyList())
            val reconResult = reconstructor.reconstructForStaff(effectiveMetadata, selectedStaffId)

            if (reconResult.status == "id_not_found") {
                val detectedList = metadata.detectedStaff.joinToString { "${it.rawId} (${it.name})" }
                return@withContext Result.failure(
                    Exception("Staff ID '$selectedStaffId' was not found in the roster. Detected employees: $detectedList")
                )
            }

            RosterProcessingStage.SHIFTS_EXTRACTED.log("Extracted ${reconResult.entries.size} shift cells for ${reconResult.matchedStaffId}")
            onStageUpdate(RosterProcessingStage.SHIFTS_EXTRACTED, "Applying contiguous shift inference and weekly OFF propagation...")

            val draftSchedule = inferenceEngine.inferSections(reconResult).let { draft ->
                val cleanId = selectedStaffId.trim()
                if (cleanId.isNotBlank() && !cleanId.startsWith("MANUAL_ROW_", ignoreCase = true) && !cleanId.startsWith("ROW_", ignoreCase = true)) {
                    draft.copy(
                        staffId = cleanId,
                        staffName = staffNameHint ?: draft.staffName
                    )
                } else {
                    draft
                }
            }

            RosterProcessingStage.DRAFT_READY.log("Draft schedule generated for ${draftSchedule.staffId} (${draftSchedule.month})")
            onStageUpdate(RosterProcessingStage.DRAFT_READY, "Draft schedule ready for review.")

            Result.success(draftSchedule)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting schedule for staff $selectedStaffId", e)
            Result.failure(e)
        }
    }

    /**
     * Phase 2b: Focused Row OCR refinement on the confirmed employee row.
     * Crops the unscaled source bitmap for the complete row across all date columns,
     * applies conservative contrast enhancement, runs OCR.space Engine 2,
     * remaps crop coordinates back to sheet-level coordinates, and merges tokens with whole-roster OCR.
     */
    suspend fun refineRowWithFocusedOcr(
        context: Context,
        metadata: TableMetadata,
        selectedStaffId: String,
        cropStartY: Int,
        cropHeight: Int,
        matchedRowRawId: String? = null,
        staffNameHint: String? = null,
        onStageUpdate: (RosterProcessingStage, String) -> Unit = { _, _ -> }
    ): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            val src = metadata.report?.lastDecodedBitmap
            if (src == null) {
                return@withContext extractScheduleForStaff(
                    metadata = metadata,
                    selectedStaffId = selectedStaffId,
                    targetRowY = cropStartY.toFloat(),
                    matchedRowRawId = matchedRowRawId,
                    staffNameHint = staffNameHint,
                    onStageUpdate = onStageUpdate
                )
            }

            val safeStartY = cropStartY.coerceIn(0, (src.height - 10).coerceAtLeast(0))
            val safeHeight = cropHeight.coerceIn(10, src.height - safeStartY)

            onStageUpdate(RosterProcessingStage.OCR_COMPLETED, "Cropping row at Y=$safeStartY (height: ${safeHeight}px) & applying contrast enhancement...")
            val rowCrop = Bitmap.createBitmap(src, 0, safeStartY, src.width, safeHeight)
            val enhancedCrop = applyGentleContrastEnhancement(rowCrop, 1.25f)

            onStageUpdate(RosterProcessingStage.OCR_COMPLETED, "Submitting focused row crop to OCR.space Engine 2 (cloud processing)...")
            Log.i(TAG, "Executing focused row OCR via OCR.space Engine 2 for $selectedStaffId")

            val focusedResult = OcrSpaceClient.parseBitmap(
                context = context,
                bitmap = enhancedCrop,
                fileName = "employee_${selectedStaffId}_row_crop.jpg",
                engine = "2"
            )

            var matchedAnyRow = false
            val updatedRows = metadata.rows.map { row ->
                val isTarget = row.normalizedId == IdMatcher.normalizeId(selectedStaffId) ||
                    row.name.equals(selectedStaffId, ignoreCase = true) ||
                    row.rawId == selectedStaffId ||
                    (!matchedRowRawId.isNullOrBlank() && (row.rawId == matchedRowRawId || row.normalizedId == IdMatcher.normalizeId(matchedRowRawId))) ||
                    IdMatcher.areIdsConfusable(selectedStaffId, row.normalizedId ?: "").first ||
                    Math.abs(row.y - safeStartY.toFloat()) <= 18f

                if (isTarget) {
                    matchedAnyRow = true
                    if (focusedResult.isSuccess) {
                        val focusedWords = focusedResult.getOrThrow().words.map { w ->
                            // Remap Y coordinate from crop-relative to sheet-relative
                            w.copy(y = w.y + safeStartY)
                        }
                        Log.i(TAG, "Focused row OCR recognized ${focusedWords.size} words for $selectedStaffId (previously ${row.shiftWords.size})")

                        // Merge whole-roster words and focused words:
                        val combinedWords = mutableListOf<OcrWord>()
                        combinedWords.addAll(focusedWords)
                        for (origWord in row.shiftWords) {
                            // If no focused word overlaps closely in X, keep original word
                            val overlaps = focusedWords.any { Math.abs(it.cx - origWord.cx) < 15f }
                            if (!overlaps) {
                                combinedWords.add(origWord)
                            }
                        }
                        combinedWords.sortBy { it.x }
                        row.copy(
                            rawId = selectedStaffId,
                            normalizedId = IdMatcher.normalizeId(selectedStaffId),
                            name = staffNameHint ?: row.name,
                            shiftWords = combinedWords
                        )
                    } else {
                        row.copy(
                            rawId = selectedStaffId,
                            normalizedId = IdMatcher.normalizeId(selectedStaffId),
                            name = staffNameHint ?: row.name
                        )
                    }
                } else {
                    row
                }
            }.toMutableList()

            if (!matchedAnyRow) {
                val focusedWords = if (focusedResult.isSuccess) {
                    focusedResult.getOrThrow().words.map { w -> w.copy(y = w.y + safeStartY) }
                } else emptyList()
                updatedRows.add(
                    ReconstructedRow(
                        serialNumber = null,
                        rawId = selectedStaffId,
                        normalizedId = IdMatcher.normalizeId(selectedStaffId),
                        name = staffNameHint ?: "Staff ($selectedStaffId)",
                        weeklyOffDay = null,
                        floor = null,
                        y = safeStartY.toFloat(),
                        shiftWords = focusedWords
                    )
                )
            }

            val updatedMetadata = metadata.copy(rows = updatedRows)
            return@withContext extractScheduleForStaff(
                metadata = updatedMetadata,
                selectedStaffId = selectedStaffId,
                targetRowY = safeStartY.toFloat(),
                matchedRowRawId = matchedRowRawId,
                staffNameHint = staffNameHint,
                onStageUpdate = onStageUpdate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in focused row OCR refinement", e)
            return@withContext extractScheduleForStaff(
                metadata = metadata,
                selectedStaffId = selectedStaffId,
                targetRowY = cropStartY.toFloat(),
                matchedRowRawId = matchedRowRawId,
                staffNameHint = staffNameHint,
                onStageUpdate = onStageUpdate
            )
        }
    }

    private val cellOcrProcessor = CellOcrProcessor(textRecognizer)

    /**
     * Phase 2c: High-accuracy Cell-Level OCR.
     * Replaces whole-row-only crop by segmenting individual daily columns,
     * adding vertical safety margin (+5px) to prevent character clipping,
     * executing local on-device ML Kit Latin OCR as primary engine with contrast/binarization
     * recovery variants, and optional OCR.space cloud fallback.
     */
    suspend fun refineRowWithCellLevelOcr(
        context: Context,
        metadata: TableMetadata,
        selectedStaffId: String,
        targetRowY: Float,
        rowHeight: Float? = null,
        useCloudFallback: Boolean = false,
        verticalMargin: Float = 0.5f,
        matchedRowRawId: String? = null,
        staffNameHint: String? = null,
        onStageUpdate: (RosterProcessingStage, String) -> Unit = { _, _ -> }
    ): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            val src = metadata.report?.lastDecodedBitmap
            if (src == null) {
                Log.w(TAG, "No decoded bitmap available for cell-level OCR, falling back to basic extraction")
                return@withContext extractScheduleForStaff(
                    metadata = metadata,
                    selectedStaffId = selectedStaffId,
                    targetRowY = targetRowY,
                    matchedRowRawId = matchedRowRawId,
                    staffNameHint = staffNameHint,
                    onStageUpdate = onStageUpdate
                )
            }

            val staffMember = metadata.detectedStaff.find {
                it.rawId == selectedStaffId ||
                    it.normalizedId == IdMatcher.normalizeId(selectedStaffId) ||
                    (!matchedRowRawId.isNullOrBlank() && (it.rawId == matchedRowRawId || it.normalizedId == IdMatcher.normalizeId(matchedRowRawId))) ||
                    it.name.equals(selectedStaffId, ignoreCase = true) ||
                    IdMatcher.areIdsConfusable(selectedStaffId, it.normalizedId).first ||
                    Math.abs(it.rowY - targetRowY) <= 15f
            }
            val targetRow = metadata.rows.find {
                it.rawId == selectedStaffId ||
                    it.normalizedId == IdMatcher.normalizeId(selectedStaffId) ||
                    (!matchedRowRawId.isNullOrBlank() && (it.rawId == matchedRowRawId || it.normalizedId == IdMatcher.normalizeId(matchedRowRawId))) ||
                    it.name.equals(selectedStaffId, ignoreCase = true) ||
                    IdMatcher.areIdsConfusable(selectedStaffId, it.normalizedId ?: "").first ||
                    Math.abs(it.y - targetRowY) <= 15f
            }

            // Derive target row height and bounds from table geometry:
            // Never assume 26px when target table rows are ~12-14px tall.
            val effectiveRowHeight = rowHeight
                ?: staffMember?.rowHeight
                ?: targetRow?.rowHeight
                ?: 13f

            val topBoundLimit = staffMember?.topBound ?: targetRow?.topBound
            val bottomBoundLimit = staffMember?.bottomBound ?: targetRow?.bottomBound

            val cal = Calendar.getInstance()
            cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, 1)
            val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            onStageUpdate(
                RosterProcessingStage.OCR_COMPLETED,
                "Running cell-level OCR across $numDays day columns at Y=${targetRowY.toInt()} (height: ${effectiveRowHeight.toInt()}px, Google ML Kit on-device)..."
            )

            val cellWords = mutableListOf<OcrWord>()
            val cellExtractions = mutableMapOf<Int, CellCropExtraction>()

            for (d in 1..numDays) {
                val (leftBound, rightBound) = metadata.dayIntervals[d] ?: (0f to 0f)
                if (leftBound <= 0f && rightBound <= 0f) continue

                cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, d)
                val dateStr = String.format(Locale.US, "%04d-%02d-%02d", metadata.monthInfo.year, metadata.monthInfo.month, d)
                val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: ""

                val cellResult = cellOcrProcessor.processDailyCell(
                    sourceBitmap = src,
                    dayNumber = d,
                    dateStr = dateStr,
                    weekday = weekday,
                    leftBound = leftBound,
                    rightBound = rightBound,
                    rowCenterY = targetRowY,
                    rowHeight = effectiveRowHeight,
                    verticalMargin = verticalMargin,
                    useCloudFallback = useCloudFallback,
                    context = context,
                    topBoundLimit = topBoundLimit,
                    bottomBoundLimit = bottomBoundLimit,
                    sourceWidthHint = metadata.sourceImageWidth,
                    sourceHeightHint = metadata.sourceImageHeight
                )

                cellExtractions[d] = cellResult
                if (!cellResult.rawToken.isNullOrBlank()) {
                    cellWords.add(
                        OcrWord(
                            text = cellResult.rawToken,
                            x = leftBound,
                            y = targetRowY - effectiveRowHeight / 2,
                            width = rightBound - leftBound,
                            height = effectiveRowHeight,
                            confidence = cellResult.confidence.toFloat()
                        )
                    )
                }
            }

            Log.i(TAG, "Cell-level OCR completed: recognized ${cellWords.size}/$numDays daily cell tokens on-device")

            // Update or synthesize row in metadata.rows while preserving selectedStaffId
            var matchedExisting = false
            val updatedRows = metadata.rows.map { row ->
                val isTarget = row.normalizedId == IdMatcher.normalizeId(selectedStaffId) ||
                    row.name.equals(selectedStaffId, ignoreCase = true) ||
                    row.rawId == selectedStaffId ||
                    (!matchedRowRawId.isNullOrBlank() && (row.rawId == matchedRowRawId || row.normalizedId == IdMatcher.normalizeId(matchedRowRawId))) ||
                    IdMatcher.areIdsConfusable(selectedStaffId, row.normalizedId ?: "").first ||
                    Math.abs(row.y - targetRowY) <= 15f

                if (isTarget && !matchedExisting) {
                    matchedExisting = true
                    // Merge existing shift words with new cell-level words
                    val merged = mutableListOf<OcrWord>()
                    merged.addAll(cellWords)
                    for (orig in row.shiftWords) {
                        if (cellWords.none { Math.abs(it.cx - orig.cx) < 15f }) {
                            merged.add(orig)
                        }
                    }
                    merged.sortBy { it.x }
                    row.copy(
                        rawId = selectedStaffId,
                        normalizedId = IdMatcher.normalizeId(selectedStaffId),
                        name = staffNameHint ?: row.name,
                        shiftWords = merged,
                        y = targetRowY
                    )
                } else row
            }.toMutableList()

            if (!matchedExisting) {
                // Synthesize row for manual staff row selection
                val synthRow = ReconstructedRow(
                    serialNumber = null,
                    rawId = selectedStaffId,
                    normalizedId = IdMatcher.normalizeId(selectedStaffId),
                    name = staffNameHint ?: "Staff $selectedStaffId",
                    floor = staffMember?.floor,
                    weeklyOffDay = staffMember?.weeklyOffDay,
                    y = targetRowY,
                    shiftWords = cellWords
                )
                updatedRows.add(synthRow)
            }

            val updatedMetadata = metadata.copy(rows = updatedRows)
            val scheduleResult = extractScheduleForStaff(
                metadata = updatedMetadata,
                selectedStaffId = selectedStaffId,
                targetRowY = targetRowY,
                matchedRowRawId = matchedRowRawId,
                staffNameHint = staffNameHint,
                onStageUpdate = onStageUpdate
            )

            scheduleResult.map { draft ->
                // Augment draft cells with exact cell OCR extraction provenance & bounding boxes
                val augmentedEntries = draft.entries.map { entry ->
                    val cellExt = cellExtractions[entry.dayNumber]
                    if (cellExt != null && !cellExt.rawToken.isNullOrBlank()) {
                        val bbox = "[${cellExt.leftBound.toInt()}, ${cellExt.topBound.toInt()}, ${(cellExt.rightBound - cellExt.leftBound).toInt()}x${(cellExt.bottomBound - cellExt.topBound).toInt()}]"
                        entry.copy(
                            rawValue = cellExt.rawToken,
                            classification = if (entry.classification.isNotBlank()) entry.classification else cellExt.classification,
                            ocrBoundingBox = bbox,
                            ocrConfidence = cellExt.confidence
                        )
                    } else {
                        entry
                    }
                }
                val cleanId = selectedStaffId.trim()
                draft.copy(
                    staffId = if (cleanId.isNotBlank()) cleanId else draft.staffId,
                    staffName = staffNameHint ?: draft.staffName,
                    entries = augmentedEntries
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cell-level OCR refinement", e)
            return@withContext extractScheduleForStaff(
                metadata = metadata,
                selectedStaffId = selectedStaffId,
                targetRowY = targetRowY,
                matchedRowRawId = matchedRowRawId,
                staffNameHint = staffNameHint,
                onStageUpdate = onStageUpdate
            )
        }
    }
}
