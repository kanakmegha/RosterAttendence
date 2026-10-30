package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.AppDestination
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Regression test suite verifying the Review Mode interaction and schedule approval workflow:
 * 1. Editing a Requires Review cell.
 * 2. Accepting a rule-inferred shift.
 * 3. Manually changing an inferred shift.
 * 4. Persisting corrections after leaving and reopening Review Mode.
 * 5. Preventing OCR from overwriting manual corrections.
 * 6. Preventing approval when required items remain unresolved.
 * 7. Successfully approving after all required items are resolved.
 */
class ReviewModeAndApprovalWorkflowRegressionTest {

    private lateinit var inferenceEngine: LocalSectionInferenceEngine
    private lateinit var coordinator: ExtractionWorkflowCoordinator

    @Before
    fun setUp() {
        inferenceEngine = LocalSectionInferenceEngine(verifyRotationRuleDefault = true)
        coordinator = ExtractionWorkflowCoordinator()
    }

    private fun buildSampleDraftWithReviewAndInferredCells(): DraftSchedule {
        val cells = (1..30).map { day ->
            when (day) {
                // Directly observed anchors
                1, 2 -> ReconstructedCell(day, "2026-09-%02d".format(day), "Weekday", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                11, 12 -> ReconstructedCell(day, "2026-09-%02d".format(day), "Weekday", "E", "Evening", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                21, 22 -> ReconstructedCell(day, "2026-09-%02d".format(day), "Weekday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                // Directly observed OFF
                6, 13, 20, 27 -> ReconstructedCell(day, "2026-09-%02d".format(day), "Sunday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                // Ambiguous / Requires Review cell on Day 14 ("NN")
                14 -> ReconstructedCell(day, "2026-09-14", "Monday", "NN", "Night", "needs_review", 0.65, "ocr_observed", "Ambiguous token 'NN' requires review", "num", "high")
                // Non-target 'A' on Day 25 ("needs_review")
                25 -> ReconstructedCell(day, "2026-09-25", "Friday", "A", null, "needs_review", 0.85, "ocr_observed", "Observed 'A' requires review", "num", "high")
                // All other cells missing -> will be rule-inferred from section anchors
                else -> ReconstructedCell(day, "2026-09-%02d".format(day), "Weekday", null, null, "missing", null, null, null, "num", "high")
            }
        }

        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = "SUN",
            floorDepartment = "Floor 2",
            status = "needs_review",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )

        return inferenceEngine.inferSections(recon, verifyRotationRule = true)
    }

    // 1. Editing a Requires Review cell
    @Test
    fun test1_editingRequiresReviewCellUpdatesShiftAndPreservesDiagnostics() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()

        // Day 14 initially has reviewStatus == "needs_review" (conflicting / ambiguous 'NN')
        val day14Before = initialDraft.entries.first { it.dayNumber == 14 }
        assertEquals("needs_review", day14Before.reviewStatus)
        assertEquals("NN", day14Before.rawValue)
        assertTrue("Day 14 must be marked unresolved before user action", day14Before.isUnresolved)

        // User edits Day 14 to "E" (Evening)
        val afterEveningEdit = inferenceEngine.updateCellManual(initialDraft, 14, "E")
        val day14After = afterEveningEdit.entries.first { it.dayNumber == 14 }
        assertEquals("Evening", day14After.normalizedShift)
        assertEquals("E", day14After.classification)
        assertEquals("manual", day14After.sourceType)
        assertEquals("confirmed", day14After.reviewStatus)
        assertFalse("Day 14 must be resolved after saving manual correction", day14After.isUnresolved)
        assertEquals("Original OCR token 'NN' must be preserved for diagnostics", "NN", day14After.rawValue)

        // Day 25 initially has rawValue = "A", reviewStatus = "needs_review", normalizedShift = null
        val day25Before = afterEveningEdit.entries.first { it.dayNumber == 25 }
        assertEquals("needs_review", day25Before.reviewStatus)
        assertTrue(day25Before.isUnresolved)

        // User confirms/selects supported value "A" on Day 25
        val afterAEdit = inferenceEngine.updateCellManual(afterEveningEdit, 25, "A")
        val day25After = afterAEdit.entries.first { it.dayNumber == 25 }
        assertEquals("A", day25After.normalizedShift)
        assertEquals("A", day25After.classification)
        assertEquals("manual", day25After.sourceType)
        assertEquals("confirmed", day25After.reviewStatus)
        assertFalse("Day 25 must be resolved after selecting 'A'", day25After.isUnresolved)

        // User can also explicitly leave a cell unresolved if they cannot verify it
        val afterLeaveUnresolved = inferenceEngine.updateCellManual(afterAEdit, 14, null)
        val day14Unresolved = afterLeaveUnresolved.entries.first { it.dayNumber == 14 }
        assertNull("Unresolved cell must not be replaced with a guessed shift", day14Unresolved.normalizedShift)
        assertEquals("unresolved", day14Unresolved.reviewStatus)
        assertTrue(day14Unresolved.isExplicitlyUnresolved)
    }

    // 2. Accepting a rule-inferred shift
    @Test
    fun test2_acceptingRuleInferredShiftRetainsValueAndMarksUserConfirmed() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()

        // Day 3 is inferred from Section 1 anchors as "Morning"
        val day3Before = initialDraft.entries.first { it.dayNumber == 3 }
        assertEquals("Morning", day3Before.normalizedShift)
        assertEquals("inferred_from_section_anchor", day3Before.sourceType)
        assertEquals("inferred_needs_confirmation", day3Before.reviewStatus)
        assertEquals("section_anchor_fill", day3Before.inferenceRule)
        assertTrue("Rule-inferred cell must initially require confirmation", day3Before.requiresInferredConfirmation)
        assertFalse(day3Before.isUserConfirmedInferred)

        // User accepts the inferred shift on Day 3
        val updatedDraft = inferenceEngine.acceptInferredCell(initialDraft, 3)
        val day3After = updatedDraft.entries.first { it.dayNumber == 3 }

        assertEquals("Accepted inferred shift must retain value 'Morning'", "Morning", day3After.normalizedShift)
        assertEquals("Accepted inferred shift must retain inferred sourceType", "inferred_from_section_anchor", day3After.sourceType)
        assertEquals("Accepted inferred shift must retain inferenceRule", "section_anchor_fill", day3After.inferenceRule)
        assertEquals("Accepted inferred shift must be marked confirmed", "confirmed", day3After.reviewStatus)
        assertTrue("Accepted inferred shift must report isUserConfirmedInferred = true", day3After.isUserConfirmedInferred)
        assertFalse("Accepted inferred shift must no longer require confirmation", day3After.requiresInferredConfirmation)
        assertFalse("Accepted inferred shift must no longer be in unresolvedDates", updatedDraft.unresolvedDates.contains(3))
    }

    // 3. Manually changing an inferred shift
    @Test
    fun test3_manuallyChangingInferredShiftUpdatesValueAndMarksManual() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()

        // Day 4 is rule-inferred as "Morning"
        val day4Before = initialDraft.entries.first { it.dayNumber == 4 }
        assertEquals("Morning", day4Before.normalizedShift)
        assertTrue(day4Before.isInferred)

        // User disagrees with inference and changes Day 4 to "OFF"
        val updatedDraft = inferenceEngine.updateCellManual(initialDraft, 4, "OFF")
        val day4After = updatedDraft.entries.first { it.dayNumber == 4 }

        assertEquals("Off", day4After.normalizedShift)
        assertEquals("OFF", day4After.classification)
        assertEquals("manual", day4After.sourceType)
        assertEquals("confirmed", day4After.reviewStatus)
        assertTrue(day4After.isManual)
        assertFalse(day4After.isInferred)
        assertFalse(day4After.isUnresolved)
        assertEquals(1, updatedDraft.manuallyCorrectedCount)
    }

