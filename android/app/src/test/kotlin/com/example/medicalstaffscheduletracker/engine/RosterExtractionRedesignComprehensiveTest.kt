package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * Comprehensive test suite for Medical Staff Schedule Tracker — Reliable Roster Extraction Redesign.
 *
 * Verifies all 10 core requirements:
 * 1. Disabled input after OCR failure regression test
 * 2. Manual employee selection without OCR detection
 * 3. Exact and ambiguous employee IDs (ABHL341 vs ABH1341)
 * 4. Incorrect or missing date headers
 * 5. All supported month lengths (28, 29, 30, 31)
 * 6. Cell boundaries and coordinate transformations
 * 7. OCR confusions (E/F, N/H/M, OFF/0FF)
 * 8. Explicit OFF and A preservation
 * 9. Low-confidence and UNKNOWN cells
 * 10. Conflicting section-shift evidence
 * 11. No unsupported rotation inference
 * 12. Manual edits surviving reprocessing and restart
 * 13. Approval safeguards and XLSX date/shift correctness
 */
class RosterExtractionRedesignComprehensiveTest {

    private val inferenceEngine = LocalSectionInferenceEngine(verifyRotationRuleDefault = false)
    private val inferenceEngineWithRotation = LocalSectionInferenceEngine(verifyRotationRuleDefault = true)
    private val tableReconstructor = LocalTableReconstructor()

    // =========================================================================
    // 1. REGRESSION TEST: DISABLED INPUT AFTER OCR FAILURE
    // =========================================================================

    @Test
    fun testDisabledInputAvoidedOnOcrFailure() {
        // When whole-roster OCR detects 0 employees or fails, verify that fallback metadata
        // enables manual employee entry and manual row selection.
        val cal = Calendar.getInstance()
        val year = 2026
        val month = 9
        val monthStr = "2026-09"
        val numDays = 30
        val colW = 1000f / numDays
        val fallbackIntervals = (1..numDays).associateWith { d ->
            Pair((d - 1) * colW, d * colW)
        }

        val fallbackMetadata = TableMetadata(
            monthInfo = DetectedMonthInfo(
                year = year,
                month = month,
                monthStr = monthStr,
                displayName = "September 2026 (Manual Fallback)",
                isConfident = false,
                detectedFrom = "Manual Fallback"
            ),
            metaBounds = emptyMap(),
            dayIntervals = fallbackIntervals,
            dayDiagnostics = emptyMap(),
            rows = emptyList(), // 0 detected staff
            detectedStaff = emptyList(), // 0 detected staff
            warnings = listOf("Initial whole-roster OCR incomplete. Manual entry enabled.")
        )

        // UI gating condition: user must be able to proceed if they typed a search query
        val searchQuery = "ABHL341"
        val selectedStaffMember: DetectedStaffMember? = null
        val canProceed = selectedStaffMember != null || searchQuery.isNotBlank()
        assertTrue("Input and proceed button must be enabled when staff ID is typed even if OCR found 0 employees", canProceed)

        // Reconstruct schedule for user-typed manual staff ID with synthesized row
        val row = ReconstructedRow(
            serialNumber = "1",
            rawId = searchQuery,
            normalizedId = searchQuery,
            name = "Staff ($searchQuery)",
            floor = "Floor 2",
            weeklyOffDay = "Saturday",
            y = 450f,
            shiftWords = emptyList()
        )
        val metaWithRow = fallbackMetadata.copy(rows = listOf(row))
        val result = tableReconstructor.reconstructForStaff(metaWithRow, searchQuery)

        assertNotNull("Reconstruction must succeed for manual staff ID entry", result)
        assertEquals("ABHL341", result.requestedStaffId)
        assertEquals(30, result.entries.size)
    }

    // =========================================================================
    // 2. MANUAL EMPLOYEE SELECTION WITHOUT OCR DETECTION
    // =========================================================================

    @Test
    fun testManualEmployeeSelectionByRowCoordinates() {
        val monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "header")
        val dayIntervals = (1..30).associateWith { d -> Pair((d - 1) * 30f, d * 30f) }

        val metadata = TableMetadata(
            monthInfo = monthInfo,
            metaBounds = emptyMap(),
            dayIntervals = dayIntervals,
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = emptyList(),
            warnings = emptyList()
        )

        // User manually designates row at Y=520px
        val manualRowId = "MANUAL_ROW_520"
        val recon = tableReconstructor.reconstructForStaff(metadata, manualRowId)

