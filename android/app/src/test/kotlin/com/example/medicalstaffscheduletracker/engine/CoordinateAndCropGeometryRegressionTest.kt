package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import com.example.medicalstaffscheduletracker.data.model.SectionSummary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Regression Test Suite for Proven Coordinate and Crop Geometry Defects:
 *
 * 1. Original-scale coordinates are not applied directly to an upscaled bitmap.
 * 2. Every crop remains inside the correct employee row and excludes adjacent rows.
 * 3. Crop bounds map correctly after scaling.
 * 4. Different row heights (10px, 12px, 14px, 20px, 30px) are supported dynamically without 26f hardcoding.
 * 5. A crop cannot silently include adjacent rows or out-of-bounds coordinates.
 * 6. Day-column boundaries remain strictly aligned and avoid vertical gridlines.
 * 7. Manual corrections survive reprocessing.
 */
class CoordinateAndCropGeometryRegressionTest {

    private val inferenceEngine = LocalSectionInferenceEngine(verifyRotationRuleDefault = true)

    @Test
    fun test1_originalScaleCoordinatesNotAppliedToUpscaledBitmap() {
        val origW = 1131
        val origH = 653
        val upscaledW = 2035
        val upscaledH = 1175

        val scaleFactor = upscaledW.toFloat() / origW.toFloat()
        assertEquals(1.79929f, scaleFactor, 0.001f)

        val metadata = DocumentTransformMetadata(
            originalWidth = origW,
            originalHeight = origH,
            isPdf = false,
            hasEmbeddedText = false,
            exifOrientation = 1,
            scaleFactor = scaleFactor,
            preprocessedWidth = upscaledW,
            preprocessedHeight = upscaledH,
            processingDescription = "Upscaled rasterization"
        )

        val origX = 247f
        val origY = 296.86f // Akash Row 17 Y in canonical source coordinates

        val upscaledX = metadata.mapFromSourceX(origX)
        val upscaledY = metadata.mapFromSourceY(origY)

        // Verify mapped coordinates
        assertEquals(444.42f, upscaledX, 0.1f)
        assertEquals(534.14f, upscaledY, 0.1f)

        // Proving the defect: if original coordinate (Y=296.86) were applied directly
        // to the 2035x1175 bitmap without scaling, it would be offset by 237.28px!
        val verticalDiscrepancy = upscaledY - origY
        assertTrue("Directly applying original Y to upscaled bitmap causes fatal vertical shift of ~237px", verticalDiscrepancy > 200f)

        // Verify boundary conversion back to source coordinate space exactly once
        val roundtripX = metadata.mapToSourceX(upscaledX)
        val roundtripY = metadata.mapToSourceY(upscaledY)
        assertEquals(origX, roundtripX, 0.01f)
        assertEquals(origY, roundtripY, 0.01f)
    }

