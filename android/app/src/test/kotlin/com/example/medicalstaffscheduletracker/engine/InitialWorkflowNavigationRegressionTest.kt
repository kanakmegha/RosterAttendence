package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.AppDestination
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import org.junit.Assert.*
import org.junit.Test

class InitialWorkflowNavigationRegressionTest {

    @Test
    fun testNewWorkflowOpensOnEmployeeIdEntryEvenWhenSavedDraftExists() {
        // Requirement 1: When the app launches for a new workflow, the FIRST screen must be Employee ID Entry.
        val coordinator = ExtractionWorkflowCoordinator()

        assertEquals(
            "Initial destination must be EMPLOYEE_ID_ENTRY for a new workflow",
            AppDestination.EMPLOYEE_ID_ENTRY,
            coordinator.currentScreen
        )
        assertEquals("Entered ID must start blank on launch", "", coordinator.enteredStaffId)
        assertFalse("Continue button must be disabled when ID is blank", coordinator.canProceedWithId)
        assertFalse("OCR must NOT be triggered on launch", coordinator.isOcrTriggered)
        assertEquals(0, coordinator.ocrRunCount)
        assertFalse("Cell OCR must NOT be triggered on launch", coordinator.isCellOcrTriggered)
        assertEquals(0, coordinator.cellOcrRunCount)
        assertNull("Selected file URI must be null", coordinator.selectedFileUri)
        assertNull("Table metadata must be null", coordinator.activeTableMetadata)

        // Simulate startup loading an existing schedule from SQLite via setResumableDraft:
        // Must NOT auto-fill enteredStaffId or navigate away from EMPLOYEE_ID_ENTRY!
        val existingDbSchedule = DraftSchedule(
            scheduleId = "saved_001",
            staffId = "OLD_STAFF_999",
            staffName = "Previous User",
            month = "2026-09",
            entries = emptyList(),
            isApproved = true,
            approvedAt = "2026-09-01T10:00:00Z",
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        coordinator.setResumableDraft(existingDbSchedule)

        assertEquals(
            "Loading resumable draft on startup must stay on EMPLOYEE_ID_ENTRY",
            AppDestination.EMPLOYEE_ID_ENTRY,
            coordinator.currentScreen
        )
        assertEquals(
            "Loading resumable draft on startup must NOT pre-populate enteredStaffId for a new workflow",
            "",
            coordinator.enteredStaffId
        )
        assertFalse("Continue button must remain disabled until user enters an ID", coordinator.canProceedWithId)
        assertNotNull("Resumable draft should be available for optional resume", coordinator.resumableDraft)
    }

    @Test
    fun testNoOcrTriggeredBeforeIdIsSubmitted() {
        // Requirement 4: Do not start OCR, scan the roster, or require staff detection before ID is entered.
        val coordinator = ExtractionWorkflowCoordinator()

        assertFalse("OCR must not be triggered initially", coordinator.isOcrTriggered)
        assertEquals(0, coordinator.ocrRunCount)
        assertFalse("Cell OCR must not be triggered initially", coordinator.isCellOcrTriggered)

        // Typing an ID does NOT trigger OCR
        coordinator.updateStaffId("ABH1341")
        assertFalse("Updating staff ID must not trigger OCR", coordinator.isOcrTriggered)
        assertEquals(0, coordinator.ocrRunCount)
        assertEquals("ABH1341", coordinator.enteredStaffId)

        // Submitting ID navigates to ROSTER_IMPORT but does not trigger OCR until a document is chosen
        val submitted = coordinator.submitStaffIdAndContinue()
        assertTrue("Submitting non-blank ID should succeed", submitted)
        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)
        assertFalse("Navigating to import screen must NOT trigger OCR prematurely", coordinator.isOcrTriggered)
        assertEquals(0, coordinator.ocrRunCount)
        assertFalse("Cell OCR must NOT be triggered on import screen", coordinator.isCellOcrTriggered)
    }

    @Test
    fun testBlankIdsCannotProceed() {
        // Requirement 3: Provide a Continue button that remains disabled only when ID is blank.
        val coordinator = ExtractionWorkflowCoordinator()

        // 1. Initially blank
        assertFalse("Blank ID cannot proceed", coordinator.canProceedWithId)
        val attempt1 = coordinator.submitStaffIdAndContinue()
        assertFalse("Submitting blank ID must fail", attempt1)
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)

