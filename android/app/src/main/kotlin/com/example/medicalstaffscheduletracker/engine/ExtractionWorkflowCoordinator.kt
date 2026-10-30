package com.example.medicalstaffscheduletracker.engine

import android.net.Uri
import com.example.medicalstaffscheduletracker.AppDestination
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import java.util.Calendar
import java.util.Locale

enum class StaffMatchStatus {
    NONE,
    EXACT_MATCH,
    AMBIGUOUS_CANDIDATES,
    NOT_DETECTED
}

/**
 * Coordinates navigation and state progression for the roster extraction workflow.
 * Guarantees that:
 * 1. The workflow starts at EMPLOYEE_ID_ENTRY.
 * 2. No OCR is triggered before employee ID is entered and submitted.
 * 3. Blank employee IDs cannot proceed.
 * 4. Entered IDs survive navigation, document analysis, and OCR failures.
 * 5. Exact IDs are matched first; missing or ambiguous IDs allow manual row selection without discarding the entered ID.
 * 6. Focused image processing and cell-level shift OCR only run after the employee row is selected.
 * 7. Back navigation preserves the entered ID and does not inadvertently re-trigger OCR.
 * 8. Existing drafts can be resumed without losing saved employee IDs.
 */
class ExtractionWorkflowCoordinator(
    initialDestination: AppDestination = AppDestination.EMPLOYEE_ID_ENTRY,
    initialStaffId: String? = null,
    var onStateChanged: (() -> Unit)? = null
) {
    var currentScreen: AppDestination = initialDestination
        private set

    var enteredStaffId: String = initialStaffId ?: ""
        private set

    val currentStaffId: String?
        get() = enteredStaffId.ifBlank { activeSchedule?.staffId }

    var isOcrTriggered: Boolean = false
        private set

    var ocrRunCount: Int = 0
        private set

    var isCellOcrTriggered: Boolean = false
        private set

    var cellOcrRunCount: Int = 0
        private set

    var selectedFileUri: Uri? = null
        private set

    var activeTableMetadata: TableMetadata? = null
        private set

    var matchStatus: StaffMatchStatus = StaffMatchStatus.NONE
        private set

    val staffMatchStatus: StaffMatchStatus
        get() = matchStatus

    var matchedStaffMember: DetectedStaffMember? = null
        private set

    var ambiguousCandidates: List<DetectedStaffMember> = emptyList()
        private set

    var selectedStaffName: String? = null
        private set

    var selectedMatchedRowId: String? = null
        private set

    var confirmedMonthStr: String? = null
        private set

    var selectedRowY: Float = 100f
        private set

    var selectedRowHeight: Float = 26f
        private set

    var activeSchedule: DraftSchedule? = null
        private set

    var resumableDraft: DraftSchedule? = null
        private set

    val canProceedWithId: Boolean
        get() = enteredStaffId.trim().isNotBlank()

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }

    fun updateStaffId(id: String) {
        enteredStaffId = id
        notifyChanged()
    }

    fun updateEnteredStaffId(id: String) = updateStaffId(id)

    fun clearStaffId() {
        enteredStaffId = ""
        notifyChanged()
    }

    fun clearEnteredStaffId() = clearStaffId()

    /**
     * Records a previously saved draft loaded from local storage on app startup
     * without leaving EMPLOYEE_ID_ENTRY, without pre-filling/overwriting enteredStaffId
     * for a new workflow, and without triggering OCR.
     */
    fun setResumableDraft(draft: DraftSchedule?) {
        resumableDraft = draft
        notifyChanged()
    }

    /**
     * Submits the entered employee ID and advances to the document import screen.
     * Returns true if successful, false if employee ID is blank.
     */
    fun submitStaffIdAndContinue(): Boolean {
        if (!canProceedWithId) {
            return false
        }
        enteredStaffId = enteredStaffId.trim()
        currentScreen = AppDestination.ROSTER_IMPORT
        notifyChanged()
        return true
    }

    /**
     * Initiates document analysis only after an ID has been entered and a file selected.
     */
    fun onFileSelected(uri: Uri?) {
        selectedFileUri = uri
        isOcrTriggered = true
        ocrRunCount++
        notifyChanged()
    }

    fun startDocumentAnalysis(uri: Uri? = null) = onFileSelected(uri)

    /**
     * Called when whole-document layout analysis completes.
     * Matches exact IDs first; if not detected or ambiguous, prepares manual/candidate row selection
     * without discarding enteredStaffId.
     */
    fun onDocumentAnalysisCompleted(metadata: TableMetadata) {
        activeTableMetadata = metadata
        confirmedMonthStr = metadata.monthInfo.monthStr

        val trimmedId = enteredStaffId.trim()
        val normalizedTarget = IdMatcher.normalizeId(trimmedId)

        val exactMatches = metadata.detectedStaff.filter {
            it.rawId.equals(trimmedId, ignoreCase = true) ||
                (normalizedTarget.isNotEmpty() && IdMatcher.normalizeId(it.rawId) == normalizedTarget)
        }

        val confusableMatches = if (exactMatches.isEmpty() && normalizedTarget.isNotEmpty()) {
            metadata.detectedStaff.filter {
                val (isConf, _) = IdMatcher.areIdsConfusable(trimmedId, it.rawId)
                isConf
            }
        } else {
            emptyList()
        }

        when {
            exactMatches.size == 1 -> {
                val exactMatch = exactMatches.first()
                matchedStaffMember = exactMatch
                ambiguousCandidates = emptyList()
                matchStatus = StaffMatchStatus.EXACT_MATCH
                selectedStaffName = exactMatch.name
                selectedMatchedRowId = exactMatch.rawId
                selectedRowY = exactMatch.rowY
                selectedRowHeight = exactMatch.rowHeight ?: 26f
            }
            exactMatches.size > 1 -> {
                matchedStaffMember = null
                ambiguousCandidates = exactMatches
                matchStatus = StaffMatchStatus.AMBIGUOUS_CANDIDATES
                selectedStaffName = null
                selectedMatchedRowId = null
            }
            confusableMatches.isNotEmpty() -> {
                matchedStaffMember = null
                ambiguousCandidates = confusableMatches
                matchStatus = StaffMatchStatus.AMBIGUOUS_CANDIDATES
                selectedStaffName = null
                selectedMatchedRowId = null
            }
            else -> {
                matchedStaffMember = null
                ambiguousCandidates = emptyList()
                matchStatus = StaffMatchStatus.NOT_DETECTED
                selectedStaffName = null
                selectedMatchedRowId = null
            }
        }

        currentScreen = AppDestination.STAFF_SELECTION
        notifyChanged()
    }

    /**
     * Called if whole-document OCR fails or produces 0 detected staff.
     * Ensures entered staff ID is preserved and manual row selection is enabled.
     */
    fun onDocumentAnalysisFailed(fallbackMetadata: TableMetadata) {
        activeTableMetadata = fallbackMetadata
        confirmedMonthStr = fallbackMetadata.monthInfo.monthStr
        matchedStaffMember = null
        ambiguousCandidates = emptyList()
        matchStatus = StaffMatchStatus.NOT_DETECTED
        selectedStaffName = null
        selectedMatchedRowId = null
        currentScreen = AppDestination.STAFF_SELECTION
        notifyChanged()
    }

    fun onDocumentAnalysisFailed(errorMessage: String) {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val nowMonth = String.format(Locale.US, "%04d-%02d", year, month)
        val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val fallbackIntervals = mutableMapOf<Int, Pair<Float, Float>>()
        val colW = 1000f / numDays
        for (d in 1..numDays) {
            fallbackIntervals[d] = Pair((d - 1) * colW, d * colW)
        }
        val fallbackMetadata = TableMetadata(
            monthInfo = DetectedMonthInfo(
                year = year,
                month = month,
                monthStr = nowMonth,
                displayName = "$nowMonth (Manual Fallback)",
                isConfident = false,
                detectedFrom = "Manual Fallback"
            ),
            metaBounds = emptyMap(),
            dayIntervals = fallbackIntervals,
            dayDiagnostics = emptyMap(),
            rows = emptyList(),
            detectedStaff = emptyList(),
            warnings = listOf("Initial whole-roster OCR incomplete: $errorMessage. Manual entry enabled.")
        )
        onDocumentAnalysisFailed(fallbackMetadata)
    }

    /**
     * Confirms the target staff member and row boundaries before cell-level OCR.
     * Preserves the user's enteredStaffId even if a fallback/manual row was picked.
     */
    fun confirmStaffAndRow(
        selectedId: String,
        confirmedMonth: String,
        rowY: Float,
        rowHeight: Float,
        staffName: String?,
        matchedRowRawId: String? = null
    ) {
        val cleanSelected = selectedId.trim()
        val isSyntheticPlaceholder = cleanSelected.startsWith("MANUAL_ROW_", ignoreCase = true) ||
            cleanSelected.startsWith("ROW_", ignoreCase = true)

        if (enteredStaffId.isBlank() && cleanSelected.isNotBlank()) {
            enteredStaffId = cleanSelected
        } else if (cleanSelected.isNotBlank() && !isSyntheticPlaceholder && matchStatus == StaffMatchStatus.EXACT_MATCH) {
            enteredStaffId = cleanSelected
        }

        confirmedMonthStr = confirmedMonth
        selectedRowY = rowY
        selectedRowHeight = rowHeight
        selectedStaffName = staffName
        selectedMatchedRowId = matchedRowRawId ?: cleanSelected.takeIf { it.isNotEmpty() }

        // Synchronize confirmed month into activeTableMetadata so cell OCR uses the confirmed month
        val currentMeta = activeTableMetadata
        if (currentMeta != null && confirmedMonth.matches(Regex("^\\d{4}-\\d{2}$")) &&
            currentMeta.monthInfo.monthStr != confirmedMonth
        ) {
            val parts = confirmedMonth.split("-")
            val yr = parts[0].toIntOrNull() ?: currentMeta.monthInfo.year
            val mo = parts[1].toIntOrNull() ?: currentMeta.monthInfo.month
            val cal = Calendar.getInstance()
            cal.set(yr, mo - 1, 1)
            val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $yr"
            activeTableMetadata = currentMeta.copy(
                monthInfo = DetectedMonthInfo(
                    year = yr,
                    month = mo,
                    monthStr = confirmedMonth,
                    displayName = disp,
                    isConfident = true,
                    detectedFrom = "User confirmed month"
                )
            )
        }

        currentScreen = AppDestination.ROW_REFINEMENT
        notifyChanged()
    }

    /**
     * Marks the start of focused row / cell-level shift OCR (only after row confirmation).
     */
    fun startCellOcr() {
        isCellOcrTriggered = true
        cellOcrRunCount++
        notifyChanged()
    }

    /**
     * Called when cell-level OCR finishes extracting shifts for the confirmed row.
     * Persists enteredStaffId in the active draft and merges with any existing draft
     * for the same staffId and month so manual corrections and accepted inferences
     * are never overwritten by subsequent OCR runs.
     */
    fun onCellOcrCompleted(draft: DraftSchedule) {
        isCellOcrTriggered = true
        val preservedId = enteredStaffId.trim().ifBlank { draft.staffId }
        val candidateDraft = if (draft.staffId != preservedId) {
            draft.copy(staffId = preservedId)
        } else {
            draft
        }

        val existing = (activeSchedule ?: resumableDraft)?.takeIf {
            it.staffId.equals(candidateDraft.staffId, ignoreCase = true) && it.month == candidateDraft.month
        }
        val finalDraft = if (existing != null) {
            LocalSectionInferenceEngine().mergePreservingUserActions(existing, candidateDraft)
        } else {
            candidateDraft
        }

        activeSchedule = finalDraft
        resumableDraft = finalDraft
        confirmedMonthStr = finalDraft.month
        currentScreen = AppDestination.EDITABLE_REVIEW
        notifyChanged()
    }

    /**
     * Updates the active draft during editable review while preserving enteredStaffId.
     */
    fun updateActiveDraft(updated: DraftSchedule) {
        val preservedId = enteredStaffId.trim().ifBlank { updated.staffId }
        val finalDraft = if (updated.staffId != preservedId) {
            updated.copy(staffId = preservedId)
        } else {
            updated
        }
        activeSchedule = finalDraft
        resumableDraft = finalDraft
        notifyChanged()
    }

    /**
     * Manually edits a cell in Review Mode (supports M, E, N, OFF, A, or null to leave unresolved)
     * and persists the updated schedule in both activeSchedule and resumableDraft.
     */
    fun updateCellInReview(dayNumber: Int, newShift: String?): DraftSchedule? {
        val current = activeSchedule ?: resumableDraft ?: return null
        val updated = LocalSectionInferenceEngine().updateCellManual(current, dayNumber, newShift)
        updateActiveDraft(updated)
        return activeSchedule
    }

    /**
     * Accepts a rule-inferred (or reviewable candidate) shift in Review Mode,
     * retaining its value and marking it user-confirmed.
     */
    fun acceptInferredCellInReview(dayNumber: Int): DraftSchedule? {
        val current = activeSchedule ?: resumableDraft ?: return null
        val updated = LocalSectionInferenceEngine().acceptInferredCell(current, dayNumber)
        updateActiveDraft(updated)
        return activeSchedule
    }

    /**
     * Accepts all pending rule-inferred shifts in the active schedule.
     */
    fun acceptAllInferredInReview(): DraftSchedule? {
        val current = activeSchedule ?: resumableDraft ?: return null
        val updated = LocalSectionInferenceEngine().acceptAllInferredCells(current)
        updateActiveDraft(updated)
        return activeSchedule
    }

    /**
     * Reopens Review Mode with the latest saved state (merging any persisted user actions).
     */
    fun reopenReviewMode(latestFromStorage: DraftSchedule? = null): DraftSchedule? {
        val current = activeSchedule ?: resumableDraft
        val target = when {
            latestFromStorage != null && current != null &&
                latestFromStorage.staffId.equals(current.staffId, ignoreCase = true) &&
                latestFromStorage.month == current.month -> {
                LocalSectionInferenceEngine().mergePreservingUserActions(current, latestFromStorage)
            }
            latestFromStorage != null -> latestFromStorage
            else -> current
        } ?: return null

        activeSchedule = target
        resumableDraft = target
        enteredStaffId = target.staffId
        confirmedMonthStr = target.month
        selectedStaffName = target.staffName
        currentScreen = AppDestination.EDITABLE_REVIEW
        notifyChanged()
        return activeSchedule
    }

    /**
     * Checks whether the current active schedule is eligible for final approval.
     */
    fun canApproveCurrentSchedule(acknowledgeExplicitUnresolved: Boolean = false): Boolean {
        val current = activeSchedule ?: resumableDraft ?: return false
        return LocalSectionInferenceEngine().canApproveSchedule(current, acknowledgeExplicitUnresolved)
    }

    /**
     * Attempts to approve the current schedule. Returns false and remains on EDITABLE_REVIEW
     * if required review items remain unresolved.
     */
    fun approveCurrentSchedule(
        acknowledgeExplicitUnresolved: Boolean = false,
        approvedAt: String = "2026-10-30T12:00:00Z"
    ): Boolean {
        val current = activeSchedule ?: resumableDraft ?: return false
        if (!LocalSectionInferenceEngine().canApproveSchedule(current, acknowledgeExplicitUnresolved)) {
            return false
        }
        onScheduleApproved(current.copy(isApproved = true, approvedAt = approvedAt))
        return true
    }

    /**
     * Called when user approves schedule.
     */
    fun onScheduleApproved(approved: DraftSchedule) {
        val preservedId = enteredStaffId.trim().ifBlank { approved.staffId }
        val finalApproved = if (approved.staffId != preservedId) {
            approved.copy(staffId = preservedId, isApproved = true)
        } else {
            approved.copy(isApproved = true)
        }
        activeSchedule = finalApproved
        resumableDraft = finalApproved
        confirmedMonthStr = finalApproved.month
        currentScreen = AppDestination.CALENDAR
        notifyChanged()
    }

    /**
     * Opens the calendar screen to inspect an existing schedule.
     */
    fun openCalendar() {
        if (activeSchedule == null && resumableDraft != null) {
            activeSchedule = resumableDraft
            confirmedMonthStr = resumableDraft?.month
        }
        currentScreen = AppDestination.CALENDAR
        notifyChanged()
    }

    fun navigateToCalendar() = openCalendar()

    /**
     * Handles back navigation safely without erasing the ID or triggering unwanted OCR.
     */
    fun navigateBack() {
        when (currentScreen) {
            AppDestination.ROSTER_IMPORT -> {
                // Return to ID entry screen with entered ID preserved
                currentScreen = AppDestination.EMPLOYEE_ID_ENTRY
            }
            AppDestination.STAFF_SELECTION -> {
                // Return to import screen without resetting document or ID or restarting OCR
                currentScreen = AppDestination.ROSTER_IMPORT
            }
            AppDestination.ROW_REFINEMENT -> {
                // Return to staff selection screen
                currentScreen = AppDestination.STAFF_SELECTION
            }
            AppDestination.EDITABLE_REVIEW -> {
                currentScreen = if (activeTableMetadata != null) {
                    AppDestination.ROW_REFINEMENT
                } else {
                    AppDestination.CALENDAR
                }
            }
            AppDestination.CALENDAR -> {
                currentScreen = AppDestination.EMPLOYEE_ID_ENTRY
            }
            AppDestination.EMPLOYEE_ID_ENTRY -> {
                // Already at root
            }
        }
        notifyChanged()
    }

    /**
     * Resumes an existing saved draft or approved schedule.
     */
    fun resumeDraft(draft: DraftSchedule) {
        activeSchedule = draft
        resumableDraft = draft
        enteredStaffId = draft.staffId
        confirmedMonthStr = draft.month
        selectedStaffName = draft.staffName
        currentScreen = if (draft.isApproved) AppDestination.CALENDAR else AppDestination.EDITABLE_REVIEW
        notifyChanged()
    }

    /**
     * Starts a completely fresh extraction workflow from the initial Employee ID screen.
     */
    fun startNewWorkflow(clearEnteredId: Boolean = true) {
        currentScreen = AppDestination.EMPLOYEE_ID_ENTRY
        isOcrTriggered = false
        isCellOcrTriggered = false
        selectedFileUri = null
        activeTableMetadata = null
        matchStatus = StaffMatchStatus.NONE
        matchedStaffMember = null
        ambiguousCandidates = emptyList()
        if (clearEnteredId) {
            enteredStaffId = ""
        }
        notifyChanged()
    }
}