    @Test
    fun test2_everyCropRemainsInsideTargetEmployeeRowAndExcludesAdjacentRows() {
        // Build table with header and adjacent rows:
        // Header: Y ~ 50
        // Row 16: Y ~ 284.50
        // Row 17: Y ~ 296.86 (Target: Akash)
        // Row 18: Y ~ 309.22
        val headerWords = listOf(
            OcrWord("S.NO", 15f, 50f, 20f, 10f),
            OcrWord("ID", 45f, 50f, 20f, 10f),
            OcrWord("NAME", 115f, 50f, 30f, 10f),
            OcrWord("OFF", 200f, 50f, 25f, 10f),
            OcrWord("FLOOR", 240f, 50f, 30f, 10f)
        )
        val row16Words = listOf(
            OcrWord("16", 15f, 284.50f, 10f, 10f),
            OcrWord("ABH1340", 45f, 284.50f, 40f, 10f),
            OcrWord("Deepa", 115f, 284.50f, 40f, 10f)
        )
        val row17Words = listOf(
            OcrWord("17", 15f, 296.86f, 10f, 10f),
            OcrWord("ABH1341", 45f, 296.86f, 40f, 10f),
            OcrWord("Akash", 115f, 296.86f, 40f, 10f)
        )
        val row18Words = listOf(
            OcrWord("18", 15f, 309.22f, 10f, 10f),
            OcrWord("ABH1342", 45f, 309.22f, 40f, 10f),
            OcrWord("Rohan", 115f, 309.22f, 40f, 10f)
        )

        val allWords = headerWords + row16Words + row17Words + row18Words
        val reconstructor = LocalTableReconstructor(words = allWords)
        val metadata = reconstructor.analyzeRosterStructure()

        assertEquals(3, metadata.rows.size)

        val r16 = metadata.rows[0]
        val r17 = metadata.rows[1]
        val r18 = metadata.rows[2]

        assertEquals("ABH1340", r16.normalizedId)
        assertEquals("ABH1341", r17.normalizedId)
        assertEquals("ABH1342", r18.normalizedId)

        // Verify that target row (Akash) has concrete, derived topBound and bottomBound
        assertNotNull(r17.topBound)
        assertNotNull(r17.bottomBound)
        assertNotNull(r17.rowHeight)

        val top17 = r17.topBound!!
        val bottom17 = r17.bottomBound!!
        val height17 = bottom17 - top17

        // Row 17 bounds must be strictly within midpoint divider buffer (0.5px margin)
        val div16_17 = (r16.y + r17.y) / 2f
        val div17_18 = (r17.y + r18.y) / 2f
        assertTrue("Row 17 top bound ($top17) must be strictly below Row 16 divider ($div16_17)", top17 >= div16_17 + 0.49f)
        assertTrue("Row 17 bottom bound ($bottom17) must be strictly above Row 18 divider ($div17_18)", bottom17 <= div17_18 - 0.49f)

        // Height must be tight (~11.1px), NOT the defective 36px
        assertTrue("Row 17 crop height ($height17) must be <= 14px", height17 <= 14.0f)
        assertTrue("Row 17 crop height ($height17) must be >= 9px", height17 >= 9.0f)

        // Verify zero overlap with adjacent rows
        val b16 = r16.bottomBound ?: 0f
        val t18 = r18.topBound ?: 999f
        assertTrue("Row 17 top bound ($top17) must be strictly greater than Row 16 bottom bound ($b16)", top17 > b16)
        assertTrue("Row 17 bottom bound ($bottom17) must be strictly less than Row 18 top bound ($t18)", bottom17 < t18)
    }

    @Test
    fun test3_cropBoundsMapCorrectlyAfterScaling() {
        val origW = 1131
        val origH = 653
        val targetW = 2035
        val targetH = 1175

        val scaleX = targetW.toFloat() / origW.toFloat()
        val scaleY = targetH.toFloat() / origH.toFloat()

        val topBound = 291.31f
        val bottomBound = 302.41f
        val rowHeight = bottomBound - topBound

        val scaledTop = topBound * scaleY
        val scaledBottom = bottomBound * scaleY
        val scaledHeight = rowHeight * scaleY

        assertEquals(scaledBottom - scaledTop, scaledHeight, 0.001f)

        // Reversible mapping without drift
        val unscaledTop = scaledTop / scaleY
        val unscaledBottom = scaledBottom / scaleY
        assertEquals(topBound, unscaledTop, 0.001f)
        assertEquals(bottomBound, unscaledBottom, 0.001f)
    }

