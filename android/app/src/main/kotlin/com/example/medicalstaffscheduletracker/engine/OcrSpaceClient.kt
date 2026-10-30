package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

sealed class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class KeyMissing(message: String) : OcrException(message)
    class NetworkOffline(message: String) : OcrException(message)
    class Timeout(message: String) : OcrException(message)
    class RateLimitExceeded(message: String) : OcrException(message)
    class FileLimitExceeded(message: String) : OcrException(message)
    class ApiError(message: String) : OcrException(message)
}

data class OcrExtractionResult(
    val provider: OcrProviderType,
    val engine: String,
    val durationMs: Long,
    val fullText: String,
    val words: List<OcrWord>,
    val hasSpatialGeometry: Boolean,
    val warningMessage: String? = null
)

object OcrSpaceClient {

    private const val TAG = "MedicalScheduleTracker"
    private const val OCR_SPACE_ENDPOINT = "https://api.ocr.space/parse/image"
    private const val MAX_FILE_SIZE_BYTES = 1024 * 1024 // 1 MB Free tier limit
    private const val TIMEOUT_MS = 35000

    private fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Safely compresses an image bitmap to JPEG under 1000 KB if the source exceeds 1 MB,
     * preserving full image dimensions (no downscaling).
     */
    private fun ensureUnderFileSizeLimit(fileBytes: ByteArray, mimeType: String): ByteArray {
        if (fileBytes.size <= MAX_FILE_SIZE_BYTES || mimeType.contains("pdf")) {
            return fileBytes
        }

        try {
            val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size) ?: return fileBytes
            var quality = 88
            var currentBytes = fileBytes
            while (currentBytes.size > MAX_FILE_SIZE_BYTES && quality >= 50) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                currentBytes = stream.toByteArray()
                quality -= 10
            }
            Log.i(TAG, "Compressed source image from ${fileBytes.size / 1024} KB to ${currentBytes.size / 1024} KB (quality=$quality) to satisfy OCR.space 1MB limit")
            return currentBytes
        } catch (e: Exception) {
            Log.w(TAG, "Notice compressing image: ${e.message}")
            return fileBytes
        }
    }

    suspend fun parseFile(
        context: Context,
        rawFileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        engine: String = OcrKeyManager.getActiveEngine(context)
    ): Result<OcrExtractionResult> = withContext(Dispatchers.IO) {
        val apiKey = OcrKeyManager.getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                OcrException.KeyMissing(
                    "OCR.space API Key is missing. Please configure your free API key in Settings or switch to on-device Google ML Kit."
                )
            )
        }

        if (!isNetworkConnected(context)) {
            return@withContext Result.failure(
                OcrException.NetworkOffline(
                    "No internet connection. OCR.space requires an active network connection. Please connect to Wi-Fi/data or switch to on-device Google ML Kit."
                )
            )
        }

        val fileBytes = ensureUnderFileSizeLimit(rawFileBytes, mimeType)
        if (fileBytes.size > MAX_FILE_SIZE_BYTES) {
            return@withContext Result.failure(
                OcrException.FileLimitExceeded(
                    "File size (${fileBytes.size / 1024} KB) exceeds OCR.space's 1024 KB limit. Please select a smaller document or crop unnecessary borders."
                )
            )
        }

        val maskedKey = OcrKeyManager.getMaskedKey(context)
        Log.i(TAG, "Dispatching OCR.space request: File='$fileName', Engine=$engine, Size=${fileBytes.size}B, Key=$maskedKey")

        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val url = URL(OCR_SPACE_ENDPOINT)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Content-Type", "multipart/form-data;boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                fun writeFormField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }

                writeFormField("apikey", apiKey)
                writeFormField("OCREngine", engine)
                writeFormField("isTable", "true")
                writeFormField("scale", "true")
                writeFormField("isOverlayRequired", "true")
                writeFormField("detectOrientation", "true")
                writeFormField("language", "eng")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                out.writeBytes("Content-Type: $mimeType\r\n\r\n")
                out.write(fileBytes)
                out.writeBytes("\r\n")

                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val responseCode = conn.responseCode
            val responseStream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val responseText = responseStream.bufferedReader().use { it.readText() }
            val durationMs = System.currentTimeMillis() - startTime

            if (responseCode == 429) {
                return@withContext Result.failure(
                    OcrException.RateLimitExceeded(
                        "OCR.space free tier rate limit exceeded (500 requests/day or 10 requests/min). Please try again shortly or switch to on-device ML Kit."
                    )
                )
            } else if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    OcrException.ApiError("OCR.space returned HTTP error $responseCode: ${responseText.take(200)}")
                )
            }

            val json = JSONObject(responseText)
            if (json.optBoolean("IsErroredOnProcessing", false)) {
                val errArr = json.optJSONArray("ErrorMessage")
                val errMsg = errArr?.optString(0) ?: "OCR.space processing error occurred"
                return@withContext Result.failure(OcrException.ApiError(errMsg))
            }

            val parsedResults = json.optJSONArray("ParsedResults")
            if (parsedResults == null || parsedResults.length() == 0) {
                return@withContext Result.failure(
                    OcrException.ApiError("OCR.space returned an empty result for this document.")
                )
            }

            val firstResult = parsedResults.getJSONObject(0)
            val parsedText = firstResult.optString("ParsedText", "").trim()
            val textOverlay = firstResult.optJSONObject("TextOverlay")

            val wordsList = mutableListOf<OcrWord>()
            if (textOverlay != null) {
                val lines = textOverlay.optJSONArray("Lines")
                if (lines != null) {
                    for (i in 0 until lines.length()) {
                        val lineObj = lines.getJSONObject(i)
                        val words = lineObj.optJSONArray("Words")
                        if (words != null) {
                            for (j in 0 until words.length()) {
                                val wObj = words.getJSONObject(j)
                                val text = wObj.optString("WordText", "").trim()
                                if (text.isNotEmpty()) {
                                    val left = wObj.optDouble("Left", 0.0).toFloat()
                                    val top = wObj.optDouble("Top", 0.0).toFloat()
                                    val width = wObj.optDouble("Width", 0.0).toFloat()
                                    val height = wObj.optDouble("Height", 0.0).toFloat()
                                    wordsList.add(
                                        OcrWord(
                                            text = text,
                                            x = left,
                                            y = top,
                                            width = width,
                                            height = height,
                                            confidence = 0.95f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val hasSpatialGeometry = wordsList.isNotEmpty()
            var warningMessage: String? = null
            if (!hasSpatialGeometry) {
                warningMessage = "OCR.space Engine $engine recognized ${parsedText.length} characters of text, but did not return 2D spatial word bounding boxes."
            }

            Log.i(
                TAG,
                "OCR.space Engine $engine completed in ${durationMs}ms: extracted ${wordsList.size} words with geometry (${parsedText.length} characters total)"
            )

            val extractionResult = OcrExtractionResult(
                provider = OcrProviderType.OCR_SPACE,
                engine = "OCR.space Cloud Engine $engine",
                durationMs = durationMs,
                fullText = parsedText,
                words = wordsList,
                hasSpatialGeometry = hasSpatialGeometry,
                warningMessage = warningMessage
            )

            Result.success(extractionResult)
        } catch (e: SocketTimeoutException) {
            Result.failure(
                OcrException.Timeout(
                    "OCR.space request timed out after 35 seconds. Engine $engine may be congested. Try switching to Engine 2 or on-device ML Kit."
                )
            )
        } catch (e: UnknownHostException) {
            Result.failure(
                OcrException.NetworkOffline(
                    "Cannot reach api.ocr.space. Please verify your internet connection or switch to on-device ML Kit."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "OCR.space client error", e)
            Result.failure(OcrException.ApiError("OCR.space request error: ${e.message}"))
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun parseBitmap(
        context: Context,
        bitmap: Bitmap,
        fileName: String = "row_crop.jpg",
        engine: String = OcrKeyManager.getActiveEngine(context)
    ): Result<OcrExtractionResult> = withContext(Dispatchers.IO) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        val bytes = stream.toByteArray()
        parseFile(
            context = context,
            rawFileBytes = bytes,
            fileName = fileName,
            mimeType = "image/jpeg",
            engine = engine
        )
    }
}
