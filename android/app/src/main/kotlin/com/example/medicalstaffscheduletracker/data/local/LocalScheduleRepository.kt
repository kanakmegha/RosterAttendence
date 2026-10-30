package com.example.medicalstaffscheduletracker.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.medicalstaffscheduletracker.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LocalScheduleRepository(context: Context) {
    private val dbHelper = ScheduleDatabase(context)

    suspend fun saveDraftSchedule(
        draft: DraftSchedule,
        isApproved: Boolean = false,
        department: String = "Emergency ICU",
        floor: String = "Floor 2",
        weeklyOff: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Delete existing schedule for this staff/month (or matching schedule ID), while preserving user actions
            val existingCursor = db.query(
                ScheduleDatabase.TABLE_SCHEDULES,
                arrayOf("id"),
                "(staff_id = ? AND month = ? AND is_approved = ?) OR id = ?",
                arrayOf(draft.staffId, draft.month, if (isApproved) "1" else "0", draft.scheduleId),
                null, null, null
            )
            val toDeleteIds = mutableListOf<String>()
            while (existingCursor.moveToNext()) {
                toDeleteIds.add(existingCursor.getString(0))
            }
            existingCursor.close()

            // Collect manual overrides and user-confirmed inferred shifts from previous draft so they survive reprocessing
            val preservedManualEntries = mutableMapOf<Int, ContentValues>()
            val preservedConfirmedInferredEntries = mutableMapOf<Int, ContentValues>()
            for (delId in toDeleteIds.distinct()) {
                val mCursor = db.query(
                    ScheduleDatabase.TABLE_ENTRIES,
                    null,
                    "schedule_id = ?",
                    arrayOf(delId),
                    null, null, null
                )
                while (mCursor.moveToNext()) {
                    val dNum = mCursor.getInt(mCursor.getColumnIndexOrThrow("day_number"))
                    val srcType = mCursor.getString(mCursor.getColumnIndexOrThrow("source_type")) ?: ""
                    val revStatus = mCursor.getString(mCursor.getColumnIndexOrThrow("review_status")) ?: ""
                    val cv = ContentValues().apply {
                        put("day_number", dNum)
                        put("date", mCursor.getString(mCursor.getColumnIndexOrThrow("date")))
                        put("weekday", mCursor.getString(mCursor.getColumnIndexOrThrow("weekday")))
                        put("raw_value", mCursor.getString(mCursor.getColumnIndexOrThrow("raw_value")))
                        put("normalized_shift", mCursor.getString(mCursor.getColumnIndexOrThrow("normalized_shift")))
                        put("review_status", revStatus)
                        put("review_reason", mCursor.getString(mCursor.getColumnIndexOrThrow("review_reason")))
                        put("confidence", mCursor.getDouble(mCursor.getColumnIndexOrThrow("confidence")))
                        put("source_type", srcType)
                        put("inference_rule", mCursor.getString(mCursor.getColumnIndexOrThrow("inference_rule")))
                    }
                    if (srcType in listOf("manual", "manual_override")) {
                        preservedManualEntries[dNum] = cv
                    } else if (revStatus in listOf("confirmed", "user_confirmed") &&
                        srcType in listOf(
                            "section_inference",
                            "inferred_from_section_anchor",
                            "inferred_from_weekly_off",
                            "inferred_from_verified_rotation",
                            "off_propagation"
                        )
                    ) {
                        preservedConfirmedInferredEntries[dNum] = cv
                    }
                }
                mCursor.close()

                db.delete(ScheduleDatabase.TABLE_ENTRIES, "schedule_id = ?", arrayOf(delId))
                db.delete(ScheduleDatabase.TABLE_SCHEDULES, "id = ?", arrayOf(delId))
            }

            val scheduleId = draft.scheduleId.ifBlank { UUID.randomUUID().toString() }
            val now = System.currentTimeMillis()
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(now))

            val schedCv = ContentValues().apply {
                put("id", scheduleId)
                put("month", draft.month)
                put("staff_id", draft.staffId)
                put("staff_name", draft.staffName)
                put("department", department)
                put("floor", floor)
                put("weekly_off", weeklyOff)
                put("is_approved", if (isApproved) 1 else 0)
                put("approved_at", if (isApproved) nowIso else null)
                put("is_confusable", if (draft.isConfusableId) 1 else 0)
                put("confusable_message", draft.confusableMessage)
                put("created_at", now)
            }
            db.insertOrThrow(ScheduleDatabase.TABLE_SCHEDULES, null, schedCv)

            for (entry in draft.entries) {
                val manualOverride = preservedManualEntries[entry.dayNumber]
                val confirmedInferred = preservedConfirmedInferredEntries[entry.dayNumber]
                val entryId = UUID.randomUUID().toString()
                val entryCv = if (manualOverride != null && !entry.isManual) {
                    // Retain previously verified manual override
                    manualOverride.apply {
                        put("id", entryId)
                        put("schedule_id", scheduleId)
                        if (getAsString("raw_value").isNullOrBlank() && !entry.rawValue.isNullOrBlank()) {
                            put("raw_value", entry.rawValue)
                        }
                        put("supporting_dates", JSONArray(entry.supportingDates).toString())
                        put("supporting_raw_tokens", JSONArray(entry.supportingRawTokens).toString())
                        put("section_start_day", entry.sectionStartDay)
                        put("section_end_day", entry.sectionEndDay)
                    }
                } else if (confirmedInferred != null && entry.requiresInferredConfirmation && !entry.isManual) {
                    // Retain previously accepted inferred value
                    confirmedInferred.apply {
                        put("id", entryId)
                        put("schedule_id", scheduleId)
                        if (getAsString("raw_value").isNullOrBlank() && !entry.rawValue.isNullOrBlank()) {
                            put("raw_value", entry.rawValue)
                        }
                        put("supporting_dates", JSONArray(entry.supportingDates).toString())
                        put("supporting_raw_tokens", JSONArray(entry.supportingRawTokens).toString())
                        put("section_start_day", entry.sectionStartDay)
                        put("section_end_day", entry.sectionEndDay)
                    }
                } else {
                    ContentValues().apply {
                        put("id", entryId)
                        put("schedule_id", scheduleId)
                        put("day_number", entry.dayNumber)
                        put("date", entry.date)
                        put("weekday", entry.weekday)
                        put("raw_value", entry.rawValue)
                        put("normalized_shift", entry.normalizedShift)
                        put("review_status", entry.reviewStatus)
                        put("review_reason", entry.reviewReason)
                        put("confidence", entry.confidence)
                        put("source_type", entry.sourceType)
                        put("inference_rule", entry.inferenceRule)
                        put("supporting_dates", JSONArray(entry.supportingDates).toString())
                        put("supporting_raw_tokens", JSONArray(entry.supportingRawTokens).toString())
                        put("section_start_day", entry.sectionStartDay)
                        put("section_end_day", entry.sectionEndDay)
                    }
                }
                db.insertOrThrow(ScheduleDatabase.TABLE_ENTRIES, null, entryCv)
            }

            db.setTransactionSuccessful()
            Result.success(scheduleId)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getScheduleById(scheduleId: String): DraftSchedule? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ScheduleDatabase.TABLE_SCHEDULES,
            null,
            "id = ?",
            arrayOf(scheduleId),
            null, null, null
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return@withContext null
        }

        val draft = mapCursorToDraftSchedule(cursor, db)
        cursor.close()
        draft
    }

    suspend fun getLatestSavedSchedule(): DraftSchedule? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ScheduleDatabase.TABLE_SCHEDULES,
            null,
            null,
            null,
            null, null, "created_at DESC", "1"
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return@withContext null
        }
        val draft = mapCursorToDraftSchedule(cursor, db)
        cursor.close()
        draft
    }

    suspend fun getActiveSchedule(month: String? = null, staffId: String? = null): DraftSchedule? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val activeStaffId = staffId ?: getPreference("active_staff_id")
        val activeMonth = month ?: getPreference("active_month")

        if (activeStaffId.isNullOrBlank() || activeMonth.isNullOrBlank()) {
            // Fresh install or no preference set: check if any schedule was previously saved
            return@withContext getLatestSavedSchedule()
        }

        // First check approved schedule
        var cursor = db.query(
            ScheduleDatabase.TABLE_SCHEDULES,
            null,
            "month = ? AND staff_id = ? AND is_approved = 1",
            arrayOf(activeMonth, activeStaffId),
            null, null, "created_at DESC", "1"
        )

        // If no approved, check latest draft
        if (!cursor.moveToFirst()) {
            cursor.close()
            cursor = db.query(
                ScheduleDatabase.TABLE_SCHEDULES,
                null,
                "month = ? AND staff_id = ?",
                arrayOf(activeMonth, activeStaffId),
                null, null, "created_at DESC", "1"
            )
            if (!cursor.moveToFirst()) {
                cursor.close()
                return@withContext null
            }
        }

        val draft = mapCursorToDraftSchedule(cursor, db)
        cursor.close()
        draft
    }

    suspend fun approveSchedule(context: Context, scheduleId: String): Result<File?> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        try {
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
            val cv = ContentValues().apply {
                put("is_approved", 1)
                put("approved_at", nowIso)
            }
            val rows = db.update(
                ScheduleDatabase.TABLE_SCHEDULES,
                cv,
                "id = ?",
                arrayOf(scheduleId)
            )

            if (rows > 0) {
                val approvedSchedule = getScheduleById(scheduleId)
                if (approvedSchedule != null) {
                    setPreference("active_staff_id", approvedSchedule.staffId)
                    setPreference("active_month", approvedSchedule.month)
                    // Synchronize Excel .xlsx workbook in Documents/MedicalStaffScheduleTracker
                    val fileResult = com.example.medicalstaffscheduletracker.engine.ExcelWorkbookManager
                        .writeApprovedScheduleWorkbook(context, approvedSchedule)
                    return@withContext Result.success(fileResult.getOrNull())
                }
                Result.success(null)
            } else {
                Result.failure<File?>(Exception("Schedule $scheduleId not found for approval"))
            }
        } catch (e: Exception) {
            Result.failure<File?>(e)
        }
    }

    suspend fun syncWorkbookForSchedule(context: Context, scheduleId: String): Result<File> = withContext(Dispatchers.IO) {
        val schedule = getScheduleById(scheduleId)
            ?: return@withContext Result.failure<File>(Exception("Schedule not found"))
        if (!schedule.isApproved) {
            return@withContext Result.failure<File>(Exception("Cannot write workbook for unapproved draft schedule"))
        }
        com.example.medicalstaffscheduletracker.engine.ExcelWorkbookManager
            .writeApprovedScheduleWorkbook(context, schedule)
    }

    suspend fun updateCellShift(
        context: Context? = null,
        scheduleId: String,
        dayNumber: Int,
        shift: String?,
        status: String = "confirmed",
        reason: String? = "Manually edited by user",
        sourceType: String = "manual",
        inferenceRule: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        try {
            val cv = ContentValues().apply {
                put("normalized_shift", shift)
                put("review_status", status)
                put("review_reason", reason)
                put("source_type", sourceType)
                if (inferenceRule != null) {
                    put("inference_rule", inferenceRule)
                }
            }
            db.update(
                ScheduleDatabase.TABLE_ENTRIES,
                cv,
                "schedule_id = ? AND day_number = ?",
                arrayOf(scheduleId, dayNumber.toString())
            )

            // Re-sync workbook if schedule is approved
            if (context != null) {
                val sched = getScheduleById(scheduleId)
                if (sched != null && sched.isApproved) {
                    com.example.medicalstaffscheduletracker.engine.ExcelWorkbookManager
                        .writeApprovedScheduleWorkbook(context, sched)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateExtractedCell(
        context: Context? = null,
        scheduleId: String,
        cell: ExtractedCell
    ): Result<Unit> = updateCellShift(
        context = context,
        scheduleId = scheduleId,
        dayNumber = cell.dayNumber,
        shift = cell.normalizedShift,
        status = cell.reviewStatus,
        reason = cell.reviewReason,
        sourceType = cell.sourceType,
        inferenceRule = cell.inferenceRule
    )

    suspend fun saveManualDuty(
        context: Context? = null,
        duty: ManualDuty,
        activeScheduleId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        try {
            val cv = ContentValues().apply {
                put("id", duty.id.ifBlank { UUID.randomUUID().toString() })
                put("date", duty.date)
                put("shift", duty.shift)
                put("department", duty.department)
                put("floor", duty.floor)
                put("notes", duty.notes)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                ScheduleDatabase.TABLE_MANUAL_DUTIES,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            if (context != null && activeScheduleId != null) {
                val sched = getScheduleById(activeScheduleId)
                if (sched != null && sched.isApproved) {
                    // Update entry in schedule_entries if exists for that date
                    val dayNum = duty.date.split("-").getOrNull(2)?.toIntOrNull()
                    if (dayNum != null) {
                        val entryCv = ContentValues().apply {
                            put("normalized_shift", duty.shift)
                            put("review_status", "confirmed")
                            put("source_type", "manual")
                            put("review_reason", "Manual emergency duty (${duty.notes ?: "Updated"})")
                        }
                        db.update(
                            ScheduleDatabase.TABLE_ENTRIES,
                            entryCv,
                            "schedule_id = ? AND day_number = ?",
                            arrayOf(activeScheduleId, dayNum.toString())
                        )
                    }
                    val updatedSched = getScheduleById(activeScheduleId)
                    if (updatedSched != null) {
                        com.example.medicalstaffscheduletracker.engine.ExcelWorkbookManager
                            .writeApprovedScheduleWorkbook(context, updatedSched)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getManualDutiesForMonth(monthPrefix: String): List<ManualDuty> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ScheduleDatabase.TABLE_MANUAL_DUTIES,
            null,
            "date LIKE ?",
            arrayOf("$monthPrefix%"),
            null, null, "date ASC"
        )
        val list = mutableListOf<ManualDuty>()
        while (cursor.moveToNext()) {
            list.add(
                ManualDuty(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    date = cursor.getString(cursor.getColumnIndexOrThrow("date")),
                    shift = cursor.getString(cursor.getColumnIndexOrThrow("shift")),
                    department = cursor.getString(cursor.getColumnIndexOrThrow("department")),
                    floor = cursor.getString(cursor.getColumnIndexOrThrow("floor")),
                    notes = cursor.getString(cursor.getColumnIndexOrThrow("notes"))
                )
            )
        }
        cursor.close()
        list
    }

    suspend fun getPreference(key: String): String? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ScheduleDatabase.TABLE_PREFERENCES,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null, null, null
        )
        var result: String? = null
        if (cursor.moveToFirst()) {
            result = cursor.getString(0)
        }
        cursor.close()
        result
    }

    suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(
            ScheduleDatabase.TABLE_PREFERENCES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun mapCursorToDraftSchedule(cursor: Cursor, db: SQLiteDatabase): DraftSchedule {
        val scheduleId = cursor.getString(cursor.getColumnIndexOrThrow("id"))
        val month = cursor.getString(cursor.getColumnIndexOrThrow("month"))
        val staffId = cursor.getString(cursor.getColumnIndexOrThrow("staff_id"))
        val staffName = cursor.getString(cursor.getColumnIndexOrThrow("staff_name"))
        val isApproved = cursor.getInt(cursor.getColumnIndexOrThrow("is_approved")) == 1
        val approvedAt = cursor.getString(cursor.getColumnIndexOrThrow("approved_at"))
        val isConfusable = cursor.getInt(cursor.getColumnIndexOrThrow("is_confusable")) == 1
        val confusableMsg = cursor.getString(cursor.getColumnIndexOrThrow("confusable_message"))

        val entriesCursor = db.query(
            ScheduleDatabase.TABLE_ENTRIES,
            null,
            "schedule_id = ?",
            arrayOf(scheduleId),
            null, null, "day_number ASC"
        )
        val entries = mutableListOf<ExtractedCell>()
        val unresolvedDates = mutableListOf<Int>()

        while (entriesCursor.moveToNext()) {
            val dayNumber = entriesCursor.getInt(entriesCursor.getColumnIndexOrThrow("day_number"))
            val date = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("date"))
            val weekday = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("weekday"))
            val rawVal = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("raw_value"))
            val normShift = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("normalized_shift"))
            val status = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("review_status"))
            val reason = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("review_reason"))
            val conf = entriesCursor.getDouble(entriesCursor.getColumnIndexOrThrow("confidence"))
            val src = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("source_type"))
            val rule = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("inference_rule"))
            val supDatesStr = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("supporting_dates"))
            val supTokensStr = entriesCursor.getString(entriesCursor.getColumnIndexOrThrow("supporting_raw_tokens"))
            val secStart = if (entriesCursor.isNull(entriesCursor.getColumnIndexOrThrow("section_start_day"))) null else entriesCursor.getInt(entriesCursor.getColumnIndexOrThrow("section_start_day"))
            val secEnd = if (entriesCursor.isNull(entriesCursor.getColumnIndexOrThrow("section_end_day"))) null else entriesCursor.getInt(entriesCursor.getColumnIndexOrThrow("section_end_day"))

            val supDates = mutableListOf<Int>()
            if (!supDatesStr.isNullOrBlank()) {
                val arr = JSONArray(supDatesStr)
                for (i in 0 until arr.length()) supDates.add(arr.getInt(i))
            }
            val supTokens = mutableListOf<String>()
            if (!supTokensStr.isNullOrBlank()) {
                val arr = JSONArray(supTokensStr)
                for (i in 0 until arr.length()) supTokens.add(arr.getString(i))
            }

            val rawClean = rawVal?.trim()?.uppercase(Locale.US) ?: ""
            val classif = when {
                normShift == "Morning" -> "M"
                normShift == "Evening" -> "E"
                normShift == "Night" -> "N"
                normShift == "Off" -> "OFF"
                normShift == "A" || rawClean == "A" -> "A"
                src == "conflicting" -> "CONFLICT"
                else -> "UNRESOLVED"
            }

            val cell = ExtractedCell(
                dayNumber = dayNumber,
                date = date,
                weekday = weekday,
                rawValue = rawVal,
                normalizedShift = normShift,
                reviewStatus = status,
                reviewReason = reason,
                confidence = conf,
                sourceType = src,
                supportingDates = supDates,
                supportingRawTokens = supTokens,
                inferenceRule = rule,
                sectionStartDay = secStart,
                sectionEndDay = secEnd,
                classification = classif
            )
            entries.add(cell)
            if (cell.isUnresolved) {
                unresolvedDates.add(dayNumber)
            }
        }
        entriesCursor.close()

        // Generate section summaries from entries
        val sectionSummaries = generateSectionSummariesFromEntries(entries)
        val directCount = entries.count { it.isObserved }
        val inferredCount = entries.count { it.isInferred }
        val conflictCount = entries.count { it.sourceType == "conflicting" }
        val missingCount = entries.count { it.reviewStatus in listOf("missing", "unresolved") }

        return DraftSchedule(
            scheduleId = scheduleId,
            staffId = staffId,
            staffName = staffName,
            month = month,
            isApproved = isApproved,
            approvedAt = approvedAt,
            entries = entries,
            sectionSummaries = sectionSummaries,
            unresolvedDates = unresolvedDates,
            isConfusableId = isConfusable,
            confusableMessage = confusableMsg,
            directEvidenceCount = directCount,
            inferredEvidenceCount = inferredCount,
            conflictCount = conflictCount,
            missingCount = missingCount
        )
    }

    private fun generateSectionSummariesFromEntries(entries: List<ExtractedCell>): List<SectionSummary> {
        val numDays = entries.size
        if (numDays == 0) return emptyList()

        val sectionDefs = com.example.medicalstaffscheduletracker.engine.LocalSectionInferenceEngine().detectRosterSections(numDays)

        return sectionDefs.map { secDef ->
            val start = secDef.startDay
            val end = secDef.endDay
            val sectionCells = entries.filter { it.dayNumber in start..end }
            val obsTokens = sectionCells.mapNotNull { it.rawValue }.filter { it.isNotBlank() }
            val proposed = sectionCells.mapNotNull { it.normalizedShift }.firstOrNull { it in listOf("Morning", "Evening", "Night") }
            val unres = sectionCells.filter { it.isUnresolved }.map { it.dayNumber }
            val conflicts = sectionCells.mapNotNull { it.reviewReason }.filter { "conflict" in it.lowercase(Locale.US) }

            SectionSummary(
                sectionIndex = secDef.sectionIndex,
                startDay = start,
                endDay = end,
                totalDays = secDef.dayCount,
                proposedShift = proposed,
                observedTokens = obsTokens,
                unknownOrConflictingTokens = conflicts,
                datesEligible = sectionCells.size,
                unresolvedDates = unres,
                reviewReason = conflicts.firstOrNull()
            )
        }
    }
}
