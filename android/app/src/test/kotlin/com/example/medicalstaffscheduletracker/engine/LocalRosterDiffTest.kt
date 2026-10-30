package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import org.junit.Assert.*
import org.junit.Test

class LocalRosterDiffTest {

    @Test
    fun testCompareRostersDetectsShiftChangesAndDutyModifications() {
        val originalEntries = listOf(
            ExtractedCell(1, "2026-09-01", "Tuesday", "M", "Morning", "confirmed", null, 0.95, "ocr_observed"),
            ExtractedCell(2, "2026-09-02", "Wednesday", "M", "Morning", "confirmed", null, 0.95, "ocr_observed"),
            ExtractedCell(3, "2026-09-03", "Thursday", null, null, "missing", null, 0.0, "ocr_observed")
        )

        val revisedEntries = listOf(
            ExtractedCell(1, "2026-09-01", "Tuesday", "M", "Morning", "confirmed", null, 0.95, "ocr_observed"), // unchanged
            ExtractedCell(2, "2026-09-02", "Wednesday", "E", "Evening", "confirmed", null, 0.95, "ocr_observed"), // changed to Evening
            ExtractedCell(3, "2026-09-03", "Thursday", "N", "Night", "confirmed", null, 0.95, "ocr_observed")   // duty added
        )

        val original = DraftSchedule("orig1", "ABHL341", "Akash", "2026-09", true, "2026-09-01T00:00:00Z", originalEntries, emptyList(), emptyList(), false, null)
        val revised = DraftSchedule("rev1", "ABHL341", "Akash", "2026-09", false, null, revisedEntries, emptyList(), emptyList(), false, null)

        val diff = LocalRosterDiff.compare(original, revised)

        assertEquals("2026-09", diff.originalMonth)
        assertEquals("2026-09", diff.revisedMonth)
        assertEquals("ABHL341", diff.staffId)
        assertEquals(2, diff.totalChanges) // Day 2 changed, Day 3 duty added

        val day1Diff = diff.items.first { it.dayNumber == 1 }
        assertEquals("UNCHANGED", day1Diff.changeType)

        val day2Diff = diff.items.first { it.dayNumber == 2 }
        assertEquals("SHIFT_CHANGED", day2Diff.changeType)
        assertEquals("Morning", day2Diff.originalShift)
        assertEquals("Evening", day2Diff.revisedShift)

        val day3Diff = diff.items.first { it.dayNumber == 3 }
        assertEquals("DUTY_ADDED", day3Diff.changeType)
        assertNull(day3Diff.originalShift)
        assertEquals("Night", day3Diff.revisedShift)
    }
}
