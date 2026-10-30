package com.example.medicalstaffscheduletracker.engine

import org.junit.Assert.*
import org.junit.Test

class LocalSectionInferenceTest {

    private val inferenceEngine = LocalSectionInferenceEngine(verifyRotationRuleDefault = false)
    private val inferenceEngineWithRotation = LocalSectionInferenceEngine(verifyRotationRuleDefault = true)

    @Test
    fun testMonthLengthsIncludingLeapYear() {
        // Test 28-day month (Feb non-leap)
        val cellsFeb28 = (1..28).map { d ->
            ReconstructedCell(d, "2025-02-$d", "Day", null, null, "missing", null, null, null, "num", "high")
        }
        val resFeb28 = inferenceEngine.inferSections(
            TableReconstructionResult("2025-02", "ABHL341", "ABHL341", "exact", "Akash", "SAT", "Floor 2", "confirmed", false, null, cellsFeb28, emptyList())
        )
        assertEquals(28, resFeb28.entries.size)
        assertEquals(3, resFeb28.sectionSummaries.size)
        // 28 days: Section 1: 1..10 (10), Section 2: 11..19 (9), Section 3: 20..28 (9)
        assertEquals(1, resFeb28.sectionSummaries[0].startDay)
        assertEquals(10, resFeb28.sectionSummaries[0].endDay)
        assertEquals(11, resFeb28.sectionSummaries[1].startDay)
        assertEquals(19, resFeb28.sectionSummaries[1].endDay)
        assertEquals(20, resFeb28.sectionSummaries[2].startDay)
        assertEquals(28, resFeb28.sectionSummaries[2].endDay)

        // Test 29-day month (Feb 2024 leap year)
        val cellsFeb29 = (1..29).map { d ->
            ReconstructedCell(d, "2024-02-$d", "Day", null, null, "missing", null, null, null, "num", "high")
        }
        val resFeb29 = inferenceEngine.inferSections(
            TableReconstructionResult("2024-02", "ABHL341", "ABHL341", "exact", "Akash", "SAT", "Floor 2", "confirmed", false, null, cellsFeb29, emptyList())
        )
        assertEquals(29, resFeb29.entries.size)
        assertEquals(3, resFeb29.sectionSummaries.size)
        // 29 days: Section 1: 1..10 (10), Section 2: 11..20 (10), Section 3: 21..29 (9)
        assertEquals(1, resFeb29.sectionSummaries[0].startDay)
        assertEquals(10, resFeb29.sectionSummaries[0].endDay)
        assertEquals(11, resFeb29.sectionSummaries[1].startDay)
        assertEquals(20, resFeb29.sectionSummaries[1].endDay)
        assertEquals(21, resFeb29.sectionSummaries[2].startDay)
        assertEquals(29, resFeb29.sectionSummaries[2].endDay)

        // Test 30-day month (Sept 2026)
        val cellsSep30 = (1..30).map { d ->
            ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
        }
        val resSep30 = inferenceEngine.inferSections(
            TableReconstructionResult("2026-09", "ABHL341", "ABHL341", "exact", "Akash", "SAT", "Floor 2", "confirmed", false, null, cellsSep30, emptyList())
        )
        assertEquals(30, resSep30.entries.size)
        assertEquals(3, resSep30.sectionSummaries.size)
        // 30 days: Section 1: 1..10 (10), Section 2: 11..20 (10), Section 3: 21..30 (10)
        assertEquals(1, resSep30.sectionSummaries[0].startDay)
        assertEquals(10, resSep30.sectionSummaries[0].endDay)
        assertEquals(11, resSep30.sectionSummaries[1].startDay)
        assertEquals(20, resSep30.sectionSummaries[1].endDay)
        assertEquals(21, resSep30.sectionSummaries[2].startDay)
        assertEquals(30, resSep30.sectionSummaries[2].endDay)

        // Test 31-day month (Oct 2026)
        val cellsOct31 = (1..31).map { d ->
            ReconstructedCell(d, "2026-10-$d", "Day", null, null, "missing", null, null, null, "num", "high")
        }
        val resOct31 = inferenceEngine.inferSections(
            TableReconstructionResult("2026-10", "ABHL341", "ABHL341", "exact", "Akash", "SAT", "Floor 2", "confirmed", false, null, cellsOct31, emptyList())
        )
        assertEquals(31, resOct31.entries.size)
        assertEquals(3, resOct31.sectionSummaries.size)
        // 31 days: Section 1: 1..11 (11), Section 2: 12..21 (10), Section 3: 22..31 (10)
        assertEquals(1, resOct31.sectionSummaries[0].startDay)
        assertEquals(11, resOct31.sectionSummaries[0].endDay)
        assertEquals(12, resOct31.sectionSummaries[1].startDay)
        assertEquals(21, resOct31.sectionSummaries[1].endDay)
        assertEquals(22, resOct31.sectionSummaries[2].startDay)
        assertEquals(31, resOct31.sectionSummaries[2].endDay)

        val day31 = resOct31.entries.first { it.dayNumber == 31 }
        assertEquals(22, day31.sectionStartDay)
        assertEquals(31, day31.sectionEndDay)
    }

