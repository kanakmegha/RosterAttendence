package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * High-reliability Document Processing Pipeline for Medical Staff Schedule Tracker.
 *
 * Preserves the original file unchanged.
 * For PDFs:
 * - Checks whether the PDF contains usable embedded text.
 * - Rasterizes scanned/vector pages at configurable high DPI (200-300 DPI).
 * - Preserves page dimensions and coordinate transforms.
 * For Images:
 * - Corrects EXIF orientation.
 * - Generates grayscale, contrast-normalized, adaptive-threshold, and mildly denoised variants.
 * - Allows controlled upscaling for OCR without claiming it restores lost source detail.
 * - Stores source dimensions and transformation metadata so every detected coordinate
 *   can be mapped 1:1 back to the original image coordinate system.
 */
data class DocumentTransformMetadata(
    val originalWidth: Int,
    val originalHeight: Int,
    val isPdf: Boolean,
    val hasEmbeddedText: Boolean,
    val exifOrientation: Int,
    val scaleFactor: Float,
    val preprocessedWidth: Int,
    val preprocessedHeight: Int,
    val processingDescription: String
) {
    /**
     * Maps coordinates from preprocessed / rendered bitmap back to original source coordinate space.
     */
    fun mapToSourceX(x: Float): Float = if (scaleFactor > 0f) x / scaleFactor else x
    fun mapToSourceY(y: Float): Float = if (scaleFactor > 0f) y / scaleFactor else y

    /**
     * Maps coordinates from original source coordinate space to preprocessed bitmap space.
     */
    fun mapFromSourceX(x: Float): Float = x * scaleFactor
    fun mapFromSourceY(y: Float): Float = y * scaleFactor
}

data class PixelDensityAnalysis(
    val meanGray: Float,
    val minGray: Int,
    val maxGray: Int,
    val darkPixelRatio: Float,
    val isSolidFill: Boolean,
    val isFaintOrBlank: Boolean
)

object DocumentImageProcessor {

    private const val TAG = "MedicalScheduleTracker"