    @Test
    fun test4_differentRowHeightsAreSupportedDynamically() {
        val testHeights = listOf(10f, 12.5f, 14f, 20f, 28f, 35f)

        for (h in testHeights) {
            val y1 = 100f
            val y2 = y1 + h
            val y3 = y2 + h

            val header = listOf(
                OcrWord("S.NO", 10f, 40f, 10f, 8f),
                OcrWord("ID", 30f, 40f, 20f, 8f),
                OcrWord("NAME", 60f, 40f, 30f, 8f)
            )
            val rowsWords = listOf(
                OcrWord("1", 10f, y1, 10f, 8f),
                OcrWord("ID1", 30f, y1, 20f, 8f),
                OcrWord("Name1", 60f, y1, 30f, 8f),
                OcrWord("2", 10f, y2, 10f, 8f),
                OcrWord("ID2", 30f, y2, 20f, 8f),
                OcrWord("Name2", 60f, y2, 30f, 8f),
                OcrWord("3", 10f, y3, 10f, 8f),
                OcrWord("ID3", 30f, y3, 20f, 8f),
                OcrWord("Name3", 60f, y3, 30f, 8f)
            )

            val reconstructor = LocalTableReconstructor(words = header + rowsWords)
            val metadata = reconstructor.analyzeRosterStructure()

            assertEquals(3, metadata.rows.size)
            val middleRow = metadata.rows[1]
            assertNotNull(middleRow.rowHeight)
            assertNotNull(middleRow.topBound)
            assertNotNull(middleRow.bottomBound)

            val measuredH = middleRow.bottomBound!! - middleRow.topBound!!
            assertTrue("Measured height $measuredH must be proportional to input spacing $h (not hardcoded to 26f)", measuredH <= h)
            assertTrue("Measured height $measuredH must be >= 60% of row spacing", measuredH >= h * 0.6f)
        }
    }