    // 4. Persisting corrections after leaving and reopening Review Mode
    @Test
    fun test4_persistingCorrectionsAfterLeavingAndReopeningReviewMode() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()
        coordinator.updateStaffId("ABHL341")
        coordinator.submitStaffIdAndContinue()
        coordinator.onCellOcrCompleted(initialDraft)
        assertEquals(AppDestination.EDITABLE_REVIEW, coordinator.currentScreen)

        // Perform actions in Review Mode:
        // - Accept inferred shift on Day 3 ("Morning")
        // - Manually edit Requires Review cell on Day 14 to "Evening"
        // - Manually change inferred shift on Day 4 to "Night"
        coordinator.acceptInferredCellInReview(3)
        coordinator.updateCellInReview(14, "Evening")
        coordinator.updateCellInReview(4, "Night")

        // Leave Review Mode (navigate to Calendar, then back to Employee ID Entry)
        coordinator.navigateToCalendar()
        assertEquals(AppDestination.CALENDAR, coordinator.currentScreen)
        coordinator.navigateBack()
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)

        // Reopen Review Mode
        val reopened = coordinator.reopenReviewMode()
        assertNotNull(reopened)
        assertEquals(AppDestination.EDITABLE_REVIEW, coordinator.currentScreen)

        val day3 = reopened!!.entries.first { it.dayNumber == 3 }
        val day4 = reopened.entries.first { it.dayNumber == 4 }
        val day14 = reopened.entries.first { it.dayNumber == 14 }

        assertTrue("Accepted inferred cell Day 3 must remain confirmed after reopening Review Mode", day3.isUserConfirmedInferred)
        assertEquals("Morning", day3.normalizedShift)

        assertTrue("Manual override on Day 4 must persist after reopening Review Mode", day4.isManual)
        assertEquals("Night", day4.normalizedShift)

        assertTrue("Manual correction on Day 14 must persist after reopening Review Mode", day14.isManual)
        assertEquals("Evening", day14.normalizedShift)
    }

    // 5. Preventing OCR from overwriting manual corrections
    @Test
    fun test5_preventingSubsequentOcrAndInferenceFromOverwritingManualCorrections() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()
        coordinator.updateStaffId("ABHL341")
        coordinator.submitStaffIdAndContinue()
        coordinator.onCellOcrCompleted(initialDraft)

        // User manually corrects Day 5 to "Night" and Day 14 to "Off", and accepts inferred Day 3 ("Morning")
        coordinator.updateCellInReview(5, "Night")
        coordinator.updateCellInReview(14, "OFF")
        coordinator.acceptInferredCellInReview(3)

        // Simulate subsequent OCR / re-extraction run where OCR predicts "Morning" for Day 5 and "Evening" for Day 14
        val reprocessedCells = (1..30).map { day ->
            ReconstructedCell(
                dayNumber = day,
                date = "2026-09-%02d".format(day),
                weekday = "Weekday",
                rawValue = if (day == 14) "E" else "M",
                normalizedShift = if (day == 14) "Evening" else "Morning",
                status = "confirmed",
                confidence = 0.92,
                provenance = "ocr_observed",
                reviewReason = null,
                anchorType = "num",
                anchorConfidence = "high"
            )
        }
        val reprocessedRecon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = "SUN",
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = reprocessedCells
        )

        val freshOcrDraft = inferenceEngine.inferSections(reprocessedRecon)
        coordinator.onCellOcrCompleted(freshOcrDraft)

        val activeAfterOcr = coordinator.activeSchedule!!
        val day5AfterOcr = activeAfterOcr.entries.first { it.dayNumber == 5 }
        val day14AfterOcr = activeAfterOcr.entries.first { it.dayNumber == 14 }

        assertEquals("Subsequent OCR must NEVER overwrite manual correction on Day 5", "Night", day5AfterOcr.normalizedShift)
        assertEquals("manual", day5AfterOcr.sourceType)
        assertEquals("confirmed", day5AfterOcr.reviewStatus)

        assertEquals("Subsequent OCR must NEVER overwrite manual correction on Day 14", "Off", day14AfterOcr.normalizedShift)
        assertEquals("manual", day14AfterOcr.sourceType)
        assertEquals("confirmed", day14AfterOcr.reviewStatus)
    }

    // 6. Preventing approval when required items remain unresolved
    @Test
    fun test6_preventingApprovalWhenRequiredItemsRemainUnresolved() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()
        coordinator.updateStaffId("ABHL341")
        coordinator.submitStaffIdAndContinue()
        coordinator.onCellOcrCompleted(initialDraft)

        // Initially there are unconfirmed rule-inferred shifts and 2 'needs_review' items (Days 14 and 25)
        assertTrue(initialDraft.unreviewedActionCount > 0)
        assertFalse("Approval must be blocked when unreviewed items remain", coordinator.canApproveCurrentSchedule())
        assertFalse(
            "Even with explicit unresolved acknowledgment, unreviewed action items must block approval",
            coordinator.canApproveCurrentSchedule(acknowledgeExplicitUnresolved = true)
        )
        assertFalse("approveCurrentSchedule must return false when blocked", coordinator.approveCurrentSchedule())
        assertEquals("Screen must remain on EDITABLE_REVIEW when approval is blocked", AppDestination.EDITABLE_REVIEW, coordinator.currentScreen)

        // Accept all rule-inferred shifts, but leave Days 14 and 25 in 'needs_review'
        coordinator.acceptAllInferredInReview()
        assertEquals(2, coordinator.activeSchedule!!.unreviewedActionCount)
        assertFalse("Approval must still be blocked while 'needs_review' cells (Days 14, 25) are unaddressed", coordinator.canApproveCurrentSchedule())

        // Resolve Day 25 to 'A', and explicitly mark Day 14 as 'Leave Unresolved' (null)
        coordinator.updateCellInReview(25, "A")
        coordinator.updateCellInReview(14, null)

        assertEquals(0, coordinator.activeSchedule!!.unreviewedActionCount)
        assertEquals(1, coordinator.activeSchedule!!.explicitUnresolvedCellCount)

        // Without acknowledging the 1 explicit unresolved cell, approval is still blocked
        assertFalse(
            "Explicitly unresolved cell must block approval until user acknowledges it",
            coordinator.canApproveCurrentSchedule(acknowledgeExplicitUnresolved = false)
        )
        // With acknowledgment, approval is allowed
        assertTrue(
            "Explicitly unresolved cell allows approval once user acknowledges it",
            coordinator.canApproveCurrentSchedule(acknowledgeExplicitUnresolved = true)
        )
    }

    // 7. Successfully approving after all required items are resolved
    @Test
    fun test7_successfullyApprovingAfterAllRequiredItemsAreResolvedAndExportingExcel() {
        val initialDraft = buildSampleDraftWithReviewAndInferredCells()
        coordinator.updateStaffId("ABHL341")
        coordinator.submitStaffIdAndContinue()
        coordinator.onCellOcrCompleted(initialDraft)

        // 1. Accept all rule-inferred shifts
        coordinator.acceptAllInferredInReview()
        // 2. Resolve Day 14 ('needs_review') to Evening ("E")
        coordinator.updateCellInReview(14, "E")
        // 3. Resolve Day 25 ('needs_review' observed 'A') to "A"
        coordinator.updateCellInReview(25, "A")

        val reviewedSchedule = coordinator.activeSchedule!!
        assertEquals(0, reviewedSchedule.unreviewedActionCount)
        assertEquals(0, reviewedSchedule.explicitUnresolvedCellCount)
        assertEquals(30, reviewedSchedule.confirmedCellCount)
        assertEquals(1.0f, reviewedSchedule.reviewProgressFraction, 0.001f)

        // Verify approval succeeds without needing unresolved acknowledgment
        assertTrue(coordinator.canApproveCurrentSchedule(acknowledgeExplicitUnresolved = false))
        val approved = coordinator.approveCurrentSchedule(acknowledgeExplicitUnresolved = false)
        assertTrue("Approval must succeed once all required items are resolved", approved)
        assertEquals(AppDestination.CALENDAR, coordinator.currentScreen)

        val finalApproved = coordinator.activeSchedule!!
        assertTrue(finalApproved.isApproved)
        assertEquals("Evening", finalApproved.entries.first { it.dayNumber == 14 }.normalizedShift)
        assertEquals("A", finalApproved.entries.first { it.dayNumber == 25 }.normalizedShift)
        assertEquals("Morning", finalApproved.entries.first { it.dayNumber == 3 }.normalizedShift)

        // Verify Excel (.xlsx) export matches the final reviewed schedule
        val xlsxBytes = ExcelWorkbookManager.generateXlsxBytes(finalApproved)
        assertTrue("Generated XLSX bytes must be non-empty", xlsxBytes.isNotEmpty())

        var sheetXml = ""
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    sheetXml = zis.readBytes().toString(Charsets.UTF_8)
                    break
                }
                entry = zis.nextEntry
            }
        }
        assertTrue("XLSX must contain worksheet XML", sheetXml.isNotEmpty())
        assertTrue("XLSX must include Staff ID ABHL341", sheetXml.contains("ABHL341"))
        assertTrue("XLSX must include Approved & Active status", sheetXml.contains("Approved &amp; Active"))
        assertTrue("XLSX must include Manually Corrected provenance", sheetXml.contains("Manually Corrected"))
        assertTrue("XLSX must include Rule-Inferred provenance", sheetXml.contains("Rule-Inferred"))
    }
}
