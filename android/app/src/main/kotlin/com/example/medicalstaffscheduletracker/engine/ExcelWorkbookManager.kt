package com.example.medicalstaffscheduletracker.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExcelWorkbookManager {

    private const val TAG = "MedicalScheduleTracker"
    const val FOLDER_NAME = "MedicalStaffScheduleTracker"

    /**
     * Resolves or creates the local MedicalStaffScheduleTracker directory in Documents.
     */
    fun getOrCreateDocumentsFolder(context: Context): File {
        // Primary: standard public Documents folder
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appFolder = File(publicDocs, FOLDER_NAME)
        if (!appFolder.exists()) {
            val created = appFolder.mkdirs()
            Log.i(TAG, "Creating public Documents folder: ${appFolder.absolutePath}, success: $created")
        }

        if (appFolder.exists() && appFolder.canWrite()) {
            return appFolder
        }

        // Secondary fallback: app-specific external files dir in Documents
        val extDocs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val fallbackFolder = File(extDocs ?: context.filesDir, FOLDER_NAME)
        if (!fallbackFolder.exists()) {
            fallbackFolder.mkdirs()
        }
        Log.i(TAG, "Using fallback Documents folder: ${fallbackFolder.absolutePath}")
        return fallbackFolder
    }

    /**
     * Constructs standard filename: <STAFF_ID>_<YYYY-MM>.xlsx (e.g. ABHL341_2026-09.xlsx)
     */
    fun getWorkbookFileName(staffId: String, month: String): String {
        val cleanId = staffId.replace(Regex("[^A-Za-z0-9_-]"), "")
        val cleanMonth = month.replace(Regex("[^0-9-]"), "")
        return "${cleanId}_${cleanMonth}.xlsx"
    }

    /**
     * Generates a valid Microsoft Excel .xlsx workbook file for the approved schedule.
     */
    suspend fun writeApprovedScheduleWorkbook(
        context: Context,
        schedule: DraftSchedule
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = getWorkbookFileName(schedule.staffId, schedule.month)
            val folder = getOrCreateDocumentsFolder(context)
            val targetFile = File(folder, fileName)
            val tempFile = File(folder, "${fileName}.tmp")

            Log.i(TAG, "Generating Excel workbook for ${schedule.staffId} (${schedule.month}) at: ${targetFile.absolutePath}")

            // 1. Generate OpenXML XLSX bytes
            val xlsxBytes = generateXlsxBytes(schedule)

            // 2. Write safely to temp file first
            FileOutputStream(tempFile).use { fos ->
                fos.write(xlsxBytes)
                fos.flush()
            }

            // 3. Atomically rename temp file to target file (preserves last good workbook on error)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                // Fallback copy if renameTo fails across mounts
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // 4. On Android Q+ (API 29+), publish to MediaStore so it is immediately visible in file managers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(context, targetFile, fileName)
            }

            Log.i(TAG, "Excel workbook successfully written: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Excel workbook for ${schedule.staffId}", e)
            Result.failure(e)
        }
    }

    /**
     * Returns the existing workbook file if present.
     */
    fun getExistingWorkbookFile(context: Context, staffId: String, month: String): File? {
        val fileName = getWorkbookFileName(staffId, month)
        val folder = getOrCreateDocumentsFolder(context)
        val file = File(folder, fileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Publishes file to MediaStore on Android 10+
     */
    private fun publishToMediaStore(context: Context, file: File, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        file.inputStream().use { input ->
                            input.copyTo(os)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore publish notice: ${e.message}")
        }
    }

    /**
     * Generates a fully standard OpenXML .xlsx ZIP archive bytes in memory.
     */
    fun generateXlsxBytes(schedule: DraftSchedule): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // 1. [Content_Types].xml
            addZipEntry(zos, "[Content_Types].xml", buildContentTypesXml())

            // 2. _rels/.rels
            addZipEntry(zos, "_rels/.rels", buildGlobalRelsXml())

            // 3. xl/_rels/workbook.xml.rels
            addZipEntry(zos, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())

            // 4. xl/workbook.xml
            addZipEntry(zos, "xl/workbook.xml", buildWorkbookXml())

            // 5. xl/styles.xml
            addZipEntry(zos, "xl/styles.xml", buildStylesXml())

            // 6. xl/worksheets/sheet1.xml
            addZipEntry(zos, "xl/worksheets/sheet1.xml", buildSheet1Xml(schedule))
        }
        return bos.toByteArray()
    }

    private fun addZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun buildGlobalRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Duty Schedule" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><name val="Calibri"/><sz val="11"/></font>
    <font><b/><name val="Calibri"/><sz val="11"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""

    private fun escapeXml(text: String?): String {
        if (text == null) return ""
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildSheet1Xml(schedule: DraftSchedule): String {
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val staffName = schedule.staffName ?: "Unknown"

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
""")

        // Row 1: Header Title
        sb.append("""    <row r="1">
      <c r="A1" t="inlineStr" s="1"><is><t>Hospital Duty Schedule - Official Record</t></is></c>
    </row>
""")

        // Row 2: Staff Metadata
        sb.append("""    <row r="2">
      <c r="A2" t="inlineStr"><is><t>Staff ID:</t></is></c>
      <c r="B2" t="inlineStr" s="1"><is><t>${escapeXml(schedule.staffId)}</t></is></c>
      <c r="C2" t="inlineStr"><is><t>Staff Name:</t></is></c>
      <c r="D2" t="inlineStr" s="1"><is><t>${escapeXml(staffName)}</t></is></c>
    </row>
""")

        // Row 3: Month & Approval Metadata
        sb.append("""    <row r="3">
      <c r="A3" t="inlineStr"><is><t>Roster Month:</t></is></c>
      <c r="B3" t="inlineStr" s="1"><is><t>${escapeXml(schedule.month)}</t></is></c>
      <c r="C3" t="inlineStr"><is><t>Status:</t></is></c>
      <c r="D3" t="inlineStr" s="1"><is><t>${if (schedule.isApproved) "Approved &amp; Active" else "Draft"}</t></is></c>
      <c r="E3" t="inlineStr"><is><t>Last Updated:</t></is></c>
      <c r="F3" t="inlineStr"><is><t>${escapeXml(schedule.approvedAt ?: nowIso)}</t></is></c>
    </row>
""")

        // Row 4: Spacer
        sb.append("""    <row r="4"/>
""")

        // Row 5: Table Column Headers
        sb.append("""    <row r="5">
      <c r="A5" t="inlineStr" s="1"><is><t>Day</t></is></c>
      <c r="B5" t="inlineStr" s="1"><is><t>Date</t></is></c>
      <c r="C5" t="inlineStr" s="1"><is><t>Weekday</t></is></c>
      <c r="D5" t="inlineStr" s="1"><is><t>Final Approved Shift</t></is></c>
      <c r="E5" t="inlineStr" s="1"><is><t>Original Observed Token</t></is></c>
      <c r="F5" t="inlineStr" s="1"><is><t>Source Type</t></is></c>
      <c r="G5" t="inlineStr" s="1"><is><t>Review Status</t></is></c>
      <c r="H5" t="inlineStr" s="1"><is><t>Uncertainty / Explanation</t></is></c>
    </row>
""")

        // Data Rows starting at Row 6
        var rowIdx = 6
        for (cell in schedule.entries.sortedBy { it.dayNumber }) {
            val shift = cell.normalizedShift
                ?: if (cell.classification == "A" && cell.isConfirmed) "A" else "Unresolved"
            val raw = cell.rawValue ?: ""
            val source = when (cell.sourceType) {
                "ocr_observed", "directly_observed" -> "Directly Detected"
                "section_inference", "inferred_from_section_anchor" -> "Rule-Inferred (Section Anchor)"
                "inferred_from_verified_rotation" -> "Rule-Inferred (3-Section Rotation)"
                "off_propagation", "inferred_from_weekly_off" -> "Rule-Inferred (Weekly OFF)"
                "manual", "manual_override" -> "Manually Corrected"
                else -> cell.sourceType
            }
            val status = cell.reviewStatus
            val explanation = cell.reviewReason ?: ""

            sb.append("""    <row r="$rowIdx">
      <c r="A$rowIdx"><v>${cell.dayNumber}</v></c>
      <c r="B$rowIdx" t="inlineStr"><is><t>${escapeXml(cell.date)}</t></is></c>
      <c r="C$rowIdx" t="inlineStr"><is><t>${escapeXml(cell.weekday)}</t></is></c>
      <c r="D$rowIdx" t="inlineStr" s="1"><is><t>${escapeXml(shift)}</t></is></c>
      <c r="E$rowIdx" t="inlineStr"><is><t>${escapeXml(raw)}</t></is></c>
      <c r="F$rowIdx" t="inlineStr"><is><t>${escapeXml(source)}</t></is></c>
      <c r="G$rowIdx" t="inlineStr"><is><t>${escapeXml(status)}</t></is></c>
      <c r="H$rowIdx" t="inlineStr"><is><t>${escapeXml(explanation)}</t></is></c>
    </row>
""")
            rowIdx++
        }

        sb.append("""  </sheetData>
</worksheet>""")
        return sb.toString()
    }
}