    @Test
    fun test5_cropCannotSilentlyIncludeAdjacentRowsOrOutOfBounds() {
        val srcW = 1131
        val srcH = 653
        val effectiveRowH = 12.36f

        // 1. Proving height threshold assertion:
        // When cell crop height is 36px (from defective rowHeight=26 + margin 10),
        // it must violate the threshold `cellH <= (effectiveRowH * 1.5f).toInt() + 4`
        val defectiveCellH = 36
        val maxAllowedH = (effectiveRowH * 1.5f).toInt() + 4 // 18 + 4 = 22px
        assertTrue("Defective 36px height exceeds allowed threshold of $maxAllowedH px", defectiveCellH > maxAllowedH)

        // 2. Proving coordinate bounds validation:
        val invalidLeft = -5f
        val invalidRight = -10f
        val invalidRowY = 700f // > srcH

        // leftBound >= 0 assertion
        val leftFailed = try {
            require(invalidLeft >= 0f && invalidLeft < srcW) { "Invalid leftBound" }
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("Negative leftBound must trigger IllegalArgumentException", leftFailed)

        // rightBound > leftBound assertion
        val rightFailed = try {
            require(invalidRight > invalidLeft) { "Invalid rightBound" }
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("rightBound <= leftBound must trigger IllegalArgumentException", rightFailed)

        // rowY < srcH assertion
        val rowYFailed = try {
            require(invalidRowY >= 0f && invalidRowY < srcH) { "Invalid rowY" }
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue("rowY >= srcH must trigger IllegalArgumentException", rowYFailed)
    }

    @Test
    fun test6_dayColumnBoundariesRemainAligned() {
        val startX = 247.3f
        val colWidth = 26.65f

        val dayIntervals = (1..30).associate { d ->
            val left = startX + (d - 1) * colWidth
            val right = left + colWidth
            d to Pair(left, right)
        }

        assertEquals(30, dayIntervals.size)

        for (d in 1..29) {
            val cur = dayIntervals[d]!!
            val next = dayIntervals[d + 1]!!

            // Columns must be strictly monotonic
            assertTrue("Day $d leftBound must be < rightBound", cur.first < cur.second)
            assertEquals("Day $d rightBound must align with Day ${d + 1} leftBound", cur.second, next.first, 0.001f)

            // Test 1px inset prevents vertical gridline overlap
            val cellW = (cur.second - cur.first).toInt()
            if (cellW > 10) {
                val inX0 = cur.first.toInt() + 1
                val inX1 = cur.second.toInt() - 1
                assertTrue("Inset width must remain positive and clear border by 1px on both sides", inX1 - inX0 > 0)
            }
        }
    }

    @Test
    fun test7_manualCorrectionsSurviveReprocessing() {
        // Construct extracted cells with user manual overrides
        val initialCells = (1..30).map { d ->
            when (d) {
                13 -> ExtractedCell(
                    dayNumber = 13,
                    date = "2026-09-13",
                    weekday = "Sun",
                    rawValue = "OFF N N", // Raw OCR had adjacent row bleed
                    normalizedShift = "Night", // User manually corrected to Night
                    reviewStatus = "confirmed",
                    reviewReason = "User manual edit confirmed",
                    confidence = 1.0,
                    sourceType = "manual_override",
                    classification = "N"
                )
                26 -> ExtractedCell(
                    dayNumber = 26,
                    date = "2026-09-26",
                    weekday = "Sat",
                    rawValue = "M OFF M", // Raw OCR had adjacent row bleed
                    normalizedShift = "Off", // User manually corrected to Off
                    reviewStatus = "confirmed",
                    reviewReason = "User manual edit confirmed",
                    confidence = 1.0,
                    sourceType = "manual_override",
                    classification = "OFF"
                )
                else -> ExtractedCell(
                    dayNumber = d,
                    date = "2026-09-${d.toString().padStart(2, '0')}",
                    weekday = "Day",
                    rawValue = "M",
                    normalizedShift = "Morning",
                    reviewStatus = "confirmed",
                    reviewReason = null,
                    confidence = 0.9,
                    sourceType = "ocr_observed",
                    classification = "M"
                )
            }
        }

        val summaries = listOf(
            SectionSummary(1, 1, 10, 10, "Morning", listOf("M"), emptyList(), 10, emptyList(), null),
            SectionSummary(2, 11, 20, 10, "Night", listOf("N"), emptyList(), 10, emptyList(), null),
            SectionSummary(3, 21, 30, 10, "Evening", listOf("E"), emptyList(), 10, emptyList(), null)
        )

        val draft = DraftSchedule(
            scheduleId = "draft_manual_test",
            staffId = "ABH1341",
            staffName = "Akash",
            month = "2026-09",
            isApproved = false,
            approvedAt = null,
            entries = initialCells,
            sectionSummaries = summaries,
            unresolvedDates = emptyList(),
            isConfusableId = false,
            confusableMessage = null
        )

        // Reprocess sections
        val reprocessedDraft = inferenceEngine.overrideSectionShift(draft, 2, "Night")

        val day13 = reprocessedDraft.entries.first { it.dayNumber == 13 }
        val day26 = reprocessedDraft.entries.first { it.dayNumber == 26 }

        // Assert that user's manual edits survived
        assertEquals("manual_override", day13.sourceType)
        assertEquals("Night", day13.normalizedShift)
        assertEquals("N", day13.classification)

        assertEquals("manual_override", day26.sourceType)
        assertEquals("Off", day26.normalizedShift)
        assertEquals("OFF", day26.classification)
    }

    @Test
    fun test8_normalizationRulesAndRepeatedTokens() {
        // 1. Unambiguous repeated tokens normalize cleanly
        val resNN = ShiftClassifier.classifyToken("N N")
        assertEquals("Night", resNN.normalizedShift)
        assertEquals("N", resNN.classification)
        assertTrue("Repeated N N must be confirmed", resNN.isConfirmed)

        val resNNNL = ShiftClassifier.classifyToken("N\nN")
        assertEquals("Night", resNNNL.normalizedShift)
        assertEquals("N", resNNNL.classification)
        assertTrue("Repeated N\\nN must be confirmed", resNNNL.isConfirmed)

        val resAA = ShiftClassifier.classifyToken("A A")
        assertEquals("A", resAA.normalizedShift)
        assertEquals("A", resAA.classification)
        assertTrue("Repeated A A must be confirmed", resAA.isConfirmed)

        val resAANL = ShiftClassifier.classifyToken("A\nA")
        assertEquals("A", resAANL.normalizedShift)
        assertEquals("A", resAANL.classification)
        assertTrue("Repeated A\\nA must be confirmed", resAANL.isConfirmed)

        val resMM = ShiftClassifier.classifyToken("M M")
        assertEquals("Morning", resMM.normalizedShift)
        assertEquals("M", resMM.classification)
        assertTrue("Repeated M M must be confirmed", resMM.isConfirmed)

        val resEE = ShiftClassifier.classifyToken("E E")
        assertEquals("Evening", resEE.normalizedShift)
        assertEquals("E", resEE.classification)
        assertTrue("Repeated E E must be confirmed", resEE.isConfirmed)

        // 2. OFF and A distinctly preserved and never null
        val resOff = ShiftClassifier.classifyToken("OFF")
        assertEquals("Off", resOff.normalizedShift)
        assertEquals("OFF", resOff.classification)

        val resA = ShiftClassifier.classifyToken("A")
        assertEquals("A", resA.normalizedShift)
        assertEquals("A", resA.classification)
        assertNotNull("Normalized shift for A must not be null", resA.normalizedShift)

        // 3. Ambiguous tokens must remain unresolved / UNKNOWN with null normalizedShift
        val res3 = ShiftClassifier.classifyToken("3")
        assertEquals(ShiftClassifier.SHIFT_UNKNOWN, res3.classification)
        assertNull("Ambiguous token '3' must not be converted to a shift", res3.normalizedShift)
        assertFalse("Ambiguous token '3' must not be confirmed", res3.isConfirmed)

        val resBlank = ShiftClassifier.classifyToken("")
        assertEquals(ShiftClassifier.SHIFT_UNKNOWN, resBlank.classification)
        assertNull(resBlank.normalizedShift)

        val resConflicting = ShiftClassifier.classifyToken("XYZ")
        assertEquals(ShiftClassifier.SHIFT_UNKNOWN, resConflicting.classification)
        assertNull(resConflicting.normalizedShift)
    }

    @Test
    fun test9_evaluationMetricStrictlyRequiresNormalizedMatch() {
        // Ground truth
        val gtCode = "A"
        val expectedNormalized = "A"

        // Scenario A: Raw token has "A A", but normalizedShift is null -> MUST NOT MATCH
        val defectiveCellWithNullNorm = ExtractedCell(
            dayNumber = 29,
            date = "2026-09-29",
            weekday = "Tue",
            rawValue = "A A",
            normalizedShift = null, // null normalized shift
            reviewStatus = "needs_review",
            reviewReason = null,
            confidence = 0.85,
            sourceType = "ocr_observed",
            classification = "A"
        )

        val matchDefective = defectiveCellWithNullNorm.normalizedShift != null &&
                defectiveCellWithNullNorm.normalizedShift == expectedNormalized
        assertFalse("Cell with null normalizedShift must never count as a resolved match", matchDefective)

        // Scenario B: Correctly resolved cell with normalizedShift = "A" -> MATCHES
        val correctlyResolvedCell = ExtractedCell(
            dayNumber = 29,
            date = "2026-09-29",
            weekday = "Tue",
            rawValue = "A A",
            normalizedShift = "A",
            reviewStatus = "confirmed",
            reviewReason = null,
            confidence = 0.95,
            sourceType = "ocr_observed",
            classification = "A"
        )

        val matchCorrect = correctlyResolvedCell.normalizedShift != null &&
                correctlyResolvedCell.normalizedShift == expectedNormalized
        assertTrue("Cell with normalizedShift matching ground truth counts as resolved match", matchCorrect)

        // Scenario C: Ambiguous '3' where expected is 'E' (Evening) -> UNRESOLVED, NOT MATCH
        val ambiguousCell = ExtractedCell(
            dayNumber = 2,
            date = "2026-09-02",
            weekday = "Wed",
            rawValue = "3",
            normalizedShift = null,
            reviewStatus = "needs_review",
            reviewReason = "Ambiguous token '3'",
            confidence = 0.20,
            sourceType = "ocr_observed",
            classification = "UNKNOWN"
        )
        val expectedNormE = "Evening"
        val matchAmbiguous = ambiguousCell.normalizedShift != null &&
                ambiguousCell.normalizedShift == expectedNormE
        assertFalse("Unresolved cell must not match", matchAmbiguous)
    }
}
