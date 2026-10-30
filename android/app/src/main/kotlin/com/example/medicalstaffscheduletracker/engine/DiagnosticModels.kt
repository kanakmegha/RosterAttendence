package com.example.medicalstaffscheduletracker.engine

import android.graphics.Bitmap

/**
 * Diagnostic models for inspecting image decoding, OCR recognition, table geometry,
 * candidate employee row rejections, and opt-in benchmarks.
 */

data class FileSelectionDetails(
    val fileName: String,
    val mimeType: String,
    val encodedSizeBytes: Long,
    val isUndersized: Boolean,
    val undersizedWarning: String?
)

data class DecodingDetails(
    val decodedWidth: Int,
    val decodedHeight: Int,
    val bitmapConfig: String,
    val exifOrientation: Int,
    val preprocessingApplied: String,
    val preprocessedWidth: Int,
    val preprocessedHeight: Int,
    val scaleFactor: Float
)

data class OcrExecutionDetails(
    val engineName: String = "Google ML Kit On-Device Latin OCR",
    val ocrDurationMs: Long,
    val extractedWordCount: Int,
    val sampleTokens: List<String>
)

data class CandidateRowEvaluation(
    val clusterIndex: Int,
    val rowY: Float,
    val rawTokens: List<String>,
    val rawId: String?,
    val name: String?,
    val floor: String?,
    val offDay: String?,
    val shiftWordCount: Int,
    val isAccepted: Boolean,
    val rejectionStage: String?, // "HEADER_ZONE", "FOOTER_KEYWORD", "NO_ID_NAME_OR_SHIFTS", "SUMMARY_KEYWORD", or null if accepted
    val decisionReason: String
)

data class CloudBenchmarkSummary(
    val engine: String,
    val durationMs: Long,
    val extractedWordCount: Int,
    val sampleText: String,
    val rawResponseSummary: String
)

data class RosterDiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val provider: OcrProviderType = OcrProviderType.OCR_SPACE,
    val fileDetails: FileSelectionDetails,
    val decodingDetails: DecodingDetails,
    val ocrDetails: OcrExecutionDetails,
    val fullRecognizedText: String = "",
    val rawWords: List<OcrWord>,
    val detectedHeaders: Map<String, Float>,
    val dayColumnCount: Int,
    val candidateRows: List<CandidateRowEvaluation>,
    val acceptedStaffRows: List<DetectedStaffMember>,
    val warnings: List<String>,
    val lastDecodedBitmap: Bitmap? = null,
    val cloudBenchmark: CloudBenchmarkSummary? = null
)
