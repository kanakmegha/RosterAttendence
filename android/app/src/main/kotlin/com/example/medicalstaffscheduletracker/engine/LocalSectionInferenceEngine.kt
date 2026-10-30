package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell
import com.example.medicalstaffscheduletracker.data.model.SectionSummary
import java.util.Calendar
import java.util.Locale

class LocalSectionInferenceEngine(
    private val verifyRotationRuleDefault: Boolean = true
) {

    companion object {
        val TARGET_WORKING_SHIFTS = setOf("Morning", "Evening", "Night")
        val WEEKDAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val WEEKDAY_ABBRS = mapOf("MON" to 0, "TUE" to 1, "WED" to 2, "THU" to 3, "FRI" to 4, "SAT" to 5, "SUN" to 6)
    }

    data class SectionDef(
        val sectionIndex: Int,
        val startDay: Int,
        val endDay: Int,
        val dayCount: Int
    )

    data class SectionEvaluation(
        val sectionIndex: Int,
        val startDay: Int,
        val endDay: Int,
        val totalDays: Int,
        val candidateShift: String?,
        val inferenceMethod: String, // "section_anchor", "verified_rotation", "unresolved", "conflict"
        val hasConflict: Boolean,
        val directEvidenceTokens: List<String>,
        val directEvidenceDates: List<Int>,
        val contradictoryEvidence: List<String>,
        val reviewReason: String?
    )

    /**
     * Reconstructs and infers the monthly schedule for an employee row.
     * Evaluates weekly Off recurrence, corner/inward section anchors, and verified 3-section rotation.
     */
    fun inferSections(
        reconstruction: TableReconstructionResult,
        verifyRotationRule: Boolean = verifyRotationRuleDefault,
        existingDraft: DraftSchedule? = null
    ): DraftSchedule {
        val warnings = reconstruction.warnings.toMutableList()
        val monthStr = reconstruction.rosterMonth
        val parts = monthStr.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val month = parts.getOrNull(1)?.toIntOrNull() ?: 9

        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val numDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // 1. Divide month into 3 approximately equal sections using deterministic mapping:
        // sectionIndex = floor((day - 1) * 3 / numDays), producing sections 1, 2, 3
        val sectionDefs = detectRosterSections(numDays)

        // 2. Prepare mutable entries map by day, preserving source evidence
        val entriesByDay = mutableMapOf<Int, MutableEntry>()
        for (cell in reconstruction.entries) {
            val rawClean = cell.rawValue?.trim()?.uppercase(Locale.US) ?: ""
            val classif = cell.classification ?: when {
                cell.normalizedShift == "Off" || rawClean in listOf("OFF", "OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI") -> "OFF"
                rawClean == "A" -> "A"
                cell.normalizedShift == "Morning" || rawClean in listOf("M", "MM", "MMM") -> "M"
                cell.normalizedShift == "Evening" || rawClean == "E" -> "E"
                cell.normalizedShift == "Night" || rawClean in listOf("N", "NN") -> "N"
                cell.status == "missing" -> "UNRESOLVED"
                else -> "UNRESOLVED"
            }

            entriesByDay[cell.dayNumber] = MutableEntry(
                dayNumber = cell.dayNumber,
                date = cell.date,
                weekday = cell.weekday,
                rawValue = cell.rawValue,
                normalizedShift = cell.normalizedShift,
                reviewStatus = cell.status,
                reviewReason = cell.reviewReason,
                confidence = cell.confidence ?: 0.0,
                sourceType = if (cell.status == "missing") "unresolved" else "directly_observed",
                anchorType = cell.anchorType,
                anchorConfidence = cell.anchorConfidence,
                ocrBoundingBox = cell.ocrBoundingBox,
                matchDistance = cell.matchDistance,
                ocrConfidence = cell.ocrConfidence,
                classification = classif
            )
        }

        // Fill any missing days if entries list was incomplete
        for (d in 1..numDays) {
            if (!entriesByDay.containsKey(d)) {
                cal.set(year, month - 1, d)
                val dtStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, d)
                val wName = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: ""
                entriesByDay[d] = MutableEntry(
                    dayNumber = d,
                    date = dtStr,
                    weekday = wName,
                    rawValue = null,
                    normalizedShift = null,
                    reviewStatus = "missing",
                    reviewReason = "Missing from OCR response",
                    confidence = 0.0,
                    sourceType = "unresolved",
                    anchorType = "unanchored",
                    anchorConfidence = "unanchored",
                    classification = "UNRESOLVED"
                )
            }
        }

        // 3. Detect the weekly Off weekday and propagate safely
        val (confidentOffDays, offWarnings) = detectAndPropagateWeeklyOff(
            entriesByDay = entriesByDay,
            year = year,
            month = month,
            numDays = numDays,
            weeklyOffHint = reconstruction.weeklyOffDay
        )
        warnings.addAll(offWarnings)

        // 4. Evaluate each section using corner observations and inward search
        val sectionEvaluations = evaluateSectionCornersAndInward(
            sectionDefs = sectionDefs,
            entriesByDay = entriesByDay,
            confidentOffDays = confidentOffDays
        )

        // 5. Recover sections with missing anchors via verified 3-section rotation fallback
        val finalSectionEvaluations = resolveSectionFallbacks(
            sectionEvaluations = sectionEvaluations,
            reconstruction = reconstruction,
            verifyRotationRule = verifyRotationRule
        )

        // 6. Apply shifts to cells, strictly preserving classifications, observations, and explicit OFF
        val finalCells = mutableListOf<ExtractedCell>()
        val unresolvedDates = mutableListOf<Int>()

        for (sEval in finalSectionEvaluations) {
            val startDay = sEval.startDay
            val endDay = sEval.endDay
            val propShift = sEval.candidateShift
            val hasConflict = sEval.hasConflict

            for (d in startDay..endDay) {
                val e = entriesByDay[d]!!
                val raw = e.rawValue?.trim()?.uppercase(Locale.US) ?: ""
                val norm = e.normalizedShift
                val status = e.reviewStatus

                e.sectionStartDay = startDay
                e.sectionEndDay = endDay

                // Case A: Confirmed or Propagated OFF Day
                if (d in confidentOffDays) {
                    e.normalizedShift = "Off"
                    e.classification = "OFF"
                    if (e.sourceType != "inferred_from_weekly_off" && e.sourceType != "conflicting") {
                        e.sourceType = "directly_observed"
                        if (e.reviewStatus != "needs_review") {
                            e.reviewStatus = "confirmed"
                        }
                    }
                    val cell = e.toExtractedCell()
                    finalCells.add(cell)
                    if (cell.isUnresolved) unresolvedDates.add(d)
                    continue
                }

                // Case B: Non-target shift 'A'
                if (raw == "A" || e.classification == "A") {
                    e.sourceType = "directly_observed"
                    e.classification = "A"
                    e.normalizedShift = null // Must remain blank in final shift field
                    e.reviewStatus = "needs_review"
                    e.reviewReason = "Valid observation for non-target shift 'A'; final recorded shift field left blank."
                    val cell = e.toExtractedCell()
                    finalCells.add(cell)
                    unresolvedDates.add(d)
                    continue
                }

                // Case C: Directly observed working shift (preserve direct observations!)
                val isDirectObs = (norm in TARGET_WORKING_SHIFTS || raw in listOf("M", "E", "N")) &&
                        status != "missing" &&
                        e.sourceType in listOf("ocr_observed", "directly_observed")

                if (isDirectObs) {
                    e.sourceType = "directly_observed"
                    e.classification = when (norm) {
                        "Morning" -> "M"
                        "Evening" -> "E"
                        "Night" -> "N"
                        else -> raw.take(1)
                    }
                    if (hasConflict || (propShift != null && norm != propShift)) {
                        e.sourceType = "conflicting"
                        e.reviewStatus = "needs_review"
                        val reason = "Observed shift '$norm' differs from section candidate '$propShift' [Days $startDay–$endDay]; directly observed value preserved."
                        e.reviewReason = if (e.reviewReason != null) "${e.reviewReason}; $reason" else reason
                        unresolvedDates.add(d)
                    }
                    finalCells.add(e.toExtractedCell())
                    continue
                }

                // Case D: Missing working cell filled via section inference
                if (propShift != null && !hasConflict) {
                    e.normalizedShift = propShift
                    e.classification = when (propShift) {
                        "Morning" -> "M"
                        "Evening" -> "E"
                        "Night" -> "N"
                        else -> propShift.take(1)
                    }
                    if (sEval.inferenceMethod == "verified_rotation") {
                        e.sourceType = "inferred_from_verified_rotation"
                        e.reviewStatus = "inferred_needs_confirmation"
                        e.confidence = 0.80
                        e.inferenceRule = "verified_three_section_rotation"
                        e.reviewReason = sEval.reviewReason ?: "Inferred '$propShift' via verified 3-section rotation rule; requires review"
                    } else {
                        e.sourceType = "inferred_from_section_anchor"
                        e.reviewStatus = "inferred_needs_confirmation"
                        e.confidence = 0.85
                        e.inferenceRule = "section_anchor_fill"
                        e.reviewReason = "Inferred '$propShift' from section anchors [Days $startDay–$endDay]; requires review"
                    }
                    e.supportingDates = sEval.directEvidenceDates
                    e.supportingRawTokens = sEval.directEvidenceTokens
                    unresolvedDates.add(d)
                } else {
                    // Case E: Unsupported or Conflicting cell left unresolved
                    e.normalizedShift = null
                    e.classification = if (hasConflict) "CONFLICT" else "UNRESOLVED"
                    e.sourceType = if (hasConflict) "conflicting" else "unresolved"
                    e.reviewStatus = "unresolved"
                    e.reviewReason = if (hasConflict) {
                        "Conflicting observed shifts in section [Days $startDay–$endDay]; cell left unresolved"
                    } else {
                        sEval.reviewReason ?: "No reliable working shift observed in section [Days $startDay–$endDay]; cell left unresolved"
                    }
                    unresolvedDates.add(d)
                }

                finalCells.add(e.toExtractedCell())
            }
        }

        // 7. Audit trail counts to prevent feedback loops and verify evidence separation
        val directCount = finalCells.count { it.sourceType == "directly_observed" }
        val inferredCount = finalCells.count { it.sourceType in listOf("inferred_from_section_anchor", "inferred_from_weekly_off", "inferred_from_verified_rotation") }
        val conflictCount = finalCells.count { it.sourceType == "conflicting" || it.reviewStatus == "needs_review" && "conflict" in (it.reviewReason ?: "").lowercase(Locale.US) }
        val missingCount = finalCells.count { it.reviewStatus in listOf("missing", "unresolved") }

        // 8. Build SectionSummaries
        val summaries = finalSectionEvaluations.map { sEval ->
            val secCells = finalCells.filter { it.dayNumber in sEval.startDay..sEval.endDay }
            val obsTokens = secCells.mapNotNull { it.rawValue }.filter { it.isNotBlank() }
            val unkTokens = secCells.filter { it.reviewReason != null && "conflict" in it.reviewReason.lowercase(Locale.US) }
                .mapNotNull { it.rawValue }
            val unres = secCells.filter { it.isUnresolved }.map { it.dayNumber }

            SectionSummary(
                sectionIndex = sEval.sectionIndex,
                startDay = sEval.startDay,
                endDay = sEval.endDay,
                totalDays = sEval.totalDays,
                proposedShift = sEval.candidateShift,
                observedTokens = obsTokens,
                unknownOrConflictingTokens = unkTokens,
                datesEligible = secCells.size,
                unresolvedDates = unres,
                reviewReason = sEval.reviewReason,
                inferenceMethod = sEval.inferenceMethod,
                directEvidenceCount = sEval.directEvidenceDates.size,
                directEvidenceTokens = sEval.directEvidenceTokens,
                contradictoryEvidence = sEval.contradictoryEvidence
            )
        }

        val scheduleId = java.util.UUID.randomUUID().toString()
        val resolvedStaffId = reconstruction.requestedStaffId?.takeIf {
            it.isNotBlank() && !it.startsWith("MANUAL_ROW_", ignoreCase = true) && !it.startsWith("ROW_", ignoreCase = true)
        } ?: reconstruction.matchedStaffId ?: reconstruction.requestedStaffId ?: "ABHL341"

        val generatedDraft = DraftSchedule(
            scheduleId = existingDraft?.scheduleId?.takeIf { it.isNotBlank() } ?: scheduleId,
            staffId = resolvedStaffId,
            staffName = reconstruction.staffName,
            month = monthStr,
            isApproved = false,
            approvedAt = null,
            entries = finalCells.sortedBy { it.dayNumber },
            sectionSummaries = summaries,
            unresolvedDates = unresolvedDates.distinct().sorted(),
            isConfusableId = reconstruction.isConfusableId,
            confusableMessage = reconstruction.confusableMessage,
            directEvidenceCount = directCount,
            inferredEvidenceCount = inferredCount,
            conflictCount = conflictCount,
            missingCount = missingCount
        )

        return if (existingDraft != null) {
            mergePreservingUserActions(existingDraft, generatedDraft)
        } else {
            generatedDraft
        }
    }

    /**
     * Divides month into 3 approximately equal sections using deterministic mapping:
     * sectionIndex = floor((day - 1) * 3 / numDays), producing 1, 2, 3.
     */
    fun detectRosterSections(numDays: Int): List<SectionDef> {
        val daysBySec = (1..numDays).groupBy { ((it - 1) * 3) / numDays }
        return daysBySec.map { (secIdx, days) ->
            SectionDef(
                sectionIndex = secIdx + 1,
                startDay = days.first(),
                endDay = days.last(),
                dayCount = days.size
            )
        }.sortedBy { it.sectionIndex }
    }

    /**
     * Detects consistent weekly Off weekday and propagates candidate OFF cells across matching weekdays.
     * Flags a conflict if multiple reliable OFF observations indicate different weekdays.
     * Never silently overwrites directly observed working shifts or 'A'.
     */
    private fun detectAndPropagateWeeklyOff(
        entriesByDay: Map<Int, MutableEntry>,
        year: Int,
        month: Int,
        numDays: Int,
        weeklyOffHint: String?
    ): Pair<Set<Int>, List<String>> {
        val confidentOffDays = mutableSetOf<Int>()
        val warnings = mutableListOf<String>()
        val observedOffByWeekday = Array(7) { mutableListOf<Int>() }

        val cal = Calendar.getInstance()

        for (d in 1..numDays) {
            val entry = entriesByDay[d] ?: continue
            cal.set(year, month - 1, d)
            // 0=Mon .. 6=Sun
            val wIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

            val raw = entry.rawValue?.trim()?.uppercase(Locale.US) ?: ""
            val norm = entry.normalizedShift
            val conf = entry.confidence
            val status = entry.reviewStatus

            val isOffCode = norm == "Off" || raw in listOf("OFF", "OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI")
            val isReliableConf = conf >= 0.70 && status != "missing"

            if (isOffCode && isReliableConf) {
                confidentOffDays.add(d)
                entry.normalizedShift = "Off"
                entry.classification = "OFF"
                entry.sourceType = "directly_observed"
                observedOffByWeekday[wIdx].add(d)
            }
        }

        // Check for conflicting OFF weekdays
        val weekdaysWithOff = observedOffByWeekday.indices.filter { observedOffByWeekday[it].isNotEmpty() }
        if (weekdaysWithOff.size > 1) {
            val details = weekdaysWithOff.joinToString(", ") {
                "${WEEKDAY_NAMES[it]} (Days ${observedOffByWeekday[it]})"
            }
            val msg = "Conflicting weekly Off observations detected: $details. Recurring weekly Off inference suspended."
            warnings.add(msg)
            // Leave observed OFF days as directly observed, do NOT propagate across the month
            return confidentOffDays to warnings
        }

        var targetWeekdayIdx: Int? = weekdaysWithOff.firstOrNull()

        // Fallback to hint if no direct OFF tokens recognized
        if (targetWeekdayIdx == null && !weeklyOffHint.isNullOrBlank()) {
            val hintClean = weeklyOffHint.trim().uppercase(Locale.US)
            for ((abbr, idx) in WEEKDAY_ABBRS) {
                if (abbr in hintClean) {
                    targetWeekdayIdx = idx
                    break
                }
            }
        }

        if (targetWeekdayIdx != null) {
            val wName = WEEKDAY_NAMES[targetWeekdayIdx]
            for (d in 1..numDays) {
                cal.set(year, month - 1, d)
                val wIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                if (wIdx == targetWeekdayIdx) {
                    val entry = entriesByDay[d]!!
                    val norm = entry.normalizedShift
                    val raw = entry.rawValue?.trim()?.uppercase(Locale.US) ?: ""
                    val conf = entry.confidence
                    val isReliableWorking = (norm in TARGET_WORKING_SHIFTS || raw in listOf("M", "E", "N", "A")) &&
                            conf >= 0.70 && entry.reviewStatus != "missing"

                    if (isReliableWorking) {
                        entry.sourceType = "conflicting"
                        entry.reviewStatus = "needs_review"
                        val msg = "Conflict on Day $d: Date matches recurring weekly $wName OFF, but directly observed '$norm'. Directly observed value preserved; held for review."
                        entry.reviewReason = msg
                        warnings.add(msg)
                    } else if (d !in confidentOffDays) {
                        entry.normalizedShift = "Off"
                        entry.classification = "OFF"
                        entry.sourceType = "inferred_from_weekly_off"
                        entry.reviewStatus = "inferred_needs_confirmation"
                        entry.confidence = 0.90
                        entry.inferenceRule = "weekly_off_recurrence"
                        entry.reviewReason = "Inferred from consistent weekly $wName OFF rule; requires review"
                        confidentOffDays.add(d)
                    }
                }
            }
        }

        return confidentOffDays to warnings
    }

    /**
     * Evaluates section shifts using corner observations (first/last eligible dates) and inward search.
     */
    private fun evaluateSectionCornersAndInward(
        sectionDefs: List<SectionDef>,
        entriesByDay: Map<Int, MutableEntry>,
        confidentOffDays: Set<Int>
    ): List<SectionEvaluation> {
        val evaluations = mutableListOf<SectionEvaluation>()

        for (sDef in sectionDefs) {
            val eligibleDays = (sDef.startDay..sDef.endDay).filter { d ->
                if (d in confidentOffDays) return@filter false
                val e = entriesByDay[d] ?: return@filter false
                val raw = e.rawValue?.trim()?.uppercase(Locale.US) ?: ""
                raw != "A" && e.classification != "A"
            }

            if (eligibleDays.isEmpty()) {
                evaluations.add(
                    SectionEvaluation(
                        sectionIndex = sDef.sectionIndex,
                        startDay = sDef.startDay,
                        endDay = sDef.endDay,
                        totalDays = sDef.dayCount,
                        candidateShift = null,
                        inferenceMethod = "unresolved",
                        hasConflict = false,
                        directEvidenceTokens = emptyList(),
                        directEvidenceDates = emptyList(),
                        contradictoryEvidence = emptyList(),
                        reviewReason = "No eligible working duty days in section"
                    )
                )
                continue
            }

            // Corner candidates: first few (up to 2) and last few (up to 2) eligible dates
            val firstCorners = eligibleDays.take(2)
            val lastCorners = eligibleDays.takeLast(2)
            val cornerDates = (firstCorners + lastCorners).distinct().sorted()

            // Collect reliable working shifts from corner dates
            val cornerObs = mutableMapOf<Int, String>()
            val cornerTokens = mutableListOf<String>()

            for (d in cornerDates) {
                val e = entriesByDay[d] ?: continue
                val raw = e.rawValue?.trim()?.uppercase(Locale.US) ?: ""
                val norm = e.normalizedShift
                val conf = e.confidence
                val status = e.reviewStatus

                val isAmbiguous = status == "needs_review" ||
                        raw in listOf("NN", "MM", "MMM") ||
                        e.reviewReason?.contains("ambiguous", ignoreCase = true) == true

                if (isAmbiguous) continue

                if (norm in TARGET_WORKING_SHIFTS && conf >= 0.70 && status != "missing") {
                    cornerObs[d] = norm!!
                    cornerTokens.add(raw.ifBlank { norm })
                }
            }

            // Inward search candidates: dates in eligibleDays not in cornerDates
            val inwardDates = eligibleDays.filter { it !in cornerDates }
            val inwardObs = mutableMapOf<Int, String>()
            val inwardTokens = mutableListOf<String>()

            for (d in inwardDates) {
                val e = entriesByDay[d] ?: continue
                val raw = e.rawValue?.trim()?.uppercase(Locale.US) ?: ""
                val norm = e.normalizedShift
                val conf = e.confidence
                val status = e.reviewStatus

                val isAmbiguous = status == "needs_review" ||
                        raw in listOf("NN", "MM", "MMM") ||
                        e.reviewReason?.contains("ambiguous", ignoreCase = true) == true

                if (isAmbiguous) continue

                if (norm in TARGET_WORKING_SHIFTS && conf >= 0.70 && status != "missing") {
                    inwardObs[d] = norm!!
                    inwardTokens.add(raw.ifBlank { norm })
                }
            }

            val allDirectShifts = (cornerObs.values + inwardObs.values).distinct()
            val allObsDates = (cornerObs.keys + inwardObs.keys).sorted()
            val allObsTokens = cornerTokens + inwardTokens

            if (allDirectShifts.size > 1) {
                // Conflicting shifts observed within section!
                evaluations.add(
                    SectionEvaluation(
                        sectionIndex = sDef.sectionIndex,
                        startDay = sDef.startDay,
                        endDay = sDef.endDay,
                        totalDays = sDef.dayCount,
                        candidateShift = null,
                        inferenceMethod = "conflict",
                        hasConflict = true,
                        directEvidenceTokens = allObsTokens,
                        directEvidenceDates = allObsDates,
                        contradictoryEvidence = allDirectShifts,
                        reviewReason = "Conflicting observed working shifts in section: $allDirectShifts"
                    )
                )
            } else if (allDirectShifts.size == 1) {
                // Consistent shift established via corner or inward observations
                val establishedShift = allDirectShifts.first()
                evaluations.add(
                    SectionEvaluation(
                        sectionIndex = sDef.sectionIndex,
                        startDay = sDef.startDay,
                        endDay = sDef.endDay,
                        totalDays = sDef.dayCount,
                        candidateShift = establishedShift,
                        inferenceMethod = "section_anchor",
                        hasConflict = false,
                        directEvidenceTokens = allObsTokens,
                        directEvidenceDates = allObsDates,
                        contradictoryEvidence = emptyList(),
                        reviewReason = "Supported by direct observations on days $allObsDates"
                    )
                )
            } else {
                // Zero working shift observations in corners or inward search
                evaluations.add(
                    SectionEvaluation(
                        sectionIndex = sDef.sectionIndex,
                        startDay = sDef.startDay,
                        endDay = sDef.endDay,
                        totalDays = sDef.dayCount,
                        candidateShift = null,
                        inferenceMethod = "unresolved",
                        hasConflict = false,
                        directEvidenceTokens = emptyList(),
                        directEvidenceDates = emptyList(),
                        contradictoryEvidence = emptyList(),
                        reviewReason = "No reliable working shift observed in section"
                    )
                )
            }
        }

        return evaluations
    }

    /**
     * Resolves missing section shifts using the verified 3-section rotation fallback (Requirement 6).
     * Only invokes fallback when:
     * - The missing section has zero anchors and no conflicts.
     * - The other two sections have reliable, independently observed shifts without conflicts.
     * - The other two sections have distinct shifts (S_A != S_B).
     * - verifyRotationRule is true.
     */
    private fun resolveSectionFallbacks(
        sectionEvaluations: List<SectionEvaluation>,
        reconstruction: TableReconstructionResult,
        verifyRotationRule: Boolean
    ): List<SectionEvaluation> {
        val resolved = sectionEvaluations.toMutableList()

        for (i in resolved.indices) {
            val eval = resolved[i]
            if (eval.candidateShift != null || eval.hasConflict) continue

            // Candidate section has no anchors. Check if exactly one section is missing among 3 sections.
            if (resolved.size != 3) continue

            val otherEvals = resolved.filterIndexed { idx, _ -> idx != i }
            val otherShifts = otherEvals.mapNotNull { it.candidateShift }.distinct()

            val canAttemptFallback = otherEvals.all { it.candidateShift != null && !it.hasConflict } &&
                    otherShifts.size == 2 &&
                    otherShifts.all { it in TARGET_WORKING_SHIFTS }

            if (canAttemptFallback && verifyRotationRule) {
                // Verify rotation: remaining shift is TARGET_WORKING_SHIFTS - otherShifts
                val remainingShifts = TARGET_WORKING_SHIFTS - otherShifts.toSet()
                if (remainingShifts.size == 1) {
                    val recoveredShift = remainingShifts.first()
                    val otherSummary = otherEvals.joinToString(" & ") { "Sec ${it.sectionIndex}: ${it.candidateShift}" }
                    val explanation = "Recovered '$recoveredShift' via verified 3-section rotation rule: $otherSummary, uniquely determining '$recoveredShift'. Held for human review."

                    resolved[i] = eval.copy(
                        candidateShift = recoveredShift,
                        inferenceMethod = "verified_rotation",
                        reviewReason = explanation
                    )
                }
            } else {
                resolved[i] = eval.copy(
                    candidateShift = null,
                    inferenceMethod = "unresolved",
                    reviewReason = if (!verifyRotationRule) {
                        "No reliable working shift observed in section and rotation rule verification disabled; left unresolved"
                    } else {
                        "No reliable working shift observed in section and 3-section rotation could not be uniquely verified; left unresolved"
                    }
                )
            }
        }

        return resolved
    }

    data class RotationValidationResult(
        val isValid: Boolean,
        val isComplete: Boolean,
        val remainingShifts: List<String>,
        val promptMessage: String?
    )

    fun validateRotationConstraint(summaries: List<SectionSummary>): RotationValidationResult {
        val assigned = summaries.mapNotNull { it.proposedShift }
        val distinctAssigned = assigned.distinct()
        val allWorking = TARGET_WORKING_SHIFTS.toList()
        val remaining = allWorking.filter { it !in distinctAssigned }

        return when {
            assigned.size == 3 && distinctAssigned.size == 3 -> {
                RotationValidationResult(
                    isValid = true,
                    isComplete = true,
                    remainingShifts = emptyList(),
                    promptMessage = "All 3 section shifts confirmed with verified rotation."
                )
            }
            assigned.size < 3 && distinctAssigned.size == assigned.size -> {
                val missingCount = 3 - assigned.size
                val msg = if (missingCount == 1) {
                    "Section 3 shift uniquely determined as '${remaining.firstOrNull()}'. Confirm to apply."
                } else {
                    "Only ${assigned.size} section shift known (${assigned.joinToString(", ")}). Missing shifts (${remaining.joinToString(", ")}) are not uniquely determined. Please confirm missing sections."
                }
                RotationValidationResult(
                    isValid = true,
                    isComplete = false,
                    remainingShifts = remaining,
                    promptMessage = msg
                )
            }
            else -> {
                RotationValidationResult(
                    isValid = false,
                    isComplete = assigned.size == 3,
                    remainingShifts = remaining,
                    promptMessage = "Duplicate shifts detected (${assigned.joinToString(", ")}). Please review section assignments."
                )
            }
        }
    }

    /**
     * Normalizes supported user shift selections: M, E, N, OFF, A (or full names),
     * or null when the user explicitly leaves a cell unresolved.
     */
    fun normalizeUserSelectedShift(shiftInput: String?): String? {
        return when (shiftInput?.trim()?.uppercase(Locale.US)) {
            "M", "MORNING" -> "Morning"
            "E", "EVENING" -> "Evening"
            "N", "NIGHT" -> "Night"
            "OFF", "0FF" -> "Off"
            "A", "ABSENT", "LEAVE" -> "A"
            null, "", "UNRESOLVED", "UNKNOWN" -> null
            else -> shiftInput.trim().takeIf { it in TARGET_WORKING_SHIFTS }
        }
    }

    /**
     * Applies manual edit to a single cell, strictly preserving manual provenance
     * and preserving the original OCR rawValue/diagnostics.
     */
    fun updateCellManual(currentSchedule: DraftSchedule, dayNumber: Int, newShift: String?): DraftSchedule {
        val canonicalShift = normalizeUserSelectedShift(newShift)
        val updatedEntries = currentSchedule.entries.map { cell ->
            if (cell.dayNumber == dayNumber) {
                when (canonicalShift) {
                    "A" -> cell.copy(
                        normalizedShift = "A",
                        classification = "A",
                        sourceType = "manual",
                        reviewStatus = "confirmed",
                        reviewReason = "Manually set to A (Leave/Absent)"
                    )
                    "Off" -> cell.copy(
                        normalizedShift = "Off",
                        classification = "OFF",
                        sourceType = "manual",
                        reviewStatus = "confirmed",
                        reviewReason = "Manually set to Off"
                    )
                    in TARGET_WORKING_SHIFTS -> cell.copy(
                        normalizedShift = canonicalShift,
                        classification = canonicalShift!!.take(1),
                        sourceType = "manual",
                        reviewStatus = "confirmed",
                        reviewReason = "Manually set to $canonicalShift"
                    )
                    else -> cell.copy(
                        normalizedShift = null,
                        classification = "UNRESOLVED",
                        sourceType = "unresolved",
                        reviewStatus = "unresolved",
                        reviewReason = "Left unresolved by user"
                    )
                }
            } else cell
        }

        return recomputeScheduleCounts(currentSchedule, updatedEntries)
    }

    /**
     * Accepts and confirms a rule-inferred shift (or a reviewable candidate shift),
     * retaining its inferred shift value and inference rule while marking it user-confirmed.
     */
    fun acceptInferredCell(currentSchedule: DraftSchedule, dayNumber: Int): DraftSchedule {
        val updatedEntries = currentSchedule.entries.map { cell ->
            if (cell.dayNumber == dayNumber) {
                val candidateShift = cell.normalizedShift
                    ?: if (cell.classification == "A" || cell.rawValue?.trim()?.uppercase(Locale.US) == "A") "A" else null

                if (candidateShift != null) {
                    val preservedSource = when {
                        cell.isInferred -> cell.sourceType
                        cell.sourceType == "conflicting" -> "directly_observed"
                        cell.isObserved -> cell.sourceType
                        else -> cell.sourceType
                    }
                    val preservedRule = cell.inferenceRule ?: if (cell.isInferred) cell.sourceType else null
                    val baseReason = cell.reviewReason?.removePrefix("User accepted: ")?.trim()
                    val confirmedReason = when {
                        cell.isInferred && !baseReason.isNullOrBlank() -> "User accepted: $baseReason"
                        cell.isInferred -> "User accepted inferred shift ($candidateShift)"
                        else -> "User confirmed shift ($candidateShift)"
                    }
                    cell.copy(
                        normalizedShift = candidateShift,
                        classification = when (candidateShift) {
                            "Morning" -> "M"
                            "Evening" -> "E"
                            "Night" -> "N"
                            "Off" -> "OFF"
                            "A" -> "A"
                            else -> cell.classification
                        },
                        sourceType = preservedSource,
                        reviewStatus = "confirmed",
                        inferenceRule = preservedRule,
                        reviewReason = confirmedReason
                    )
                } else {
                    cell
                }
            } else cell
        }

        return recomputeScheduleCounts(currentSchedule, updatedEntries)
    }

    /**
     * Accepts all pending rule-inferred shifts in the schedule, marking them confirmed.
     */
    fun acceptAllInferredCells(currentSchedule: DraftSchedule): DraftSchedule {
        val updatedEntries = currentSchedule.entries.map { cell ->
            if (cell.isInferred && cell.normalizedShift != null && !cell.isConfirmed) {
                val baseReason = cell.reviewReason?.removePrefix("User accepted: ")?.trim()
                cell.copy(
                    reviewStatus = "confirmed",
                    inferenceRule = cell.inferenceRule ?: cell.sourceType,
                    reviewReason = if (!baseReason.isNullOrBlank()) "User accepted: $baseReason" else "User accepted inferred shift (${cell.normalizedShift})"
                )
            } else cell
        }
        return recomputeScheduleCounts(currentSchedule, updatedEntries)
    }

    /**
     * Merges an existing schedule with a newly generated OCR/inferred schedule,
     * ensuring manual corrections and user-confirmed inferred shifts are never overwritten.
     */
    fun mergePreservingUserActions(existingSchedule: DraftSchedule?, newSchedule: DraftSchedule): DraftSchedule {
        if (existingSchedule == null) return newSchedule
        val existingByDay = existingSchedule.entries.associateBy { it.dayNumber }

        val mergedEntries = newSchedule.entries.map { newCell ->
            val oldCell = existingByDay[newCell.dayNumber]
            when {
                oldCell != null && oldCell.isManual -> {
                    // Never allow subsequent OCR or inference to overwrite a manual correction
                    oldCell.copy(
                        rawValue = oldCell.rawValue ?: newCell.rawValue,
                        ocrBoundingBox = oldCell.ocrBoundingBox ?: newCell.ocrBoundingBox,
                        ocrConfidence = oldCell.ocrConfidence ?: newCell.ocrConfidence,
                        matchDistance = oldCell.matchDistance ?: newCell.matchDistance
                    )
                }
                oldCell != null && oldCell.isUserConfirmedInferred && !newCell.isManual -> {
                    // Retain user-accepted inferred shift
                    oldCell.copy(
                        rawValue = oldCell.rawValue ?: newCell.rawValue
                    )
                }
                else -> newCell
            }
        }

        return recomputeScheduleCounts(newSchedule, mergedEntries)
    }

    /**
     * Evaluates whether the schedule is eligible for final approval.
     * - Blocks approval if any unconfirmed inferred, needs_review, ambiguous, or conflicting items remain.
     * - Blocks approval if any unresolved/missing cells remain unless explicitly acknowledged by the user.
     */
    fun canApproveSchedule(schedule: DraftSchedule, acknowledgeExplicitUnresolved: Boolean = false): Boolean {
        if (schedule.entries.isEmpty()) return false
        if (schedule.unreviewedActionCount > 0) return false
        if (schedule.explicitUnresolvedCellCount > 0 && !acknowledgeExplicitUnresolved) return false
        return true
    }

    private fun recomputeScheduleCounts(
        baseSchedule: DraftSchedule,
        updatedEntries: List<ExtractedCell>,
        updatedSummaries: List<SectionSummary> = baseSchedule.sectionSummaries
    ): DraftSchedule {
        val unres = updatedEntries.filter { it.isUnresolved }.map { it.dayNumber }
        val directCount = updatedEntries.count { it.isObserved }
        val inferredCount = updatedEntries.count { it.isInferred }
        val conflictCount = updatedEntries.count { it.sourceType == "conflicting" }
        val missingCount = updatedEntries.count { it.reviewStatus in listOf("missing", "unresolved") }

        return baseSchedule.copy(
            entries = updatedEntries,
            sectionSummaries = updatedSummaries,
            unresolvedDates = unres,
            directEvidenceCount = directCount,
            inferredEvidenceCount = inferredCount,
            conflictCount = conflictCount,
            missingCount = missingCount
        )
    }

    /**
     * Overrides a section's shift while strictly preserving manual edits, directly observed shifts, explicit OFF, and 'A'.
     */
    fun overrideSectionShift(currentSchedule: DraftSchedule, sectionIndex: Int, newShift: String?): DraftSchedule {
        val targetSummary = currentSchedule.sectionSummaries.find { it.sectionIndex == sectionIndex }
            ?: return currentSchedule
        val startDay = targetSummary.startDay
        val endDay = targetSummary.endDay

        val updatedEntries = currentSchedule.entries.map { cell ->
            if (cell.dayNumber in startDay..endDay) {
                // Rule 1: Never overwrite manual corrections
                if (cell.sourceType == "manual" || cell.sourceType == "manual_override") return@map cell

                // Rule 2: Preserve explicit non-target shift 'A'
                if (cell.classification == "A" || cell.normalizedShift == "A") return@map cell

                // Rule 3: Preserve explicit OFF or propagated OFF
                if (cell.classification == "OFF" || cell.normalizedShift == "Off") return@map cell

                // Rule 4: Preserve directly observed shifts, flag conflict if different
                if (cell.sourceType == "directly_observed" && cell.normalizedShift in TARGET_WORKING_SHIFTS) {
                    if (newShift != null && cell.normalizedShift != newShift) {
                        return@map cell.copy(
                            sourceType = "conflicting",
                            reviewStatus = "needs_review",
                            reviewReason = "Observed ${cell.normalizedShift} conflicts with section override ($newShift); directly observed value preserved."
                        )
                    }
                    return@map cell
                }

                // Rule 5: For missing/inferred cells, apply new section shift or mark unresolved
                if (newShift != null && newShift in TARGET_WORKING_SHIFTS) {
                    cell.copy(
                        normalizedShift = newShift,
                        classification = newShift.take(1),
                        sourceType = "inferred_from_section_anchor",
                        reviewStatus = "inferred_needs_confirmation",
                        inferenceRule = "manual_section_override",
                        reviewReason = "Applied from section shift override ($newShift); requires review"
                    )
                } else {
                    cell.copy(
                        normalizedShift = null,
                        classification = "UNRESOLVED",
                        sourceType = "unresolved",
                        reviewStatus = "unresolved",
                        reviewReason = "Section shift set to Unknown; cell left unresolved"
                    )
                }
            } else cell
        }

        val updatedSummaries = currentSchedule.sectionSummaries.map { summary ->
            if (summary.sectionIndex == sectionIndex) {
                summary.copy(
                    proposedShift = newShift,
                    inferenceMethod = if (newShift != null) "manual_section_override" else "unresolved",
                    reviewReason = if (newShift != null) "Manually confirmed shift '$newShift'" else "User set to Unknown"
                )
            } else summary
        }

        val unres = updatedEntries.filter { it.isUnresolved }.map { it.dayNumber }
        val directCount = updatedEntries.count { it.sourceType == "directly_observed" }
        val inferredCount = updatedEntries.count { it.sourceType in listOf("inferred_from_section_anchor", "inferred_from_weekly_off", "inferred_from_verified_rotation") }
        val conflictCount = updatedEntries.count { it.sourceType == "conflicting" }
        val missingCount = updatedEntries.count { it.reviewStatus in listOf("missing", "unresolved") }

        return currentSchedule.copy(
            entries = updatedEntries,
            sectionSummaries = updatedSummaries,
            unresolvedDates = unres,
            directEvidenceCount = directCount,
            inferredEvidenceCount = inferredCount,
            conflictCount = conflictCount,
            missingCount = missingCount
        )
    }

    /**
     * Changes the weekly Off weekday with affected dates preview.
     * Preserves manual edits, 'A', and directly observed shifts (flags conflict instead of overwriting).
     */
    fun overrideWeeklyOff(
        currentSchedule: DraftSchedule,
        newOffWeekday: String?,
        year: Int,
        month: Int
    ): Pair<DraftSchedule, List<Int>> {
        val affectedDates = mutableListOf<Int>()
        val cal = Calendar.getInstance()

        val updatedEntries = currentSchedule.entries.map { cell ->
            // Invariant: Never overwrite manual edits
            if (cell.sourceType == "manual" || cell.sourceType == "manual_override") return@map cell

            cal.set(year, month - 1, cell.dayNumber)
            val dayWeekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: cell.weekday

            if (newOffWeekday != null && dayWeekday.equals(newOffWeekday, ignoreCase = true)) {
                affectedDates.add(cell.dayNumber)

                // Preserve non-target shift 'A'
                if (cell.classification == "A" || cell.normalizedShift == "A") return@map cell

                // Never silently overwrite directly observed working shift
                if (cell.sourceType == "directly_observed" && cell.normalizedShift in TARGET_WORKING_SHIFTS) {
                    return@map cell.copy(
                        sourceType = "conflicting",
                        reviewStatus = "needs_review",
                        reviewReason = "Directly observed ${cell.normalizedShift} conflicts with expected weekly Off ($newOffWeekday); observed shift preserved."
                    )
                }

                // If explicit OFF already observed, keep directly_observed
                if (cell.sourceType == "directly_observed" && cell.normalizedShift == "Off") {
                    return@map cell
                }

                return@map cell.copy(
                    normalizedShift = "Off",
                    classification = "OFF",
                    sourceType = "inferred_from_weekly_off",
                    reviewStatus = "inferred_needs_confirmation",
                    inferenceRule = "weekly_off_override",
                    reviewReason = "Inferred from weekly Off weekday ($newOffWeekday); requires review"
                )
            } else if (cell.sourceType == "inferred_from_weekly_off") {
                // Revert previous weekly Off inference to section candidate
                val sec = currentSchedule.sectionSummaries.find { cell.dayNumber in it.startDay..it.endDay }
                if (sec?.proposedShift != null) {
                    return@map cell.copy(
                        normalizedShift = sec.proposedShift,
                        classification = sec.proposedShift.take(1),
                        sourceType = "inferred_from_section_anchor",
                        reviewStatus = "inferred_needs_confirmation",
                        reviewReason = "Reverted from weekly Off to section candidate (${sec.proposedShift})"
                    )
                } else {
                    return@map cell.copy(
                        normalizedShift = null,
                        classification = "UNRESOLVED",
                        sourceType = "unresolved",
                        reviewStatus = "unresolved",
                        reviewReason = "Weekly Off removed; cell unresolved"
                    )
                }
            } else {
                cell
            }
        }

        val unres = updatedEntries.filter { it.isUnresolved }.map { it.dayNumber }
        val directCount = updatedEntries.count { it.sourceType == "directly_observed" }
        val inferredCount = updatedEntries.count { it.sourceType in listOf("inferred_from_section_anchor", "inferred_from_weekly_off", "inferred_from_verified_rotation") }
        val conflictCount = updatedEntries.count { it.sourceType == "conflicting" }
        val missingCount = updatedEntries.count { it.reviewStatus in listOf("missing", "unresolved") }

        val sched = currentSchedule.copy(
            entries = updatedEntries,
            unresolvedDates = unres,
            directEvidenceCount = directCount,
            inferredEvidenceCount = inferredCount,
            conflictCount = conflictCount,
            missingCount = missingCount
        )

        return sched to affectedDates
    }

    private data class MutableEntry(
        val dayNumber: Int,
        val date: String,
        val weekday: String,
        var rawValue: String?,
        var normalizedShift: String?,
        var reviewStatus: String,
        var reviewReason: String?,
        var confidence: Double,
        var sourceType: String,
        var inferenceRule: String? = null,
        var supportingDates: List<Int> = emptyList(),
        var supportingRawTokens: List<String> = emptyList(),
        var sectionStartDay: Int? = null,
        var sectionEndDay: Int? = null,
        val anchorType: String = "",
        val anchorConfidence: String = "",
        var ocrBoundingBox: String? = null,
        var matchDistance: Float? = null,
        var ocrConfidence: Double? = null,
        var classification: String = ""
    ) {
        fun toExtractedCell() = ExtractedCell(
            dayNumber = dayNumber,
            date = date,
            weekday = weekday,
            rawValue = rawValue,
            normalizedShift = normalizedShift,
            reviewStatus = reviewStatus,
            reviewReason = reviewReason,
            confidence = confidence,
            sourceType = sourceType,
            supportingDates = supportingDates,
            supportingRawTokens = supportingRawTokens,
            inferenceRule = inferenceRule,
            sectionStartDay = sectionStartDay,
            sectionEndDay = sectionEndDay,
            classification = classification,
            ocrBoundingBox = ocrBoundingBox,
            matchDistance = matchDistance,
            ocrConfidence = ocrConfidence
        )
    }
}
