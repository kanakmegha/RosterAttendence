package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import com.example.medicalstaffscheduletracker.data.model.ManualDuty
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class FreshInstallAndPersistenceWorkflowTest {

    // Simulates an in-memory repository store representing the local SQLite + SharedPreferences
    private class InMemoryScheduleStorage {
        val schedules = mutableMapOf<String, DraftSchedule>()
        val manualDuties = mutableListOf<ManualDuty>()
        val preferences = mutableMapOf<String, String>()

        fun getActiveSchedule(month: String? = null, staffId: String? = null): DraftSchedule? {
            val activeStaffId = staffId ?: preferences["active_staff_id"]
            val activeMonth = month ?: preferences["active_month"]

            if (activeStaffId.isNullOrBlank() || activeMonth.isNullOrBlank()) {
                // If no preference set, return latest saved schedule or null on fresh install
                return schedules.values.filter { it.isApproved }.lastOrNull() ?: schedules.values.lastOrNull()
            }

            // Prefer latest approved schedule (created_at DESC)
            return schedules.values.filter { it.staffId == activeStaffId && it.month == activeMonth && it.isApproved }.lastOrNull()
                ?: schedules.values.filter { it.staffId == activeStaffId && it.month == activeMonth }.lastOrNull()
        }

        fun saveDraft(draft: DraftSchedule, isApproved: Boolean = false): String {
            val toSave = draft.copy(isApproved = isApproved)
            schedules[draft.scheduleId] = toSave
            return draft.scheduleId
        }

        fun approveSchedule(scheduleId: String): DraftSchedule? {
            val existing = schedules[scheduleId] ?: return null
            val approved = existing.copy(isApproved = true, approvedAt = "2026-09-01T12:00:00Z")
            schedules[scheduleId] = approved
            preferences["active_staff_id"] = approved.staffId
            preferences["active_month"] = approved.month
            return approved
        }

        fun updateCell(scheduleId: String, dayNumber: Int, newShift: String): DraftSchedule? {
            val existing = schedules[scheduleId] ?: return null
            val updatedEntries = existing.entries.map {
                if (it.dayNumber == dayNumber) it.copy(normalizedShift = newShift, reviewStatus = "confirmed", reviewReason = "User edit")
                else it
            }
            val updated = existing.copy(entries = updatedEntries)
            schedules[scheduleId] = updated
            return updated
        }

        fun addManualDuty(activeScheduleId: String, duty: ManualDuty): DraftSchedule? {
            manualDuties.add(duty)
            val existing = schedules[activeScheduleId] ?: return null
            val dayNum = duty.date.split("-").getOrNull(2)?.toIntOrNull() ?: return existing
            val updatedEntries = existing.entries.map {
                if (it.dayNumber == dayNum) it.copy(
                    normalizedShift = duty.shift,
                    reviewStatus = "confirmed",
                    sourceType = "manual",
                    reviewReason = "Manual emergency duty (${duty.notes ?: ""})"
                ) else it
            }
            val updated = existing.copy(entries = updatedEntries)
            schedules[activeScheduleId] = updated
            return updated
        }
    }

    @Test
    fun testFreshInstallShowsEmptyStateWithNoDefaultStaffOrMonth() {
        val storage = InMemoryScheduleStorage()

        // 1. On fresh install, storage has no preferences and no schedules
        assertTrue("Preferences must be empty on fresh install", storage.preferences.isEmpty())
        assertTrue("No schedules should exist on fresh install", storage.schedules.isEmpty())

        // 2. Active schedule must be null - no hardcoded 'ABHL341', no hardcoded '2026-09'
        val activeSchedule = storage.getActiveSchedule(null, null)
        assertNull("Active schedule must be null on a fresh install", activeSchedule)
        assertNull("No default staff ID should be set", storage.preferences["active_staff_id"])
        assertNull("No default month should be set", storage.preferences["active_month"])
    }

    @Test
    fun testCompleteWorkflowFromImportToApprovalAndExcelSync() {
        val storage = InMemoryScheduleStorage()

        // 1. User selects file and table reconstructor extracts staff members and detects month
        val words = listOf(
            OcrWord("DEPARTMENT", 100f, 30f, 60f, 15f),
            OcrWord("ROSTER", 165f, 30f, 50f, 15f),
            OcrWord("SEPTEMBER", 220f, 30f, 70f, 15f),
            OcrWord("2026", 295f, 30f, 40f, 15f)
        )
        val monthDetection = LocalTableReconstructor.detectMonthFromWords(words)
        assertTrue(monthDetection.isConfident)
        assertEquals("2026-09", monthDetection.monthStr)

        // 2. Draft schedule generated for selected employee "ABH1341" (Akash)
        val entries = (1..30).map { day ->
            ExtractedCell(
                dayNumber = day,
                date = String.format("2026-09-%02d", day),
                weekday = "TUE",
                rawValue = "E",
                normalizedShift = "Evening",
                reviewStatus = "auto_accepted",
                reviewReason = null,
                confidence = 0.95,
                sourceType = "ocr_observed"
            )
        }
        val draft = DraftSchedule(
            scheduleId = "draft_101",
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

        // 3. Save as unapproved draft: Verify draft is saved but NOT yet marked approved
        storage.saveDraft(draft, isApproved = false)
        val savedDraft = storage.schedules["draft_101"]
        assertNotNull(savedDraft)
        assertFalse("Imported roster must start as unapproved draft", savedDraft!!.isApproved)

        // 4. User reviews and approves the schedule
        val approved = storage.approveSchedule("draft_101")
        assertNotNull(approved)
        assertTrue("Schedule must be approved", approved!!.isApproved)
        assertEquals("ABH1341", storage.preferences["active_staff_id"])
        assertEquals("2026-09", storage.preferences["active_month"])

        // 5. Excel workbook bytes generated on approval
        val xlsxBytes = ExcelWorkbookManager.generateXlsxBytes(approved)
        assertTrue("Workbook bytes must be generated on approval", xlsxBytes.isNotEmpty())

        // 6. User edits calendar: Changes day 3 from Evening to Night
        val updatedSchedule = storage.updateCell("draft_101", dayNumber = 3, newShift = "Night")
        assertNotNull(updatedSchedule)
        val day3Entry = updatedSchedule!!.entries.first { it.dayNumber == 3 }
        assertEquals("Night", day3Entry.normalizedShift)
        assertEquals("confirmed", day3Entry.reviewStatus)

        // Verify updated workbook reflects the change
        val updatedBytes = ExcelWorkbookManager.generateXlsxBytes(updatedSchedule)
        val zis = ZipInputStream(ByteArrayInputStream(updatedBytes))
        var sheetXml: String? = null
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == "xl/worksheets/sheet1.xml") {
                sheetXml = zis.bufferedReader().readText()
                break
            }
            entry = zis.nextEntry
        }
        zis.close()
        assertNotNull(sheetXml)
        assertTrue("Updated workbook should contain Night for edited cell", sheetXml!!.contains("Night"))

        // 7. User adds manual emergency duty on day 15 (Shift: Morning, Note: "Covering ICU emergency")
        val manualDuty = ManualDuty(
            id = "duty_1",
            date = "2026-09-15",
            shift = "Morning",
            department = "ICU",
            floor = "Floor 2",
            notes = "Covering ICU emergency"
        )
        val schedWithDuty = storage.addManualDuty("draft_101", manualDuty)
        assertNotNull(schedWithDuty)
        val day15Entry = schedWithDuty!!.entries.first { it.dayNumber == 15 }
        assertEquals("Morning", day15Entry.normalizedShift)
        assertEquals("manual", day15Entry.sourceType)
        assertTrue(day15Entry.reviewReason!!.contains("Covering ICU emergency"))
    }

    @Test
    fun testReimportingRevisedRosterDoesNotOverwriteApprovedSchedulePrematurely() {
        val storage = InMemoryScheduleStorage()

        // 1. Initial approved schedule
        val approvedEntries = (1..5).map { day ->
            ExtractedCell(
                dayNumber = day,
                date = String.format("2026-09-%02d", day),
                weekday = "MON",
                rawValue = "M",
                normalizedShift = "Morning",
                reviewStatus = "auto_accepted",
                reviewReason = null,
                confidence = 0.95,
                sourceType = "ocr_observed"
            )
        }
        val approvedSchedule = DraftSchedule(
            scheduleId = "v1_approved",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = true,
            approvedAt = "2026-09-01T09:00:00Z",
            entries = approvedEntries,
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        storage.saveDraft(approvedSchedule, isApproved = true)
        storage.preferences["active_staff_id"] = "ABH1341"
        storage.preferences["active_month"] = "2026-09"

        // 2. Re-import revised roster as a new unapproved draft (v2)
        val revisedEntries = approvedEntries.map {
            if (it.dayNumber == 2) it.copy(rawValue = "E", normalizedShift = "Evening")
            else it.copy()
        }
        val revisedDraft = DraftSchedule(
            scheduleId = "v2_draft",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false, // Must remain false until user approves
            approvedAt = null,
            entries = revisedEntries,
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        storage.saveDraft(revisedDraft, isApproved = false)

        // 3. Verify that querying the active schedule still returns the approved schedule (v1), NOT the unreviewed draft
        val currentActive = storage.getActiveSchedule("2026-09", "ABH1341")
        assertNotNull(currentActive)
        assertEquals("v1_approved", currentActive!!.scheduleId)
        assertTrue("Active schedule must remain the approved one", currentActive.isApproved)
        assertEquals("Morning", currentActive.entries.first { it.dayNumber == 2 }.normalizedShift)

        // 4. Compare diff
        val diff = LocalRosterDiff.compare(approvedSchedule, revisedDraft)
        assertEquals(1, diff.totalChanges)
        val day2Diff = diff.items.first { it.dayNumber == 2 }
        assertEquals("SHIFT_CHANGED", day2Diff.changeType)
        assertEquals("Morning", day2Diff.originalShift)
        assertEquals("Evening", day2Diff.revisedShift)

        // 5. Only when user approves revised draft does it become the active approved schedule
        storage.approveSchedule("v2_draft")
        val newActive = storage.getActiveSchedule("2026-09", "ABH1341")
        assertNotNull(newActive)
        assertEquals("v2_draft", newActive!!.scheduleId)
        assertTrue(newActive.isApproved)
        assertEquals("Evening", newActive.entries.first { it.dayNumber == 2 }.normalizedShift)
    }

    @Test
    fun testSavingAndRestoringScheduleAfterSimulatedAppRestart() {
        val storage = InMemoryScheduleStorage()

        // 1. User previously saved and approved a schedule for Priya (ABH2044) in October 2026
        val entries = (1..31).map { day ->
            ExtractedCell(
                dayNumber = day,
                date = String.format("2026-10-%02d", day),
                weekday = "THU",
                rawValue = "N",
                normalizedShift = "Night",
                reviewStatus = "auto_accepted",
                reviewReason = null,
                confidence = 0.95,
                sourceType = "ocr_observed"
            )
        }
        val priyaSchedule = DraftSchedule(
            scheduleId = "priya_oct_2026",
            staffId = "ABH2044",
            staffName = "Priya",
            month = "2026-10",
            isApproved = true,
            approvedAt = "2026-10-01T08:00:00Z",
            entries = entries,
            sectionSummaries = emptyList(),
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )
        storage.saveDraft(priyaSchedule, isApproved = true)
        storage.approveSchedule("priya_oct_2026")

        // 2. Simulate app restart: A new screen/repository instance queries getActiveSchedule(null, null)
        val restoredSchedule = storage.getActiveSchedule(null, null)
        assertNotNull("Previously saved schedule must be restored on app launch", restoredSchedule)
        assertEquals("ABH2044", restoredSchedule!!.staffId)
        assertEquals("Priya", restoredSchedule.staffName)
        assertEquals("2026-10", restoredSchedule.month)
        assertTrue(restoredSchedule.isApproved)
        assertEquals(31, restoredSchedule.entries.size)
    }
}