    /**
     * Inspects a PDF stream to check if it contains usable embedded text objects.
     */
    fun inspectPdfEmbeddedText(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var textMarkers = 0
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    if (chunk.contains("/Font") || chunk.contains("BT") || chunk.contains("Tj") || chunk.contains("TJ")) {
                        textMarkers++
                    }
                    if (textMarkers >= 3) return true
                }
                textMarkers > 0
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Notice checking PDF embedded text: ${e.message}")
            false
        }
    }

    /**
     * Decodes source bitmap while inspecting and correcting EXIF orientation.
     * The original file is preserved unchanged.
     */
    fun decodeBitmapWithExif(context: Context, uri: Uri): Triple<Bitmap?, Int, Pair<Int, Int>> {
        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice inspecting EXIF: ${e.message}")
        }

        val original = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding source bitmap", e)
            null
        }

        if (original == null) return Triple(null, orientation, 0 to 0)

        val origWidth = original.width
        val origHeight = original.height

        val matrix = Matrix()
        var needsTransform = true
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> needsTransform = false
        }

        if (!needsTransform) {
            return Triple(original, orientation, origWidth to origHeight)
        }

        return try {
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated != original) {
                original.recycle()
            }
            Triple(rotated, orientation, origWidth to origHeight)
        } catch (e: Exception) {
            Log.w(TAG, "Rotation matrix application notice: ${e.message}")
            Triple(original, orientation, origWidth to origHeight)
        }
    }

    /**
     * Generates a contrast-normalized working copy (+25% to +50% contrast).
     */
    fun applyContrastNormalization(src: Bitmap, contrast: Float = 1.35f): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix()
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

    /**
     * Generates an adaptive-threshold / binarized variant for faint cell recovery.
     */
    fun applyAdaptiveThreshold(src: Bitmap, threshold: Int = 135): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            val bin = if (gray > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            pixels[i] = bin
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    /**
     * Generates a mildly denoised variant with 3x3 median filtering to remove scanner salt-and-pepper noise.
     */
    fun applyMildDenoise(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val window = IntArray(9)
                var idx = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[(y + dy) * width + (x + dx)]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        window[idx++] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    }
                }
                window.sort()
                val median = window[4]
                output[y * width + x] = (0xFF shl 24) or (median shl 16) or (median shl 8) or median
            }
        }
        dest.setPixels(output, 0, width, 0, 0, width, height)
        return dest
    }

    /**
     * Variant 1: Baseline unscaled crop with safety border padding.
     */
    fun createPaddedBaselineCrop(src: Bitmap, borderPadding: Int = 12): Bitmap {
        val finalW = src.width + borderPadding * 2
        val finalH = src.height + borderPadding * 2
        val padded = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, borderPadding.toFloat(), borderPadding.toFloat(), null)
        return padded
    }

    /**
     * Variant 2: Padded upscaled crop (3x-5x, default 4.0x) with bilinear interpolation.
     */
    fun createPaddedUpscaledCrop(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)

        val finalW = targetW + borderPadding * 2
        val finalH = targetH + borderPadding * 2
        val padded = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, borderPadding.toFloat(), borderPadding.toFloat(), null)
        if (scaled != src && !scaled.isRecycled) {
            scaled.recycle()
        }
        return padded
    }

    /**
     * Variant 3: Grayscale conversion + conservative contrast enhancement (min-max normalization) + 4x upscale.
     */
    fun createContrastEnhancedVariant(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var minGray = 255
        var maxGray = 0
        val grayPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            if (gray < minGray) minGray = gray
            if (gray > maxGray) maxGray = gray
        }

        val range = maxOf(1, maxGray - minGray)
        val outPixels = IntArray(width * height)
        for (i in grayPixels.indices) {
            val normalized = ((grayPixels[i] - minGray).toFloat() / range * 255f).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        val res = createPaddedUpscaledCrop(dest, scale, borderPadding)
        dest.recycle()
        return res
    }

    /**
     * Variant 4: Adaptive / Otsu binarization preserving glyph strokes + 4x upscale.
     */
    fun createBinarizedVariant(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val width = src.width
        val height = src.height
        val total = width * height
        if (total == 0) return createPaddedUpscaledCrop(src, scale, borderPadding)

        val pixels = IntArray(total)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        val grayPixels = IntArray(total)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            histogram[gray]++
        }

        // Otsu's thresholding method
        var sum = 0.0
        for (t in 0..255) sum += t * histogram[t]

        var sumB = 0.0
        var wB = 0
        var wF: Int
        var varMax = 0.0
        var threshold = 128

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            wF = total - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val varBetween = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        val outPixels = IntArray(total)
        for (i in grayPixels.indices) {
            val v = if (grayPixels[i] <= threshold) 0x00 else 0xFF
            outPixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        val res = createPaddedUpscaledCrop(dest, scale, borderPadding)
        dest.recycle()
        return res
    }

    /**
     * Variant 2b: Nearest-neighbor upscale (sharp, no bilinear blur) + white padding.
     * Preserves thin 1-2px glyph strokes that bilinear interpolation would smear,
     * which is critical for small shift glyphs (~6px tall in source cells).
     */
    fun createSharpUpscaledCrop(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        // filter = false => nearest-neighbor interpolation, keeps edges crisp
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, false)

        val finalW = targetW + borderPadding * 2
        val finalH = targetH + borderPadding * 2
        val padded = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, borderPadding.toFloat(), borderPadding.toFloat(), null)
        if (scaled != src && !scaled.isRecycled) {
            scaled.recycle()
        }
        return padded
    }

    /**
     * Variant 3b: Min-max contrast stretch + nearest-neighbor 4x upscale + white padding.
     * Maps the cell's darkest pixel to black and lightest to white so faint glyphs on
     * colored backgrounds become crisp black-on-white before recognition.
     */
    fun createContrastSharpUpscaledCrop(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var minGray = 255
        var maxGray = 0
        val grayPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            if (gray < minGray) minGray = gray
            if (gray > maxGray) maxGray = gray
        }

        val range = maxOf(1, maxGray - minGray)
        val outPixels = IntArray(width * height)
        for (i in grayPixels.indices) {
            val normalized = ((grayPixels[i] - minGray).toFloat() / range * 255f).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        val res = createSharpUpscaledCrop(dest, scale, borderPadding)
        dest.recycle()
        return res
    }

    /**
     * Variant 3c: Min-max contrast stretch at original 1x scale + white padding (no upscale).
     * Baseline for measuring whether contrast alone (without interpolation blur) recovers faint glyphs.
     */
    fun createContrastOnlyCrop(src: Bitmap, borderPadding: Int = 12): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var minGray = 255
        var maxGray = 0
        val grayPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            if (gray < minGray) minGray = gray
            if (gray > maxGray) maxGray = gray
        }

        val range = maxOf(1, maxGray - minGray)
        val outPixels = IntArray(width * height)
        for (i in grayPixels.indices) {
            val normalized = ((grayPixels[i] - minGray).toFloat() / range * 255f).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return createPaddedBaselineCrop(dest, borderPadding)
    }

    /**
     * Variant 4b: Otsu binarization + nearest-neighbor 3x upscale + white padding.
     * Sharp binarized variant to check whether thresholding preserves the E/M/N glyph strokes
     * without the blur that bilinear 4x introduces.
     */
    fun createBinarizedSharpVariant(src: Bitmap, scale: Float = 3.0f, borderPadding: Int = 16): Bitmap {
        val width = src.width
        val height = src.height
        val total = width * height
        if (total == 0) return createSharpUpscaledCrop(src, scale, borderPadding)

        val pixels = IntArray(total)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        val grayPixels = IntArray(total)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            histogram[gray]++
        }

        // Otsu's thresholding method
        var sum = 0.0
        for (t in 0..255) sum += t * histogram[t]

        var sumB = 0.0
        var wB = 0
        var wF: Int
        var varMax = 0.0
        var threshold = 128

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            wF = total - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val varBetween = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        val outPixels = IntArray(total)
        for (i in grayPixels.indices) {
            val v = if (grayPixels[i] <= threshold) 0x00 else 0xFF
            outPixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        val res = createSharpUpscaledCrop(dest, scale, borderPadding)
        dest.recycle()
        return res
    }

    /**
     * Variant 5: Border-line suppressed + contrast stretch + 4x upscale.
     * Identifies and suppresses horizontal dividing table border lines at row 0/1 within the cell crop.
     */
    fun createBorderCleanedEnhancedVariant(src: Bitmap, scale: Float = 4.0f, borderPadding: Int = 16): Bitmap {
        val width = src.width
        val height = src.height
        if (height < 6) return createContrastEnhancedVariant(src, scale, borderPadding)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = Array(height) { IntArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[y][x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }

        // Detect if row 0 or 1 is a horizontal table dividing line (mean < 120 and min < 50)
        var borderRow = -1
        for (r in 0 until minOf(2, height)) {
            val mean = gray[r].average()
            val minVal = gray[r].minOrNull() ?: 255
            if (mean < 120 && minVal < 50) {
                borderRow = r
                break
            }
        }

        if (borderRow != -1) {
            val bgRow = minOf(borderRow + 2, height - 1)
            for (x in 0 until width) {
                gray[borderRow][x] = gray[bgRow][x]
                if (borderRow == 1) {
                    gray[0][x] = gray[bgRow][x]
                }
            }
        }

        var minGray = 255
        var maxGray = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = gray[y][x]
                if (v < minGray) minGray = v
                if (v > maxGray) maxGray = v
            }
        }

        val range = maxOf(1, maxGray - minGray)
        val outPixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val normalized = ((gray[y][x] - minGray).toFloat() / range * 255f).toInt().coerceIn(0, 255)
                outPixels[y * width + x] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
            }
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        val res = createPaddedUpscaledCrop(dest, scale, borderPadding)
        dest.recycle()
        return res
    }

    /**
     * Inspects grayscale pixel distribution to distinguish solid ink fill / shading from faint noise or blank canvas.
     */
    fun analyzePixelDensity(crop: Bitmap): PixelDensityAnalysis {
        val width = crop.width
        val height = crop.height
        val total = width * height
        if (total == 0) {
            return PixelDensityAnalysis(255f, 255, 255, 0f, isSolidFill = false, isFaintOrBlank = true)
        }

        val pixels = IntArray(total)
        crop.getPixels(pixels, 0, width, 0, 0, width, height)

        var sumGray = 0L
        var minG = 255
        var maxG = 0
        var darkCount = 0

        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            sumGray += gray
            if (gray < minG) minG = gray
            if (gray > maxG) maxG = gray
            if (gray < 115) darkCount++
        }

        val meanG = sumGray.toFloat() / total
        val darkRatio = darkCount.toFloat() / total
        val isSolid = darkRatio >= 0.70f || (minG <= 10 && meanG < 130f && darkRatio > 0.50f)
        val isFaint = darkRatio < 0.12f

        return PixelDensityAnalysis(
            meanGray = meanG,
            minGray = minG,
            maxGray = maxG,
            darkPixelRatio = darkRatio,
            isSolidFill = isSolid,
            isFaintOrBlank = isFaint
        )
    }

    /**
     * Generates a diagnostic contact sheet showing:
     * 1. Header with metadata (image dimensions, target row Y, row bounds).
     * 2. Full employee row strip across date columns.
     * 3. Representative daily cells (Days 1, 5, 13, 26, 29) scaled up with coordinates and recognized tokens.
     */
    fun createDiagnosticContactSheet(
        sourceBitmap: Bitmap,
        targetRowY: Float,
        rowHeight: Float,
        topBound: Float,
        bottomBound: Float,
        cells: List<CellCropExtraction>,
        representativeDays: List<Int> = listOf(1, 5, 13, 26, 29),
        title: String = "DIAGNOSTIC CONTACT SHEET: Employee Row & Daily Cells"
    ): Bitmap {
        val sheetWidth = 1200
        val sheetHeight = 420
        val sheet = Bitmap.createBitmap(sheetWidth, sheetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 13f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
        }
        val borderPaint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cellBorderPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // 1. Header
        canvas.drawText(title, 20f, 30f, textPaint)
        val metaStr = "Source: ${sourceBitmap.width}x${sourceBitmap.height} | Row Y: $targetRowY | Row Height: $rowHeight | Bounds: [${topBound.toInt()} .. ${bottomBound.toInt()}]"
        canvas.drawText(metaStr, 20f, 52f, subTextPaint)

        // 2. Full Row Strip
        val rowY0 = topBound.toInt().coerceIn(0, sourceBitmap.height - 2)
        val rowY1 = bottomBound.toInt().coerceIn(rowY0 + 1, sourceBitmap.height)
        val rowH = (rowY1 - rowY0).coerceAtLeast(1)

        canvas.drawText("Full Employee Row Strip [Y: $rowY0..$rowY1, H: ${rowH}px]:", 20f, 80f, subTextPaint)
        try {
            val fullRowCrop = Bitmap.createBitmap(sourceBitmap, 0, rowY0, sourceBitmap.width, rowH)
            val destRect = android.graphics.Rect(20, 90, 20 + minOf(sheetWidth - 40, fullRowCrop.width), 90 + rowH * 2)
            canvas.drawBitmap(fullRowCrop, null, destRect, null)
            canvas.drawRect(destRect, borderPaint)
        } catch (e: Exception) {
            canvas.drawText("Could not render row strip: ${e.message}", 20f, 105f, subTextPaint)
        }

        // 3. Representative Daily Cells (Days 1, 5, 13, 26, 29)
        val repY = 175f
        canvas.drawText("Representative Daily Cells (Scaled 4x for High-Visibility Inspection):", 20f, repY, textPaint)

        val cellMap = cells.associateBy { it.dayNumber }
        val cardW = 215
        val cardH = 180
        val startX = 20

        representativeDays.forEachIndexed { index, dayNum ->
            val cell = cellMap[dayNum]
            val cardX = startX + index * (cardW + 15)
            val cardY = repY + 15f

            // Card background & outline
            val cardRect = android.graphics.RectF(cardX.toFloat(), cardY, (cardX + cardW).toFloat(), cardY + cardH)
            val bgPaint = Paint().apply {
                color = Color.rgb(245, 247, 250)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(cardRect, 8f, 8f, bgPaint)
            val cardOutline = Paint().apply {
                color = Color.LTGRAY
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawRoundRect(cardRect, 8f, 8f, cardOutline)

            if (cell != null) {
                // Title
                val dayTitle = "Day $dayNum (${cell.weekday})"
                canvas.drawText(dayTitle, cardX + 10f, cardY + 22f, textPaint.apply { textSize = 14f })

                val cX0 = cell.leftBound.toInt().coerceIn(0, sourceBitmap.width - 2)
                val cX1 = cell.rightBound.toInt().coerceIn(cX0 + 1, sourceBitmap.width)
                val cY0 = cell.topBound.toInt().coerceIn(0, sourceBitmap.height - 2)
                val cY1 = cell.bottomBound.toInt().coerceIn(cY0 + 1, sourceBitmap.height)
                val cW = (cX1 - cX0).coerceAtLeast(1)
                val cH = (cY1 - cY0).coerceAtLeast(1)

                try {
                    val rawCellCrop = Bitmap.createBitmap(sourceBitmap, cX0, cY0, cW, cH)
                    val cropScale = 3
                    val targetCropW = cW * cropScale
                    val targetCropH = cH * cropScale
                    val cropDest = android.graphics.Rect(
                        cardX + 10,
                        (cardY + 32).toInt(),
                        cardX + 10 + targetCropW,
                        (cardY + 32 + targetCropH).toInt()
                    )
                    canvas.drawBitmap(rawCellCrop, null, cropDest, null)
                    canvas.drawRect(cropDest, cellBorderPaint)
                } catch (e: Exception) {
                    canvas.drawText("Crop error: ${e.message}", cardX + 10f, cardY + 50f, subTextPaint)
                }

                val infoY = cardY + 95f
                canvas.drawText("Crop: ${cW}x${cH}px [X:$cX0, Y:$cY0]", cardX + 10f, infoY, labelPaint)
                canvas.drawText("Raw OCR: '${cell.rawToken ?: ""}'", cardX + 10f, infoY + 16f, labelPaint.apply { isFakeBoldText = true })
                canvas.drawText("Shift: ${cell.normalizedShift ?: "None"} (${cell.classification})", cardX + 10f, infoY + 32f, labelPaint.apply { isFakeBoldText = false })
                canvas.drawText("Status: ${cell.reviewStatus}", cardX + 10f, infoY + 48f, subTextPaint.apply { textSize = 11f })
            } else {
                canvas.drawText("Day $dayNum: Not Found", cardX + 10f, cardY + 30f, textPaint.apply { textSize = 13f })
            }
        }

        return sheet
    }

    /**
     * Saves a diagnostic contact sheet to a PNG file on disk.
     */
    fun saveContactSheetToFile(bitmap: Bitmap, outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save diagnostic contact sheet to ${outputFile.absolutePath}: ${e.message}", e)
            false
        }
    }
}
