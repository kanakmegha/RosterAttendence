package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import com.example.medicalstaffscheduletracker.data.model.DraftSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

object RosterExporter {

    suspend fun exportToCsv(
        context: Context,
        schedule: DraftSchedule
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = "Roster_${schedule.staffId}_${schedule.month}.csv"
            val exportDir = File(context.filesDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val file = File(exportDir, fileName)
            FileWriter(file).use { writer ->
                writer.append("Day,Date,Weekday,Duty/Shift,Observed Token,Status,Source,Review Reason\n")
                for (entry in schedule.entries) {
                    val shift = entry.normalizedShift ?: "Unresolved"
                    val raw = entry.rawValue ?: ""
                    val status = entry.reviewStatus
                    val source = entry.sourceType
                    val reason = (entry.reviewReason ?: "").replace(",", ";")

                    writer.append("${entry.dayNumber},${entry.date},${entry.weekday},$shift,\"$raw\",$status,$source,\"$reason\"\n")
                }
            }
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
