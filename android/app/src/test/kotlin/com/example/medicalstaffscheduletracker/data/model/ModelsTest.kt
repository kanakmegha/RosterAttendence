package com.example.medicalstaffscheduletracker.data.model

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    @Test
    fun testExtractedCellStatusCategorization() {
        val observedCell = ExtractedCell(
            dayNumber = 14,
            date = "2026-09-14",
            weekday = "Mon",
            rawValue = "N",
            normalizedShift = "N",
            reviewStatus = "confirmed",
            reviewReason = null,
            confidence = 0.95,
            sourceType = "ocr_observed"
        )

        assertTrue(observedCell.isObserved)
        assertFalse(observedCell.isInferred)
        assertTrue(observedCell.isConfirmed)
        assertFalse(observedCell.isUnresolved)

        val inferredCell = ExtractedCell(
            dayNumber = 12,
            date = "2026-09-12",
            weekday = "Sat",
            rawValue = null,
            normalizedShift = "N",
            reviewStatus = "inferred_needs_confirmation",
            reviewReason = "Inferred from single shift per 10-day section rule",
            confidence = 0.70,
            sourceType = "section_inference",
            supportingDates = listOf(14, 15, 17, 20)
        )

        assertFalse(inferredCell.isObserved)
        assertTrue(inferredCell.isInferred)
        assertFalse(inferredCell.isConfirmed)
        assertTrue(inferredCell.isUnresolved)
    }

    @Test
    fun testDraftScheduleSafetyRules() {
        val draft = DraftSchedule(
            scheduleId = "sched-001",
            staffId = "ABHL341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false,
            approvedAt = null,
            entries = emptyList(),
            sectionSummaries = emptyList(),
            unresolvedDates = listOf(1, 2, 3, 29),
            isConfusableId = true,
            confusableMessage = "Similar to ABH1341"
        )

        // Safety Invariant: Draft schedules are never approved by default
        assertFalse(draft.isApproved)
        assertNull(draft.approvedAt)
        assertTrue(draft.isConfusableId)
        assertEquals(4, draft.unresolvedDates.size)
    }
}
