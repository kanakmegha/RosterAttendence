package com.example.medicalstaffscheduletracker.data.model

data class StaffUser(
    val staffId: String,
    val name: String,
    val role: String,
    val department: String,
    val floor: String
)

data class ExtractedCell(
    val dayNumber: Int,
    val date: String,
    val weekday: String,
    val rawValue: String?,
    val normalizedShift: String?,
    val reviewStatus: String,
    val reviewReason: String?,
    val confidence: Double,
    val sourceType: String,
    val supportingDates: List<Int> = emptyList(),
    val supportingRawTokens: List<String> = emptyList(),
    val inferenceRule: String? = null,
    val sectionStartDay: Int? = null,
    val sectionEndDay: Int? = null,
    val classification: String = "",
    val ocrBoundingBox: String? = null,
    val matchDistance: Float? = null,
    val ocrConfidence: Double? = null
) {
    val isConfirmed: Boolean
        get() = reviewStatus in listOf("confirmed", "user_confirmed", "observed", "auto_accepted")

    val isManual: Boolean
        get() = sourceType in listOf("manual", "manual_override")

    val isObserved: Boolean
        get() = sourceType in listOf("ocr_observed", "directly_observed")

    val isInferred: Boolean
        get() = sourceType in listOf(
            "section_inference",
            "inferred_from_section_anchor",
            "inferred_from_weekly_off",
            "inferred_from_verified_rotation",
            "off_propagation"
        )

    val isUserConfirmedInferred: Boolean
        get() = isInferred && isConfirmed && normalizedShift != null

    val requiresInferredConfirmation: Boolean
        get() = isInferred && !isConfirmed && normalizedShift != null

    val isExplicitlyUnresolved: Boolean
        get() = normalizedShift == null && !(classification == "A" && isConfirmed) &&
            reviewStatus in listOf("unresolved", "missing", "unreadable")

    val isUnresolved: Boolean
        get() = reviewStatus in listOf(
            "unresolved",
            "unreadable",
            "missing",
            "needs_review",
            "inferred_needs_confirmation",
            "ambiguous",
            "conflict"
        ) || (normalizedShift == null && !(classification == "A" && isConfirmed))
}

data class SectionSummary(
    val sectionIndex: Int,
    val startDay: Int,
    val endDay: Int,
    val totalDays: Int,
    val proposedShift: String?,
    val observedTokens: List<String>,
    val unknownOrConflictingTokens: List<String>,
    val datesEligible: Int,
    val unresolvedDates: List<Int>,
    val reviewReason: String?,
    val inferenceMethod: String? = null,
    val directEvidenceCount: Int = 0,
    val directEvidenceTokens: List<String> = emptyList(),
    val contradictoryEvidence: List<String> = emptyList()
)

data class DraftSchedule(
    val scheduleId: String,
    val staffId: String,
    val staffName: String?,
    val month: String,
    val isApproved: Boolean,
    val approvedAt: String?,
    val entries: List<ExtractedCell>,
    val sectionSummaries: List<SectionSummary>,
    val unresolvedDates: List<Int>,
    val isConfusableId: Boolean,
    val confusableMessage: String?,
    val directEvidenceCount: Int = 0,
    val inferredEvidenceCount: Int = 0,
    val conflictCount: Int = 0,
    val missingCount: Int = 0
) {
    val directlyDetectedCount: Int
        get() = entries.count { it.isObserved && it.normalizedShift != null }

    val ruleInferredCount: Int
        get() = entries.count { it.isInferred && it.normalizedShift != null }

    val confirmedInferredCount: Int
        get() = entries.count { it.isUserConfirmedInferred }

    val pendingInferredCount: Int
        get() = entries.count { it.requiresInferredConfirmation }

    val manuallyCorrectedCount: Int
        get() = entries.count { it.isManual && it.normalizedShift != null }

    val unresolvedOrNeedsReviewCount: Int
        get() = entries.count { it.isUnresolved }

    val unreviewedActionCount: Int
        get() = entries.count {
            it.reviewStatus in listOf("inferred_needs_confirmation", "needs_review", "ambiguous", "conflict") ||
                it.sourceType == "conflicting"
        }

    val explicitUnresolvedCellCount: Int
        get() = entries.count {
            it.normalizedShift == null &&
                it.reviewStatus !in listOf("inferred_needs_confirmation", "needs_review", "ambiguous", "conflict") &&
                it.sourceType != "conflicting"
        }

    val confirmedCellCount: Int
        get() = entries.count { !it.isUnresolved && it.normalizedShift != null }

    val reviewProgressFraction: Float
        get() = if (entries.isEmpty()) 0f else confirmedCellCount.toFloat() / entries.size.toFloat()
}

data class ManualDuty(
    val id: String,
    val date: String,
    val shift: String,
    val department: String,
    val floor: String,
    val notes: String?
)
