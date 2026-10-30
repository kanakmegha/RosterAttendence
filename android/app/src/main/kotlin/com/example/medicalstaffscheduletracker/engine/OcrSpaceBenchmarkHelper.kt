package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Opt-in Developer Benchmark Client for comparing OCR.space Engines with on-device ML Kit.
 */
object OcrSpaceBenchmarkHelper {

    private const val TAG = "MedicalScheduleTracker"

    suspend fun benchmarkWithOcrSpace(
        context: Context,
        bitmap: Bitmap,
        engine: String = OcrKeyManager.getActiveEngine(context)
    ): Result<CloudBenchmarkSummary> = withContext(Dispatchers.IO) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val imageBytes = stream.toByteArray()

        val parseRes = OcrSpaceClient.parseFile(
            context = context,
            rawFileBytes = imageBytes,
            fileName = "roster_diagnostic.jpg",
            mimeType = "image/jpeg",
            engine = engine
        )

        parseRes.map { ext ->
            val sampleWords = ext.words.take(25).map { it.text }
            val sample = if (sampleWords.isNotEmpty()) {
                sampleWords.joinToString(" ")
            } else {
                ext.fullText.take(200)
            }

            CloudBenchmarkSummary(
                engine = ext.engine,
                durationMs = ext.durationMs,
                extractedWordCount = if (ext.words.isNotEmpty()) ext.words.size else ext.fullText.split(Regex("\\s+")).count { it.isNotBlank() },
                sampleText = sample,
                rawResponseSummary = "Engine: ${ext.engine}, Words: ${ext.words.size}, HasGeometry: ${ext.hasSpatialGeometry}, Length: ${ext.fullText.length}"
            )
        }
    }
}