        // 2. Whitespace only
        coordinator.updateStaffId("   ")
        assertFalse("Whitespace-only ID cannot proceed", coordinator.canProceedWithId)
        val attempt2 = coordinator.submitStaffIdAndContinue()
        assertFalse("Submitting whitespace ID must fail", attempt2)
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)

        // 3. Valid ID
        coordinator.updateStaffId("ABH1341")
        assertTrue("Non-blank ID can proceed", coordinator.canProceedWithId)

        // 4. Clearing ID makes it un-proceedable again
        coordinator.clearStaffId()
        assertEquals("", coordinator.enteredStaffId)
        assertFalse("Cleared ID cannot proceed", coordinator.canProceedWithId)
        assertFalse("Submitting cleared ID must fail", coordinator.submitStaffIdAndContinue())

        // 5. Re-entering valid ID proceeds to ROSTER_IMPORT
        coordinator.updateStaffId("  ABH1341 ")
        assertTrue(coordinator.canProceedWithId)
        assertTrue(coordinator.submitStaffIdAndContinue())
        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)
    }

    @Test
    fun testValidEnteredIdSurvivesNavigationToImportRowSelectionAndActiveDraft() {
        // Requirement 8 & 10: Valid entered ID survives navigation to import, row selection, refinement, and draft persistence
        val coordinator = ExtractionWorkflowCoordinator()
        coordinator.updateStaffId("ABH1341")
        coordinator.submitStaffIdAndContinue()

        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)

        // Simulate file selection
        coordinator.onFileSelected(null)
        assertTrue("Whole-sheet OCR triggered after file selection", coordinator.isOcrTriggered)
        assertEquals(1, coordinator.ocrRunCount)
        assertFalse("Cell-level shift OCR must NOT run before row selection", coordinator.isCellOcrTriggered)

        // Simulate metadata extraction matching exact ID
        val mockStaff = DetectedStaffMember(
            serialNumber = "17",
            rawId = "ABH1341",
            normalizedId = "ABH1341",
            name = "Akash",
            floor = "2",
            weeklyOffDay = "Sun",
            rowY = 320f
        )
        val metadata = TableMetadata(
            monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "Header"),
            metaBounds = emptyMap(),
            dayIntervals = mapOf(1 to Pair(0f, 30f)),
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = listOf(mockStaff),
            warnings = emptyList()
        )

        coordinator.onDocumentAnalysisCompleted(metadata)
        assertEquals(AppDestination.STAFF_SELECTION, coordinator.currentScreen)
        assertEquals(StaffMatchStatus.EXACT_MATCH, coordinator.staffMatchStatus)
        assertEquals("Entered ID must be preserved", "ABH1341", coordinator.enteredStaffId)
        assertEquals("Akash", coordinator.selectedStaffName)
        assertEquals(320f, coordinator.selectedRowY, 0.1f)
        assertFalse("Cell OCR still not triggered before row confirmation", coordinator.isCellOcrTriggered)

        // Confirm row selection -> ROW_REFINEMENT
        coordinator.confirmStaffAndRow(
            selectedId = "ABH1341",
            confirmedMonth = "2026-09",
            rowY = 320f,
            rowHeight = 26f,
            staffName = "Akash",
            matchedRowRawId = "ABH1341"
        )
        assertEquals(AppDestination.ROW_REFINEMENT, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)

        // Only now trigger cell-level shift OCR
        coordinator.startCellOcr()
        assertTrue(coordinator.isCellOcrTriggered)
        assertEquals(1, coordinator.cellOcrRunCount)

        val generatedDraft = DraftSchedule(
            scheduleId = "2026-09_ABH1341",
            month = "2026-09",
            staffId = "ABH1341",
            staffName = "Akash",
            isApproved = false,
            approvedAt = null,
            entries = emptyList(),
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        coordinator.onCellOcrCompleted(generatedDraft)
        assertEquals(AppDestination.EDITABLE_REVIEW, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.activeSchedule?.staffId)
    }

    @Test
    fun testIdNotDetectedByOcrStillAllowsManualRowSelectionWithoutLosingEnteredId() {
        // Requirement 8 & 9: If the ID is not detected or multiple candidates are ambiguous, let the user select
        // the correct employee row manually. Do not discard the entered ID or block progress.
        val coordinator = ExtractionWorkflowCoordinator()
        coordinator.updateStaffId("DR_UNKNOWN_99")
        coordinator.submitStaffIdAndContinue()

        // Metadata where the ID was NOT detected in OCR, but other rows exist
        val otherRow = DetectedStaffMember("1", "OTHER_1", "OTHER_1", "Nurse Kelly", "1", null, 150f)
        val metadata = TableMetadata(
            monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "Header"),
            metaBounds = emptyMap(),
            dayIntervals = mapOf(1 to Pair(0f, 30f)),
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = listOf(otherRow),
            warnings = listOf("Staff DR_UNKNOWN_99 not detected")
        )

        coordinator.onDocumentAnalysisCompleted(metadata)

        assertEquals(AppDestination.STAFF_SELECTION, coordinator.currentScreen)
        assertEquals(StaffMatchStatus.NOT_DETECTED, coordinator.staffMatchStatus)
        assertEquals("Entered ID must NOT be discarded", "DR_UNKNOWN_99", coordinator.enteredStaffId)
        assertNull("Must not auto-select an unrelated employee row", coordinator.matchedStaffMember)

        // Case A: User manually selects an OCR row (or synthetic MANUAL_ROW_450)
        // Even if selectedId passed is the OCR row's rawId or MANUAL_ROW_450, enteredStaffId must be preserved!
        coordinator.confirmStaffAndRow(
            selectedId = "MANUAL_ROW_450",
            confirmedMonth = "2026-09",
            rowY = 450f,
            rowHeight = 26f,
            staffName = "Staff (DR_UNKNOWN_99)",
            matchedRowRawId = "OTHER_1"
        )

        assertEquals("Should advance to ROW_REFINEMENT", AppDestination.ROW_REFINEMENT, coordinator.currentScreen)
        assertEquals("Entered ID must remain DR_UNKNOWN_99", "DR_UNKNOWN_99", coordinator.enteredStaffId)
        assertEquals("Current staff ID must remain DR_UNKNOWN_99", "DR_UNKNOWN_99", coordinator.currentStaffId)
        assertEquals("OTHER_1", coordinator.selectedMatchedRowId)
        assertEquals(450f, coordinator.selectedRowY, 0.1f)

        // Completing cell OCR persists the user's enteredStaffId in the active draft
        val draftWithOcrRowId = DraftSchedule(
            scheduleId = "2026-09_OTHER_1",
            month = "2026-09",
            staffId = "OTHER_1",
            staffName = "Staff (DR_UNKNOWN_99)",
            isApproved = false,
            approvedAt = null,
            entries = emptyList(),
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        coordinator.onCellOcrCompleted(draftWithOcrRowId)
        assertEquals(AppDestination.EDITABLE_REVIEW, coordinator.currentScreen)
        assertEquals("Draft schedule must persist the entered Employee ID", "DR_UNKNOWN_99", coordinator.activeSchedule?.staffId)
    }

    @Test
    fun testAmbiguousOrConfusableCandidatesDetectedAndPreservesEnteredId() {
        val coordinator = ExtractionWorkflowCoordinator()
        coordinator.updateStaffId("ABH1341")
        coordinator.submitStaffIdAndContinue()

        // Metadata with confusable candidate ABHL341 (L instead of 1)
        val confusableRow = DetectedStaffMember("17", "ABHL341", "ABHL341", "Akash", "2", "Sun", 310f)
        val metadata = TableMetadata(
            monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "Header"),
            metaBounds = emptyMap(),
            dayIntervals = mapOf(1 to Pair(0f, 30f)),
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = listOf(confusableRow),
            warnings = emptyList()
        )

        coordinator.onDocumentAnalysisCompleted(metadata)
        assertEquals(AppDestination.STAFF_SELECTION, coordinator.currentScreen)
        assertEquals(StaffMatchStatus.AMBIGUOUS_CANDIDATES, coordinator.staffMatchStatus)
        assertEquals(1, coordinator.ambiguousCandidates.size)
        assertEquals("ABHL341", coordinator.ambiguousCandidates.first().rawId)
        assertEquals("Entered ID must remain ABH1341", "ABH1341", coordinator.enteredStaffId)
    }

    @Test
    fun testReturningFromLaterScreenDoesNotLoseIdOrRestartOcr() {
        // Requirement: Ensure back navigation does not unexpectedly restart OCR or erase the ID.
        val coordinator = ExtractionWorkflowCoordinator()
        coordinator.updateStaffId("ABH1341")
        coordinator.submitStaffIdAndContinue()
        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)

        // Back from ROSTER_IMPORT -> EMPLOYEE_ID_ENTRY
        coordinator.navigateBack()
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)
        assertEquals("ID must be preserved on back navigation", "ABH1341", coordinator.enteredStaffId)
        assertEquals("OCR must not have run", 0, coordinator.ocrRunCount)

        // Forward to ROSTER_IMPORT again
        coordinator.submitStaffIdAndContinue()
        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)

        // Simulate file import & reaching STAFF_SELECTION
        coordinator.startDocumentAnalysis()
        assertEquals(1, coordinator.ocrRunCount)
        val metadata = TableMetadata(
            monthInfo = DetectedMonthInfo(2026, 9, "2026-09", "September 2026", true, "Header"),
            metaBounds = emptyMap(),
            dayIntervals = emptyMap(),
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = emptyList(),
            warnings = emptyList()
        )
        coordinator.onDocumentAnalysisCompleted(metadata)
        assertEquals(AppDestination.STAFF_SELECTION, coordinator.currentScreen)

        // Advance to ROW_REFINEMENT
        coordinator.confirmStaffAndRow("ABH1341", "2026-09", 200f, 26f, "Akash")
        assertEquals(AppDestination.ROW_REFINEMENT, coordinator.currentScreen)

        // Back from ROW_REFINEMENT -> STAFF_SELECTION
        coordinator.navigateBack()
        assertEquals(AppDestination.STAFF_SELECTION, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)
        assertEquals("Back navigation must not restart OCR", 1, coordinator.ocrRunCount)
        assertEquals("Back navigation must not trigger cell OCR", 0, coordinator.cellOcrRunCount)

        // Back from STAFF_SELECTION -> ROSTER_IMPORT
        coordinator.navigateBack()
        assertEquals(AppDestination.ROSTER_IMPORT, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)
        assertEquals("Back navigation must not restart OCR", 1, coordinator.ocrRunCount)

        // Back from ROSTER_IMPORT -> EMPLOYEE_ID_ENTRY
        coordinator.navigateBack()
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)
        assertEquals("ABH1341", coordinator.enteredStaffId)
        assertEquals("Back navigation must not restart OCR", 1, coordinator.ocrRunCount)
    }

    @Test
    fun testExistingDraftCanBeResumedCorrectly() {
        // Requirement: Ensure an existing draft can be resumed without losing its saved employee ID.
        val coordinator = ExtractionWorkflowCoordinator()

        val savedDraft = DraftSchedule(
            scheduleId = "draft_123",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false,
            approvedAt = null,
            entries = listOf(
                ExtractedCell(
                    dayNumber = 1,
                    date = "2026-09-01",
                    weekday = "Tue",
                    rawValue = "N",
                    normalizedShift = "Night",
                    reviewStatus = "confirmed",
                    reviewReason = null,
                    confidence = 0.95,
                    sourceType = "ocr"
                )
            ),
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )

        coordinator.resumeDraft(savedDraft)

        assertEquals(
            "Resuming unapproved draft must navigate to EDITABLE_REVIEW",
            AppDestination.EDITABLE_REVIEW,
            coordinator.currentScreen
        )
        assertEquals("Saved employee ID must be loaded", "ABH1341", coordinator.enteredStaffId)
        assertEquals("Saved employee ID must be set as currentStaffId", "ABH1341", coordinator.currentStaffId)
        assertNotNull("Active schedule must be set", coordinator.activeSchedule)
        assertEquals("Akash", coordinator.activeSchedule?.staffName)

        // If draft was already approved, it should navigate to CALENDAR
        val approvedSchedule = savedDraft.copy(isApproved = true)
        coordinator.resumeDraft(approvedSchedule)
        assertEquals(
            "Resuming approved schedule must navigate to CALENDAR",
            AppDestination.CALENDAR,
            coordinator.currentScreen
        )
        assertEquals("ABH1341", coordinator.enteredStaffId)

        // Starting a new workflow from CALENDAR resets to EMPLOYEE_ID_ENTRY with blank ID while keeping resumableDraft
        coordinator.startNewWorkflow()
        assertEquals(AppDestination.EMPLOYEE_ID_ENTRY, coordinator.currentScreen)
        assertEquals("", coordinator.enteredStaffId)
        assertEquals("ABH1341", coordinator.resumableDraft?.staffId)
    }
}
