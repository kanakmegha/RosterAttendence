package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class RenderedPdfPage(
    val bitmap: Bitmap,
    val pageIndex: Int,
    val originalPageWidth: Int,
    val originalPageHeight: Int,
    val dpiScale: Float,
    val transformMetadata: DocumentTransformMetadata
)

object PdfRendererHelper {

    suspend fun renderPdfPagesWithMetadata(
        context: Context,
        pdfUri: Uri,
        maxPages: Int = 3,
        dpiScale: Float = 2.0f
    ): List<RenderedPdfPage> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RenderedPdfPage>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var tempFile: File? = null

        val hasEmbedded = DocumentImageProcessor.inspectPdfEmbeddedText(context, pdfUri)

        try {
            tempFile = File.createTempFile("roster_upload_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val pageCount = minOf(renderer.pageCount, maxPages)
            for (i in 0 until pageCount) {
                renderer.openPage(i).use { page ->
                    val origW = page.width
                    val origH = page.height
                    val width = (origW * dpiScale).toInt().coerceAtMost(2800)
                    val height = (origH * dpiScale).toInt().coerceAtMost(2800)
                    val actualScale = width.toFloat() / origW.toFloat()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val meta = DocumentTransformMetadata(
                        originalWidth = origW,
                        originalHeight = origH,
                        isPdf = true,
                        hasEmbeddedText = hasEmbedded,
                        exifOrientation = 1,
                        scaleFactor = actualScale,
                        preprocessedWidth = width,
                        preprocessedHeight = height,
                        processingDescription = "Native PdfRenderer at scale ${String.format(java.util.Locale.US, "%.2f", actualScale)}x (${if (hasEmbedded) "embedded text detected" else "scanned/vector"})"
                    )

                    results.add(
                        RenderedPdfPage(
                            bitmap = bitmap,
                            pageIndex = i,
                            originalPageWidth = origW,
                            originalPageHeight = origH,
                            dpiScale = actualScale,
                            transformMetadata = meta
                        )
                    )
                }
            }
        } finally {
            renderer?.close()
            pfd?.close()
            tempFile?.delete()
        }

        results
    }

    suspend fun renderPdfPagesToBitmaps(
        context: Context,
        pdfUri: Uri,
        maxPages: Int = 3,
        dpiScale: Float = 2.0f
    ): List<Bitmap> {
        return renderPdfPagesWithMetadata(context, pdfUri, maxPages, dpiScale).map { it.bitmap }
    }
}
