package com.example.medicalstaffscheduletracker.data.api

import com.example.medicalstaffscheduletracker.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class RosterApiClient(
    var baseUrl: String = "http://10.0.2.2:5001/api"
) {
    var authToken: String? = null
    var currentUser: StaffUser? = null

    suspend fun login(staffId: String, password: String): Result<StaffUser> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/auth/login")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            val body = JSONObject().apply {
                put("staff_id", staffId)
                put("password", password)
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 200) {
                val json = JSONObject(responseText)
                authToken = json.getString("token")
                val uJson = json.getJSONObject("user")
                val user = StaffUser(
                    staffId = uJson.getString("staff_id"),
                    name = uJson.getString("name"),
                    role = uJson.getString("role"),
                    department = uJson.optString("department", "Emergency ICU"),
                    floor = uJson.optString("floor", "Floor 2")
                )
                currentUser = user
                Result.success(user)
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "Login failed ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadRoster(
        fileBytes: ByteArray,
        filename: String,
        month: String = "2026-09",
        inferSections: Boolean = true
    ): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            val boundary = "==Boundary-${System.currentTimeMillis()}=="
            val url = URL("$baseUrl/roster/upload")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                doOutput = true
                connectTimeout = 60000
                readTimeout = 60000
            }

            val outputStream = conn.outputStream
            val writer = outputStream.bufferedWriter(StandardCharsets.UTF_8)

            // Parameter: month
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"month\"\r\n\r\n")
            writer.write("$month\r\n")

            // Parameter: infer_sections
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"infer_sections\"\r\n\r\n")
            writer.write("${if (inferSections) "true" else "false"}\r\n")

            // File Part: roster_file
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"roster_file\"; filename=\"$filename\"\r\n")
            writer.write("Content-Type: application/octet-stream\r\n\r\n")
            writer.flush()

            outputStream.write(fileBytes)
            outputStream.flush()

            writer.write("\r\n--$boundary--\r\n")
            writer.flush()

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 201 || responseCode == 200) {
                val json = JSONObject(responseText)
                val scheduleId = json.getString("schedule_id")
                // Now fetch the full draft details
                getDraftSchedule(scheduleId)
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "Upload failed ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDraftSchedule(scheduleId: String): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/schedule/draft/$scheduleId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 200) {
                val json = JSONObject(responseText)
                val draftJson = json.getJSONObject("draft")
                val reconJson = draftJson.getJSONObject("reconstruction")
                val schedule = parseDraftSchedule(draftJson, reconJson)
                Result.success(schedule)
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "Failed to retrieve draft ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reviewCell(
        scheduleId: String,
        dayNumber: Int,
        shift: String,
        notes: String = "Confirmed by user"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/schedule/review")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            val body = JSONObject().apply {
                put("schedule_id", scheduleId)
                put("day_number", dayNumber)
                put("shift", shift)
                put("notes", notes)
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 200) {
                Result.success(Unit)
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "Review failed ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun approveSchedule(scheduleId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/schedule/approve")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            val body = JSONObject().apply {
                put("schedule_id", scheduleId)
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 200) {
                val json = JSONObject(responseText)
                Result.success(json.optBoolean("is_approved", true))
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "Approval failed ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getApprovedSchedule(staffId: String, month: String): Result<DraftSchedule> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/schedule/approved/$staffId/$month")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode == 200) {
                val json = JSONObject(responseText)
                val scheduleJson = json.getJSONObject("schedule")
                val reconJson = scheduleJson.getJSONObject("reconstruction")
                val schedule = parseDraftSchedule(scheduleJson, reconJson)
                Result.success(schedule)
            } else {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val errMsg = errJson?.optString("error") ?: "No approved schedule found ($responseCode)"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseDraftSchedule(draftJson: JSONObject, reconJson: JSONObject): DraftSchedule {
        val scheduleId = draftJson.getString("schedule_id")
        val staffId = draftJson.getString("staff_id")
        val staffName = reconJson.optString("staff_name", "")
        val month = draftJson.getString("month")
        val isApproved = draftJson.optBoolean("is_approved", false)
        val approvedAt = if (draftJson.isNull("approved_at")) null else draftJson.optString("approved_at")


        val entriesList = mutableListOf<ExtractedCell>()
        val entriesArr = reconJson.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entriesArr.length()) {
            val item = entriesArr.getJSONObject(i)
            val supDates = mutableListOf<Int>()
            item.optJSONArray("supporting_dates")?.let { arr ->
                for (j in 0 until arr.length()) supDates.add(arr.getInt(j))
            }
            val supTokens = mutableListOf<String>()
            item.optJSONArray("supporting_raw_tokens")?.let { arr ->
                for (j in 0 until arr.length()) supTokens.add(arr.getString(j))
            }

            entriesList.add(
                ExtractedCell(
                    dayNumber = item.getInt("day_number"),
                    date = item.getString("date"),
                    weekday = item.getString("weekday"),
                    rawValue = if (item.isNull("raw_value")) null else item.optString("raw_value"),
                    normalizedShift = if (item.isNull("normalized_shift")) null else item.optString("normalized_shift"),
                    reviewStatus = item.optString("review_status", "missing"),
                    reviewReason = if (item.isNull("review_reason")) null else item.optString("review_reason"),
                    confidence = item.optDouble("confidence", 0.0),
                    sourceType = item.optString("source_type", "ocr_observed"),
                    supportingDates = supDates,
                    supportingRawTokens = supTokens,
                    inferenceRule = if (item.isNull("inference_rule")) null else item.optString("inference_rule"),
                    sectionStartDay = if (item.isNull("section_start_day")) null else item.optInt("section_start_day"),
                    sectionEndDay = if (item.isNull("section_end_day")) null else item.optInt("section_end_day")
                )
            )
        }

        val summariesList = mutableListOf<SectionSummary>()
        val summariesArr = reconJson.optJSONArray("section_summaries")
            ?: reconJson.optJSONObject("section_inference")?.optJSONArray("sections")
            ?: JSONArray()
        for (i in 0 until summariesArr.length()) {
            val s = summariesArr.getJSONObject(i)
            val obsTokens = mutableListOf<String>()
            s.optJSONArray("observed_tokens")?.let { arr ->
                for (j in 0 until arr.length()) obsTokens.add(arr.getString(j))
            }
            val unkTokens = mutableListOf<String>()
            val confArr = s.optJSONArray("conflicting_shifts") ?: s.optJSONArray("unknown_or_conflicting_tokens")
            confArr?.let { arr ->
                for (j in 0 until arr.length()) unkTokens.add(arr.getString(j))
            }
            val unresDates = mutableListOf<Int>()
            s.optJSONArray("unresolved_dates")?.let { arr ->
                for (j in 0 until arr.length()) unresDates.add(arr.getInt(j))
            }

            val totalDays = if (s.has("day_count")) s.getInt("day_count") else s.optInt("total_days", s.getInt("end_day") - s.getInt("start_day") + 1)
            val proposed = when {
                !s.isNull("proposed_shift") -> s.optString("proposed_shift")
                !s.isNull("proposed_section_shift") -> s.optString("proposed_section_shift")
                else -> null
            }
            val eligibleCount = if (s.has("eligible_dates")) {
                s.optJSONArray("eligible_dates")?.length() ?: 0
            } else {
                s.optInt("dates_eligible_for_inference", 0)
            }
            val revReason = when {
                !s.isNull("review_reason") -> s.optString("review_reason")
                s.has("review_reasons") -> {
                    val rArr = s.optJSONArray("review_reasons")
                    if (rArr != null && rArr.length() > 0) {
                        val list = mutableListOf<String>()
                        for (k in 0 until rArr.length()) list.add(rArr.getString(k))
                        list.joinToString("; ")
                    } else null
                }
                else -> null
            }

            summariesList.add(
                SectionSummary(
                    sectionIndex = s.getInt("section_index"),
                    startDay = s.getInt("start_day"),
                    endDay = s.getInt("end_day"),
                    totalDays = totalDays,
                    proposedShift = proposed,
                    observedTokens = obsTokens,
                    unknownOrConflictingTokens = unkTokens,
                    datesEligible = eligibleCount,
                    unresolvedDates = unresDates,
                    reviewReason = revReason
                )
            )
        }

        val unresolvedDates = mutableListOf<Int>()
        reconJson.optJSONArray("unresolved_dates")?.let { arr ->
            for (i in 0 until arr.length()) unresolvedDates.add(arr.getInt(i))
        }

        val idWarnings = mutableListOf<String>()
        reconJson.optJSONArray("id_warnings")?.let { arr ->
            for (i in 0 until arr.length()) idWarnings.add(arr.getString(i))
        }
        reconJson.optJSONArray("warnings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.getString(i)
                if ("confusion" in w.lowercase() || "confusable" in w.lowercase()) {
                    idWarnings.add(w)
                }
            }
        }
        val matchReason = reconJson.optString("match_reason", "")
        val isConfusableFromReason = "confusion" in matchReason.lowercase() || "confusable" in matchReason.lowercase()
        val isConfusable = idWarnings.isNotEmpty() || (reconJson.optString("id_match_type") == "confusable_flagged") || isConfusableFromReason

        return DraftSchedule(
            scheduleId = scheduleId,
            staffId = staffId,
            staffName = staffName,
            month = month,
            isApproved = isApproved,
            approvedAt = approvedAt,
            entries = entriesList,
            sectionSummaries = summariesList,
            unresolvedDates = unresolvedDates,
            isConfusableId = isConfusable,
            confusableMessage = if (isConfusable) idWarnings.firstOrNull() ?: "Confusable staff ID requires manual review" else null
        )
    }
}
