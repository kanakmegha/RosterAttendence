package com.example.medicalstaffscheduletracker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.medicalstaffscheduletracker.data.local.LocalScheduleRepository
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import com.example.medicalstaffscheduletracker.data.model.ManualDuty
import com.example.medicalstaffscheduletracker.engine.*
import com.example.medicalstaffscheduletracker.ui.screens.CalendarScreen
import com.example.medicalstaffscheduletracker.ui.screens.EditableReviewScreen
import com.example.medicalstaffscheduletracker.ui.screens.EmployeeIdEntryScreen
import com.example.medicalstaffscheduletracker.ui.screens.RosterImportScreen
import com.example.medicalstaffscheduletracker.ui.screens.RowRefinementScreen
import com.example.medicalstaffscheduletracker.ui.screens.StaffSelectionScreen
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

enum class AppDestination {
    EMPLOYEE_ID_ENTRY,
    ROSTER_IMPORT,
    STAFF_SELECTION,
    ROW_REFINEMENT,
    EDITABLE_REVIEW,
    CALENDAR
}

private fun synthesizeFallbackDraft(
    metadata: TableMetadata,
    staffId: String,
    staffName: String?
): DraftSchedule {
    val monthStr = metadata.monthInfo.monthStr
    val year = metadata.monthInfo.year
    val month = metadata.monthInfo.month
    val cal = Calendar.getInstance()
    cal.set(year, month - 1, 1)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val entries = (1..daysInMonth).map { day ->
        cal.set(year, month - 1, day)
        val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) ?: "Mon"
        ExtractedCell(
            dayNumber = day,
            date = dateStr,
            weekday = weekday,
            rawValue = null,
            normalizedShift = null,
            confidence = 0.0,
            sourceType = "unresolved",
            reviewStatus = "unresolved",
            reviewReason = "Manual shift entry required",
            classification = "UNRESOLVED"
        )
    }
    return DraftSchedule(
        scheduleId = "${monthStr}_${staffId}",
        month = monthStr,
        staffId = staffId,
        staffName = staffName ?: "Staff ($staffId)",
        entries = entries,
        isApproved = false,
        approvedAt = null,
        sectionSummaries = emptyList(),
        unresolvedDates = (1..daysInMonth).toList(),
        isConfusableId = false,
        confusableMessage = null,
        missingCount = daysInMonth
    )
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repository = remember { LocalScheduleRepository(context) }
    val onDeviceProcessor = remember { OnDeviceRosterProcessor(context) }

    var stateVersion by remember { mutableIntStateOf(0) }
    val coordinator = remember {
        ExtractionWorkflowCoordinator().apply {
            onStateChanged = { stateVersion++ }
        }
    }

    // Read stateVersion so Compose recomposes whenever coordinator state mutates
    @Suppress("UNUSED_VARIABLE")
    val observedVersion = stateVersion

    val currentScreen = coordinator.currentScreen
    val enteredStaffId = coordinator.enteredStaffId
    val currentStaffId = coordinator.currentStaffId
    val selectedStaffName = coordinator.selectedStaffName
    val selectedRowY = coordinator.selectedRowY
    val selectedRowHeight = coordinator.selectedRowHeight
    val selectedMatchedRowId = coordinator.selectedMatchedRowId
    val activeTableMetadata = coordinator.activeTableMetadata
    val activeSchedule = coordinator.activeSchedule
    val resumableDraft = coordinator.resumableDraft

    var currentMonth by remember { mutableStateOf<String?>(null) }
    var initialOcrDraft by remember { mutableStateOf<DraftSchedule?>(null) }
    var undoHistory by remember { mutableStateOf<List<DraftSchedule>>(emptyList()) }
    var manualDuties by remember { mutableStateOf<List<ManualDuty>>(emptyList()) }

    var isProcessing by remember { mutableStateOf(false) }
    var processingStage by remember { mutableStateOf(RosterProcessingStage.IDLE) }
    var processingStatus by remember { mutableStateOf<String?>(null) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    var syncErrorMessage by remember { mutableStateOf<String?>(null) }

    var showDiagnosticDialog by remember { mutableStateOf(false) }
    var isCloudBenchmarking by remember { mutableStateOf(false) }

    // System back button navigation: preserves enteredStaffId and never restarts OCR
    BackHandler(enabled = currentScreen != AppDestination.EMPLOYEE_ID_ENTRY) {
        coordinator.navigateBack()
    }

    // SAF file picker for importing roster files (Image / PDF)
    // Only invoked from ROSTER_IMPORT after the user has submitted their Employee ID
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importErrorMessage = null
            syncErrorMessage = null
            isProcessing = true
            processingStage = RosterProcessingStage.FILE_SELECTED
            val targetStaff = coordinator.enteredStaffId.ifBlank { coordinator.currentStaffId ?: "" }
            processingStatus = "Starting layout analysis for employee $targetStaff..."
            coordinator.startDocumentAnalysis()

            scope.launch {
                val result = onDeviceProcessor.analyzeRosterFile(uri) { stage, statusMsg ->
                    processingStage = stage
                    processingStatus = statusMsg
                }
                isProcessing = false
                processingStatus = null

                result.onSuccess { metadata ->
                    coordinator.onDocumentAnalysisCompleted(metadata)
                }.onFailure { err ->
                    importErrorMessage = "Initial OCR incomplete: ${err.message}. Manual entry and row selection enabled."
                    coordinator.onDocumentAnalysisFailed(err.message ?: "OCR incomplete")
                }
            }
        }
    }

    // Check for an existing saved draft on launch so the user CAN optionally resume it from
    // EmployeeIdEntryScreen, WITHOUT pre-filling enteredStaffId or navigating away from EMPLOYEE_ID_ENTRY.
    LaunchedEffect(Unit) {
        val savedSched = repository.getActiveSchedule(null, null)
        coordinator.setResumableDraft(savedSched)
        if (savedSched != null) {
            manualDuties = repository.getManualDutiesForMonth(savedSched.month)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentScreen) {
                AppDestination.EMPLOYEE_ID_ENTRY -> {
                    EmployeeIdEntryScreen(
                        initialStaffId = enteredStaffId,
                        savedDraft = activeSchedule ?: resumableDraft,
                        onStaffIdChange = { updatedId ->
                            coordinator.updateEnteredStaffId(updatedId)
                        },
                        onClearStaffId = {
                            coordinator.clearEnteredStaffId()
                        },
                        onContinueToImport = { staffId ->
                            coordinator.updateEnteredStaffId(staffId)
                            coordinator.submitStaffIdAndContinue()
                        },
                        onResumeDraft = { draft ->
                            scope.launch {
                                val latestDraft = repository.getScheduleById(draft.scheduleId)
                                    ?: repository.getActiveSchedule(draft.month, draft.staffId)
                                    ?: draft
                                initialOcrDraft = initialOcrDraft ?: latestDraft
                                currentMonth = latestDraft.month
                                manualDuties = repository.getManualDutiesForMonth(latestDraft.month)
                                coordinator.resumeDraft(latestDraft)
                            }
                        },
                        onViewCalendar = {
                            val draft = activeSchedule ?: resumableDraft
                            if (draft != null) {
                                currentMonth = draft.month
                                if (activeSchedule == null) {
                                    coordinator.updateActiveDraft(draft)
                                }
                            }
                            coordinator.navigateToCalendar()
                        }
                    )
                }

                AppDestination.ROSTER_IMPORT -> {
                    val staffId = enteredStaffId.ifBlank { currentStaffId ?: "" }
                    RosterImportScreen(
                        staffId = staffId,
                        isProcessing = isProcessing,
                        processingStage = processingStage,
                        processingStatus = processingStatus,
                        importErrorMessage = importErrorMessage,
                        onDismissImportError = { importErrorMessage = null },
                        onSelectFile = {
                            filePickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                        },
                        onChangeStaffId = {
                            coordinator.navigateBack()
                        },
                        onBack = {
                            coordinator.navigateBack()
                        }
                    )
                }

                AppDestination.CALENDAR -> {
                    CalendarScreen(
                        schedule = activeSchedule ?: resumableDraft,
                        manualDuties = manualDuties,
                        currentMonth = currentMonth ?: coordinator.confirmedMonthStr ?: activeSchedule?.month ?: resumableDraft?.month,
                        currentStaffId = currentStaffId ?: enteredStaffId.ifBlank { resumableDraft?.staffId },
                        onMonthChange = { newMonth ->
                            currentMonth = newMonth
                            scope.launch {
                                repository.setPreference("active_month", newMonth)
                                val lookupStaffId = currentStaffId ?: enteredStaffId.ifBlank { resumableDraft?.staffId }
                                val sched = repository.getActiveSchedule(newMonth, lookupStaffId)
                                if (sched != null) {
                                    coordinator.updateActiveDraft(sched)
                                }
                                manualDuties = repository.getManualDutiesForMonth(newMonth)
                            }
                        },
                        onStartImportRoster = {
                            // Starting a new roster extraction workflow always resets and begins at Employee ID Entry
                            importErrorMessage = null
                            syncErrorMessage = null
                            coordinator.startNewWorkflow()
                        },
                        onSaveManualDuty = { duty ->
                            scope.launch {
                                val sched = activeSchedule ?: resumableDraft
                                val activeId = sched?.scheduleId
                                val res = repository.saveManualDuty(context, duty, activeId)
                                val monthPrefix = currentMonth ?: sched?.month ?: duty.date.take(7)
                                manualDuties = repository.getManualDutiesForMonth(monthPrefix)
                                val refreshed = repository.getActiveSchedule(monthPrefix, currentStaffId ?: sched?.staffId)
                                if (refreshed != null) {
                                    coordinator.updateActiveDraft(refreshed)
                                }
                                if (res.isFailure) {
                                    syncErrorMessage = "Manual duty saved to SQLite, but Excel sync warning: ${res.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        onNavigateToDraftReview = {
                            val draft = activeSchedule ?: resumableDraft
                            if (draft != null) {
                                scope.launch {
                                    val latestDraft = repository.getScheduleById(draft.scheduleId)
                                        ?: repository.getActiveSchedule(draft.month, draft.staffId)
                                        ?: draft
                                    coordinator.reopenReviewMode(latestDraft.copy(isApproved = false))
                                }
                            }
                        },
                        onNavigateToRevisionDiff = {
                            // Revision diff
                        },
                        onRetryWorkbookSync = {
                            syncErrorMessage = null
                            val sched = activeSchedule ?: resumableDraft
                            if (sched != null && sched.isApproved) {
                                scope.launch {
                                    val res = ExcelWorkbookManager.writeApprovedScheduleWorkbook(context, sched)
                                    if (res.isFailure) {
                                        syncErrorMessage = res.exceptionOrNull()?.message
                                    }
                                }
                            }
                        },
                        isProcessing = isProcessing,
                        processingStage = processingStage,
                        processingStatus = processingStatus,
                        importErrorMessage = importErrorMessage,
                        onDismissImportError = { importErrorMessage = null },
                        syncErrorMessage = syncErrorMessage,
                        hasDiagnostics = onDeviceProcessor.lastDiagnosticReport != null,
                        onOpenDiagnostics = { showDiagnosticDialog = true }
                    )
                }

                AppDestination.STAFF_SELECTION -> {
                    val metadata = activeTableMetadata
                    val targetId = enteredStaffId.ifBlank { currentStaffId ?: "" }
                    if (metadata != null) {
                        StaffSelectionScreen(
                            metadata = metadata,
                            preselectedStaffId = targetId,
                            matchStatus = coordinator.staffMatchStatus,
                            ambiguousCandidates = coordinator.ambiguousCandidates,
                            onConfirmStaffAndMonth = { selectedId, confirmedMonth, rowY, rowHeight, staffName ->
                                currentMonth = confirmedMonth
                                coordinator.confirmStaffAndRow(
                                    selectedId = selectedId,
                                    confirmedMonth = confirmedMonth,
                                    rowY = rowY,
                                    rowHeight = rowHeight,
                                    staffName = staffName
                                )
                            },
                            onConfirmStaffRow = { selectedId, confirmedMonth, rowY, rowHeight, staffName, matchedRowRawId ->
                                currentMonth = confirmedMonth
                                coordinator.confirmStaffAndRow(
                                    selectedId = selectedId,
                                    confirmedMonth = confirmedMonth,
                                    rowY = rowY,
                                    rowHeight = rowHeight,
                                    staffName = staffName,
                                    matchedRowRawId = matchedRowRawId
                                )
                            },
                            onCancel = {
                                coordinator.navigateBack()
                            }
                        )
                    } else {
                        coordinator.navigateBack()
                    }
                }

                AppDestination.ROW_REFINEMENT -> {
                    val metadata = activeTableMetadata
                    val staffId = enteredStaffId.ifBlank { currentStaffId ?: "" }
                    if (metadata != null && staffId.isNotBlank()) {
                        RowRefinementScreen(
                            metadata = metadata,
                            selectedStaffId = staffId,
                            staffName = selectedStaffName,
                            initialRowY = selectedRowY,
                            initialRowHeight = selectedRowHeight,
                            onProceedWithCellLevelOcr = { cropStartY, cropHeight, useCloud ->
                                isProcessing = true
                                processingStage = RosterProcessingStage.OCR_COMPLETED
                                val engineName = if (useCloud) "OCR.space Engine 2 (Cloud)" else "Google ML Kit (Local On-Device)"
                                processingStatus = "Running cell-level OCR on $staffId using $engineName..."
                                coordinator.startCellOcr()

                                scope.launch {
                                    val result = onDeviceProcessor.refineRowWithCellLevelOcr(
                                        context = context,
                                        metadata = metadata,
                                        selectedStaffId = staffId,
                                        targetRowY = cropStartY.toFloat(),
                                        rowHeight = cropHeight.toFloat(),
                                        useCloudFallback = useCloud,
                                        matchedRowRawId = selectedMatchedRowId,
                                        staffNameHint = selectedStaffName
                                    ) { stage, msg ->
                                        processingStage = stage
                                        processingStatus = msg
                                    }
                                    isProcessing = false
                                    processingStatus = null

                                    val finalDraft = result.getOrElse { err ->
                                        importErrorMessage = "Cell-level OCR notice: ${err.message}. Using full-page extraction."
                                        onDeviceProcessor.extractScheduleForStaff(
                                            metadata = metadata,
                                            selectedStaffId = staffId,
                                            targetRowY = cropStartY.toFloat(),
                                            matchedRowRawId = selectedMatchedRowId,
                                            staffNameHint = selectedStaffName
                                        ).getOrElse {
                                            synthesizeFallbackDraft(metadata, staffId, selectedStaffName)
                                        }
                                    }.copy(staffId = staffId)

                                    val existingForStaff = (coordinator.activeSchedule ?: coordinator.resumableDraft)?.takeIf {
                                        it.staffId.equals(staffId, ignoreCase = true) && it.month == finalDraft.month
                                    }
                                    val mergedDraft = LocalSectionInferenceEngine().mergePreservingUserActions(existingForStaff, finalDraft)
                                    initialOcrDraft = finalDraft
                                    undoHistory = emptyList()
                                    val savedId = repository.saveDraftSchedule(mergedDraft, isApproved = false).getOrNull() ?: mergedDraft.scheduleId
                                    val persistedDraft = repository.getScheduleById(savedId) ?: mergedDraft
                                    coordinator.onCellOcrCompleted(persistedDraft)
                                }
                            },
                            onProceedWithOcrSpaceRefinement = { cropStartY, cropHeight ->
                                isProcessing = true
                                processingStage = RosterProcessingStage.OCR_COMPLETED
                                processingStatus = "Running focused OCR.space Engine 2 on employee row crop..."
                                coordinator.startCellOcr()

                                scope.launch {
                                    val result = onDeviceProcessor.refineRowWithFocusedOcr(
                                        context = context,
                                        metadata = metadata,
                                        selectedStaffId = staffId,
                                        cropStartY = cropStartY,
                                        cropHeight = cropHeight,
                                        matchedRowRawId = selectedMatchedRowId,
                                        staffNameHint = selectedStaffName
                                    ) { stage, msg ->
                                        processingStage = stage
                                        processingStatus = msg
                                    }
                                    isProcessing = false
                                    processingStatus = null

                                    val finalDraft = result.getOrElse { err ->
                                        importErrorMessage = "Focused refinement notice: ${err.message}. Using full-page extraction."
                                        onDeviceProcessor.extractScheduleForStaff(
                                            metadata = metadata,
                                            selectedStaffId = staffId,
                                            targetRowY = cropStartY.toFloat(),
                                            matchedRowRawId = selectedMatchedRowId,
                                            staffNameHint = selectedStaffName
                                        ).getOrElse {
                                            synthesizeFallbackDraft(metadata, staffId, selectedStaffName)
                                        }
                                    }.copy(staffId = staffId)

                                    val existingForStaff = (coordinator.activeSchedule ?: coordinator.resumableDraft)?.takeIf {
                                        it.staffId.equals(staffId, ignoreCase = true) && it.month == finalDraft.month
                                    }
                                    val mergedDraft = LocalSectionInferenceEngine().mergePreservingUserActions(existingForStaff, finalDraft)
                                    initialOcrDraft = finalDraft
                                    undoHistory = emptyList()
                                    val savedId = repository.saveDraftSchedule(mergedDraft, isApproved = false).getOrNull() ?: mergedDraft.scheduleId
                                    val persistedDraft = repository.getScheduleById(savedId) ?: mergedDraft
                                    coordinator.onCellOcrCompleted(persistedDraft)
                                }
                            },
                            onProceedWithoutRefinement = {
                                isProcessing = true
                                processingStage = RosterProcessingStage.SHIFTS_EXTRACTED
                                processingStatus = "Extracting shifts for $staffId using whole-roster OCR..."
                                coordinator.startCellOcr()

                                scope.launch {
                                    val result = onDeviceProcessor.extractScheduleForStaff(
                                        metadata = metadata,
                                        selectedStaffId = staffId,
                                        targetRowY = selectedRowY,
                                        matchedRowRawId = selectedMatchedRowId,
                                        staffNameHint = selectedStaffName
                                    )
                                    isProcessing = false
                                    processingStatus = null

                                    val finalDraft = result.getOrElse { err ->
                                        importErrorMessage = "Direct row extraction notice: ${err.message}. Manual shift review enabled."
                                        synthesizeFallbackDraft(metadata, staffId, selectedStaffName)
                                    }.copy(staffId = staffId)

                                    val existingForStaff = (coordinator.activeSchedule ?: coordinator.resumableDraft)?.takeIf {
                                        it.staffId.equals(staffId, ignoreCase = true) && it.month == finalDraft.month
                                    }
                                    val mergedDraft = LocalSectionInferenceEngine().mergePreservingUserActions(existingForStaff, finalDraft)
                                    initialOcrDraft = finalDraft
                                    undoHistory = emptyList()
                                    val savedId = repository.saveDraftSchedule(mergedDraft, isApproved = false).getOrNull() ?: mergedDraft.scheduleId
                                    val persistedDraft = repository.getScheduleById(savedId) ?: mergedDraft
                                    coordinator.onCellOcrCompleted(persistedDraft)
                                }
                            },
                            onBack = {
                                coordinator.navigateBack()
                            }
                        )
                    } else {
                        coordinator.navigateBack()
                    }
                }

                AppDestination.EDITABLE_REVIEW -> {
                    val draft = activeSchedule
                    if (draft != null) {
                        EditableReviewScreen(
                            draft = draft,
                            onUpdateCell = { dayNumber, newShift ->
                                val currentDraft = coordinator.activeSchedule ?: draft
                                undoHistory = undoHistory + currentDraft
                                val updated = coordinator.updateCellInReview(dayNumber, newShift)
                                    ?: LocalSectionInferenceEngine().updateCellManual(currentDraft, dayNumber, newShift)
                                val updatedCell = updated.entries.find { it.dayNumber == dayNumber }
                                scope.launch {
                                    if (updatedCell != null) {
                                        repository.updateExtractedCell(
                                            context = context,
                                            scheduleId = updated.scheduleId,
                                            cell = updatedCell
                                        )
                                    }
                                }
                            },
                            onAcceptCell = { dayNumber ->
                                val currentDraft = coordinator.activeSchedule ?: draft
                                undoHistory = undoHistory + currentDraft
                                val updated = coordinator.acceptInferredCellInReview(dayNumber)
                                    ?: LocalSectionInferenceEngine().acceptInferredCell(currentDraft, dayNumber)
                                val updatedCell = updated.entries.find { it.dayNumber == dayNumber }
                                scope.launch {
                                    if (updatedCell != null) {
                                        repository.updateExtractedCell(
                                            context = context,
                                            scheduleId = updated.scheduleId,
                                            cell = updatedCell
                                        )
                                    }
                                }
                            },
                            onAcceptAllInferred = {
                                val currentDraft = coordinator.activeSchedule ?: draft
                                undoHistory = undoHistory + currentDraft
                                val updated = coordinator.acceptAllInferredInReview()
                                    ?: LocalSectionInferenceEngine().acceptAllInferredCells(currentDraft)
                                scope.launch {
                                    repository.saveDraftSchedule(updated, isApproved = updated.isApproved)
                                }
                            },
                            onOverrideSection = { sectionIndex, newShift ->
                                val currentDraft = coordinator.activeSchedule ?: draft
                                val updated = LocalSectionInferenceEngine().overrideSectionShift(currentDraft, sectionIndex, newShift)
                                undoHistory = undoHistory + currentDraft
                                coordinator.updateActiveDraft(updated)
                                scope.launch {
                                    repository.saveDraftSchedule(updated, isApproved = updated.isApproved)
                                }
                            },
                            onOverrideWeeklyOff = { newWeekday ->
                                val currentDraft = coordinator.activeSchedule ?: draft
                                val parts = currentDraft.month.split("-")
                                val yr = parts.getOrNull(0)?.toIntOrNull() ?: 2026
                                val mo = parts.getOrNull(1)?.toIntOrNull() ?: 9
                                val (updated, _) = LocalSectionInferenceEngine().overrideWeeklyOff(currentDraft, newWeekday, yr, mo)
                                undoHistory = undoHistory + currentDraft
                                coordinator.updateActiveDraft(updated)
                                scope.launch {
                                    repository.saveDraftSchedule(updated, isApproved = updated.isApproved)
                                }
                            },
                            onUndo = {
                                if (undoHistory.isNotEmpty()) {
                                    val previous = undoHistory.last()
                                    undoHistory = undoHistory.dropLast(1)
                                    coordinator.updateActiveDraft(previous)
                                    scope.launch {
                                        repository.saveDraftSchedule(previous, isApproved = previous.isApproved)
                                    }
                                }
                            },
                            canUndo = undoHistory.isNotEmpty(),
                            onResetToOcrDraft = {
                                val initial = initialOcrDraft ?: return@EditableReviewScreen
                                val currentDraft = coordinator.activeSchedule ?: draft
                                undoHistory = undoHistory + currentDraft
                                coordinator.updateActiveDraft(initial)
                                scope.launch {
                                    repository.saveDraftSchedule(initial, isApproved = false)
                                }
                            },
                            onSaveDraftLocally = {
                                scope.launch {
                                    val persistedDraft = coordinator.activeSchedule ?: draft
                                    repository.saveDraftSchedule(persistedDraft, isApproved = false)
                                    importErrorMessage = "Draft saved locally to SQLite."
                                }
                            },
                            onApproveSchedule = {
                                val candidateDraft = coordinator.activeSchedule ?: draft
                                if (!LocalSectionInferenceEngine().canApproveSchedule(candidateDraft, acknowledgeExplicitUnresolved = true)) {
                                    return@EditableReviewScreen
                                }
                                scope.launch {
                                    isProcessing = true
                                    processingStage = RosterProcessingStage.SAVE_COMPLETED
                                    processingStatus = "Saving approved schedule & generating Excel workbook..."

                                    val persistedDraft = coordinator.activeSchedule ?: draft
                                    repository.saveDraftSchedule(persistedDraft, isApproved = false)
                                    val approveResult = repository.approveSchedule(context, persistedDraft.scheduleId)
                                    val reloadedApproved = repository.getScheduleById(persistedDraft.scheduleId)
                                        ?: persistedDraft.copy(isApproved = true)
                                    isProcessing = false
                                    processingStatus = null

                                    currentMonth = reloadedApproved.month

                                    if (approveResult.isFailure) {
                                        syncErrorMessage = "Schedule approved in SQLite, but Excel write failed: ${approveResult.exceptionOrNull()?.message}"
                                    }

                                    coordinator.onScheduleApproved(reloadedApproved)
                                }
                            },
                            onBackToCalendar = {
                                scope.launch {
                                    val persistedDraft = coordinator.activeSchedule ?: draft
                                    repository.saveDraftSchedule(persistedDraft, isApproved = persistedDraft.isApproved)
                                    coordinator.navigateToCalendar()
                                }
                            }
                        )
                    } else {
                        coordinator.navigateBack()
                    }
                }
            }

            if (showDiagnosticDialog && onDeviceProcessor.lastDiagnosticReport != null) {
                val report = onDeviceProcessor.lastDiagnosticReport!!
                com.example.medicalstaffscheduletracker.ui.screens.DeveloperDiagnosticDialog(
                    report = report,
                    onDismiss = { showDiagnosticDialog = false },
                    onRunCloudBenchmark = {
                        val bitmap = report.lastDecodedBitmap
                        if (bitmap != null) {
                            isCloudBenchmarking = true
                            scope.launch {
                                val benchResult = OcrSpaceBenchmarkHelper.benchmarkWithOcrSpace(context, bitmap)
                                isCloudBenchmarking = false
                                benchResult.onSuccess { summary ->
                                    onDeviceProcessor.updateCloudBenchmarkInReport(summary)
                                }.onFailure { err ->
                                    importErrorMessage = "Cloud OCR.space benchmark notice: ${err.message}"
                                }
                            }
                        }
                    },
                    isCloudBenchmarking = isCloudBenchmarking
                )
            }
        }
    }
}
