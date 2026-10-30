package com.example.medicalstaffscheduletracker.engine

import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import com.example.medicalstaffscheduletracker.data.model.ExtractedCell

data class RosterDiffItem(
    val dayNumber: Int,
    val date: String,
    val weekday: String,
    val originalShift: String?,
    val revisedShift: String?,
    val changeType: String, // "SHIFT_CHANGED", "DUTY_ADDED", "DUTY_REMOVED", "UNCHANGED"
    val description: String
)

data class RosterDiffResult(
    val originalMonth: String,
    val revisedMonth: String,
    val staffId: String,
    val totalChanges: Int,
    val items: List<RosterDiffItem>
)

object LocalRosterDiff {

    fun compare(original: DraftSchedule, revised: DraftSchedule): RosterDiffResult {
        val originalMap = original.entries.associateBy { it.dayNumber }
        val revisedMap = revised.entries.associateBy { it.dayNumber }
        val allDays = (originalMap.keys + revisedMap.keys).distinct().sorted()

        val items = mutableListOf<RosterDiffItem>()
        var changeCount = 0

        for (day in allDays) {
            val orig = originalMap[day]
            val rev = revisedMap[day]

            val origShift = orig?.normalizedShift
            val revShift = rev?.normalizedShift
            val date = rev?.date ?: orig?.date ?: ""
            val weekday = rev?.weekday ?: orig?.weekday ?: ""

            val (type, desc) = when {
                origShift == revShift -> "UNCHANGED" to "No shift change"
                origShift == null && revShift != null -> {
                    changeCount++
                    "DUTY_ADDED" to "Duty assigned: $revShift"
                }
                origShift != null && revShift == null -> {
                    changeCount++
                    "DUTY_REMOVED" to "Duty removed (was $origShift)"
                }
                else -> {
                    changeCount++
                    "SHIFT_CHANGED" to "Shift changed from $origShift to $revShift"
                }
            }

            items.add(
                RosterDiffItem(
                    dayNumber = day,
                    date = date,
                    weekday = weekday,
                    originalShift = origShift,
                    revisedShift = revShift,
                    changeType = type,
                    description = desc
                )
            )
        }

        return RosterDiffResult(
            originalMonth = original.month,
            revisedMonth = revised.month,
            staffId = revised.staffId,
            totalChanges = changeCount,
            items = items
        )
    }
}
