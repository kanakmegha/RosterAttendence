package com.example.medicalstaffscheduletracker.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalTableReconstructorTest {

    @Test
    fun testExactAndConfusableIdMatching() {
        val (exactMatch, exactReason) = IdMatcher.areIdsConfusable("ABHL341", "ABHL341")
        assertTrue(exactMatch)
        assertEquals("exact", exactReason)

        val (confusableMatch, confusableReason) = IdMatcher.areIdsConfusable("ABHL341", "ABH1341")
        assertTrue(confusableMatch)
        assertNotNull(confusableReason)
        assertTrue(confusableReason!!.contains("Visual OCR character confusion"))

        val (mismatch, _) = IdMatcher.areIdsConfusable("ABHL341", "XYZ999")
        assertFalse(mismatch)
    }

    @Test
    fun testTableReconstructionWithShiftParsingAndSafetyRules() {
        // Construct header tokens and employee row tokens
        val words = mutableListOf(
            OcrWord(text = "S.NO", x = 10f, y = 50f, width = 20f, height = 10f),
            OcrWord(text = "ID", x = 40f, y = 50f, width = 30f, height = 10f),
            OcrWord(text = "NAME", x = 110f, y = 50f, width = 40f, height = 10f),
            OcrWord(text = "OFF DAY", x = 210f, y = 50f, width = 35f, height = 10f),
            OcrWord(text = "FLOOR", x = 260f, y = 50f, width = 35f, height = 10f)
        )

        // Add calendar headers for Sept 2026 (Day 1..5)
        val colStartX = 310f
        val colStep = 30f
        val days = listOf("TUE", "WED", "THU", "FRI", "SAT")
        for (i in 0 until 5) {
            val d = i + 1
            words.add(OcrWord(text = days[i], x = colStartX + i * colStep, y = 40f, width = 25f, height = 10f))
            words.add(OcrWord(text = "$d", x = colStartX + i * colStep + 5f, y = 60f, width = 15f, height = 10f))
        }

        // Employee row: Akash (ABH1341 - confusable with ABHL341)
        val rowY = 120f
        words.add(OcrWord(text = "1", x = 15f, y = rowY, width = 10f, height = 10f))
        words.add(OcrWord(text = "ABH1341", x = 45f, y = rowY, width = 40f, height = 10f))
        words.add(OcrWord(text = "Akash", x = 115f, y = rowY, width = 40f, height = 10f))
        words.add(OcrWord(text = "SAT", x = 215f, y = rowY, width = 25f, height = 10f))
        words.add(OcrWord(text = "Floor 2", x = 265f, y = rowY, width = 35f, height = 10f))

        // Shift tokens:
        // Day 1: M (Morning)
        words.add(OcrWord(text = "M", x = colStartX + 5f, y = rowY, width = 12f, height = 10f))
        // Day 2: OFFI (Border artifact -> Off, needs_review)
        words.add(OcrWord(text = "OFFI", x = colStartX + colStep + 3f, y = rowY, width = 20f, height = 10f))
        // Day 3: NN (Concatenated Night -> Night, needs_review)
        words.add(OcrWord(text = "NN", x = colStartX + 2 * colStep + 3f, y = rowY, width = 16f, height = 10f))
        // Day 4: A (Non-target shift code -> normalized_shift MUST BE NULL, needs_review)
        words.add(OcrWord(text = "A", x = colStartX + 3 * colStep + 5f, y = rowY, width = 12f, height = 10f))
        // Day 5: blank / unreadable (MUST NOT be treated as Off)

        val reconstructor = LocalTableReconstructor(
            words = words,
            monthStr = "2026-09",
            requestedStaffId = "ABHL341"
        )
        val result = reconstructor.reconstruct()

        // 1. Confusable ID check
        assertTrue("Expected confusable ID flag for ABH1341 vs ABHL341", result.isConfusableId)
        assertEquals("ABH1341", result.matchedStaffId)
        assertEquals("Akash", result.staffName)

        val entries = result.entries
        assertEquals(30, entries.size) // Sept 2026 has 30 days

        // Day 1: M -> Morning
        val day1 = entries[0]
        assertEquals("M", day1.rawValue)
        assertEquals("Morning", day1.normalizedShift)

        // Day 2: OFFI -> Off with needs_review
        val day2 = entries[1]
        assertEquals("OFFI", day2.rawValue)
        assertEquals("Off", day2.normalizedShift)
        assertEquals("needs_review", day2.status)

        // Day 3: NN -> Night with needs_review
        val day3 = entries[2]
        assertEquals("NN", day3.rawValue)
        assertEquals("Night", day3.normalizedShift)
        assertEquals("needs_review", day3.status)

        // Day 4: A -> rawValue is "A", normalizedShift MUST BE NULL!
        val day4 = entries[3]
        assertEquals("A", day4.rawValue)
        assertNull("Shift 'A' must not be converted to a known shift", day4.normalizedShift)
        assertEquals("needs_review", day4.status)

        // Day 5: blank -> MUST NOT BE TREATED AS OFF
        val day5 = entries[4]
        assertNull(day5.rawValue)
        assertNull("Blank cell must NEVER be treated as Off in table reconstruction", day5.normalizedShift)
        assertEquals("missing", day5.status)
    }

    @Test
    fun testRealRosterOcrOutputReconstructsAkashAndAllStaffRows() {
        val cacheFile = File("/Users/skanakmegha/.gemini/antigravity-ide/brain/b7496765-c5cc-4573-b241-99708491753a/scratch/roster_ocr_cache.json")
        if (!cacheFile.exists()) return

        val jsonStr = cacheFile.readText()
        val wordsSection = if (jsonStr.contains("\"words\": [")) {
            jsonStr.substring(jsonStr.indexOf("\"words\": ["))
        } else jsonStr
        val wordBlockRegex = Regex("""\{[^{}]*"text":\s*"([^"]*)"[^{}]*\}""")
        val realWords = mutableListOf<OcrWord>()
        for (match in wordBlockRegex.findAll(wordsSection)) {
            val block = match.value
            val text = match.groupValues[1]
            val x = Regex(""""x":\s*([0-9.]+)""").find(block)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val y = Regex(""""y":\s*([0-9.]+)""").find(block)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val width = Regex(""""width":\s*([0-9.]+)""").find(block)?.groupValues?.get(1)?.toFloatOrNull() ?: 10f
            val height = Regex(""""height":\s*([0-9.]+)""").find(block)?.groupValues?.get(1)?.toFloatOrNull() ?: 10f
            val confidence = Regex(""""confidence":\s*([0-9.]+)""").find(block)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.95f
            realWords.add(OcrWord(text = text, x = x, y = y, width = width, height = height, confidence = confidence))
        }

        // Test at natural resolution (1131x653)
        val reconstructor = LocalTableReconstructor(words = realWords)
        val metadata = reconstructor.analyzeRosterStructure()

        assertTrue("Roster must detect at least 30 staff members", metadata.detectedStaff.size >= 30)
        assertEquals("2026-09", metadata.monthInfo.monthStr)

        // Target search for ABHL341 (OCR-misread as ABH1341)
        val targetResult = reconstructor.reconstructForStaff(metadata, "ABHL341")
        assertEquals("ABH1341", targetResult.matchedStaffId)
        assertTrue("ABH1341 vs ABHL341 must be flagged as confusable match", targetResult.isConfusableId)
        assertEquals("AKASH", targetResult.staffName)
        assertEquals(30, targetResult.entries.size)

        // Verify shift observations for Akash
        val nonBlankShifts = targetResult.entries.filter { it.normalizedShift != null || it.rawValue != null }
        assertTrue("Akash must have multiple shift observations", nonBlankShifts.size >= 8)

        // Test at scaled resolution (2.5x simulating high-res phone camera capture)
        val scaledWords = realWords.map {
            OcrWord(
                text = it.text,
                x = it.x * 2.5f,
                y = it.y * 2.5f,
                width = it.width * 2.5f,
                height = it.height * 2.5f,
                confidence = it.confidence
            )
        }
        val scaledReconstructor = LocalTableReconstructor(words = scaledWords)
        val scaledMetadata = scaledReconstructor.analyzeRosterStructure()
        assertTrue("Scaled roster must still detect at least 30 staff members (was ${scaledMetadata.detectedStaff.size})", scaledMetadata.detectedStaff.size >= 30)
        val scaledTarget = scaledReconstructor.reconstructForStaff(scaledMetadata, "ABHL341")
        assertEquals("ABH1341", scaledTarget.matchedStaffId)
        assertEquals("AKASH", scaledTarget.staffName)
    }

    @Test
    fun testReconstructionWithMissingOptionalColumns() {
        // Document has ONLY S.No, ID, Name (No Floor column, No Off Day column)
        val words = mutableListOf(
            OcrWord(text = "S.NO", x = 10f, y = 30f, width = 20f, height = 10f),
            OcrWord(text = "ID", x = 40f, y = 30f, width = 30f, height = 10f),
            OcrWord(text = "NAME", x = 90f, y = 30f, width = 50f, height = 10f),
            OcrWord(text = "SEPTEMBER 2026", x = 100f, y = 10f, width = 120f, height = 12f)
        )

        // Calendar header (Starts at x=180)
        val calStartX = 180f
        for (d in 1..5) {
            words.add(OcrWord(text = "DAY_$d", x = calStartX + (d - 1) * 30f, y = 30f, width = 25f, height = 10f))
        }

        // Row 1: Priya
        words.add(OcrWord(text = "1", x = 10f, y = 60f, width = 15f, height = 10f))
        words.add(OcrWord(text = "ABH2044", x = 40f, y = 60f, width = 35f, height = 10f))
        words.add(OcrWord(text = "Priya", x = 90f, y = 60f, width = 40f, height = 10f))
        words.add(OcrWord(text = "E", x = calStartX + 5f, y = 60f, width = 12f, height = 10f))

        val reconstructor = LocalTableReconstructor(words = words)
        val metadata = reconstructor.analyzeRosterStructure()

        assertEquals("Missing optional columns must still detect employee rows", 1, metadata.detectedStaff.size)
        assertEquals("ABH2044", metadata.detectedStaff[0].rawId)
        assertEquals("Priya", metadata.detectedStaff[0].name)
        assertNull(metadata.detectedStaff[0].floor)
        assertNull(metadata.detectedStaff[0].weeklyOffDay)
    }

    @Test
    fun testReconstructionWithDifferentColumnOrders() {
        // Layout where Name comes before ID: S.No | Name | ID
        val words = mutableListOf(
            OcrWord(text = "SR", x = 10f, y = 30f, width = 20f, height = 10f),
            OcrWord(text = "STAFF NAME", x = 40f, y = 30f, width = 60f, height = 10f),
            OcrWord(text = "EMP ID", x = 120f, y = 30f, width = 40f, height = 10f),
            OcrWord(text = "OCTOBER 2026", x = 100f, y = 10f, width = 100f, height = 12f)
        )

        val calStartX = 200f
        words.add(OcrWord(text = "THU", x = calStartX, y = 30f, width = 25f, height = 10f))
        words.add(OcrWord(text = "1", x = calStartX + 5f, y = 45f, width = 15f, height = 10f))

        // Row: Dr. Sharma, ID: MED8821
        words.add(OcrWord(text = "1", x = 10f, y = 80f, width = 15f, height = 10f))
        words.add(OcrWord(text = "Dr. Sharma", x = 40f, y = 80f, width = 60f, height = 10f))
        words.add(OcrWord(text = "MED8821", x = 120f, y = 80f, width = 45f, height = 10f))
        words.add(OcrWord(text = "N", x = calStartX + 5f, y = 80f, width = 15f, height = 10f))

        val reconstructor = LocalTableReconstructor(words = words)
        val metadata = reconstructor.analyzeRosterStructure()

        assertEquals(1, metadata.detectedStaff.size)
        val staff = metadata.detectedStaff[0]
        assertEquals("MED8821", staff.rawId)
        assertTrue(staff.name.contains("Dr. Sharma"))
    }

    @Test
    fun testMergedHeadersHandling() {
        // Calendar header with merged days: "MONTUE"
        val words = mutableListOf(
            OcrWord(text = "ID", x = 20f, y = 30f, width = 30f, height = 10f),
            OcrWord(text = "NAME", x = 70f, y = 30f, width = 40f, height = 10f),
            OcrWord(text = "SEPTEMBER 2026", x = 50f, y = 10f, width = 100f, height = 10f)
        )

        val calStartX = 150f
        // Day 1: TUE at 150
        words.add(OcrWord(text = "TUE", x = calStartX, y = 30f, width = 25f, height = 10f))
        words.add(OcrWord(text = "1", x = calStartX + 5f, y = 45f, width = 15f, height = 10f))
        // Day 7 & 8: Merged "MONTUE" header
        words.add(OcrWord(text = "MONTUE", x = calStartX + 180f, y = 30f, width = 55f, height = 10f))

        words.add(OcrWord(text = "ABHL101", x = 20f, y = 80f, width = 35f, height = 10f))
        words.add(OcrWord(text = "Karan", x = 70f, y = 80f, width = 35f, height = 10f))
        words.add(OcrWord(text = "M", x = calStartX + 5f, y = 80f, width = 12f, height = 10f))

        val reconstructor = LocalTableReconstructor(words = words)
        val metadata = reconstructor.analyzeRosterStructure()

        // Should flag warning for merged header without crashing
        assertTrue("Warnings should mention merged header", metadata.warnings.any { it.contains("merged header") })
        assertEquals(1, metadata.detectedStaff.size)
    }

    @Test
    fun testOcrSpaceEngine2OutputReconstruction() {
        // Build sample words modeled after OCR.space Engine 2 output on roster.jpeg
        val words = mutableListOf(
            OcrWord(text = "DUTY ROSTER FOR SEPTEMBER 2026", x = 150f, y = 50f, width = 350f, height = 25f),
            OcrWord(text = "S.no", x = 120f, y = 145f, width = 35f, height = 15f),
            OcrWord(text = "ID", x = 175f, y = 145f, width = 25f, height = 15f),
            OcrWord(text = "NAME", x = 240f, y = 145f, width = 50f, height = 15f),
            OcrWord(text = "OFF", x = 320f, y = 145f, width = 30f, height = 15f),
            OcrWord(text = "DAY", x = 355f, y = 145f, width = 30f, height = 15f),
            OcrWord(text = "FLOOR", x = 405f, y = 145f, width = 45f, height = 15f)
        )

        // 30 days of September 2026
        val days = listOf("TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON")
        val colStartX = 480f
        val colWidth = 35f
        for (i in 1..30) {
            val weekday = days[(i - 1) % 7]
            val x = colStartX + (i - 1) * colWidth
            words.add(OcrWord(text = weekday, x = x, y = 135f, width = 25f, height = 12f))
            words.add(OcrWord(text = "$i", x = x + 5f, y = 150f, width = 15f, height = 12f))
        }

        // Employee 1: Nand Kishore (ABHL248)
        val y1 = 200f
        words.add(OcrWord(text = "1", x = 125f, y = y1, width = 15f, height = 15f))
        words.add(OcrWord(text = "ABHL248", x = 170f, y = y1, width = 60f, height = 15f))
        words.add(OcrWord(text = "Nand", x = 240f, y = y1, width = 35f, height = 15f))
        words.add(OcrWord(text = "Kishore", x = 280f, y = y1, width = 45f, height = 15f))
        words.add(OcrWord(text = "THU", x = 330f, y = y1, width = 30f, height = 15f))
        words.add(OcrWord(text = "3rd", x = 405f, y = y1, width = 25f, height = 15f))
        words.add(OcrWord(text = "floor", x = 435f, y = y1, width = 35f, height = 15f))

        // Employee 2: Akash (ABHL34 - confusable with ABHL341)
        val y2 = 250f
        words.add(OcrWord(text = "2", x = 125f, y = y2, width = 15f, height = 15f))
        words.add(OcrWord(text = "ABHL34", x = 170f, y = y2, width = 55f, height = 15f))
        words.add(OcrWord(text = "Akash", x = 240f, y = y2, width = 45f, height = 15f))
        words.add(OcrWord(text = "TUE", x = 330f, y = y2, width = 30f, height = 15f))
        words.add(OcrWord(text = "4th", x = 405f, y = y2, width = 25f, height = 15f))
        words.add(OcrWord(text = "floor", x = 435f, y = y2, width = 35f, height = 15f))

        // Shift tokens for Akash:
        // Day 1 (Tue): OFF
        words.add(OcrWord(text = "OFF", x = colStartX + 5f, y = y2, width = 25f, height = 15f))
        // Day 2 (Wed): E
        words.add(OcrWord(text = "E", x = colStartX + colWidth + 8f, y = y2, width = 15f, height = 15f))
        // Day 3 (Thu): E
        words.add(OcrWord(text = "E", x = colStartX + 2 * colWidth + 8f, y = y2, width = 15f, height = 15f))

        val reconstructor = LocalTableReconstructor(words = words)
        val metadata = reconstructor.analyzeRosterStructure()

        assertEquals("2026-09", metadata.monthInfo.monthStr)
        assertTrue(metadata.monthInfo.isConfident)
        assertEquals(2, metadata.detectedStaff.size)

        val staff2 = metadata.detectedStaff.firstOrNull { it.name == "Akash" }
        assertNotNull("Akash must be detected", staff2)
        assertEquals("2", staff2!!.serialNumber)
        assertEquals("ABHL34", staff2.rawId)
        assertEquals("TUE", staff2.weeklyOffDay)
        assertTrue(staff2.floor!!.contains("4th"))

        // Test reconstructForStaff with target ID ABHL341
        val result = reconstructor.reconstructForStaff(metadata, "ABHL341")
        assertTrue(result.isConfusableId)
        assertEquals("ABHL34", result.matchedStaffId)
        assertEquals("Akash", result.staffName)
        assertEquals(30, result.entries.size)

        // Day 1 is OFF
        val d1 = result.entries[0]
        assertEquals(1, d1.dayNumber)
        assertEquals("OFF", d1.rawValue)
        assertEquals("Off", d1.normalizedShift)

        // Day 2 is Evening
        val d2 = result.entries[1]
        assertEquals(2, d2.dayNumber)
        assertEquals("E", d2.rawValue)
        assertEquals("Evening", d2.normalizedShift)
    }
}
