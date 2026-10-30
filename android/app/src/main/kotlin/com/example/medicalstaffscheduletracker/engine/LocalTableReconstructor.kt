package com.example.medicalstaffscheduletracker.engine

import android.util.Log
import java.util.Calendar
import java.util.Locale

data class ReconstructedRow(
    val serialNumber: String?,
    val rawId: String?,
    val normalizedId: String?,
    val name: String?,
    val weeklyOffDay: String?,
    val floor: String?,
    val y: Float,
    val shiftWords: List<OcrWord>,
    val rowHeight: Float? = null,
    val topBound: Float? = null,
    val bottomBound: Float? = null
)

data class ReconstructedCell(
    val dayNumber: Int,
    val date: String,
    val weekday: String,
    val rawValue: String?,
    val normalizedShift: String?,
    val status: String,
    val confidence: Double?,
    val provenance: String?,
    val reviewReason: String?,
    val anchorType: String,
    val anchorConfidence: String,
    val ocrBoundingBox: String? = null,
    val matchDistance: Float? = null,
    val ocrConfidence: Double? = null,
    val classification: String? = null
)

data class DetectedStaffMember(
    val serialNumber: String?,
    val rawId: String,
    val normalizedId: String,
    val name: String,
    val floor: String?,
    val weeklyOffDay: String?,
    val rowY: Float,
    val rowHeight: Float? = null,
    val topBound: Float? = null,
    val bottomBound: Float? = null
)

data class DetectedMonthInfo(
    val year: Int,
    val month: Int,
    val monthStr: String, // "YYYY-MM"
    val displayName: String, // "September 2026"
    val isConfident: Boolean,
    val detectedFrom: String
)

data class ColumnDiagnostic(
    val dayNumber: Int,
    val weekday: String,
    val centerX: Float,
    val anchorType: String,
    val anchorConfidence: String
)

data class TableMetadata(
    val monthInfo: DetectedMonthInfo,
    val metaBounds: Map<String, Float>,
    val dayIntervals: Map<Int, Pair<Float, Float>>,
    val dayDiagnostics: Map<Int, ColumnDiagnostic>,
    val rows: List<ReconstructedRow>,
    val detectedStaff: List<DetectedStaffMember>,
    val warnings: List<String>,
    val candidateRows: List<CandidateRowEvaluation> = emptyList(),
    val rawText: String = "",
    val providerName: String = "OCR.space",
    val report: RosterDiagnosticReport? = null,
    val sourceImageWidth: Int = 0,
    val sourceImageHeight: Int = 0,
    val coordinateSystem: String = "CANONICAL_SOURCE_PIXELS"
)

data class TableReconstructionResult(
    val rosterMonth: String,
    val requestedStaffId: String?,
    val matchedStaffId: String?,
    val matchReason: String?,
    val staffName: String?,
    val weeklyOffDay: String?,
    val floorDepartment: String?,
    val status: String,
    val isConfusableId: Boolean,
    val confusableMessage: String?,
    val entries: List<ReconstructedCell>,
    val allDetectedStaff: List<DetectedStaffMember> = emptyList(),
    val warnings: List<String> = emptyList(),
    val allRows: List<ReconstructedRow> = emptyList()
)

object IdMatcher {
    fun normalizeId(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.US)
    }

    fun areIdsConfusable(targetId: String, candidateId: String): Pair<Boolean, String?> {
        val t = normalizeId(targetId)
        val c = normalizeId(candidateId)
        if (t.isEmpty() || c.isEmpty()) return false to null

        if (t == c) return true to "exact"

        fun canonicalConfusables(s: String): String {
            return s.replace("L", "1")
                .replace("I", "1")
                .replace("|", "1")
                .replace("O", "0")
                .replace("B", "8")
        }

        if (t.length == c.length) {
            if (canonicalConfusables(t) == canonicalConfusables(c)) {
                return true to "Visual OCR character confusion between '$candidateId' and '$targetId' (e.g. L vs 1 or O vs 0)"
            }
        } else {
            val minLen = minOf(t.length, c.length)
            val maxLen = maxOf(t.length, c.length)
            if (minLen >= 5 && (maxLen - minLen <= 2)) {
                val tPrefix = canonicalConfusables(t.substring(0, minLen))
                val cPrefix = canonicalConfusables(c.substring(0, minLen))
                if (tPrefix == cPrefix) {
                    return true to "Truncated / partial ID match between '$candidateId' and '$targetId'"
                }
            }
        }

        return false to null
    }
}

