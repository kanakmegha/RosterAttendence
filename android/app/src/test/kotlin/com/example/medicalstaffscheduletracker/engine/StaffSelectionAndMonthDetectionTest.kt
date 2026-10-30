package com.example.medicalstaffscheduletracker.engine

import org.junit.Assert.*
import org.junit.Test

class StaffSelectionAndMonthDetectionTest {

    @Test
    fun testMonthAndYearDetectionFromHeaders() {
        // Words containing header "DEPARTMENT DUTY ROSTER SEPTEMBER 2026"
        val wordsSep = listOf(
            OcrWord("DEPARTMENT", 100f, 30f, 60f, 15f),
            OcrWord("DUTY", 165f, 30f, 40f, 15f),
            OcrWord("ROSTER", 210f, 30f, 50f, 15f),
            OcrWord("SEPTEMBER", 270f, 30f, 70f, 15f),
            OcrWord("2026", 345f, 30f, 40f, 15f)
        )
        val detectedSep = LocalTableReconstructor.detectMonthFromWords(wordsSep)
        assertTrue(detectedSep.isConfident)
        assertEquals("2026-09", detectedSep.monthStr)
        assertEquals(2026, detectedSep.year)
        assertEquals(9, detectedSep.month)
        assertTrue(detectedSep.displayName.contains("September 2026"))

        // Words containing "OCTOBER" without explicit year -> detected with isConfident = false
        val wordsOct = listOf(
            OcrWord("DUTY", 100f, 30f, 40f, 15f),
            OcrWord("ROSTER", 145f, 30f, 50f, 15f),
            OcrWord("OCTOBER", 200f, 30f, 60f, 15f)
        )
        val detectedOct = LocalTableReconstructor.detectMonthFromWords(wordsOct)
        assertFalse(detectedOct.isConfident) // Unanchored year requires confirmation
        assertEquals(10, detectedOct.month)

        // Words with no month names at all -> fallback with isConfident = false
        val wordsNoMonth = listOf(
            OcrWord("HOSPITAL", 100f, 30f, 60f, 15f),
            OcrWord("SCHEDULE", 165f, 30f, 60f, 15f)
        )
        val detectedFallback = LocalTableReconstructor.detectMonthFromWords(wordsNoMonth)
        assertFalse(detectedFallback.isConfident)
    }

    @Test
    fun testStaffExtractionAndUserDrivenSelection() {
        // Construct full roster with multiple staff members
        val words = mutableListOf(
            OcrWord(text = "S.NO", x = 10f, y = 50f, width = 20f, height = 10f),
            OcrWord(text = "ID", x = 40f, y = 50f, width = 30f, height = 10f),
            OcrWord(text = "NAME", x = 110f, y = 50f, width = 40f, height = 10f),
            OcrWord(text = "OFF DAY", x = 210f, y = 50f, width = 35f, height = 10f),
            OcrWord(text = "FLOOR", x = 260f, y = 50f, width = 35f, height = 10f),
            OcrWord(text = "SEPTEMBER 2026", x = 100f, y = 20f, width = 120f, height = 15f)
        )

        // Add 5 day headers
        val colStartX = 310f
        val colStep = 30f
        val days = listOf("TUE", "WED", "THU", "FRI", "SAT")
        for (i in 0 until 5) {
            val d = i + 1
            words.add(OcrWord(text = days[i], x = colStartX + i * colStep, y = 40f, width = 25f, height = 10f))
            words.add(OcrWord(text = "$d", x = colStartX + i * colStep + 5f, y = 60f, width = 15f, height = 10f))
        }

        // Employee 1: Akash (ABH1341)
        val row1Y = 120f
        words.add(OcrWord(text = "1", x = 15f, y = row1Y, width = 10f, height = 10f))
        words.add(OcrWord(text = "ABH1341", x = 45f, y = row1Y, width = 40f, height = 10f))
        words.add(OcrWord(text = "Akash", x = 115f, y = row1Y, width = 40f, height = 10f))
        words.add(OcrWord(text = "SAT", x = 215f, y = row1Y, width = 25f, height = 10f))
        words.add(OcrWord(text = "Floor 2", x = 265f, y = row1Y, width = 35f, height = 10f))
        words.add(OcrWord(text = "M", x = colStartX + 5f, y = row1Y, width = 12f, height = 10f))

        // Employee 2: Priya (ABH2044)
        val row2Y = 160f
        words.add(OcrWord(text = "2", x = 15f, y = row2Y, width = 10f, height = 10f))
        words.add(OcrWord(text = "ABH2044", x = 45f, y = row2Y, width = 40f, height = 10f))
        words.add(OcrWord(text = "Priya", x = 115f, y = row2Y, width = 40f, height = 10f))
        words.add(OcrWord(text = "SUN", x = 215f, y = row2Y, width = 25f, height = 10f))
        words.add(OcrWord(text = "Floor 3", x = 265f, y = row2Y, width = 35f, height = 10f))
        words.add(OcrWord(text = "E", x = colStartX + 5f, y = row2Y, width = 12f, height = 10f))

        val reconstructor = LocalTableReconstructor(words = words)
        val metadata = reconstructor.analyzeRosterStructure()

        // 1. Verify detected staff list
        assertEquals(2, metadata.detectedStaff.size)
        val staff1 = metadata.detectedStaff[0]
        val staff2 = metadata.detectedStaff[1]

        assertEquals("ABH1341", staff1.rawId)
        assertEquals("Akash", staff1.name)
        assertEquals("ABH2044", staff2.rawId)
        assertEquals("Priya", staff2.name)

        // 2. Select Priya (ABH2044)
        val priyaResult = reconstructor.reconstructForStaff(metadata, "ABH2044")
        assertEquals("ABH2044", priyaResult.matchedStaffId)
        assertEquals("Priya", priyaResult.staffName)
        assertEquals("Evening", priyaResult.entries[0].normalizedShift)

        // 3. User entered ID with OCR character confusion: 'ABHL341' searching for 'ABH1341'
        val confusableResult = reconstructor.reconstructForStaff(metadata, "ABHL341")
        assertTrue("Confusable match flag expected", confusableResult.isConfusableId)
        assertEquals("ABH1341", confusableResult.matchedStaffId)
        assertEquals("Akash", confusableResult.staffName)
        assertNotNull(confusableResult.confusableMessage)

        // 4. Missing ID search: 'UNKNOWN_999' -> returns id_not_found with diagnostic detected list
        val missingResult = reconstructor.reconstructForStaff(metadata, "UNKNOWN_999")
        assertEquals("id_not_found", missingResult.status)
        assertEquals(2, missingResult.allDetectedStaff.size)
        assertTrue(missingResult.warnings.any { it.contains("UNKNOWN_999") && it.contains("Detected employees") })
    }
}
