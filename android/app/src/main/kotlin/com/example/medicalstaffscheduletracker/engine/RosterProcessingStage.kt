package com.example.medicalstaffscheduletracker.engine

import android.util.Log

enum class RosterProcessingStage(val stepNumber: Int, val title: String) {
    IDLE(0, "Ready"),
    FILE_SELECTED(1, "File selected"),
    FILE_VALIDATED(2, "File type validated"),
    PDF_RENDERED(3, "PDF pages rendered"),
    OCR_COMPLETED(4, "On-device OCR completed"),
    DATE_HEADERS_DETECTED(5, "Date headers & month detected"),
    STAFF_ROWS_RECONSTRUCTED(6, "Staff rows reconstructed"),
    AWAITING_STAFF_SELECTION(7, "Awaiting staff selection"),
    STAFF_SELECTED(8, "Staff member confirmed"),
    SHIFTS_EXTRACTED(9, "Shift cells extracted & inferred"),
    DRAFT_READY(10, "Draft schedule ready for review"),
    SAVE_COMPLETED(11, "Schedule saved & Excel workbook synchronized");

    fun log(details: String? = null) {
        val msg = "[Stage $stepNumber: $title] ${details ?: ""}"
        Log.i("MedicalScheduleTracker", msg)
    }
}