    @Test
    fun testWeeklyOffRecurrenceAndPropagation() {
        // September 2026: Days 5, 12, 19, 26 are Saturdays.
        // Day 5 and Day 12 have directly observed OFF. Day 19 and 26 are blank.
        val cells = (1..30).map { d ->
            when (d) {
                5, 12 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Weekday", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = "SAT",
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        // Day 5: directly observed OFF
        val day5 = draft.entries.first { it.dayNumber == 5 }
        assertEquals("Off", day5.normalizedShift)
        assertEquals("OFF", day5.classification)
        assertEquals("directly_observed", day5.sourceType)
        assertEquals("confirmed", day5.reviewStatus)

        // Day 19 and 26: inferred from weekly Off rule
        val day19 = draft.entries.first { it.dayNumber == 19 }
        assertEquals("Off", day19.normalizedShift)
        assertEquals("OFF", day19.classification)
        assertEquals("inferred_from_weekly_off", day19.sourceType)
        assertEquals("inferred_needs_confirmation", day19.reviewStatus)
        assertTrue(day19.reviewReason?.contains("weekly Saturday OFF") == true)

        val day26 = draft.entries.first { it.dayNumber == 26 }
        assertEquals("Off", day26.normalizedShift)
        assertEquals("OFF", day26.classification)
        assertEquals("inferred_from_weekly_off", day26.sourceType)
    }

    @Test
    fun testConflictingOffWeekdaysSuspendsRecurrence() {
        // Conflicting OFF: Day 5 is Saturday OFF, Day 8 is Tuesday OFF
        val cells = (1..30).map { d ->
            when (d) {
                5 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                8 -> ReconstructedCell(d, "2026-09-$d", "Tuesday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Weekday", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        // Both observed OFF days are preserved
        assertEquals("Off", draft.entries.first { it.dayNumber == 5 }.normalizedShift)
        assertEquals("Off", draft.entries.first { it.dayNumber == 8 }.normalizedShift)

        // But other Saturdays (Day 12, 19, 26) must NOT be automatically inferred as OFF due to conflict!
        val day12 = draft.entries.first { it.dayNumber == 12 }
        assertNull(day12.normalizedShift)
        assertEquals("unresolved", day12.reviewStatus)
    }

    @Test
    fun testCornerAnchorDetectionAndInwardSearch() {
        // Section 2: Corner date (Day 11) is missing, but inward date (Day 17) has Night
        val cells = (1..30).map { d ->
            when (d) {
                17 -> ReconstructedCell(d, "2026-09-$d", "Thursday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        // Missing days in Section 2 (e.g. Day 11, Day 14) are filled with Night from inward anchor
        val day11 = draft.entries.first { it.dayNumber == 11 }
        assertEquals("Night", day11.normalizedShift)
        assertEquals("N", day11.classification)
        assertEquals("inferred_from_section_anchor", day11.sourceType)
        assertEquals("inferred_needs_confirmation", day11.reviewStatus)

        // Directly observed anchor day preserves ocr_observed
        val day17 = draft.entries.first { it.dayNumber == 17 }
        assertEquals("Night", day17.normalizedShift)
        assertEquals("N", day17.classification)
        assertEquals("directly_observed", day17.sourceType)
        assertEquals("confirmed", day17.reviewStatus)
    }

    @Test
    fun testSectionWithNoAnchorsAndVerifiedRotationFallback() {
        // Section 1: No shift tokens (only Day 5 is Saturday OFF)
        // Section 2: Direct observation Night (Day 17)
        // Section 3: Direct observation Morning (Day 21)
        // Verified 3-section rotation fallback recovers 'Evening' for Section 1!
        val cells = (1..30).map { d ->
            when (d) {
                5 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                17 -> ReconstructedCell(d, "2026-09-$d", "Thursday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                21 -> ReconstructedCell(d, "2026-09-$d", "Monday", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = "SAT",
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngineWithRotation.inferSections(recon, verifyRotationRule = true)

        // Section 1: Working days (e.g. Day 1, 2, 3) must be recovered as Evening!
        val day1 = draft.entries.first { it.dayNumber == 1 }
        assertEquals("Evening", day1.normalizedShift)
        assertEquals("E", day1.classification)
        assertEquals("inferred_from_verified_rotation", day1.sourceType)
        assertEquals("inferred_needs_confirmation", day1.reviewStatus)
        assertTrue(day1.reviewReason?.contains("verified 3-section rotation rule") == true)

        // Section 1 Summary should show inferenceMethod = verified_rotation
        val sec1 = draft.sectionSummaries[0]
        assertEquals("Evening", sec1.proposedShift)
        assertEquals("verified_rotation", sec1.inferenceMethod)

        // Day 5 remains OFF
        assertEquals("Off", draft.entries.first { it.dayNumber == 5 }.normalizedShift)
    }

    @Test
    fun testSectionWithNoAnchorsAndNoVerifiedRotationRemainsUnresolved() {
        // Same inputs as above, but verifyRotationRule = false
        val cells = (1..30).map { d ->
            when (d) {
                5 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                17 -> ReconstructedCell(d, "2026-09-$d", "Thursday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                21 -> ReconstructedCell(d, "2026-09-$d", "Monday", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = "SAT",
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon, verifyRotationRule = false)

        // Section 1: Must remain unresolved!
        val day1 = draft.entries.first { it.dayNumber == 1 }
        assertNull("Section 1 without verified rotation must remain null", day1.normalizedShift)
        assertEquals("unresolved", day1.reviewStatus)
        assertEquals("UNRESOLVED", day1.classification)

        val sec1 = draft.sectionSummaries[0]
        assertNull(sec1.proposedShift)
        assertEquals("unresolved", sec1.inferenceMethod)
    }

    @Test
    fun testConflictingHighConfidenceShifts() {
        // Section 2 has conflicting shifts: Day 13 is Morning, Day 17 is Night
        val cells = (1..30).map { d ->
            when (d) {
                13 -> ReconstructedCell(d, "2026-09-$d", "Day", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                17 -> ReconstructedCell(d, "2026-09-$d", "Day", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        // Missing days in Section 2 must be left unresolved due to conflict
        val day14 = draft.entries.first { it.dayNumber == 14 }
        assertNull(day14.normalizedShift)
        assertEquals("unresolved", day14.reviewStatus)
        assertEquals("CONFLICT", day14.classification)
        assertEquals("conflicting", day14.sourceType)

        // Observed days must be preserved with needs_review and conflicting provenance
        val day13 = draft.entries.first { it.dayNumber == 13 }
        assertEquals("Morning", day13.normalizedShift)
        assertEquals("needs_review", day13.reviewStatus)
        assertEquals("conflicting", day13.sourceType)

        val day17 = draft.entries.first { it.dayNumber == 17 }
        assertEquals("Night", day17.normalizedShift)
        assertEquals("needs_review", day17.reviewStatus)
        assertEquals("conflicting", day17.sourceType)
    }

    @Test
    fun testAmbiguousOcrTokenCannotEstablishPattern() {
        // Section 2 has only an ambiguous token "NN" (status = needs_review)
        val cells = (1..30).map { d ->
            when (d) {
                14 -> ReconstructedCell(d, "2026-09-$d", "Day", "NN", "Night", "needs_review", 0.70, "ocr_observed", "Ambiguous concatenated token 'NN'", "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        // Ambiguous observation preserved with needs_review
        val day14 = draft.entries.first { it.dayNumber == 14 }
        assertEquals("Night", day14.normalizedShift)
        assertEquals("needs_review", day14.reviewStatus)

        // Other missing days in Section 2 must NOT infer Night based on an ambiguous token!
        val day15 = draft.entries.first { it.dayNumber == 15 }
        assertNull(day15.normalizedShift)
        assertEquals("unresolved", day15.reviewStatus)
    }

    @Test
    fun testExplicitOffIsNeverSilentlyOverwritten() {
        // Section 1 has proposed Morning, but Day 5 has explicit OFF
        val cells = (1..30).map { d ->
            when (d) {
                1, 2, 3 -> ReconstructedCell(d, "2026-09-$d", "Day", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                5 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "confirmed",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)

        val day5 = draft.entries.first { it.dayNumber == 5 }
        assertEquals("Off", day5.normalizedShift)
        assertEquals("OFF", day5.classification)
        assertEquals("directly_observed", day5.sourceType)
        assertEquals("confirmed", day5.reviewStatus)

        // Day 4 (missing) gets inferred Morning
        val day4 = draft.entries.first { it.dayNumber == 4 }
        assertEquals("Morning", day4.normalizedShift)
        assertEquals("inferred_from_section_anchor", day4.sourceType)
    }

    @Test
    fun testNonTargetShiftAIsPreservedAsBlankFinalShift() {
        // Roster contains 'A' on Day 8
        val cells = (1..30).map { d ->
            when (d) {
                8 -> ReconstructedCell(d, "2026-09-$d", "Day", "A", null, "needs_review", 0.70, "ocr_observed", "Observation A requires review", "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Day", null, null, "missing", null, null, null, "num", "high")
            }
        }
        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABHL341",
            matchReason = "exact",
            staffName = "Akash",
            weeklyOffDay = null,
            floorDepartment = "Floor 2",
            status = "needs_review",
            isConfusableId = false,
            confusableMessage = null,
            entries = cells
        )
        val draft = inferenceEngine.inferSections(recon)
        val day8 = draft.entries.first { it.dayNumber == 8 }

        assertEquals("A", day8.rawValue)
        assertNull("Non-target shift code 'A' final normalized shift field MUST be left blank/null", day8.normalizedShift)
        assertEquals("A", day8.classification)
        assertEquals("needs_review", day8.reviewStatus)
        assertTrue(day8.isUnresolved)
    }

    @Test
    fun testAkashRosterScenarioWithEngine2Tokens() {
        // Exact September 2026 OCR.space tokens for Akash
        val cells = (1..30).map { d ->
            when (d) {
                5, 12, 19 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFF", "Off", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                26 -> ReconstructedCell(d, "2026-09-$d", "Saturday", "OFFI", "Off", "needs_review", 0.90, "ocr_observed", "Border artifact", "num", "high")
                14 -> ReconstructedCell(d, "2026-09-$d", "Monday", "NN", "Night", "needs_review", 0.70, "ocr_observed", "Ambiguous concatenated NN", "num", "high")
                17 -> ReconstructedCell(d, "2026-09-$d", "Thursday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                20 -> ReconstructedCell(d, "2026-09-$d", "Sunday", "N", "Night", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                21, 22, 28, 30 -> ReconstructedCell(d, "2026-09-$d", "Weekday", "M", "Morning", "confirmed", 0.95, "ocr_observed", null, "num", "high")
                29 -> ReconstructedCell(d, "2026-09-$d", "Tuesday", "A", null, "needs_review", 0.70, "ocr_observed", "Non-target shift code A", "num", "high")
                else -> ReconstructedCell(d, "2026-09-$d", "Weekday", null, null, "missing", null, null, null, "num", "high")
            }
        }

        val recon = TableReconstructionResult(
            rosterMonth = "2026-09",
            requestedStaffId = "ABHL341",
            matchedStaffId = "ABH1341",
            matchReason = "Visual OCR character confusion",
            staffName = "Akash",
            weeklyOffDay = "SAT",
            floorDepartment = "4th floor",
            status = "needs_review",
            isConfusableId = true,
            confusableMessage = "Exact ID matched confusable ABH1341",
            entries = cells
        )

        val draft = inferenceEngineWithRotation.inferSections(recon, verifyRotationRule = true)
        val entries = draft.entries
        assertEquals(30, entries.size)

        // Section 1: Recovered as Evening via verified 3-section rotation rule
        for (d in listOf(1, 2, 3, 4, 6, 7, 8, 9, 10)) {
            val cell = entries.first { it.dayNumber == d }
            assertEquals("Evening", cell.normalizedShift)
            assertEquals("E", cell.classification)
            assertEquals("inferred_from_verified_rotation", cell.sourceType)
            assertEquals("inferred_needs_confirmation", cell.reviewStatus)
        }
        // Saturday 5 is OFF
        assertEquals("Off", entries.first { it.dayNumber == 5 }.normalizedShift)
        assertEquals("OFF", entries.first { it.dayNumber == 5 }.classification)

        // Section 2: Inferred Night from anchors
        for (d in listOf(11, 13, 16, 18)) {
            val cell = entries.first { it.dayNumber == d }
            assertEquals("Night", cell.normalizedShift)
            assertEquals("N", cell.classification)
            assertEquals("inferred_from_section_anchor", cell.sourceType)
        }
        // Saturdays 12 and 19 are OFF
        assertEquals("Off", entries.first { it.dayNumber == 12 }.normalizedShift)
        assertEquals("Off", entries.first { it.dayNumber == 19 }.normalizedShift)
        // Directly observed 17 and 20 are Night
        assertEquals("Night", entries.first { it.dayNumber == 17 }.normalizedShift)
        assertEquals("Night", entries.first { it.dayNumber == 20 }.normalizedShift)

        // Section 3: Inferred Morning from anchors
        for (d in listOf(23, 24, 25, 27)) {
            val cell = entries.first { it.dayNumber == d }
            assertEquals("Morning", cell.normalizedShift)
            assertEquals("M", cell.classification)
            assertEquals("inferred_from_section_anchor", cell.sourceType)
        }
        // Saturday 26 is OFF
        assertEquals("Off", entries.first { it.dayNumber == 26 }.normalizedShift)
        // Directly observed 21, 22, 28, 30 are Morning
        assertEquals("Morning", entries.first { it.dayNumber == 21 }.normalizedShift)
        assertEquals("Morning", entries.first { it.dayNumber == 30 }.normalizedShift)
        // Day 29 is 'A' (final recorded shift field blank)
        val day29 = entries.first { it.dayNumber == 29 }
        assertNull("Day 29 with 'A' must have null normalized shift", day29.normalizedShift)
        assertEquals("A", day29.rawValue)
        assertEquals("A", day29.classification)
        assertEquals("needs_review", day29.reviewStatus)

        // Audit counts:
        assertTrue(draft.directEvidenceCount > 0)
        assertTrue(draft.inferredEvidenceCount > 0)
    }
}