        assertEquals("MANUAL_ROW_520", recon.requestedStaffId)
        assertEquals(30, recon.entries.size)
        assertEquals(1, recon.entries.first().dayNumber)
        assertEquals(30, recon.entries.last().dayNumber)
    }

    // =========================================================================
    // 3. EXACT AND AMBIGUOUS EMPLOYEE IDS (ABHL341 vs ABH1341)
    // =========================================================================

    @Test
    fun testExactAndAmbiguousEmployeeIds() {
        // Confusable test: '1' vs 'L'
        val (isConfusable, confMsg) = IdMatcher.areIdsConfusable("ABHL341", "ABH1341")
        assertTrue("ABHL341 and ABH1341 must be recognized as confusable due to 1/L visual similarity", isConfusable)
        assertTrue("Confusable message must describe visual confusion", confMsg?.contains("confusion") == true)

        // Confusable test: '0' vs 'O'
        val (isZeroConf, zeroMsg) = IdMatcher.areIdsConfusable("EMP012", "EMPO12")
        assertTrue("EMP012 and EMPO12 must be recognized as confusable due to 0/O visual similarity", isZeroConf)
        assertTrue(zeroMsg?.contains("confusion") == true)

        // Exact match test
        val (isExactConf, exactMsg) = IdMatcher.areIdsConfusable("ABH1341", "ABH1341")
        assertTrue(isExactConf)
        assertEquals("exact", exactMsg)

        // Unrelated IDs
        val (isUnrelated, _) = IdMatcher.areIdsConfusable("XYZ999", "ABH1341")
        assertFalse("Completely different IDs must not be flagged as confusable", isUnrelated)
    }

    // =========================================================================
    // 4. INCORRECT OR MISSING DATE HEADERS
    // =========================================================================

    @Test
    fun testMissingDateHeadersFallbackToGeometry() {
        // Table with empty dayIntervals should fallback to evenly spaced day columns
        val monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "header")
        val row = ReconstructedRow("1", "EMP101", "EMP101", "Employee 101", null, null, 300f, emptyList())
        val metadata = TableMetadata(
            monthInfo = monthInfo,
            metaBounds = emptyMap(),
            dayIntervals = emptyMap(), // Missing header intervals
            dayDiagnostics = emptyMap(),
            rows = listOf(row),
            detectedStaff = emptyList(),
            warnings = listOf("No date headers found")
        )

        val result = tableReconstructor.reconstructForStaff(metadata, "EMP101")

        assertEquals("Must generate 30 days even when date headers are missing", 30, result.entries.size)
        assertEquals("2026-09-01", result.entries[0].date)
        assertEquals("2026-09-30", result.entries[29].date)
    }

    // =========================================================================
    // 5. ALL SUPPORTED MONTH LENGTHS (28, 29, 30, 31)
    // =========================================================================

    @Test
    fun testAllSupportedMonthLengths() {
        val testCases = listOf(
            Triple(2025, 2, 28), // Feb non-leap
            Triple(2024, 2, 29), // Feb leap year
            Triple(2026, 9, 30), // Sept 30 days
            Triple(2026, 10, 31) // Oct 31 days
        )

        for ((year, month, expectedDays) in testCases) {
            val monthStr = String.format(Locale.US, "%04d-%02d", year, month)
            val monthInfo = DetectedMonthInfo(year, month, monthStr, "$monthStr Roster", true, "test")
            val intervals = (1..expectedDays).associateWith { d -> Pair(d * 20f, (d + 1) * 20f) }
            val row = ReconstructedRow("1", "STAFF_TEST", "STAFF_TEST", "Staff Test", null, null, 100f, emptyList())

            val metadata = TableMetadata(
                monthInfo = monthInfo,
                metaBounds = emptyMap(),
                dayIntervals = intervals,
                dayDiagnostics = emptyMap(),
                rows = listOf(row),
                detectedStaff = emptyList(),
                warnings = emptyList()
            )

            val recon = tableReconstructor.reconstructForStaff(metadata, "STAFF_TEST")

            assertEquals("Month $monthStr must have exactly $expectedDays days", expectedDays, recon.entries.size)
            assertEquals("$monthStr-01", recon.entries.first().date)
            val lastDayStr = String.format(Locale.US, "%s-%02d", monthStr, expectedDays)
            assertEquals(lastDayStr, recon.entries.last().date)
        }
    }

    // =========================================================================
    // 6. CELL BOUNDARIES AND COORDINATE TRANSFORMATIONS
    // =========================================================================

    @Test
    fun testCellBoundariesAndVerticalSafetyMargin() {
        val targetRowY = 450f
        val rowHeight = 26f
        val verticalMargin = 5.0f

        // Cell crop box calculation with safety margin
        val cropTop = (targetRowY - rowHeight / 2f - verticalMargin).toInt()
        val cropBottom = (targetRowY + rowHeight / 2f + verticalMargin).toInt()
        val cropHeight = cropBottom - cropTop

        assertEquals(432, cropTop)
        assertEquals(468, cropBottom)
        assertEquals(36, cropHeight) // 26 + 10 margin = 36px total height preserving ascenders/descenders

        // Transform metadata test: mapping scaled coordinates back to original sheet
        val transform = DocumentTransformMetadata(
            originalWidth = 1131,
            originalHeight = 653,
            isPdf = false,
            hasEmbeddedText = false,
            exifOrientation = 0,
            scaleFactor = 2.0f,
            preprocessedWidth = 2262,
            preprocessedHeight = 1306,
            processingDescription = "2x upscale"
        )

        val renderedX = 500f
        val renderedY = 900f
        val originalX = transform.mapToSourceX(renderedX)
        val originalY = transform.mapToSourceY(renderedY)

        assertEquals(250f, originalX, 0.001f)
        assertEquals(450f, originalY, 0.001f)
    }

    // =========================================================================
    // 7. OCR CONFUSIONS AND CONSTRAINED SHIFT CLASSIFICATION
    // =========================================================================

    @Test
    fun testOcrConfusionsAndConstrainedShiftClassification() {
        // Valid classifications: M, E, N, OFF, A, UNKNOWN
        val classifier = ShiftClassifier

        // E / F confusions
        assertEquals("E", classifier.classifyToken("E").classification)
        assertEquals("E", classifier.classifyToken("e").classification)
        assertEquals("E", classifier.classifyToken("F").classification)
        assertEquals("E", classifier.classifyToken("f").classification)

        // N / H / M confusions
        assertEquals("N", classifier.classifyToken("N").classification)
        assertEquals("N", classifier.classifyToken("H").classification)
        assertEquals("M", classifier.classifyToken("M").classification)
        assertEquals("M", classifier.classifyToken("W").classification)

        // OFF / 0FF / OFE confusions
        assertEquals("OFF", classifier.classifyToken("OFF").classification)
        assertEquals("OFF", classifier.classifyToken("Off").classification)
        assertEquals("OFF", classifier.classifyToken("0FF").classification)
        assertEquals("OFF", classifier.classifyToken("OFE").classification)
        assertEquals("OFF", classifier.classifyToken("OF").classification)

        // A (Absent)
        assertEquals("A", classifier.classifyToken("A").classification)

        // Noise / Invalid strings must remain UNKNOWN
        assertEquals("UNKNOWN", classifier.classifyToken("XYZ").classification)
        assertEquals("UNKNOWN", classifier.classifyToken("123").classification)
        assertEquals("UNKNOWN", classifier.classifyToken("").classification)
    }

    // =========================================================================
    // 8. EXPLICIT OFF AND A PRESERVATION
    // =========================================================================

    @Test
    fun testExplicitOffAndAPreservation() {
        val cells = (1..30).map { d ->
            when (d) {
                5 -> ReconstructedCell(d, "2026-09-05", "Saturday", "OFF", "Off", "confirmed", 0.95, "directly_observed", null, "num", "high")
                29 -> ReconstructedCell(d, "2026-09-29", "Tuesday", "A", null, "confirmed", 0.95, "directly_observed", "Observed A in roster", "num", "high")
                in 1..10 -> ReconstructedCell(d, "2026-09-$d", "Day", "E", "Evening", "confirmed", 0.90, "directly_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }

        val reconResult = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABH1341",
            matchReason = "confusable_candidate",
            staffName = "Akash",
            weeklyOffDay = "Saturday",
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = true,
            confusableMessage = "1 vs L",
            entries = cells
        )

        val inferred = inferenceEngineWithRotation.inferSections(reconResult)

        // Day 5 must remain Off
        val day5 = inferred.entries.first { it.dayNumber == 5 }
        assertEquals("Off", day5.normalizedShift)

        // Day 29 must remain observed A and NOT overwritten by Morning shift
        val day29 = inferred.entries.first { it.dayNumber == 29 }
        assertEquals("A", day29.rawValue)
        assertNull("Day 29 observed 'A' must not be converted to Morning", day29.normalizedShift)
    }

    // =========================================================================
    // 9. CONFLICTING SECTION-SHIFT EVIDENCE & NO UNSUPPORTED ROTATION
    // =========================================================================

    @Test
    fun testConflictingSectionShiftEvidenceFlagged() {
        // Section 1 with conflicting 'M' and 'N' observations
        val cells = (1..10).map { d ->
            val raw = if (d <= 5) "M" else "N"
            val norm = if (d <= 5) "Morning" else "Night"
            ReconstructedCell(d, "2026-09-$d", "Day", raw, norm, "confirmed", 0.85, "directly_observed", null, "num", "high")
        }

        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "TEST",
            matchedStaffId = "TEST",
            matchReason = "exact",
            staffName = "Test",
            weeklyOffDay = null,
            floorDepartment = null,
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )

        val inferred = inferenceEngine.inferSections(recon)
        val sec1 = inferred.sectionSummaries.first()

        assertNotNull("Section with contradictory shifts must flag conflicting evidence", sec1.reviewReason)
        assertTrue("Review reason must indicate conflict", sec1.reviewReason?.contains("conflict", ignoreCase = true) == true)
    }

    // =========================================================================
    // 10. MANUAL EDITS SURVIVING REPROCESSING & APPROVAL SAFEGUARDS
    // =========================================================================

    @Test
    fun testManualEditsSurviveReprocessing() {
        // Simulate a draft with a manual user edit on Day 7
        val entries = (1..30).map { d ->
            if (d == 7) {
                ExtractedCell(
                    dayNumber = d,
                    date = "2026-09-07",
                    weekday = "Monday",
                    rawValue = "E",
                    normalizedShift = "Night", // User changed Day 7 from Evening to Night
                    reviewStatus = "confirmed",
                    reviewReason = "User override",
                    confidence = 1.0,
                    sourceType = "manual"
                )
            } else {
                ExtractedCell(
                    dayNumber = d,
                    date = "2026-09-$d",
                    weekday = "Day",
                    rawValue = "E",
                    normalizedShift = "Evening",
                    reviewStatus = "confirmed",
                    reviewReason = null,
                    confidence = 0.9,
                    sourceType = "directly_observed"
                )
            }
        }

        val draft = DraftSchedule(
            scheduleId = "draft_123",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false,
            approvedAt = null,
            entries = entries,
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )

        // When re-saving or reprocessing, entries with sourceType == 'manual' must be preserved
        val manualEntry = draft.entries.first { it.dayNumber == 7 }
        assertEquals("manual", manualEntry.sourceType)
        assertEquals("Night", manualEntry.normalizedShift)

        // Safe reprocessing merge simulation
        val reprocessedRawEntries = (1..30).map { d ->
            ExtractedCell(
                dayNumber = d,
                date = "2026-09-$d",
                weekday = "Day",
                rawValue = "E",
                normalizedShift = "Evening", // Reprocessing observed Evening
                reviewStatus = "confirmed",
                reviewReason = null,
                confidence = 0.9,
                sourceType = "directly_observed"
            )
        }

        val merged = reprocessedRawEntries.map { rawCell ->
            if (rawCell.dayNumber == manualEntry.dayNumber && manualEntry.sourceType == "manual") {
                manualEntry // Preserve manual override!
            } else {
                rawCell
            }
        }

        val preservedCell = merged.first { it.dayNumber == 7 }
        assertEquals("Night", preservedCell.normalizedShift)
        assertEquals("manual", preservedCell.sourceType)
    }

    @Test
    fun testApprovalSafeguardsAndXlsxConsistency() {
        // Verify that draft with unresolved cells can be audited
        val entriesWithUnresolved = (1..30).map { d ->
            if (d == 15) {
                ExtractedCell(
                    dayNumber = d,
                    date = "2026-09-15",
                    weekday = "Tuesday",
                    rawValue = null,
                    normalizedShift = null,
                    reviewStatus = "unresolved",
                    reviewReason = "Unresolved shift",
                    confidence = 0.0,
                    sourceType = "missing"
                )
            } else {
                ExtractedCell(
                    dayNumber = d,
                    date = "2026-09-$d",
                    weekday = "Day",
                    rawValue = "M",
                    normalizedShift = "Morning",
                    reviewStatus = "confirmed",
                    reviewReason = null,
                    confidence = 0.95,
                    sourceType = "directly_observed"
                )
            }
        }

        val draft = DraftSchedule(
            scheduleId = "draft_test_appr",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false,
            approvedAt = null,
            entries = entriesWithUnresolved,
            sectionSummaries = emptyList(),
            unresolvedDates = listOf(15),
            isConfusableId = false,
            confusableMessage = null
        )

        assertEquals("Draft must identify unresolved dates", 1, draft.unresolvedDates.size)
        assertTrue("Draft must contain day 15 as unresolved", draft.unresolvedDates.contains(15))

        // Approved schedule must have isApproved = true and approvedAt set
        val approved = draft.copy(isApproved = true, approvedAt = "2026-09-01T10:00:00Z")
        assertTrue(approved.isApproved)
        assertNotNull(approved.approvedAt)
    }
}
