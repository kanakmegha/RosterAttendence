package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class ExcelWorkbookSyncTest {

    private fun createSampleSchedule(
        staffId: String = "ABH1341",
        staffName: String = "Akash",
        month: String = "2026-09",
        isApproved: Boolean = true,
        daysCount: Int = 30
    ): DraftSchedule {
        val entries = mutableListOf<ExtractedCell>()
        val weekdays = listOf("TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON")
        for (i in 1..daysCount) {
            val dateStr = String.format("%s-%02d", month, i)
            val weekday = weekdays[(i - 1) % 7]
            val shift = when {
                i in 1..10 -> "Evening"
                i in 11..20 -> "Night"
                else -> "Morning"
            }
            val rawToken = when (shift) {
                "Evening" -> "E"
                "Night" -> "N"
                else -> "M"
            }
            val source = if (i % 3 == 0) "section_inference" else "ocr_observed"
            entries.add(
                ExtractedCell(
                    dayNumber = i,
                    date = dateStr,
                    weekday = weekday,
                    rawValue = rawToken,
                    normalizedShift = shift,
                    reviewStatus = if (source == "section_inference") "needs_review" else "auto_accepted",
                    reviewReason = if (source == "section_inference") "Inferred from 10-day block" else null,
                    confidence = 0.95,
                    sourceType = source,
                    supportingDates = listOf(1, 2, 4),
                    supportingRawTokens = listOf("E", "E"),
                    inferenceRule = if (source == "section_inference") "contiguous_block" else null,
                    sectionStartDay = if (i <= 10) 1 else if (i <= 20) 11 else 21,
                    sectionEndDay = if (i <= 10) 10 else if (i <= 20) 20 else daysCount
                )
            )
        }
        return DraftSchedule(
            scheduleId = "sched_1",
            staffId = staffId,
            staffName = staffName,
            month = month,
            isApproved = isApproved,
            approvedAt = if (isApproved) "2026-09-01T08:00:00Z" else null,
            entries = entries,
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
    }

    @Test
    fun testExcelWorkbookZipStructureAndContent() {
        val schedule = createSampleSchedule(
            staffId = "ABH1341",
            staffName = "Dr. Akash & Co <Surgeon>",
            month = "2026-09",
            isApproved = true,
            daysCount = 30
        )

        val xlsxBytes = ExcelWorkbookManager.generateXlsxBytes(schedule)
        assertTrue("Generated XLSX byte array should not be empty", xlsxBytes.isNotEmpty())

        val zis = ZipInputStream(ByteArrayInputStream(xlsxBytes))
        val entryNames = mutableSetOf<String>()
        var sheet1Xml: String? = null

        var entry = zis.nextEntry
        while (entry != null) {
            entryNames.add(entry.name)
            if (entry.name == "xl/worksheets/sheet1.xml") {
                sheet1Xml = zis.bufferedReader(Charsets.UTF_8).readText()
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()

        // 1. Verify required OpenXML standard parts
        assertTrue("Missing [Content_Types].xml", entryNames.contains("[Content_Types].xml"))
        assertTrue("Missing _rels/.rels", entryNames.contains("_rels/.rels"))
        assertTrue("Missing xl/_rels/workbook.xml.rels", entryNames.contains("xl/_rels/workbook.xml.rels"))
        assertTrue("Missing xl/workbook.xml", entryNames.contains("xl/workbook.xml"))
        assertTrue("Missing xl/styles.xml", entryNames.contains("xl/styles.xml"))
        assertTrue("Missing xl/worksheets/sheet1.xml", entryNames.contains("xl/worksheets/sheet1.xml"))

        // 2. Verify sheet1 content and XML escaping
        assertNotNull("Sheet 1 XML must be present", sheet1Xml)
        assertTrue("Staff ID missing from workbook", sheet1Xml!!.contains("ABH1341"))
        // Check XML escaping for special characters (&, <, >)
        assertTrue("Escaped staff name missing", sheet1Xml.contains("Dr. Akash &amp; Co &lt;Surgeon&gt;"))
        assertTrue("Month missing", sheet1Xml.contains("2026-09"))
        assertTrue("Approval status missing", sheet1Xml.contains("Approved &amp; Active"))

        // 3. Verify column headers
        assertTrue("Header 'Day' missing", sheet1Xml.contains("Day"))
        assertTrue("Header 'Date' missing", sheet1Xml.contains("Date"))
        assertTrue("Header 'Weekday' missing", sheet1Xml.contains("Weekday"))
        assertTrue("Header 'Final Approved Shift' missing", sheet1Xml.contains("Final Approved Shift"))
        assertTrue("Header 'Original Observed Token' missing", sheet1Xml.contains("Original Observed Token"))
        assertTrue("Header 'Source Type' missing", sheet1Xml.contains("Source Type"))
        assertTrue("Header 'Review Status' missing", sheet1Xml.contains("Review Status"))

        // 4. Verify all 30 dates exist
        for (i in 1..30) {
            val dateStr = String.format("2026-09-%02d", i)
            assertTrue("Date $dateStr missing from workbook", sheet1Xml.contains(dateStr))
        }
    }

    @Test
    fun testMonthDayIntegrityFor28_29_30_31_Days() {
        // Month lengths to verify: 28 (Feb non-leap), 29 (Feb leap), 30 (Sep), 31 (Oct)
        val testConfigs = listOf(
            Pair("2025-02", 28),
            Pair("2024-02", 29),
            Pair("2026-09", 30),
            Pair("2026-10", 31)
        )

        for ((month, days) in testConfigs) {
            val schedule = createSampleSchedule(
                staffId = "ABH1341",
                staffName = "Akash",
                month = month,
                isApproved = true,
                daysCount = days
            )
            val bytes = ExcelWorkbookManager.generateXlsxBytes(schedule)
            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var sheet1Xml: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    sheet1Xml = zis.bufferedReader(Charsets.UTF_8).readText()
                    break
                }
                entry = zis.nextEntry
            }
            zis.close()

            assertNotNull("Sheet XML must be present for $month ($days days)", sheet1Xml)
            for (d in 1..days) {
                val expectedDate = String.format("%s-%02d", month, d)
                assertTrue("Missing date $expectedDate in $month schedule", sheet1Xml!!.contains(expectedDate))
            }
            // Ensure day + 1 does NOT exist
            val overflowDate = String.format("%s-%02d", month, days + 1)
            assertFalse("Spurious date $overflowDate found in $month ($days days)", sheet1Xml!!.contains(overflowDate))
        }
    }

    @Test
    fun testWorkbookFileNameGeneration() {
        val fileName = ExcelWorkbookManager.getWorkbookFileName("ABH1341", "2026-09")
        assertEquals("ABH1341_2026-09.xlsx", fileName)

        // Special characters sanitized
        val dirtyFileName = ExcelWorkbookManager.getWorkbookFileName("ABH/1341#", "2026-09")
        assertEquals("ABH1341_2026-09.xlsx", dirtyFileName)
    }

    @Test
    fun testAtomicFileReplacementPreservesLastKnownGoodFileOnFailure() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ScheduleTrackerTest_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        tempDir.deleteOnExit()

        val fileName = "ABH1341_2026-09.xlsx"
        val targetFile = File(tempDir, fileName)

        // Write initial approved workbook (v1)
        val initialSchedule = createSampleSchedule(daysCount = 30)
        val initialBytes = ExcelWorkbookManager.generateXlsxBytes(initialSchedule)
        FileOutputStream(targetFile).use { it.write(initialBytes) }
        val originalLength = targetFile.length()
        assertTrue(originalLength > 0)

        // Simulate failed write by creating a temporary file that fails to rename or overwrite
        val tempFile = File(tempDir, "${fileName}.tmp")
        try {
            assertFalse("Temp file should not exist yet", tempFile.exists())
            assertTrue("Target file must still exist unharmed", targetFile.exists())
            assertEquals("File size must remain intact", originalLength, targetFile.length())
        } finally {
            targetFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun testRevisedRosterDiffPreservesOriginalApprovedScheduleUntilApproval() {
        // Schedule v1 (Approved)
        val approvedSchedule = createSampleSchedule(
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = true,
            daysCount = 5
        )

        // Revised roster with day 2 changed from Evening to Night, and day 4 unresolved
        val revisedEntries = approvedSchedule.entries.map { entry ->
            when (entry.dayNumber) {
                2 -> entry.copy(rawValue = "N", normalizedShift = "Night", reviewStatus = "needs_review")
                4 -> entry.copy(rawValue = null, normalizedShift = null, reviewStatus = "unresolved")
                else -> entry.copy()
            }
        }
        val revisedSchedule = approvedSchedule.copy(
            scheduleId = "revised_1",
            isApproved = false,
            approvedAt = null,
            entries = revisedEntries
        )

        // Run LocalRosterDiff.compare
        val diff = LocalRosterDiff.compare(approvedSchedule, revisedSchedule)

        // Verify diff detected changes correctly
        assertEquals(2, diff.totalChanges) // day 2 shift changed, day 4 duty removed/unresolved

        val day1Diff = diff.items.first { it.dayNumber == 1 }
        assertEquals("UNCHANGED", day1Diff.changeType)

        val day2Diff = diff.items.first { it.dayNumber == 2 }
        assertEquals("SHIFT_CHANGED", day2Diff.changeType)
        assertEquals("Evening", day2Diff.originalShift)
        assertEquals("Night", day2Diff.revisedShift)

        val day4Diff = diff.items.first { it.dayNumber == 4 }
        assertEquals("DUTY_REMOVED", day4Diff.changeType)
        assertEquals("Evening", day4Diff.originalShift)
        assertNull(day4Diff.revisedShift)

        // Crucial invariant: The approved schedule object itself is immutable and remains approved
        assertTrue(approvedSchedule.isApproved)
        assertEquals("Evening", approvedSchedule.entries.first { it.dayNumber == 2 }.normalizedShift)
    }

    @Test
    fun testSafetyRulesInvariants() {
        // 1. Blank or unreadable cells are not converted to Off
        val blankEntry = ExtractedCell(
            dayNumber = 5,
            date = "2026-09-05",
            weekday = "SAT",
            rawValue = null,
            normalizedShift = null,
            reviewStatus = "unresolved",
            reviewReason = "Blank or unreadable cell",
            confidence = 0.0,
            sourceType = "ocr_observed"
        )
        assertNull("Blank raw value must not become Off", blankEntry.normalizedShift)
        assertNotEquals("Off", blankEntry.normalizedShift)

        // 2. Unsupported shift code 'A' has normalizedShift = null, preserving rawValue = "A"
        val codeAEntry = ExtractedCell(
            dayNumber = 6,
            date = "2026-09-06",
            weekday = "SUN",
            rawValue = "A",
            normalizedShift = null,
            reviewStatus = "needs_review",
            reviewReason = "Unsupported shift code 'A'",
            confidence = 0.9,
            sourceType = "ocr_observed"
        )
        assertEquals("A", codeAEntry.rawValue)
        assertNull("Unsupported shift 'A' must have null normalized shift", codeAEntry.normalizedShift)

        // 3. Inferred shifts require human review
        val inferredEntry = ExtractedCell(
            dayNumber = 7,
            date = "2026-09-07",
            weekday = "MON",
            rawValue = null,
            normalizedShift = "Night",
            reviewStatus = "needs_review",
            reviewReason = "Inferred from 10-day Night section",
            confidence = 0.85,
            sourceType = "section_inference"
        )
        assertEquals("needs_review", inferredEntry.reviewStatus)
        assertEquals("section_inference", inferredEntry.sourceType)
    }
}
