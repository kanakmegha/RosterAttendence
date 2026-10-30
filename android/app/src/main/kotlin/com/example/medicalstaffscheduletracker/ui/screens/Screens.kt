package com.example.medicalstaffscheduletracker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import com.example.medicalstaffscheduletracker.data.model.ManualDuty
import com.example.medicalstaffscheduletracker.engine.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =========================================================================
// 0. STEP 1: EMPLOYEE ID ENTRY SCREEN (FIRST SCREEN FOR NEW WORKFLOW)
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeIdEntryScreen(
    initialStaffId: String = "",
    savedDraft: DraftSchedule? = null,
    onStaffIdChange: (String) -> Unit = {},
    onClearStaffId: () -> Unit = {},
    onContinueToImport: (staffId: String) -> Unit,
    onResumeDraft: (DraftSchedule) -> Unit = {},
    onViewCalendar: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    var staffId by remember(initialStaffId) { mutableStateOf(initialStaffId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Branding & Header
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("🏥", fontSize = 28.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Medical Staff Schedule Tracker",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "STEP 1: ENTER EMPLOYEE ID",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Instructions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Enter Your Staff Identifier",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type or paste your hospital employee ID below. We will use this ID to locate your specific shift row on the roster document.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Employee ID Text Field
        OutlinedTextField(
            value = staffId,
            onValueChange = {
                staffId = it
                onStaffIdChange(it)
            },
            label = { Text("Employee ID") },
            placeholder = { Text("e.g. ABH1341") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            trailingIcon = {
                if (staffId.isNotEmpty()) {
                    IconButton(onClick = {
                        staffId = ""
                        onStaffIdChange("")
                        onClearStaffId()
                    }) {
                        Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Helper Buttons (Paste & Clear)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        val trimmed = clip.trim()
                        staffId = trimmed
                        onStaffIdChange(trimmed)
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("📋 Paste from Clipboard", fontSize = 12.sp)
            }

            if (staffId.isNotEmpty()) {
                TextButton(
                    onClick = {
                        staffId = ""
                        onStaffIdChange("")
                        onClearStaffId()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Primary Action: Continue Button
        Button(
            onClick = { onContinueToImport(staffId.trim()) },
            enabled = staffId.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (staffId.isNotBlank()) "Continue to Import Roster ➔" else "Enter Employee ID to Continue",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        // Resume Active Draft / Saved Schedule (if available)
        if (savedDraft != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Existing Schedule Available",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Surface(
                            color = if (savedDraft.isApproved) Color(0xFF16A34A) else Color(0xFFEA580C),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (savedDraft.isApproved) "APPROVED" else "DRAFT",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Employee: ${savedDraft.staffId} (${savedDraft.staffName ?: "Staff"}) • Month: ${savedDraft.month} (${savedDraft.entries.size} days)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onResumeDraft(savedDraft) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Resume Draft", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onViewCalendar,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View Calendar", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 0B. STEP 2: ROSTER DOCUMENT IMPORT SCREEN
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterImportScreen(
    staffId: String,
    isProcessing: Boolean,
    processingStage: RosterProcessingStage,
    processingStatus: String?,
    importErrorMessage: String?,
    onDismissImportError: () -> Unit,
    onSelectFile: () -> Unit,
    onChangeStaffId: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("◀", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Step 2: Import Roster",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Target Employee Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Target Employee ID",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        text = staffId,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                OutlinedButton(
                    onClick = onChangeStaffId,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Change ID", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Document Picker Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "📄", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select Duty Roster Document",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose an original roster photo (JPEG/PNG) or document (PDF).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSelectFile,
                    enabled = !isProcessing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📂 Pick Document (PDF / Image)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Processing Banner
        if (isProcessing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Column {
                        Text(
                            text = processingStage.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = processingStatus ?: "Analyzing document structure for $staffId...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // Error message banner
        if (importErrorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠ Analysis Notice",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = onDismissImportError) {
                            Text("✕ Dismiss", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Text(
                        text = importErrorMessage,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

// =========================================================================
// 1. MONTHLY CALENDAR SCREEN (100% LOCAL-FIRST)
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    schedule: DraftSchedule?,
    manualDuties: List<ManualDuty>,
    currentMonth: String?,
    currentStaffId: String?,
    onMonthChange: (String) -> Unit,
    onStartImportRoster: () -> Unit,
    onSaveManualDuty: (ManualDuty) -> Unit,
    onNavigateToDraftReview: () -> Unit,
    onNavigateToRevisionDiff: () -> Unit,
    onRetryWorkbookSync: () -> Unit,
    isProcessing: Boolean,
    processingStage: RosterProcessingStage,
    processingStatus: String?,
    importErrorMessage: String? = null,
    onDismissImportError: () -> Unit = {},
    syncErrorMessage: String?,
    hasDiagnostics: Boolean = false,
    onOpenDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedCell by remember { mutableStateOf<ExtractedCell?>(null) }
    var showManualDutyDialog by remember { mutableStateOf(false) }
    var showOcrSettingsDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // Parse Month and Year for Display
    val displayMonth = currentMonth ?: schedule?.month
    val monthDisplayName = remember(displayMonth) {
        if (displayMonth != null) {
            val parts = displayMonth.split("-")
            val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 9
            val cal = Calendar.getInstance()
            cal.set(y, m - 1, 1)
            val monthName = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) ?: ""
            "$monthName $y"
        } else {
            "No Schedule Selected"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Top App Bar / Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Duty Schedule Tracker",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (schedule != null) "Staff: ${schedule.staffId} (${schedule.staffName ?: "Staff"})"
                    else "Primary: OCR.space • Standalone",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showOcrSettingsDialog = true }) {
                    Text("⚙", fontSize = 18.sp)
                }
                if (hasDiagnostics) {
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🛠 Diag", fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = onStartImportRoster,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Import Roster", fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Month Navigation Header (only if schedule exists or month is defined)
        if (displayMonth != null && schedule != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val parts = displayMonth.split("-")
                    val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: 9

                    IconButton(onClick = {
                        val cal = Calendar.getInstance()
                        cal.set(y, m - 1, 1)
                        cal.add(Calendar.MONTH, -1)
                        val newY = cal.get(Calendar.YEAR)
                        val newM = cal.get(Calendar.MONTH) + 1
                        onMonthChange(String.format(Locale.US, "%04d-%02d", newY, newM))
                    }) {
                        Text("◀", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = monthDisplayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )

                    IconButton(onClick = {
                        val cal = Calendar.getInstance()
                        cal.set(y, m - 1, 1)
                        cal.add(Calendar.MONTH, 1)
                        val newY = cal.get(Calendar.YEAR)
                        val newM = cal.get(Calendar.MONTH) + 1
                        onMonthChange(String.format(Locale.US, "%04d-%02d", newY, newM))
                    }) {
                        Text("▶", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Shift Legend
            ShiftLegendBar()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Processing Progress Banner
        if (isProcessing) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Column {
                        Text(
                            text = processingStage.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = processingStatus ?: "Processing on device...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // Dedicated Roster Import Error Banner (with Retry and Dismiss)
        if (importErrorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠ Roster Import Analysis Failed",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(
                            onClick = onDismissImportError,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("✕ Dismiss", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = importErrorMessage,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onStartImportRoster,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Retry Import", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                        }
                        OutlinedButton(
                            onClick = { showOcrSettingsDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("⚙ Configure OCR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                OcrKeyManager.setActiveProvider(context, OcrProviderType.ML_KIT)
                                onDismissImportError()
                                onStartImportRoster()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("Switch to Local ML Kit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (hasDiagnostics) {
                            OutlinedButton(
                                onClick = onOpenDiagnostics,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("🛠 Diag", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Excel Sync Warning Banner (with Retry) - Only shown for actual workbook file errors
        if (syncErrorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚠ Excel sync error: $syncErrorMessage",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetryWorkbookSync) {
                        Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (exportMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { exportMessage = null }
            ) {
                Text(
                    text = exportMessage!!,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Empty State or Calendar Day List
        if (schedule == null || schedule.entries.isEmpty()) {
            EmptyCalendarState(
                onImportRoster = onStartImportRoster,
                onAddEmergencyDuty = { showManualDutyDialog = true }
            )
        } else {
            // Schedule Summary Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (schedule.isApproved) "✓ Active Schedule (Excel Synced)" else "⚠ Unapproved Draft",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (schedule.isApproved) Color(0xFF047857) else Color(0xFFD97706)
                    )
                    Text(
                        text = "Documents/${ExcelWorkbookManager.FOLDER_NAME}/${ExcelWorkbookManager.getWorkbookFileName(schedule.staffId, schedule.month)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onNavigateToDraftReview) {
                        Text(if (schedule.isApproved) "Review / Edit" else "Review Draft", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        val file = ExcelWorkbookManager.getExistingWorkbookFile(context, schedule.staffId, schedule.month)
                        if (file != null) {
                            exportMessage = "Workbook: ${file.name} (${file.length()} bytes)"
                        } else {
                            scope.launch {
                                val res = ExcelWorkbookManager.writeApprovedScheduleWorkbook(context, schedule)
                                res.onSuccess { f ->
                                    exportMessage = "Workbook created: ${f.name}"
                                }.onFailure {
                                    exportMessage = "Write failed: ${it.message}"
                                }
                            }
                        }
                    }) {
                        Text("Excel .xlsx", fontSize = 12.sp)
                    }
                    TextButton(onClick = { showManualDutyDialog = true }) {
                        Text("+ Emergency", fontSize = 12.sp)
                    }
                }
            }

            // Entries List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(schedule.entries) { entry ->
                    CalendarDayRow(
                        entry = entry,
                        manualDuty = manualDuties.find { it.date == entry.date },
                        onClick = { selectedCell = entry }
                    )
                }
            }
        }
    }

    // Cell Detail & Edit Dialog
    if (selectedCell != null) {
        val cell = selectedCell!!
        DateDetailDialog(
            cell = cell,
            onDismiss = { selectedCell = null },
            onEditDuty = { showManualDutyDialog = true }
        )
    }

    // Manual Emergency Duty Dialog
    if (showManualDutyDialog) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val defaultDate = selectedCell?.date ?: displayMonth?.let { "$it-01" } ?: todayStr

        ManualEmergencyDutyDialog(
            initialDate = defaultDate,
            onDismiss = { showManualDutyDialog = false },
            onSave = { duty ->
                onSaveManualDuty(duty)
                showManualDutyDialog = false
            }
        )
    }

    if (showOcrSettingsDialog) {
        OcrSettingsDialog(onDismiss = { showOcrSettingsDialog = false })
    }
}

// =========================================================================
// 2. EMPTY CALENDAR STATE (FRESH INSTALL / NO SAVED ROSTER)
// =========================================================================

@Composable
fun EmptyCalendarState(
    onImportRoster: () -> Unit,
    onAddEmergencyDuty: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("📅", fontSize = 28.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Duty Roster Imported",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Welcome to Medical Staff Schedule Tracker. Import a monthly duty roster photo or scanned PDF to reconstruct your calendar.\n\nAll OCR, table reconstruction, and shift inference run 100% locally on your device without internet access. An accessible Excel (.xlsx) file will be saved in your Documents folder.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            Button(
                onClick = onImportRoster,
                modifier = Modifier.fillMaxWidth(0.85f),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text("Import Roster (Image / PDF)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onAddEmergencyDuty,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Text("Log Manual Emergency Duty")
            }
        }
    }
}

// =========================================================================
// 2. OCR PROVIDER & API KEY CONFIGURATION DIALOG
// =========================================================================

@Composable
fun OcrSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activeProvider by remember { mutableStateOf(OcrKeyManager.getActiveProvider(context)) }
    var activeEngine by remember { mutableStateOf(OcrKeyManager.getActiveEngine(context)) }
    var customKey by remember { mutableStateOf(OcrKeyManager.getApiKey(context)) }
    var showSavedNotification by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("⚙ OCR Provider & Key Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Primary OCR Provider", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "OCR.space is the primary provider for high-accuracy tabular roster imports. Google ML Kit is available as an on-device fallback.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = activeProvider == OcrProviderType.OCR_SPACE,
                        onClick = {
                            activeProvider = OcrProviderType.OCR_SPACE
                            OcrKeyManager.setActiveProvider(context, OcrProviderType.OCR_SPACE)
                        }
                    )
                    Column(
                        modifier = Modifier.clickable {
                            activeProvider = OcrProviderType.OCR_SPACE
                            OcrKeyManager.setActiveProvider(context, OcrProviderType.OCR_SPACE)
                        }
                    ) {
                        Text("OCR.space (Cloud API - Primary)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("High precision table recognition with 2D word bounding boxes", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = activeProvider == OcrProviderType.ML_KIT,
                        onClick = {
                            activeProvider = OcrProviderType.ML_KIT
                            OcrKeyManager.setActiveProvider(context, OcrProviderType.ML_KIT)
                        }
                    )
                    Column(
                        modifier = Modifier.clickable {
                            activeProvider = OcrProviderType.ML_KIT
                            OcrKeyManager.setActiveProvider(context, OcrProviderType.ML_KIT)
                        }
                    ) {
                        Text("Google ML Kit (On-Device Fallback)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("100% on-device & offline, no API key required", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (activeProvider == OcrProviderType.OCR_SPACE) {
                    Divider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("OCR.space Engine", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Engine 2 is benchmarked and recommended for tables and tabular rosters.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))

                    val engines = listOf(
                        "2" to "Engine 2 (Table & Structured - Fast & Recommended)",
                        "3" to "Engine 3 (Deep Learning - Free tier may time out)",
                        "1" to "Engine 1 (Standard OCR Engine)"
                    )
                    engines.forEach { (engId, label) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = activeEngine == engId,
                                onClick = {
                                    activeEngine = engId
                                    OcrKeyManager.setActiveEngine(context, engId)
                                }
                            )
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    activeEngine = engId
                                    OcrKeyManager.setActiveEngine(context, engId)
                                }
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("OCR.space API Key", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Current Status: ${OcrKeyManager.getMaskedKey(context)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("Enter OCR.space API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                OcrKeyManager.setApiKey(context, customKey)
                                showSavedNotification = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Save Key", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                customKey = ""
                                OcrKeyManager.setApiKey(context, "")
                                showSavedNotification = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Reset to Default", fontSize = 11.sp)
                        }
                    }
                    if (showSavedNotification) {
                        Text("✓ Key settings saved", color = Color(0xFF10B981), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 10.dp))
                // Architecture and Security notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Security & Architecture Note", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• Direct Mobile Call: Keeps the app local-first and standalone with zero ongoing server hosting costs. On rooted devices, network inspection could expose client-side keys.\n" +
                            "• Proxy Server Alternative: Safely stores the key on an authoritative server, but makes mobile roster import dependent on remote backend uptime and internet access.\n" +
                            "• Key Protection: Keys are stored in private SharedPreferences or compile-time BuildConfig, never committed to git or printed in logs.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

// =========================================================================
// 3. COMPLETE ROSTER EXTRACTION REVIEW SCREEN
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterExtractionReviewScreen(
    metadata: TableMetadata,
    preselectedStaffId: String? = null,
    matchStatus: StaffMatchStatus = StaffMatchStatus.NONE,
    ambiguousCandidates: List<DetectedStaffMember> = emptyList(),
    onConfirmStaffAndMonth: (selectedStaffId: String, confirmedMonth: String, rowY: Float, rowHeight: Float, staffName: String?) -> Unit,
    onConfirmStaffRow: ((selectedStaffId: String, confirmedMonth: String, rowY: Float, rowHeight: Float, staffName: String?, matchedRowRawId: String?) -> Unit)? = null,
    onCancel: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val enteredStaffId = remember(preselectedStaffId) { preselectedStaffId?.trim() ?: "" }
    var filterQuery by remember { mutableStateOf("") }

    // Match exact IDs first; do NOT auto-select the first roster row if the entered ID was not matched or is ambiguous!
    val exactMatches = remember(enteredStaffId, metadata.detectedStaff) {
        if (enteredStaffId.isNotBlank()) {
            metadata.detectedStaff.filter {
                it.rawId.equals(enteredStaffId, ignoreCase = true) ||
                it.normalizedId.equals(enteredStaffId, ignoreCase = true)
            }
        } else emptyList()
    }

    var selectedStaffMember by remember(enteredStaffId, metadata.detectedStaff) {
        mutableStateOf(
            when {
                enteredStaffId.isNotBlank() -> exactMatches.singleOrNull()
                else -> metadata.detectedStaff.firstOrNull()
            }
        )
    }
    var confirmedMonthStr by remember { mutableStateOf(metadata.monthInfo.monthStr) }
    var showMonthPicker by remember { mutableStateOf(false) }

    // Ambiguous / Confusable ID confirmation state
    var pendingStaffForConfirmation by remember { mutableStateOf<DetectedStaffMember?>(null) }
    var showConfusableDialog by remember { mutableStateOf(false) }
    var showManualRowDialog by remember { mutableStateOf(false) }
    var manualRowY by remember { mutableStateOf(100f) }

    // Raw Recognized Text inspection state
    var isRawTextExpanded by remember { mutableStateOf(false) }
    var textSearchQuery by remember { mutableStateOf("") }
    var showCopiedToast by remember { mutableStateOf(false) }

    // Cell detail inspector / edit state
    var inspectingCell by remember { mutableStateOf<Pair<ReconstructedCell, List<OcrWord>>?>(null) }
    var showFullDocumentDialog by remember { mutableStateOf(false) }

    // Live search filter for employees (separate from enteredStaffId so unmatched IDs don't hide all rows!)
    val filteredStaff = remember(filterQuery, metadata.detectedStaff) {
        if (filterQuery.isBlank()) {
            metadata.detectedStaff
        } else {
            val q = filterQuery.trim().lowercase(Locale.US)
            metadata.detectedStaff.filter {
                it.rawId.lowercase(Locale.US).contains(q) ||
                it.name.lowercase(Locale.US).contains(q) ||
                (it.floor?.lowercase(Locale.US)?.contains(q) == true) ||
                (it.serialNumber?.contains(q) == true)
            }
        }
    }

    // Determine effective candidates (from coordinator or computed locally)
    val effectiveCandidates = remember(enteredStaffId, metadata.detectedStaff, ambiguousCandidates) {
        if (ambiguousCandidates.isNotEmpty()) {
            ambiguousCandidates
        } else if (enteredStaffId.isNotBlank() && exactMatches.size != 1) {
            if (exactMatches.size > 1) {
                exactMatches
            } else {
                metadata.detectedStaff.filter {
                    val (isConf, _) = IdMatcher.areIdsConfusable(enteredStaffId, it.rawId)
                    isConf || it.rawId.contains(enteredStaffId, ignoreCase = true) || enteredStaffId.contains(it.rawId, ignoreCase = true)
                }
            }
        } else emptyList()
    }

    val confirmSelection: (String, String, Float, Float, String?, String?) -> Unit = { staffId, month, rowY, rowHeight, staffName, matchedRowRawId ->
        val finalStaffId = if (enteredStaffId.isNotBlank()) enteredStaffId else staffId
        if (onConfirmStaffRow != null) {
            onConfirmStaffRow(finalStaffId, month, rowY, rowHeight, staffName, matchedRowRawId)
        } else {
            onConfirmStaffAndMonth(finalStaffId, month, rowY, rowHeight, staffName)
        }
    }

    // Compute reconstructed shift cells for currently selected employee
    val reconstructionResult = remember(selectedStaffMember, metadata, confirmedMonthStr) {
        val staff = selectedStaffMember
        if (staff != null) {
            val reconstructor = LocalTableReconstructor(words = emptyList())
            reconstructor.reconstructForStaff(metadata, staff.rawId)
        } else null
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localRepo = remember(context) { com.example.medicalstaffscheduletracker.data.local.LocalScheduleRepository(context) }

    val inferenceEngine = remember { LocalSectionInferenceEngine() }
    var manualOverridesByDay by remember(selectedStaffMember) { mutableStateOf<Map<Int, String?>>(emptyMap()) }
    var acceptedInferredDays by remember(selectedStaffMember) { mutableStateOf<Set<Int>>(emptySet()) }

    val draftScheduleResult = remember(reconstructionResult, manualOverridesByDay, acceptedInferredDays, enteredStaffId, confirmedMonthStr) {
        if (reconstructionResult != null) {
            var draft = inferenceEngine.inferSections(reconstructionResult)
            val effectiveStaffId = enteredStaffId.ifBlank { draft.staffId }
            if (draft.staffId != effectiveStaffId || draft.month != confirmedMonthStr) {
                draft = draft.copy(staffId = effectiveStaffId, month = confirmedMonthStr)
            }
            for (day in acceptedInferredDays) {
                draft = inferenceEngine.acceptInferredCell(draft, day)
            }
            for ((day, shift) in manualOverridesByDay) {
                draft = inferenceEngine.updateCellManual(draft, day, shift)
            }
            draft
        } else null
    }

    val applyPreviewCellEdit: (Int, String?) -> Unit = { dayNum, newShift ->
        manualOverridesByDay = manualOverridesByDay + (dayNum to newShift)
        val currentBase = draftScheduleResult
        if (currentBase != null) {
            val updated = inferenceEngine.updateCellManual(currentBase, dayNum, newShift)
            scope.launch {
                localRepo.saveDraftSchedule(updated, isApproved = false)
            }
        }
    }

    val applyPreviewCellAccept: (Int) -> Unit = { dayNum ->
        acceptedInferredDays = acceptedInferredDays + dayNum
        val currentBase = draftScheduleResult
        if (currentBase != null) {
            val updated = inferenceEngine.acceptInferredCell(currentBase, dayNum)
            scope.launch {
                localRepo.saveDraftSchedule(updated, isApproved = false)
            }
        }
    }

    var showInferredSchedule by remember { mutableStateOf(true) }

    // Find the ReconstructedRow for selected staff to inspect raw word bounding boxes
    val activeRow = remember(selectedStaffMember, metadata.rows) {
        metadata.rows.firstOrNull { it.rawId == selectedStaffMember?.rawId }
    }

    // Crop horizontal row strip from lastDecodedBitmap for visual verification
    val rowCropBitmap = remember(selectedStaffMember, metadata.report?.lastDecodedBitmap) {
        val src = metadata.report?.lastDecodedBitmap
        val staff = selectedStaffMember
        if (src != null && staff != null) {
            val rowY = staff.rowY.toInt()
            val cropHeight = 70.coerceAtMost(src.height)
            val startY = (rowY - cropHeight / 2).coerceIn(0, (src.height - cropHeight).coerceAtLeast(0))
            try {
                Bitmap.createBitmap(src, 0, startY, src.width, cropHeight)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    val report = metadata.report

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Roster Extraction Review",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Evaluate recognized text, geometry, and extracted monthly schedule",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Cancel", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Source File, Dimensions, Provider & Engine Details Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📄 ${report?.fileDetails?.fileName ?: "Roster Document"}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = metadata.providerName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Dimensions: ${report?.decodingDetails?.decodedWidth ?: "-"} × ${report?.decodingDetails?.decodedHeight ?: "-"} px",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Size: ${(report?.fileDetails?.encodedSizeBytes ?: 0L) / 1024} KB • MIME: ${report?.fileDetails?.mimeType ?: "image"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Engine: ${report?.ocrDetails?.engineName ?: "Engine 2"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Words: ${report?.ocrDetails?.extractedWordCount ?: metadata.rows.sumOf { it.shiftWords.size }} • ${report?.ocrDetails?.ocrDurationMs ?: 0}ms",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Expandable Recognized Text Inspector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Complete Recognized Text",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${metadata.rawText.length} characters • Tap to expand & inspect",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(metadata.rawText))
                                showCopiedToast = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (showCopiedToast) "✓ Copied!" else "Copy", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = { isRawTextExpanded = !isRawTextExpanded },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (isRawTextExpanded) "▲ Collapse" else "▼ Expand", fontSize = 11.sp)
                        }
                    }
                }

                if (isRawTextExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textSearchQuery,
                        onValueChange = { textSearchQuery = it },
                        placeholder = { Text("Filter words in recognized text...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val displayText = remember(metadata.rawText, textSearchQuery) {
                        if (textSearchQuery.isBlank()) metadata.rawText
                        else {
                            metadata.rawText.lines().filter { it.contains(textSearchQuery, ignoreCase = true) }.joinToString("\n")
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (displayText.isNotBlank()) displayText else "No matching lines found.",
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color(0xFF1E293B)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Roster Month & Review Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (metadata.monthInfo.isConfident) MaterialTheme.colorScheme.surfaceVariant
                else Color(0xFFFEF3C7)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Roster Month: $confirmedMonthStr",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Surface(
                            color = if (metadata.monthInfo.isConfident) Color(0xFF10B981) else Color(0xFFF59E0B),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (metadata.monthInfo.isConfident) "Confident" else "Needs Review",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "Source: ${metadata.monthInfo.detectedFrom}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = { showMonthPicker = true }) {
                    Text("Change", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Detected Employees Section & Target Employee ID Match Card
        if (enteredStaffId.isNotBlank()) {
            val isExactMatch = exactMatches.size == 1 || matchStatus == StaffMatchStatus.EXACT_MATCH
            val hasAmbiguous = effectiveCandidates.isNotEmpty() || matchStatus == StaffMatchStatus.AMBIGUOUS_CANDIDATES

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isExactMatch -> Color(0xFFF0FDF4)
                        hasAmbiguous -> Color(0xFFEFF6FF)
                        else -> Color(0xFFFEF3C7)
                    }
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        isExactMatch -> Color(0xFF86EFAC)
                        hasAmbiguous -> Color(0xFF93C5FD)
                        else -> Color(0xFFF59E0B)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Entered Employee ID: $enteredStaffId",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF0F172A)
                        )
                        Surface(
                            color = when {
                                isExactMatch -> Color(0xFF16A34A)
                                hasAmbiguous -> Color(0xFF2563EB)
                                else -> Color(0xFFD97706)
                            },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = when {
                                    isExactMatch -> "✓ EXACT MATCH"
                                    hasAmbiguous -> "AMBIGUOUS CANDIDATES"
                                    else -> "SELECT ROW MANUALLY"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    when {
                        isExactMatch -> {
                            val match = exactMatches.firstOrNull() ?: selectedStaffMember
                            Text(
                                text = "Exact match located on roster: ${match?.name ?: enteredStaffId} (Row #${match?.serialNumber ?: "-"} at Y=${match?.rowY?.toInt() ?: 0}px).",
                                fontSize = 11.sp,
                                color = Color(0xFF166534)
                            )
                        }
                        hasAmbiguous -> {
                            Text(
                                text = "Exact ID '$enteredStaffId' had ambiguous or OCR-confusable candidates. Select your row below (your entered ID '$enteredStaffId' will be kept):",
                                fontSize = 11.sp,
                                color = Color(0xFF1E3A8A)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            effectiveCandidates.forEach { cand ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "• ${cand.rawId} — ${cand.name} (Row #${cand.serialNumber ?: "-"})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1E40AF),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { selectedStaffMember = cand },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (selectedStaffMember?.rawId == cand.rawId) "Selected ✓" else "Select Row", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "ID '$enteredStaffId' was not automatically detected by initial OCR. Your ID '$enteredStaffId' is preserved — tap your employee row from the list below or tap '📍 Pick Row' to select your row manually.",
                                fontSize = 11.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Detected Employee Rows (${metadata.detectedStaff.size})",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = "Select your employee row below or use '📍 Pick Row' to align by image coordinates:",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // Filter Detected Rows Field + Manual Row Picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                label = { Text("Filter Detected Rows (by name, ID, or #)", fontSize = 11.sp) },
                placeholder = { Text("Type to filter detected rows...") },
                trailingIcon = {
                    if (filterQuery.isNotEmpty()) {
                        IconButton(onClick = { filterQuery = "" }) {
                            Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = { showManualRowDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text("📍 Pick Row", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Employee Cards (Horizontal or Compact List)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredStaff) { staff ->
                val isSelected = selectedStaffMember?.rawId == staff.rawId
                Card(
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 220.dp)
                        .clickable { selectedStaffMember = staff }
                        .then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            else Modifier
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "#${staff.serialNumber ?: "-"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (staff.weeklyOffDay != null) {
                                Surface(
                                    color = Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = "OFF: ${staff.weeklyOffDay}",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = staff.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "ID: ${staff.rawId} • ${staff.floor ?: "Floor 2"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. Visual Document Comparison Section (Selected Row Crop & Full Image)
        if (selectedStaffMember != null) {
            val staff = selectedStaffMember!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "📷 Physical Document Comparison",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Comparing row for ${staff.name} (${staff.rawId}) at Y=${staff.rowY.toInt()}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (report?.lastDecodedBitmap != null) {
                            TextButton(
                                onClick = { showFullDocumentDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("View Full Sheet", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (rowCropBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(95.dp)
                                .horizontalScroll(rememberScrollState())
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(4.dp))
                                .padding(2.dp)
                        ) {
                            Image(
                                bitmap = rowCropBitmap.asImageBitmap(),
                                contentDescription = "Scanned document row crop (scrollable across 30 days)",
                                contentScale = ContentScale.FillHeight,
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    } else {
                        Text(
                            text = "Image crop preview not available for this source.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. Extracted Monthly Calendar & Shift Alignment
            if (reconstructionResult != null) {
                // Inferred vs Raw entries
                val activeEntries: List<ReconstructedCell> = if (showInferredSchedule && draftScheduleResult != null) {
                    draftScheduleResult.entries.map { ec ->
                        ReconstructedCell(
                            dayNumber = ec.dayNumber,
                            date = ec.date,
                            weekday = ec.weekday,
                            rawValue = ec.rawValue,
                            normalizedShift = ec.normalizedShift,
                            status = ec.reviewStatus,
                            confidence = ec.confidence,
                            provenance = ec.sourceType,
                            reviewReason = ec.reviewReason,
                            anchorType = metadata.dayDiagnostics[ec.dayNumber]?.anchorType ?: "",
                            anchorConfidence = metadata.dayDiagnostics[ec.dayNumber]?.anchorConfidence ?: "",
                            ocrBoundingBox = ec.ocrBoundingBox,
                            matchDistance = ec.matchDistance,
                            ocrConfidence = ec.ocrConfidence,
                            classification = ec.classification
                        )
                    }
                } else {
                    reconstructionResult.entries
                }

                val mCount = activeEntries.count { it.normalizedShift == "Morning" }
                val eCount = activeEntries.count { it.normalizedShift == "Evening" }
                val nCount = activeEntries.count { it.normalizedShift == "Night" }
                val offCount = activeEntries.count { it.normalizedShift == "Off" }
                val inferredCount = if (draftScheduleResult != null) draftScheduleResult.entries.count { it.sourceType == "section_inference" || it.sourceType == "off_propagation" } else 0
                val unresolvedCount = activeEntries.count { it.status in listOf("unresolved", "missing", "unreadable", "ambiguous") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Extracted Shifts (${activeEntries.size} Days)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (showInferredSchedule) "10-day contiguous section inference applied" else "Raw direct OCR observations without inference",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (unresolvedCount > 0) {
                        Surface(
                            color = Color(0xFFFEE2E2),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "⚠ $unresolvedCount UNRESOLVED",
                                color = Color(0xFF991B1B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Toggle: Inferred Schedule vs Raw OCR Observations
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = showInferredSchedule,
                        onClick = { showInferredSchedule = true },
                        label = { Text("10-Day Section Inference", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !showInferredSchedule,
                        onClick = { showInferredSchedule = false },
                        label = { Text("Raw OCR Observations", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 10-Day Section Inference Summary Cards
                if (showInferredSchedule && draftScheduleResult != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        draftScheduleResult.sectionSummaries.forEach { sec ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (sec.proposedShift != null) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (sec.proposedShift != null) Color(0xFF86EFAC) else Color(0xFFFCA5A5)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Section ${sec.sectionIndex}: Days ${sec.startDay}–${sec.endDay} (${sec.totalDays} Days) • Method: ${sec.inferenceMethod ?: "direct"}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (sec.proposedShift != null) Color(0xFF166534) else Color(0xFF991B1B)
                                        )
                                        val statusText = if (sec.proposedShift != null) {
                                            "Proposed Shift: ${sec.proposedShift} • Direct Evidence: ${sec.directEvidenceCount} (${sec.directEvidenceTokens.distinct().joinToString(", ")})"
                                        } else {
                                            sec.reviewReason ?: "No reliable working shift observed in section; left unresolved"
                                        }
                                        Text(
                                            text = statusText,
                                            fontSize = 10.sp,
                                            color = if (sec.proposedShift != null) Color(0xFF15803D) else Color(0xFFB91C1C)
                                        )
                                        if (sec.contradictoryEvidence.isNotEmpty()) {
                                            Text(
                                                text = "Contradictory Shifts: ${sec.contradictoryEvidence.joinToString(", ")}",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFDC2626)
                                            )
                                        }
                                    }
                                    if (sec.proposedShift != null) {
                                        Surface(
                                            color = when (sec.proposedShift) {
                                                "Morning" -> Color(0xFF2563EB)
                                                "Evening" -> Color(0xFF7C3AED)
                                                "Night" -> Color(0xFF0F172A)
                                                else -> Color(0xFF64748B)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = sec.proposedShift.take(1),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else {
                                        Surface(
                                            color = Color(0xFFEF4444),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "UNRESOLVED",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Shift Count Summary Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SummaryChip(label = "M", count = mCount, color = Color(0xFF2563EB))
                    SummaryChip(label = "E", count = eCount, color = Color(0xFF7C3AED))
                    SummaryChip(label = "N", count = nCount, color = Color(0xFF0F172A))
                    SummaryChip(label = "OFF", count = offCount, color = Color(0xFF64748B))
                    if (showInferredSchedule && inferredCount > 0) {
                        SummaryChip(label = "Inferred", count = inferredCount, color = Color(0xFF0284C7))
                    }
                    if (unresolvedCount > 0) {
                        SummaryChip(label = "Unresolved", count = unresolvedCount, color = Color(0xFFEF4444))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Calendar Days List
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    activeEntries.forEach { cell ->
                        val (leftBound, rightBound) = metadata.dayIntervals[cell.dayNumber] ?: (0f to 0f)
                        val cellWords = activeRow?.shiftWords?.filter { w ->
                            val mid = w.x + w.width / 2f
                            mid in leftBound..rightBound
                        } ?: emptyList()
                        val diag = metadata.dayDiagnostics[cell.dayNumber]

                        ExtractionDayCell(
                            cell = cell,
                            cellWords = cellWords,
                            diag = diag,
                            onClick = { inspectingCell = cell to cellWords },
                            onAccept = if (cell.status in listOf("inferred_needs_confirmation", "needs_review") &&
                                (cell.normalizedShift != null || cell.rawValue?.trim()?.uppercase(Locale.US) == "A")
                            ) {
                                { applyPreviewCellAccept(cell.dayNumber) }
                            } else null,
                            onEdit = { inspectingCell = cell to cellWords }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            val canProceed = selectedStaffMember != null || enteredStaffId.isNotBlank() || filterQuery.isNotBlank()
            Button(
                onClick = {
                    val staff = selectedStaffMember
                    val requestedId = enteredStaffId.ifBlank { filterQuery.trim() }
                    if (staff != null) {
                        val (isConf, _) = if (requestedId.isNotBlank() && !requestedId.equals(staff.rawId, ignoreCase = true) && !requestedId.equals(staff.normalizedId, ignoreCase = true)) {
                            IdMatcher.areIdsConfusable(requestedId, staff.rawId)
                        } else false to null

                        if (isConf) {
                            pendingStaffForConfirmation = staff
                            showConfusableDialog = true
                        } else {
                            val rHeight = staff.rowHeight ?: 13f
                            val effectiveId = requestedId.ifBlank { staff.rawId }
                            confirmSelection(effectiveId, confirmedMonthStr, staff.rowY, rHeight, staff.name, staff.rawId)
                        }
                    } else if (requestedId.isNotBlank()) {
                        showManualRowDialog = true
                    }
                },
                enabled = canProceed,
                modifier = Modifier.weight(2f)
            ) {
                Text(
                    text = when {
                        selectedStaffMember != null && enteredStaffId.isNotBlank() && !selectedStaffMember!!.rawId.equals(enteredStaffId, ignoreCase = true) ->
                            "Proceed ($enteredStaffId on ${selectedStaffMember!!.name}'s Row)"
                        selectedStaffMember != null ->
                            "Proceed with ${selectedStaffMember!!.name} (${enteredStaffId.ifBlank { selectedStaffMember!!.rawId }})"
                        enteredStaffId.isNotBlank() ->
                            "Pick Row for ID '$enteredStaffId'"
                        filterQuery.isNotBlank() ->
                            "Proceed with ID '${filterQuery.trim()}'"
                        else ->
                            "Select Employee Row"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Month / Year Confirmation Dialog
    if (showMonthPicker) {
        var tempMonth by remember { mutableStateOf(confirmedMonthStr) }
        AlertDialog(
            onDismissRequest = { showMonthPicker = false },
            title = { Text("Confirm Roster Month") },
            text = {
                Column {
                    Text("Enter the correct month and year for this duty roster (YYYY-MM):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempMonth,
                        onValueChange = { tempMonth = it },
                        label = { Text("Month (YYYY-MM)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (tempMonth.matches(Regex("^\\d{4}-\\d{2}$"))) {
                        confirmedMonthStr = tempMonth
                        showMonthPicker = false
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMonthPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Cell Inspection, Action & Edit Dialog
    if (inspectingCell != null) {
        val (cell, cellWords) = inspectingCell!!
        val isRuleInferred = cell.provenance in listOf(
            "section_inference",
            "inferred_from_section_anchor",
            "inferred_from_weekly_off",
            "inferred_from_verified_rotation",
            "off_propagation"
        )
        AlertDialog(
            onDismissRequest = { inspectingCell = null },
            title = {
                Text("Day ${cell.dayNumber} (${cell.weekday}) - ${cell.date}", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Current Shift: ${cell.normalizedShift ?: if (cell.classification == "A") "A" else "Unresolved"}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Detection / Source: ${cell.provenance ?: "unresolved"}", fontSize = 12.sp)
                    Text("Original OCR Token: ${cell.rawValue ?: "None"}", fontSize = 12.sp)
                    Text("Review Status: ${cell.status}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (!cell.reviewReason.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Rule / Reason: ${cell.reviewReason}", color = Color(0xFFB91C1C), fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if ((isRuleInferred || cell.status in listOf("inferred_needs_confirmation", "needs_review")) &&
                        (cell.normalizedShift != null || cell.rawValue?.trim()?.uppercase(Locale.US) == "A")
                    ) {
                        Button(
                            onClick = {
                                applyPreviewCellAccept(cell.dayNumber)
                                inspectingCell = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        ) {
                            Text("✓ Accept Shift (${cell.normalizedShift ?: "A"})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text("Select or Override Shift (M, E, N, OFF, A):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Morning" to "M", "Evening" to "E", "Night" to "N", "Off" to "OFF", "A" to "A").forEach { (sh, lbl) ->
                            Button(
                                onClick = {
                                    applyPreviewCellEdit(cell.dayNumber, sh)
                                    inspectingCell = null
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                            ) {
                                Text(lbl, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            applyPreviewCellEdit(cell.dayNumber, null)
                            inspectingCell = null
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                    ) {
                        Text("Leave Unresolved", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Raw OCR Word Tokens & Source Coordinates:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    if (cellWords.isNotEmpty()) {
                        cellWords.forEach { w ->
                            Text(
                                text = "• \"${w.text}\" [Left: ${w.x.toInt()}, Top: ${w.y.toInt()}, W: ${w.width.toInt()}, H: ${w.height.toInt()}]",
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = Color(0xFF334155)
                            )
                        }
                    } else {
                        Text(
                            text = "No direct word tokens recognized in this date column cell.",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectingCell = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Full Document Image View Dialog
    if (showFullDocumentDialog && report?.lastDecodedBitmap != null) {
        AlertDialog(
            onDismissRequest = { showFullDocumentDialog = false },
            title = { Text("Original Roster Document") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .background(Color.Black, RoundedCornerShape(6.dp))
                ) {
                    Image(
                        bitmap = report.lastDecodedBitmap.asImageBitmap(),
                        contentDescription = "Original roster document",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFullDocumentDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Confusable Staff ID Confirmation Dialog
    if (showConfusableDialog && pendingStaffForConfirmation != null) {
        val staff = pendingStaffForConfirmation!!
        val requestedId = enteredStaffId.ifBlank { filterQuery.trim() }
        AlertDialog(
            onDismissRequest = { showConfusableDialog = false },
            title = { Text("Confirm Confusable Staff ID") },
            text = {
                Column {
                    Text("Entered Staff ID (Preserved): '$requestedId'", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Selected Roster Row: '${staff.rawId}' (${staff.name})", fontSize = 12.sp, color = Color(0xFF1E40AF))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Potential visual OCR character confusion detected between your entered ID '$requestedId' and OCR row '${staff.rawId}' (e.g. 1 vs L, 0 vs O). Confirming will extract shifts from this row while preserving your entered ID '$requestedId'.",
                        fontSize = 11.sp,
                        color = Color(0xFF334155)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedStaffMember = staff
                        showConfusableDialog = false
                        val rHeight = staff.rowHeight ?: 13f
                        val effectiveId = requestedId.ifBlank { staff.rawId }
                        confirmSelection(effectiveId, confirmedMonthStr, staff.rowY, rHeight, staff.name, staff.rawId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Confirm & Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfusableDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Manual Row Selection Dialog (when staff ID cannot be found by OCR)
    if (showManualRowDialog) {
        val srcBmp = metadata.report?.lastDecodedBitmap
        val maxH = (srcBmp?.height?.toFloat() ?: 600f)
        val preservedId = enteredStaffId.ifBlank { filterQuery.trim() }
        AlertDialog(
            onDismissRequest = { showManualRowDialog = false },
            title = { Text("Select Employee Row Manually") },
            text = {
                Column {
                    if (preservedId.isNotBlank()) {
                        Text("Employee ID: $preservedId (will be preserved)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text("If the employee cannot be located by OCR, select their row directly from the image coordinates:", fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Selected Row Y: ${manualRowY.toInt()} px", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Slider(
                        value = manualRowY,
                        onValueChange = { manualRowY = it },
                        valueRange = 0f..maxH,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (srcBmp != null && !srcBmp.isRecycled) {
                        val cropY = manualRowY.toInt().coerceIn(0, (srcBmp.height - 30).coerceAtLeast(0))
                        val cropBmp = try {
                            Bitmap.createBitmap(srcBmp, 0, cropY, srcBmp.width, 30.coerceAtMost(srcBmp.height - cropY))
                        } catch (_: Exception) { null }

                        if (cropBmp != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(45.dp)
                                    .horizontalScroll(rememberScrollState())
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(4.dp))
                            ) {
                                Image(
                                    bitmap = cropBmp.asImageBitmap(),
                                    contentDescription = "Manual row crop preview",
                                    contentScale = ContentScale.FillHeight,
                                    modifier = Modifier.fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showManualRowDialog = false
                    val manualId = preservedId.ifBlank { "MANUAL_ROW_${manualRowY.toInt()}" }
                    val rHeight = metadata.detectedStaff.firstOrNull { it.rowHeight != null }?.rowHeight ?: 13f
                    confirmSelection(manualId, confirmedMonthStr, manualRowY, rHeight, "Staff ($manualId)", null)
                }) {
                    Text("Select Row")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualRowDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SummaryChip(label: String, count: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = color)
            Text("$count", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = color)
        }
    }
}

@Composable
fun ExtractionDayCell(
    cell: ReconstructedCell,
    cellWords: List<OcrWord>,
    diag: ColumnDiagnostic? = null,
    onClick: () -> Unit,
    onAccept: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    val isUnresolved = cell.status in listOf("unresolved", "missing", "unreadable", "ambiguous") ||
        (cell.normalizedShift == null && cell.rawValue != "A" && cell.classification != "A")
    val isNonTargetA = cell.normalizedShift == "A" || cell.classification == "A" || cell.rawValue?.trim()?.uppercase(Locale.US) == "A"
    val shiftColor = when (cell.normalizedShift) {
        "Morning" -> Color(0xFF2563EB)
        "Evening" -> Color(0xFF7C3AED)
        "Night" -> Color(0xFF0F172A)
        "Off" -> Color(0xFF64748B)
        "A" -> Color(0xFFD97706)
        else -> when {
            isNonTargetA -> Color(0xFFD97706)
            isUnresolved -> Color(0xFFDC2626)
            else -> Color(0xFF94A3B8)
        }
    }

    val provenanceLabel = when (cell.provenance) {
        "manual", "manual_override" -> "Manual ✓"
        "directly_observed", "ocr_observed" -> "Direct Observed"
        "inferred_from_section_anchor" -> "Inferred (Section Anchor)"
        "inferred_from_verified_rotation" -> "Inferred (3-Sec Rotation)"
        "inferred_from_weekly_off", "off_propagation" -> "Inferred (Weekly OFF)"
        "conflicting" -> "Conflict ⚠"
        "unresolved" -> "Unresolved"
        else -> when {
            cell.provenance?.contains("rotation", ignoreCase = true) == true -> "Inferred (3-Sec Rotation)"
            cell.provenance?.contains("section", ignoreCase = true) == true -> "Inferred (Section Anchor)"
            cell.provenance?.contains("weekly", ignoreCase = true) == true || cell.provenance?.contains("propagated", ignoreCase = true) == true -> "Inferred (Weekly OFF)"
            isNonTargetA -> "Observed 'A'"
            cellWords.isNotEmpty() || !cell.rawValue.isNullOrBlank() -> "Direct Observed"
            else -> "Missing"
        }
    }

    val provenanceColor = when (provenanceLabel) {
        "Manual ✓" -> Color(0xFF92400E)
        "Direct Observed" -> Color(0xFF16A34A)
        "Inferred (Section Anchor)" -> Color(0xFF0284C7)
        "Inferred (3-Sec Rotation)" -> Color(0xFF7C3AED)
        "Inferred (Weekly OFF)" -> Color(0xFF0D9488)
        "Observed 'A'" -> Color(0xFFD97706)
        "Conflict ⚠" -> Color(0xFFDC2626)
        else -> Color(0xFF94A3B8)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnresolved) Color(0xFFFEF2F2) else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isUnresolved) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.width(36.dp)
                    ) {
                        Text(
                            text = "${cell.dayNumber}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = cell.weekday.take(3),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                            Surface(
                                color = provenanceColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(3.dp)
                            ) {
                                Text(
                                    text = provenanceLabel,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = provenanceColor,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            if (cell.status in listOf("needs_review", "inferred_needs_confirmation")) {
                                Surface(
                                    color = Color(0xFFFEF3C7),
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = "Requires Review",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB45309),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            } else if (cell.status == "confirmed") {
                                Surface(
                                    color = Color(0xFFD1FAE5),
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        text = "✓ Confirmed",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF065F46),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        val rawInfo = if (cellWords.isNotEmpty()) {
                            val w = cellWords.first()
                            val dist = if (diag != null) Math.abs(w.cx - diag.centerX) else 0f
                            "raw: \"${w.text}\" [${w.x.toInt()}, ${w.y.toInt()}, ${w.width.toInt()}×${w.height.toInt()}] Δx=${String.format(Locale.US, "%.1f", dist)}px"
                        } else if (!cell.ocrBoundingBox.isNullOrBlank()) {
                            "raw: \"${cell.rawValue ?: ""}\" ${cell.ocrBoundingBox} Δx=${String.format(Locale.US, "%.1f", cell.matchDistance ?: 0f)}px"
                        } else if (!cell.rawValue.isNullOrBlank()) {
                            "raw: \"${cell.rawValue}\""
                        } else {
                            val expX = diag?.let { "exp cx=${String.format(Locale.US, "%.1f", it.centerX)}" } ?: ""
                            "no token in cell $expX"
                        }

                        Text(
                            text = rawInfo,
                            fontSize = 9.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isUnresolved) {
                        Surface(
                            color = Color(0xFFEF4444),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "UNRESOLVED ⚠",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = shiftColor,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = when (cell.normalizedShift) {
                                    "Morning" -> "M"
                                    "Evening" -> "E"
                                    "Night" -> "N"
                                    "Off" -> "OFF"
                                    "A" -> "A"
                                    else -> if (isNonTargetA) "A" else cell.rawValue ?: "?"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (onAccept != null) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 34.dp)
                        ) {
                            Text("✓ Accept", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { (onEdit ?: onClick)() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 34.dp)
                    ) {
                        Text("✎ Edit", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (!cell.reviewReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "• ${cell.reviewReason}",
                    fontSize = 9.sp,
                    color = if (isUnresolved) Color(0xFFB91C1C) else Color(0xFF475569),
                    lineHeight = 11.sp
                )
            }
        }
    }
}

@Composable
fun StaffSelectionScreen(
    metadata: TableMetadata,
    preselectedStaffId: String? = null,
    matchStatus: StaffMatchStatus = StaffMatchStatus.NONE,
    ambiguousCandidates: List<DetectedStaffMember> = emptyList(),
    onConfirmStaffAndMonth: (selectedStaffId: String, confirmedMonth: String, rowY: Float, rowHeight: Float, staffName: String?) -> Unit,
    onConfirmStaffRow: ((selectedStaffId: String, confirmedMonth: String, rowY: Float, rowHeight: Float, staffName: String?, matchedRowRawId: String?) -> Unit)? = null,
    onCancel: () -> Unit
) {
    RosterExtractionReviewScreen(
        metadata = metadata,
        preselectedStaffId = preselectedStaffId,
        matchStatus = matchStatus,
        ambiguousCandidates = ambiguousCandidates,
        onConfirmStaffAndMonth = onConfirmStaffAndMonth,
        onConfirmStaffRow = onConfirmStaffRow,
        onCancel = onCancel
    )
}

@Composable
fun StaffSelectionScreen(
    metadata: TableMetadata,
    onConfirmStaffAndMonth: (selectedStaffId: String, confirmedMonth: String, rowY: Float, rowHeight: Float, staffName: String?) -> Unit,
    onCancel: () -> Unit
) {
    StaffSelectionScreen(
        metadata = metadata,
        preselectedStaffId = null,
        onConfirmStaffAndMonth = onConfirmStaffAndMonth,
        onCancel = onCancel
    )
}

@Composable
fun StaffSelectionScreen(
    metadata: TableMetadata,
    onConfirmStaffAndMonth: (selectedStaffId: String, confirmedMonth: String) -> Unit,
    onCancel: () -> Unit
) {
    StaffSelectionScreen(
        metadata = metadata,
        preselectedStaffId = null,
        onConfirmStaffAndMonth = { id, m, _, _, _ -> onConfirmStaffAndMonth(id, m) },
        onCancel = onCancel
    )
}

// =========================================================================
// 4. SHIFT LEGEND BAR COMPONENT
// =========================================================================

@Composable
fun ShiftLegendBar() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(code = "M", label = "Morning", color = Color(0xFF2563EB))
        LegendItem(code = "E", label = "Evening", color = Color(0xFF7C3AED))
        LegendItem(code = "N", label = "Night", color = Color(0xFF0F172A))
        LegendItem(code = "OFF", label = "Off", color = Color(0xFF64748B))
        LegendItem(code = "!", label = "Review", color = Color(0xFFEF4444))
    }
}

@Composable
fun LegendItem(code: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "$code ($label)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// =========================================================================
// 5. CALENDAR DAY ROW COMPONENT
// =========================================================================

@Composable
fun CalendarDayRow(
    entry: ExtractedCell,
    manualDuty: ManualDuty?,
    onClick: () -> Unit
) {
    val effectiveShift = manualDuty?.shift
        ?: entry.normalizedShift
        ?: if (entry.classification == "A" && entry.isConfirmed) "A" else null
    val isEmergency = manualDuty != null

    val isUnresolved = entry.isUnresolved && !isEmergency
    val shiftColor = when (effectiveShift) {
        "Morning", "M" -> Color(0xFF2563EB)
        "Evening", "E" -> Color(0xFF7C3AED)
        "Night", "N" -> Color(0xFF0F172A)
        "Off", "OFF" -> Color(0xFF64748B)
        "A" -> Color(0xFFD97706)
        else -> Color(0xFFEF4444)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isUnresolved) Modifier.border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isEmergency) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Day ${entry.dayNumber}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.width(55.dp)
                )
                Text(
                    text = entry.weekday.take(3),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp)
                )
                if (isEmergency) {
                    Surface(
                        color = Color(0xFFDC2626),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = "EMERGENCY",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "Raw: ${entry.rawValue ?: "-"}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (srcLabel, srcBg, srcText) = when {
                    isEmergency || entry.isManual -> Triple("Manual", Color(0xFFFEF3C7), Color(0xFF92400E))
                    entry.isObserved -> Triple("Detected", Color(0xFFD1FAE5), Color(0xFF065F46))
                    entry.isUserConfirmedInferred -> Triple("Inferred ✓", Color(0xFFDBEAFE), Color(0xFF1E40AF))
                    entry.sourceType in listOf("inferred_from_weekly_off", "off_propagation") -> Triple("Weekly OFF", Color(0xFFEDE9FE), Color(0xFF5B21B6))
                    entry.sourceType in listOf("inferred_from_section_anchor", "section_inference") -> Triple("Anchor", Color(0xFFDBEAFE), Color(0xFF1E40AF))
                    entry.sourceType == "inferred_from_verified_rotation" -> Triple("Rotation", Color(0xFFCCFBF1), Color(0xFF115E59))
                    entry.sourceType == "conflicting" -> Triple("Conflict", Color(0xFFFEE2E2), Color(0xFF991B1B))
                    else -> Triple("Unresolved", Color(0xFFF1F5F9), Color(0xFF475569))
                }
                Surface(color = srcBg, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = srcLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = srcText,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    color = shiftColor,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.width(68.dp)
                ) {
                    Text(
                        text = effectiveShift ?: "Unresolved",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// 6. DATE DETAIL & AUDIT DIALOG
// =========================================================================

@Composable
fun DateDetailDialog(
    cell: ExtractedCell,
    onDismiss: () -> Unit,
    onEditDuty: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day ${cell.dayNumber} (${cell.weekday})", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Date: ${cell.date}", fontSize = 13.sp)
                Text("Assigned Duty: ${cell.normalizedShift ?: "None / Unresolved"}", fontWeight = FontWeight.Bold)
                Text("Observed Token: ${cell.rawValue ?: "None (blank/missing)"}", fontSize = 12.sp)
                Text("Source Type: ${cell.sourceType}", fontSize = 12.sp)
                Text("Review Status: ${cell.reviewStatus}", fontSize = 12.sp)
                if (cell.confidence > 0) {
                    Text("OCR Confidence: ${(cell.confidence * 100).toInt()}%", fontSize = 12.sp)
                }
                if (!cell.reviewReason.isNullOrBlank()) {
                    Text(
                        text = "Explanation: ${cell.reviewReason}",
                        color = Color(0xFFB91C1C),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onEditDuty()
            }) {
                Text("Manual Edit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// =========================================================================
// 7. MANUAL EMERGENCY DUTY DIALOG
// =========================================================================

@Composable
fun ManualEmergencyDutyDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onSave: (ManualDuty) -> Unit
) {
    var dateStr by remember { mutableStateOf(initialDate) }
    var selectedShift by remember { mutableStateOf("Morning") }
    var dept by remember { mutableStateOf("Emergency ICU") }
    var floor by remember { mutableStateOf("Floor 2") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Emergency Duty Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = { dateStr = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Select Duty Shift:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("Morning", "Evening", "Night", "Off").forEach { shift ->
                        FilterChip(
                            selected = selectedShift == shift,
                            onClick = { selectedShift = shift },
                            label = { Text(shift.take(1), fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = dept,
                    onValueChange = { dept = it },
                    label = { Text("Department") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (e.g. emergency cover)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ManualDuty(
                            id = java.util.UUID.randomUUID().toString(),
                            date = dateStr,
                            shift = selectedShift,
                            department = dept,
                            floor = floor,
                            notes = notes.ifBlank { null }
                        )
                    )
                },
                enabled = dateStr.isNotBlank()
            ) {
                Text("Save Duty")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// =========================================================================
// 8. EMPLOYEE-FOCUSED ROW REFINEMENT SCREEN (PHASE B)
// =========================================================================

@Composable
fun RowRefinementScreen(
    metadata: TableMetadata,
    selectedStaffId: String,
    staffName: String?,
    initialRowY: Float,
    initialRowHeight: Float,
    onProceedWithCellLevelOcr: ((cropStartY: Int, cropHeight: Int, useCloudFallback: Boolean) -> Unit)? = null,
    onProceedWithOcrSpaceRefinement: (cropStartY: Int, cropHeight: Int) -> Unit,
    onProceedWithoutRefinement: () -> Unit,
    onBack: () -> Unit
) {
    var offsetY by remember { mutableStateOf(0) }
    var heightDelta by remember { mutableStateOf(0) }
    var showCloudConsentDialog by remember { mutableStateOf(false) }

    val srcBitmap = metadata.report?.lastDecodedBitmap
    val bmpWidth = srcBitmap?.width ?: 1131
    val bmpHeight = srcBitmap?.height ?: 653

    val cropStartY = remember(initialRowY, offsetY, bmpHeight) {
        (initialRowY.toInt() + offsetY).coerceIn(0, (bmpHeight - 8).coerceAtLeast(0))
    }
    val cropHeight = remember(initialRowHeight, heightDelta, bmpHeight, cropStartY) {
        (initialRowHeight.toInt() + heightDelta).coerceIn(8, bmpHeight - cropStartY)
    }

    val cropBitmap = remember(srcBitmap, cropStartY, cropHeight) {
        if (srcBitmap != null && !srcBitmap.isRecycled) {
            try {
                Bitmap.createBitmap(srcBitmap, 0, cropStartY, srcBitmap.width, cropHeight)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Employee-Focused Row Refinement",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Review and fine-tune the high-resolution row crop for ${staffName ?: "Employee"} ($selectedStaffId) before cloud OCR processing.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Crop Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📷 Complete Row Strip Preview",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Y: $cropStartY • Height: ${cropHeight}px • Full Width (${bmpWidth}px)",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (cropBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .horizontalScroll(rememberScrollState())
                            .background(Color.White, RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(4.dp))
                            .padding(2.dp)
                    ) {
                        Image(
                            bitmap = cropBitmap.asImageBitmap(),
                            contentDescription = "Employee row crop across all date columns",
                            contentScale = ContentScale.FillHeight,
                            modifier = Modifier.fillMaxHeight()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Source image bitmap not available for crop preview.", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Boundary Adjustment Nudge Controls
                Text("Manual Row Boundary Adjustment:", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { offsetY -= 2 },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("▲ Up (-2px)", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { offsetY += 2 },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("▼ Down (+2px)", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { heightDelta += 2 },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("+ Height", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { heightDelta = (heightDelta - 2).coerceAtLeast(-8) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("- Height", fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = { offsetY = 0; heightDelta = 0 },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Reset", fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Local-First On-Device Cell OCR Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🔒", fontSize = 18.sp)
                Column {
                    Text(
                        text = "Local On-Device Cell OCR (Google ML Kit - Primary)",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF166534),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Segments each date column into individual cells with +5px vertical safety margins to prevent character clipping. Executes locally on your device with 0 network latency. No image data is transmitted to the cloud.",
                        fontSize = 11.sp,
                        color = Color(0xFF15803D),
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Actions
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Primary Recommended: Google ML Kit Cell-Level OCR
            Button(
                onClick = {
                    if (onProceedWithCellLevelOcr != null) {
                        onProceedWithCellLevelOcr(cropStartY, cropHeight, false)
                    } else {
                        onProceedWithOcrSpaceRefinement(cropStartY, cropHeight)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
            ) {
                Text("⚡ Extract Shifts with Cell-Level OCR (ML Kit Local)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            // Secondary: Cloud Fallback
            OutlinedButton(
                onClick = { showCloudConsentDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("☁ Cloud Fallback: Cell OCR (OCR.space Engine 2)", fontSize = 12.sp)
            }

            // Legacy Fallback
            TextButton(
                onClick = onProceedWithoutRefinement,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip Refinement & Use Whole-Sheet OCR", fontSize = 11.sp)
            }

            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Back to Employee Selection", fontSize = 12.sp)
            }
        }
    }

    // Cloud Disclosure & Consent Dialog
    if (showCloudConsentDialog) {
        AlertDialog(
            onDismissRequest = { showCloudConsentDialog = false },
            title = { Text("Cloud OCR Processing Consent") },
            text = {
                Column {
                    Text(
                        text = "Transmit row crop to OCR.space cloud:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By proceeding, cropped date cells from row Y=$cropStartY will be transmitted to OCR.space cloud servers (Engine 2) via HTTPS for OCR recognition. The data is processed ephemerally and not stored permanently.",
                        fontSize = 11.sp,
                        color = Color(0xFF334155)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloudConsentDialog = false
                        if (onProceedWithCellLevelOcr != null) {
                            onProceedWithCellLevelOcr(cropStartY, cropHeight, true)
                        } else {
                            onProceedWithOcrSpaceRefinement(cropStartY, cropHeight)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Consent & Upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudConsentDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// =========================================================================
// 9. EDITABLE DRAFT REVIEW & APPROVAL SCREEN (PHASE C, D, E, F)
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableReviewScreen(
    draft: DraftSchedule,
    onUpdateCell: (dayNumber: Int, newShift: String?) -> Unit,
    onAcceptCell: (dayNumber: Int) -> Unit = {},
    onAcceptAllInferred: () -> Unit = {},
    onOverrideSection: (sectionIndex: Int, newShift: String?) -> Unit,
    onOverrideWeeklyOff: (weekday: String?) -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
    onResetToOcrDraft: () -> Unit,
    onSaveDraftLocally: () -> Unit,
    onApproveSchedule: () -> Unit,
    onBackToCalendar: () -> Unit
) {
    var selectedCellForEdit by remember { mutableStateOf<ExtractedCell?>(null) }
    var showWeeklyOffDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showApproveConfirmDialog by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf("ALL") } // "ALL", "NEEDS_ACTION", "INFERRED", "MANUAL"
    var acknowledgeExplicitUnresolved by remember(draft.explicitUnresolvedCellCount) { mutableStateOf(false) }

    val rotationValidation = remember(draft.sectionSummaries) {
        LocalSectionInferenceEngine().validateRotationConstraint(draft.sectionSummaries)
    }

    val canApproveNow = remember(draft, acknowledgeExplicitUnresolved) {
        LocalSectionInferenceEngine().canApproveSchedule(draft, acknowledgeExplicitUnresolved)
    }

    val filteredEntries = remember(draft.entries, activeFilter) {
        when (activeFilter) {
            "NEEDS_ACTION" -> draft.entries.filter { it.isUnresolved }
            "INFERRED" -> draft.entries.filter { it.isInferred }
            "MANUAL" -> draft.entries.filter { it.isManual }
            else -> draft.entries
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Status & Staff Banner
        Surface(
            color = if (draft.isApproved) Color(0xFF10B981) else Color(0xFFF59E0B),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (draft.isApproved) "✓ APPROVED SCHEDULE" else "✎ REVIEW MODE — Interactive Schedule Verification",
                        color = if (draft.isApproved) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${draft.staffName ?: "Employee"} (${draft.staffId}) • ${draft.month}",
                        color = if (draft.isApproved) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${draft.confirmedCellCount}/${draft.entries.size} Confirmed",
                        color = if (draft.isApproved) Color.White else Color.Black,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Confusable ID Warning if applicable
        if (draft.isConfusableId) {
            Surface(
                color = Color(0xFFFEF2F2),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5))
            ) {
                Text(
                    text = "⚠ Confusable ID Notice: ${draft.confusableMessage ?: "Review staff ID and shift mappings carefully."}",
                    color = Color(0xFF991B1B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Review Progress & Breakdown Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Review Progress: ${draft.confirmedCellCount} of ${draft.entries.size} Days Verified (${(draft.reviewProgressFraction * 100).toInt()}%)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    if (draft.pendingInferredCount > 0) {
                        Button(
                            onClick = onAcceptAllInferred,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 34.dp)
                        ) {
                            Text("✓ Accept All Inferred (${draft.pendingInferredCount})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = draft.reviewProgressFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),
                    color = if (draft.unresolvedOrNeedsReviewCount == 0) Color(0xFF10B981) else Color(0xFF2563EB),
                    trackColor = Color(0xFFE2E8F0)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4 Required Category Breakdown Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReviewBreakdownBadge(
                        title = "Detected",
                        count = draft.directlyDetectedCount,
                        subtitle = "Direct OCR",
                        color = Color(0xFF047857),
                        modifier = Modifier.weight(1f)
                    )
                    ReviewBreakdownBadge(
                        title = "Inferred",
                        count = draft.ruleInferredCount,
                        subtitle = "${draft.confirmedInferredCount}/${draft.ruleInferredCount} conf",
                        color = Color(0xFF0284C7),
                        modifier = Modifier.weight(1f)
                    )
                    ReviewBreakdownBadge(
                        title = "Corrected",
                        count = draft.manuallyCorrectedCount,
                        subtitle = "Manual edit",
                        color = Color(0xFFB45309),
                        modifier = Modifier.weight(1f)
                    )
                    ReviewBreakdownBadge(
                        title = "Unresolved",
                        count = draft.unresolvedOrNeedsReviewCount,
                        subtitle = if (draft.unreviewedActionCount > 0) "${draft.unreviewedActionCount} req action" else "Verified",
                        color = if (draft.unresolvedOrNeedsReviewCount > 0) Color(0xFFDC2626) else Color(0xFF64748B),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Section Controls & Rotation Validation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Section Shift Controls", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    TextButton(
                        onClick = { showWeeklyOffDialog = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text("Change Weekly Off ⚙", fontSize = 11.sp)
                    }
                }

                // 3 Section Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    draft.sectionSummaries.forEach { sec ->
                        val shift = sec.proposedShift
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = when (shift) {
                                    "Morning" -> Color(0xFFEFF6FF)
                                    "Evening" -> Color(0xFFF5F3FF)
                                    "Night" -> Color(0xFFF8FAFC)
                                    else -> Color(0xFFFEF2F2)
                                }
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                when (shift) {
                                    "Morning" -> Color(0xFF93C5FD)
                                    "Evening" -> Color(0xFFC4B5FD)
                                    "Night" -> Color(0xFFCBD5E1)
                                    else -> Color(0xFFFCA5A5)
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                Text(
                                    text = "Sec ${sec.sectionIndex} (${sec.startDay}-${sec.endDay})",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.DarkGray
                                )
                                Text(
                                    text = shift ?: "Unknown",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (shift) {
                                        "Morning" -> Color(0xFF1D4ED8)
                                        "Evening" -> Color(0xFF6D28D9)
                                        "Night" -> Color(0xFF0F172A)
                                        else -> Color(0xFFDC2626)
                                    }
                                )

                                Spacer(modifier = Modifier.height(3.dp))

                                // Quick Override Buttons (M, E, N, ?)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    listOf("Morning" to "M", "Evening" to "E", "Night" to "N").forEach { (sh, lbl) ->
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 28.dp)
                                                .clickable { onOverrideSection(sec.sectionIndex, sh) },
                                            color = if (shift == sh) Color(0xFF2563EB) else Color(0xFFE2E8F0),
                                            shape = RoundedCornerShape(3.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                                                Text(
                                                    text = lbl,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (shift == sh) Color.White else Color.Black,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 28.dp)
                                            .clickable { onOverrideSection(sec.sectionIndex, null) },
                                        color = if (shift == null) Color(0xFFDC2626) else Color(0xFFE2E8F0),
                                        shape = RoundedCornerShape(3.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = "?",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (shift == null) Color.White else Color.Black,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (rotationValidation.promptMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = (if (rotationValidation.isValid && rotationValidation.isComplete) "✓ " else "ℹ ") + rotationValidation.promptMessage,
                        fontSize = 10.sp,
                        color = if (rotationValidation.isValid && rotationValidation.isComplete) Color(0xFF166534) else Color(0xFF1E40AF),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Toolbar: Filters, Undo, Reset, Save Draft
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = activeFilter == "ALL",
                onClick = { activeFilter = "ALL" },
                label = { Text("All (${draft.entries.size})", fontSize = 11.sp) }
            )
            FilterChip(
                selected = activeFilter == "NEEDS_ACTION",
                onClick = { activeFilter = "NEEDS_ACTION" },
                label = { Text("Needs Action (${draft.unresolvedOrNeedsReviewCount})", fontSize = 11.sp) }
            )
            FilterChip(
                selected = activeFilter == "INFERRED",
                onClick = { activeFilter = "INFERRED" },
                label = { Text("Inferred (${draft.ruleInferredCount})", fontSize = 11.sp) }
            )
            FilterChip(
                selected = activeFilter == "MANUAL",
                onClick = { activeFilter = "MANUAL" },
                label = { Text("Corrected (${draft.manuallyCorrectedCount})", fontSize = 11.sp) }
            )
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Text("↶ Undo", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { showResetConfirmDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Text("Reset", fontSize = 11.sp)
            }
            Button(
                onClick = onSaveDraftLocally,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Text("💾 Save", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Interactive Daily Review Cards List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredEntries, key = { it.dayNumber }) { cell ->
                ReviewDayCard(
                    cell = cell,
                    onAccept = { onAcceptCell(cell.dayNumber) },
                    onEdit = { selectedCellForEdit = cell }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Approval Gate Status Banner / Explicit Unresolved Acknowledgment
        if (!draft.isApproved) {
            if (draft.unreviewedActionCount > 0) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠ Action Required: Please Accept, Edit, or resolve ${draft.unreviewedActionCount} remaining review item(s) before final approval.",
                        color = Color(0xFF92400E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            } else if (draft.explicitUnresolvedCellCount > 0) {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { acknowledgeExplicitUnresolved = !acknowledgeExplicitUnresolved }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = acknowledgeExplicitUnresolved,
                            onCheckedChange = { acknowledgeExplicitUnresolved = it }
                        )
                        Text(
                            text = "I acknowledge ${draft.explicitUnresolvedCellCount} cell(s) are left unresolved and confirm approving this schedule.",
                            color = Color(0xFF991B1B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Bottom Actions (Back & Confirm/Approve)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBackToCalendar,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
            ) {
                Text("Back", fontWeight = FontWeight.SemiBold)
            }

            if (!draft.isApproved) {
                Button(
                    onClick = { showApproveConfirmDialog = true },
                    enabled = canApproveNow,
                    modifier = Modifier
                        .weight(2f)
                        .heightIn(min = 46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        disabledContainerColor = Color(0xFFCBD5E1)
                    )
                ) {
                    Text(
                        text = if (canApproveNow) "Confirm & Approve Schedule" else "Resolve Review Items (${draft.unreviewedActionCount})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    // Interactive Shift Selection & Review Dialog
    if (selectedCellForEdit != null) {
        val latestCell = draft.entries.find { it.dayNumber == selectedCellForEdit!!.dayNumber } ?: selectedCellForEdit!!
        ReviewCellEditDialog(
            cell = latestCell,
            onDismiss = { selectedCellForEdit = null },
            onAcceptInferred = {
                onAcceptCell(latestCell.dayNumber)
                selectedCellForEdit = null
            },
            onSaveShift = { chosenShift ->
                onUpdateCell(latestCell.dayNumber, chosenShift)
                selectedCellForEdit = null
            },
            onLeaveUnresolved = {
                onUpdateCell(latestCell.dayNumber, null)
                selectedCellForEdit = null
            }
        )
    }

    // Weekly Off Dialog
    if (showWeeklyOffDialog) {
        var selectedWk by remember { mutableStateOf<String?>("Thursday") }
        val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        AlertDialog(
            onDismissRequest = { showWeeklyOffDialog = false },
            title = { Text("Configure Weekly Off Weekday") },
            text = {
                Column {
                    Text("Select recurring weekly Off weekday. Directly observed working shifts will NOT be overwritten:", fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    weekdays.forEach { w ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedWk = w }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedWk == w, onClick = { selectedWk = w })
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(w, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onOverrideWeeklyOff(selectedWk)
                    showWeeklyOffDialog = false
                }) {
                    Text("Apply Weekly Off")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWeeklyOffDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reset Confirm Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset to Initial OCR Draft?") },
            text = {
                Text("All manual cell corrections and section overrides will be reverted back to the initial OCR observations and inference.", fontSize = 12.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetToOcrDraft()
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Approve Confirm Dialog
    if (showApproveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showApproveConfirmDialog = false },
            title = { Text("Confirm & Approve Schedule?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "This will finalize the reviewed duty schedule and export the official .xlsx workbook to Documents/MedicalStaffScheduleTracker.",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Directly Detected: ${draft.directlyDetectedCount} days", fontSize = 11.sp)
                    Text("• Rule-Inferred (Confirmed): ${draft.confirmedInferredCount} days", fontSize = 11.sp)
                    Text("• Manually Corrected: ${draft.manuallyCorrectedCount} days", fontSize = 11.sp)
                    if (draft.explicitUnresolvedCellCount > 0) {
                        Text(
                            "• Explicitly Unresolved: ${draft.explicitUnresolvedCellCount} days (acknowledged)",
                            fontSize = 11.sp,
                            color = Color(0xFFB91C1C),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApproveConfirmDialog = false
                        onApproveSchedule()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Confirm & Export XLSX", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReviewBreakdownBadge(
    title: String,
    count: Int,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "$count", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = color)
            Text(text = subtitle, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ReviewDayCard(
    cell: ExtractedCell,
    onAccept: () -> Unit,
    onEdit: () -> Unit
) {
    val effectiveShift = cell.normalizedShift
        ?: if (cell.classification == "A" && cell.isConfirmed) "A" else null

    val shiftCode = when (effectiveShift) {
        "Morning", "M" -> "M"
        "Evening", "E" -> "E"
        "Night", "N" -> "N"
        "Off", "OFF" -> "OFF"
        "A" -> "A"
        else -> if (cell.classification == "A" || cell.rawValue?.trim()?.uppercase(Locale.US) == "A") "A?" else "UNKNOWN"
    }

    val shiftColor = when (shiftCode) {
        "M" -> Color(0xFF2563EB)
        "E" -> Color(0xFF7C3AED)
        "N" -> Color(0xFF0F172A)
        "OFF" -> Color(0xFF475569)
        "A", "A?" -> Color(0xFFD97706)
        else -> Color(0xFFDC2626)
    }

    val (sourceLabel, sourceBg, sourceFg) = when {
        cell.isManual -> Triple("Manually Corrected", Color(0xFFFEF3C7), Color(0xFF92400E))
        cell.isObserved -> Triple("Directly Detected (OCR)", Color(0xFFD1FAE5), Color(0xFF065F46))
        cell.isInferred -> Triple("Rule-Inferred", Color(0xFFDBEAFE), Color(0xFF1E40AF))
        cell.sourceType == "conflicting" -> Triple("Conflicting Evidence", Color(0xFFFEE2E2), Color(0xFF991B1B))
        else -> Triple("Unresolved / Unknown", Color(0xFFF1F5F9), Color(0xFF475569))
    }

    val (statusLabel, statusBg, statusFg) = when {
        cell.isManual -> Triple("✓ Saved Manual Edit", Color(0xFFFEF3C7), Color(0xFF92400E))
        cell.isUserConfirmedInferred -> Triple("✓ User-Confirmed", Color(0xFFD1FAE5), Color(0xFF065F46))
        cell.requiresInferredConfirmation -> Triple("Needs Confirmation", Color(0xFFE0F2FE), Color(0xFF0369A1))
        cell.reviewStatus in listOf("needs_review", "ambiguous", "conflict") || cell.sourceType == "conflicting" ->
            Triple("⚠ Requires Review", Color(0xFFFEF3C7), Color(0xFFB45309))
        cell.isConfirmed && effectiveShift != null -> Triple("✓ Confirmed", Color(0xFFD1FAE5), Color(0xFF065F46))
        else -> Triple("Unresolved", Color(0xFFFEE2E2), Color(0xFF991B1B))
    }

    val ruleLabel = when (cell.inferenceRule ?: cell.sourceType) {
        "section_anchor_fill", "inferred_from_section_anchor", "section_inference" -> "Rule: 10-Day Section Anchor Fill"
        "verified_three_section_rotation", "inferred_from_verified_rotation" -> "Rule: Verified 3-Section Rotation"
        "weekly_off_recurrence", "weekly_off_override", "inferred_from_weekly_off", "off_propagation" -> "Rule: Weekly OFF Recurrence"
        "manual_section_override" -> "Rule: Section Shift Override"
        else -> null
    }

    val showAcceptButton = cell.requiresInferredConfirmation ||
        (cell.reviewStatus == "needs_review" && (cell.normalizedShift != null || cell.classification == "A" || cell.rawValue?.trim()?.uppercase(Locale.US) == "A"))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                cell.reviewStatus in listOf("needs_review", "ambiguous", "conflict") || cell.sourceType == "conflicting" -> Color(0xFFFFFBEB)
                cell.requiresInferredConfirmation -> Color(0xFFF8FAFC)
                cell.isUnresolved -> Color(0xFFFEF2F2)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (cell.isUnresolved) 1.5.dp else 1.dp,
            color = when {
                cell.reviewStatus in listOf("needs_review", "ambiguous", "conflict") || cell.sourceType == "conflicting" -> Color(0xFFF59E0B)
                cell.requiresInferredConfirmation -> Color(0xFF38BDF8)
                cell.isUnresolved -> Color(0xFFFCA5A5)
                else -> Color(0xFFE2E8F0)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Shift Badge
                    Surface(
                        color = shiftColor,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.width(54.dp)
                    ) {
                        Text(
                            text = shiftCode,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Day ${cell.dayNumber} (${cell.weekday.take(3)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = effectiveShift ?: if (shiftCode == "A?") "Candidate: A" else "No Shift Assigned",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = shiftColor
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(color = sourceBg, shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = sourceLabel,
                                    color = sourceFg,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Surface(color = statusBg, shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = statusLabel,
                                    color = statusFg,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            if (!cell.rawValue.isNullOrBlank()) {
                                Text(
                                    text = "OCR: '${cell.rawValue}'",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Action Buttons (Accept / Edit) with accessible touch targets
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showAcceptButton) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Text("✓ Accept", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text(
                            text = if (effectiveShift == null) "✎ Select Shift" else "✎ Edit",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Display Inference Rule or Review Reason
            if (ruleLabel != null || !cell.reviewReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (ruleLabel != null) {
                            Text(
                                text = ruleLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E40AF)
                            )
                        }
                        if (!cell.reviewReason.isNullOrBlank()) {
                            Text(
                                text = cell.reviewReason,
                                fontSize = 10.sp,
                                color = Color(0xFF334155)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewCellEditDialog(
    cell: ExtractedCell,
    onDismiss: () -> Unit,
    onAcceptInferred: () -> Unit,
    onSaveShift: (String) -> Unit,
    onLeaveUnresolved: () -> Unit
) {
    val initialSelection = remember(cell) {
        when (cell.normalizedShift) {
            "Morning", "M" -> "Morning"
            "Evening", "E" -> "Evening"
            "Night", "N" -> "Night"
            "Off", "OFF" -> "Off"
            "A" -> "A"
            else -> if (cell.classification == "A" || cell.rawValue?.trim()?.uppercase(Locale.US) == "A") "A" else null
        }
    }
    var selectedShift by remember(cell) { mutableStateOf<String?>(initialSelection) }

    val shiftOptions = listOf(
        Triple("Morning", "M", "Morning Shift"),
        Triple("Evening", "E", "Evening Shift"),
        Triple("Night", "N", "Night Shift"),
        Triple("Off", "OFF", "Weekly / Day Off"),
        Triple("A", "A", "Absent / Leave (A)")
    )

    val canAcceptExisting = cell.requiresInferredConfirmation ||
        (cell.reviewStatus == "needs_review" && initialSelection != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Review Day ${cell.dayNumber} (${cell.weekday}) — ${cell.date}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Diagnostic & Provenance Card (Preserves Original OCR Result)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Current Shift: ${cell.normalizedShift ?: if (cell.classification == "A") "A" else "Unresolved / Unknown"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Detection Source: ${cell.sourceType} (${cell.reviewStatus})",
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Preserved OCR Token: '${cell.rawValue ?: "None (blank)"}'" +
                                (if (cell.confidence > 0) " • Confidence: ${(cell.confidence * 100).toInt()}%" else ""),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!cell.inferenceRule.isNullOrBlank()) {
                            Text(
                                text = "Inference Rule: ${cell.inferenceRule}",
                                fontSize = 11.sp,
                                color = Color(0xFF1E40AF),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (!cell.reviewReason.isNullOrBlank()) {
                            Text(
                                text = "Reason: ${cell.reviewReason}",
                                fontSize = 11.sp,
                                color = Color(0xFFB91C1C)
                            )
                        }
                    }
                }

                // Quick Accept Button for Rule-Inferred or Candidate Shift
                if (canAcceptExisting && initialSelection != null) {
                    Button(
                        onClick = onAcceptInferred,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = "✓ Accept ${if (cell.isInferred) "Inferred" else "Detected"} Shift ($initialSelection)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Text(
                    text = "Select Shift Value (M, E, N, OFF, A):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                // Large Accessible Shift Selection Cards
                shiftOptions.forEach { (canonical, code, desc) ->
                    val isSelected = selectedShift == canonical
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { selectedShift = canonical },
                        color = if (isSelected) Color(0xFFDBEAFE) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFFCBD5E1)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedShift = canonical }
                                )
                                Text(
                                    text = "$code — $desc",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "Selected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D4ED8)
                                )
                            }
                        }
                    }
                }

                // Leave Unresolved Option
                OutlinedButton(
                    onClick = onLeaveUnresolved,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDC2626))
                ) {
                    Text(
                        text = "Leave Cell Unresolved (Cannot Verify)",
                        color = Color(0xFFDC2626),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = selectedShift
                    if (chosen != null) {
                        onSaveShift(chosen)
                    }
                },
                enabled = selectedShift != null,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Save Manual Correction", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Backward compatibility forwarder
 */
@Composable
fun DraftReviewScreen(
    draft: DraftSchedule,
    onConfirmCell: (dayNumber: Int, shift: String) -> Unit,
    onApproveSchedule: () -> Unit,
    onBackToCalendar: () -> Unit
) {
    EditableReviewScreen(
        draft = draft,
        onUpdateCell = { d, s -> onConfirmCell(d, s ?: "Unresolved") },
        onOverrideSection = { _, _ -> },
        onOverrideWeeklyOff = { _ -> },
        onUndo = {},
        canUndo = false,
        onResetToOcrDraft = {},
        onSaveDraftLocally = {},
        onApproveSchedule = onApproveSchedule,
        onBackToCalendar = onBackToCalendar
    )
}

// =========================================================================
// 5. DEVELOPER-ONLY DIAGNOSTIC INSPECTOR (LOCAL-FIRST & OPT-IN BENCHMARK)
// =========================================================================

@Composable
fun DeveloperDiagnosticDialog(
    report: RosterDiagnosticReport,
    onDismiss: () -> Unit,
    onRunCloudBenchmark: () -> Unit,
    isCloudBenchmarking: Boolean
) {
    var selectedTab by remember { mutableStateOf(0) }
    var wordSearchQuery by remember { mutableStateOf("") }
    var showBenchmarkWarningDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🛠 Developer Diagnostic Report", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Local-First ML Kit Pipeline Inspection", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Text("✕", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Section Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Overview", fontSize = 11.sp) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Rows (${report.candidateRows.size})", fontSize = 11.sp) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Words (${report.ocrDetails.extractedWordCount})", fontSize = 11.sp) })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Cloud OCR", fontSize = 11.sp) })
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> {
                        // Overview & File pipeline tab
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Undersized warning banner if applicable
                            if (report.fileDetails.isUndersized && report.fileDetails.undersizedWarning != null) {
                                item {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("⚠ LOW-RESOLUTION / THUMBNAIL INPUT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(report.fileDetails.undersizedWarning, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                }
                            }

                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("1. File & Image Pipeline", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("• File Name: ${report.fileDetails.fileName}", fontSize = 11.sp)
                                        Text("• MIME Type: ${report.fileDetails.mimeType}", fontSize = 11.sp)
                                        Text("• Encoded Size: ${report.fileDetails.encodedSizeBytes / 1024} KB (${report.fileDetails.encodedSizeBytes} bytes)", fontSize = 11.sp)
                                        Text("• Decoded Dimensions: ${report.decodingDetails.decodedWidth} x ${report.decodingDetails.decodedHeight} px", fontSize = 11.sp)
                                        Text("• Bitmap Config: ${report.decodingDetails.bitmapConfig}", fontSize = 11.sp)
                                        Text("• EXIF Orientation: ${report.decodingDetails.exifOrientation}", fontSize = 11.sp)
                                        Text("• Preprocessing Applied: ${report.decodingDetails.preprocessingApplied}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        Text("• Preprocessed Dims: ${report.decodingDetails.preprocessedWidth} x ${report.decodingDetails.preprocessedHeight} px", fontSize = 11.sp)
                                    }
                                }
                            }

                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("2. On-Device OCR Metrics", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("• Active Engine: ${report.ocrDetails.engineName}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("• OCR Duration: ${report.ocrDetails.ocrDurationMs} ms", fontSize = 11.sp)
                                        Text("• Total Words Extracted: ${report.ocrDetails.extractedWordCount}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("• First 15 Tokens: ${report.ocrDetails.sampleTokens.take(15).joinToString()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("3. Table Geometry & Staff Detection", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("• Date Columns Detected: ${report.dayColumnCount}", fontSize = 11.sp)
                                        Text("• Total Candidate Rows Evaluated: ${report.candidateRows.size}", fontSize = 11.sp)
                                        Text("• Accepted Staff Rows: ${report.acceptedStaffRows.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("• Rejected Clusters: ${report.candidateRows.count { !it.isAccepted }}", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Candidate row rejection audit tab
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(report.candidateRows) { row ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (row.isAccepted) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Cluster #${row.clusterIndex} (Y=${String.format(Locale.US, "%.1f", row.rowY)})",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (row.isAccepted) Color(0xFF065F46) else Color(0xFF991B1B)
                                            )
                                            Surface(
                                                color = if (row.isAccepted) Color(0xFF10B981) else Color(0xFFEF4444),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (row.isAccepted) "ACCEPTED" else (row.rejectionStage ?: "REJECTED"),
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Tokens: ${row.rawTokens.take(12).joinToString()}", fontSize = 11.sp)
                                        Text("Reason: ${row.decisionReason}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        if (row.isAccepted) {
                                            Text("Staff ID: ${row.rawId ?: "Auto"} | Name: ${row.name ?: "Unnamed"} | Shifts: ${row.shiftWordCount}", fontSize = 10.sp, color = Color(0xFF047857))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // Raw OCR Word Inspector
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = wordSearchQuery,
                                onValueChange = { wordSearchQuery = it },
                                label = { Text("Filter words (e.g. ABHL, AKASH, SEP)", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val filteredWords = remember(wordSearchQuery, report.rawWords) {
                                if (wordSearchQuery.isBlank()) report.rawWords
                                else report.rawWords.filter { it.text.contains(wordSearchQuery, ignoreCase = true) }
                            }
                            Text("Showing ${filteredWords.size} / ${report.rawWords.size} words", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(filteredWords) { w ->
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("\"${w.text}\"", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(
                                                "[${w.x.toInt()}, ${w.y.toInt()}, ${w.width.toInt()}x${w.height.toInt()}] " +
                                                "conf: ${w.confidence?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "N/A"}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // Opt-in OCR.space Engine 3 Cloud Benchmark
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB))) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("⚠ PRIVACY & ENGINE POLICY NOTICE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF92400E))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "MedicalStaffScheduleTracker is local-first by default: OCR runs 100% on-device via Google ML Kit Latin OCR.\n\n" +
                                            "This diagnostic benchmark invokes OCR.space Engine 3 using OCR_API_KEY. The roster image will leave this device and be transmitted to OCR.space servers.\n\n" +
                                            "Neither OCR.space nor PaddleOCR is included in the production app path.",
                                            fontSize = 11.sp,
                                            color = Color(0xFF78350F)
                                        )
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = { showBenchmarkWarningDialog = true },
                                    enabled = !isCloudBenchmarking && report.lastDecodedBitmap != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                                ) {
                                    if (isCloudBenchmarking) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Running OCR.space Benchmark...")
                                    } else {
                                        Text("Benchmark Against OCR.space Engine 3 (Opt-in)")
                                    }
                                }
                            }

                            if (report.cloudBenchmark != null) {
                                item {
                                    val bench = report.cloudBenchmark
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("Benchmark Result: ${bench.engine}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("• Duration: ${bench.durationMs} ms (vs ML Kit ${report.ocrDetails.ocrDurationMs} ms)", fontSize = 11.sp)
                                            Text("• Extracted Words: ${bench.extractedWordCount} words (vs ML Kit ${report.ocrDetails.extractedWordCount} words)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("• Sample OCR Text:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Text(bench.sampleText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {}
    )

    // Benchmark Privacy Confirmation Dialog
    if (showBenchmarkWarningDialog) {
        AlertDialog(
            onDismissRequest = { showBenchmarkWarningDialog = false },
            title = { Text("Confirm Cloud Transmission") },
            text = {
                Text(
                    "Warning: Opt-in Diagnostic Cloud OCR sends the roster image to OCR.space servers for benchmark comparison. This is NOT part of the local production pipeline. Your image will leave this device.\n\nProceed with upload?",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBenchmarkWarningDialog = false
                        onRunCloudBenchmark()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Yes, Upload & Benchmark")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBenchmarkWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