class LocalTableReconstructor(
    words: List<OcrWord> = emptyList(),
    private val monthStr: String? = null,
    private val requestedStaffId: String? = null
) {
    private val words = words.filter { it.text.trim().isNotEmpty() }

    private val weekdayAbbrs = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    private val defaultShiftLegend = mapOf(
        "M" to "Morning",
        "E" to "Evening",
        "N" to "Night",
        "OFF" to "Off"
    )

    companion object {
        private const val TAG = "MedicalScheduleTracker"

        val MONTH_MAP = mapOf(
            "JANUARY" to 1, "FEBRUARY" to 2, "MARCH" to 3, "APRIL" to 4,
            "MAY" to 5, "JUNE" to 6, "JULY" to 7, "AUGUST" to 8,
            "SEPTEMBER" to 9, "OCTOBER" to 10, "NOVEMBER" to 11, "DECEMBER" to 12,
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4,
            "JUN" to 6, "JUL" to 7, "AUG" to 8, "SEP" to 9, "SEPT" to 9,
            "OCT" to 10, "NOV" to 11, "DEC" to 12
        )

        fun detectMonthFromWords(words: List<OcrWord>): DetectedMonthInfo {
            if (words.isEmpty()) {
                val cal = Calendar.getInstance()
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                return DetectedMonthInfo(y, m, String.format(Locale.US, "%04d-%02d", y, m), "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $y", false, "Empty word list fallback")
            }

            val minY = words.minOf { it.y }
            val maxY = words.maxOf { it.y }
            val docHeight = maxOf(50f, maxY - minY)
            val titleThresholdY = minY + docHeight * 0.25f

            val candidateWords = words.filter { it.y <= titleThresholdY }
            val headerText = (if (candidateWords.size >= 3) candidateWords else words.take(50))
                .joinToString(" ") { it.text.uppercase(Locale.US) }

            // 1. Check for Month name and 4-digit year (e.g. "SEPTEMBER 2026", "SEPTEMBER:-2026")
            for ((name, mNum) in MONTH_MAP) {
                val pattern = Regex("\\b$name\\b[^0-9]*(\\d{4})", RegexOption.IGNORE_CASE)
                val match = pattern.find(headerText)
                if (match != null) {
                    val year = match.groupValues[1].toInt()
                    val mStr = String.format(Locale.US, "%04d-%02d", year, mNum)
                    val cal = Calendar.getInstance()
                    cal.set(year, mNum - 1, 1)
                    val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $year"
                    return DetectedMonthInfo(year, mNum, mStr, disp, true, "Header anchor '$name $year'")
                }
            }

            // 2. Check for month name without explicit year
            for ((name, mNum) in MONTH_MAP) {
                if (Regex("\\b$name\\b", RegexOption.IGNORE_CASE).containsMatchIn(headerText)) {
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                    val mStr = String.format(Locale.US, "%04d-%02d", currentYear, mNum)
                    val cal = Calendar.getInstance()
                    cal.set(currentYear, mNum - 1, 1)
                    val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $currentYear"
                    return DetectedMonthInfo(currentYear, mNum, mStr, disp, false, "Found month '$name', year requires confirmation")
                }
            }

            // Fallback: Current Month (unconfident, prompts user for confirmation)
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val mStr = String.format(Locale.US, "%04d-%02d", year, month)
            val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $year"
            return DetectedMonthInfo(year, month, mStr, disp, false, "Defaulted to current calendar month (unanchored)")
        }
    }

    /**
     * Phase 1: Analyzes roster geometry, extracts all staff rows, and detects date columns.
     * Truly adaptive and resolution-independent.
     */
    fun analyzeRosterStructure(overrideMonthStr: String? = null): TableMetadata {
        val warnings = mutableListOf<String>()
        val monthInfo = if (!overrideMonthStr.isNullOrBlank()) {
            val parts = overrideMonthStr.split("-")
            val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 9
            val cal = Calendar.getInstance()
            cal.set(y, m - 1, 1)
            val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $y"
            DetectedMonthInfo(y, m, overrideMonthStr, disp, true, "User confirmed month")
        } else if (!monthStr.isNullOrBlank()) {
            val parts = monthStr.split("-")
            val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 9
            val cal = Calendar.getInstance()
            cal.set(y, m - 1, 1)
            val disp = "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)} $y"
            DetectedMonthInfo(y, m, monthStr, disp, true, "Provided month")
        } else {
            detectMonthFromWords(words)
        }

        if (words.isEmpty()) {
            return TableMetadata(
                monthInfo = monthInfo,
                metaBounds = emptyMap(),
                dayIntervals = emptyMap(),
                dayDiagnostics = emptyMap(),
                rows = emptyList(),
                detectedStaff = emptyList(),
                warnings = listOf("No text detected in document")
            )
        }

        // 1. Detect dynamic table geometry (header band, calStartX, metadata columns)
        val geometry = detectAdaptiveTableGeometry()
        warnings.addAll(geometry.warnings)

        // 2. Detect date columns within calendar zone
        val (dayIntervals, dayDiagnostics, colWarnings) = detectDateColumns(
            calStartX = geometry.calendarStartX,
            calEndX = geometry.calendarEndX,
            headerTop = geometry.headerTop,
            headerBottom = geometry.headerBottom,
            year = monthInfo.year,
            month = monthInfo.month
        )
        warnings.addAll(colWarnings)

        // 3. Cluster and extract employee rows from the table body
        val (rows, candidateRows) = detectEmployeeRows(geometry)

        // 4. Construct list of recognized staff members for UI selection
        val detectedStaffList = rows.mapIndexedNotNull { idx, r ->
            val id = r.rawId ?: r.normalizedId
            val name = r.name ?: "Employee ${idx + 1}"
            if (!id.isNullOrBlank() || !r.name.isNullOrBlank()) {
                DetectedStaffMember(
                    serialNumber = r.serialNumber,
                    rawId = id ?: "ROW_${idx + 1}",
                    normalizedId = r.normalizedId ?: IdMatcher.normalizeId(id ?: "ROW_${idx + 1}"),
                    name = name,
                    floor = r.floor,
                    weeklyOffDay = r.weeklyOffDay,
                    rowY = r.y,
                    rowHeight = r.rowHeight,
                    topBound = r.topBound,
                    bottomBound = r.bottomBound
                )
            } else null
        }

        val estimatedW = words.maxOfOrNull { it.x + it.width }?.toInt() ?: 0
        val estimatedH = words.maxOfOrNull { it.y + it.height }?.toInt() ?: 0

        return TableMetadata(
            monthInfo = monthInfo,
            metaBounds = geometry.metaBounds,
            dayIntervals = dayIntervals,
            dayDiagnostics = dayDiagnostics,
            rows = rows,
            detectedStaff = detectedStaffList,
            warnings = warnings,
            candidateRows = candidateRows,
            sourceImageWidth = estimatedW,
            sourceImageHeight = estimatedH,
            coordinateSystem = "CANONICAL_SOURCE_PIXELS"
        )
    }

    /**
     * Phase 2: Builds schedule for a specific selected staff member from the analyzed metadata.
     */
    fun reconstructForStaff(metadata: TableMetadata, targetStaffId: String): TableReconstructionResult {
        val warnings = metadata.warnings.toMutableList()
        val targetIdNorm = IdMatcher.normalizeId(targetStaffId)

        var targetRow: ReconstructedRow? = null
        var matchedId: String? = null
        var matchReason = "exact"
        var overallStatus = "confirmed"
        var isConfusable = false
        var confusableMsg: String? = null

        // 1. Exact match by normalized ID
        for (r in metadata.rows) {
            if (r.normalizedId != null && r.normalizedId == targetIdNorm) {
                targetRow = r
                matchedId = r.rawId ?: targetStaffId
                break
            }
        }

        // 2. Exact match by Name if user clicked name
        if (targetRow == null) {
            for (r in metadata.rows) {
                if (r.name != null && r.name.equals(targetStaffId, ignoreCase = true)) {
                    targetRow = r
                    matchedId = r.rawId ?: r.name
                    break
                }
            }
        }

        // 3. Confusable visual OCR match (e.g. ABHL341 vs ABH1341)
        if (targetRow == null) {
            for (r in metadata.rows) {
                if (r.normalizedId != null) {
                    val (confusable, reason) = IdMatcher.areIdsConfusable(targetStaffId, r.normalizedId)
                    if (confusable) {
                        targetRow = r
                        matchedId = r.rawId
                        matchReason = reason ?: "confusable_match"
                        overallStatus = "needs_review"
                        isConfusable = true
                        confusableMsg = "Exact Staff ID '$targetStaffId' was not found. Matched OCR candidate '$matchedId' ($reason) for staff '${r.name}'. Schedule held for human review."
                        warnings.add(confusableMsg)
                        break
                    }
                }
            }
        }

        // 4. Fallback search by serial number (e.g. user selected "ROW_1" or "1")
        if (targetRow == null) {
            val cleanTarget = targetStaffId.replace("ROW_", "")
            for (r in metadata.rows) {
                if (r.serialNumber == cleanTarget || r.rawId == targetStaffId) {
                    targetRow = r
                    matchedId = r.rawId ?: "STAFF_${r.serialNumber ?: cleanTarget}"
                    break
                }
            }
        }

        // 5. Fallback for manual row selection (e.g. "MANUAL_ROW_297" or user-selected coordinate)
        if (targetRow == null && targetStaffId.contains("MANUAL_ROW_")) {
            val manualY = targetStaffId.substringAfter("MANUAL_ROW_").toFloatOrNull() ?: 297f
            val nearbyWords = words.filter { Math.abs(it.cy - manualY) <= 18f }
            targetRow = ReconstructedRow(
                serialNumber = null,
                rawId = targetStaffId,
                normalizedId = targetStaffId,
                name = "Manual Selected Row (Y=${manualY.toInt()})",
                floor = null,
                weeklyOffDay = null,
                y = manualY,
                shiftWords = nearbyWords
            )
            matchedId = targetStaffId
            matchReason = "manual_row_selection"
            overallStatus = "needs_review"
        }

        if (targetRow == null) {
            val detectedIds = metadata.detectedStaff.map { "${it.rawId} (${it.name})" }
            val notFoundMsg = "Staff ID '$targetStaffId' was not found in the roster table. Detected employees: $detectedIds"
            warnings.add(notFoundMsg)
            return TableReconstructionResult(
                rosterMonth = metadata.monthInfo.monthStr,
                requestedStaffId = targetStaffId,
                matchedStaffId = null,
                matchReason = null,
                staffName = null,
                weeklyOffDay = null,
                floorDepartment = null,
                status = "id_not_found",
                isConfusableId = false,
                confusableMessage = notFoundMsg,
                entries = emptyList(),
                allDetectedStaff = metadata.detectedStaff,
                warnings = warnings,
                allRows = metadata.rows
            )
        }

        val cal = Calendar.getInstance()
        cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, 1)
        val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val entries = mutableListOf<ReconstructedCell>()
        for (d in 1..numDays) {
            val (leftBound, rightBound) = metadata.dayIntervals[d] ?: (0f to 0f)
            val diag = metadata.dayDiagnostics[d] ?: ColumnDiagnostic(d, "", 0f, "unanchored", "unanchored")

            cal.set(metadata.monthInfo.year, metadata.monthInfo.month - 1, d)
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", metadata.monthInfo.year, metadata.monthInfo.month, d)
            val weekdayName = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: ""

            val colWords = mutableListOf<OcrWord>()
            for (w in targetRow.shiftWords) {
                val wx0 = w.x
                val wx1 = w.x + w.width
                if (w.cx in leftBound..<rightBound) {
                    colWords.add(w)
                } else if (w.width >= 20f && w.text.trim().length >= 2) {
                    val overlap = minOf(wx1, rightBound) - maxOf(wx0, leftBound)
                    if (overlap >= w.width * 0.35f) {
                        colWords.add(w)
                    }
                }
            }
            colWords.sortBy { it.x }

            val parsed = parseShiftTokens(colWords, diag)
            var cellStatus = parsed.status
            var cellReviewReason = parsed.reviewReason

            if (matchReason != "exact" && cellStatus == "confirmed") {
                cellStatus = "needs_review"
                cellReviewReason = "Schedule subject to Staff ID confusion ($matchReason)"
            }

            if (cellStatus == "needs_review" && overallStatus == "confirmed") {
                overallStatus = "needs_review"
            }

            entries.add(
                ReconstructedCell(
                    dayNumber = d,
                    date = dateStr,
                    weekday = weekdayName,
                    rawValue = parsed.rawValue,
                    normalizedShift = parsed.normalizedShift,
                    status = cellStatus,
                    confidence = parsed.confidence,
                    provenance = parsed.provenance,
                    reviewReason = cellReviewReason,
                    anchorType = diag.anchorType,
                    anchorConfidence = diag.anchorConfidence,
                    ocrBoundingBox = parsed.ocrBoundingBox,
                    matchDistance = parsed.matchDistance,
                    ocrConfidence = parsed.ocrConfidence,
                    classification = parsed.classification
                )
            )
        }

        return TableReconstructionResult(
            rosterMonth = metadata.monthInfo.monthStr,
            requestedStaffId = targetStaffId,
            matchedStaffId = matchedId,
            matchReason = matchReason,
            staffName = targetRow.name,
            weeklyOffDay = targetRow.weeklyOffDay,
            floorDepartment = targetRow.floor,
            status = overallStatus,
            isConfusableId = isConfusable,
            confusableMessage = confusableMsg,
            entries = entries,
            allDetectedStaff = metadata.detectedStaff,
            warnings = warnings,
            allRows = metadata.rows
        )
    }

    /**
     * Backward-compatible single-pass reconstruct method.
     */
    fun reconstruct(): TableReconstructionResult {
        val meta = analyzeRosterStructure()
        val targetId = requestedStaffId ?: meta.detectedStaff.firstOrNull()?.rawId ?: "STAFF_01"
        return reconstructForStaff(meta, targetId)
    }

    data class MetaColumnInterval(
        val type: String,
        val startX: Float,
        val endX: Float
    )

    private data class AdaptiveGeometry(
        val headerTop: Float,
        val headerBottom: Float,
        val calendarStartX: Float,
        val calendarEndX: Float,
        val metaBounds: Map<String, Float>,
        val metaColumns: List<MetaColumnInterval>,
        val medianWordHeight: Float,
        val warnings: List<String>
    )

    /**
     * Dynamically detects the table header band, calendar boundary, and metadata columns
     * without any hardcoded pixel boundaries.
     */
    private fun detectAdaptiveTableGeometry(): AdaptiveGeometry {
        val warnings = mutableListOf<String>()

        val minY = words.minOf { it.y }
        val maxY = words.maxOf { it.y + it.height }
        val minX = words.minOf { it.x }
        val maxX = words.maxOf { it.x + it.width }
        val docHeight = maxOf(50f, maxY - minY)
        val docWidth = maxOf(100f, maxX - minX)

        // Calculate median word height
        val validHeights = words.map { it.height }.filter { it > 0f }.sorted()
        val medianH = if (validHeights.isNotEmpty()) validHeights[validHeights.size / 2] else 12f

        // 1. Identify candidate weekday header tokens across the top section of the document
        val topZoneThresholdY = maxOf(minY + 250f, minY + docHeight * 0.40f)
        val weekdayWords = mutableListOf<OcrWord>()
        for (w in words.filter { it.y <= topZoneThresholdY }) {
            val clean = w.text.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
            if (clean in weekdayAbbrs || clean == "MONTUE" || (clean.length == 6 && weekdayAbbrs.any { clean.startsWith(it) })) {
                weekdayWords.add(w)
            }
        }

        // Group weekday words into horizontal lines by Y proximity
        val weekdayClusters = mutableListOf<MutableList<OcrWord>>()
        for (w in weekdayWords.sortedBy { it.cy }) {
            var matched: MutableList<OcrWord>? = null
            for (c in weekdayClusters) {
                val avgCy = c.sumOf { it.cy.toDouble() } / c.size
                if (Math.abs(avgCy - w.cy) <= medianH * 1.2f) {
                    matched = c
                    break
                }
            }
            if (matched != null) matched.add(w)
            else weekdayClusters.add(mutableListOf(w))
        }

        val bestWeekdayCluster = weekdayClusters.filter { it.size >= 2 }.maxByOrNull { it.size }

        // Fallback: horizontal sequence of day numbers 1..31 in top zone (requires at least 3 numbers)
        val numberWords = words.filter {
            val num = it.text.trim().toIntOrNull()
            num != null && num in 1..31 && it.y <= topZoneThresholdY
        }
        val numClusters = mutableListOf<MutableList<OcrWord>>()
        for (w in numberWords.sortedBy { it.cy }) {
            var matched: MutableList<OcrWord>? = null
            for (c in numClusters) {
                val avgCy = c.sumOf { it.cy.toDouble() } / c.size
                if (Math.abs(avgCy - w.cy) <= medianH * 1.2f) {
                    matched = c
                    break
                }
            }
            if (matched != null) matched.add(w)
            else numClusters.add(mutableListOf(w))
        }
        val bestNumCluster = numClusters.filter { it.size >= 3 }.maxByOrNull { it.size }

        // Determine estimated header band Y
        val headerBandY: Float = when {
            bestWeekdayCluster != null && bestWeekdayCluster.isNotEmpty() ->
                (bestWeekdayCluster.sumOf { it.cy.toDouble() } / bestWeekdayCluster.size).toFloat()
            bestNumCluster != null && bestNumCluster.isNotEmpty() ->
                (bestNumCluster.sumOf { it.cy.toDouble() } / bestNumCluster.size).toFloat()
            else ->
                minY + docHeight * 0.08f
        }

        // 2. Search for metadata column header keywords near headerBandY
        val headerBandTolerance = maxOf(medianH * 2.8f, docHeight * 0.08f)
        val metaHeaders = mutableMapOf<String, OcrWord>()

        for (w in words.filter { Math.abs(it.cy - headerBandY) <= headerBandTolerance }) {
            val clean = w.text.uppercase(Locale.US).replace(Regex("[^A-Z.]"), "")
            if (("SNO" in clean || "S.NO" in clean || clean == "SR") && !metaHeaders.containsKey("sno")) {
                metaHeaders["sno"] = w
            } else if ((clean == "ID" || clean == "EMPID" || clean == "STAFFID") && !metaHeaders.containsKey("id")) {
                metaHeaders["id"] = w
            } else if (("NAME" in clean || clean == "EMPLOYEE" || clean == "STAFFNAME") && !metaHeaders.containsKey("name")) {
                metaHeaders["name"] = w
            } else if ((clean == "OFF" || clean == "OFFDAY" || clean == "WEEKLYOFF" || clean == "OFFDAY.") && !metaHeaders.containsKey("off_day")) {
                metaHeaders["off_day"] = w
            } else if (("FLOOR" in clean || "DEPT" in clean || "WARD" in clean || "STATION" in clean) && !metaHeaders.containsKey("floor")) {
                metaHeaders["floor"] = w
            }
        }

        val maxMetaRight = metaHeaders.values.maxOfOrNull { it.x + it.width } ?: minX

        // 3. Determine calendar start X from weekday/number tokens to the right of metadata
        val headerTokens: List<OcrWord>
        val calStartX: Float

        if (bestWeekdayCluster != null && bestWeekdayCluster.any { it.x >= maxMetaRight - medianH }) {
            val calTokens = bestWeekdayCluster.filter { it.x >= maxMetaRight - medianH }.sortedBy { it.x }
            headerTokens = calTokens
            calStartX = calTokens.first().x
        } else if (bestNumCluster != null && bestNumCluster.any { it.x >= maxMetaRight - medianH }) {
            val calTokens = bestNumCluster.filter { it.x >= maxMetaRight - medianH }.sortedBy { it.x }
            headerTokens = calTokens
            calStartX = calTokens.first().x
        } else {
            calStartX = if (metaHeaders.isNotEmpty()) maxMetaRight + medianH * 1.5f else minX + docWidth * 0.28f
            headerTokens = emptyList()
            warnings.add("Header row could not be anchored by weekday or date sequence; using geometric estimation.")
        }

        val allHeaderTops = mutableListOf<Float>()
        val allHeaderBottoms = mutableListOf<Float>()
        if (bestWeekdayCluster != null && bestWeekdayCluster.isNotEmpty()) {
            allHeaderTops.add(bestWeekdayCluster.minOf { it.y })
            allHeaderBottoms.add(bestWeekdayCluster.maxOf { it.y + it.height })
        }
        if (bestNumCluster != null && bestNumCluster.isNotEmpty()) {
            allHeaderTops.add(bestNumCluster.minOf { it.y })
            allHeaderBottoms.add(bestNumCluster.maxOf { it.y + it.height })
        }
        if (headerTokens.isNotEmpty()) {
            allHeaderTops.add(headerTokens.minOf { it.y })
            allHeaderBottoms.add(headerTokens.maxOf { it.y + it.height })
        }
        for (hw in metaHeaders.values) {
            allHeaderTops.add(hw.y)
            allHeaderBottoms.add(hw.y + hw.height)
        }

        // Also include date numbers in calendar zone in header band
        val headerDayNumbers = words.filter {
            it.x >= calStartX - medianH * 1.5f && Math.abs(it.cy - headerBandY) <= headerBandTolerance && it.text.trim().toIntOrNull() in 1..31
        }
        for (nw in headerDayNumbers) {
            allHeaderTops.add(nw.y)
            allHeaderBottoms.add(nw.y + nw.height)
        }

        val headerTop = (allHeaderTops.minOrNull() ?: (headerBandY - medianH)).coerceAtLeast(minY)
        val headerBottom = (allHeaderBottoms.maxOrNull() ?: (headerBandY + medianH))

        // Dynamic column intervals in metadata zone (ordered dynamically by header position)
        val sortedHeaders = metaHeaders.entries.sortedBy { it.value.x }
        val metaColumns = mutableListOf<MetaColumnInterval>()
        val metaWidth = calStartX - minX

        if (sortedHeaders.isNotEmpty()) {
            for (i in sortedHeaders.indices) {
                val curr = sortedHeaders[i]
                val prev = if (i > 0) sortedHeaders[i - 1] else null
                val next = if (i < sortedHeaders.size - 1) sortedHeaders[i + 1] else null

                val startX = if (prev != null) {
                    (prev.value.x + prev.value.width + curr.value.x) / 2f
                } else {
                    minX
                }

                val endX = if (next != null) {
                    (curr.value.x + curr.value.width + next.value.x) / 2f
                } else {
                    calStartX
                }

                metaColumns.add(MetaColumnInterval(curr.key, startX, endX))
            }
        } else {
            // Default layout when no metadata headers are detected
            metaColumns.add(MetaColumnInterval("sno", minX, minX + metaWidth * 0.12f))
            metaColumns.add(MetaColumnInterval("id", minX + metaWidth * 0.12f, minX + metaWidth * 0.35f))
            metaColumns.add(MetaColumnInterval("name", minX + metaWidth * 0.35f, minX + metaWidth * 0.70f))
            metaColumns.add(MetaColumnInterval("off_day", minX + metaWidth * 0.70f, minX + metaWidth * 0.85f))
            metaColumns.add(MetaColumnInterval("floor", minX + metaWidth * 0.85f, calStartX))
        }

        val bounds = mutableMapOf<String, Float>()
        for (col in metaColumns) {
            bounds["${col.type}_max"] = col.endX
        }
        bounds["calendar_start"] = calStartX

        return AdaptiveGeometry(
            headerTop = headerTop,
            headerBottom = headerBottom,
            calendarStartX = calStartX,
            calendarEndX = maxX,
            metaBounds = bounds,
            metaColumns = metaColumns,
            medianWordHeight = medianH,
            warnings = warnings
        )
    }

    private fun detectDateColumns(
        calStartX: Float,
        calEndX: Float,
        headerTop: Float,
        headerBottom: Float,
        year: Int,
        month: Int
    ): Triple<Map<Int, Pair<Float, Float>>, Map<Int, ColumnDiagnostic>, List<String>> {
        val warnings = mutableListOf<String>()
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val expectedWeekdays = (1..numDays).associateWith { d ->
            val c = Calendar.getInstance()
            c.set(year, month - 1, d)
            c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)?.uppercase(Locale.US) ?: ""
        }

        val headerWords = words.filter { it.x >= calStartX - 25f && it.y in (headerTop - 15f)..(headerBottom + 15f) }

        val numberAnchors = mutableMapOf<Int, OcrWord>()
        for (w in headerWords) {
            val digits = w.text.replace(Regex("\\D"), "")
            val dVal = digits.toIntOrNull()
            if (dVal != null && dVal in 1..numDays && !numberAnchors.containsKey(dVal)) {
                numberAnchors[dVal] = w
            }
        }

        val weekdayTokens = mutableListOf<OcrWord>()
        for (w in headerWords) {
            var clean = w.text.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
            if (clean == "FRE") clean = "FRI"
            if (clean.isNotEmpty() && !clean.all { it.isDigit() } && (weekdayAbbrs.any { it in clean } || clean == "MONTUE")) {
                weekdayTokens.add(w.copy(text = clean))
            }
        }
        weekdayTokens.sortBy { it.x }

        val roughColStep: Float
        val roughFirstX: Float
        if (numberAnchors.size >= 2) {
            val sortedKeys = numberAnchors.keys.sorted()
            val firstK = sortedKeys.first()
            val lastK = sortedKeys.last()
            roughColStep = (numberAnchors[lastK]!!.cx - numberAnchors[firstK]!!.cx) / maxOf(1, lastK - firstK)
            roughFirstX = numberAnchors[firstK]!!.cx - (firstK - 1) * roughColStep
        } else if (weekdayTokens.size >= 5) {
            val sortedX = weekdayTokens.map { it.cx }.sorted()
            roughColStep = (sortedX.last() - sortedX.first()) / maxOf(1, weekdayTokens.size - 1)
            roughFirstX = sortedX.first()
        } else {
            roughColStep = maxOf(18f, (calEndX - calStartX) / numDays)
            roughFirstX = calStartX + roughColStep / 2f
        }

        val dayAnchors = mutableMapOf<Int, Triple<Float, String, String>>()
        var tokIdx = 0

        for (d in 1..numDays) {
            val expW = expectedWeekdays[d] ?: ""
            val numAnc = numberAnchors[d]
            var wAnc: OcrWord? = null
            var isMerged = false

            if (tokIdx < weekdayTokens.size) {
                val currTok = weekdayTokens[tokIdx]
                val estDay = Math.round((currTok.cx - roughFirstX) / maxOf(12f, roughColStep)) + 1
                if (Math.abs(d - estDay) <= 2.5f) {
                    if (currTok.text == expW) {
                        wAnc = currTok
                        tokIdx++
                    } else if (currTok.text.length > 3 && weekdayAbbrs.any { it in currTok.text }) {
                        if (currTok.text.startsWith(expW)) {
                            isMerged = true
                            wAnc = currTok
                            val nextW = expectedWeekdays.getOrElse(d + 1) { "" }
                            if (d < numDays && nextW in currTok.text) {
                                // keep for next day
                            } else {
                                tokIdx++
                            }
                        } else if (d > 1 && expW in currTok.text) {
                            isMerged = true
                            wAnc = currTok
                            tokIdx++
                        }
                    }
                } else if (d > estDay + 2) {
                    tokIdx++
                }
            }

            if (numAnc != null && wAnc != null) {
                dayAnchors[d] = Triple((numAnc.cx + wAnc.cx) / 2f, "number_and_weekday", "high")
            } else if (numAnc != null) {
                dayAnchors[d] = Triple(numAnc.cx, "number_only", "high")
            } else if (wAnc != null) {
                val cx = if (!isMerged) wAnc.cx else {
                    wAnc.x + wAnc.width * (if (expW == "MON") 0.25f else 0.75f)
                }
                val conf = if (isMerged) "merged_header" else "high"
                dayAnchors[d] = Triple(cx, if (isMerged) "weekday_merged" else "weekday_only", conf)
                if (isMerged) {
                    warnings.add("Day $d ($expW) falls in merged header '${wAnc.text}'. Column boundaries require manual review.")
                }
            }
        }

        val unanchored = (1..numDays).filter { !dayAnchors.containsKey(it) }
        val anchoredSorted = dayAnchors.keys.sorted()
        if (anchoredSorted.isEmpty()) {
            for (d in 1..numDays) {
                dayAnchors[d] = Triple(calStartX + (d - 0.5f) * roughColStep, "unanchored_fallback", "unanchored")
            }
        } else {
            for (d in unanchored) {
                val lefts = anchoredSorted.filter { it < d }
                val rights = anchoredSorted.filter { it > d }
                val interpCx: Float
                if (lefts.isNotEmpty() && rights.isNotEmpty()) {
                    val lDay = lefts.last()
                    val rDay = rights.first()
                    val lCx = dayAnchors[lDay]!!.first
                    val rCx = dayAnchors[rDay]!!.first
                    interpCx = lCx + (d - lDay) * (rCx - lCx) / (rDay - lDay)
                } else if (lefts.isNotEmpty()) {
                    val lDay = lefts.last()
                    interpCx = dayAnchors[lDay]!!.first + (d - lDay) * roughColStep
                } else {
                    val rDay = rights.first()
                    interpCx = dayAnchors[rDay]!!.first - (rDay - d) * roughColStep
                }
                dayAnchors[d] = Triple(interpCx, "interpolated", "unanchored")
            }
        }

        val intervals = mutableMapOf<Int, Pair<Float, Float>>()
        val diagnostics = mutableMapOf<Int, ColumnDiagnostic>()

        for (d in 1..numDays) {
            val cx = dayAnchors[d]!!.first
            val left = if (d == 1) calStartX else (dayAnchors[d - 1]!!.first + cx) / 2f
            val right = if (d == numDays) {
                val step = cx - (dayAnchors[d - 1]?.first ?: (cx - roughColStep))
                cx + step / 2f
            } else {
                (cx + dayAnchors[d + 1]!!.first) / 2f
            }
            intervals[d] = left to right
            diagnostics[d] = ColumnDiagnostic(
                dayNumber = d,
                weekday = expectedWeekdays[d] ?: "",
                centerX = cx,
                anchorType = dayAnchors[d]!!.second,
                anchorConfidence = dayAnchors[d]!!.third
            )
        }

        return Triple(intervals, diagnostics, warnings)
    }

    private fun detectEmployeeRows(geom: AdaptiveGeometry): Pair<List<ReconstructedRow>, List<CandidateRowEvaluation>> {
        val metaColumns = geom.metaColumns
        val headerBottom = geom.headerBottom
        val calStartX = geom.calendarStartX
        val medianH = geom.medianWordHeight

        // Filter out header words and footer summary words
        val footerKeywords = listOf("TOTAL", "PRESENT", "ABSENT", "NIGHT OFF", "SIGNATURE", "INCHARGE", "SUPERVISOR", "SUMMARY", "DAILY-WEEKLY-OFF")
        val candidateEvaluations = mutableListOf<CandidateRowEvaluation>()

        val allSortedWords = words.sortedWith(compareBy({ it.cy }, { it.x }))
        if (allSortedWords.isEmpty()) return emptyList<ReconstructedRow>() to emptyList()

        val bodyHeights = allSortedWords.map { it.height }.filter { it > 0f }.sorted()
        val bodyMedianH = if (bodyHeights.isNotEmpty()) bodyHeights[bodyHeights.size / 2] else medianH
        val rowTolerance = maxOf(3f, bodyMedianH * 0.70f)
        val clusters = mutableListOf<MutableList<OcrWord>>()

        for (w in allSortedWords) {
            val matched = clusters.filter { c ->
                val avgY = c.sumOf { it.cy.toDouble() } / c.size
                Math.abs(avgY - w.cy) <= rowTolerance
            }.minByOrNull { c ->
                val avgY = c.sumOf { it.cy.toDouble() } / c.size
                Math.abs(avgY - w.cy)
            }

            if (matched != null) {
                matched.add(w)
            } else {
                clusters.add(mutableListOf(w))
            }
        }
        clusters.sortBy { c -> c.sumOf { it.cy.toDouble() } / c.size }

        val rows = mutableListOf<ReconstructedRow>()
        val idRegex = Regex("^(ABH[L1I\\d]\\d+|\\d{4,8}|[A-Z]{2,4}\\d{3,6})$", RegexOption.IGNORE_CASE)
        val floorKeywords = listOf("FLOOR", "ICU", "SICU", "NSICU", "TRIAGE", "WING", "BMT", "RELIVER", "REUVER", "EMERGENCY", "WARD", "OT", "OPD", "CCU", "ATH", "STH", "7TH", "9TH", "4TH", "6TH", "3RD")

        for ((clusterIdx, c) in clusters.withIndex()) {
            val rowWords = c.sortedBy { it.x }
            val rowY = (c.sumOf { it.cy.toDouble() } / c.size).toFloat()
            val rawTokens = rowWords.map { it.text.trim() }

            // Stage 1: Header zone check
            if (rowY <= headerBottom) {
                candidateEvaluations.add(
                    CandidateRowEvaluation(
                        clusterIndex = clusterIdx + 1,
                        rowY = rowY,
                        rawTokens = rawTokens,
                        rawId = null,
                        name = null,
                        floor = null,
                        offDay = null,
                        shiftWordCount = 0,
                        isAccepted = false,
                        rejectionStage = "HEADER_ZONE",
                        decisionReason = "Cluster center Y (${String.format(Locale.US, "%.1f", rowY)}) is within header zone (top ${String.format(Locale.US, "%.1f", headerBottom)} px)"
                    )
                )
                continue
            }

            // Stage 2: Footer / summary keyword check
            val footerMatch = footerKeywords.firstOrNull { kw ->
                rawTokens.any { tok -> tok.uppercase(Locale.US).contains(kw) }
            }
            if (footerMatch != null) {
                candidateEvaluations.add(
                    CandidateRowEvaluation(
                        clusterIndex = clusterIdx + 1,
                        rowY = rowY,
                        rawTokens = rawTokens,
                        rawId = null,
                        name = null,
                        floor = null,
                        offDay = null,
                        shiftWordCount = 0,
                        isAccepted = false,
                        rejectionStage = "FOOTER_KEYWORD",
                        decisionReason = "Contains footer or summary keyword '$footerMatch'"
                    )
                )
                continue
            }

            val columnTokens = mutableMapOf<String, MutableList<String>>()
            for (col in metaColumns) {
                columnTokens[col.type] = mutableListOf()
            }
            val shiftWords = mutableListOf<OcrWord>()

            for (w in rowWords) {
                val t = w.text.trim()
                if (w.x >= calStartX) {
                    shiftWords.add(w)
                } else {
                    val matchingCol = metaColumns.firstOrNull { w.cx in it.startX..<it.endX }
                        ?: metaColumns.minByOrNull { Math.abs(w.cx - (it.startX + it.endX) / 2f) }
                    if (matchingCol != null) {
                        columnTokens.getOrPut(matchingCol.type) { mutableListOf() }.add(t)
                    }
                }
            }

            val snoTokens = columnTokens["sno"] ?: mutableListOf()
            val idTokens = columnTokens["id"] ?: mutableListOf()
            val nameTokens = columnTokens["name"] ?: mutableListOf()
            val offTokens = columnTokens["off_day"] ?: mutableListOf()
            val floorTokens = columnTokens["floor"] ?: mutableListOf()

            // 1. Semantic extraction for Staff ID:
            var rawId: String? = null
            for (tokens in listOf(idTokens, nameTokens, snoTokens, floorTokens)) {
                for (i in tokens.indices.reversed()) {
                    val norm = IdMatcher.normalizeId(tokens[i])
                    if (idRegex.matches(norm)) {
                        rawId = tokens.removeAt(i)
                        break
                    }
                }
                if (rawId != null) break
            }

            // 2. Off Day extraction:
            for (tokens in listOf(floorTokens, nameTokens, idTokens)) {
                for (i in tokens.indices.reversed()) {
                    val clean = tokens[i].uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
                    if (clean in weekdayAbbrs) {
                        offTokens.add(tokens.removeAt(i))
                    }
                }
            }

            // 3. Floor extraction:
            for (tokens in listOf(nameTokens, idTokens)) {
                for (i in tokens.indices.reversed()) {
                    val upper = tokens[i].uppercase(Locale.US)
                    if (floorKeywords.any { upper.contains(it) }) {
                        floorTokens.add(tokens.removeAt(i))
                    }
                }
            }

            // 4. If ID still not found, check if idTokens has a plausible code
            if (rawId == null && idTokens.isNotEmpty()) {
                val candidate = idTokens.joinToString(" ").trim()
                if (candidate.length in 3..12 && !candidate.contains(" ")) {
                    rawId = idTokens.removeAt(0)
                }
            }

            val normId = rawId?.let { IdMatcher.normalizeId(it) }
            val name = nameTokens.filterIndexed { i, tok -> i == 0 || tok != nameTokens[i - 1] }
                .joinToString(" ").trim().ifBlank { null }
            val off = offTokens.joinToString(" ").trim().ifBlank { null }
            val fl = floorTokens.joinToString(" ").trim().ifBlank { null }
            val sno = snoTokens.joinToString(" ").trim().ifBlank { null }

            // Stage 3: Empty row check
            if (rawId == null && name == null && shiftWords.isEmpty()) {
                candidateEvaluations.add(
                    CandidateRowEvaluation(
                        clusterIndex = clusterIdx + 1,
                        rowY = rowY,
                        rawTokens = rawTokens,
                        rawId = null,
                        name = null,
                        floor = null,
                        offDay = null,
                        shiftWordCount = 0,
                        isAccepted = false,
                        rejectionStage = "NO_ID_NAME_OR_SHIFTS",
                        decisionReason = "Cluster contains neither staff ID, employee name, nor shift entries"
                    )
                )
                continue
            }

            // Stage 4: Summary row keyword in off column
            if (off != null && listOf("DAILY", "PRESENT", "WEEKLY", "TOTAL").any { off.uppercase(Locale.US).contains(it) }) {
                candidateEvaluations.add(
                    CandidateRowEvaluation(
                        clusterIndex = clusterIdx + 1,
                        rowY = rowY,
                        rawTokens = rawTokens,
                        rawId = rawId,
                        name = name,
                        floor = fl,
                        offDay = off,
                        shiftWordCount = shiftWords.size,
                        isAccepted = false,
                        rejectionStage = "SUMMARY_KEYWORD",
                        decisionReason = "Off column contains summary keyword '$off'"
                    )
                )
                continue
            }

            // Stage 5: Accepted employee row
            candidateEvaluations.add(
                CandidateRowEvaluation(
                    clusterIndex = clusterIdx + 1,
                    rowY = rowY,
                    rawTokens = rawTokens,
                    rawId = rawId,
                    name = name,
                    floor = fl,
                    offDay = off,
                    shiftWordCount = shiftWords.size,
                    isAccepted = true,
                    rejectionStage = null,
                    decisionReason = "Accepted as employee row (ID='${rawId ?: "Auto"}', Name='${name ?: "Unnamed"}', Shifts=${shiftWords.size})"
                )
            )

            rows.add(
                ReconstructedRow(
                    serialNumber = sno,
                    rawId = rawId,
                    normalizedId = normId,
                    name = name,
                    weeklyOffDay = off,
                    floor = fl,
                    y = rowY,
                    shiftWords = shiftWords
                )
            )
        }

        rows.sortBy { it.y }

        // Derive target row bounds from table geometry, strictly preventing adjacent row overlap
        val boundedRows = rows.mapIndexed { i, row ->
            val prevRowY = if (i > 0) rows[i - 1].y else null
            val nextRowY = if (i < rows.size - 1) rows[i + 1].y else null

            val spacingAbove = if (prevRowY != null) row.y - prevRowY else null
            val spacingBelow = if (nextRowY != null) nextRowY - row.y else null
            val avgSpacing = when {
                spacingAbove != null && spacingBelow != null -> (spacingAbove + spacingBelow) / 2f
                spacingAbove != null -> spacingAbove
                spacingBelow != null -> spacingBelow
                else -> maxOf(12f, bodyMedianH * 1.5f)
            }

            val divTop = if (prevRowY != null) (prevRowY + row.y) / 2f else row.y - (avgSpacing / 2f)
            val divBottom = if (nextRowY != null) (row.y + nextRowY) / 2f else row.y + (avgSpacing / 2f)

            // Tight geometric row bounds: 90% of spacing, strictly staying within divider lines with 0.5px buffer
            val tightH = (avgSpacing * 0.90f).coerceAtLeast(maxOf(8f, bodyMedianH))
            val topBound = maxOf(divTop + 0.5f, row.y - tightH / 2f)
            val bottomBound = minOf(divBottom - 0.5f, row.y + tightH / 2f)
            val calcRowH = (bottomBound - topBound).coerceAtLeast(bodyMedianH)

            row.copy(
                rowHeight = calcRowH,
                topBound = topBound,
                bottomBound = bottomBound
            )
        }

        return boundedRows to candidateEvaluations
    }

    private data class ParsedShift(
        val rawValue: String?,
        val normalizedShift: String?,
        val status: String,
        val confidence: Double?,
        val provenance: String?,
        val reviewReason: String?,
        val ocrBoundingBox: String? = null,
        val matchDistance: Float? = null,
        val ocrConfidence: Double? = null,
        val classification: String? = null
    )

    private fun parseShiftTokens(colWords: List<OcrWord>, diag: ColumnDiagnostic): ParsedShift {
        if (colWords.isEmpty()) {
            return ParsedShift(null, null, "missing", null, null, null, null, null, null, null)
        }

        val rawVal = colWords.joinToString(" ") { it.text.trim() }
        val conf = colWords.mapNotNull { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.95
        val bboxes = colWords.joinToString("; ") { "[${it.x.toInt()}, ${it.y.toInt()}, ${it.width.toInt()}x${it.height.toInt()}]" }
        val minDist = colWords.map { Math.abs(it.cx - diag.centerX) }.minOrNull()

        // Match normalized shifts
        val clean = colWords.joinToString("") { it.text.uppercase(Locale.US).replace(Regex("[^A-Z]"), "") }

        val normalized: String?
        val status: String
        val reason: String?
        val classification: String

        if (defaultShiftLegend.containsKey(clean)) {
            normalized = defaultShiftLegend[clean]
            status = if (diag.anchorConfidence == "high") "confirmed" else "needs_review"
            reason = if (status == "needs_review") "Unanchored date column" else null
            classification = when (clean) {
                "OFF" -> "OFF"
                "A" -> "A"
                "E" -> "E"
                "M" -> "M"
                "N" -> "N"
                else -> clean
            }
        } else if (clean in listOf("OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI")) {
            normalized = "Off"
            status = "needs_review"
            reason = "OCR border artifact '$rawVal' interpreted as Off. Held for review."
            classification = "OFF"
        } else if (clean == "NN") {
            normalized = "Night"
            status = "needs_review"
            reason = "Ambiguous concatenated token 'NN'. Candidate: Night. Held for review."
            classification = "N"
        } else if (clean in listOf("MM", "MMM")) {
            normalized = "Morning"
            status = "needs_review"
            reason = "Ambiguous concatenated token '$rawVal'. Candidate: Morning. Held for review."
            classification = "M"
        } else if (clean == "A") {
            normalized = null
            status = "needs_review"
            reason = "Observation 'A' requires supervisor review"
            classification = "A"
        } else {
            normalized = null
            status = "unresolved"
            reason = "Unrecognized shift token '$rawVal'"
            classification = "UNRESOLVED"
        }

        return ParsedShift(
            rawValue = rawVal,
            normalizedShift = normalized,
            status = status,
            confidence = conf,
            provenance = "ocr_observed",
            reviewReason = reason,
            ocrBoundingBox = bboxes,
            matchDistance = minDist,
            ocrConfidence = conf,
            classification = classification
        )
    }
}
